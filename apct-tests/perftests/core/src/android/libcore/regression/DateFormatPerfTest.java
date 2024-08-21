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

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.text.DateFormat;
import java.util.Locale;

@RunWith(AndroidJUnit4.class)
@LargeTest
public final class DateFormatPerfTest {
    @Rule public BenchmarkRule mBenchmarkRule = new BenchmarkRule();

    private Locale mLocale1;
    private Locale mLocale2;
    private Locale mLocale3;
    private Locale mLocale4;

    @Before
    public void setUp() throws Exception {
        mLocale1 = Locale.TAIWAN;
        mLocale2 = Locale.GERMANY;
        mLocale3 = Locale.FRANCE;
        mLocale4 = Locale.ITALY;
    }

    @Test
    public void timeGetDateTimeInstance() throws Exception {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            DateFormat.getDateTimeInstance();
        }
    }

    @Test
    public void timeGetDateTimeInstance_multiple() throws Exception {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, mLocale1);
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, mLocale2);
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, mLocale3);
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, mLocale4);
        }
    }
}
