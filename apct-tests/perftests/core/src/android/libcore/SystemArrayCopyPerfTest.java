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

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.test.suitebuilder.annotation.LargeTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
@LargeTest
public class SystemArrayCopyPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Parameters(name = "arrayLength={0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(
                new Object[][] {
                    {2}, {4}, {8}, {16}, {32}, {64}, {128}, {256}, {512}, {1024}, {2048}, {4096},
                    {8192}, {16384}, {32768}, {65536}, {131072}, {262144}
                });
    }

    @Parameterized.Parameter(0)
    public int arrayLength;

    // Provides benchmarking for different types of arrays using the arraycopy function.
    @Test
    public void timeSystemCharArrayCopy() {
        final int len = arrayLength;
        char[] src = new char[len];
        char[] dst = new char[len];
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            System.arraycopy(src, 0, dst, 0, len);
        }
    }

    @Test
    public void timeSystemByteArrayCopy() {
        final int len = arrayLength;
        byte[] src = new byte[len];
        byte[] dst = new byte[len];
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            System.arraycopy(src, 0, dst, 0, len);
        }
    }

    @Test
    public void timeSystemShortArrayCopy() {
        final int len = arrayLength;
        short[] src = new short[len];
        short[] dst = new short[len];
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            System.arraycopy(src, 0, dst, 0, len);
        }
    }

    @Test
    public void timeSystemIntArrayCopy() {
        final int len = arrayLength;
        int[] src = new int[len];
        int[] dst = new int[len];
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            System.arraycopy(src, 0, dst, 0, len);
        }
    }

    @Test
    public void timeSystemLongArrayCopy() {
        final int len = arrayLength;
        long[] src = new long[len];
        long[] dst = new long[len];
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            System.arraycopy(src, 0, dst, 0, len);
        }
    }

    @Test
    public void timeSystemFloatArrayCopy() {
        final int len = arrayLength;
        float[] src = new float[len];
        float[] dst = new float[len];
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            System.arraycopy(src, 0, dst, 0, len);
        }
    }

    @Test
    public void timeSystemDoubleArrayCopy() {
        final int len = arrayLength;
        double[] src = new double[len];
        double[] dst = new double[len];
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            System.arraycopy(src, 0, dst, 0, len);
        }
    }

    @Test
    public void timeSystemBooleanArrayCopy() {
        final int len = arrayLength;
        boolean[] src = new boolean[len];
        boolean[] dst = new boolean[len];
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            System.arraycopy(src, 0, dst, 0, len);
        }
    }
}
