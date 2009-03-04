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

package com.android.internal.telephony.gsm.stk;


/**
 * Enumeration for representing text alignment.
 *
 * {@hide}
 */
public enum TextAlignment {
    LEFT(0x0),
    CENTER(0x1),
    RIGHT(0x2),
    /** Language dependent (default) */
    DEFAULT(0x3);

    private int mValue;

    TextAlignment(int value) {
        mValue = value;
    }

    /**
     * Create a TextAlignment object.
     * @param value Integer value to be converted to a TextAlignment object.
     * @return TextAlignment object whose value is {@code value}. If no
     *         TextAlignment object has that value, null is returned.
     */
    public static TextAlignment fromInt(int value) {
        for (TextAlignment e : TextAlignment.values()) {
            if (e.mValue == value) {
                return e;
            }
        }
        return null;
    }
}
