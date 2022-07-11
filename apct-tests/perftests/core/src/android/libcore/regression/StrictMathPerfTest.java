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
public class StrictMathPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private final double mDouble = 1.2;
    private final float mFloat = 1.2f;
    private final int mInt = 1;
    private final long mLong = 1L;

    /* Values for full line coverage of ceiling function */
    private static final double[] CEIL_DOUBLES =
            new double[] {
                3245817.2018463886,
                1418139.083668501,
                3.572936802189103E15,
                -4.7828929737254625E249,
                213596.58636369856,
                6.891928421440976E-96,
                -7.9318566885477E-36,
                -1.9610339084804148E15,
                -4.696725715628246E10,
                3742491.296880909,
                7.140274745333553E11
            };

    /* Values for full line coverage of floor function */
    private static final double[] FLOOR_DOUBLES =
            new double[] {
                7.140274745333553E11,
                3742491.296880909,
                -4.696725715628246E10,
                -1.9610339084804148E15,
                7.049948629370372E-56,
                -7.702933170334643E-16,
                -1.99657681810579,
                -1.1659287182288336E236,
                4.085518816513057E15,
                -1500948.440658056,
                -2.2316479921415575E7
            };

    @Test
    public void timeAbsD() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.abs(mDouble);
        }
    }

    @Test
    public void timeAbsF() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.abs(mFloat);
        }
    }

    @Test
    public void timeAbsI() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.abs(mInt);
        }
    }

    @Test
    public void timeAbsL() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.abs(mLong);
        }
    }

    @Test
    public void timeAcos() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.acos(mDouble);
        }
    }

    @Test
    public void timeAsin() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.asin(mDouble);
        }
    }

    @Test
    public void timeAtan() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.atan(mDouble);
        }
    }

    @Test
    public void timeAtan2() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.atan2(3, 4);
        }
    }

    @Test
    public void timeCbrt() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.cbrt(mDouble);
        }
    }

    @Test
    public void timeCeilOverInterestingValues() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 0; i < CEIL_DOUBLES.length; ++i) {
                StrictMath.ceil(CEIL_DOUBLES[i]);
            }
        }
    }

    @Test
    public void timeCopySignD() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.copySign(mDouble, mDouble);
        }
    }

    @Test
    public void timeCopySignF() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.copySign(mFloat, mFloat);
        }
    }

    @Test
    public void timeCos() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.cos(mDouble);
        }
    }

    @Test
    public void timeCosh() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.cosh(mDouble);
        }
    }

    @Test
    public void timeExp() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.exp(mDouble);
        }
    }

    @Test
    public void timeExpm1() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.expm1(mDouble);
        }
    }

    @Test
    public void timeFloorOverInterestingValues() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 0; i < FLOOR_DOUBLES.length; ++i) {
                StrictMath.floor(FLOOR_DOUBLES[i]);
            }
        }
    }

    @Test
    public void timeGetExponentD() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.getExponent(mDouble);
        }
    }

    @Test
    public void timeGetExponentF() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.getExponent(mFloat);
        }
    }

    @Test
    public void timeHypot() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.hypot(mDouble, mDouble);
        }
    }

    @Test
    public void timeIEEEremainder() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.IEEEremainder(mDouble, mDouble);
        }
    }

    @Test
    public void timeLog() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.log(mDouble);
        }
    }

    @Test
    public void timeLog10() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.log10(mDouble);
        }
    }

    @Test
    public void timeLog1p() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.log1p(mDouble);
        }
    }

    @Test
    public void timeMaxD() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.max(mDouble, mDouble);
        }
    }

    @Test
    public void timeMaxF() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.max(mFloat, mFloat);
        }
    }

    @Test
    public void timeMaxI() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.max(mInt, mInt);
        }
    }

    @Test
    public void timeMaxL() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.max(mLong, mLong);
        }
    }

    @Test
    public void timeMinD() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.min(mDouble, mDouble);
        }
    }

    @Test
    public void timeMinF() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.min(mFloat, mFloat);
        }
    }

    @Test
    public void timeMinI() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.min(mInt, mInt);
        }
    }

    @Test
    public void timeMinL() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.min(mLong, mLong);
        }
    }

    @Test
    public void timeNextAfterD() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.nextAfter(mDouble, mDouble);
        }
    }

    @Test
    public void timeNextAfterF() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.nextAfter(mFloat, mFloat);
        }
    }

    @Test
    public void timeNextUpD() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.nextUp(mDouble);
        }
    }

    @Test
    public void timeNextUpF() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.nextUp(mFloat);
        }
    }

    @Test
    public void timePow() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.pow(mDouble, mDouble);
        }
    }

    @Test
    public void timeRandom() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.random();
        }
    }

    @Test
    public void timeRint() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.rint(mDouble);
        }
    }

    @Test
    public void timeRoundD() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.round(mDouble);
        }
    }

    @Test
    public void timeRoundF() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.round(mFloat);
        }
    }

    @Test
    public void timeScalbD() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.scalb(mDouble, 5);
        }
    }

    @Test
    public void timeScalbF() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.scalb(mFloat, 5);
        }
    }

    @Test
    public void timeSignumD() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.signum(mDouble);
        }
    }

    @Test
    public void timeSignumF() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.signum(mFloat);
        }
    }

    @Test
    public void timeSin() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.sin(mDouble);
        }
    }

    @Test
    public void timeSinh() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.sinh(mDouble);
        }
    }

    @Test
    public void timeSqrt() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.sqrt(mDouble);
        }
    }

    @Test
    public void timeTan() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.tan(mDouble);
        }
    }

    @Test
    public void timeTanh() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.tanh(mDouble);
        }
    }

    @Test
    public void timeToDegrees() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.toDegrees(mDouble);
        }
    }

    @Test
    public void timeToRadians() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.toRadians(mDouble);
        }
    }

    @Test
    public void timeUlpD() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.ulp(mDouble);
        }
    }

    @Test
    public void timeUlpF() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StrictMath.ulp(mFloat);
        }
    }
}
