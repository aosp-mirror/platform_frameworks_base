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
import android.test.suitebuilder.annotation.LargeTest;

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
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void timeAddConstantToLocalInt() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        int result = 0;
        while (state.keepRunning()) {
            result += 123;
        }
    }
    @Test
    public void timeAddTwoLocalInts() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        int result = 0;
        int constant = 123;
        while (state.keepRunning()) {
            result += constant;
        }
    }
    @Test
    public void timeAddConstantToLocalLong() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        long result = 0;
        while (state.keepRunning()) {
            result += 123L;
        }
    }
    @Test
    public void timeAddTwoLocalLongs() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        long result = 0;
        long constant = 123L;
        while (state.keepRunning()) {
            result += constant;
        }
    }
    @Test
    public void timeAddConstantToLocalFloat() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        float result = 0.0f;
        while (state.keepRunning()) {
            result += 123.0f;
        }
    }
    @Test
    public void timeAddTwoLocalFloats() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        float result = 0.0f;
        float constant = 123.0f;
        while (state.keepRunning()) {
            result += constant;
        }
    }
    @Test
    public void timeAddConstantToLocalDouble() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        double result = 0.0;
        while (state.keepRunning()) {
            result += 123.0;
        }
    }
    @Test
    public void timeAddTwoLocalDoubles() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        double result = 0.0;
        double constant = 123.0;
        while (state.keepRunning()) {
            result += constant;
        }
    }
}
