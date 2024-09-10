/*
 * Copyright (C) 2016 The Android Open Source Project
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

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Benchmark for java.text.SimpleDateFormat. This tests common formatting, parsing and creation
 * operations with a specific focus on TimeZone handling.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class SimpleDateFormatPerfTest {
    @Rule public BenchmarkRule mBenchmarkRule = new BenchmarkRule();

    @Test
    public void time_createFormatWithTimeZone() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy.MM.dd z");
        }
    }

    @Test
    public void time_parseWithTimeZoneShort() throws ParseException {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy.MM.dd z");
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            sdf.parse("2000.01.01 PST");
        }
    }

    @Test
    public void time_parseWithTimeZoneLong() throws ParseException {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy.MM.dd zzzz");
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            sdf.parse("2000.01.01 Pacific Standard Time");
        }
    }

    @Test
    public void time_parseWithoutTimeZone() throws ParseException {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy.MM.dd");
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            sdf.parse("2000.01.01");
        }
    }

    @Test
    public void time_createAndParseWithTimeZoneShort() throws ParseException {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy.MM.dd z");
            sdf.parse("2000.01.01 PST");
        }
    }

    @Test
    public void time_createAndParseWithTimeZoneLong() throws ParseException {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy.MM.dd zzzz");
            sdf.parse("2000.01.01 Pacific Standard Time");
        }
    }

    @Test
    public void time_formatWithTimeZoneShort() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy.MM.dd z");
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            sdf.format(new Date());
        }
    }

    @Test
    public void time_formatWithTimeZoneLong() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy.MM.dd zzzz");
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            sdf.format(new Date());
        }
    }
}
