/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.util.SparseLongArray;

import java.util.List;

public class TareTestUtils {
    static void assertLedgersEqual(Ledger expected, Ledger actual) {
        if (expected == null) {
            assertNull(actual);
            return;
        }
        assertNotNull(actual);
        assertEquals(expected.getCurrentBalance(), actual.getCurrentBalance());

        List<Ledger.Transaction> expectedTransactions = expected.getTransactions();
        List<Ledger.Transaction> actualTransactions = actual.getTransactions();
        assertEquals(expectedTransactions.size(), actualTransactions.size());
        for (int i = 0; i < expectedTransactions.size(); ++i) {
            assertTransactionsEqual(expectedTransactions.get(i), actualTransactions.get(i));
        }

        List<Ledger.RewardBucket> expectedRewardBuckets = expected.getRewardBuckets();
        List<Ledger.RewardBucket> actualRewardBuckets = actual.getRewardBuckets();
        assertEquals(expectedRewardBuckets.size(), actualRewardBuckets.size());
        for (int i = 0; i < expectedRewardBuckets.size(); ++i) {
            assertRewardBucketsEqual(expectedRewardBuckets.get(i), actualRewardBuckets.get(i));
        }
    }


    static void assertSparseLongArraysEqual(SparseLongArray expected, SparseLongArray actual) {
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

    static void assertRewardBucketsEqual(Ledger.RewardBucket expected, Ledger.RewardBucket actual) {
        if (expected == null) {
            assertNull(actual);
            return;
        }
        assertNotNull(actual);
        assertEquals(expected.startTimeMs, actual.startTimeMs);
        assertSparseLongArraysEqual(expected.cumulativeDelta, actual.cumulativeDelta);
    }

    static void assertTransactionsEqual(Ledger.Transaction expected, Ledger.Transaction actual) {
        if (expected == null) {
            assertNull(actual);
            return;
        }
        assertNotNull(actual);
        assertEquals(expected.startTimeMs, actual.startTimeMs);
        assertEquals(expected.endTimeMs, actual.endTimeMs);
        assertEquals(expected.eventId, actual.eventId);
        assertEquals(expected.tag, actual.tag);
        assertEquals(expected.delta, actual.delta);
        assertEquals(expected.ctp, actual.ctp);
    }
}
