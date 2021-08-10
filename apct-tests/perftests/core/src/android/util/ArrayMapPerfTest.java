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

import java.util.function.BiConsumer;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class ArrayMapPerfTest {
    private static final int NUM_ITERATIONS = 100;
    private static final int SET_SIZE_SMALL = 10;
    private static final int SET_SIZE_LARGE = 50;

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void testForEach_Small() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        BiConsumer<String, Integer> consumer = (s, i) -> {
        };
        while (state.keepRunning()) {
            for (int i = 0; i < NUM_ITERATIONS; ++i) {
                ArrayMap<String, Integer> map = new ArrayMap<>();
                for (int j = 0; j < SET_SIZE_SMALL; j++) {
                    map.put(Integer.toString(j), j);
                }
                map.forEach(consumer);
            }
        }
    }

    @Test
    public void testForEach_Large() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        BiConsumer<String, Integer> consumer = (s, i) -> {
        };
        while (state.keepRunning()) {
            for (int i = 0; i < NUM_ITERATIONS; ++i) {
                ArrayMap<String, Integer> map = new ArrayMap<>();
                for (int j = 0; j < SET_SIZE_LARGE; j++) {
                    map.put(Integer.toString(j), j);
                }
                map.forEach(consumer);
            }
        }
    }
}
