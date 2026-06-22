package com.nookify.backend.service;

import com.nookify.backend.dto.RoomLayout;
import com.nookify.backend.dto.WallSegment;
import com.nookify.backend.dto.WallSegment.WallType;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Deterministic geometry engine — v3.
 *
 * Key changes vs v2:
 * ─────────────────
 * 1. INT wall extent clamped to apartment bbox.
 *    effA = clamp(a − HALF_EXT, aptMinCoord, aptMaxCoord)
 *    effB = clamp(b + HALF_EXT, aptMinCoord, aptMaxCoord)
 *    Eliminates wall segments sticking out past the EXT boundary.
 *
 * 2. Apartment bounding box (aptMinX/Z, aptMaxX/Z) computed once from all
 *    snapped rooms and passed into every packInterval call.
 *
 * 3. Tile sizes: 2m / 1m / 0.5m — greedy largest-first.
 *    (3m тайлы убраны: 3-метровой simple-модели нет, она давала дыру 1м.)
 *    Sub-0.5m remainder (float noise after 0.1m snap) absorbed into the
 *    last 0.5m tile via scaleWidth < 1.0.
 *
 * 4. No scaleWidth = 2 stretch hack.
 *
 * 5. Sub-interval noise filter: segments shorter than MIN_INTERVAL (0.5m)
 *    are discarded before type classification, preventing float-noise
 *    slivers from being read as a second EXTERNAL interval.
 *
 * Frontend note (app.js):
 *   scaleWidth сжимает стену вдоль её длины. Длина модели всегда лежит по
 *   ЛОКАЛЬНОМУ X, а scale применяется до поворота — поэтому на фронте всегда
 *   model.scale.x = scaleWidth (для ЛЮБОГО rotation). Скейлить scale.z нельзя:
 *   у вертикальных стен это сжимает толщину, а не длину.
 */
@Service
public class LayoutEngineService {

    private static final double SNAP       = 0.1;
    // Порог отсева float-шума. Координаты снэпаются на 0.1, поэтому настоящие
    // интервалы кратны 0.1, а шум имеет порядок 1e-16. 0.05 режет только шум и
    // НЕ выкидывает реальные короткие куски стен (их закрывает 0.5-модель со scaleWidth).
    private static final double MIN_INTERVAL = 0.05;

    private static final double DEPTH_EXT = 0.5;
    private static final double DEPTH_INT = 0.14;
    private static final double HALF_EXT  = DEPTH_EXT / 2.0;  // 0.25
    private static final double HALF_INT  = DEPTH_INT / 2.0;  // 0.07

    private static final double EPS = 1e-6;

    // =========================================================================
    // Public API
    // =========================================================================

    public List<WallSegment> buildWallSegments(List<RoomLayout> rooms) {
        List<RoomLayout> snapped = snapRooms(rooms);

        // ── Apartment bounding box ──────────────────────────────────────────
        double aptMinX = snapped.stream().mapToDouble(RoomLayout::getX).min().orElse(0.0);
        double aptMinZ = snapped.stream().mapToDouble(RoomLayout::getZ).min().orElse(0.0);
        double aptMaxX = snapped.stream().mapToDouble(r -> r.getX() + r.getWidth()).max().orElse(10.0);
        double aptMaxZ = snapped.stream().mapToDouble(r -> r.getZ() + r.getDepth()).max().orElse(10.0);

        // ── Centroid for rotation direction ────────────────────────────────
        double centerX = (aptMinX + aptMaxX) / 2.0;
        double centerZ = (aptMinZ + aptMaxZ) / 2.0;

        System.out.printf("[LayoutEngine] BBox: X=[%.2f,%.2f] Z=[%.2f,%.2f] Center=(%.2f,%.2f)%n",
                aptMinX, aptMaxX, aptMinZ, aptMaxZ, centerX, centerZ);

        // ── Collect raw intervals per grid line ────────────────────────────
        Map<String, List<Interval>> lineMap = new LinkedHashMap<>();
        for (RoomLayout room : snapped) {
            double x0 = room.getX(), z0 = room.getZ();
            double x1 = x0 + room.getWidth(), z1 = z0 + room.getDepth();
            String name = room.getName();
            addInterval(lineMap, hKey(z0), x0, x1, name);
            addInterval(lineMap, hKey(z1), x0, x1, name);
            addInterval(lineMap, vKey(x0), z0, z1, name);
            addInterval(lineMap, vKey(x1), z0, z1, name);
        }

        List<WallSegment> result = new ArrayList<>();
        int extCount = 0, intCount = 0;

        for (Map.Entry<String, List<Interval>> entry : lineMap.entrySet()) {
            String lineKey = entry.getKey();
            List<Interval> intervals = entry.getValue();
            boolean isH = lineKey.startsWith("H:");
            double lineCoord = Double.parseDouble(lineKey.substring(2));

            // Sorted breakpoints on this line
            TreeSet<Double> pts = new TreeSet<>();
            for (Interval iv : intervals) { pts.add(iv.start); pts.add(iv.end); }
            List<Double> ptList = new ArrayList<>(pts);

            for (int i = 0; i < ptList.size() - 1; i++) {
                double a = ptList.get(i), b = ptList.get(i + 1);

                // Discard float-noise slivers (< MIN_INTERVAL)
                if (b - a < MIN_INTERVAL - EPS) continue;

                // Coverage at midpoint
                double mid = (a + b) / 2;
                int cover = 0;
                String roomName = null;
                for (Interval iv : intervals) {
                    if (iv.start <= mid + EPS && iv.end >= mid - EPS) {
                        cover++;
                        if (roomName == null) roomName = iv.roomName;
                    }
                }
                if (cover == 0 || roomName == null) continue;

                WallType type = (cover == 1) ? WallType.EXTERNAL : WallType.INTERNAL;
                if (type == WallType.EXTERNAL) extCount++; else intCount++;

                double rotation = computeRotation(isH, lineCoord, centerX, centerZ);

                // ── INT walls: extend to meet EXT inner face, then clamp ──
                double effA = a, effB = b;
                if (type == WallType.INTERNAL) {
                    effA = a - HALF_EXT;
                    effB = b + HALF_EXT;
                    // Clamp to apartment boundary so wall never exits the floor plan
                    if (isH) {
                        effA = Math.max(effA, aptMinX);
                        effB = Math.min(effB, aptMaxX);
                    } else {
                        effA = Math.max(effA, aptMinZ);
                        effB = Math.min(effB, aptMaxZ);
                    }
                }

                // Skip degenerate after clamp
                if (effB - effA < EPS) continue;

                result.addAll(packInterval(effA, effB, lineCoord, isH, rotation, type, roomName));
            }
        }

        System.out.println("[LayoutEngine] Sub-intervals: EXTERNAL=" + extCount + " INTERNAL=" + intCount);
        System.out.println("[LayoutEngine] Total wall segments: " + result.size());
        return result;
    }

