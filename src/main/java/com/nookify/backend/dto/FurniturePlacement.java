package com.nookify.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL) // Скроет null-поля (например, furnitureId) в Bruno
public class FurniturePlacement {

    @JsonProperty("id")
    private String slug; // Техническое имя (bed, chair)

    @JsonProperty("model_url")
    private String modelUrl; // Ссылка на скачивание .glb

    private double x;
    private double y;
    private double z;
    private double rotation;

    @JsonProperty("scaleWidth")
    private double scaleWidth = 1.0;
}