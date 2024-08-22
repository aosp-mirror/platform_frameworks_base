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

import androidx.benchmark.BenchmarkState;
import androidx.benchmark.junit4.BenchmarkRule;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * What do various kinds of addition cost?
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class AdditionPerfTest {

    @Rule
    public BenchmarkRule mBenchmarkRule = new BenchmarkRule();

    @Test
    public void timeAddConstantToLocalInt() {
        final BenchmarkState state = mBenchmarkRule.getState();
        int result = 0;
        while (state.keepRunning()) {
            result += 123;
        }
    }
    @Test
    public void timeAddTwoLocalInts() {
        final BenchmarkState state = mBenchmarkRule.getState();
        int result = 0;
        int constant = 123;
        while (state.keepRunning()) {
            result += constant;
        }
    }
    @Test
    public void timeAddConstantToLocalLong() {
        final BenchmarkState state = mBenchmarkRule.getState();
        long result = 0;
        while (state.keepRunning()) {
            result += 123L;
        }
    }
    @Test
    public void timeAddTwoLocalLongs() {
        final BenchmarkState state = mBenchmarkRule.getState();
        long result = 0;
        long constant = 123L;
        while (state.keepRunning()) {
            result += constant;
        }
    }
    @Test
    public void timeAddConstantToLocalFloat() {
        final BenchmarkState state = mBenchmarkRule.getState();
        float result = 0.0f;
        while (state.keepRunning()) {
            result += 123.0f;
        }
    }
    @Test
    public void timeAddTwoLocalFloats() {
        final BenchmarkState state = mBenchmarkRule.getState();
        float result = 0.0f;
        float constant = 123.0f;
        while (state.keepRunning()) {
            result += constant;
        }
    }
    @Test
    public void timeAddConstantToLocalDouble() {
        final BenchmarkState state = mBenchmarkRule.getState();
        double result = 0.0;
        while (state.keepRunning()) {
            result += 123.0;
        }
    }
    @Test
    public void timeAddTwoLocalDoubles() {
        final BenchmarkState state = mBenchmarkRule.getState();
        double result = 0.0;
        double constant = 123.0;
        while (state.keepRunning()) {
            result += constant;
        }
    }
}
