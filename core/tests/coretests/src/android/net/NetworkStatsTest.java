/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.net;

import android.os.SystemClock;
import android.test.suitebuilder.annotation.SmallTest;

import junit.framework.TestCase;

@SmallTest
public class NetworkStatsTest extends TestCase {

    private static final String TEST_IFACE = "test0";

    public void testFindIndex() throws Exception {
        final NetworkStats stats = new NetworkStats.Builder(SystemClock.elapsedRealtime(), 3)
                .addEntry(TEST_IFACE, 100, 1024, 0)
                .addEntry(TEST_IFACE, 101, 0, 1024)
                .addEntry(TEST_IFACE, 102, 1024, 1024).build();

        assertEquals(2, stats.findIndex(TEST_IFACE, 102));
        assertEquals(2, stats.findIndex(TEST_IFACE, 102));
        assertEquals(0, stats.findIndex(TEST_IFACE, 100));
        assertEquals(-1, stats.findIndex(TEST_IFACE, 6));
    }

    public void testSubtractIdenticalData() throws Exception {
        final NetworkStats before = new NetworkStats.Builder(SystemClock.elapsedRealtime(), 2)
                .addEntry(TEST_IFACE, 100, 1024, 0)
                .addEntry(TEST_IFACE, 101, 0, 1024).build();

        final NetworkStats after = new NetworkStats.Builder(SystemClock.elapsedRealtime(), 2)
                .addEntry(TEST_IFACE, 100, 1024, 0)
                .addEntry(TEST_IFACE, 101, 0, 1024).build();

        final NetworkStats result = after.subtract(before);

        assertEquals(0, result.rx[0]);
        assertEquals(0, result.tx[0]);
        assertEquals(0, result.rx[1]);
        assertEquals(0, result.tx[1]);
    }

    public void testSubtractIdenticalRows() throws Exception {
        final NetworkStats before = new NetworkStats.Builder(SystemClock.elapsedRealtime(), 2)
                .addEntry(TEST_IFACE, 100, 1024, 0)
                .addEntry(TEST_IFACE, 101, 0, 1024).build();

        final NetworkStats after = new NetworkStats.Builder(SystemClock.elapsedRealtime(), 2)
                .addEntry(TEST_IFACE, 100, 1025, 2)
                .addEntry(TEST_IFACE, 101, 3, 1028).build();

        final NetworkStats result = after.subtract(before);

        // expect delta between measurements
        assertEquals(1, result.rx[0]);
        assertEquals(2, result.tx[0]);
        assertEquals(3, result.rx[1]);
        assertEquals(4, result.tx[1]);
    }

    public void testSubtractNewRows() throws Exception {
        final NetworkStats before = new NetworkStats.Builder(SystemClock.elapsedRealtime(), 2)
                .addEntry(TEST_IFACE, 100, 1024, 0)
                .addEntry(TEST_IFACE, 101, 0, 1024).build();

        final NetworkStats after = new NetworkStats.Builder(SystemClock.elapsedRealtime(), 3)
                .addEntry(TEST_IFACE, 100, 1024, 0)
                .addEntry(TEST_IFACE, 101, 0, 1024)
                .addEntry(TEST_IFACE, 102, 1024, 1024).build();

        final NetworkStats result = after.subtract(before);

        // its okay to have new rows
        assertEquals(0, result.rx[0]);
        assertEquals(0, result.tx[0]);
        assertEquals(0, result.rx[1]);
        assertEquals(0, result.tx[1]);
        assertEquals(1024, result.rx[2]);
        assertEquals(1024, result.tx[2]);
    }

}
