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

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;

@RunWith(Parameterized.class)
@LargeTest
public class BitSetPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Parameters(name = "mSize={0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] {{1000}, {10000}});
    }

    @Parameterized.Parameter(0)
    public int mSize;

    private BitSet mBitSet;

    @Before
    public void setUp() throws Exception {
        mBitSet = new BitSet(mSize);
    }

    @Test
    public void timeIsEmptyTrue() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            if (!mBitSet.isEmpty()) throw new RuntimeException();
        }
    }

    @Test
    public void timeIsEmptyFalse() {
        mBitSet.set(mBitSet.size() - 1);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            if (mBitSet.isEmpty()) throw new RuntimeException();
        }
    }

    @Test
    public void timeGet() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        int i = 1;
        while (state.keepRunning()) {
            mBitSet.get(++i % mSize);
        }
    }

    @Test
    public void timeClear() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        int i = 1;
        while (state.keepRunning()) {
            mBitSet.clear(++i % mSize);
        }
    }

    @Test
    public void timeSet() {
        int i = 1;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mBitSet.set(++i % mSize);
        }
    }

    @Test
    public void timeSetOn() {
        int i = 1;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mBitSet.set(++i % mSize, true);
        }
    }

    @Test
    public void timeSetOff() {
        int i = 1;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mBitSet.set(++i % mSize, false);
        }
    }
}
