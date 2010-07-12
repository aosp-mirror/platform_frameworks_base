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
 * Enumeration for representing text font size.
 *
 * {@hide}
 */
public enum FontSize {
    NORMAL(0x0),
    LARGE(0x1),
    SMALL(0x2);

    private int mValue;

    FontSize(int value) {
        mValue = value;
    }

    /**
     * Create a FontSize object.
     * @param value Integer value to be converted to a FontSize object.
     * @return FontSize object whose value is {@code value}. If no
     *         FontSize object has that value, null is returned.
     */
    public static FontSize fromInt(int value) {
        for (FontSize e : FontSize.values()) {
            if (e.mValue == value) {
                return e;
            }
        }
        return null;
    }
}
