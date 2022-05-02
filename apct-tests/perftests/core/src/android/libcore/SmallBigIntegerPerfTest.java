/*
 * Copyright (C) 2022 The Android Open Source Project.
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

import java.math.BigInteger;
import java.util.Random;

/**
 * This measures performance of operations on small BigIntegers. We manually determine the number of
 * iterations so that it should cause total memory allocation on the order of a few hundred
 * megabytes. Due to BigInteger's reliance on finalization, these may unfortunately all be kept
 * around at once.
 *
 * <p>This is not structured as a proper benchmark; just run main(), e.g. with vogar
 * libcore/benchmarks/src/benchmarks/SmallBigIntegerBenchmark.java
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class SmallBigIntegerPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();
    // We allocate about 2 1/3 BigIntegers per iteration.
    // Assuming 100 bytes/BigInteger, this gives us around 500MB total.
    static final BigInteger BIG_THREE = BigInteger.valueOf(3);
    static final BigInteger BIG_FOUR = BigInteger.valueOf(4);

    @Test
    public void testSmallBigInteger() {
        final Random r = new Random();
        BigInteger x = new BigInteger(20, r);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            // We know this converges, but the compiler doesn't.
            if (x.and(BigInteger.ONE).equals(BigInteger.ONE)) {
                x = x.multiply(BIG_THREE).add(BigInteger.ONE);
            } else {
                x = x.shiftRight(1);
            }
        }
        if (x.signum() < 0 || x.compareTo(BIG_FOUR) > 0) {
            throw new AssertionError("Something went horribly wrong.");
        }
    }
}
