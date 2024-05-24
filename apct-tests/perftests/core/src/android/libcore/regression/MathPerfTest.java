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
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Many of these tests are bogus in that the cost will vary wildly depending on inputs. For _my_
 * current purposes, that's okay. But beware!
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class MathPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private final double mDouble = 1.2;
    private final float mFloat = 1.2f;
    private final int mInt = 1;
    private final long mLong = 1L;

    // NOTE: To avoid the benchmarked function from being optimized away, we store the result
    // and use it as the benchmark's return value. This is good enough for now but may not be in
    // the future, a smart compiler could determine that the result value will depend on whether
    // we get into the loop or not and turn the whole loop into an if statement.

    @Test
    public void timeAbsD() {
        double result = mDouble;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.abs(mDouble);
        }
    }

    @Test
    public void timeAbsF() {
        float result = mFloat;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.abs(mFloat);
        }
    }

    @Test
    public void timeAbsI() {
        int result = mInt;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.abs(mInt);
        }
    }

    @Test
    public void timeAbsL() {
        long result = mLong;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.abs(mLong);
        }
    }

    @Test
    public void timeAcos() {
        double result = mDouble;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.acos(mDouble);
        }
    }

    @Test
    public void timeAsin() {
        double result = mDouble;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.asin(mDouble);
        }
    }

    @Test
    public void timeAtan() {
        double result = mDouble;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.atan(mDouble);
        }
    }

    @Test
    public void timeAtan2() {
        double result = mDouble;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.atan2(3, 4);
        }
    }

    @Test
    public void timeCbrt() {
        double result = mDouble;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.cbrt(mDouble);
        }
    }

    @Test
    public void timeCeil() {
        double result = mDouble;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.ceil(mDouble);
        }
    }

    @Test
    public void timeCopySignD() {
        double result = mDouble;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.copySign(mDouble, mDouble);
        }
    }

    @Test
    public void timeCopySignF() {
        float result = mFloat;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.copySign(mFloat, mFloat);
        }
    }

    @Test
    public void timeCopySignD_strict() {
        double result = mDouble;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = StrictMath.copySign(mDouble, mDouble);
        }
    }

    @Test
    public void timeCopySignF_strict() {
        float result = mFloat;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = StrictMath.copySign(mFloat, mFloat);
        }
    }

    @Test
    public void timeCos() {
        double result = mDouble;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.cos(mDouble);
        }
    }

    @Test
    public void timeCosh() {
        double result = mDouble;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.cosh(mDouble);
        }
    }

    @Test
    public void timeExp() {
        double result = mDouble;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.exp(mDouble);
        }
    }

    @Test
    public void timeExpm1() {
        double result = mDouble;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.expm1(mDouble);
        }
    }

    @Test
    public void timeFloor() {
        double result = mDouble;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.floor(mDouble);
        }
    }

    @Test
    public void timeGetExponentD() {
        int result = mInt;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.getExponent(mDouble);
        }
    }

    @Test
    public void timeGetExponentF() {
        int result = mInt;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.getExponent(mFloat);
        }
    }

    @Test
    public void timeHypot() {
        double result = mDouble;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.hypot(mDouble, mDouble);
        }
    }

    @Test
    public void timeIEEEremainder() {
        double result = mDouble;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.IEEEremainder(mDouble, mDouble);
        }
    }

    @Test
    public void timeLog() {
        double result = mDouble;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.log(mDouble);
        }
    }

    @Test
    public void timeLog10() {
        double result = mDouble;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.log10(mDouble);
        }
    }

    @Test
    public void timeLog1p() {
        double result = mDouble;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.log1p(mDouble);
        }
    }

    @Test
    public void timeMaxD() {
        double result = mDouble;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.max(mDouble, mDouble);
        }
    }

    @Test
    public void timeMaxF() {
        float result = mFloat;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.max(mFloat, mFloat);
        }
    }

    @Test
    public void timeMaxI() {
        int result = mInt;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.max(mInt, mInt);
        }
    }

    @Test
    public void timeMaxL() {
        long result = mLong;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.max(mLong, mLong);
        }
    }

    @Test
    public void timeMinD() {
        double result = mDouble;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.min(mDouble, mDouble);
        }
    }

    @Test
    public void timeMinF() {
        float result = mFloat;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.min(mFloat, mFloat);
        }
    }

    @Test
    public void timeMinI() {
        int result = mInt;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.min(mInt, mInt);
        }
    }

    @Test
    public void timeMinL() {
        long result = mLong;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.min(mLong, mLong);
        }
    }

    @Test
    public void timeNextAfterD() {
        double result = mDouble;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.nextAfter(mDouble, mDouble);
        }
    }

    @Test
    public void timeNextAfterF() {
        float result = mFloat;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.nextAfter(mFloat, mFloat);
        }
    }

    @Test
    public void timeNextUpD() {
        double result = mDouble;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.nextUp(mDouble);
        }
    }

    @Test
    public void timeNextUpF() {
        float result = mFloat;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.nextUp(mFloat);
        }
    }

    @Test
    public void timePow() {
        double result = mDouble;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.pow(mDouble, mDouble);
        }
    }

    @Test
    public void timeRandom() {
        double result = mDouble;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.random();
        }
    }

    @Test
    public void timeRint() {
        double result = mDouble;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.rint(mDouble);
        }
    }

    @Test
    public void timeRoundD() {
        long result = mLong;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.round(mDouble);
        }
    }

    @Test
    public void timeRoundF() {
        int result = mInt;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.round(mFloat);
        }
    }

    @Test
    public void timeScalbD() {
        double result = mDouble;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.scalb(mDouble, 5);
        }
    }

    @Test
    public void timeScalbF() {
        float result = mFloat;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.scalb(mFloat, 5);
        }
    }

    @Test
    public void timeSignumD() {
        double result = mDouble;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.signum(mDouble);
        }
    }

    @Test
    public void timeSignumF() {
        float result = mFloat;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.signum(mFloat);
        }
    }

    @Test
    public void timeSin() {
        double result = mDouble;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.sin(mDouble);
        }
    }

    @Test
    public void timeSinh() {
        double result = mDouble;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.sinh(mDouble);
        }
    }

    @Test
    public void timeSqrt() {
        double result = mDouble;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.sqrt(mDouble);
        }
    }

    @Test
    public void timeTan() {
        double result = mDouble;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.tan(mDouble);
        }
    }

    @Test
    public void timeTanh() {
        double result = mDouble;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.tanh(mDouble);
        }
    }

    @Test
    public void timeToDegrees() {
        double result = mDouble;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.toDegrees(mDouble);
        }
    }

    @Test
    public void timeToRadians() {
        double result = mDouble;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.toRadians(mDouble);
        }
    }

    @Test
    public void timeUlpD() {
        double result = mDouble;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.ulp(mDouble);
        }
    }

    @Test
    public void timeUlpF() {
        float result = mFloat;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = Math.ulp(mFloat);
        }
    }
}
