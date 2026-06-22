package com.nookify.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Промежуточный DTO: то, что Gemini возвращает в первом проходе.
 * Только прямоугольные комнаты — без стен, без мебели.
 * Координаты x/z — левый нижний угол комнаты (минимальные X и Z).
 */
@Data
public class RoomLayout {

    /** Человекочитаемое название зоны, например "Кухня", "Спальня", "Санузел" */
    @JsonProperty("name")
    private String name;

    /**
     * Тип зоны — используется во втором промпте, чтобы Gemini знал,
     * какую мебель уместно размещать в каждой комнате.
     * Значения: KITCHEN, BEDROOM, LIVING_ROOM, BATHROOM, HALLWAY, OTHER
     */
    @JsonProperty("type")
    private String type;

    /** Координата X левого нижнего угла комнаты (метры, 0.0–10.0) */
    @JsonProperty("x")
    private double x;

    /** Координата Z левого нижнего угла комнаты (метры, 0.0–10.0) */
    @JsonProperty("z")
    private double z;

    /** Ширина комнаты по оси X (метры) */
    @JsonProperty("width")
    private double width;

    /** Глубина комнаты по оси Z (метры) */
    @JsonProperty("depth")
    private double depth;
}
