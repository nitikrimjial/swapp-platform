package com.swapp.storage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoredFileRepository extends JpaRepository<StoredFile, String> {

    /** Bytes held for one user: photos of all their listings (removed ones too; files outlive soft deletes) + avatar. */
    @Query(value = """
            SELECT CAST(COALESCE(SUM(f.size_bytes), 0) AS BIGINT) FROM stored_files f
            WHERE f.storage_key IN (SELECT ii.storage_key FROM item_images ii
                                    JOIN items i ON i.id = ii.item_id WHERE i.owner_id = :userId)
               OR f.storage_key = (SELECT u.avatar_key FROM users u WHERE u.id = :userId)
            """, nativeQuery = true)
    long bytesUsedBy(@Param("userId") long userId);

    @Query(value = "SELECT CAST(COALESCE(SUM(size_bytes), 0) AS BIGINT) FROM stored_files", nativeQuery = true)
    long totalBytes();
}
