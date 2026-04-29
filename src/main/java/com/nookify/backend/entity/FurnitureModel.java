package com.nookify.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

@Entity
@Table(name = "furniture_models")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FurnitureModel {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name; // Уникальное имя, например "SM_Prop_Fridge_02"

    @Enumerated(EnumType.STRING)
    private FurnitureCategory category;

    @Column(name = "model_path", nullable = false)
    private String modelPath; // Имя файла в бакете MinIO: "Fridge_02.glb"

    // Размеры в метрах. Помогут AI не ставить шкаф там, где он не влезет
    private Double width;
    private Double height;
    private Double depth;
}