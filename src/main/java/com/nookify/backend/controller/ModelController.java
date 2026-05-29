package com.nookify.backend.controller;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
public class ModelController {
    private final MinioClient minioClient;
    private static final String BUCKET = "furniture";

    @GetMapping("/{filename}")
    public ResponseEntity<InputStreamResource> getModel(@PathVariable String filename) {
        try {
            GetObjectResponse response = minioClient.getObject(
                    io.minio.GetObjectArgs.builder().bucket(BUCKET).object(filename).build()
            );
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=" + filename)
                    .contentType(MediaType.parseMediaType("model/gltf-binary"))
                    .body(new InputStreamResource(response));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}