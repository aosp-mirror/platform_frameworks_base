/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.timedetector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import android.util.ArrayMap;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.IndentingPrintWriter;
import com.android.server.timezonedetector.ArrayMapWithHistory;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.StringWriter;
import java.util.concurrent.Callable;

@RunWith(AndroidJUnit4.class)
public class ArrayMapWithHistoryTest {

    @Test
    public void testValueHistoryBehavior() {
        // Create a map that will retain 2 values per key.
        ArrayMapWithHistory<String, String> historyMap = new ArrayMapWithHistory<>(2 /* history */);
        ArrayMap<String, String> arrayMap = new ArrayMap<>();

        compareGetAndSizeForKeys(historyMap, arrayMap, entry("K1", null));

        assertEquals(0, historyMap.getHistoryCountForKeyForTests("K1"));
        assertToStringAndDumpNotNull(historyMap);

        putAndCompareReturnValue(historyMap, arrayMap, "K1", "V1");
        compareGetAndSizeForKeys(historyMap, arrayMap, entry("K1", "V1"));
        compareKeyAtAndValueAtForIndex(0, historyMap, arrayMap);

        assertEquals(1, historyMap.getHistoryCountForKeyForTests("K1"));
        assertToStringAndDumpNotNull(historyMap);

        // put() a new value for the same key.
        putAndCompareReturnValue(historyMap, arrayMap, "K1", "V2");
        compareGetAndSizeForKeys(historyMap, arrayMap, entry("K1", "V2"));
        compareKeyAtAndValueAtForIndex(0, historyMap, arrayMap);

        assertEquals(2, historyMap.getHistoryCountForKeyForTests("K1"));
        assertToStringAndDumpNotNull(historyMap);

        // put() a new value for the same key. We should have hit the limit of "2 values retained
        // per key".
        putAndCompareReturnValue(historyMap, arrayMap, "K1", "V3");
        compareGetAndSizeForKeys(historyMap, arrayMap, entry("K1", "V3"));
        compareKeyAtAndValueAtForIndex(0, historyMap, arrayMap);

        assertEquals(2, historyMap.getHistoryCountForKeyForTests("K1"));
        assertToStringAndDumpNotNull(historyMap);
    }

    @Test
    public void testMapBehavior() throws Exception {
        ArrayMapWithHistory<String, String> historyMap = new ArrayMapWithHistory<>(2);
        ArrayMap<String, String> arrayMap = new ArrayMap<>();

        compareGetAndSizeForKeys(historyMap, arrayMap, entry("K1", null), entry("K2", null));
        assertIndexAccessThrowsException(0, historyMap, arrayMap);

        assertEquals(0, historyMap.getHistoryCountForKeyForTests("K1"));
        assertEquals(0, historyMap.getHistoryCountForKeyForTests("K2"));

        putAndCompareReturnValue(historyMap, arrayMap, "K1", "V1");
        compareGetAndSizeForKeys(historyMap, arrayMap, entry("K1", "V1"), entry("K2", null));
        compareKeyAtAndValueAtForIndex(0, historyMap, arrayMap);
        // TODO Restore after http://b/146563025 is fixed and ArrayMap behaves properly in tests.
        // assertIndexAccessThrowsException(1, historyMap, arrayMap);

        assertEquals(1, historyMap.getHistoryCountForKeyForTests("K1"));
        assertToStringAndDumpNotNull(historyMap);

        putAndCompareReturnValue(historyMap, arrayMap, "K2", "V2");
        compareGetAndSizeForKeys(historyMap, arrayMap, entry("K1", "V1"), entry("K2", "V2"));
        compareKeyAtAndValueAtForIndex(0, historyMap, arrayMap);
        compareKeyAtAndValueAtForIndex(1, historyMap, arrayMap);
        // TODO Restore after http://b/146563025 is fixed and ArrayMap behaves properly in tests.
        // assertIndexAccessThrowsException(2, historyMap, arrayMap);

        assertEquals(1, historyMap.getHistoryCountForKeyForTests("K1"));
        assertEquals(1, historyMap.getHistoryCountForKeyForTests("K2"));
        assertToStringAndDumpNotNull(historyMap);
    }

    private static String dumpHistoryMap(ArrayMapWithHistory<?, ?> historyMap) {
        StringWriter stringWriter = new StringWriter();
        try (IndentingPrintWriter ipw = new IndentingPrintWriter(stringWriter, " ")) {
            historyMap.dump(ipw);
            return stringWriter.toString();
        }
    }

    private static <K, V> void putAndCompareReturnValue(ArrayMapWithHistory<K, V> historyMap,
            ArrayMap<K, V> arrayMap, K key, V value) {
        assertEquals(arrayMap.put(key, value), historyMap.put(key, value));
    }

    private static class Entry<K, V> {
        public final K key;
        public final V value;

        Entry(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    private static <K, V> Entry<K, V> entry(K key, V value) {
        return new Entry<>(key, value);
    }

    @SafeVarargs
    private static <K, V> void compareGetAndSizeForKeys(ArrayMapWithHistory<K, V> historyMap,
            ArrayMap<K, V> arrayMap, Entry<K, V>... expectedEntries) {
        for (Entry<K, V> expectedEntry : expectedEntries) {
            assertEquals(arrayMap.get(expectedEntry.key), historyMap.get(expectedEntry.key));
            assertEquals(expectedEntry.value, historyMap.get(expectedEntry.key));
        }
        assertEquals(arrayMap.size(), historyMap.size());
    }

    private static void compareKeyAtAndValueAtForIndex(
            int index, ArrayMapWithHistory<?, ?> historyMap, ArrayMap<?, ?> arrayMap) {
        assertEquals(arrayMap.keyAt(index), historyMap.keyAt(index));
        assertEquals(arrayMap.valueAt(index), historyMap.valueAt(index));
    }

    private static void assertIndexAccessThrowsException(
            int index, ArrayMapWithHistory<?, ?> historyMap, ArrayMap<?, ?> arrayMap)
            throws Exception {
        assertThrowsArrayIndexOutOfBoundsException(
                "ArrayMap.keyAt(" + index + ")", () -> arrayMap.keyAt(index));
        assertThrowsArrayIndexOutOfBoundsException(
                "ArrayMapWithHistory.keyAt(" + index + ")", () -> historyMap.keyAt(index));
        assertThrowsArrayIndexOutOfBoundsException(
                "ArrayMap.keyAt(" + index + ")", () -> arrayMap.valueAt(index));
        assertThrowsArrayIndexOutOfBoundsException(
                "ArrayMapWithHistory.keyAt(" + index + ")", () -> historyMap.valueAt(index));
    }

    private static void assertThrowsArrayIndexOutOfBoundsException(
            String description, Callable<?> callable) throws Exception {
        try {
            callable.call();
            fail("Expected exception for " + description);
        } catch (ArrayIndexOutOfBoundsException expected) {
            // This is fine.
        } catch (Exception e) {
            // Any other exception is just rethrown.
            throw e;
        }
    }

    private static void assertToStringAndDumpNotNull(ArrayMapWithHistory<?, ?> historyMap) {
        assertNotNull(historyMap.toString());
        assertNotNull(dumpHistoryMap(historyMap));
    }
}
