/*
 * Copyright 2018 The Android Open Source Project
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

package android.graphics.fonts;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.ArraySet;

import dalvik.annotation.optimization.FastNative;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.Set;

/**
 * Provides a utility for font file operations.
 * @hide
 */
public class FontFileUtil {

    private FontFileUtil() {}  // Do not instanciate

    /**
     * Unpack the weight value from packed integer.
     */
    public static int unpackWeight(int packed) {
        return packed & 0xFFFF;
    }

    /**
     * Unpack the italic value from packed integer.
     */
    public static boolean unpackItalic(int packed) {
        return (packed & 0x10000) != 0;
    }

    /**
     * Returns true if the analyzeStyle succeeded
     */
    public static boolean isSuccess(int packed) {
        return packed != ANALYZE_ERROR;
    }

    private static int pack(@IntRange(from = 0, to = 1000) int weight, boolean italic) {
        return weight | (italic ? 0x10000 : 0);
    }

    private static final int SFNT_VERSION_1 = 0x00010000;
    private static final int SFNT_VERSION_OTTO = 0x4F54544F;
    private static final int TTC_TAG = 0x74746366;
    private static final int OS2_TABLE_TAG = 0x4F532F32;
    private static final int FVAR_TABLE_TAG = 0x66766172;

    private static final int ANALYZE_ERROR = 0xFFFFFFFF;

    /**
     * Analyze the font file returns packed style info
     */
    public static final int analyzeStyle(@NonNull ByteBuffer buffer,
            @IntRange(from = 0) int ttcIndex, @Nullable FontVariationAxis[] varSettings) {
        int weight = -1;
        int italic = -1;
        if (varSettings != null) {
            for (FontVariationAxis axis :varSettings) {
                if ("wght".equals(axis.getTag())) {
                    weight = (int) axis.getStyleValue();
                } else if ("ital".equals(axis.getTag())) {
                    italic = (axis.getStyleValue() == 1.0f) ? 1 : 0;
                }
            }
        }

        if (weight != -1 && italic != -1) {
            // Both weight/italic style are specifeid by variation settings.
            // No need to look into OS/2 table.
            // TODO: Good to look HVAR table to check if this font supports wght/ital axes.
            return pack(weight, italic == 1);
        }

        ByteOrder originalOrder = buffer.order();
        buffer.order(ByteOrder.BIG_ENDIAN);
        try {
            int fontFileOffset = 0;
            int magicNumber = buffer.getInt(0);
            if (magicNumber == TTC_TAG) {
                // TTC file.
                if (ttcIndex >= buffer.getInt(8 /* offset to number of fonts in TTC */)) {
                    return ANALYZE_ERROR;
                }
                fontFileOffset = buffer.getInt(
                    12 /* offset to array of offsets of font files */ + 4 * ttcIndex);
            }
            int sfntVersion = buffer.getInt(fontFileOffset);

            if (sfntVersion != SFNT_VERSION_1 && sfntVersion != SFNT_VERSION_OTTO) {
                return ANALYZE_ERROR;
            }

            int numTables = buffer.getShort(fontFileOffset + 4 /* offset to number of tables */);
            int os2TableOffset = -1;
            for (int i = 0; i < numTables; ++i) {
                int tableOffset = fontFileOffset + 12 /* size of offset table */
                        + i * 16 /* size of table record */;
                if (buffer.getInt(tableOffset) == OS2_TABLE_TAG) {
                    os2TableOffset = buffer.getInt(tableOffset + 8 /* offset to the table */);
                    break;
                }
            }

            if (os2TableOffset == -1) {
                // Couldn't find OS/2 table. use regular style
                return pack(400, false);
            }

            int weightFromOS2 = buffer.getShort(os2TableOffset + 4 /* offset to weight class */);
            boolean italicFromOS2 =
                    (buffer.getShort(os2TableOffset + 62 /* offset to fsSelection */) & 1) != 0;
            return pack(weight == -1 ? weightFromOS2 : weight,
                    italic == -1 ? italicFromOS2 : italic == 1);
        } finally {
            buffer.order(originalOrder);
        }
    }

    /**
     * Analyze head OpenType table and return fontRevision value as 32bit integer.
     *
     * The font revision is stored in 16.16 bit fixed point value. This function returns this fixed
     * point value as 32 bit integer, i.e. the value multiplied with 65536.
     *
     * IllegalArgumentException will be thrown for invalid font data.
     * If the font file is invalid, returns -1L.
     *
     * @param buffer a buffer of OpenType font
     * @param index a font index
     * @return font revision that shifted 16 bits left.
     */
    public static long getRevision(@NonNull ByteBuffer buffer, @IntRange(from = 0) int index) {
        return nGetFontRevision(buffer, index);
    }

