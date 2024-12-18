/*
 * Copyright (C) 2020 The Android Open Source Project
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

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.math.BigInteger;

/**
 * Tries to measure important BigInteger operations across a variety of BigInteger sizes. Note that
 * BigInteger implementations commonly need to use wildly different algorithms for different sizes,
 * so relative performance may change substantially depending on the size of the integer. This is
 * not structured as a proper benchmark; just run main(), e.g. with vogar
 * libcore/benchmarks/src/benchmarks/BigIntegerBenchmark.java.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class BigIntegerPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    // A simple sum of products computation, mostly so we can check timing in the
    // absence of any division. Computes the sum from 1 to n of ((10^prec) << 30) + 1)^2,
    // repeating the multiplication, but not addition of 1, each time through the loop.
    // Check the last few bits of the result as we go. Assumes n < 2^30.
    // Note that we're actually squaring values in computing the product.
    // That affects the algorithm used by some implementations.
    private static void inner(int n, int prec) {
        BigInteger big = BigInteger.TEN.pow(prec).shiftLeft(30).add(BigInteger.ONE);
        BigInteger sum = BigInteger.ZERO;
        for (int i = 0; i < n; ++i) {
            sum = sum.add(big.multiply(big));
        }
        if (sum.and(BigInteger.valueOf(0x3fffffff)).intValue() != n) {
            throw new AssertionError(
                    "inner() got " + sum.and(BigInteger.valueOf(0x3fffffff)) + " instead of " + n);
        }
    }

    // Execute the above rep times, optionally timing it.
    @Test
    public void repeatInner() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 10; i <= 10_000; i *= 10) {
                inner(100, i);
            }
        }
    }

    // Approximate the sum of the first 1000 terms of the harmonic series (sum of 1/m as m
    // goes from 1 to n) to about prec digits. The result has an implicit decimal point
    // prec digits from the right.
    private static BigInteger harmonic1000(int prec) {
        BigInteger scaledOne = BigInteger.TEN.pow(prec);
        BigInteger sum = BigInteger.ZERO;
        for (int i = 1; i <= 1000; ++i) {
            sum = sum.add(scaledOne.divide(BigInteger.valueOf(i)));
        }
        return sum;
    }

    // Execute the above rep times, optionally timing it.
    // Check results for equality, and print one, to compaare against reference.
    @Test
    public void repeatHarmonic1000() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 5; i <= 5_000; i *= 10) {
                BigInteger refRes = harmonic1000(i);
                BigInteger newRes = harmonic1000(i);
                if (!newRes.equals(refRes)) {
                    throw new AssertionError(newRes + " != " + refRes);
                }
                if (i >= 50
                        && !refRes.toString()
                                .startsWith("748547086055034491265651820433390017652167916970")) {
                    throw new AssertionError("harmanic(" + i + ") incorrectly produced " + refRes);
                }
            }
        }
    }

    // Repeatedly execute just the base conversion from the last test, allowing
    // us to time and check it for consistency as well.
    @Test
    public void repeatToString() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 5; i <= 5_000; i *= 10) {
                BigInteger refRes = harmonic1000(i);
                String refString = refRes.toString();
                // Disguise refRes to avoid compiler optimization issues.
                BigInteger newRes = refRes.shiftLeft(30).add(BigInteger.valueOf(i)).shiftRight(30);
                // The time-consuming part:
                String newString = newRes.toString();
            }
        }
    }

    // Compute base^exp, where base and result are scaled/multiplied by scaleBy to make them
    // integers. exp >= 0 .
    private static BigInteger myPow(BigInteger base, int exp, BigInteger scaleBy) {
        if (exp == 0) {
            return scaleBy; // Return one.
        } else if ((exp & 1) != 0) {
            BigInteger tmp = myPow(base, exp - 1, scaleBy);
            return tmp.multiply(base).divide(scaleBy);
        } else {
            BigInteger tmp = myPow(base, exp / 2, scaleBy);
            return tmp.multiply(tmp).divide(scaleBy);
        }
    }

    // Approximate e by computing (1 + 1/n)^n to prec decimal digits.
    // This isn't necessarily a very good approximation to e.
    // Return the result, scaled by 10^prec.
    private static BigInteger eApprox(int n, int prec) {
        BigInteger scaledOne = BigInteger.TEN.pow(prec);
        BigInteger base = scaledOne.add(scaledOne.divide(BigInteger.valueOf(n)));
        return myPow(base, n, scaledOne);
    }

    // Repeatedly execute and check the above, printing one of the results
    // to compare to reference.
    @Test
    public void repeatEApprox() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 10; i <= 10_000; i *= 10) {
                BigInteger refRes = eApprox(100_000, i);
                BigInteger newRes = eApprox(100_000, i);
                if (!newRes.equals(refRes)) {
                    throw new AssertionError(newRes + " != " + refRes);
                }
                if (i >= 10 && !refRes.toString().startsWith("271826")) {
                    throw new AssertionError(
                            "eApprox(" + 100_000 + "," + i + ") incorrectly produced " + refRes);
                }
            }
        }
    }

    // Test / time modPow()
    @Test
    public void repeatModPow() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 5; i <= 500; i *= 10) {
                BigInteger odd1 = BigInteger.TEN.pow(i / 2).add(BigInteger.ONE);
                BigInteger odd2 = BigInteger.TEN.pow(i / 2).add(BigInteger.valueOf(17));
                BigInteger product = odd1.multiply(odd2);
                BigInteger exponent = BigInteger.TEN.pow(i / 2 - 1);
                BigInteger base = BigInteger.TEN.pow(i / 4);
                BigInteger newRes = base.modPow(exponent, product);
                if (!newRes.mod(odd1).equals(base.modPow(exponent, odd1))) {
                    throw new AssertionError(
                            "ModPow() result incorrect mod odd1:"
                                    + odd1
                                    + "; lastRes.mod(odd1)="
                                    + newRes.mod(odd1)
                                    + " vs. "
                                    + "base.modPow(exponent, odd1)="
                                    + base.modPow(exponent, odd1)
                                    + " base="
                                    + base
                                    + " exponent="
                                    + exponent);
                }
                if (!newRes.mod(odd2).equals(base.modPow(exponent, odd2))) {
                    throw new AssertionError("ModPow() result incorrect mod odd2");
                }
            }
        }
    }

    // Test / time modInverse()
    @Test
    public void repeatModInverse() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 10; i <= 10_000; i *= 10) {
                BigInteger odd1 = BigInteger.TEN.pow(i / 2).add(BigInteger.ONE);
                BigInteger odd2 = BigInteger.TEN.pow(i / 2).add(BigInteger.valueOf(17));
                BigInteger product = odd1.multiply(odd2);
                BigInteger arg = BigInteger.ONE.shiftLeft(i / 4);
                BigInteger lastRes = null;
                BigInteger newRes = arg.modInverse(product);
                lastRes = newRes;
                if (!lastRes.mod(odd1).equals(arg.modInverse(odd1))) {
                    throw new AssertionError("ModInverse() result incorrect mod odd1");
                }
                if (!lastRes.mod(odd2).equals(arg.modInverse(odd2))) {
                    throw new AssertionError("ModInverse() result incorrect mod odd2");
                }
            }
        }
    }
}
