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

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.test.suitebuilder.annotation.LargeTest;

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
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void timeNewDateFormatTimeInstance() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            DateFormat df = DateFormat.getTimeInstance(DateFormat.SHORT);
            df.format(System.currentTimeMillis());
        }
    }

    @Test
    public void timeClonedDateFormatTimeInstance() {
        DateFormat df = DateFormat.getTimeInstance(DateFormat.SHORT);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            ((DateFormat) df.clone()).format(System.currentTimeMillis());
        }
    }

    @Test
    public void timeReusedDateFormatTimeInstance() {
        DateFormat df = DateFormat.getTimeInstance(DateFormat.SHORT);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            synchronized (df) {
                df.format(System.currentTimeMillis());
            }
        }
    }

    @Test
    public void timeNewCollator() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Collator.getInstance(Locale.US);
        }
    }

    @Test
    public void timeClonedCollator() {
        Collator c = Collator.getInstance(Locale.US);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            c.clone();
        }
    }

    @Test
    public void timeNewDateFormatSymbols() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            new DateFormatSymbols(Locale.US);
        }
    }

    @Test
    public void timeClonedDateFormatSymbols() {
        DateFormatSymbols dfs = new DateFormatSymbols(Locale.US);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            dfs.clone();
        }
    }

    @Test
    public void timeNewDecimalFormatSymbols() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            new DecimalFormatSymbols(Locale.US);
        }
    }

    @Test
    public void timeClonedDecimalFormatSymbols() {
        DecimalFormatSymbols dfs = new DecimalFormatSymbols(Locale.US);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            dfs.clone();
        }
    }

    @Test
    public void timeNewNumberFormat() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            NumberFormat.getInstance(Locale.US);
        }
    }

    @Test
    public void timeClonedNumberFormat() {
        NumberFormat nf = NumberFormat.getInstance(Locale.US);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            nf.clone();
        }
    }

    @Test
    public void timeLongToString() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Long.toString(1024L);
        }
    }

    @Test
    public void timeNumberFormatTrivialFormatDouble() {
        NumberFormat nf = NumberFormat.getInstance(Locale.US);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            nf.format(1024.0);
        }
    }

    @Test
    public void timeClonedSimpleDateFormat() {
        SimpleDateFormat sdf = new SimpleDateFormat();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            sdf.clone();
        }
    }

    @Test
    public void timeNewGregorianCalendar() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            new GregorianCalendar();
        }
    }

    @Test
    public void timeClonedGregorianCalendar() {
        GregorianCalendar gc = new GregorianCalendar();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            gc.clone();
        }
    }
}
