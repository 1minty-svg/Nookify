package com.nookify.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nookify.backend.entity.FurnitureModel;
import com.nookify.backend.repository.FurnitureRepository;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class AiPlannerService {

    private final String apiKey;
    private final OkHttpClient client;
    private final ObjectMapper objectMapper;
    private final FurnitureRepository furnitureRepository;

    public AiPlannerService(
            @Value("${gemini.api.key}") String apiKey,
            @Value("${proxy.host}") String proxyHost,
            @Value("${proxy.port}") int proxyPort,
            @Value("${proxy.user}") String proxyUser,
            @Value("${proxy.pass}") String proxyPass,
            ObjectMapper objectMapper,
            FurnitureRepository furnitureRepository) {

        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
        this.furnitureRepository = furnitureRepository;

        Authenticator proxyAuthenticator = (route, response) -> {
            String credential = Credentials.basic(proxyUser, proxyPass);
            return response.request().newBuilder()
                    .header("Proxy-Authorization", credential)
                    .build();
        };

        this.client = new OkHttpClient.Builder()
                .connectTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort)))
                .proxyAuthenticator(proxyAuthenticator)
                .build();
    }

    public String planRoom(String query) {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" + this.apiKey.trim();
        String bucketUrl = "http://localhost:9000/furniture/";

        try {
            List<FurnitureModel> allModels = furnitureRepository.findAll();

            String catalogContext = allModels.stream()
                    .map(m -> String.format("- %s (Размеры ШxВxГ: %.2f x %.2f x %.2f м)",
                            m.getName(), m.getWidth(), m.getHeight(), m.getDepth()))
                    .collect(Collectors.joining("\n"));

            String promptText = "Ты — ИИ-архитектор Nookify. Спроектируй интерьер пространства 10.0 × 10.0 метров по запросу: \"" + query + "\".\n\n"
                    + "СИСТЕМА КООРДИНАТ:\n"
                    + "- Правосторонняя система координат. X и Z — плоскость пола (границы: 0.0–10.0). Y — высота (Y=0 — уровень пола).\n"
                    + "- Вращение вокруг оси Y в градусах. При взгляде сверху — против часовой стрелки при увеличении угла.\n"
                    + "- Все модели по умолчанию смотрят лицом в сторону +X. Используй rotation, чтобы направить лицо куда нужно.\n\n"
                    + "ORIGIN-ТОЧКИ МОДЕЛЕЙ:\n\n"
                    + "Абсолютно все модели имеют origin на полу (Y=0) по центру объекта (по X и Z).\n\n"
                    + "Исключение 1: навесные шкафы (kitchen_wall_unit) — origin тоже на полу, Y=0. Встанут на нужную высоту автоматически. Их X и Z могут совпадать с напольным шкафом под ними.\n\n"
                    + "Исключение 2: угловые модули (kitchen_base_unit_corner, wall_ext_corner) — origin в центре угла (точка стыка), геометрия уходит в +X и +Z.\n\n"
                    + "   - Одна наружняя стена: 3 полноценные + угловая слева и справа (3 стены по 3 метра + 2 уголка по 0.5 метра, итого ровно 10 метров)\n"
                    + "   - Используй внутренние стены (Structure_Wall_Int_Simple_01) для перегородок между комнатами\n\n"
                    + "ВРАЩЕНИЕ — ОРИЕНТИРЫ:\n\n"
                    + "Стены по периметру (лицевая сторона смотрит внутрь комнаты):\n"
                    + "   - Верхняя стена (вдоль Z=0): rotation = 180\n" // 0 180 270 90
                    + "   - Нижняя стена (вдоль Z=10): rotation = 0\n"
                    + "   - Левая стена (вдоль X=0): rotation = 270\n"
                    + "   - Правая стена (вдоль X=10): rotation = 90\n\n"
                    + "Фурнитура у стен (лицо смотрит в центр комнаты - не в стенку):\n"
                    + "   - У левой стены (X≈0): rotation = 270\n"
                    + "   - У правой стены (X≈10): rotation = 90\n"
                    + "   - У верхней стены (Z≈0): rotation = 0\n"
                    + "   - У нижней стены (Z≈10): rotation = 180\n\n"
                    + "Стулья у обеденного стола (лицо смотрит к столу):\n"
                    + "   - Стул левее стола: rotation = 90\n"
                    + "   - Стул правее стола: rotation = 270\n"
                    + "   - Стул с меньшим Z чем стол: rotation = 180\n"
                    + "   - Стул с большим Z чем стол: rotation = 0\n\n"
                    + "КАТАЛОГ МОДЕЛЕЙ:\n"
                    + catalogContext + "\n\n"
                    + "Верни СТРОГО JSON-массив без лишнего текста. Структура каждого объекта:\n"
                    + "{\"id\": \"название\", \"model_url\": \"" + bucketUrl + "\"+id+\".glb\", \"x\": число, \"y\": 0.0, \"z\": число, \"rotation\": число, \"width\": число, \"height\": число, \"depth\": число}";
            Map<String, Object> requestBodyMap = new HashMap<>();
            requestBodyMap.put("contents", List.of(
                    Map.of("parts", List.of(Map.of("text", promptText)))
            ));
            requestBodyMap.put("generationConfig", Map.of("responseMimeType", "application/json"));

            String jsonRequest = objectMapper.writeValueAsString(requestBodyMap);
            RequestBody body = RequestBody.create(jsonRequest, MediaType.get("application/json; charset=utf-8"));
            Request request = new Request.Builder().url(url).post(body).build();

            try (Response response = client.newCall(request).execute()) {
                String responseString = response.body() != null ? response.body().string() : "";

                if (response.isSuccessful()) {
                    JsonNode rootNode = objectMapper.readTree(responseString);
                    String generatedJsonText = rootNode.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText();

                    generatedJsonText = generatedJsonText.trim();
                    if (generatedJsonText.startsWith("```")) {
                        int firstNewline = generatedJsonText.indexOf('\n');
                        int lastTicks = generatedJsonText.lastIndexOf("```");
                        if (firstNewline != -1 && lastTicks != -1 && lastTicks > firstNewline) {
                            generatedJsonText = generatedJsonText.substring(firstNewline, lastTicks).trim();
                        }
                    }
                    return generatedJsonText.isEmpty() ? "[]" : generatedJsonText;
                } else {
                    System.err.println("GOOGLE API ERROR CODE: " + response.code() + " Body: " + responseString);
                    return "[]";
                }
            }
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
            return "[]";
        }
    }
}