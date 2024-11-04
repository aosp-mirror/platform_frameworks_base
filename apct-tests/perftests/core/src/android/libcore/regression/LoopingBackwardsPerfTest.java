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
import java.util.Collection;

/**
 * Testing the old canard that looping backwards is faster.
 *
 * @author Kevin Bourrillion
 */
@RunWith(JUnitParamsRunner.class)
@LargeTest
public class LoopingBackwardsPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    public static Collection<Object[]> getData() {
        return Arrays.asList(new Object[][] {{2}, {20}, {2000}, {20000000}});
    }

    @Test
    @Parameters(method = "getData")
    public void timeForwards(int max) {
        int fake = 0;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int j = 0; j < max; j++) {
                fake += j;
            }
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeBackwards(int max) {
        int fake = 0;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int j = max - 1; j >= 0; j--) {
                fake += j;
            }
        }
    }
}
