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

import java.util.Formatter;
import java.util.Locale;

/**
 * Compares Formatter against hand-written StringBuilder code.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class FormatterPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void timeFormatter_NoFormatting() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Formatter f = new Formatter();
            f.format("this is a reasonably short string that doesn't actually need any formatting");
        }
    }

    @Test
    public void timeStringBuilder_NoFormatting() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StringBuilder sb = new StringBuilder();
            sb.append("this is a reasonably short string that doesn't actually need formatting");
        }
    }

    @Test
    public void timeFormatter_OneInt() {
        Integer value = Integer.valueOf(1024); // We're not trying to benchmark boxing here.
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Formatter f = new Formatter();
            f.format("this is a reasonably short string that has an int %d in it", value);
        }
    }

    @Test
    public void timeFormatter_OneIntArabic() {
        Locale arabic = new Locale("ar");
        Integer value = Integer.valueOf(1024); // We're not trying to benchmark boxing here.
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Formatter f = new Formatter();
            f.format(arabic, "this is a reasonably short string that has an int %d in it", value);
        }
    }

    @Test
    public void timeStringBuilder_OneInt() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StringBuilder sb = new StringBuilder();
            sb.append("this is a reasonably short string that has an int ");
            sb.append(1024);
            sb.append(" in it");
        }
    }

    @Test
    public void timeFormatter_OneHexInt() {
        Integer value = Integer.valueOf(1024); // We're not trying to benchmark boxing here.
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Formatter f = new Formatter();
            f.format("this is a reasonably short string that has an int %x in it", value);
        }
    }

    @Test
    public void timeStringBuilder_OneHexInt() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StringBuilder sb = new StringBuilder();
            sb.append("this is a reasonably short string that has an int ");
            sb.append(Integer.toHexString(1024));
            sb.append(" in it");
        }
    }

    @Test
    public void timeFormatter_OneFloat() {
        Float value = Float.valueOf(10.24f); // We're not trying to benchmark boxing here.
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Formatter f = new Formatter();
            f.format("this is a reasonably short string that has a float %f in it", value);
        }
    }

    @Test
    public void timeFormatter_OneFloat_dot2f() {
        Float value = Float.valueOf(10.24f); // We're not trying to benchmark boxing here.
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Formatter f = new Formatter();
            f.format("this is a reasonably short string that has a float %.2f in it", value);
        }
    }

    @Test
    public void timeFormatter_TwoFloats() {
        Float value = Float.valueOf(10.24f); // We're not trying to benchmark boxing here.
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Formatter f = new Formatter();
            f.format("this is a short string that has two floats %f and %f in it", value, value);
        }
    }

    @Test
    public void timeStringBuilder_OneFloat() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StringBuilder sb = new StringBuilder();
            sb.append("this is a reasonably short string that has a float ");
            sb.append(10.24f);
            sb.append(" in it");
        }
    }

    @Test
    public void timeFormatter_OneString() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Formatter f = new Formatter();
            f.format("this is a reasonably short string that has a string %s in it", "hello");
        }
    }

    @Test
    public void timeStringBuilder_OneString() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StringBuilder sb = new StringBuilder();
            sb.append("this is a reasonably short string that has a string ");
            sb.append("hello");
            sb.append(" in it");
        }
    }
}
