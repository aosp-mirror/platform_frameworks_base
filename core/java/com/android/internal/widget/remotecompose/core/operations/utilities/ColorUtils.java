/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.core.operations.utilities;

/**
 * These are tools to use long Color as variables
 * long colors are stored a 0xXXXXXXXX XXXXXX??
 * in SRGB the colors are stored 0xAARRGGBB,00000000
 * SRGB color sapce is color space 0
 * Our Color will use color float with a
 * Current android supports
 * SRGB, LINEAR_SRGB, EXTENDED_SRGB, LINEAR_EXTENDED_SRGB, BT709, BT2020,
 * DCI_P3, DISPLAY_P3, NTSC_1953, SMPTE_C, ADOBE_RGB, PRO_PHOTO_RGB, ACES,
 * ACESCG, CIE_XYZ, CIE_LAB, BT2020_HLG, BT2020_PQ 0..17 respectively
 *
 * Our color space will be 62 (MAX_ID-1). (0x3E)
 * Storing the default value in SRGB format and having the
 * id of the color between the ARGB values and the 62 i.e.
 * 0xAARRGGBB 00 00 00 3E
 *
 */
public class ColorUtils {
    public static int RC_COLOR = 62;

    long packRCColor(int defaultARGB, int id) {
        long l = defaultARGB;
        return (l << 32) | id << 8 | RC_COLOR;
    }

    boolean isRCColor(long color) {
        return ((color & 0x3F) == 62);
    }

    int getID(long color) {
        if (isRCColor(color)) {
            return (int) ((color & 0xFFFFFF00) >> 8);
        }
        return -1;
    }

    /**
     * get default color from long color
     * @param color
     * @return
     */
    public int getDefaultColor(long color) {
        if (isRCColor(color)) {
            return (int) (color >> 32);
        }
        if (((color & 0xFF) == 0)) {
            return (int) (color >> 32);
        }
        return 0;
    }
}
