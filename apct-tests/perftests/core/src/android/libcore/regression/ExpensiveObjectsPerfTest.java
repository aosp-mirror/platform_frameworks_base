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

package android.libcore.regression;

import androidx.benchmark.BenchmarkState;
import androidx.benchmark.junit4.BenchmarkRule;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.text.Collator;
import java.text.DateFormat;
import java.text.DateFormatSymbols;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.GregorianCalendar;
import java.util.Locale;

/**
 * Benchmarks creation and cloning various expensive objects.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class ExpensiveObjectsPerfTest {
    @Rule public BenchmarkRule mBenchmarkRule = new BenchmarkRule();

    @Test
    public void timeNewDateFormatTimeInstance() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            DateFormat df = DateFormat.getTimeInstance(DateFormat.SHORT);
            df.format(System.currentTimeMillis());
        }
    }

    @Test(timeout = 900000)
    public void timeClonedDateFormatTimeInstance() {
        DateFormat df = DateFormat.getTimeInstance(DateFormat.SHORT);
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            ((DateFormat) df.clone()).format(System.currentTimeMillis());
        }
    }

    @Test
    public void timeReusedDateFormatTimeInstance() {
        DateFormat df = DateFormat.getTimeInstance(DateFormat.SHORT);
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            synchronized (df) {
                df.format(System.currentTimeMillis());
            }
        }
    }

    @Test(timeout = 900000)
    public void timeNewCollator() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            Collator.getInstance(Locale.US);
        }
    }

    @Test
    public void timeClonedCollator() {
        Collator c = Collator.getInstance(Locale.US);
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            c.clone();
        }
    }

    @Test
    public void timeNewDateFormatSymbols() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            new DateFormatSymbols(Locale.US);
        }
    }

    @Test
    public void timeClonedDateFormatSymbols() {
        DateFormatSymbols dfs = new DateFormatSymbols(Locale.US);
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            dfs.clone();
        }
    }

    @Test
    public void timeNewDecimalFormatSymbols() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            new DecimalFormatSymbols(Locale.US);
        }
    }

    @Test
    public void timeClonedDecimalFormatSymbols() {
        DecimalFormatSymbols dfs = new DecimalFormatSymbols(Locale.US);
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            dfs.clone();
        }
    }

    @Test
    public void timeNewNumberFormat() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            NumberFormat.getInstance(Locale.US);
        }
    }

    @Test
    public void timeClonedNumberFormat() {
        NumberFormat nf = NumberFormat.getInstance(Locale.US);
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            nf.clone();
        }
    }

    @Test
    public void timeLongToString() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            Long.toString(1024L);
        }
    }

    @Test
    public void timeNumberFormatTrivialFormatDouble() {
        NumberFormat nf = NumberFormat.getInstance(Locale.US);
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            nf.format(1024.0);
        }
    }

    @Test
    public void timeNewSimpleDateFormat() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            new SimpleDateFormat();
        }
    }

    @Test
    public void timeNewGregorianCalendar() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            new GregorianCalendar();
        }
    }

    @Test
    public void timeClonedGregorianCalendar() {
        GregorianCalendar gc = new GregorianCalendar();
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            gc.clone();
        }
    }
}
