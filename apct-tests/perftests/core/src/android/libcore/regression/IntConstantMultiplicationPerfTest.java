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
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class IntConstantMultiplicationPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void timeMultiplyIntByConstant6() {
        int result = 1;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result *= 6;
        }
    }

    @Test
    public void timeMultiplyIntByConstant7() {
        int result = 1;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result *= 7;
        }
    }

    @Test
    public void timeMultiplyIntByConstant8() {
        int result = 1;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result *= 8;
        }
    }

    @Test
    public void timeMultiplyIntByConstant8_Shift() {
        int result = 1;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result <<= 3;
        }
    }

    @Test
    public void timeMultiplyIntByConstant10() {
        int result = 1;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result *= 10;
        }
    }

    @Test
    public void timeMultiplyIntByConstant10_Shift() {
        int result = 1;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = (result + (result << 2)) << 1;
        }
    }

    @Test
    public void timeMultiplyIntByConstant2047() {
        int result = 1;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result *= 2047;
        }
    }

    @Test
    public void timeMultiplyIntByConstant2048() {
        int result = 1;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result *= 2048;
        }
    }

    @Test
    public void timeMultiplyIntByConstant2049() {
        int result = 1;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result *= 2049;
        }
    }

    @Test
    public void timeMultiplyIntByVariable10() {
        int result = 1;
        int factor = 10;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result *= factor;
        }
    }

    @Test
    public void timeMultiplyIntByVariable8() {
        int result = 1;
        int factor = 8;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result *= factor;
        }
    }
}
