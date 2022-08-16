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

import java.util.Arrays;
import java.util.Collection;

/** Tests the performance of various StringBuilder methods. */
@RunWith(Parameterized.class)
@LargeTest
public class StringBuilderPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Parameters(name = "mLength={0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] {{1}, {10}, {100}});
    }

    @Parameterized.Parameter(0)
    public int mLength;

    @Test
    public void timeAppendBoolean() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < mLength; ++j) {
                sb.append(true);
            }
        }
    }

    @Test
    public void timeAppendChar() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < mLength; ++j) {
                sb.append('c');
            }
        }
    }

    @Test
    public void timeAppendCharArray() {
        char[] chars = "chars".toCharArray();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < mLength; ++j) {
                sb.append(chars);
            }
        }
    }

    @Test
    public void timeAppendCharSequence() {
        CharSequence cs = "chars";
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < mLength; ++j) {
                sb.append(cs);
            }
        }
    }

    @Test
    public void timeAppendSubCharSequence() {
        CharSequence cs = "chars";
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < mLength; ++j) {
                sb.append(cs);
            }
        }
    }

    @Test
    public void timeAppendDouble() {
        double d = 1.2;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < mLength; ++j) {
                sb.append(d);
            }
        }
    }

    @Test
    public void timeAppendFloat() {
        float f = 1.2f;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < mLength; ++j) {
                sb.append(f);
            }
        }
    }

    @Test
    public void timeAppendInt() {
        int n = 123;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < mLength; ++j) {
                sb.append(n);
            }
        }
    }

    @Test
    public void timeAppendLong() {
        long l = 123;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < mLength; ++j) {
                sb.append(l);
            }
        }
    }

    @Test
    public void timeAppendObject() {
        // We don't want to time the toString, so ensure we're calling a trivial one...
        Object o =
                new Object() {
                    @Override
                    public String toString() {
                        return "constant";
                    }
                };
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < mLength; ++j) {
                sb.append(o);
            }
        }
    }

    @Test
    public void timeAppendString() {
        String s = "chars";
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < mLength; ++j) {
                sb.append(s);
            }
        }
    }
}
