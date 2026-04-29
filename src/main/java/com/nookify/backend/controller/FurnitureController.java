package com.nookify.backend.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nookify.backend.dto.FurniturePlacement; // Проверь этот путь!
import com.nookify.backend.service.AiPlannerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List; // Обязательно!

@RestController
@RequestMapping("/api/furniture")
public class FurnitureController {

    @Autowired
    private AiPlannerService aiPlannerService;

    @Autowired
    private ObjectMapper objectMapper; // Spring сам его создаст

    @PostMapping("/plan")
    public List<FurniturePlacement> plan(@RequestParam String query) {
        try {
            // 1. Получаем сырой JSON-ответ (String) от нашего сервиса через прокси
            String rawJson = aiPlannerService.planRoom(query);

            // 2. Парсим этот JSON в список объектов FurniturePlacement
            // (Если Gemini вернет полный ответ с метаданными, нужно будет сначала достать текст)
            return objectMapper.readValue(rawJson, new TypeReference<List<FurniturePlacement>>() {});

        } catch (Exception e) {
            e.printStackTrace();
            // В случае ошибки возвращаем пустой список (или можно пробросить ошибку дальше)
            return List.of();
        }
    }
}