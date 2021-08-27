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

package android.graphics.perftests;

import android.graphics.Typeface;
import android.os.SharedMemory;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.util.ArrayMap;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class TypefaceSerializationPerfTest {

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void testSerializeFontMap() throws Exception {
        Map<String, Typeface> systemFontMap = Typeface.getSystemFontMap();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();

        while (state.keepRunning()) {
            Typeface.serializeFontMap(systemFontMap);
        }
    }

    @Test
    public void testDeserializeFontMap() throws Exception {
        SharedMemory memory = Typeface.serializeFontMap(Typeface.getSystemFontMap());
        ByteBuffer buffer = memory.mapReadOnly().order(ByteOrder.BIG_ENDIAN);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();

        ArrayMap<String, Typeface> out = new ArrayMap<>();
        while (state.keepRunning()) {
            buffer.position(0);
            Typeface.deserializeFontMap(buffer, out);
        }
    }

    @Test
    public void testSetSystemFontMap() throws Exception {
        SharedMemory memory = null;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();

        while (state.keepRunning()) {
            state.pauseTiming();
            // Explicitly destroy lazy-loaded typefaces, so that we don't hit the mmap limit
            // (max_map_count).
            Typeface.destroySystemFontMap();
            Typeface.loadPreinstalledSystemFontMap();
            if (memory != null) {
                memory.close();
            }
            memory = Typeface.serializeFontMap(Typeface.getSystemFontMap());
            state.resumeTiming();
            Typeface.setSystemFontMap(memory);
        }
    }
}
