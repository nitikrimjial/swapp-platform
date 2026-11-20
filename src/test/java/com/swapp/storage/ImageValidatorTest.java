package com.swapp.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import com.swapp.common.ApiException;
import com.swapp.config.AppProperties;

class ImageValidatorTest {

    private final ImageValidator validator = new ImageValidator(new AppProperties(null, null,
            new AppProperties.Cors(List.of()), new AppProperties.Security(4, 3, Duration.ofMinutes(1)),
            new AppProperties.Storage("database", "x", 64, 6)));

    @Test
    void detectsSupportedFormatsBySignature() {
        assertThat(ImageValidator.detect(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0})).isEqualTo("image/jpeg");
        assertThat(ImageValidator.detect(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}))
                .isEqualTo("image/png");
        assertThat(ImageValidator.detect("GIF89a....".getBytes())).isEqualTo("image/gif");
        assertThat(ImageValidator.detect("RIFF0000WEBPVP8 ".getBytes())).isEqualTo("image/webp");
        assertThat(ImageValidator.detect("<svg onload=alert(1)>".getBytes())).isNull();
        assertThat(ImageValidator.detect(new byte[0])).isNull();
    }

    @Test
    void ignoresClientSuppliedContentType() {
        var fake = new MockMultipartFile("f", "x.jpg", "image/jpeg", "<html>".getBytes());
        assertThatThrownBy(() -> validator.validate(fake)).isInstanceOf(ApiException.class);
    }

    @Test
    void enforcesSizeLimit() {
        byte[] big = new byte[65];
        big[0] = (byte) 0xFF;
        big[1] = (byte) 0xD8;
        big[2] = (byte) 0xFF;
        assertThatThrownBy(() -> validator.validate(new MockMultipartFile("f", "a.jpg", "image/jpeg", big)))
                .isInstanceOf(ApiException.class).hasMessageContaining("MB or smaller");
    }

    @Test
    void rejectsEmptyUploads() {
        assertThatThrownBy(() -> validator.validate(new MockMultipartFile("f", new byte[0])))
                .isInstanceOf(ApiException.class);
    }
}
