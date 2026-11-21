package com.swapp.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class JpegMetadataStripperTest {

    @Test
    void removesExifButKeepsAValidDecodableImage() throws Exception {
        byte[] plain = realJpeg(40, 30);
        byte[] withExif = insertAfterSoi(plain, segment(0xE1, "Exif\0\0GPSLatitude=12.97;GPSLongitude=77.59"));
        assertThat(new String(withExif, StandardCharsets.ISO_8859_1)).contains("GPSLatitude");

        byte[] stripped = JpegMetadataStripper.strip(withExif);

        assertThat(new String(stripped, StandardCharsets.ISO_8859_1)).doesNotContain("GPSLatitude").doesNotContain("Exif");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(stripped));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(40);
        assertThat(decoded.getHeight()).isEqualTo(30);
    }

    @Test
    void removesPhotoshopIptcAndKeepsTheColourProfile() throws Exception {
        byte[] jpeg = insertAfterSoi(realJpeg(8, 8), concat(segment(0xE2, "ICC_PROFILE\0keep"), segment(0xED, "Photoshop 3.0\0city=Pune")));
        String result = new String(JpegMetadataStripper.strip(jpeg), StandardCharsets.ISO_8859_1);
        assertThat(result).contains("ICC_PROFILE").doesNotContain("city=Pune");
    }

    @Test
    void returnsFilesWithoutMetadataOrThatCannotBeParsedUnchanged() throws Exception {
        byte[] plain = realJpeg(8, 8);
        assertThat(JpegMetadataStripper.strip(plain)).isSameAs(plain);
        byte[] truncated = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0x7F, 0x7F, 1, 2};
        assertThat(JpegMetadataStripper.strip(truncated)).isSameAs(truncated);
        byte[] notJpeg = "GIF89a".getBytes(StandardCharsets.US_ASCII);
        assertThat(JpegMetadataStripper.strip(notJpeg)).isSameAs(notJpeg);
    }

    private static byte[] realJpeg(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(1, 1, 0x00FF8800);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, "jpg", out)).isTrue();
        return out.toByteArray();
    }

    private static byte[] segment(int marker, String payload) {
        byte[] body = payload.getBytes(StandardCharsets.ISO_8859_1);
        int length = body.length + 2;
        byte[] seg = new byte[body.length + 4];
        seg[0] = (byte) 0xFF;
        seg[1] = (byte) marker;
        seg[2] = (byte) (length >> 8);
        seg[3] = (byte) length;
        System.arraycopy(body, 0, seg, 4, body.length);
        return seg;
    }

    private static byte[] insertAfterSoi(byte[] jpeg, byte[] extra) {
        byte[] result = new byte[jpeg.length + extra.length];
        System.arraycopy(jpeg, 0, result, 0, 2);
        System.arraycopy(extra, 0, result, 2, extra.length);
        System.arraycopy(jpeg, 2, result, 2 + extra.length, jpeg.length - 2);
        return result;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
