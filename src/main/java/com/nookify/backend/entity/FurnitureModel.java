package com.nookify.backend.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "furniture_models")
@Data
public class FurnitureModel {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String category; // Напр: "chair", "table"

    @Column(name = "model_path")
    private String modelPath; // Путь к файлу модели в хранилище

    // Размеры пригодятся для AI-генерации сцены
    private Double width;
    private Double height;
    private Double depth;
}