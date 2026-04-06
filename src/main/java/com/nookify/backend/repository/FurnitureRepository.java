package com.nookify.backend.repository;

import com.nookify.backend.entity.FurnitureModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FurnitureRepository extends JpaRepository<FurnitureModel, Long> {
    // Spring сам реализует все стандартные методы (save, findById, findAll)
}