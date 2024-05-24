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
public class IntegralToStringPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private static final int SMALL = 12;
    private static final int MEDIUM = 12345;
    private static final int LARGE = 12345678;

    @Test
    public void time_IntegerToString_small() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Integer.toString(SMALL);
        }
    }

    @Test
    public void time_IntegerToString_medium() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Integer.toString(MEDIUM);
        }
    }

    @Test
    public void time_IntegerToString_large() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Integer.toString(LARGE);
        }
    }

    @Test
    public void time_IntegerToString2_small() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Integer.toString(SMALL, 2);
        }
    }

    @Test
    public void time_IntegerToString2_medium() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Integer.toString(MEDIUM, 2);
        }
    }

    @Test
    public void time_IntegerToString2_large() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Integer.toString(LARGE, 2);
        }
    }

    @Test
    public void time_IntegerToString10_small() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Integer.toString(SMALL, 10);
        }
    }

    @Test
    public void time_IntegerToString10_medium() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Integer.toString(MEDIUM, 10);
        }
    }

    @Test
    public void time_IntegerToString10_large() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Integer.toString(LARGE, 10);
        }
    }

    @Test
    public void time_IntegerToString16_small() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Integer.toString(SMALL, 16);
        }
    }

    @Test
    public void time_IntegerToString16_medium() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Integer.toString(MEDIUM, 16);
        }
    }

    @Test
    public void time_IntegerToString16_large() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Integer.toString(LARGE, 16);
        }
    }

    @Test
    public void time_IntegerToBinaryString_small() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Integer.toBinaryString(SMALL);
        }
    }

    @Test
    public void time_IntegerToBinaryString_medium() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Integer.toBinaryString(MEDIUM);
        }
    }

    @Test
    public void time_IntegerToBinaryString_large() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Integer.toBinaryString(LARGE);
        }
    }

    @Test
    public void time_IntegerToHexString_small() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Integer.toHexString(SMALL);
        }
    }

    @Test
    public void time_IntegerToHexString_medium() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Integer.toHexString(MEDIUM);
        }
    }

    @Test
    public void time_IntegerToHexString_large() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Integer.toHexString(LARGE);
        }
    }

    @Test
    public void time_StringBuilder_small() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            new StringBuilder().append(SMALL);
        }
    }

    @Test
    public void time_StringBuilder_medium() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            new StringBuilder().append(MEDIUM);
        }
    }

    @Test
    public void time_StringBuilder_large() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            new StringBuilder().append(LARGE);
        }
    }

    @Test
    public void time_Formatter_small() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            String.format("%d", SMALL);
        }
    }

    @Test
    public void time_Formatter_medium() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            String.format("%d", MEDIUM);
        }
    }

    @Test
    public void time_Formatter_large() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            String.format("%d", LARGE);
        }
    }
}
