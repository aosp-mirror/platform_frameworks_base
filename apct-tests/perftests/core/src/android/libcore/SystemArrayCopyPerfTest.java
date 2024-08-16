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

package android.libcore;

import androidx.benchmark.BenchmarkState;
import androidx.benchmark.junit4.BenchmarkRule;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collection;

@RunWith(JUnitParamsRunner.class)
@LargeTest
public class SystemArrayCopyPerfTest {
    @Rule
    public BenchmarkRule mBenchmarkRule = new BenchmarkRule();

    public static Collection<Object[]> getData() {
        return Arrays.asList(
                new Object[][] {
                    {2}, {4}, {8}, {16}, {32}, {64}, {128}, {256}, {512}, {1024}, {2048}, {4096},
                    {8192}, {16384}, {32768}, {65536}, {131072}, {262144}
                });
    }

    // Provides benchmarking for different types of arrays using the arraycopy function.
    @Test
    @Parameters(method = "getData")
    public void timeSystemCharArrayCopy(int arrayLength) {
        final int len = arrayLength;
        char[] src = new char[len];
        char[] dst = new char[len];
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            System.arraycopy(src, 0, dst, 0, len);
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeSystemByteArrayCopy(int arrayLength) {
        final int len = arrayLength;
        byte[] src = new byte[len];
        byte[] dst = new byte[len];
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            System.arraycopy(src, 0, dst, 0, len);
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeSystemShortArrayCopy(int arrayLength) {
        final int len = arrayLength;
        short[] src = new short[len];
        short[] dst = new short[len];
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            System.arraycopy(src, 0, dst, 0, len);
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeSystemIntArrayCopy(int arrayLength) {
        final int len = arrayLength;
        int[] src = new int[len];
        int[] dst = new int[len];
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            System.arraycopy(src, 0, dst, 0, len);
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeSystemLongArrayCopy(int arrayLength) {
        final int len = arrayLength;
        long[] src = new long[len];
        long[] dst = new long[len];
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            System.arraycopy(src, 0, dst, 0, len);
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeSystemFloatArrayCopy(int arrayLength) {
        final int len = arrayLength;
        float[] src = new float[len];
        float[] dst = new float[len];
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            System.arraycopy(src, 0, dst, 0, len);
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeSystemDoubleArrayCopy(int arrayLength) {
        final int len = arrayLength;
        double[] src = new double[len];
        double[] dst = new double[len];
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            System.arraycopy(src, 0, dst, 0, len);
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeSystemBooleanArrayCopy(int arrayLength) {
        final int len = arrayLength;
        boolean[] src = new boolean[len];
        boolean[] dst = new boolean[len];
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            System.arraycopy(src, 0, dst, 0, len);
        }
    }
}
