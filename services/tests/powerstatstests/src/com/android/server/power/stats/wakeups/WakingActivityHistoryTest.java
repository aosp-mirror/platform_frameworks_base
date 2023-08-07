/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.power.stats.wakeups;

import static com.google.common.truth.Truth.assertThat;

import android.util.LongSparseArray;
import android.util.SparseIntArray;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.server.power.stats.wakeups.CpuWakeupStats.WakingActivityHistory;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.ThreadLocalRandom;

@RunWith(AndroidJUnit4.class)
public class WakingActivityHistoryTest {
    private volatile long mTestRetention = 54;

    private static boolean areSame(SparseIntArray a, SparseIntArray b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        final int lenA = a.size();
        if (b.size() != lenA) {
            return false;
        }
        for (int i = 0; i < lenA; i++) {
            if (a.keyAt(i) != b.keyAt(i)) {
                return false;
            }
            if (a.valueAt(i) != b.valueAt(i)) {
                return false;
            }
        }
        return true;
    }

    @Test
    public void recordActivityAppendsUids() {
        final WakingActivityHistory history = new WakingActivityHistory(() -> Long.MAX_VALUE);
        final int subsystem = 42;
        final long timestamp = 54;

        final SparseIntArray uids = new SparseIntArray();
        uids.put(1, 3);
        uids.put(5, 2);

        history.recordActivity(subsystem, timestamp, uids);

        assertThat(history.mWakingActivity.size()).isEqualTo(1);
        assertThat(history.mWakingActivity.contains(subsystem)).isTrue();
        assertThat(history.mWakingActivity.contains(subsystem + 1)).isFalse();
        assertThat(history.mWakingActivity.contains(subsystem - 1)).isFalse();

        final LongSparseArray<SparseIntArray> recordedHistory = history.mWakingActivity.get(
                subsystem);

        assertThat(recordedHistory.size()).isEqualTo(1);
        assertThat(recordedHistory.indexOfKey(timestamp - 1)).isLessThan(0);
        assertThat(recordedHistory.indexOfKey(timestamp)).isAtLeast(0);
        assertThat(recordedHistory.indexOfKey(timestamp + 1)).isLessThan(0);

        SparseIntArray recordedUids = recordedHistory.get(timestamp);
        assertThat(recordedUids).isNotSameInstanceAs(uids);
        assertThat(areSame(recordedUids, uids)).isTrue();

        uids.put(1, 7);
        uids.clear();
        uids.put(10, 12);
        uids.put(17, 53);

        history.recordActivity(subsystem, timestamp, uids);

        recordedUids = recordedHistory.get(timestamp);

        assertThat(recordedUids.size()).isEqualTo(4);
        assertThat(recordedUids.indexOfKey(1)).isAtLeast(0);
        assertThat(recordedUids.get(5, -1)).isEqualTo(2);
        assertThat(recordedUids.get(10, -1)).isEqualTo(12);
        assertThat(recordedUids.get(17, -1)).isEqualTo(53);
    }

    @Test
    public void recordActivityDoesNotDeleteExistingUids() {
        final WakingActivityHistory history = new WakingActivityHistory(() -> Long.MAX_VALUE);
        final int subsystem = 42;
        long timestamp = 101;

        final SparseIntArray uids = new SparseIntArray();
        uids.put(1, 17);
        uids.put(15, 2);
        uids.put(62, 31);

        history.recordActivity(subsystem, timestamp, uids);

        assertThat(history.mWakingActivity.size()).isEqualTo(1);
        assertThat(history.mWakingActivity.contains(subsystem)).isTrue();
        assertThat(history.mWakingActivity.contains(subsystem + 1)).isFalse();
        assertThat(history.mWakingActivity.contains(subsystem - 1)).isFalse();

        final LongSparseArray<SparseIntArray> recordedHistory = history.mWakingActivity.get(
                subsystem);

        assertThat(recordedHistory.size()).isEqualTo(1);
        assertThat(recordedHistory.indexOfKey(timestamp - 1)).isLessThan(0);
        assertThat(recordedHistory.indexOfKey(timestamp)).isAtLeast(0);
        assertThat(recordedHistory.indexOfKey(timestamp + 1)).isLessThan(0);

        SparseIntArray recordedUids = recordedHistory.get(timestamp);
        assertThat(recordedUids).isNotSameInstanceAs(uids);
        assertThat(areSame(recordedUids, uids)).isTrue();

        uids.delete(1);
        uids.delete(15);
        uids.put(85, 39);

        history.recordActivity(subsystem, timestamp, uids);
        recordedUids = recordedHistory.get(timestamp);

        assertThat(recordedUids.size()).isEqualTo(4);
        assertThat(recordedUids.get(1, -1)).isEqualTo(17);
        assertThat(recordedUids.get(15, -1)).isEqualTo(2);
        assertThat(recordedUids.get(62, -1)).isEqualTo(31);
        assertThat(recordedUids.get(85, -1)).isEqualTo(39);

        uids.clear();
        history.recordActivity(subsystem, timestamp, uids);
        recordedUids = recordedHistory.get(timestamp);

        assertThat(recordedUids.size()).isEqualTo(4);
        assertThat(recordedUids.get(1, -1)).isEqualTo(17);
        assertThat(recordedUids.get(15, -1)).isEqualTo(2);
        assertThat(recordedUids.get(62, -1)).isEqualTo(31);
        assertThat(recordedUids.get(85, -1)).isEqualTo(39);
    }

