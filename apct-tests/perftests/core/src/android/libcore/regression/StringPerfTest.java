/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.libcore.regression;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collection;

@RunWith(JUnitParamsRunner.class)
@LargeTest
public class StringPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    enum StringLengths {
        EMPTY(""),
        SHORT("short"),
        EIGHTY(makeString(80)),
        EIGHT_KI(makeString(8192));
        final String mValue;

        StringLengths(String value) {
            this.mValue = value;
        }
    }

    public static Collection<Object[]> getData() {
        return Arrays.asList(
                new Object[][] {
                    {StringLengths.EIGHT_KI},
                    {StringLengths.EIGHTY},
                    {StringLengths.SHORT},
                    {StringLengths.EMPTY}
                });
    }

    private static String makeString(int length) {
        StringBuilder result = new StringBuilder(length);
        for (int i = 0; i < length; ++i) {
            result.append((char) i);
        }
        return result.toString();
    }

    @Test
    @Parameters(method = "getData")
    public void timeHashCode(StringLengths stringLengths) {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            stringLengths.mValue.hashCode();
        }
    }
}
