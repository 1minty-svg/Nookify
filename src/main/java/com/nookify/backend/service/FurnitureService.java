package com.nookify.backend.service;

import com.nookify.backend.entity.FurnitureModel;
import com.nookify.backend.repository.FurnitureRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class FurnitureService {

    private final FurnitureRepository repository;

    // Метод для сохранения новой модели (например, из Blender)
    public FurnitureModel saveModel(FurnitureModel model) {
        return repository.save(model);
    }

    // Метод для получения списка всей доступной мебели
    public List<FurnitureModel> getAllModels() {
        return repository.findAll();
    }

    // Поиск конкретной модели по ID
    public FurnitureModel getModelById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Model not found with id: " + id));
    }
}