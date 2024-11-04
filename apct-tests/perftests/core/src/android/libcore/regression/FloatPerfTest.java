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
public class FloatPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private float mFloat = 1.2f;
    private int mInt = 1067030938;

    @Test
    public void timeFloatToIntBits() {
        int result = 123;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Float.floatToIntBits(mFloat);
        }
        if (result != mInt) {
            throw new RuntimeException(Integer.toString(result));
        }
    }

    @Test
    public void timeFloatToRawIntBits() {
        int result = 123;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Float.floatToRawIntBits(mFloat);
        }
        if (result != mInt) {
            throw new RuntimeException(Integer.toString(result));
        }
    }

    @Test
    public void timeIntBitsToFloat() {
        float result = 123.0f;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Float.intBitsToFloat(mInt);
        }
        if (result != mFloat) {
            throw new RuntimeException(Float.toString(result) + " "
                    + Float.floatToRawIntBits(result));
        }
    }
}
