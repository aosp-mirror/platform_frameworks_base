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

import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class RealToStringPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private static final float SMALL = -123.45f;
    private static final float MEDIUM = -123.45e8f;
    private static final float LARGE = -123.45e36f;

    @Test
    public void timeFloat_toString_NaN() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Float.toString(Float.NaN);
        }
    }

    @Test
    public void timeFloat_toString_NEGATIVE_INFINITY() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Float.toString(Float.NEGATIVE_INFINITY);
        }
    }

    @Test
    public void timeFloat_toString_POSITIVE_INFINITY() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Float.toString(Float.POSITIVE_INFINITY);
        }
    }

    @Test
    public void timeFloat_toString_zero() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Float.toString(0.0f);
        }
    }

    @Test
    public void timeFloat_toString_minusZero() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Float.toString(-0.0f);
        }
    }

    @Test
    public void timeFloat_toString_small() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Float.toString(SMALL);
        }
    }

    @Test
    public void timeFloat_toString_medium() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Float.toString(MEDIUM);
        }
    }

    @Test
    public void timeFloat_toString_large() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Float.toString(LARGE);
        }
    }

    @Test
    public void timeStringBuilder_small() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            new StringBuilder().append(SMALL);
        }
    }

    @Test
    public void timeStringBuilder_medium() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            new StringBuilder().append(MEDIUM);
        }
    }

    @Test
    public void timeStringBuilder_large() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            new StringBuilder().append(LARGE);
        }
    }

    @Test
    public void timeFormatter_small() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            String.format("%f", SMALL);
        }
    }

    @Test
    public void timeFormatter_medium() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            String.format("%f", MEDIUM);
        }
    }

    @Test
    public void timeFormatter_large() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            String.format("%f", LARGE);
        }
    }

    @Test
    public void timeFormatter_dot2f_small() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            String.format("%.2f", SMALL);
        }
    }

    @Test
    public void timeFormatter_dot2f_medium() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            String.format("%.2f", MEDIUM);
        }
    }

    @Test
    public void timeFormatter_dot2f_large() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            String.format("%.2f", LARGE);
        }
    }
}
