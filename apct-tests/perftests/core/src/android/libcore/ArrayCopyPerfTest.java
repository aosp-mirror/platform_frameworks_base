/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.libcore;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class ArrayCopyPerfTest {

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void timeManualArrayCopy() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        char[] src = new char[8192];
        while (state.keepRunning()) {
            char[] dst = new char[8192];
            for (int i = 0; i < 8192; ++i) {
                dst[i] = src[i];
            }
        }
    }

    @Test
    public void time_System_arrayCopy() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        char[] src = new char[8192];
        while (state.keepRunning()) {
            char[] dst = new char[8192];
            System.arraycopy(src, 0, dst, 0, 8192);
        }
    }

    @Test
    public void time_Arrays_copyOf() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        char[] src = new char[8192];
        while (state.keepRunning()) {
            char[] dst = Arrays.copyOf(src, 8192);
        }
    }

    @Test
    public void time_Arrays_copyOfRange() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        char[] src = new char[8192];
        while (state.keepRunning()) {
            char[] dst = Arrays.copyOfRange(src, 0, 8192);
        }
    }
}
