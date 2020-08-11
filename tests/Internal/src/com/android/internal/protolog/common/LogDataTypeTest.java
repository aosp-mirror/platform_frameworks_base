/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.protolog.common;

import static org.junit.Assert.assertEquals;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class LogDataTypeTest {
    @Test
    public void parseFormatString() {
        String str = "%b %d %o %x %f %e %g %s %%";
        List<Integer> out = LogDataType.parseFormatString(str);
        assertEquals(Arrays.asList(
                LogDataType.BOOLEAN,
                LogDataType.LONG,
                LogDataType.LONG,
                LogDataType.LONG,
                LogDataType.DOUBLE,
                LogDataType.DOUBLE,
                LogDataType.DOUBLE,
                LogDataType.STRING
        ), out);
    }

    @Test(expected = InvalidFormatStringException.class)
    public void parseFormatString_invalid() {
        String str = "%q";
        LogDataType.parseFormatString(str);
    }

    @Test
    public void logDataTypesToBitMask() {
        List<Integer> types = Arrays.asList(LogDataType.STRING, LogDataType.DOUBLE,
                LogDataType.LONG, LogDataType.BOOLEAN);
        int mask = LogDataType.logDataTypesToBitMask(types);
        assertEquals(0b11011000, mask);
    }

    @Test(expected = BitmaskConversionException.class)
    public void logDataTypesToBitMask_toManyParams() {
        ArrayList<Integer> types = new ArrayList<>();
        for (int i = 0; i <= 16; i++) {
            types.add(LogDataType.STRING);
        }
        LogDataType.logDataTypesToBitMask(types);
    }

    @Test
    public void bitmaskToLogDataTypes() {
        int bitmask = 0b11011000;
        List<Integer> types = Arrays.asList(LogDataType.STRING, LogDataType.DOUBLE,
                LogDataType.LONG, LogDataType.BOOLEAN);
        for (int i = 0; i < types.size(); i++) {
            assertEquals(types.get(i).intValue(), LogDataType.bitmaskToLogDataType(bitmask, i));
        }
    }
}
