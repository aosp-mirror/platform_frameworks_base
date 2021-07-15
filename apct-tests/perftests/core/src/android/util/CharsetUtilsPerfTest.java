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

package android.util;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;

import dalvik.system.VMRuntime;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;

@LargeTest
@RunWith(Parameterized.class)
public class CharsetUtilsPerfTest {
    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Parameterized.Parameter(0)
    public String mName;
    @Parameterized.Parameter(1)
    public String mValue;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> getParameters() {
        return Arrays.asList(new Object[][] {
                { "simple", "com.example.typical_package_name" },
                { "complex", "從不喜歡孤單一個 - 蘇永康／吳雨霏" },
        });
    }

    @Test
    public void timeUpstream() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mValue.getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * Measure performance of writing into a small buffer where bounds checking
     * requires careful measurement of encoded size.
     */
    @Test
    public void timeLocal_SmallBuffer() {
        final byte[] dest = (byte[]) VMRuntime.getRuntime().newNonMovableArray(byte.class, 64);
        final long destPtr = VMRuntime.getRuntime().addressOf(dest);

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            CharsetUtils.toModifiedUtf8Bytes(mValue, destPtr, 0, dest.length);
        }
    }

    /**
     * Measure performance of writing into a large buffer where bounds checking
     * only needs a simple worst-case 4-bytes-per-char check.
     */
    @Test
    public void timeLocal_LargeBuffer() {
        final byte[] dest = (byte[]) VMRuntime.getRuntime().newNonMovableArray(byte.class, 1024);
        final long destPtr = VMRuntime.getRuntime().addressOf(dest);

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            CharsetUtils.toModifiedUtf8Bytes(mValue, destPtr, 0, dest.length);
       }
    }
}
