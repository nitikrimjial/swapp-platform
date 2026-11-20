package com.swapp.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.swapp.config.AppProperties;

/** Stores images on a local (ideally persistent, mounted) volume. */
@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "filesystem")
public class FileSystemStorageService implements StorageService {

    private static final Pattern SAFE_KEY = Pattern.compile("^[a-f0-9]{32}\\.(jpg|png|gif|webp)$");

    private final Path root;

    public FileSystemStorageService(AppProperties props) throws IOException {
        this.root = Path.of(props.storage().localDir()).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    @Override
    public String store(byte[] data, String contentType) {
        String key = UUID.randomUUID().toString().replace("-", "") + "." + ImageValidator.extensionFor(contentType);
        try {
            Files.write(root.resolve(key), data);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store file", e);
        }
        return key;
    }

    @Override
    public Optional<StoredObject> load(String key) {
        Path path = resolve(key);
        if (path == null || !Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new StoredObject(Files.readAllBytes(path), ImageValidator.contentTypeForKey(key)));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read file", e);
        }
    }

    @Override
    public void delete(String key) {
        Path path = resolve(key);
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not delete file", e);
            }
        }
    }

    /** Only server-generated key shapes are accepted, which rules out path traversal. */
    private Path resolve(String key) {
        if (key == null || !SAFE_KEY.matcher(key).matches()) {
            return null;
        }
        return root.resolve(key);
    }
}
