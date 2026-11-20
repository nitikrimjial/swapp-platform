package com.swapp.storage;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "stored_files")
@Getter
@NoArgsConstructor
public class StoredFile {

    @Id
    @Column(name = "storage_key", length = 100)
    private String storageKey;

    @Column(name = "content_type", nullable = false, length = 50)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** Mapped to PostgreSQL BYTEA. */
    @Column(name = "data", nullable = false)
    private byte[] data;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public StoredFile(String storageKey, String contentType, byte[] data, Instant createdAt) {
        this.storageKey = storageKey;
        this.contentType = contentType;
        this.data = data;
        this.sizeBytes = data.length;
        this.createdAt = createdAt;
    }
}