    // =========================================================================
    // Packing — greedy tiling, no stretch hack
    // =========================================================================

    private List<WallSegment> packInterval(
            double a, double b,
            double lineCoord, boolean isHorizontal,
            double rotation, WallType type, String roomName) {

        double halfDepth = (type == WallType.EXTERNAL ? DEPTH_EXT : DEPTH_INT) / 2.0;

        // Shift center inward so outer face aligns with room edge
        double shiftX = 0, shiftZ = 0;
        switch ((int) rotation) {
            case 270: shiftX = +halfDepth; break;
            case  90: shiftX = -halfDepth; break;
            case 180: shiftZ = +halfDepth; break;
            default:  shiftZ = -halfDepth; break;  // 0°
        }

        List<WallSegment> segments = new ArrayList<>();
        double cursor = a;

        while (b - cursor > EPS) {
            double remaining = b - cursor;
            double w;
            double scaleWidth = 1.0;

            // Максимальный тайл — 2 м. 3-метровой simple-модели нет, поэтому
            // 3-метровый тайл рендерился бы 2-метровой моделью → дыра 1 м.
            // Под 2/1/0.5 есть точные модели (_Simple_01/_02/_03).
            if      (remaining >= 2.0 - EPS) { w = 2.0; }
            else if (remaining >= 1.0 - EPS) { w = 1.0; }
            else if (remaining >= 0.5 - EPS) { w = 0.5; }
            else {
                // Tiny remainder after 0.5m snap — scale a 0.5m tile to fit
                w = 0.5;
                scaleWidth = remaining / 0.5;
            }

            double placedLen = (scaleWidth < 1.0 - EPS) ? remaining : w;
            double mid = cursor + placedLen / 2.0;

            double cx = (isHorizontal ? mid       : lineCoord) + shiftX;
            double cz = (isHorizontal ? lineCoord : mid)       + shiftZ;

            segments.add(new WallSegment(cx, cz, rotation, type, roomName, w, scaleWidth));
            cursor += placedLen;

            System.out.printf("[Pack] type=%-8s a=%.3f b=%.3f cursor=%.3f w=%.1f scale=%.3f cx=%.3f cz=%.3f%n",
                    type, a, b, cursor, w, scaleWidth, cx, cz);
        }

        return segments;
    }

    // =========================================================================
    // Rotation — face points inward
    // =========================================================================

    private double computeRotation(boolean isHorizontal, double lineCoord,
                                   double centerX, double centerZ) {
        if (isHorizontal) {
            return (lineCoord < centerZ - EPS) ? 180.0 : 0.0;
        } else {
            return (lineCoord < centerX - EPS) ? 270.0 : 90.0;
        }
    }

    // =========================================================================
    // Snapping
    // =========================================================================

    private List<RoomLayout> snapRooms(List<RoomLayout> rooms) {
        List<RoomLayout> out = new ArrayList<>();
        for (RoomLayout r : rooms) {
            RoomLayout s = new RoomLayout();
            s.setName(r.getName());
            s.setType(r.getType());
            double x0 = snap(r.getX()),                  z0 = snap(r.getZ());
            double x1 = snap(r.getX() + r.getWidth()),   z1 = snap(r.getZ() + r.getDepth());
            s.setX(x0);  s.setZ(z0);
            s.setWidth(Math.max(1.0, x1 - x0));
            s.setDepth(Math.max(1.0, z1 - z0));
            out.add(s);
        }
        return out;
    }

    private double snap(double v) { return Math.round(v / SNAP) * SNAP; }

    private String hKey(double z) { return "H:" + snapStr(z); }
    private String vKey(double x) { return "V:" + snapStr(x); }
    private String snapStr(double v) { return String.valueOf(Math.round(v / SNAP) * SNAP / 1.0); }

    private void addInterval(Map<String, List<Interval>> map, String key,
                             double start, double end, String roomName) {
        map.computeIfAbsent(key, k -> new ArrayList<>())
                .add(new Interval(Math.min(start, end), Math.max(start, end), roomName));
    }

    private static class Interval {
        final double start, end;
        final String roomName;
        Interval(double start, double end, String roomName) {
            this.start = start; this.end = end; this.roomName = roomName;
        }
    }
}