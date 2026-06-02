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
    private final FurnitureRepository furnitureRepository; // Добавляем доступ к БД

    public AiPlannerService(
            @Value("${gemini.api.key}") String apiKey,
            @Value("${proxy.host}") String proxyHost,
            @Value("${proxy.port}") int proxyPort,
            @Value("${proxy.user}") String proxyUser,
            @Value("${proxy.pass}") String proxyPass,
            ObjectMapper objectMapper,
            FurnitureRepository furnitureRepository) { // Spring сам инжектит репозиторий

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
        String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" + apiKey.trim();
        String bucketUrl = "http://localhost:9000/furniture/";

        try {
            // 1. Достаем каталог из базы данных
            List<FurnitureModel> allModels = furnitureRepository.findAll();

            // Формируем текстовое описание всех моделей для нейросети
            String catalogContext = allModels.stream()
                    .map(m -> String.format("- %s (Категория: %s, Размеры ШxВxГ: %.2f x %.2f x %.2f м)",
                            m.getName(), m.getCategory(), m.getWidth(), m.getHeight(), m.getDepth()))
                    .collect(Collectors.joining("\n"));

            // 2. ОБНОВЛЕННЫЙ ПРОМПТ С УЧЕТОМ СДВИГА PIVOT POINT И МНОГОКРАТНОГО ИСПОЛЬЗОВАНИЯ
            String promptText = "Ты — ИИ-архитектор Nookify. Твоя задача: спроектировать интерьер квадратной комнаты размером строго 6.0 x 6.0 метров по запросу пользователя: \"" + query + "\".\n\n"
                    + "КРИТИЧЕСКИ ВАЖНЫЕ ПРАВИЛА КООРДИНАТ И ВЫРАВНИВАНИЯ:\n"
                    + "1. Возвращаемые координаты \"x\", \"y\", \"z\" — это координаты ТОЧКИ ORIGIN (пивота), которая находится строго в ближнем левом нижнем углу ограничивающего бокса (bounding box) модели, а НЕ в её центре!\n"
                    + "2. Вся геометрия объекта от точки (x, y, z) распространяется СТРОГО в положительном направлении всех координатных осей (+X, +Y, +Z). Объект занимает пространство от x до (x + width), от y до (y + height) и от z до (z + depth).\n"
                    + "3. Направление: По умолчанию (при rotation = 0) объект ориентирован (смотрит лицом) в сторону положительного направления оси Y.\n"
                    + "4. Границы сцены: Комната имеет размер 6.0 на 6.0 метров. Ни один объект с учетом его ширины и глубины не должен выходить за рамки координат от 0.0 до 6.0 по осям плоскости пола.\n\n"
                    + "ПРАВИЛА ИСПОЛЬЗОВАНИЯ МОДЕЛЕЙ:\n"
                    + "- ОДИН И ТОТ ЖЕ ПРОП МОЖНО ИСПОЛЬЗОВАТЬ МНОГОКРАТНО. Чтобы построить стены комнаты, добавь несколько объектов стен (например, четыре модели Wall_Ext_Simple_01 или Wall_Ext_Window_03), правильно развернув их через параметр rotation и состыковав по углам периметра (0.0 - 6.0 метров).\n"
                    + "- Разрешено использовать ТОЛЬКО модели из предоставленного ниже списка. Придумывать новые названия id запрещено.\n\n"
                    + "КАТАЛОГ ДОСТУПНОЙ МЕБЕЛИ В БАЗЕ ДАННЫХ:\n"
                    + catalogContext + "\n\n"
                    + "Верни СТРОГО JSON массив объектов (без какого-либо лишнего текста). Каждый объект должен содержать поля:\n"
                    + "\"id\" (точное текстовое название модели из каталога),\n"
                    + "\"model_url\" (строка: '" + bucketUrl + "' + id + '.glb'),\n"
                    + "\"x\", \"y\", \"z\" (координаты точки origin объекта в метрах),\n"
                    + "\"rotation\" (угол поворота вокруг вертикальной оси в градусах, от 0 до 360).";

            Map<String, Object> requestBodyMap = new HashMap<>();
            requestBodyMap.put("contents", List.of(
                    Map.of("parts", List.of(
                            Map.of("text", promptText)
                    ))
            ));
            requestBodyMap.put("generationConfig", Map.of(
                    "responseMimeType", "application/json"
            ));

            String jsonRequest = objectMapper.writeValueAsString(requestBodyMap);
            RequestBody body = RequestBody.create(jsonRequest, MediaType.get("application/json; charset=utf-8"));
            Request request = new Request.Builder().url(url).post(body).build();

            try (Response response = client.newCall(request).execute()) {
                String responseString = response.body() != null ? response.body().string() : "";

                if (response.isSuccessful()) {
                    JsonNode rootNode = objectMapper.readTree(responseString);
                    String generatedJsonText = rootNode.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText();
                    return generatedJsonText;
                } else {
                    System.err.println("GOOGLE API ERROR CODE: " + response.code());
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