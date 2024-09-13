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
public class StringReplacePerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    enum StringLengths {
        EMPTY(""),
        L_16(makeString(16)),
        L_64(makeString(64)),
        L_256(makeString(256)),
        L_512(makeString(512));

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

    public static Collection<Object[]> getData() {
        return Arrays.asList(
                new Object[][] {
                    {StringLengths.EMPTY},
                    {StringLengths.L_16},
                    {StringLengths.L_64},
                    {StringLengths.L_256},
                    {StringLengths.L_512}
                });
    }

    @Test
    @Parameters(method = "getData")
    public void timeReplaceCharNonExistent(StringLengths stringLengths) {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            stringLengths.mValue.replace('z', '0');
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeReplaceCharRepeated(StringLengths stringLengths) {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            stringLengths.mValue.replace('a', '0');
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeReplaceSingleChar(StringLengths stringLengths) {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            stringLengths.mValue.replace('q', '0');
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeReplaceSequenceNonExistent(StringLengths stringLengths) {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            stringLengths.mValue.replace("fish", "0");
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeReplaceSequenceRepeated(StringLengths stringLengths) {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            stringLengths.mValue.replace("jklm", "0");
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeReplaceSingleSequence(StringLengths stringLengths) {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            stringLengths.mValue.replace("qrst", "0");
        }
    }
}
