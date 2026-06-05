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
                    .map(m -> String.format("- %s (Категория: %s, Размеры ШxВxГ: %.2f x %.2f x %.2f м)",
                            m.getName(), m.getCategory(), m.getWidth(), m.getHeight(), m.getDepth()))
                    .collect(Collectors.joining("\n"));

            String promptText = "Ты — ИИ-архитектор Nookify. Твоя задача: спроектировать интерьер квадратной комнаты размером строго 6.0 x 6.0 метров по запросу пользователя: \"" + query + "\".\n\n"
                    + "КРИТИЧЕСКИ ВАЖНЫЕ ПРАВИЛА КООРДИНАТ И ВЫРАВНИВАНИЯ:\n"
                    + "1. Координаты \"x\", \"y\", \"z\" — это координаты точки origin модели. Для мебели на полу отдавай y = 0.\n"
                    + "2. Вся геометрия объекта от точки (x, y, z) распространяется в положительном направлении осей.\n"
                    + "3. Границы сцены: 6.0 на 6.0 метров.\n\n"
                    + "ПРАВИЛА ИСПОЛЬЗОВАНИЯ МОДЕЛЕЙ:\n"
                    + "- ОДИН И ТОТ ЖЕ ПРОП МОЖНО ИСПОЛЬЗОВАТЬ МНОГОКРАТНО (например, несколько стен).\n"
                    + "- Разрешено использовать ТОЛЬКО модели из предоставленного ниже списка.\n\n"
                    + "КАТАЛОГ ДОСТУПНОЙ МЕБЕЛИ В БАЗЕ ДАННЫХ:\n"
                    + catalogContext + "\n\n"
                    + "Верни СТРОГО JSON массив объектов (без лишнего текста). Каждый объект должен содержать поля:\n"
                    + "\"id\" (текстовое название модели),\n"
                    + "\"model_url\" (строка: '" + bucketUrl + "' + id + '.glb'),\n"
                    + "\"x\", \"y\", \"z\" (координаты origin в метрах),\n"
                    + "\"rotation\" (угол поворота вокруг Y в градусах, от 0 до 360),\n"
                    + "\"width\", \"height\", \"depth\" (размеры модели из каталога)."; // <-- Добавили явный запрос размеров для фронта

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

                    // Пуленепробиваемый стрипинг markdown, если Gemini пришлет блок ```json ... ```
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