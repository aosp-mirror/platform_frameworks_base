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

import static org.junit.Assert.assertEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Run with:
 atest /android/pi-dev/frameworks/base/core/tests/coretests/src/com/android/internal/util/ParseUtilsTest.java
 */
@RunWith(AndroidJUnit4.class)
public class ParseUtilsTest {
    private static final float DELTA_FLOAT = 0.0f;
    private static final double DELTA_DOUBLE = 0.0d;

    @Test
    public void testParseInt() {
        assertEquals(1, ParseUtils.parseInt(null, 1));
        assertEquals(1, ParseUtils.parseInt("", 1));
        assertEquals(1, ParseUtils.parseInt("1x", 1));
        assertEquals(2, ParseUtils.parseInt("2", 1));

        assertEquals(2, ParseUtils.parseInt("+2", 1));
        assertEquals(-2, ParseUtils.parseInt("-2", 1));
    }

    @Test
    public void testParseIntWithBase() {
        assertEquals(1, ParseUtils.parseIntWithBase(null, 10, 1));
        assertEquals(1, ParseUtils.parseIntWithBase("", 10, 1));
        assertEquals(1, ParseUtils.parseIntWithBase("1x", 10, 1));
        assertEquals(2, ParseUtils.parseIntWithBase("2", 10, 1));
        assertEquals(10, ParseUtils.parseIntWithBase("10", 10, 1));
        assertEquals(3, ParseUtils.parseIntWithBase("10", 3, 1));

        assertEquals(3, ParseUtils.parseIntWithBase("+10", 3, 1));
        assertEquals(-3, ParseUtils.parseIntWithBase("-10", 3, 1));
    }

    @Test
    public void testParseLong() {
        assertEquals(1L, ParseUtils.parseLong(null, 1));
        assertEquals(1L, ParseUtils.parseLong("", 1));
        assertEquals(1L, ParseUtils.parseLong("1x", 1));
        assertEquals(2L, ParseUtils.parseLong("2", 1));
    }

    @Test
    public void testParseLongWithBase() {
        assertEquals(1L, ParseUtils.parseLongWithBase(null, 10, 1));
        assertEquals(1L, ParseUtils.parseLongWithBase("", 10, 1));
        assertEquals(1L, ParseUtils.parseLongWithBase("1x", 10, 1));
        assertEquals(2L, ParseUtils.parseLongWithBase("2", 10, 1));
        assertEquals(10L, ParseUtils.parseLongWithBase("10", 10, 1));
        assertEquals(3L, ParseUtils.parseLongWithBase("10", 3, 1));

        assertEquals(3L, ParseUtils.parseLongWithBase("+10", 3, 1));
        assertEquals(-3L, ParseUtils.parseLongWithBase("-10", 3, 1));

        assertEquals(10_000_000_000L, ParseUtils.parseLongWithBase("+10000000000", 10, 1));
        assertEquals(-10_000_000_000L, ParseUtils.parseLongWithBase("-10000000000", 10, 1));

        assertEquals(10_000_000_000L, ParseUtils.parseLongWithBase(null, 10, 10_000_000_000L));
    }

    @Test
    public void testParseFloat() {
        assertEquals(0.5f, ParseUtils.parseFloat(null, 0.5f), DELTA_FLOAT);
        assertEquals(0.5f, ParseUtils.parseFloat("", 0.5f), DELTA_FLOAT);
        assertEquals(0.5f, ParseUtils.parseFloat("1x", 0.5f), DELTA_FLOAT);
        assertEquals(1.5f, ParseUtils.parseFloat("1.5", 0.5f), DELTA_FLOAT);
    }

    @Test
    public void testParseDouble() {
        assertEquals(0.5, ParseUtils.parseDouble(null, 0.5), DELTA_DOUBLE);
        assertEquals(0.5, ParseUtils.parseDouble("", 0.5), DELTA_DOUBLE);
        assertEquals(0.5, ParseUtils.parseDouble("1x", 0.5), DELTA_DOUBLE);
        assertEquals(1.5, ParseUtils.parseDouble("1.5", 0.5), DELTA_DOUBLE);
    }

    @Test
    public void testParseBoolean() {
        assertEquals(false, ParseUtils.parseBoolean(null, false));
        assertEquals(true, ParseUtils.parseBoolean(null, true));

        assertEquals(false, ParseUtils.parseBoolean("", false));
        assertEquals(true, ParseUtils.parseBoolean("", true));

        assertEquals(true, ParseUtils.parseBoolean("true", false));
        assertEquals(true, ParseUtils.parseBoolean("true", true));

        assertEquals(false, ParseUtils.parseBoolean("false", false));
        assertEquals(false, ParseUtils.parseBoolean("false", true));

        assertEquals(true, ParseUtils.parseBoolean("1", false));
        assertEquals(true, ParseUtils.parseBoolean("1", true));

        assertEquals(false, ParseUtils.parseBoolean("0", false));
        assertEquals(false, ParseUtils.parseBoolean("0", true));
    }
}
