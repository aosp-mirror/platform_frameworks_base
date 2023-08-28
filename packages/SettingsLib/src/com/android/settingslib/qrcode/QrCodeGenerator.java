/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settingslib.qrcode;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;

import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class QrCodeGenerator {
    private static final int DEFAULT_MARGIN = -1;
    /**
     * Generates a barcode image with {@code contents}.
     *
     * @param contents The contents to encode in the barcode
     * @param size     The preferred image size in pixels
     * @return Barcode bitmap
     */
    public static Bitmap encodeQrCode(String contents, int size)
            throws WriterException, IllegalArgumentException {
        return encodeQrCode(contents, size, DEFAULT_MARGIN, /*invert=*/false);
    }

    /**
     * Generates a barcode image with {@code contents}.
     *
     * @param contents The contents to encode in the barcode
     * @param size     The preferred image size in pixels
     * @param margin   The margin around the actual barcode
     * @return Barcode bitmap
     */
    public static Bitmap encodeQrCode(String contents, int size, int margin)
            throws WriterException, IllegalArgumentException {
        return encodeQrCode(contents, size, margin, /*invert=*/false);
    }

    /**
     * Generates a barcode image with {@code contents}.
     *
     * @param contents The contents to encode in the barcode
     * @param size     The preferred image size in pixels
     * @param invert   Whether to invert the black/white pixels (e.g. for dark mode)
     * @return Barcode bitmap
     */
    public static Bitmap encodeQrCode(String contents, int size, boolean invert)
            throws WriterException, IllegalArgumentException {
        return encodeQrCode(contents, size, DEFAULT_MARGIN, /*invert=*/invert);
    }

    /**
     * Generates a barcode image with {@code contents}.
     *
     * @param contents The contents to encode in the barcode
     * @param size     The preferred image size in pixels
     * @param margin   The margin around the actual barcode
     * @param invert   Whether to invert the black/white pixels (e.g. for dark mode)
     * @return Barcode bitmap
     */
    public static Bitmap encodeQrCode(String contents, int size, int margin, boolean invert)
            throws WriterException, IllegalArgumentException {
        final Map<EncodeHintType, Object> hints = new HashMap<>();
        if (!isIso88591(contents)) {
            hints.put(EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name());
        }
        if (margin != DEFAULT_MARGIN) {
            hints.put(EncodeHintType.MARGIN, margin);
        }

        final BitMatrix qrBits = new MultiFormatWriter().encode(contents, BarcodeFormat.QR_CODE,
                size, size, hints);
        final Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
        int setColor = invert ? Color.WHITE : Color.BLACK;
        int unsetColor = invert ? Color.BLACK : Color.WHITE;
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                bitmap.setPixel(x, y, qrBits.get(x, y) ? setColor : unsetColor);
            }
        }
        return bitmap;
    }

    private static boolean isIso88591(String contents) {
        CharsetEncoder encoder = StandardCharsets.ISO_8859_1.newEncoder();
        return encoder.canEncode(contents);
    }
}
