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

import static org.junit.Assert.assertEquals;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Test that the ledger records transactions correctly. */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class LedgerTest {

    @Test
    public void testInitialState() {
        final Ledger ledger = new Ledger();
        assertEquals(0, ledger.getCurrentBalance());
        assertEquals(0, ledger.get24HourSum(0, 0));
    }

    @Test
    public void testMultipleTransactions() {
        final Ledger ledger = new Ledger();
        ledger.recordTransaction(new Ledger.Transaction(0, 1000, 1, null, 5));
        assertEquals(5, ledger.getCurrentBalance());
        assertEquals(5, ledger.get24HourSum(1, 60_000));
        ledger.recordTransaction(new Ledger.Transaction(2000, 2000, 1, null, 25));
        assertEquals(30, ledger.getCurrentBalance());
        assertEquals(30, ledger.get24HourSum(1, 60_000));
        ledger.recordTransaction(new Ledger.Transaction(5000, 5500, 1, null, -10));
        assertEquals(20, ledger.getCurrentBalance());
        assertEquals(20, ledger.get24HourSum(1, 60_000));
    }

    @Test
    public void test24HourSum() {
        final Ledger ledger = new Ledger();
        ledger.recordTransaction(new Ledger.Transaction(0, 1000, 1, null, 500));
        assertEquals(500, ledger.get24HourSum(1, 24 * HOUR_IN_MILLIS));
        ledger.recordTransaction(
                new Ledger.Transaction(2 * HOUR_IN_MILLIS, 3 * HOUR_IN_MILLIS, 1, null, 2500));
        assertEquals(3000, ledger.get24HourSum(1, 24 * HOUR_IN_MILLIS));
        ledger.recordTransaction(
                new Ledger.Transaction(4 * HOUR_IN_MILLIS, 4 * HOUR_IN_MILLIS, 1, null, 1));
        assertEquals(3001, ledger.get24HourSum(1, 24 * HOUR_IN_MILLIS));
        assertEquals(2501, ledger.get24HourSum(1, 25 * HOUR_IN_MILLIS));
        assertEquals(2501, ledger.get24HourSum(1, 26 * HOUR_IN_MILLIS));
        // Pro-rated as the second transaction phases out
        assertEquals(1251,
                ledger.get24HourSum(1, 26 * HOUR_IN_MILLIS + 30 * MINUTE_IN_MILLIS));
        assertEquals(1, ledger.get24HourSum(1, 27 * HOUR_IN_MILLIS));
        assertEquals(0, ledger.get24HourSum(1, 28 * HOUR_IN_MILLIS));
    }
}
