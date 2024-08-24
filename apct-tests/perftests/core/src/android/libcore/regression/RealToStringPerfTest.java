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

import androidx.benchmark.BenchmarkState;
import androidx.benchmark.junit4.BenchmarkRule;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class RealToStringPerfTest {
    @Rule public BenchmarkRule mBenchmarkRule = new BenchmarkRule();

    private static final float SMALL = -123.45f;
    private static final float MEDIUM = -123.45e8f;
    private static final float LARGE = -123.45e36f;

    @Test
    public void timeFloat_toString_NaN() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            Float.toString(Float.NaN);
        }
    }

    @Test
    public void timeFloat_toString_NEGATIVE_INFINITY() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            Float.toString(Float.NEGATIVE_INFINITY);
        }
    }

    @Test
    public void timeFloat_toString_POSITIVE_INFINITY() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            Float.toString(Float.POSITIVE_INFINITY);
        }
    }

    @Test
    public void timeFloat_toString_zero() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            Float.toString(0.0f);
        }
    }

    @Test
    public void timeFloat_toString_minusZero() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            Float.toString(-0.0f);
        }
    }

    @Test
    public void timeFloat_toString_small() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            Float.toString(SMALL);
        }
    }

    @Test
    public void timeFloat_toString_medium() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            Float.toString(MEDIUM);
        }
    }

    @Test
    public void timeFloat_toString_large() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            Float.toString(LARGE);
        }
    }

    @Test
    public void timeStringBuilder_small() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            new StringBuilder().append(SMALL);
        }
    }

    @Test
    public void timeStringBuilder_medium() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            new StringBuilder().append(MEDIUM);
        }
    }

    @Test
    public void timeStringBuilder_large() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            new StringBuilder().append(LARGE);
        }
    }

    @Test
    public void timeFormatter_small() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            String.format("%f", SMALL);
        }
    }

    @Test
    public void timeFormatter_medium() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            String.format("%f", MEDIUM);
        }
    }

    @Test
    public void timeFormatter_large() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            String.format("%f", LARGE);
        }
    }

    @Test
    public void timeFormatter_dot2f_small() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            String.format("%.2f", SMALL);
        }
    }

    @Test
    public void timeFormatter_dot2f_medium() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            String.format("%.2f", MEDIUM);
        }
    }

    @Test
    public void timeFormatter_dot2f_large() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            String.format("%.2f", LARGE);
        }
    }
}
