/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.os;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Performance tests collecting CPU data different mechanisms.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class CpuUsageTrackingPerfTest {
    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void timeSystemThread() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        Binder b = new Binder();
        while (state.keepRunning()) {
            SystemClock.currentThreadTimeMicro();
        }
    }

    @Test
    public void timeReadStatFileDirectly() throws Exception {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        // CPU usage by frequency for this pid. Data is in text format.
        final String procFile = "/proc/self/stat";
        while (state.keepRunning()) {
            byte[] data = Files.readAllBytes(Paths.get(procFile));
        }
    }

    @Test
    public void timeReadPidProcDirectly() throws Exception {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        // CPU usage by frequency for this pid. Data is in text format.
        final String procFile = "/proc/self/time_in_state";
        while (state.keepRunning()) {
            byte[] data = Files.readAllBytes(Paths.get(procFile));
        }
    }

    @Test
    public void timeReadThreadProcDirectly() throws Exception {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        // CPU usage by frequency for this UID. Data is in text format.
        final String procFile = "/proc/self/task/" + android.os.Process.myTid()
                + "/time_in_state";
        while (state.keepRunning()) {
            byte[] data = Files.readAllBytes(Paths.get(procFile));
        }
    }
}
