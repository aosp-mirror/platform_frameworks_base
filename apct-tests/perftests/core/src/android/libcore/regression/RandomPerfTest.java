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

import androidx.benchmark.BenchmarkState;
import androidx.benchmark.junit4.BenchmarkRule;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.SecureRandom;
import java.util.Random;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class RandomPerfTest {
    @Rule public BenchmarkRule mBenchmarkRule = new BenchmarkRule();

    @Test
    public void timeNewRandom() throws Exception {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            Random rng = new Random();
            rng.nextInt();
        }
    }

    @Test
    public void timeReusedRandom() throws Exception {
        Random rng = new Random();
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            rng.nextInt();
        }
    }

    @Test
    public void timeReusedSecureRandom() throws Exception {
        SecureRandom rng = new SecureRandom();
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            rng.nextInt();
        }
    }

    @Test
    public void timeNewSecureRandom() throws Exception {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            SecureRandom rng = new SecureRandom();
            rng.nextInt();
        }
    }
}
