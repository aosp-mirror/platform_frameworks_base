/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony.cat;


/**
 * Enumeration for representing text color.
 *
 * {@hide}
 */
public enum TextColor {
    BLACK(0x0),
    DARK_GRAY(0x1),
    DARK_RED(0x2),
    DARK_YELLOW(0x3),
    DARK_GREEN(0x4),
    DARK_CYAN(0x5),
    DARK_BLUE(0x6),
    DARK_MAGENTA(0x7),
    GRAY(0x8),
    WHITE(0x9),
    BRIGHT_RED(0xa),
    BRIGHT_YELLOW(0xb),
    BRIGHT_GREEN(0xc),
    BRIGHT_CYAN(0xd),
    BRIGHT_BLUE(0xe),
    BRIGHT_MAGENTA(0xf);

    private int mValue;

    TextColor(int value) {
        mValue = value;
    }

    /**
     * Create a TextColor object.
     * @param value Integer value to be converted to a TextColor object.
     * @return TextColor object whose value is {@code value}. If no TextColor
     *         object has that value, null is returned.
     */
    public static TextColor fromInt(int value) {
        for (TextColor e : TextColor.values()) {
            if (e.mValue == value) {
                return e;
            }
        }
        return null;
    }
}
