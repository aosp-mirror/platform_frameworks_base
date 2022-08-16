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

import java.util.TimeZone;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class TimeZonePerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void timeTimeZone_getDefault() throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            TimeZone.getDefault();
        }
    }

    @Test
    public void timeTimeZone_getTimeZoneUTC() throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            TimeZone.getTimeZone("UTC");
        }
    }

    @Test
    public void timeTimeZone_getTimeZone_default() throws Exception {
        String defaultId = TimeZone.getDefault().getID();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            TimeZone.getTimeZone(defaultId);
        }
    }

    // A time zone with relatively few transitions.
    @Test
    public void timeTimeZone_getTimeZone_America_Caracas() throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            TimeZone.getTimeZone("America/Caracas");
        }
    }

    // A time zone with a lot of transitions.
    @Test
    public void timeTimeZone_getTimeZone_America_Santiago() throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            TimeZone.getTimeZone("America/Santiago");
        }
    }

    @Test
    public void timeTimeZone_getTimeZone_GMT_plus_10() throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            TimeZone.getTimeZone("GMT+10");
        }
    }
}
