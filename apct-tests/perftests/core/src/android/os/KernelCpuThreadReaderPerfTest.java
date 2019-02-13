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

import static org.junit.Assert.assertNotNull;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.KernelCpuThreadReader;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Performance tests collecting per-thread CPU data.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class KernelCpuThreadReaderPerfTest {
    @Rule
    public final PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private final KernelCpuThreadReader mKernelCpuThreadReader =
            KernelCpuThreadReader.create(8, uid -> 1000 <= uid && uid < 2000, 0);

    @Test
    public void timeReadCurrentProcessCpuUsage() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        assertNotNull(mKernelCpuThreadReader);
        while (state.keepRunning()) {
            this.mKernelCpuThreadReader.getCurrentProcessCpuUsage();
        }
    }
}