    /**
     * Analyze name OpenType table and return PostScript name.
     *
     * IllegalArgumentException will be thrown for invalid font data.
     * null will be returned if not found or the PostScript name is invalid.
     *
     * @param buffer a buffer of OpenType font
     * @param index a font index
     * @return a post script name or null if it is invalid or not found.
     */
    public static String getPostScriptName(@NonNull ByteBuffer buffer,
            @IntRange(from = 0) int index) {
        return nGetFontPostScriptName(buffer, index);
    }

    /**
     * Analyze name OpenType table and return true if the font has PostScript Type 1 glyphs.
     *
     * IllegalArgumentException will be thrown for invalid font data.
     * -1 will be returned if the byte buffer is not a OpenType font data.
     * 0 will be returned if the font file doesn't have PostScript Type 1 glyphs, i.e. ttf file.
     * 1 will be returned if the font file has PostScript Type 1 glyphs, i.e. otf file.
     *
     * @param buffer a buffer of OpenType font
     * @param index a font index
     * @return a post script name or null if it is invalid or not found.
     */
    public static int isPostScriptType1Font(@NonNull ByteBuffer buffer,
            @IntRange(from = 0) int index) {
        return nIsPostScriptType1Font(buffer, index);
    }

    /**
     * Analyze the file content and returns 1 if the font file is an OpenType collection file, 0 if
     * the font file is a OpenType font file, -1 otherwise.
     */
    public static int isCollectionFont(@NonNull ByteBuffer buffer) {
        ByteBuffer copied = buffer.slice();
        copied.order(ByteOrder.BIG_ENDIAN);
        int magicNumber = copied.getInt(0);
        if (magicNumber == TTC_TAG) {
            return 1;
        } else if (magicNumber == SFNT_VERSION_1 || magicNumber == SFNT_VERSION_OTTO) {
            return 0;
        } else {
            return -1;
        }
    }

    private static int getUInt16(ByteBuffer buffer, int offset) {
        return ((int) buffer.getShort(offset)) & 0xFFFF;
    }

    /**
     * Returns supported axes of font
     *
     * @param buffer A buffer of the entire font file.
     * @param index A font index in case of font collection. Must be 0 otherwise.
     * @return set of supported axes tag. Returns empty set on error.
     */
    public static Set<Integer> getSupportedAxes(@NonNull ByteBuffer buffer, int index) {
        ByteOrder originalOrder = buffer.order();
        buffer.order(ByteOrder.BIG_ENDIAN);
        try {
            int fontFileOffset = 0;
            int magicNumber = buffer.getInt(0);
            if (magicNumber == TTC_TAG) {
                // TTC file.
                if (index >= buffer.getInt(8 /* offset to number of fonts in TTC */)) {
                    return Collections.EMPTY_SET;
                }
                fontFileOffset = buffer.getInt(
                        12 /* offset to array of offsets of font files */ + 4 * index);
            }
            int sfntVersion = buffer.getInt(fontFileOffset);

            if (sfntVersion != SFNT_VERSION_1 && sfntVersion != SFNT_VERSION_OTTO) {
                return Collections.EMPTY_SET;
            }

            int numTables = buffer.getShort(fontFileOffset + 4 /* offset to number of tables */);
            int fvarTableOffset = -1;
            for (int i = 0; i < numTables; ++i) {
                int tableOffset = fontFileOffset + 12 /* size of offset table */
                        + i * 16 /* size of table record */;
                if (buffer.getInt(tableOffset) == FVAR_TABLE_TAG) {
                    fvarTableOffset = buffer.getInt(tableOffset + 8 /* offset to the table */);
                    break;
                }
            }

            if (fvarTableOffset == -1) {
                // Couldn't find OS/2 table. use regular style
                return Collections.EMPTY_SET;
            }

            if (buffer.getShort(fvarTableOffset) != 1
                    || buffer.getShort(fvarTableOffset + 2) != 0) {
                return Collections.EMPTY_SET;
            }

            int axesArrayOffset = getUInt16(buffer, fvarTableOffset + 4);
            int axisCount = getUInt16(buffer, fvarTableOffset + 8);
            int axisSize = getUInt16(buffer, fvarTableOffset + 10);

            ArraySet<Integer> axes = new ArraySet<>();
            for (int i = 0; i < axisCount; ++i) {
                axes.add(buffer.getInt(fvarTableOffset + axesArrayOffset + axisSize * i));
            }

            return axes;
        } finally {
            buffer.order(originalOrder);
        }
    }

    @FastNative
    private static native long nGetFontRevision(@NonNull ByteBuffer buffer,
            @IntRange(from = 0) int index);

    @FastNative
    private static native String nGetFontPostScriptName(@NonNull ByteBuffer buffer,
            @IntRange(from = 0) int index);

    @FastNative
    private static native int nIsPostScriptType1Font(@NonNull ByteBuffer buffer,
            @IntRange(from = 0) int index);
}
