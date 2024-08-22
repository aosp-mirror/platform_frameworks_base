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
public class IntegralToStringPerfTest {
    @Rule public BenchmarkRule mBenchmarkRule = new BenchmarkRule();

    private static final int SMALL = 12;
    private static final int MEDIUM = 12345;
    private static final int LARGE = 12345678;

    @Test
    public void time_IntegerToString_small() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            Integer.toString(SMALL);
        }
    }

    @Test
    public void time_IntegerToString_medium() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            Integer.toString(MEDIUM);
        }
    }

    @Test
    public void time_IntegerToString_large() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            Integer.toString(LARGE);
        }
    }

    @Test
    public void time_IntegerToString2_small() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            Integer.toString(SMALL, 2);
        }
    }

    @Test
    public void time_IntegerToString2_medium() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            Integer.toString(MEDIUM, 2);
        }
    }

    @Test
    public void time_IntegerToString2_large() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            Integer.toString(LARGE, 2);
        }
    }

    @Test
    public void time_IntegerToString10_small() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            Integer.toString(SMALL, 10);
        }
    }

    @Test
    public void time_IntegerToString10_medium() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            Integer.toString(MEDIUM, 10);
        }
    }

    @Test
    public void time_IntegerToString10_large() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            Integer.toString(LARGE, 10);
        }
    }

    @Test
    public void time_IntegerToString16_small() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            Integer.toString(SMALL, 16);
        }
    }

    @Test
    public void time_IntegerToString16_medium() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            Integer.toString(MEDIUM, 16);
        }
    }

    @Test
    public void time_IntegerToString16_large() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            Integer.toString(LARGE, 16);
        }
    }

    @Test
    public void time_IntegerToBinaryString_small() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            Integer.toBinaryString(SMALL);
        }
    }

    @Test
    public void time_IntegerToBinaryString_medium() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            Integer.toBinaryString(MEDIUM);
        }
    }

    @Test
    public void time_IntegerToBinaryString_large() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            Integer.toBinaryString(LARGE);
        }
    }

    @Test
    public void time_IntegerToHexString_small() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            Integer.toHexString(SMALL);
        }
    }

    @Test
    public void time_IntegerToHexString_medium() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            Integer.toHexString(MEDIUM);
        }
    }

    @Test
    public void time_IntegerToHexString_large() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            Integer.toHexString(LARGE);
        }
    }

    @Test
    public void time_StringBuilder_small() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            new StringBuilder().append(SMALL);
        }
    }

    @Test
    public void time_StringBuilder_medium() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            new StringBuilder().append(MEDIUM);
        }
    }

    @Test
    public void time_StringBuilder_large() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            new StringBuilder().append(LARGE);
        }
    }

    @Test
    public void time_Formatter_small() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            String.format("%d", SMALL);
        }
    }

    @Test
    public void time_Formatter_medium() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            String.format("%d", MEDIUM);
        }
    }

    @Test
    public void time_Formatter_large() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            String.format("%d", LARGE);
        }
    }
}
