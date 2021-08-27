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

package android.text;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.function.Supplier;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class TextUtilsPerfTest {
    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    public static final String TEMPLATE = "Template that combines %s and %d together";

    public String mVar1 = "example";
    public int mVar2 = 42;

    /**
     * Measure overhead of formatting a string via {@link String#format}.
     */
    @Test
    public void timeFormatUpstream() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            String res = String.format(TEMPLATE, mVar1, mVar2);
        }
    }

    /**
     * Measure overhead of formatting a string via
     * {@link TextUtils#formatSimple}.
     */
    @Test
    public void timeFormatLocal() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            String res = TextUtils.formatSimple(TEMPLATE, mVar1, mVar2);
        }
    }

    /**
     * Measure overhead of formatting a string inline.
     */
    @Test
    public void timeFormatInline() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            String res = "Template that combines " + mVar1 + " and " + mVar2 + " together";
        }
    }

    /**
     * Measure overhead of a passing null-check that uses a lambda to
     * communicate a custom error message.
     */
    @Test
    public void timeFormat_Skip_Lambda() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            requireNonNull(this, () -> {
                return String.format(TEMPLATE, mVar1, mVar2);
            });
        }
    }

    /**
     * Measure overhead of a passing null-check that uses varargs to communicate
     * a custom error message.
     */
    @Test
    public void timeFormat_Skip_Varargs() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            requireNonNull(this, TEMPLATE, mVar1, mVar2);
        }
    }

    private static <T> T requireNonNull(T obj, Supplier<String> messageSupplier) {
        return obj;
    }

    private static <T> T requireNonNull(T obj, String format, Object... args) {
        return obj;
    }
}
