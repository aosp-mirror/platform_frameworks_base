/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.server.power.stats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.nano.OsProtoEnums;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class WakelockStatsFrameworkEventsTest {
    private WakelockStatsFrameworkEvents mEvents;

    @Before
    public void setup() {
        mEvents = new WakelockStatsFrameworkEvents();
    }

    private static final int UID_1 = 1;
    private static final int UID_2 = 2;

    private static final String TAG_1 = "TAG1";
    private static final String TAG_2 = "TAG2";

    private static final int WAKELOCK_TYPE_1 = OsProtoEnums.PARTIAL_WAKE_LOCK;
    private static final int WAKELOCK_TYPE_2 = OsProtoEnums.DOZE_WAKE_LOCK;

    private static final long TS_1 = 1000;
    private static final long TS_2 = 2000;
    private static final long TS_3 = 3000;
    private static final long TS_4 = 4000;
    private static final long TS_5 = 5000;

    // Mirrors com.android.os.framework.FrameworkWakelockInfo proto.
    private static class WakelockInfo {
        public int uid;
        public String tag;
        public int type;
        public long uptimeMillis;
        public long completedCount;

        WakelockInfo(int uid, String tag, int type, long uptimeMillis, long completedCount) {
            this.uid = uid;
            this.tag = tag;
            this.type = type;
            this.uptimeMillis = uptimeMillis;
            this.completedCount = completedCount;
        }
    }

    // Assumes that mEvents is empty.
    @SuppressWarnings("GuardedBy")
    private void makeMetricsAlmostOverflow() throws Exception {
        for (int i = 0; i < mEvents.SUMMARY_THRESHOLD - 1; i++) {
            String tag = "forceOverflow" + i;
            mEvents.noteStartWakeLock(UID_1, tag, WAKELOCK_TYPE_1, TS_1);
            mEvents.noteStopWakeLock(UID_1, tag, WAKELOCK_TYPE_1, TS_2);
        }

        assertFalse("not overflow", mEvents.inOverflow());
        ArrayList<WakelockInfo> info = pullResults(TS_4);
        WakelockInfo notOverflowInfo =
                info.stream()
                        .filter(i -> i.tag.equals(mEvents.OVERFLOW_TAG))
                        .findFirst()
                        .orElse(null);

        assertEquals("not overflow", notOverflowInfo, null);

        // Add one more to hit an overflow state.
        String lastTag = "forceOverflowLast";
        mEvents.noteStartWakeLock(UID_1, lastTag, WAKELOCK_TYPE_2, TS_1);
        mEvents.noteStopWakeLock(UID_1, lastTag, WAKELOCK_TYPE_2, TS_2);

        assertTrue("overflow", mEvents.inOverflow());
        info = pullResults(TS_4);

        WakelockInfo tag1Info =
                info.stream().filter(i -> i.tag.equals(lastTag)).findFirst().orElse(null);

        assertTrue("lastTag found", tag1Info != null);
        assertEquals("uid", UID_1, tag1Info.uid);
        assertEquals("tag", lastTag, tag1Info.tag);
        assertEquals("type", WAKELOCK_TYPE_2, tag1Info.type);
        assertEquals("duration", TS_2 - TS_1, tag1Info.uptimeMillis);
        assertEquals("count", 1, tag1Info.completedCount);
    }

    // Assumes that mEvents is empty.
    @SuppressWarnings("GuardedBy")
    private void makeMetricsAlmostHardCap() throws Exception {
        for (int i = 0; i < mEvents.MAX_WAKELOCK_DIMENSIONS - 1; i++) {
            mEvents.noteStartWakeLock(i /* uid */, TAG_1, WAKELOCK_TYPE_1, TS_1);
            mEvents.noteStopWakeLock(i /* uid */, TAG_1, WAKELOCK_TYPE_1, TS_2);
        }

        assertFalse("not hard capped", mEvents.inHardCap());
        ArrayList<WakelockInfo> info = pullResults(TS_4);
        WakelockInfo notOverflowInfo =
                info.stream()
                        .filter(i -> i.tag.equals(mEvents.HARD_CAP_TAG))
                        .findFirst()
                        .orElse(null);

        assertEquals("not overflow", notOverflowInfo, null);

        // Add one more to hit an hardcap state.
        int hardCapUid = mEvents.MAX_WAKELOCK_DIMENSIONS;
        mEvents.noteStartWakeLock(hardCapUid, TAG_2, WAKELOCK_TYPE_2, TS_1);
        mEvents.noteStopWakeLock(hardCapUid, TAG_2, WAKELOCK_TYPE_2, TS_2);

        assertTrue("hard capped", mEvents.inHardCap());
        info = pullResults(TS_4);

        WakelockInfo tag2Info =
                info.stream().filter(i -> i.uid == hardCapUid).findFirst().orElse(null);

        assertTrue("hardCapUid found", tag2Info != null);
        assertEquals("uid", hardCapUid, tag2Info.uid);
        assertEquals("tag", mEvents.OVERFLOW_TAG, tag2Info.tag);
        assertEquals("type", mEvents.OVERFLOW_LEVEL, tag2Info.type);
        assertEquals("duration", TS_2 - TS_1, tag2Info.uptimeMillis);
        assertEquals("count", 1, tag2Info.completedCount);
    }

    private ArrayList<WakelockInfo> pullResults(long timestamp) {
        ArrayList<WakelockInfo> results = new ArrayList<>();
        WakelockStatsFrameworkEvents.EventLogger logger =
                new WakelockStatsFrameworkEvents.EventLogger() {
                    public void logResult(
                            int uid,
                            String tag,
                            int wakeLockLevel,
                            long uptimeMillis,
                            long completedCount) {
                        WakelockInfo info =
                                new WakelockInfo(
                                        uid, tag, wakeLockLevel, uptimeMillis, completedCount);
                        results.add(info);
                    }
                };
        mEvents.pullFrameworkWakelockInfoAtoms(timestamp, logger);
        return results;
    }

    @Test
    public void singleWakelock() {
        mEvents.noteStartWakeLock(UID_1, TAG_1, WAKELOCK_TYPE_1, TS_1);
        mEvents.noteStopWakeLock(UID_1, TAG_1, WAKELOCK_TYPE_1, TS_2);

        ArrayList<WakelockInfo> info = pullResults(TS_3);

        assertEquals("size", 1, info.size());
        assertEquals("uid", UID_1, info.get(0).uid);
        assertEquals("tag", TAG_1, info.get(0).tag);
        assertEquals("type", WAKELOCK_TYPE_1, info.get(0).type);
        assertEquals("duration", TS_2 - TS_1, info.get(0).uptimeMillis);
        assertEquals("count", 1, info.get(0).completedCount);
    }

    @Test
    public void wakelockOpen() throws Exception {
        mEvents.noteStartWakeLock(UID_1, TAG_1, WAKELOCK_TYPE_1, TS_1);

        ArrayList<WakelockInfo> info = pullResults(TS_3);

        assertEquals("size", 1, info.size());
        assertEquals("uid", UID_1, info.get(0).uid);
        assertEquals("tag", TAG_1, info.get(0).tag);
        assertEquals("type", WAKELOCK_TYPE_1, info.get(0).type);
        assertEquals("duration", TS_3 - TS_1, info.get(0).uptimeMillis);
        assertEquals("count", 0, info.get(0).completedCount);
    }

    @Test
    public void wakelockOpenOverlap() throws Exception {
        mEvents.noteStartWakeLock(UID_1, TAG_1, WAKELOCK_TYPE_1, TS_1);
        mEvents.noteStartWakeLock(UID_1, TAG_1, WAKELOCK_TYPE_1, TS_2);
        mEvents.noteStopWakeLock(UID_1, TAG_1, WAKELOCK_TYPE_1, TS_3);

        ArrayList<WakelockInfo> info = pullResults(TS_4);

        assertEquals("size", 1, info.size());
        assertEquals("uid", UID_1, info.get(0).uid);
        assertEquals("tag", TAG_1, info.get(0).tag);
        assertEquals("type", WAKELOCK_TYPE_1, info.get(0).type);
        assertEquals("duration", TS_4 - TS_1, info.get(0).uptimeMillis);
        assertEquals("count", 0, info.get(0).completedCount);
    }

    @Test
    public void testOverflow() throws Exception {
        makeMetricsAlmostOverflow();

        // This one gets tagged as an overflow.
        mEvents.noteStartWakeLock(UID_1, TAG_2, WAKELOCK_TYPE_2, TS_1);
        mEvents.noteStopWakeLock(UID_1, TAG_2, WAKELOCK_TYPE_2, TS_2);

        ArrayList<WakelockInfo> info = pullResults(TS_4);
        WakelockInfo overflowInfo =
                info.stream()
                        .filter(i -> i.tag.equals(mEvents.OVERFLOW_TAG))
                        .findFirst()
                        .orElse(null);

        assertEquals("uid", UID_1, overflowInfo.uid);
        assertEquals("type", mEvents.OVERFLOW_LEVEL, overflowInfo.type);
        assertEquals("duration", TS_2 - TS_1, overflowInfo.uptimeMillis);
        assertEquals("count", 1, overflowInfo.completedCount);
    }

    @Test
    public void testOverflowOpen() throws Exception {
        makeMetricsAlmostOverflow();

        // This is the open wakelock that overflows.
        mEvents.noteStartWakeLock(UID_1, TAG_2, WAKELOCK_TYPE_2, TS_1);

        ArrayList<WakelockInfo> info = pullResults(TS_4);
        WakelockInfo overflowInfo =
                info.stream()
                        .filter(i -> i.tag.equals(mEvents.OVERFLOW_TAG))
                        .findFirst()
                        .orElse(null);

        assertEquals("uid", UID_1, overflowInfo.uid);
        assertEquals("type", mEvents.OVERFLOW_LEVEL, overflowInfo.type);
        assertEquals("duration", (TS_4 - TS_1), overflowInfo.uptimeMillis);
        assertEquals("count", 0, overflowInfo.completedCount);
    }

    @Test
    public void testHardCap() throws Exception {
        makeMetricsAlmostHardCap();

        // This one gets tagged as a hard cap.
        mEvents.noteStartWakeLock(UID_1, TAG_2, WAKELOCK_TYPE_2, TS_1);
        mEvents.noteStopWakeLock(UID_1, TAG_2, WAKELOCK_TYPE_2, TS_2);

        ArrayList<WakelockInfo> info = pullResults(TS_4);
        WakelockInfo hardCapInfo =
                info.stream()
                        .filter(i -> i.tag.equals(mEvents.HARD_CAP_TAG))
                        .findFirst()
                        .orElse(null);

        assertEquals("uid", mEvents.HARD_CAP_UID, hardCapInfo.uid);
        assertEquals("type", mEvents.OVERFLOW_LEVEL, hardCapInfo.type);
        assertEquals("duration", TS_2 - TS_1, hardCapInfo.uptimeMillis);
        assertEquals("count", 1, hardCapInfo.completedCount);
    }

    @Test
    public void testHardCapOpen() throws Exception {
        makeMetricsAlmostHardCap();

        // This is the open wakelock that overflows.
        mEvents.noteStartWakeLock(UID_1, TAG_2, WAKELOCK_TYPE_2, TS_1);

        ArrayList<WakelockInfo> info = pullResults(TS_4);
        WakelockInfo hardCapInfo =
                info.stream()
                        .filter(i -> i.tag.equals(mEvents.HARD_CAP_TAG))
                        .findFirst()
                        .orElse(null);

        assertEquals("uid", mEvents.HARD_CAP_UID, hardCapInfo.uid);
        assertEquals("type", mEvents.OVERFLOW_LEVEL, hardCapInfo.type);
        assertEquals("duration", (TS_4 - TS_1), hardCapInfo.uptimeMillis);
        assertEquals("count", 0, hardCapInfo.completedCount);
    }

    @Test
    public void overlappingWakelocks() throws Exception {
        mEvents.noteStartWakeLock(UID_1, TAG_1, WAKELOCK_TYPE_1, TS_1);
        mEvents.noteStartWakeLock(UID_1, TAG_1, WAKELOCK_TYPE_1, TS_2);
        mEvents.noteStopWakeLock(UID_1, TAG_1, WAKELOCK_TYPE_1, TS_3);
        mEvents.noteStopWakeLock(UID_1, TAG_1, WAKELOCK_TYPE_1, TS_4);

        ArrayList<WakelockInfo> info = pullResults(TS_5);

        assertEquals("size", 1, info.size());
        assertEquals("uid", UID_1, info.get(0).uid);
        assertEquals("tag", TAG_1, info.get(0).tag);
        assertEquals("type", WAKELOCK_TYPE_1, info.get(0).type);
        assertEquals("duration", TS_4 - TS_1, info.get(0).uptimeMillis);
        assertEquals("count", 1, info.get(0).completedCount);
    }

    @Test
    public void diffUid() throws Exception {
        mEvents.noteStartWakeLock(UID_1, TAG_1, WAKELOCK_TYPE_1, TS_1);
        mEvents.noteStartWakeLock(UID_2, TAG_1, WAKELOCK_TYPE_1, TS_2);
        mEvents.noteStopWakeLock(UID_2, TAG_1, WAKELOCK_TYPE_1, TS_3);
        mEvents.noteStopWakeLock(UID_1, TAG_1, WAKELOCK_TYPE_1, TS_4);

        ArrayList<WakelockInfo> info = pullResults(TS_5);
        assertEquals("size", 2, info.size());

        WakelockInfo uid1Info = info.stream().filter(i -> i.uid == UID_1).findFirst().orElse(null);

        assertTrue("UID_1 found", uid1Info != null);
        assertEquals("uid", UID_1, uid1Info.uid);
        assertEquals("tag", TAG_1, uid1Info.tag);
        assertEquals("type", WAKELOCK_TYPE_1, uid1Info.type);
        assertEquals("duration", TS_4 - TS_1, uid1Info.uptimeMillis);
        assertEquals("count", 1, uid1Info.completedCount);

        WakelockInfo uid2Info = info.stream().filter(i -> i.uid == UID_2).findFirst().orElse(null);
        assertTrue("UID_2 found", uid2Info != null);
        assertEquals("uid", UID_2, uid2Info.uid);
        assertEquals("tag", TAG_1, uid2Info.tag);
        assertEquals("type", WAKELOCK_TYPE_1, uid2Info.type);
        assertEquals("duration", TS_3 - TS_2, uid2Info.uptimeMillis);
        assertEquals("count", 1, uid2Info.completedCount);
    }

    @Test
    public void diffTag() throws Exception {
        mEvents.noteStartWakeLock(UID_1, TAG_1, WAKELOCK_TYPE_1, TS_1);
        mEvents.noteStartWakeLock(UID_1, TAG_2, WAKELOCK_TYPE_1, TS_2);
        mEvents.noteStopWakeLock(UID_1, TAG_2, WAKELOCK_TYPE_1, TS_3);
        mEvents.noteStopWakeLock(UID_1, TAG_1, WAKELOCK_TYPE_1, TS_4);

        ArrayList<WakelockInfo> info = pullResults(TS_5);
        assertEquals("size", 2, info.size());

        WakelockInfo uid1Info =
                info.stream().filter(i -> i.tag.equals(TAG_1)).findFirst().orElse(null);

        assertTrue("TAG_1 found", uid1Info != null);
        assertEquals("uid", UID_1, uid1Info.uid);
        assertEquals("tag", TAG_1, uid1Info.tag);
        assertEquals("type", WAKELOCK_TYPE_1, uid1Info.type);
        assertEquals("duration", TS_4 - TS_1, uid1Info.uptimeMillis);
        assertEquals("count", 1, uid1Info.completedCount);

        WakelockInfo uid2Info =
                info.stream().filter(i -> i.tag.equals(TAG_2)).findFirst().orElse(null);
        assertTrue("TAG_2 found", uid2Info != null);
        assertEquals("uid", UID_1, uid2Info.uid);
        assertEquals("tag", TAG_2, uid2Info.tag);
        assertEquals("type", WAKELOCK_TYPE_1, uid2Info.type);
        assertEquals("duration", TS_3 - TS_2, uid2Info.uptimeMillis);
        assertEquals("count", 1, uid2Info.completedCount);
    }

    @Test
    public void diffType() throws Exception {
        mEvents.noteStartWakeLock(UID_1, TAG_1, WAKELOCK_TYPE_1, TS_1);
        mEvents.noteStartWakeLock(UID_1, TAG_1, WAKELOCK_TYPE_2, TS_2);
        mEvents.noteStopWakeLock(UID_1, TAG_1, WAKELOCK_TYPE_2, TS_3);
        mEvents.noteStopWakeLock(UID_1, TAG_1, WAKELOCK_TYPE_1, TS_4);

        ArrayList<WakelockInfo> info = pullResults(TS_5);
        assertEquals("size", 2, info.size());

        WakelockInfo uid1Info =
                info.stream().filter(i -> i.type == WAKELOCK_TYPE_1).findFirst().orElse(null);

        assertTrue("WAKELOCK_TYPE_1 found", uid1Info != null);
        assertEquals("uid", UID_1, uid1Info.uid);
        assertEquals("tag", TAG_1, uid1Info.tag);
        assertEquals("type", WAKELOCK_TYPE_1, uid1Info.type);
        assertEquals("duration", TS_4 - TS_1, uid1Info.uptimeMillis);
        assertEquals("count", 1, uid1Info.completedCount);

        WakelockInfo uid2Info =
                info.stream().filter(i -> i.type == WAKELOCK_TYPE_2).findFirst().orElse(null);
        assertTrue("WAKELOCK_TYPE_2 found", uid2Info != null);
        assertEquals("uid", UID_1, uid2Info.uid);
        assertEquals("tag", TAG_1, uid2Info.tag);
        assertEquals("type", WAKELOCK_TYPE_2, uid2Info.type);
        assertEquals("duration", TS_3 - TS_2, uid2Info.uptimeMillis);
        assertEquals("count", 1, uid2Info.completedCount);
    }
}
