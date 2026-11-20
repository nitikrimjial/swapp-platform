package com.swapp.storage;

import java.util.Optional;

/** Abstraction over binary object storage for user-uploaded images. */
public interface StorageService {

    /** Stores the bytes under a server-generated key and returns that key. */
    String store(byte[] data, String contentType);

    Optional<StoredObject> load(String key);

    void delete(String key);

    record StoredObject(byte[] data, String contentType) {
    }

    static String publicUrl(String key) {
        return key == null ? null : "/api/files/" + key;
    }
}
