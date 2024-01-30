/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.settingslib.qrcode

import android.annotation.ColorInt
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import java.nio.charset.StandardCharsets
import java.util.EnumMap

object QrCodeGenerator {
    /**
     * Generates a barcode image with [contents].
     *
     * @param contents The contents to encode in the barcode
     * @param size     The preferred image size in pixels
     * @param invert   Whether to invert the black/white pixels (e.g. for dark mode)
     * @return Barcode bitmap
     */
    @JvmStatic
    @Throws(WriterException::class, java.lang.IllegalArgumentException::class)
    fun encodeQrCode(contents: String, size: Int, invert: Boolean): Bitmap =
        encodeQrCode(contents, size, DEFAULT_MARGIN, invert)

    private const val DEFAULT_MARGIN = -1

    /**
     * Generates a barcode image with [contents].
     *
     * @param contents The contents to encode in the barcode
     * @param size     The preferred image size in pixels
     * @param margin   The margin around the actual barcode
     * @param invert   Whether to invert the black/white pixels (e.g. for dark mode)
     * @return Barcode bitmap
     */
    @JvmOverloads
    @JvmStatic
    @Throws(WriterException::class, IllegalArgumentException::class)
    fun encodeQrCode(
        contents: String,
        size: Int,
        margin: Int = DEFAULT_MARGIN,
        invert: Boolean = false,
    ): Bitmap {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
        if (!isIso88591(contents)) {
            hints[EncodeHintType.CHARACTER_SET] = StandardCharsets.UTF_8.name()
        }
        if (margin != DEFAULT_MARGIN) {
            hints[EncodeHintType.MARGIN] = margin
        }
        val qrBits = MultiFormatWriter().encode(contents, BarcodeFormat.QR_CODE, size, size, hints)
        @ColorInt val setColor = if (invert) Color.WHITE else Color.BLACK
        @ColorInt val unsetColor = if (invert) Color.BLACK else Color.WHITE
        @ColorInt val pixels = IntArray(size * size)
        for (x in 0 until size) {
            for (y in 0 until size) {
                pixels[x * size + y] = if (qrBits[x, y]) setColor else unsetColor
            }
        }
        return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }
    }

    private fun isIso88591(contents: String): Boolean =
        StandardCharsets.ISO_8859_1.newEncoder().canEncode(contents)
}
