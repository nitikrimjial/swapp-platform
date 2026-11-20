package com.swapp.storage;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import com.swapp.common.ApiException;
import com.swapp.config.AppProperties;

/**
 * Validates uploads by inspecting the file signature ("magic bytes") rather than trusting the client-supplied
 * filename or Content-Type, and enforces the configured size limit.
 */
@Component
public class ImageValidator {

    private final long maxBytes;

    public ImageValidator(AppProperties props) {
        this.maxBytes = props.storage().maxImageBytes();
    }

    public ValidatedImage validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Please choose an image to upload");
        }
        if (file.getSize() > maxBytes) {
            throw ApiException.badRequest("Each image must be " + (maxBytes / (1024 * 1024)) + " MB or smaller");
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String type = detect(data);
        if (type == null) {
            throw ApiException.badRequest("Only JPEG, PNG, GIF and WebP images are supported");
        }
        return new ValidatedImage(data, type);
    }

    static String detect(byte[] d) {
        if (d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (d.length >= 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G'
                && d[4] == 0x0D && d[5] == 0x0A && d[6] == 0x1A && d[7] == 0x0A) {
            return "image/png";
        }
        if (d.length >= 6 && d[0] == 'G' && d[1] == 'I' && d[2] == 'F' && d[3] == '8'
                && (d[4] == '7' || d[4] == '9') && d[5] == 'a') {
            return "image/gif";
        }
        if (d.length >= 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    static String extensionFor(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            default -> throw new IllegalArgumentException("Unsupported type " + contentType);
        };
    }

    static String contentTypeForKey(String key) {
        String ext = key.substring(key.lastIndexOf('.') + 1);
        return switch (ext) {
            case "jpg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            default -> "application/octet-stream";
        };
    }

    public record ValidatedImage(byte[] data, String contentType) {
    }
}
