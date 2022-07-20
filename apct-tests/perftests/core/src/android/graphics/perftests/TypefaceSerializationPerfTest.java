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
import android.os.Debug;
import android.os.SharedMemory;
import android.os.SystemClock;
import android.perftests.utils.ManualBenchmarkState;
import android.perftests.utils.PerfManualStatusReporter;
import android.util.ArrayMap;
import android.util.Log;

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

    private static final String TAG = "TypefaceSerializationPerfTest";

    @Rule
    public PerfManualStatusReporter mPerfManualStatusReporter = new PerfManualStatusReporter();

    @Test
    public void testSerializeFontMap() throws Exception {
        Map<String, Typeface> systemFontMap = Typeface.getSystemFontMap();
        ManualBenchmarkState state = mPerfManualStatusReporter.getBenchmarkState();

        long elapsedTime = 0;
        while (state.keepRunning(elapsedTime)) {
            long startTime = System.nanoTime();
            Typeface.serializeFontMap(systemFontMap);
            elapsedTime = System.nanoTime() - startTime;
        }
    }

    @Test
    public void testSerializeFontMap_memory() throws Exception {
        Map<String, Typeface> systemFontMap = Typeface.getSystemFontMap();
        SharedMemory memory = Typeface.serializeFontMap(systemFontMap);
        ManualBenchmarkState state = mPerfManualStatusReporter.getBenchmarkState();

        while (state.keepRunning(memory.getSize())) {
            // Rate-limiting
            SystemClock.sleep(100);
        }
    }

    @Test
    public void testDeserializeFontMap() throws Exception {
        SharedMemory memory = Typeface.serializeFontMap(Typeface.getSystemFontMap());
        ByteBuffer buffer = memory.mapReadOnly().order(ByteOrder.BIG_ENDIAN);
        ManualBenchmarkState state = mPerfManualStatusReporter.getBenchmarkState();

        ArrayMap<String, Typeface> out = new ArrayMap<>();
        long elapsedTime = 0;
        while (state.keepRunning(elapsedTime)) {
            long startTime = System.nanoTime();
            buffer.position(0);
            Typeface.deserializeFontMap(buffer, out);
            elapsedTime = System.nanoTime() - startTime;
        }
    }

    @Test
    public void testDeserializeFontMap_memory() throws Exception {
        SharedMemory memory = Typeface.serializeFontMap(Typeface.getSystemFontMap());
        ByteBuffer buffer = memory.mapReadOnly().order(ByteOrder.BIG_ENDIAN);
        ManualBenchmarkState state = mPerfManualStatusReporter.getBenchmarkState();

        ArrayMap<String, Typeface> out = new ArrayMap<>();
        // Diff of native heap allocation size (in bytes) before and after deserializeFontMap.
        // Note: we don't measure memory usage of setSystemFontMap because setSystemFontMap sets
        // some global variables, and it's hard to clear them.
        long heapDiff = 0;
        // Sometimes heapDiff may become negative due to GC.
        // Use 0 in that case to avoid crashing in keepRunning.
        while (state.keepRunning(Math.max(0, heapDiff))) {
            buffer.position(0);
            long baselineSize = Debug.getNativeHeapAllocatedSize();
            Typeface.deserializeFontMap(buffer, out);
            long currentSize = Debug.getNativeHeapAllocatedSize();
            heapDiff = currentSize - baselineSize;
            Log.i(TAG, String.format("native heap alloc: current = %d, baseline = %d, diff = %d",
                    currentSize, baselineSize, heapDiff));
            // Release native objects here to minimize the impact of GC.
            for (Typeface typeface : out.values()) {
                typeface.releaseNativeObjectForTest();
            }
            out.clear();
        }
    }

    @Test
    public void testSetSystemFontMap() throws Exception {
        SharedMemory memory = null;
        ManualBenchmarkState state = mPerfManualStatusReporter.getBenchmarkState();

        long elapsedTime = 0;
        while (state.keepRunning(elapsedTime)) {
            // Explicitly destroy lazy-loaded typefaces, so that we don't hit the mmap limit
            // (max_map_count).
            Typeface.destroySystemFontMap();
            Typeface.loadPreinstalledSystemFontMap();
            if (memory != null) {
                memory.close();
            }
            memory = Typeface.serializeFontMap(Typeface.getSystemFontMap());
            long startTime = System.nanoTime();
            Typeface.setSystemFontMap(memory);
            elapsedTime = System.nanoTime() - startTime;
        }
    }
}
