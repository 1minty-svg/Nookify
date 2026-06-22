package com.nookify.backend.controller;

import com.nookify.backend.dto.FurniturePlacement;
import com.nookify.backend.service.AiPlannerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/furniture")
public class FurnitureController {

    @Autowired
    private AiPlannerService aiPlannerService;

    /**
     * POST /api/furniture/plan?query=...
     *
     * Возвращает единый список объектов для сцены:
     *  - стены (построены LayoutEngineService детерминированно)
     *  - мебель (расставлена Gemini вторым проходом)
     *
     * Фронтенд получает тот же формат FurniturePlacement[], что и раньше — ничего не меняется.
     */
    @PostMapping("/plan")
    public List<FurniturePlacement> plan(@RequestParam String query) {
        return aiPlannerService.planRoom(query);
    }
}
