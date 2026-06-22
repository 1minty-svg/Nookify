package com.nookify.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nookify.backend.dto.FurniturePlacement;
import com.nookify.backend.dto.RoomLayout;
import com.nookify.backend.dto.WallSegment;
import com.nookify.backend.entity.FurnitureModel;
import com.nookify.backend.repository.FurnitureRepository;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Двухпроходной AI-планировщик.
 *
 * Pass 1 (Gemini, быстро) → список прямоугольных зон: [{name, type, x, z, width, depth}]
 *                            Простая задача зонирования, без геометрии.
 *
 * Engine (Java, мгновенно) → LayoutEngineService превращает зоны в WallSegment[]:
 *                             координаты центра каждого сегмента, rotation по нормали внутрь,
 *                             тип EXTERNAL/INTERNAL. Без Gemini, детерминированно.
 *
 * buildWallPlacements (Java) → назначает модели стен детерминированно:
 *                              EXTERNAL → Structure_Wall_Ext_Simple или аналог,
 *                              INTERNAL → Structure_Wall_Int_Simple или аналог,
 *                              одно окно и одна дверь на нужные зоны.
 *                              Gemini стены не трогает вообще.
 *
 * Pass 2 (Gemini) → получает только: описание зон + каталог МЕБЕЛИ (Structure_Wall* отфильтрованы).
 *                   Расставляет мебель внутри зон. Промпт ~5x меньше предыдущей версии.
 *
 * Результат = walls (из Java) + furniture (из Gemini Pass 2).
 */
@Service
public class AiPlannerService {

    private static final String GEMINI_URL_TEMPLATE =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=%s";

    private final String apiKey;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final FurnitureRepository furnitureRepository;
    private final LayoutEngineService layoutEngine;

