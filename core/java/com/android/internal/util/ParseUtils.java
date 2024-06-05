/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.internal.util;

import android.annotation.Nullable;

/**
 * Various numeric -> strings conversion.
 *
 * Test:
 atest /android/pi-dev/frameworks/base/core/tests/coretests/src/com/android/internal/util/ParseUtilsTest.java
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public final class ParseUtils {
    private ParseUtils() {
    }

    /** Parse a value as a base-10 integer. */
    public static int parseInt(@Nullable String value, int defValue) {
        return parseIntWithBase(value, 10, defValue);
    }

    /** Parse a value as an integer of a given base. */
    public static int parseIntWithBase(@Nullable String value, int base, int defValue) {
        if (value == null) {
            return defValue;
        }
        try {
            return Integer.parseInt(value, base);
        } catch (NumberFormatException e) {
            return defValue;
        }
    }

    /** Parse a value as a base-10 long. */
    public static long parseLong(@Nullable String value, long defValue) {
        return parseLongWithBase(value, 10, defValue);
    }

    /** Parse a value as a long of a given base. */
    public static long parseLongWithBase(@Nullable String value, int base, long defValue) {
        if (value == null) {
            return defValue;
        }
        try {
            return Long.parseLong(value, base);
        } catch (NumberFormatException e) {
            return defValue;
        }
    }

    /** Parse a value as a float. */
    public static float parseFloat(@Nullable String value, float defValue) {
        if (value == null) {
            return defValue;
        }
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return defValue;
        }
    }

    /** Parse a value as a double. */
    public static double parseDouble(@Nullable String value, double defValue) {
        if (value == null) {
            return defValue;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return defValue;
        }
    }

    /** Parse a value as a boolean. */
    public static boolean parseBoolean(@Nullable String value, boolean defValue) {
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        return parseInt(value, defValue ? 1 : 0) != 0;
    }
}
