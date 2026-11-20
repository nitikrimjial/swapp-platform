package com.swapp.storage;

import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.swapp.common.BaseEntity;

/**
 * Stores images inside MySQL. Images then persist across redeploys on any host without a separate volume or bucket,
 * and are backed up together with the rest of the data.
 */
@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "database", matchIfMissing = true)
public class DatabaseStorageService implements StorageService {

    private final StoredFileRepository repository;

    public DatabaseStorageService(StoredFileRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public String store(byte[] data, String contentType) {
        String key = UUID.randomUUID().toString().replace("-", "");
        repository.save(new StoredFile(key, contentType, data, BaseEntity.now()));
        return key;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredObject> load(String key) {
        return repository.findById(key).map(f -> new StoredObject(f.getData(), f.getContentType()));
    }

    /** Runs in its own transaction because it is invoked from after-commit / after-rollback hooks. */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void delete(String key) {
        if (key != null) {
            repository.deleteById(key);
        }
    }
}
