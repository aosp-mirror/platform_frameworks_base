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
import android.test.suitebuilder.annotation.LargeTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
@LargeTest
public class StringToBytesPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    enum StringLengths {
        EMPTY(""),
        L_16(makeString(16)),
        L_64(makeString(64)),
        L_256(makeString(256)),
        L_512(makeString(512)),
        A_16(makeAsciiString(16)),
        A_64(makeAsciiString(64)),
        A_256(makeAsciiString(256)),
        A_512(makeAsciiString(512));

        private final String mValue;

        StringLengths(String s) {
            this.mValue = s;
        }
    }

    @Parameters(name = "mStringLengths={0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(
                new Object[][] {
                    {StringLengths.EMPTY},
                    {StringLengths.L_16},
                    {StringLengths.L_64},
                    {StringLengths.L_256},
                    {StringLengths.L_512},
                    {StringLengths.A_16},
                    {StringLengths.A_64},
                    {StringLengths.A_256},
                    {StringLengths.A_512}
                });
    }

    @Parameterized.Parameter(0)
    public StringLengths mStringLengths;

    private static String makeString(int length) {
        char[] chars = new char[length];
        for (int i = 0; i < length; ++i) {
            chars[i] = (char) i;
        }
        return new String(chars);
    }

    private static String makeAsciiString(int length) {
        char[] chars = new char[length];
        for (int i = 0; i < length; ++i) {
            chars[i] = ((i & 0x7f) != 0) ? (char) (i & 0x7f) : '?';
        }
        return new String(chars);
    }

    @Test
    public void timeGetBytesUtf8() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mStringLengths.mValue.getBytes(StandardCharsets.UTF_8);
        }
    }

    @Test
    public void timeGetBytesIso88591() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mStringLengths.mValue.getBytes(StandardCharsets.ISO_8859_1);
        }
    }

    @Test
    public void timeGetBytesAscii() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mStringLengths.mValue.getBytes(StandardCharsets.US_ASCII);
        }
    }
}
