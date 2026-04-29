package com.nookify.backend.service;

import com.nookify.backend.entity.FurnitureModel;
import com.nookify.backend.repository.FurnitureRepository;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class FurnitureService {

    private final FurnitureRepository repository;
    private final MinioClient minioClient;

    @Value("${minio.bucketName}")
    private String bucketName;

    // Получаем все модели и сразу генерируем для них ссылки на файлы
    public List<FurnitureModel> getAllModels() {
        List<FurnitureModel> models = repository.findAll();
        models.forEach(this::generatePresignedUrl);
        return models;
    }

    // Поиск одной модели с генерацией ссылки
    public FurnitureModel getModelById(Long id) {
        FurnitureModel model = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Model not found with id: " + id));
        generatePresignedUrl(model);
        return model;
    }

    public FurnitureModel saveModel(FurnitureModel model) {
        return repository.save(model);
    }

    // Вспомогательный метод: превращает "Bed_01.glb" в рабочую URL-ссылку
    private void generatePresignedUrl(FurnitureModel model) {
        try {
            String url = minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(bucketName)
                            .object(model.getModelPath()) // берем имя файла из БД
                            .expiry(2, TimeUnit.HOURS)    // ссылка будет жить 2 часа
                            .build()
            );
            model.setModelPath(url); // подменяем имя файла на полную ссылку
        } catch (Exception e) {
            // Если в MinIO файла нет, оставим как есть или выведем ошибку
            model.setModelPath("Error generating URL: " + e.getMessage());
        }
    }
}