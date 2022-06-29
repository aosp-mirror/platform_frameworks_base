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

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(Parameterized.class)
@LargeTest
public final class MutableIntPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    enum Kind {
        ARRAY() {
            int[] mValue = new int[1];

            @Override
            void timeCreate(BenchmarkState state) {
                while (state.keepRunning()) {
                    mValue = new int[] {5};
                }
            }

            @Override
            void timeIncrement(BenchmarkState state) {
                while (state.keepRunning()) {
                    mValue[0]++;
                }
            }

            @Override
            int timeGet(BenchmarkState state) {
                int sum = 0;
                while (state.keepRunning()) {
                    sum += mValue[0];
                }
                return sum;
            }
        },
        ATOMIC() {
            AtomicInteger mValue = new AtomicInteger();

            @Override
            void timeCreate(BenchmarkState state) {
                while (state.keepRunning()) {
                    mValue = new AtomicInteger(5);
                }
            }

            @Override
            void timeIncrement(BenchmarkState state) {
                while (state.keepRunning()) {
                    mValue.incrementAndGet();
                }
            }

            @Override
            int timeGet(BenchmarkState state) {
                int sum = 0;
                while (state.keepRunning()) {
                    sum += mValue.intValue();
                }
                return sum;
            }
        };

        abstract void timeCreate(BenchmarkState state);

        abstract void timeIncrement(BenchmarkState state);

        abstract int timeGet(BenchmarkState state);
    }

    @Parameters(name = "mKind={0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] {{Kind.ARRAY}, {Kind.ATOMIC}});
    }

    @Parameterized.Parameter(0)
    public Kind mKind;

    @Test
    public void timeCreate() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        mKind.timeCreate(state);
    }

    @Test
    public void timeIncrement() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        mKind.timeIncrement(state);
    }

    @Test
    public void timeGet() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        mKind.timeGet(state);
    }
}