    public AiPlannerService(
            @Value("${gemini.api.key}") String apiKey,
            @Value("${proxy.host}") String proxyHost,
            @Value("${proxy.port}") int proxyPort,
            @Value("${proxy.user}") String proxyUser,
            @Value("${proxy.pass}") String proxyPass,
            ObjectMapper objectMapper,
            FurnitureRepository furnitureRepository,
            LayoutEngineService layoutEngine) {

        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
        this.furnitureRepository = furnitureRepository;
        this.layoutEngine = layoutEngine;

        Authenticator proxyAuthenticator = (route, response) -> {
            String credential = Credentials.basic(proxyUser, proxyPass);
            return response.request().newBuilder()
                    .header("Proxy-Authorization", credential)
                    .build();
        };

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort)))
                .proxyAuthenticator(proxyAuthenticator)
                .build();
    }

    // =========================================================================
    // Публичный API
    // =========================================================================

    public List<FurniturePlacement> planRoom(String query) {
        try {
            List<FurnitureModel> catalog = furnitureRepository.findAll();
            String bucketUrl = "http://localhost:9000/furniture/";

            // ── Pass 1: Gemini → зоны ─────────────────────────────────────────
            String roomsJson = callGemini(buildRoomLayoutPrompt(query));
            List<RoomLayout> rooms = parseAs(roomsJson, new TypeReference<List<RoomLayout>>() {
            });
            System.out.println("[AiPlannerService] Pass 1 complete: " + rooms.size() + " zones parsed.");
            if (rooms.isEmpty()) {
                System.err.println("[AiPlannerService] Pass 1: empty room list returned by Gemini.");
                return List.of();
            }

            // ── Engine: Java → геометрия сегментов стен ──────────────────────
            List<WallSegment> segments = layoutEngine.buildWallSegments(rooms);

            // ── Java → выбор моделей стен детерминированно ───────────────────
            List<FurniturePlacement> walls = buildWallPlacements(segments, rooms, catalog, bucketUrl);

            // ── Pass 2: Gemini → только мебель (стены скрыты из каталога) ────
            List<FurnitureModel> furnitureCatalog = catalog.stream()
                    .filter(m -> !isWallModel(m.getName()))
                    .collect(Collectors.toList());

            String furnitureJson = callGemini(buildFurniturePlacementPrompt(query, rooms, segments, furnitureCatalog, bucketUrl));
            List<FurniturePlacement> furniture = parseAs(furnitureJson, new TypeReference<List<FurniturePlacement>>() {
            });

            // ── Нормализуем URL мебели от Gemini ─────────────────────────────
            for (FurniturePlacement p : furniture) {
                String raw = p.getModelUrl();
                if (raw != null && !raw.isBlank()) {
                    String filename = raw.contains("/") ? raw.substring(raw.lastIndexOf('/') + 1) : raw;
                    if (!filename.endsWith(".glb")) filename = filename + ".glb";
                    p.setModelUrl(bucketUrl + filename);
                }
            }

            // ── Детерминированный анти-клипинг: убираем пересекающуюся мебель ──
            furniture = resolveFurnitureOverlaps(furniture, furnitureCatalog);

            System.out.println("[AiPlannerService] Pass 2 complete: " + furniture.size() + " furniture items placed.");
            System.out.println("[AiPlannerService] Total scene objects: walls=" + walls.size() + " furniture=" + furniture.size());

            // ── Объединяем: стены + мебель ────────────────────────────────────
            List<FurniturePlacement> result = new ArrayList<>(walls);
            result.addAll(furniture);
            return result;

        } catch (Exception e) {
            System.err.println("[AiPlannerService] Critical error in planRoom: " + e.getMessage());
            e.printStackTrace();
            return List.of();
        }
    }

    // =========================================================================
    // Промпты
    // =========================================================================

    /**
     * Pass 1: только зонирование. Никакой геометрии — Gemini просто делит квартиру
     * на именованные прямоугольники. Быстро, промпт минимальный.
     */
    private String buildRoomLayoutPrompt(String query) {
        return "Create an apartment floor plan as rectangular zones based on this request: \"" + query + "\"\n\n" +
                "RULES:\n" +
                "- The entire apartment fits in a 10×10 m square (X and Z strictly 0.0–10.0).\n" +
                "- Each zone is a rectangle. x, z = bottom-left corner (minimum X and Z values).\n" +
                "- Zones must NOT overlap. Adjacent zones must share edges exactly (no gaps).\n" +
                "- All coordinates must sum correctly: adjacent rooms must have touching edges.\n" +
                "- Required zones (unless user explicitly says otherwise):\n" +
                "  KITCHEN (min 2×2 m), BATHROOM (min 1.5×2 m), plus BEDROOM or LIVING_ROOM.\n" +
                "- Additional allowed types: HALLWAY, STORAGE, OTHER.\n\n" +
                "Return ONLY a valid JSON array with no text, explanation, or markdown fences:\n" +
                "[{\"name\":\"Kitchen\",\"type\":\"KITCHEN\",\"x\":0.0,\"z\":0.0,\"width\":3.0,\"depth\":4.0}, ...]";
    }

    /**
     * Pass 2: только мебель. Стены уже построены кодом и в этот промпт не попадают.
     * Каталог — только мебельные модели (Structure_Wall* отфильтрованы выше в planRoom).
     */
    private String buildFurniturePlacementPrompt(
            String query,
            List<RoomLayout> rooms,
            List<WallSegment> segments,
            List<FurnitureModel> furnitureCatalog,
            String bucketUrl) {

        // EXT wall thickness = 0.5m → inner face offset 0.5m from boundary
        // INT wall thickness = 0.14m → inner face offset 0.14m from boundary
        final double EXT_OFFSET = 0.5;
        final double INT_OFFSET = 0.14;

        // Group segments by roomName for quick lookup
        Map<String, List<WallSegment>> segsByRoom = new java.util.LinkedHashMap<>();
        for (WallSegment seg : segments) {
            segsByRoom.computeIfAbsent(seg.roomName, k -> new ArrayList<>()).add(seg);
        }

        StringBuilder zonesDesc = new StringBuilder();
        for (RoomLayout r : rooms) {
            double x0 = r.getX(), x1 = r.getX() + r.getWidth();
            double z0 = r.getZ(), z1 = r.getZ() + r.getDepth();

            // Базовая safe-зона: ужимаем на толщину внешней стены со всех сторон.
            double safeX0 = x0 + EXT_OFFSET;
            double safeX1 = x1 - EXT_OFFSET;
            double safeZ0 = z0 + EXT_OFFSET;
            double safeZ1 = z1 - EXT_OFFSET;

            // Семантика поворота стены (эталон из LayoutEngineService):
            //   rot=0   → стена у Z-max (высокий Z), лицом -Z
            //   rot=90  → стена у X-max (высокий X), лицом -X
            //   rot=180 → стена у Z-min (низкий Z),  лицом +Z
            //   rot=270 → стена у X-min (низкий X),  лицом +X
            List<WallSegment> roomSegs = segsByRoom.getOrDefault(r.getName(), List.of());
            for (WallSegment seg : roomSegs) {
                if (seg.type == WallSegment.WallType.INTERNAL) {
                    double offset = INT_OFFSET;
                    int rot = (int) seg.rotation;
                    if (rot == 0)   safeZ1 = Math.min(safeZ1, seg.z - offset); // стена у высокого Z
                    if (rot == 180) safeZ0 = Math.max(safeZ0, seg.z + offset); // стена у низкого Z
                    if (rot == 90)  safeX1 = Math.min(safeX1, seg.x - offset); // стена у высокого X
                    if (rot == 270) safeX0 = Math.max(safeX0, seg.x + offset); // стена у низкого X
                }
            }

            // Инфо по сторонам: метка стороны + собственный поворот стены (back_rot).
            Map<Integer, String> sideKind = new java.util.LinkedHashMap<>();
            for (WallSegment seg : roomSegs) {
                int rot = (int) seg.rotation;
                String kind = (seg.type == WallSegment.WallType.EXTERNAL) ? "EXT" : "INT";
                sideKind.merge(rot, kind, (a, b) -> a); // first wins
            }

            StringBuilder wallsInfo = new StringBuilder();
            sideKind.forEach((rot, kind) -> {
                String side;
                switch (rot) {
                    case 0:   side = "Z-max"; break;
                    case 180: side = "Z-min"; break;
                    case 90:  side = "X-max"; break;
                    default:  side = "X-min"; break; // 270
                }
                wallsInfo.append(String.format(" %s(back_rot=%d,%s)", side, rot, kind));
            });

            zonesDesc.append(String.format(
                    "%s (%s): safe_area X[%.2f-%.2f] Z[%.2f-%.2f] |walls:%s\n",
                    r.getName(), r.getType(),
                    safeX0, safeX1, safeZ0, safeZ1,
                    wallsInfo
            ));
        }

        String catalogDesc = furnitureCatalog.stream()
                .map(m -> String.format("%s (W:%.2f D:%.2f H:%.2f)",
                        m.getName(), m.getWidth(), m.getDepth(), m.getHeight()))
                .collect(Collectors.joining("\n"));

        return "Arrange furniture for: \"" + query + "\"\n\n" +
                "ZONES (safe_area already excludes wall thickness - keep every object inside it):\n" +
                zonesDesc + "\n" +
                "CATALOG (W=size along model X, D=along model Z, H=height):\n" + catalogDesc + "\n\n" +
                "COORDINATE SYSTEM:\n" +
                "- Floor plane is X (right) and Z (depth); Y is up. Y is ALWAYS 0.0.\n" +
                "- Every model origin is centered in X/Z and at the floor in Y.\n" +
                "- Every model initially faces +X. 'rotation' is a top-down Y rotation in degrees.\n" +
                "  Facing after rotation:  0 -> +X,  90 -> -Z,  180 -> -X,  270 -> +Z\n\n" +
                "HOW TO FACE A WALL (each wall in |walls gives its back_rot):\n" +
                "- An object standing flat against a wall must face INTO the room.\n" +
                "- Rule: object_rotation = (back_rot + 90) mod 360. Equivalently by wall side:\n" +
                "    against X-min wall -> rotation 0    (faces +X)\n" +
                "    against Z-max wall -> rotation 90   (faces -Z)\n" +
                "    against X-max wall -> rotation 180  (faces -X)\n" +
                "    against Z-min wall -> rotation 270  (faces +Z)\n\n" +
                "NO CLIPPING:\n" +
                "- The whole footprint must stay inside safe_area.\n" +
                "- Against a wall, push the object BACK to the safe_area edge: its center is\n" +
                "  offset from that edge by half of the size facing the wall (use D/2 when the\n" +
                "  object faces +/-X, W/2 when it faces +/-Z, because rotation swaps which side faces the wall).\n" +
                "- Floor objects must never overlap each other or walls.\n" +
                "- EXCEPTION: wall-mounted / table-top items (lamps, shelves, monitors) may share\n" +
                "  X/Z with the wall or table they sit on; their height is baked into the model.\n\n" +
                "FACING A TABLE (chairs, etc.):\n" +
                "- To face target (tx,tz) from (ox,oz): rotation = atan2(-(tz-oz), (tx-ox)) deg, normalized to [0,360).\n" +
                "- Quick cases: chair at X-min of table -> 0; X-max -> 180; Z-min -> 270; Z-max -> 90.\n\n" +
                "OTHER RULES:\n" +
                "- Rugs/carpets go in the dead center of the room, never against a wall.\n" +
                "- Tag every general item with \"tag\":\"furn\".\n\n" +
                "Return ONLY a pure JSON array (no markdown, no prose):\n" +
                "[{\"id\":\"ModelName\",\"model_url\":\"" + bucketUrl + "ModelName.glb\",\"tag\":\"furn\",\"x\":2.5,\"y\":0.0,\"z\":3.5,\"rotation\":90}]";
    }

    // =========================================================================
    // Deterministic wall placement (no Gemini involved)
    // =========================================================================

    /**
     * Assigns wall models to segments from LayoutEngineService.
     *
     * Model selection by segment width:
     *   width=3m → *_Window_01 (ext) or *_Simple_01 (int)  [3m models first]
     *   width=2m → *_Simple_01 / *_Door_01 / *_Window_02
     *   width=1m → *_Simple_02
     *
     * Special rules (one per zone):
     *   EXTERNAL + HALLWAY/LIVING_ROOM zone → one Ext_Door_01 (2m)
     *   EXTERNAL + BEDROOM/LIVING_ROOM/KITCHEN → one Ext_Window (matching width)
     *   INTERNAL → one Int_Door_01 (2m) per zone boundary
     */
    private List<FurniturePlacement> buildWallPlacements(
            List<WallSegment> segments,
            List<RoomLayout> rooms,
            List<FurnitureModel> catalog,
            String bucketUrl) {

        // Build a lookup: prefix → model name, filtered by width
        // Available models indexed by (type, widthMeters):
        //   Ext: Simple_01=2m, Simple_02=1m, Door_01=2m, Window_01=3m, Window_02=2m
        //   Int: Simple_01=2m, Simple_02=1m, Door_01=2m, Arch_01=2m

        // Find models by exact name or prefix, grouped by rounded width
        Map<String, FurnitureModel> byName = new HashMap<>();
        for (FurnitureModel m : catalog) {
            byName.put(m.getName(), m);
        }

        // Zones that need a door / window (one each)
        Set<String> needsExtDoor = new HashSet<>();
        Set<String> needsWindow  = new HashSet<>();
        for (RoomLayout r : rooms) {
            String t = r.getType();
            if ("HALLWAY".equals(t) || "LIVING_ROOM".equals(t)) needsExtDoor.add(r.getName());
            if ("LIVING_ROOM".equals(t) || "BEDROOM".equals(t) || "KITCHEN".equals(t)) needsWindow.add(r.getName());
        }
        if (needsExtDoor.isEmpty() && !rooms.isEmpty()) needsExtDoor.add(rooms.get(0).getName());

        Set<String> extDoorDone = new HashSet<>();
        Set<String> windowDone  = new HashSet<>();
        Set<String> intDoorDone = new HashSet<>();

        List<FurniturePlacement> result = new ArrayList<>();

        for (WallSegment seg : segments) {
            double w = seg.width; // 0.5 / 1 / 2 / 3 — НЕ округлять (Math.round(0.5)=1 = баг)
            String modelId;

            if (seg.type == WallSegment.WallType.EXTERNAL) {
                if (needsExtDoor.contains(seg.roomName) && !extDoorDone.contains(seg.roomName) && Math.abs(w - 2.0) < 0.01) {
                    // External door is 2m wide — only on an exactly-2m segment (иначе дыра)
                    modelId = findModelByName(catalog, "Structure_Wall_Ext_Door_01");
                    extDoorDone.add(seg.roomName);
                } else if (needsWindow.contains(seg.roomName) && !windowDone.contains(seg.roomName) && w >= 1.99) {
                    // Window only on a 2m or 3m segment (есть точные модели Window_02/Window_01)
                    modelId = pickExtWindow(catalog, w);
                    windowDone.add(seg.roomName);
                } else {
                    modelId = pickExtSimple(catalog, w);
                }
            } else { // INTERNAL
                if (!intDoorDone.contains(seg.roomName) && Math.abs(w - 2.0) < 0.01) {
                    modelId = findModelByName(catalog, "Structure_Wall_Int_Door_01");
                    intDoorDone.add(seg.roomName);
                } else {
                    modelId = pickIntSimple(catalog, w);
                }
            }

            if (modelId == null) {
                System.err.println("[AiPlannerService] No wall model found for width=" + w + "m type=" + seg.type + ", skipping segment");
                continue;
            }

            FurniturePlacement p = new FurniturePlacement();
            p.setSlug(modelId);
            p.setModelUrl(bucketUrl + modelId + ".glb");
            p.setX(seg.x);
            p.setY(0.0);
            p.setZ(seg.z);
            p.setRotation(seg.rotation);
            p.setScaleWidth(seg.scaleWidth);
            result.add(p);
        }

        System.out.println("[AiPlannerService] Wall segments placed: " + result.size());
        return result;
    }

    // ── Model pickers ─────────────────────────────────────────────────────────

    /** Exact name lookup first, then fallback to any model with that prefix. */
    private String findModelByName(List<FurnitureModel> catalog, String name) {
        return catalog.stream()
                .map(FurnitureModel::getName)
                .filter(n -> n.equals(name))
                .findFirst()
                .orElseGet(() -> {
                    // fallback: prefix match
                    String prefix = name.substring(0, name.lastIndexOf('_'));
                    return catalog.stream()
                            .map(FurnitureModel::getName)
                            .filter(n -> n.startsWith(prefix))
                            .findFirst()
                            .orElse(null);
                });
    }

    /** Pick external simple wall matching segment width. */
    private String pickExtSimple(List<FurnitureModel> catalog, double widthM) {
        if (widthM < 0.75) { // 0.5m segment
            String m = findModelByName(catalog, "Structure_Wall_Ext_Simple_03");
            if (m != null) return m;
        }
        if (widthM < 1.5) {  // 1m segment
            String m = findModelByName(catalog, "Structure_Wall_Ext_Simple_02");
            if (m != null) return m;
        }
        // 2m (а также 3m — отдельной 3m-simple модели нет, см. примечание)
        return findModelByName(catalog, "Structure_Wall_Ext_Simple_01");
    }

    /** Pick external window matching segment width (3m→Window_01, 2m→Window_02, else simple). */
    private String pickExtWindow(List<FurnitureModel> catalog, double widthM) {
        if (widthM >= 2.5) { // 3m
            String m = findModelByName(catalog, "Structure_Wall_Ext_Window_01");
            if (m != null) return m;
        }
        if (widthM >= 1.5) { // 2m
            String m = findModelByName(catalog, "Structure_Wall_Ext_Window_02");
            if (m != null) return m;
        }
        // 1m / 0.5m segment — окна такой ширины нет, ставим простую стену
        return pickExtSimple(catalog, widthM);
    }

    /** Pick internal simple wall matching segment width. */
    private String pickIntSimple(List<FurnitureModel> catalog, double widthM) {
        if (widthM < 0.75) { // 0.5m segment
            String m = findModelByName(catalog, "Structure_Wall_Int_Simple_03");
            if (m != null) return m;
        }
        if (widthM < 1.5) {  // 1m segment
            String m = findModelByName(catalog, "Structure_Wall_Int_Simple_02");
            if (m != null) return m;
        }
        return findModelByName(catalog, "Structure_Wall_Int_Simple_01");
    }

    // =========================================================================
    // Анти-клипинг мебели (детерминированно, после Gemini)
    // =========================================================================

    /** Имена/слова моделей, которым перекрытие разрешено (настенные/настольные/плоские). */
    private static final String[] OVERLAP_OK = {
            "lamp", "light", "sconce", "shelf", "monitor", "tv", "screen", "computer",
            "laptop", "picture", "painting", "poster", "frame", "clock", "mirror",
            "rug", "carpet", "mat", "vase", "plant", "book", "decor", "curtain"
    };

    private boolean mayOverlap(String name) {
        String n = name.toLowerCase();
        for (String k : OVERLAP_OK) if (n.contains(k)) return true;
        return false;
    }

    private String modelNameFromUrl(String url) {
        if (url == null) return "";
        String f = url.contains("/") ? url.substring(url.lastIndexOf('/') + 1) : url;
        int q = f.indexOf('?');
        if (q >= 0) f = f.substring(0, q);
        if (f.endsWith(".glb")) f = f.substring(0, f.length() - 4);
        return f;
    }

    /** AABB-футпринт {minX, minZ, maxX, maxZ} с учётом поворота (90/270 меняют W↔D). */
    private double[] footprint(FurniturePlacement p, double[] wd) {
        double w = wd[0], d = wd[1];
        int rot = ((int) Math.round(p.getRotation()) % 360 + 360) % 360;
        if (rot == 90 || rot == 270) { double t = w; w = d; d = t; }
        double x = p.getX(), z = p.getZ();
        return new double[]{ x - w / 2, z - d / 2, x + w / 2, z + d / 2 };
    }

    private double overlapArea(double[] a, double[] b) {
        double ox = Math.max(0, Math.min(a[2], b[2]) - Math.max(a[0], b[0]));
        double oz = Math.max(0, Math.min(a[3], b[3]) - Math.max(a[1], b[1]));
        return ox * oz;
    }

    private double area(double[] r) { return (r[2] - r[0]) * (r[3] - r[1]); }

    /**
     * Убирает мебель, которая существенно перекрывает уже принятую.
     * Большие предметы — якоря (ставятся первыми). Декор из OVERLAP_OK пропускается.
     * Порог: перекрытие > 35% площади меньшего из пары.
     */
    private List<FurniturePlacement> resolveFurnitureOverlaps(
            List<FurniturePlacement> furniture, List<FurnitureModel> catalog) {

        Map<String, double[]> dims = new HashMap<>();
        for (FurnitureModel m : catalog) {
            if (m.getWidth() != null && m.getDepth() != null) {
                dims.put(m.getName(), new double[]{ m.getWidth(), m.getDepth() });
            }
        }

        // Сортируем по площади (больше → раньше): крупные предметы получают приоритет.
        List<FurniturePlacement> sorted = new ArrayList<>(furniture);
        sorted.sort((p, q) -> {
            double[] dp = dims.get(modelNameFromUrl(p.getModelUrl()));
            double[] dq = dims.get(modelNameFromUrl(q.getModelUrl()));
            double ap = (dp == null) ? 0 : dp[0] * dp[1];
            double aq = (dq == null) ? 0 : dq[0] * dq[1];
            return Double.compare(aq, ap);
        });

        List<FurniturePlacement> kept = new ArrayList<>();
        int dropped = 0;
        for (FurniturePlacement p : sorted) {
            String name = modelNameFromUrl(p.getModelUrl());
            double[] d = dims.get(name);
            if (d == null || mayOverlap(name)) { kept.add(p); continue; }

            double[] rp = footprint(p, d);
            double areaP = area(rp);
            boolean clash = false;
            for (FurniturePlacement q : kept) {
                String qn = modelNameFromUrl(q.getModelUrl());
                double[] qd = dims.get(qn);
                if (qd == null || mayOverlap(qn)) continue;
                double[] rq = footprint(q, qd);
                double ov = overlapArea(rp, rq);
                if (ov > 0.35 * Math.min(areaP, area(rq))) { clash = true; break; }
            }
            if (clash) {
                dropped++;
                System.out.println("[Overlap] dropped overlapping: " + name);
            } else {
                kept.add(p);
            }
        }
        System.out.println("[AiPlannerService] Overlap resolve: kept=" + kept.size() + " dropped=" + dropped);
        return kept;
    }

    /**
     * Определяет, является ли модель стеной (не должна попадать в каталог мебели для Pass 2).
     */
    private boolean isWallModel(String name) {
        return name.startsWith("Structure_Wall")
                || name.startsWith("Wall_Ext_")
                || name.startsWith("Wall_Int_");
    }

    // =========================================================================
    // Вызов Gemini
    // =========================================================================

    private String callGemini(String promptText) throws Exception {
        int maxRetries = 3;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                String result = doCallGemini(promptText);
                // Validate it's at least plausible JSON before returning
                String trimmed = result.trim();
                if (!trimmed.startsWith("[") && !trimmed.startsWith("{")) {
                    System.err.println("[AiPlannerService] Attempt " + attempt + ": Gemini response doesn't look like JSON, retrying... Got: " + trimmed.substring(0, Math.min(100, trimmed.length())));
                    if (attempt < maxRetries) continue;
                }
                return result;
            } catch (Exception e) {
                lastException = e;
                System.err.println("[AiPlannerService] Attempt " + attempt + " failed: " + e.getMessage());
                if (attempt < maxRetries) {
                    Thread.sleep(1000L * attempt); // back-off: 1s, 2s
                }
            }
        }
        throw (lastException != null) ? lastException : new RuntimeException("All Gemini retries exhausted");
    }

    private String doCallGemini(String promptText) throws Exception {
        String url = String.format(GEMINI_URL_TEMPLATE, apiKey.trim());

        Map<String, Object> requestBodyMap = new HashMap<>();
        requestBodyMap.put("contents", List.of(
                Map.of("parts", List.of(Map.of("text", promptText)))
        ));
        // thinkingBudget: 0 — отключает internal reasoning у Gemini 2.5 Flash.
        // Для задач зонирования и расстановки мебели reasoning не нужен,
        // а время ответа падает с ~2-3 мин до ~10-15 сек.
        requestBodyMap.put("generationConfig", Map.of(
                "responseMimeType", "application/json",
                "thinkingConfig", Map.of("thinkingBudget", 0)
        ));

        String jsonRequest = objectMapper.writeValueAsString(requestBodyMap);
        RequestBody body = RequestBody.create(jsonRequest, MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder().url(url).post(body).build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseString = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                System.err.println("[AiPlannerService] Gemini HTTP error " + response.code() + ": " + responseString);
                return "[]";
            }

            JsonNode root = objectMapper.readTree(responseString);
            // Gemini response structure:
            // candidates[0].content.parts[0].text  (content is an OBJECT, not an array)
            JsonNode candidate = root.path("candidates").path(0);
            JsonNode contentNode = candidate.path("content");

            // Detect blocked/empty response
            String finishReason = candidate.path("finishReason").asText("");
            if ("SAFETY".equals(finishReason) || "RECITATION".equals(finishReason)) {
                System.err.println("[AiPlannerService] Gemini blocked response, finishReason=" + finishReason);
                return "[]";
            }

            String text = contentNode.path("parts").path(0).path("text").asText("");

            if (text.isBlank()) {
                System.err.println("[AiPlannerService] Empty text in Gemini response. Full response: " + responseString);
                return "[]";
            }

            return stripMarkdownFences(text.trim());
        }
    } // end doCallGemini

    private String stripMarkdownFences(String text) {
        // Remove opening fence: ```json or ``` etc.
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastTicks = text.lastIndexOf("```");
            if (firstNewline != -1 && lastTicks > firstNewline) {
                text = text.substring(firstNewline, lastTicks).trim();
            } else if (firstNewline != -1) {
                text = text.substring(firstNewline).trim();
            }
        }
        // Trim any trailing text after the final ] or }
        if (!text.isEmpty()) {
            int lastBracket = Math.max(text.lastIndexOf(']'), text.lastIndexOf('}'));
            if (lastBracket != -1 && lastBracket < text.length() - 1) {
                text = text.substring(0, lastBracket + 1).trim();
            }
        }
        return text.isEmpty() ? "[]" : text;
    }

    // =========================================================================
    // Парсинг JSON
    // =========================================================================

    private <T> T parseAs(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            System.err.println("[AiPlannerService] JSON parse error: " + e.getMessage());
            System.err.println("[AiPlannerService] Raw JSON that failed to parse: " + json);
            try {
                return objectMapper.readValue("[]", type);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
    }
}