/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.tare;

import static android.text.format.DateUtils.HOUR_IN_MILLIS;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;

import static com.android.server.tare.TareUtils.getCurrentTimeMillis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Clock;
import java.time.ZoneOffset;

/** Test that the ledger records transactions correctly. */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class LedgerTest {

    @Before
    public void setUp() {
        TareUtils.sSystemClock = Clock.fixed(Clock.systemUTC().instant(), ZoneOffset.UTC);
    }

    @Test
    public void testInitialState() {
        final Ledger ledger = new Ledger();
        assertEquals(0, ledger.getCurrentBalance());
        assertEquals(0, ledger.get24HourSum(0, 0));
    }

    @Test
    public void testMultipleTransactions() {
        final Ledger ledger = new Ledger();
        ledger.recordTransaction(new Ledger.Transaction(0, 1000, 1, null, 5, 0));
        assertEquals(5, ledger.getCurrentBalance());
        assertEquals(5, ledger.get24HourSum(1, 60_000));
        ledger.recordTransaction(new Ledger.Transaction(2000, 2000, 1, null, 25, 0));
        assertEquals(30, ledger.getCurrentBalance());
        assertEquals(30, ledger.get24HourSum(1, 60_000));
        ledger.recordTransaction(new Ledger.Transaction(5000, 5500, 1, null, -10, 5));
        assertEquals(20, ledger.getCurrentBalance());
        assertEquals(20, ledger.get24HourSum(1, 60_000));
    }

    @Test
    public void test24HourSum() {
        final Ledger ledger = new Ledger();
        ledger.recordTransaction(new Ledger.Transaction(0, 1000, 1, null, 500, 0));
        assertEquals(500, ledger.get24HourSum(1, 24 * HOUR_IN_MILLIS));
        ledger.recordTransaction(
                new Ledger.Transaction(2 * HOUR_IN_MILLIS, 3 * HOUR_IN_MILLIS, 1, null, 2500, 0));
        assertEquals(3000, ledger.get24HourSum(1, 24 * HOUR_IN_MILLIS));
        ledger.recordTransaction(
                new Ledger.Transaction(4 * HOUR_IN_MILLIS, 4 * HOUR_IN_MILLIS, 1, null, 1, 0));
        assertEquals(3001, ledger.get24HourSum(1, 24 * HOUR_IN_MILLIS));
        assertEquals(2501, ledger.get24HourSum(1, 25 * HOUR_IN_MILLIS));
        assertEquals(2501, ledger.get24HourSum(1, 26 * HOUR_IN_MILLIS));
        // Pro-rated as the second transaction phases out
        assertEquals(1251,
                ledger.get24HourSum(1, 26 * HOUR_IN_MILLIS + 30 * MINUTE_IN_MILLIS));
        assertEquals(1, ledger.get24HourSum(1, 27 * HOUR_IN_MILLIS));
        assertEquals(0, ledger.get24HourSum(1, 28 * HOUR_IN_MILLIS));
    }

    @Test
    public void testRemoveOldTransactions() {
        final Ledger ledger = new Ledger();
        ledger.removeOldTransactions(24 * HOUR_IN_MILLIS);
        assertNull(ledger.getEarliestTransaction());

        final long now = getCurrentTimeMillis();
        Ledger.Transaction transaction1 = new Ledger.Transaction(
                now - 48 * HOUR_IN_MILLIS, now - 40 * HOUR_IN_MILLIS, 1, null, 4800, 0);
        Ledger.Transaction transaction2 = new Ledger.Transaction(
                now - 24 * HOUR_IN_MILLIS, now - 23 * HOUR_IN_MILLIS, 1, null, 600, 0);
        Ledger.Transaction transaction3 = new Ledger.Transaction(
                now - 22 * HOUR_IN_MILLIS, now - 21 * HOUR_IN_MILLIS, 1, null, 600, 0);
        // Instant event
        Ledger.Transaction transaction4 = new Ledger.Transaction(
                now - 20 * HOUR_IN_MILLIS, now - 20 * HOUR_IN_MILLIS, 1, null, 500, 0);
        // Recent event
        Ledger.Transaction transaction5 = new Ledger.Transaction(
                now - 5 * MINUTE_IN_MILLIS, now - MINUTE_IN_MILLIS, 1, null, 400, 0);
        ledger.recordTransaction(transaction1);
        ledger.recordTransaction(transaction2);
        ledger.recordTransaction(transaction3);
        ledger.recordTransaction(transaction4);
        ledger.recordTransaction(transaction5);

        assertEquals(transaction1, ledger.getEarliestTransaction());
        ledger.removeOldTransactions(24 * HOUR_IN_MILLIS);
        assertEquals(transaction2, ledger.getEarliestTransaction());
        ledger.removeOldTransactions(23 * HOUR_IN_MILLIS);
        assertEquals(transaction3, ledger.getEarliestTransaction());
        // Shouldn't delete transaction3 yet since there's still a piece of it within the min age
        // window.
        ledger.removeOldTransactions(21 * HOUR_IN_MILLIS + 30 * MINUTE_IN_MILLIS);
        assertEquals(transaction3, ledger.getEarliestTransaction());
        // Instant event should be removed as soon as we hit the exact threshold.
        ledger.removeOldTransactions(20 * HOUR_IN_MILLIS);
        assertEquals(transaction5, ledger.getEarliestTransaction());
        ledger.removeOldTransactions(0);
        assertNull(ledger.getEarliestTransaction());
    }
}
