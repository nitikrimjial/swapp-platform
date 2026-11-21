package com.swapp.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.swapp.common.ApiException;
import com.swapp.config.AppProperties;

/**
 * Upload quotas. Photos live in PostgreSQL by default, so without limits a single account could fill the database and
 * take the whole site down. Usage is read from {@code stored_files.size_bytes}; with filesystem storage that table is
 * empty and the quotas do not apply (a volume has its own size limit).
 */
@Component
public class StorageQuota {

    private static final Logger log = LoggerFactory.getLogger(StorageQuota.class);

    private final StoredFileRepository files;
    private final long perUserBytes;
    private final long totalBytes;

    public StorageQuota(StoredFileRepository files, AppProperties props) {
        this.files = files;
        this.perUserBytes = props.limits().storagePerUser().toBytes();
        this.totalBytes = props.limits().storageTotal().toBytes();
    }

    /** Checks both the user's own allowance and the site-wide ceiling before {@code incomingBytes} are stored. */
    public void checkUserUpload(long userId, long incomingBytes) {
        if (files.bytesUsedBy(userId) + incomingBytes > perUserBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "You've reached your photo storage limit ("
                    + (perUserBytes / (1024 * 1024)) + " MB). Remove some photos or old listings first.");
        }
        checkSiteCapacity(incomingBytes);
    }

    public void checkSiteCapacity(long incomingBytes) {
        if (files.totalBytes() + incomingBytes > totalBytes) {
            log.warn("Upload refused: site-wide storage ceiling of {} bytes reached", totalBytes);
            throw new ApiException(HttpStatus.INSUFFICIENT_STORAGE,
                    "Photo uploads are paused for now because storage is full. Please try again later.");
        }
    }
}
