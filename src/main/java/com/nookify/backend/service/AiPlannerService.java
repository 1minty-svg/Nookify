package com.nookify.backend.service;

import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.concurrent.TimeUnit;

@Service
public class AiPlannerService {

    private final String apiKey;
    private final OkHttpClient client;

    // Spring сам найдет эти значения в application.properties и подставит сюда
    public AiPlannerService(
            @Value("${gemini.api.key}") String apiKey,
            @Value("${proxy.host}") String proxyHost,
            @Value("${proxy.port}") int proxyPort,
            @Value("${proxy.user}") String proxyUser,
            @Value("${proxy.pass}") String proxyPass) {

        this.apiKey = apiKey;

        Authenticator proxyAuthenticator = (route, response) -> {
            String credential = Credentials.basic(proxyUser, proxyPass);
            return response.request().newBuilder()
                    .header("Proxy-Authorization", credential)
                    .build();
        };

        this.client = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort)))
                .proxyAuthenticator(proxyAuthenticator)
                .build();
    }

    public String planRoom(String query) {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" + apiKey.trim();

        String bucketUrl = "http://localhost:9000/furniture/";

        String jsonRequest = "{"
                + "\"contents\": [{"
                + "  \"parts\": [{"
                + "    \"text\": \"Ты планировщик Nookify. Расставь мебель: " + query + ". "
                + "ВАЖНО: Поле \\\"id\\\" должно строго соответствовать именам файлов в хранилище (например, Bed_01, Chair_01). "
                + "Верни JSON массив объектов с полями: "
                + "\\\"id\\\", "
                + "\\\"model_url\\\" (строка: '" + bucketUrl + "' + id + '.glb'), "
                + "\\\"x\\\", \\\"y\\\", \\\"z\\\", \\\"rotation\\\". "
                + "Только чистый JSON массив.\""
                + "  }]"
                + "}]"
                + "}";

        RequestBody body = RequestBody.create(
                jsonRequest,
                MediaType.get("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            // ПЕЧАТАЕМ ВООБЩЕ ВСЁ, ЧТО ПРИШЛО
            System.out.println("--- RAW RESPONSE FROM GOOGLE ---");
            System.out.println(responseBody);
            System.out.println("--------------------------------");

            if (response.isSuccessful()) {
                // 1. Достаем всё, что внутри самого первого "text": "..."
                String searchStr = "\"text\": \"";
                int textStart = responseBody.indexOf(searchStr);

                if (textStart != -1) {
                    textStart += searchStr.length();
                    // Находим конец строки с текстом (закрывающая кавычка)
                    int textEnd = responseBody.lastIndexOf("\"");

                    String rawText = responseBody.substring(textStart, textEnd);

                    // 2. Чистим экранирование, которое наворотил Google (важно!)
                    String unescapedText = rawText
                            .replace("\\n", "\n")
                            .replace("\\\"", "\"")
                            .replace("\\\\", "\\");

                    // 3. Теперь в ЭТОМ чистом тексте ищем массив мебели [ ... ]
                    int firstBracket = unescapedText.indexOf("[");
                    int lastBracket = unescapedText.lastIndexOf("]");

                    if (firstBracket != -1 && lastBracket != -1 && lastBracket > firstBracket) {
                        // Вырезаем строго от [ до ]
                        String finalJson = unescapedText.substring(firstBracket, lastBracket + 1).trim();

                        System.out.println("--- STERILE JSON FOR JACKSON ---");
                        System.out.println(finalJson);
                        System.out.println("--------------------------------");

                        return finalJson;
                    }
                }
                System.err.println("Could not extract furniture array from: " + responseBody);
                return "[]";

            } else {
                System.err.println("GOOGLE API ERROR CODE: " + response.code());
                System.err.println("ERROR BODY: " + responseBody);
                return "[]";
            }
        } catch (IOException e) {
            System.err.println("NETWORK/PROXY ERROR: " + e.getMessage());
            e.printStackTrace();
            return "[]";
        }
    }
}