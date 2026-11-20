package com.swapp.storage;

import java.time.Duration;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.swapp.common.ApiException;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private final StorageService storage;

    public FileController(StorageService storage) {
        this.storage = storage;
    }

    /** Keys are random and content is immutable, so responses can be cached aggressively. */
    @GetMapping("/{key}")
    public ResponseEntity<byte[]> get(@PathVariable String key) {
        if (key.length() > 100) {
            throw ApiException.notFound("File");
        }
        var object = storage.load(key).orElseThrow(() -> ApiException.notFound("File"));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(object.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", "inline")
                .body(object.data());
    }
}
