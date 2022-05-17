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
 * How do various ways of iterating through an array compare?
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class ArrayIterationPerfTest {

    public class Foo {
        int mSplat;
    }

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    Foo[] mArray = new Foo[27];
    {
        for (int i = 0; i < mArray.length; ++i) mArray[i] = new Foo();
    }
    @Test
    public void timeArrayIteration() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            int sum = 0;
            for (int i = 0; i < mArray.length; i++) {
                sum += mArray[i].mSplat;
            }
        }
    }
    @Test
    public void timeArrayIterationCached() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            int sum = 0;
            Foo[] localArray = mArray;
            int len = localArray.length;

            for (int i = 0; i < len; i++) {
                sum += localArray[i].mSplat;
            }
        }
    }
    @Test
    public void timeArrayIterationForEach() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            int sum = 0;
            for (Foo a: mArray) {
                sum += a.mSplat;
            }
        }
    }
}
