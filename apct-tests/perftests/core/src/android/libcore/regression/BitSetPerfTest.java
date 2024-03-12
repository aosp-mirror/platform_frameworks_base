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

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;

@RunWith(JUnitParamsRunner.class)
@LargeTest
public class BitSetPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    public static Collection<Object[]> getData() {
        return Arrays.asList(new Object[][] {{1000}, {10000}});
    }

    @Test
    @Parameters(method = "getData")
    public void timeIsEmptyTrue(int size) {
        BitSet bitSet = new BitSet(size);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            if (!bitSet.isEmpty()) throw new RuntimeException();
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeIsEmptyFalse(int size) {
        BitSet bitSet = new BitSet(size);
        bitSet.set(bitSet.size() - 1);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            if (bitSet.isEmpty()) throw new RuntimeException();
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeGet(int size) {
        BitSet bitSet = new BitSet(size);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        int i = 1;
        while (state.keepRunning()) {
            bitSet.get(++i % size);
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeClear(int size) {
        BitSet bitSet = new BitSet(size);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        int i = 1;
        while (state.keepRunning()) {
            bitSet.clear(++i % size);
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeSet(int size) {
        BitSet bitSet = new BitSet(size);
        int i = 1;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            bitSet.set(++i % size);
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeSetOn(int size) {
        BitSet bitSet = new BitSet(size);
        int i = 1;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            bitSet.set(++i % size, true);
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeSetOff(int size) {
        BitSet bitSet = new BitSet(size);
        int i = 1;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            bitSet.set(++i % size, false);
        }
    }
}
