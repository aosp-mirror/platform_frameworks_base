/*
 * Copyright (C) 2017 The Android Open Source Project
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

import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
@LargeTest
public class StringReplaceAllPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    // NOTE: These estimates of MOVEABLE / NON_MOVEABLE are based on a knowledge of
    // ART implementation details. They make a difference here because JNI calls related
    // to strings took different paths depending on whether the String in question was
    // moveable or not.
    enum StringLengths {
        EMPTY(""),
        MOVEABLE_16(makeString(16)),
        MOVEABLE_256(makeString(256)),
        MOVEABLE_1024(makeString(1024)),
        NON_MOVEABLE(makeString(64 * 1024)),
        BOOT_IMAGE(java.util.jar.JarFile.MANIFEST_NAME);

        private final String mValue;

        StringLengths(String s) {
            this.mValue = s;
        }
    }

    private static String makeString(int length) {
        final String sequence8 = "abcdefghijklmnop";
        final int numAppends = (length / 16) - 1;
        StringBuilder stringBuilder = new StringBuilder(length);

        // (n-1) occurrences of "abcdefghijklmnop"
        for (int i = 0; i < numAppends; ++i) {
            stringBuilder.append(sequence8);
        }

        // and one final occurrence of qrstuvwx.
        stringBuilder.append("qrstuvwx");

        return stringBuilder.toString();
    }

    @Parameters(name = "mStringLengths={0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(
                new Object[][] {
                    {StringLengths.BOOT_IMAGE},
                    {StringLengths.EMPTY},
                    {StringLengths.MOVEABLE_16},
                    {StringLengths.MOVEABLE_256},
                    {StringLengths.MOVEABLE_1024},
                    {StringLengths.NON_MOVEABLE}
                });
    }

    @Parameterized.Parameter(0)
    public StringLengths mStringLengths;

    @Test
    public void timeReplaceAllTrivialPatternNonExistent() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mStringLengths.mValue.replaceAll("fish", "0");
        }
    }

    @Test
    public void timeReplaceTrivialPatternAllRepeated() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mStringLengths.mValue.replaceAll("jklm", "0");
        }
    }

    @Test
    public void timeReplaceAllTrivialPatternSingleOccurrence() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mStringLengths.mValue.replaceAll("qrst", "0");
        }
    }
}
