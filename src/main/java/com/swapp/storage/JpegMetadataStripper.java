package com.swapp.storage;

import java.io.ByteArrayOutputStream;

/**
 * Removes metadata segments from a JPEG without re-encoding the image: APP1 (EXIF, which can hold the GPS position
 * where a photo was taken, and XMP) and APP13 (Photoshop/IPTC). The ICC colour profile (APP2) and all image data
 * are kept byte for byte.
 *
 * <p>The website re-encodes photos in the browser, which already drops metadata; this is a server-side backstop for
 * API clients. A file that cannot be parsed is returned unchanged (it already passed signature validation).
 * Side effect: the EXIF orientation tag goes too, so an API client must upload correctly rotated pixels.
 */
public final class JpegMetadataStripper {

    private static final int SOI = 0xD8;
    private static final int EOI = 0xD9;
    private static final int SOS = 0xDA;
    private static final int APP1 = 0xE1;
    private static final int APP13 = 0xED;

    private JpegMetadataStripper() {
    }

    public static byte[] strip(byte[] jpeg) {
        if (jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != SOI) {
            return jpeg;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length);
        out.write(jpeg, 0, 2);
        int i = 2;
        boolean removed = false;
        while (i + 1 < jpeg.length) {
            if ((jpeg[i] & 0xFF) != 0xFF) {
                return jpeg; // malformed: not at a marker
            }
            int marker = jpeg[i + 1] & 0xFF;
            if (marker == 0xFF) { // fill byte
                i++;
                continue;
            }
            if (marker == SOS || marker == EOI) {
                // Entropy-coded image data follows; copy the rest unchanged.
                out.write(jpeg, i, jpeg.length - i);
                return removed ? out.toByteArray() : jpeg;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) { // standalone markers, no length
                out.write(jpeg, i, 2);
                i += 2;
                continue;
            }
            if (i + 3 >= jpeg.length) {
                return jpeg;
            }
            int length = ((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF);
            if (length < 2 || i + 2 + length > jpeg.length) {
                return jpeg; // malformed segment length
            }
            if (marker == APP1 || marker == APP13) {
                removed = true;
            } else {
                out.write(jpeg, i, 2 + length);
            }
            i += 2 + length;
        }
        return jpeg; // no image data found
    }
}