    @Test
    public void removeBetween() {
        final WakingActivityHistory history = new WakingActivityHistory(() -> Long.MAX_VALUE);

        final int subsystem = 43;

        final SparseIntArray uids = new SparseIntArray();
        uids.put(1, 17);
        uids.put(15, 2);
        uids.put(62, 31);
        history.recordActivity(subsystem, 123, uids);

        uids.put(54, 91);
        history.recordActivity(subsystem, 150, uids);

        uids.put(101, 32);
        uids.delete(1);
        history.recordActivity(subsystem, 191, uids);

        SparseIntArray removedUids = history.removeBetween(subsystem, 100, 122);
        assertThat(removedUids).isNull();
        assertThat(history.mWakingActivity.get(subsystem).size()).isEqualTo(3);

        removedUids = history.removeBetween(subsystem, 124, 149);
        assertThat(removedUids).isNull();
        assertThat(history.mWakingActivity.get(subsystem).size()).isEqualTo(3);

        removedUids = history.removeBetween(subsystem, 151, 190);
        assertThat(removedUids).isNull();
        assertThat(history.mWakingActivity.get(subsystem).size()).isEqualTo(3);

        removedUids = history.removeBetween(subsystem, 192, 240);
        assertThat(removedUids).isNull();
        assertThat(history.mWakingActivity.get(subsystem).size()).isEqualTo(3);


        // Removing from a different subsystem should do nothing.
        removedUids = history.removeBetween(subsystem + 1, 0, 300);
        assertThat(removedUids).isNull();
        assertThat(history.mWakingActivity.get(subsystem).size()).isEqualTo(3);

        removedUids = history.removeBetween(subsystem, 0, 300);
        assertThat(removedUids.size()).isEqualTo(5);
        assertThat(removedUids.get(1, -1)).isEqualTo(17);
        assertThat(removedUids.get(15, -1)).isEqualTo(2);
        assertThat(removedUids.get(62, -1)).isEqualTo(31);
        assertThat(removedUids.get(54, -1)).isEqualTo(91);
        assertThat(removedUids.get(101, -1)).isEqualTo(32);

        assertThat(history.mWakingActivity.get(subsystem).size()).isEqualTo(0);

        history.recordActivity(subsystem, 23, uids);
        uids.put(31, 123);
        history.recordActivity(subsystem, 49, uids);
        uids.put(177, 432);
        history.recordActivity(subsystem, 89, uids);

        removedUids = history.removeBetween(subsystem, 23, 23);
        assertThat(removedUids.size()).isEqualTo(4);
        assertThat(removedUids.get(15, -1)).isEqualTo(2);
        assertThat(removedUids.get(62, -1)).isEqualTo(31);
        assertThat(removedUids.get(54, -1)).isEqualTo(91);
        assertThat(removedUids.get(101, -1)).isEqualTo(32);

        assertThat(history.mWakingActivity.get(subsystem).size()).isEqualTo(2);

        removedUids = history.removeBetween(subsystem, 49, 54);
        assertThat(removedUids.size()).isEqualTo(5);
        assertThat(removedUids.get(15, -1)).isEqualTo(2);
        assertThat(removedUids.get(62, -1)).isEqualTo(31);
        assertThat(removedUids.get(54, -1)).isEqualTo(91);
        assertThat(removedUids.get(101, -1)).isEqualTo(32);
        assertThat(removedUids.get(31, -1)).isEqualTo(123);

        assertThat(history.mWakingActivity.get(subsystem).size()).isEqualTo(1);

        removedUids = history.removeBetween(subsystem, 23, 89);
        assertThat(removedUids.size()).isEqualTo(6);
        assertThat(removedUids.get(15, -1)).isEqualTo(2);
        assertThat(removedUids.get(62, -1)).isEqualTo(31);
        assertThat(removedUids.get(54, -1)).isEqualTo(91);
        assertThat(removedUids.get(101, -1)).isEqualTo(32);
        assertThat(removedUids.get(31, -1)).isEqualTo(123);
        assertThat(removedUids.get(177, -1)).isEqualTo(432);

        assertThat(history.mWakingActivity.get(subsystem).size()).isEqualTo(0);
    }

    @Test
    public void deletesActivityPastRetention() {
        final WakingActivityHistory history = new WakingActivityHistory(() -> mTestRetention);
        final int subsystem = 49;

        mTestRetention = 454;

        final long firstTime = 342;
        for (int i = 0; i < mTestRetention; i++) {
            history.recordActivity(subsystem, firstTime + i, new SparseIntArray());
        }
        assertThat(history.mWakingActivity.get(subsystem)).isNotNull();
        assertThat(history.mWakingActivity.get(subsystem).size()).isEqualTo(mTestRetention);

        history.recordActivity(subsystem, firstTime + mTestRetention + 7, new SparseIntArray());
        assertThat(history.mWakingActivity.get(subsystem).size()).isEqualTo(mTestRetention - 7);

        final ThreadLocalRandom random = ThreadLocalRandom.current();

        for (int i = 0; i < 100; i++) {
            final long time = random.nextLong(firstTime + mTestRetention + 100,
                    456 * mTestRetention);
            history.recordActivity(subsystem, time, new SparseIntArray());
            assertThat(history.mWakingActivity.get(subsystem).lastIndexOnOrBefore(
                    time - mTestRetention)).isLessThan(0);
        }
    }
}
