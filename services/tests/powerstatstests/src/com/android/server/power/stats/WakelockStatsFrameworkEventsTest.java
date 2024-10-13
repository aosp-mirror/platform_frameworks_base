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

import android.os.WakeLockLevelEnum;
import android.util.StatsEvent;
import android.util.StatsEventTestUtils;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.os.AtomsProto;
import com.android.os.framework.FrameworkExtensionAtoms;
import com.android.os.framework.FrameworkExtensionAtoms.FrameworkWakelockInfo;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.ExtensionRegistryLite;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class WakelockStatsFrameworkEventsTest {
    private WakelockStatsFrameworkEvents mEvents;
    private ExtensionRegistryLite mRegistry;

    @Before
    public void setup() {
        mEvents = new WakelockStatsFrameworkEvents();
        mRegistry = ExtensionRegistryLite.newInstance();
        FrameworkExtensionAtoms.registerAllExtensions(mRegistry);
    }

    private static final int UID_1 = 1;
    private static final int UID_2 = 2;

    private static final String TAG_1 = "TAG1";
    private static final String TAG_2 = "TAG2";

    private static final WakeLockLevelEnum WAKELOCK_TYPE_1 = WakeLockLevelEnum.PARTIAL_WAKE_LOCK;
    private static final WakeLockLevelEnum WAKELOCK_TYPE_2 = WakeLockLevelEnum.DOZE_WAKE_LOCK;

    private static final long TS_1 = 1000;
    private static final long TS_2 = 2000;
    private static final long TS_3 = 3000;
    private static final long TS_4 = 4000;
    private static final long TS_5 = 5000;

    // Assumes that mEvents is empty.
    private void makeMetricsAlmostOverflow() throws Exception {
        for (int i = 0; i < mEvents.SUMMARY_THRESHOLD - 1; i++) {
            String tag = "forceOverflow" + i;
            mEvents.noteStartWakeLock(UID_1, tag, WAKELOCK_TYPE_1.getNumber(), TS_1);
            mEvents.noteStopWakeLock(UID_1, tag, WAKELOCK_TYPE_1.getNumber(), TS_2);
        }

        assertFalse("not overflow", mEvents.inOverflow());
        ArrayList<FrameworkWakelockInfo> info = pullResults(TS_4);
        FrameworkWakelockInfo notOverflowInfo =
                info.stream()
                        .filter(i -> i.getAttributionTag().equals(mEvents.OVERFLOW_TAG))
                        .findFirst()
                        .orElse(null);

        assertEquals("not overflow", notOverflowInfo, null);

        // Add one more to hit an overflow state.
        String lastTag = "forceOverflowLast";
        mEvents.noteStartWakeLock(UID_1, lastTag, WAKELOCK_TYPE_2.getNumber(), TS_1);
        mEvents.noteStopWakeLock(UID_1, lastTag, WAKELOCK_TYPE_2.getNumber(), TS_2);

        assertTrue("overflow", mEvents.inOverflow());
        info = pullResults(TS_4);

        FrameworkWakelockInfo tag1Info =
                info.stream()
                        .filter(i -> i.getAttributionTag().equals(lastTag))
                        .findFirst()
                        .orElse(null);

        assertTrue("lastTag found", tag1Info != null);
        assertEquals("uid", UID_1, tag1Info.getAttributionUid());
        assertEquals("tag", lastTag, tag1Info.getAttributionTag());
        assertEquals("type", WAKELOCK_TYPE_2, tag1Info.getType());
        assertEquals("duration", TS_2 - TS_1, tag1Info.getUptimeMillis());
        assertEquals("count", 1, tag1Info.getCompletedCount());
    }

    // Assumes that mEvents is empty.
    private void makeMetricsAlmostHardCap() throws Exception {
        for (int i = 0; i < mEvents.MAX_WAKELOCK_DIMENSIONS - 1; i++) {
            mEvents.noteStartWakeLock(i /* uid */, TAG_1, WAKELOCK_TYPE_1.getNumber(), TS_1);
            mEvents.noteStopWakeLock(i /* uid */, TAG_1, WAKELOCK_TYPE_1.getNumber(), TS_2);
        }

        assertFalse("not hard capped", mEvents.inHardCap());
        ArrayList<FrameworkWakelockInfo> info = pullResults(TS_4);
        FrameworkWakelockInfo notOverflowInfo =
                info.stream()
                        .filter(i -> i.getAttributionTag().equals(mEvents.HARD_CAP_TAG))
                        .findFirst()
                        .orElse(null);

        assertEquals("not overflow", notOverflowInfo, null);

        // Add one more to hit an hardcap state.
        int hardCapUid = mEvents.MAX_WAKELOCK_DIMENSIONS;
        mEvents.noteStartWakeLock(hardCapUid, TAG_2, WAKELOCK_TYPE_2.getNumber(), TS_1);
        mEvents.noteStopWakeLock(hardCapUid, TAG_2, WAKELOCK_TYPE_2.getNumber(), TS_2);

        assertTrue("hard capped", mEvents.inHardCap());
        info = pullResults(TS_4);

        FrameworkWakelockInfo tag2Info =
                info.stream()
                        .filter(i -> i.getAttributionUid() == hardCapUid)
                        .findFirst()
                        .orElse(null);

        assertTrue("hardCapUid found", tag2Info != null);
        assertEquals("uid", hardCapUid, tag2Info.getAttributionUid());
        assertEquals("tag", mEvents.OVERFLOW_TAG, tag2Info.getAttributionTag());
        assertEquals(
                "type", WakeLockLevelEnum.forNumber(mEvents.OVERFLOW_LEVEL), tag2Info.getType());
        assertEquals("duration", TS_2 - TS_1, tag2Info.getUptimeMillis());
        assertEquals("count", 1, tag2Info.getCompletedCount());
    }

    private ArrayList<FrameworkWakelockInfo> pullResults(long timestamp) throws Exception {
        ArrayList<FrameworkWakelockInfo> result = new ArrayList<>();
        List<StatsEvent> events = mEvents.pullFrameworkWakelockInfoAtoms(timestamp);

        for (StatsEvent e : events) {
            // The returned atom does not have external extensions registered.
            // So we serialize and then deserialize with extensions registered.
            AtomsProto.Atom atom = StatsEventTestUtils.convertToAtom(e);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            CodedOutputStream codedos = CodedOutputStream.newInstance(outputStream);
            atom.writeTo(codedos);
            codedos.flush();

            ByteArrayInputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray());
            CodedInputStream codedis = CodedInputStream.newInstance(inputStream);
            AtomsProto.Atom atomWithExtensions = AtomsProto.Atom.parseFrom(codedis, mRegistry);

            assertTrue(
                    atomWithExtensions.hasExtension(FrameworkExtensionAtoms.frameworkWakelockInfo));
            FrameworkWakelockInfo info =
                    atomWithExtensions.getExtension(FrameworkExtensionAtoms.frameworkWakelockInfo);
            result.add(info);
        }

        return result;
    }

    @Test
    public void singleWakelock() throws Exception {
        mEvents.noteStartWakeLock(UID_1, TAG_1, WAKELOCK_TYPE_1.getNumber(), TS_1);
        mEvents.noteStopWakeLock(UID_1, TAG_1, WAKELOCK_TYPE_1.getNumber(), TS_2);

        ArrayList<FrameworkWakelockInfo> info = pullResults(TS_3);

        assertEquals("size", 1, info.size());
        assertEquals("uid", UID_1, info.get(0).getAttributionUid());
        assertEquals("tag", TAG_1, info.get(0).getAttributionTag());
        assertEquals("type", WAKELOCK_TYPE_1, info.get(0).getType());
        assertEquals("duration", TS_2 - TS_1, info.get(0).getUptimeMillis());
        assertEquals("count", 1, info.get(0).getCompletedCount());
    }

    @Test
    public void wakelockOpen() throws Exception {
        mEvents.noteStartWakeLock(UID_1, TAG_1, WAKELOCK_TYPE_1.getNumber(), TS_1);

        ArrayList<FrameworkWakelockInfo> info = pullResults(TS_3);

        assertEquals("size", 1, info.size());
        assertEquals("uid", UID_1, info.get(0).getAttributionUid());
        assertEquals("tag", TAG_1, info.get(0).getAttributionTag());
        assertEquals("type", WAKELOCK_TYPE_1, info.get(0).getType());
        assertEquals("duration", TS_3 - TS_1, info.get(0).getUptimeMillis());
        assertEquals("count", 0, info.get(0).getCompletedCount());
    }

    @Test
    public void wakelockOpenOverlap() throws Exception {
        mEvents.noteStartWakeLock(UID_1, TAG_1, WAKELOCK_TYPE_1.getNumber(), TS_1);
        mEvents.noteStartWakeLock(UID_1, TAG_1, WAKELOCK_TYPE_1.getNumber(), TS_2);
        mEvents.noteStopWakeLock(UID_1, TAG_1, WAKELOCK_TYPE_1.getNumber(), TS_3);

        ArrayList<FrameworkWakelockInfo> info = pullResults(TS_4);

        assertEquals("size", 1, info.size());
        assertEquals("uid", UID_1, info.get(0).getAttributionUid());
        assertEquals("tag", TAG_1, info.get(0).getAttributionTag());
        assertEquals("type", WAKELOCK_TYPE_1, info.get(0).getType());
        assertEquals("duration", TS_4 - TS_1, info.get(0).getUptimeMillis());
        assertEquals("count", 0, info.get(0).getCompletedCount());
    }

    @Test
    public void testOverflow() throws Exception {
        makeMetricsAlmostOverflow();

        // This one gets tagged as an overflow.
        mEvents.noteStartWakeLock(UID_1, TAG_2, WAKELOCK_TYPE_2.getNumber(), TS_1);
        mEvents.noteStopWakeLock(UID_1, TAG_2, WAKELOCK_TYPE_2.getNumber(), TS_2);

        ArrayList<FrameworkWakelockInfo> info = pullResults(TS_4);
        FrameworkWakelockInfo overflowInfo =
                info.stream()
                        .filter(i -> i.getAttributionTag().equals(mEvents.OVERFLOW_TAG))
                        .findFirst()
                        .orElse(null);

        assertEquals("uid", UID_1, overflowInfo.getAttributionUid());
        assertEquals(
                "type",
                WakeLockLevelEnum.forNumber(mEvents.OVERFLOW_LEVEL),
                overflowInfo.getType());
        assertEquals("duration", TS_2 - TS_1, overflowInfo.getUptimeMillis());
        assertEquals("count", 1, overflowInfo.getCompletedCount());
    }

    @Test
    public void testOverflowOpen() throws Exception {
        makeMetricsAlmostOverflow();

        // This is the open wakelock that overflows.
        mEvents.noteStartWakeLock(UID_1, TAG_2, WAKELOCK_TYPE_2.getNumber(), TS_1);

        ArrayList<FrameworkWakelockInfo> info = pullResults(TS_4);
        FrameworkWakelockInfo overflowInfo =
                info.stream()
                        .filter(i -> i.getAttributionTag().equals(mEvents.OVERFLOW_TAG))
                        .findFirst()
                        .orElse(null);

        assertEquals("uid", UID_1, overflowInfo.getAttributionUid());
        assertEquals(
                "type",
                WakeLockLevelEnum.forNumber(mEvents.OVERFLOW_LEVEL),
                overflowInfo.getType());
        assertEquals("duration", (TS_4 - TS_1), overflowInfo.getUptimeMillis());
        assertEquals("count", 0, overflowInfo.getCompletedCount());
    }

    @Test
    public void testHardCap() throws Exception {
        makeMetricsAlmostHardCap();

        // This one gets tagged as a hard cap.
        mEvents.noteStartWakeLock(UID_1, TAG_2, WAKELOCK_TYPE_2.getNumber(), TS_1);
        mEvents.noteStopWakeLock(UID_1, TAG_2, WAKELOCK_TYPE_2.getNumber(), TS_2);

        ArrayList<FrameworkWakelockInfo> info = pullResults(TS_4);
        FrameworkWakelockInfo hardCapInfo =
                info.stream()
                        .filter(i -> i.getAttributionTag().equals(mEvents.HARD_CAP_TAG))
                        .findFirst()
                        .orElse(null);

        assertEquals("uid", mEvents.HARD_CAP_UID, hardCapInfo.getAttributionUid());
        assertEquals(
                "type",
                WakeLockLevelEnum.forNumber(mEvents.OVERFLOW_LEVEL),
                hardCapInfo.getType());
        assertEquals("duration", TS_2 - TS_1, hardCapInfo.getUptimeMillis());
        assertEquals("count", 1, hardCapInfo.getCompletedCount());
    }

    @Test
    public void testHardCapOpen() throws Exception {
        makeMetricsAlmostHardCap();

        // This is the open wakelock that overflows.
        mEvents.noteStartWakeLock(UID_1, TAG_2, WAKELOCK_TYPE_2.getNumber(), TS_1);

        ArrayList<FrameworkWakelockInfo> info = pullResults(TS_4);
        FrameworkWakelockInfo hardCapInfo =
                info.stream()
                        .filter(i -> i.getAttributionTag().equals(mEvents.HARD_CAP_TAG))
                        .findFirst()
                        .orElse(null);

        assertEquals("uid", mEvents.HARD_CAP_UID, hardCapInfo.getAttributionUid());
        assertEquals(
                "type",
                WakeLockLevelEnum.forNumber(mEvents.OVERFLOW_LEVEL),
                hardCapInfo.getType());
        assertEquals("duration", (TS_4 - TS_1), hardCapInfo.getUptimeMillis());
        assertEquals("count", 0, hardCapInfo.getCompletedCount());
    }

    @Test
    public void overlappingWakelocks() throws Exception {
        mEvents.noteStartWakeLock(UID_1, TAG_1, WAKELOCK_TYPE_1.getNumber(), TS_1);
        mEvents.noteStartWakeLock(UID_1, TAG_1, WAKELOCK_TYPE_1.getNumber(), TS_2);
        mEvents.noteStopWakeLock(UID_1, TAG_1, WAKELOCK_TYPE_1.getNumber(), TS_3);
        mEvents.noteStopWakeLock(UID_1, TAG_1, WAKELOCK_TYPE_1.getNumber(), TS_4);

        ArrayList<FrameworkWakelockInfo> info = pullResults(TS_5);

        assertEquals("size", 1, info.size());
        assertEquals("uid", UID_1, info.get(0).getAttributionUid());
        assertEquals("tag", TAG_1, info.get(0).getAttributionTag());
        assertEquals("type", WAKELOCK_TYPE_1, info.get(0).getType());
        assertEquals("duration", TS_4 - TS_1, info.get(0).getUptimeMillis());
        assertEquals("count", 1, info.get(0).getCompletedCount());
    }

    @Test
    public void diffUid() throws Exception {
        mEvents.noteStartWakeLock(UID_1, TAG_1, WAKELOCK_TYPE_1.getNumber(), TS_1);
        mEvents.noteStartWakeLock(UID_2, TAG_1, WAKELOCK_TYPE_1.getNumber(), TS_2);
        mEvents.noteStopWakeLock(UID_2, TAG_1, WAKELOCK_TYPE_1.getNumber(), TS_3);
        mEvents.noteStopWakeLock(UID_1, TAG_1, WAKELOCK_TYPE_1.getNumber(), TS_4);

        ArrayList<FrameworkWakelockInfo> info = pullResults(TS_5);
        assertEquals("size", 2, info.size());

        FrameworkWakelockInfo uid1Info =
                info.stream().filter(i -> i.getAttributionUid() == UID_1).findFirst().orElse(null);

        assertTrue("UID_1 found", uid1Info != null);
        assertEquals("uid", UID_1, uid1Info.getAttributionUid());
        assertEquals("tag", TAG_1, uid1Info.getAttributionTag());
        assertEquals("type", WAKELOCK_TYPE_1, uid1Info.getType());
        assertEquals("duration", TS_4 - TS_1, uid1Info.getUptimeMillis());
        assertEquals("count", 1, uid1Info.getCompletedCount());

        FrameworkWakelockInfo uid2Info =
                info.stream().filter(i -> i.getAttributionUid() == UID_2).findFirst().orElse(null);
        assertTrue("UID_2 found", uid2Info != null);
        assertEquals("uid", UID_2, uid2Info.getAttributionUid());
        assertEquals("tag", TAG_1, uid2Info.getAttributionTag());
        assertEquals("type", WAKELOCK_TYPE_1, uid2Info.getType());
        assertEquals("duration", TS_3 - TS_2, uid2Info.getUptimeMillis());
        assertEquals("count", 1, uid2Info.getCompletedCount());
    }

    @Test
    public void diffTag() throws Exception {
        mEvents.noteStartWakeLock(UID_1, TAG_1, WAKELOCK_TYPE_1.getNumber(), TS_1);
        mEvents.noteStartWakeLock(UID_1, TAG_2, WAKELOCK_TYPE_1.getNumber(), TS_2);
        mEvents.noteStopWakeLock(UID_1, TAG_2, WAKELOCK_TYPE_1.getNumber(), TS_3);
        mEvents.noteStopWakeLock(UID_1, TAG_1, WAKELOCK_TYPE_1.getNumber(), TS_4);

        ArrayList<FrameworkWakelockInfo> info = pullResults(TS_5);
        assertEquals("size", 2, info.size());

        FrameworkWakelockInfo uid1Info =
                info.stream()
                        .filter(i -> i.getAttributionTag().equals(TAG_1))
                        .findFirst()
                        .orElse(null);

        assertTrue("TAG_1 found", uid1Info != null);
        assertEquals("uid", UID_1, uid1Info.getAttributionUid());
        assertEquals("tag", TAG_1, uid1Info.getAttributionTag());
        assertEquals("type", WAKELOCK_TYPE_1, uid1Info.getType());
        assertEquals("duration", TS_4 - TS_1, uid1Info.getUptimeMillis());
        assertEquals("count", 1, uid1Info.getCompletedCount());

        FrameworkWakelockInfo uid2Info =
                info.stream()
                        .filter(i -> i.getAttributionTag().equals(TAG_2))
                        .findFirst()
                        .orElse(null);
        assertTrue("TAG_2 found", uid2Info != null);
        assertEquals("uid", UID_1, uid2Info.getAttributionUid());
        assertEquals("tag", TAG_2, uid2Info.getAttributionTag());
        assertEquals("type", WAKELOCK_TYPE_1, uid2Info.getType());
        assertEquals("duration", TS_3 - TS_2, uid2Info.getUptimeMillis());
        assertEquals("count", 1, uid2Info.getCompletedCount());
    }

    @Test
    public void diffType() throws Exception {
        mEvents.noteStartWakeLock(UID_1, TAG_1, WAKELOCK_TYPE_1.getNumber(), TS_1);
        mEvents.noteStartWakeLock(UID_1, TAG_1, WAKELOCK_TYPE_2.getNumber(), TS_2);
        mEvents.noteStopWakeLock(UID_1, TAG_1, WAKELOCK_TYPE_2.getNumber(), TS_3);
        mEvents.noteStopWakeLock(UID_1, TAG_1, WAKELOCK_TYPE_1.getNumber(), TS_4);

        ArrayList<FrameworkWakelockInfo> info = pullResults(TS_5);
        assertEquals("size", 2, info.size());

        FrameworkWakelockInfo uid1Info =
                info.stream().filter(i -> i.getType() == WAKELOCK_TYPE_1).findFirst().orElse(null);

        assertTrue("WAKELOCK_TYPE_1 found", uid1Info != null);
        assertEquals("uid", UID_1, uid1Info.getAttributionUid());
        assertEquals("tag", TAG_1, uid1Info.getAttributionTag());
        assertEquals("type", WAKELOCK_TYPE_1, uid1Info.getType());
        assertEquals("duration", TS_4 - TS_1, uid1Info.getUptimeMillis());
        assertEquals("count", 1, uid1Info.getCompletedCount());

        FrameworkWakelockInfo uid2Info =
                info.stream().filter(i -> i.getType() == WAKELOCK_TYPE_2).findFirst().orElse(null);
        assertTrue("WAKELOCK_TYPE_2 found", uid2Info != null);
        assertEquals("uid", UID_1, uid2Info.getAttributionUid());
        assertEquals("tag", TAG_1, uid2Info.getAttributionTag());
        assertEquals("type", WAKELOCK_TYPE_2, uid2Info.getType());
        assertEquals("duration", TS_3 - TS_2, uid2Info.getUptimeMillis());
        assertEquals("count", 1, uid2Info.getCompletedCount());
    }
}
