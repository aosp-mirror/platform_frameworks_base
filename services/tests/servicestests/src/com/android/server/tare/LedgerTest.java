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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.util.SparseLongArray;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** Test that the ledger records transactions correctly. */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class LedgerTest {

    @Before
    public void setUp() {
        TareUtils.sSystemClock = Clock.fixed(Clock.systemUTC().instant(), ZoneOffset.UTC);
    }

    private void shiftSystemTime(long incrementMs) {
        TareUtils.sSystemClock =
                Clock.offset(TareUtils.sSystemClock, Duration.ofMillis(incrementMs));
    }

    @Test
    public void testInitialState() {
        final Ledger ledger = new Ledger();
        assertEquals(0, ledger.getCurrentBalance());
        assertEquals(0, ledger.get24HourSum(0, 0));
    }

    @Test
    public void testInitialization_FullLists() {
        final long balance = 1234567890L;
        List<Ledger.Transaction> transactions = new ArrayList<>();
        List<Ledger.RewardBucket> rewardBuckets = new ArrayList<>();

        final long now = getCurrentTimeMillis();
        Ledger.Transaction secondTxn = null;
        Ledger.RewardBucket remainingBucket = null;
        for (int i = 0; i < Ledger.MAX_TRANSACTION_COUNT; ++i) {
            final long start = now - 10 * HOUR_IN_MILLIS + i * MINUTE_IN_MILLIS;
            Ledger.Transaction transaction = new Ledger.Transaction(
                    start, start + MINUTE_IN_MILLIS, 1, null, 400, 0);
            if (i == 1) {
                secondTxn = transaction;
            }
            transactions.add(transaction);
        }
        for (int b = 0; b < Ledger.NUM_REWARD_BUCKET_WINDOWS; ++b) {
            final long start = now - (Ledger.NUM_REWARD_BUCKET_WINDOWS - b) * 24 * HOUR_IN_MILLIS;
            Ledger.RewardBucket rewardBucket = new Ledger.RewardBucket();
            rewardBucket.startTimeMs = start;
            for (int r = 0; r < 5; ++r) {
                rewardBucket.cumulativeDelta.put(EconomicPolicy.TYPE_REWARD | r, b * start + r);
            }
            if (b == Ledger.NUM_REWARD_BUCKET_WINDOWS - 1) {
                remainingBucket = rewardBucket;
            }
            rewardBuckets.add(rewardBucket);
        }
        final Ledger ledger = new Ledger(balance, transactions, rewardBuckets);
        assertEquals(balance, ledger.getCurrentBalance());
        assertEquals(transactions, ledger.getTransactions());
        // Everything but the last bucket is old, so the returned list should only contain that
        // bucket.
        rewardBuckets.clear();
        rewardBuckets.add(remainingBucket);
        assertEquals(rewardBuckets, ledger.getRewardBuckets());

        // Make sure the ledger can properly record new transactions.
        final long start = now - MINUTE_IN_MILLIS;
        final long delta = 400;
        final Ledger.Transaction transaction = new Ledger.Transaction(
                start, start + MINUTE_IN_MILLIS, EconomicPolicy.TYPE_REWARD | 1, null, delta, 0);
        ledger.recordTransaction(transaction);
        assertEquals(balance + delta, ledger.getCurrentBalance());
        transactions = ledger.getTransactions();
        assertEquals(secondTxn, transactions.get(0));
        assertEquals(transaction, transactions.get(Ledger.MAX_TRANSACTION_COUNT - 1));
        final Ledger.RewardBucket rewardBucket = new Ledger.RewardBucket();
        rewardBucket.startTimeMs = now;
        rewardBucket.cumulativeDelta.put(EconomicPolicy.TYPE_REWARD | 1, delta);
        rewardBuckets = ledger.getRewardBuckets();
        assertRewardBucketsEqual(remainingBucket, rewardBuckets.get(0));
        assertRewardBucketsEqual(rewardBucket, rewardBuckets.get(1));
    }

    @Test
    public void testInitialization_OverflowingLists() {
        final long balance = 1234567890L;
        final List<Ledger.Transaction> transactions = new ArrayList<>();
        final List<Ledger.RewardBucket> rewardBuckets = new ArrayList<>();

        final long now = getCurrentTimeMillis();
        for (int i = 0; i < 2 * Ledger.MAX_TRANSACTION_COUNT; ++i) {
            final long start = now - 20 * HOUR_IN_MILLIS + i * MINUTE_IN_MILLIS;
            Ledger.Transaction transaction = new Ledger.Transaction(
                    start, start + MINUTE_IN_MILLIS, 1, null, 400, 0);
            transactions.add(transaction);
        }
        for (int b = 0; b < 2 * Ledger.NUM_REWARD_BUCKET_WINDOWS; ++b) {
            final long start = now
                    - (2 * Ledger.NUM_REWARD_BUCKET_WINDOWS - b) * 6 * HOUR_IN_MILLIS;
            Ledger.RewardBucket rewardBucket = new Ledger.RewardBucket();
            rewardBucket.startTimeMs = start;
            for (int r = 0; r < 5; ++r) {
                rewardBucket.cumulativeDelta.put(EconomicPolicy.TYPE_REWARD | r, b * start + r);
            }
            rewardBuckets.add(rewardBucket);
        }
        final Ledger ledger = new Ledger(balance, transactions, rewardBuckets);
        assertEquals(balance, ledger.getCurrentBalance());
        assertEquals(transactions.subList(Ledger.MAX_TRANSACTION_COUNT,
                        2 * Ledger.MAX_TRANSACTION_COUNT),
                ledger.getTransactions());
        assertEquals(rewardBuckets.subList(Ledger.NUM_REWARD_BUCKET_WINDOWS,
                        2 * Ledger.NUM_REWARD_BUCKET_WINDOWS),
                ledger.getRewardBuckets());
    }

    @Test
    public void testMultipleTransactions() {
        final Ledger ledger = new Ledger();
        ledger.recordTransaction(new Ledger.Transaction(0, 1000, 1, null, 5, 0));
        assertEquals(5, ledger.getCurrentBalance());
        ledger.recordTransaction(new Ledger.Transaction(2000, 2000, 1, null, 25, 0));
        assertEquals(30, ledger.getCurrentBalance());
        ledger.recordTransaction(new Ledger.Transaction(5000, 5500, 1, null, -10, 5));
        assertEquals(20, ledger.getCurrentBalance());
    }

    @Test
    public void test24HourSum() {
        final long now = getCurrentTimeMillis();
        final long end = now + 24 * HOUR_IN_MILLIS;
        final int reward1 = EconomicPolicy.TYPE_REWARD | 1;
        final int reward2 = EconomicPolicy.TYPE_REWARD | 2;
        final Ledger ledger = new Ledger();

        // First bucket
        assertEquals(0, ledger.get24HourSum(reward1, end));
        ledger.recordTransaction(new Ledger.Transaction(now, now + 1000, reward1, null, 500, 0));
        assertEquals(500, ledger.get24HourSum(reward1, end));
        assertEquals(0, ledger.get24HourSum(reward2, end));
        ledger.recordTransaction(
                new Ledger.Transaction(now + 2 * HOUR_IN_MILLIS, now + 3 * HOUR_IN_MILLIS,
                        reward1, null, 2500, 0));
        assertEquals(3000, ledger.get24HourSum(reward1, end));
        // Second bucket
        shiftSystemTime(7 * HOUR_IN_MILLIS); // now + 7
        ledger.recordTransaction(
                new Ledger.Transaction(now + 7 * HOUR_IN_MILLIS, now + 7 * HOUR_IN_MILLIS,
                        reward1, null, 1, 0));
        ledger.recordTransaction(
                new Ledger.Transaction(now + 7 * HOUR_IN_MILLIS, now + 7 * HOUR_IN_MILLIS,
                        reward2, null, 42, 0));
        assertEquals(3001, ledger.get24HourSum(reward1, end));
        assertEquals(42, ledger.get24HourSum(reward2, end));
        // Third bucket
        shiftSystemTime(12 * HOUR_IN_MILLIS); // now + 19
        ledger.recordTransaction(
                new Ledger.Transaction(now + 12 * HOUR_IN_MILLIS, now + 13 * HOUR_IN_MILLIS,
                        reward1, null, 300, 0));
        assertEquals(3301, ledger.get24HourSum(reward1, end));
        assertRewardBucketsInOrder(ledger.getRewardBuckets());
        // Older buckets should be excluded
        assertEquals(301, ledger.get24HourSum(reward1, end + HOUR_IN_MILLIS));
        assertEquals(301, ledger.get24HourSum(reward1, end + 2 * HOUR_IN_MILLIS));
        // 2nd bucket should still be included since it started at the 7 hour mark
        assertEquals(301, ledger.get24HourSum(reward1, end + 6 * HOUR_IN_MILLIS));
        assertEquals(42, ledger.get24HourSum(reward2, end + 6 * HOUR_IN_MILLIS));
        assertEquals(300, ledger.get24HourSum(reward1, end + 7 * HOUR_IN_MILLIS + 1));
        assertEquals(0, ledger.get24HourSum(reward2, end + 8 * HOUR_IN_MILLIS));
        assertEquals(0, ledger.get24HourSum(reward1, end + 19 * HOUR_IN_MILLIS + 1));
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

    @Test
    public void testTransactionsAlwaysInOrder() {
        final Ledger ledger = new Ledger();
        List<Ledger.Transaction> transactions = ledger.getTransactions();
        assertTrue(transactions.isEmpty());

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

        Ledger.Transaction transaction5 = new Ledger.Transaction(
                now - 15 * HOUR_IN_MILLIS, now - 15 * HOUR_IN_MILLIS + MINUTE_IN_MILLIS,
                1, null, 400, 0);
        ledger.recordTransaction(transaction1);
        ledger.recordTransaction(transaction2);
        ledger.recordTransaction(transaction3);
        ledger.recordTransaction(transaction4);
        ledger.recordTransaction(transaction5);

        transactions = ledger.getTransactions();
        assertEquals(5, transactions.size());
        assertTransactionsInOrder(transactions);

        for (int i = 0; i < Ledger.MAX_TRANSACTION_COUNT - 5; ++i) {
            final long start = now - 10 * HOUR_IN_MILLIS + i * MINUTE_IN_MILLIS;
            Ledger.Transaction transaction = new Ledger.Transaction(
                    start, start + MINUTE_IN_MILLIS, 1, null, 400, 0);
            ledger.recordTransaction(transaction);
        }
        transactions = ledger.getTransactions();
        assertEquals(Ledger.MAX_TRANSACTION_COUNT, transactions.size());
        assertTransactionsInOrder(transactions);

        long start = now - 5 * HOUR_IN_MILLIS;
        Ledger.Transaction transactionLast5 = new Ledger.Transaction(
                start, start + MINUTE_IN_MILLIS, 1, null, 4800, 0);
        start = now - 4 * HOUR_IN_MILLIS;
        Ledger.Transaction transactionLast4 = new Ledger.Transaction(
                start, start + MINUTE_IN_MILLIS, 1, null, 600, 0);
        start = now - 3 * HOUR_IN_MILLIS;
        Ledger.Transaction transactionLast3 = new Ledger.Transaction(
                start, start + MINUTE_IN_MILLIS, 1, null, 600, 0);
        // Instant event
        start = now - 2 * HOUR_IN_MILLIS;
        Ledger.Transaction transactionLast2 = new Ledger.Transaction(
                start, start, 1, null, 500, 0);
        Ledger.Transaction transactionLast1 = new Ledger.Transaction(
                start, start + MINUTE_IN_MILLIS, 1, null, 400, 0);
        ledger.recordTransaction(transactionLast5);
        ledger.recordTransaction(transactionLast4);
        ledger.recordTransaction(transactionLast3);
        ledger.recordTransaction(transactionLast2);
        ledger.recordTransaction(transactionLast1);

        transactions = ledger.getTransactions();
        assertEquals(Ledger.MAX_TRANSACTION_COUNT, transactions.size());
        assertTransactionsInOrder(transactions);
        assertEquals(transactionLast1, transactions.get(Ledger.MAX_TRANSACTION_COUNT - 1));
        assertEquals(transactionLast2, transactions.get(Ledger.MAX_TRANSACTION_COUNT - 2));
        assertEquals(transactionLast3, transactions.get(Ledger.MAX_TRANSACTION_COUNT - 3));
        assertEquals(transactionLast4, transactions.get(Ledger.MAX_TRANSACTION_COUNT - 4));
        assertEquals(transactionLast5, transactions.get(Ledger.MAX_TRANSACTION_COUNT - 5));
        assertFalse(transactions.contains(transaction1));
        assertFalse(transactions.contains(transaction2));
        assertFalse(transactions.contains(transaction3));
        assertFalse(transactions.contains(transaction4));
        assertFalse(transactions.contains(transaction5));
    }

    private void assertSparseLongArraysEqual(SparseLongArray expected, SparseLongArray actual) {
        if (expected == null) {
            assertNull(actual);
            return;
        }
        assertNotNull(actual);
        final int size = expected.size();
        assertEquals(size, actual.size());
        for (int i = 0; i < size; ++i) {
            assertEquals(expected.keyAt(i), actual.keyAt(i));
            assertEquals(expected.valueAt(i), actual.valueAt(i));
        }
    }

    private void assertRewardBucketsEqual(Ledger.RewardBucket expected,
            Ledger.RewardBucket actual) {
        if (expected == null) {
            assertNull(actual);
            return;
        }
        assertNotNull(actual);
        assertEquals(expected.startTimeMs, actual.startTimeMs);
        assertSparseLongArraysEqual(expected.cumulativeDelta, actual.cumulativeDelta);
    }

    private void assertRewardBucketsInOrder(List<Ledger.RewardBucket> rewardBuckets) {
        assertNotNull(rewardBuckets);
        for (int i = 1; i < rewardBuckets.size(); ++i) {
            final Ledger.RewardBucket prev = rewardBuckets.get(i - 1);
            final Ledger.RewardBucket cur = rewardBuckets.get(i);
            assertTrue("Newer bucket stored before older bucket @ index " + i
                            + ": " + prev.startTimeMs + " vs " + cur.startTimeMs,
                    prev.startTimeMs <= cur.startTimeMs);
        }
    }

    private void assertTransactionsInOrder(List<Ledger.Transaction> transactions) {
        assertNotNull(transactions);
        for (int i = 1; i < transactions.size(); ++i) {
            final Ledger.Transaction prev = transactions.get(i - 1);
            final Ledger.Transaction cur = transactions.get(i);
            assertTrue("Newer transaction stored before older transaction @ index " + i
                            + ": " + prev.endTimeMs + " vs " + cur.endTimeMs,
                    prev.endTimeMs <= cur.endTimeMs);
        }
    }
}
