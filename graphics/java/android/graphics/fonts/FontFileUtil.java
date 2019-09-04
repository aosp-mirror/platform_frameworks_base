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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

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
}
