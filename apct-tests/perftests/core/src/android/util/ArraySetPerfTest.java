/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.util;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.function.Consumer;
import java.util.function.Predicate;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class ArraySetPerfTest {
    private static final int NUM_ITERATIONS = 100;
    private static final int SET_SIZE_SMALL = 10;
    private static final int SET_SIZE_LARGE = 50;

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void testForEach_Small() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        Consumer<Integer> consumer = (i) -> {
        };
        while (state.keepRunning()) {
            for (int i = 0; i < NUM_ITERATIONS; ++i) {
                ArraySet<Integer> set = new ArraySet<>();
                for (int j = 0; j < SET_SIZE_SMALL; j++) {
                    set.add(j);
                }
                set.forEach(consumer);
            }
        }
    }

    @Test
    public void testForEach_Large() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        Consumer<Integer> consumer = (i) -> {
        };
        while (state.keepRunning()) {
            for (int i = 0; i < NUM_ITERATIONS; ++i) {
                ArraySet<Integer> set = new ArraySet<>();
                for (int j = 0; j < SET_SIZE_LARGE; j++) {
                    set.add(j);
                }
                set.forEach(consumer);
            }
        }
    }

    @Test
    public void testValueAt_InBounds() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        ArraySet<Integer> set = new ArraySet<>();
        set.add(0);
        while (state.keepRunning()) {
            for (int i = 0; i < NUM_ITERATIONS; ++i) {
                set.valueAt(0);
            }
        }
    }

    @Test
    public void testValueAt_OutOfBounds_Negative() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        ArraySet<Integer> set = new ArraySet<>();
        while (state.keepRunning()) {
            for (int i = 0; i < NUM_ITERATIONS; ++i) {
                try {
                    set.valueAt(-1);
                } catch (ArrayIndexOutOfBoundsException expected) {
                    // expected
                }
            }
        }
    }

    /**
     * Tests the case where ArraySet could index into its array even though the index is out of
     * bounds.
     */
    @Test
    public void testValueAt_OutOfBounds_EdgeCase() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        ArraySet<Integer> set = new ArraySet<>();
        set.add(0);
        while (state.keepRunning()) {
            for (int i = 0; i < NUM_ITERATIONS; ++i) {
                try {
                    set.valueAt(1);
                } catch (ArrayIndexOutOfBoundsException expected) {
                    // expected
                }
            }
        }
    }

    /**
     * This is the same code as testRemoveIf_Small_* without the removeIf in order to measure
     * the performance of the rest of the code in the loop.
     */
    @Test
    public void testRemoveIf_Small_Base() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        Predicate<Integer> predicate = (i) -> i % 2 == 0;
        while (state.keepRunning()) {
            for (int i = 0; i < NUM_ITERATIONS; ++i) {
                ArraySet<Integer> set = new ArraySet<>();
                for (int j = 0; j < SET_SIZE_SMALL; ++j) {
                    set.add(j);
                }
            }
        }
    }

    @Test
    public void testRemoveIf_Small_RemoveNothing() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        Predicate<Integer> predicate = (i) -> false;
        while (state.keepRunning()) {
            for (int i = 0; i < NUM_ITERATIONS; ++i) {
                ArraySet<Integer> set = new ArraySet<>();
                for (int j = 0; j < SET_SIZE_SMALL; ++j) {
                    set.add(j);
                }
                set.removeIf(predicate);
            }
        }
    }

    @Test
    public void testRemoveIf_Small_RemoveAll() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        Predicate<Integer> predicate = (i) -> true;
        while (state.keepRunning()) {
            for (int i = 0; i < NUM_ITERATIONS; ++i) {
                ArraySet<Integer> set = new ArraySet<>();
                for (int j = 0; j < SET_SIZE_SMALL; j++) {
                    set.add(j);
                }
                set.removeIf(predicate);
            }
        }
    }

    @Test
    public void testRemoveIf_Small_RemoveHalf() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        Predicate<Integer> predicate = (i) -> i % 2 == 0;
        while (state.keepRunning()) {
            for (int i = 0; i < NUM_ITERATIONS; ++i) {
                ArraySet<Integer> set = new ArraySet<>();
                for (int j = 0; j < SET_SIZE_SMALL; ++j) {
                    set.add(j);
                }
                set.removeIf(predicate);
            }
        }
    }

    /**
     * This is the same code as testRemoveIf_Large_* without the removeIf in order to measure
     * the performance of the rest of the code in the loop.
     */
    @Test
    public void testRemoveIf_Large_Base() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        Predicate<Integer> predicate = (i) -> i % 2 == 0;
        while (state.keepRunning()) {
            for (int i = 0; i < NUM_ITERATIONS; ++i) {
                ArraySet<Integer> set = new ArraySet<>();
                for (int j = 0; j < SET_SIZE_LARGE; ++j) {
                    set.add(j);
                }
            }
        }
    }

    @Test
    public void testRemoveIf_Large_RemoveNothing() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        Predicate<Integer> predicate = (i) -> false;
        while (state.keepRunning()) {
            for (int i = 0; i < NUM_ITERATIONS; ++i) {
                ArraySet<Integer> set = new ArraySet<>();
                for (int j = 0; j < SET_SIZE_LARGE; ++j) {
                    set.add(j);
                }
                set.removeIf(predicate);
            }
        }
    }

    @Test
    public void testRemoveIf_Large_RemoveAll() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        Predicate<Integer> predicate = (i) -> true;
        while (state.keepRunning()) {
            for (int i = 0; i < NUM_ITERATIONS; ++i) {
                ArraySet<Integer> set = new ArraySet<>();
                for (int j = 0; j < SET_SIZE_LARGE; ++j) {
                    set.add(j);
                }
                set.removeIf(predicate);
            }
        }
    }

    @Test
    public void testRemoveIf_Large_RemoveHalf() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        Predicate<Integer> predicate = (i) -> i % 2 == 0;
        while (state.keepRunning()) {
            for (int i = 0; i < NUM_ITERATIONS; ++i) {
                ArraySet<Integer> set = new ArraySet<>();
                for (int j = 0; j < SET_SIZE_LARGE; ++j) {
                    set.add(j);
                }
                set.removeIf(predicate);
            }
        }
    }
}
