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

import java.util.zip.Adler32;
import java.util.zip.CRC32;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class ChecksumPerfTest {
    @Rule public BenchmarkRule mBenchmarkRule = new BenchmarkRule();

    @Test
    public void timeAdler_block() throws Exception {
        byte[] bytes = new byte[10000];
        Adler32 adler = new Adler32();
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            adler.update(bytes);
        }
    }

    @Test
    public void timeAdler_byte() throws Exception {
        Adler32 adler = new Adler32();
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            adler.update(1);
        }
    }

    @Test
    public void timeCrc_block() throws Exception {
        byte[] bytes = new byte[10000];
        CRC32 crc = new CRC32();
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            crc.update(bytes);
        }
    }

    @Test
    public void timeCrc_byte() throws Exception {
        CRC32 crc = new CRC32();
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            crc.update(1);
        }
    }
}
