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

import static android.net.NetworkStatsHistory.FIELD_ALL;
import static android.net.NetworkStatsHistory.FIELD_OPERATIONS;
import static android.net.NetworkStatsHistory.FIELD_RX_BYTES;
import static android.net.NetworkStatsHistory.FIELD_RX_PACKETS;
import static android.net.NetworkStatsHistory.FIELD_TX_BYTES;
import static android.net.NetworkStatsHistory.DataStreamUtils.readVarLong;
import static android.net.NetworkStatsHistory.DataStreamUtils.writeVarLong;
import static android.net.NetworkStatsHistory.Entry.UNKNOWN;
import static android.text.format.DateUtils.DAY_IN_MILLIS;
import static android.text.format.DateUtils.HOUR_IN_MILLIS;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;
import static android.text.format.DateUtils.SECOND_IN_MILLIS;
import static android.text.format.DateUtils.WEEK_IN_MILLIS;
import static android.text.format.DateUtils.YEAR_IN_MILLIS;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.test.suitebuilder.annotation.Suppress;
import android.util.Log;

import com.android.frameworks.coretests.R;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Random;

@SmallTest
public class NetworkStatsHistoryTest extends AndroidTestCase {
    private static final String TAG = "NetworkStatsHistoryTest";

    private static final long TEST_START = 1194220800000L;

    private static final long KB_IN_BYTES = 1024;
    private static final long MB_IN_BYTES = KB_IN_BYTES * 1024;
    private static final long GB_IN_BYTES = MB_IN_BYTES * 1024;

    private NetworkStatsHistory stats;

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        if (stats != null) {
            assertConsistent(stats);
        }
    }

    public void testReadOriginalVersion() throws Exception {
        final DataInputStream in = new DataInputStream(
                getContext().getResources().openRawResource(R.raw.history_v1));

        NetworkStatsHistory.Entry entry = null;
        try {
            final NetworkStatsHistory history = new NetworkStatsHistory(in);
            assertEquals(15 * SECOND_IN_MILLIS, history.getBucketDuration());

            entry = history.getValues(0, entry);
            assertEquals(29143L, entry.rxBytes);
            assertEquals(6223L, entry.txBytes);

            entry = history.getValues(history.size() - 1, entry);
            assertEquals(1476L, entry.rxBytes);
            assertEquals(838L, entry.txBytes);

            entry = history.getValues(Long.MIN_VALUE, Long.MAX_VALUE, entry);
            assertEquals(332401L, entry.rxBytes);
            assertEquals(64314L, entry.txBytes);

        } finally {
            in.close();
        }
    }

    public void testRecordSingleBucket() throws Exception {
        final long BUCKET_SIZE = HOUR_IN_MILLIS;
        stats = new NetworkStatsHistory(BUCKET_SIZE);

        // record data into narrow window to get single bucket
        stats.recordData(TEST_START, TEST_START + SECOND_IN_MILLIS,
                new NetworkStats.Entry(1024L, 10L, 2048L, 20L, 2L));

        assertEquals(1, stats.size());
        assertValues(stats, 0, SECOND_IN_MILLIS, 1024L, 10L, 2048L, 20L, 2L);
    }

    public void testRecordEqualBuckets() throws Exception {
        final long bucketDuration = HOUR_IN_MILLIS;
        stats = new NetworkStatsHistory(bucketDuration);

        // split equally across two buckets
        final long recordStart = TEST_START + (bucketDuration / 2);
        stats.recordData(recordStart, recordStart + bucketDuration,
                new NetworkStats.Entry(1024L, 10L, 128L, 2L, 2L));

        assertEquals(2, stats.size());
        assertValues(stats, 0, HOUR_IN_MILLIS / 2, 512L, 5L, 64L, 1L, 1L);
        assertValues(stats, 1, HOUR_IN_MILLIS / 2, 512L, 5L, 64L, 1L, 1L);
    }

    public void testRecordTouchingBuckets() throws Exception {
        final long BUCKET_SIZE = 15 * MINUTE_IN_MILLIS;
        stats = new NetworkStatsHistory(BUCKET_SIZE);

        // split almost completely into middle bucket, but with a few minutes
        // overlap into neighboring buckets. total record is 20 minutes.
        final long recordStart = (TEST_START + BUCKET_SIZE) - MINUTE_IN_MILLIS;
        final long recordEnd = (TEST_START + (BUCKET_SIZE * 2)) + (MINUTE_IN_MILLIS * 4);
        stats.recordData(recordStart, recordEnd,
                new NetworkStats.Entry(1000L, 2000L, 5000L, 10000L, 100L));

        assertEquals(3, stats.size());
        // first bucket should have (1/20 of value)
        assertValues(stats, 0, MINUTE_IN_MILLIS, 50L, 100L, 250L, 500L, 5L);
        // second bucket should have (15/20 of value)
        assertValues(stats, 1, 15 * MINUTE_IN_MILLIS, 750L, 1500L, 3750L, 7500L, 75L);
        // final bucket should have (4/20 of value)
        assertValues(stats, 2, 4 * MINUTE_IN_MILLIS, 200L, 400L, 1000L, 2000L, 20L);
    }

    public void testRecordGapBuckets() throws Exception {
        final long BUCKET_SIZE = HOUR_IN_MILLIS;
        stats = new NetworkStatsHistory(BUCKET_SIZE);

        // record some data today and next week with large gap
        final long firstStart = TEST_START;
        final long lastStart = TEST_START + WEEK_IN_MILLIS;
        stats.recordData(firstStart, firstStart + SECOND_IN_MILLIS,
                new NetworkStats.Entry(128L, 2L, 256L, 4L, 1L));
        stats.recordData(lastStart, lastStart + SECOND_IN_MILLIS,
                new NetworkStats.Entry(64L, 1L, 512L, 8L, 2L));

        // we should have two buckets, far apart from each other
        assertEquals(2, stats.size());
        assertValues(stats, 0, SECOND_IN_MILLIS, 128L, 2L, 256L, 4L, 1L);
        assertValues(stats, 1, SECOND_IN_MILLIS, 64L, 1L, 512L, 8L, 2L);

        // now record something in middle, spread across two buckets
        final long middleStart = TEST_START + DAY_IN_MILLIS;
        final long middleEnd = middleStart + (HOUR_IN_MILLIS * 2);
        stats.recordData(middleStart, middleEnd,
                new NetworkStats.Entry(2048L, 4L, 2048L, 4L, 2L));

        // now should have four buckets, with new record in middle two buckets
        assertEquals(4, stats.size());
        assertValues(stats, 0, SECOND_IN_MILLIS, 128L, 2L, 256L, 4L, 1L);
        assertValues(stats, 1, HOUR_IN_MILLIS, 1024L, 2L, 1024L, 2L, 1L);
        assertValues(stats, 2, HOUR_IN_MILLIS, 1024L, 2L, 1024L, 2L, 1L);
        assertValues(stats, 3, SECOND_IN_MILLIS, 64L, 1L, 512L, 8L, 2L);
    }

    public void testRecordOverlapBuckets() throws Exception {
        final long BUCKET_SIZE = HOUR_IN_MILLIS;
        stats = new NetworkStatsHistory(BUCKET_SIZE);

        // record some data in one bucket, and another overlapping buckets
        stats.recordData(TEST_START, TEST_START + SECOND_IN_MILLIS,
                new NetworkStats.Entry(256L, 2L, 256L, 2L, 1L));
        final long midStart = TEST_START + (HOUR_IN_MILLIS / 2);
        stats.recordData(midStart, midStart + HOUR_IN_MILLIS,
                new NetworkStats.Entry(1024L, 10L, 1024L, 10L, 10L));

        // should have two buckets, with some data mixed together
        assertEquals(2, stats.size());
        assertValues(stats, 0, SECOND_IN_MILLIS + (HOUR_IN_MILLIS / 2), 768L, 7L, 768L, 7L, 6L);
        assertValues(stats, 1, (HOUR_IN_MILLIS / 2), 512L, 5L, 512L, 5L, 5L);
    }

    public void testRecordEntireGapIdentical() throws Exception {
        // first, create two separate histories far apart
        final NetworkStatsHistory stats1 = new NetworkStatsHistory(HOUR_IN_MILLIS);
        stats1.recordData(TEST_START, TEST_START + 2 * HOUR_IN_MILLIS, 2000L, 1000L);

        final long TEST_START_2 = TEST_START + DAY_IN_MILLIS;
        final NetworkStatsHistory stats2 = new NetworkStatsHistory(HOUR_IN_MILLIS);
        stats2.recordData(TEST_START_2, TEST_START_2 + 2 * HOUR_IN_MILLIS, 1000L, 500L);

        // combine together with identical bucket size
        stats = new NetworkStatsHistory(HOUR_IN_MILLIS);
        stats.recordEntireHistory(stats1);
        stats.recordEntireHistory(stats2);

        // first verify that totals match up
        assertValues(stats, TEST_START - WEEK_IN_MILLIS, TEST_START + WEEK_IN_MILLIS, 3000L, 1500L);

        // now inspect internal buckets
        assertValues(stats, 0, 1000L, 500L);
        assertValues(stats, 1, 1000L, 500L);
        assertValues(stats, 2, 500L, 250L);
        assertValues(stats, 3, 500L, 250L);
    }

    public void testRecordEntireOverlapVaryingBuckets() throws Exception {
        // create history just over hour bucket boundary
        final NetworkStatsHistory stats1 = new NetworkStatsHistory(HOUR_IN_MILLIS);
        stats1.recordData(TEST_START, TEST_START + MINUTE_IN_MILLIS * 60, 600L, 600L);

        final long TEST_START_2 = TEST_START + MINUTE_IN_MILLIS;
        final NetworkStatsHistory stats2 = new NetworkStatsHistory(MINUTE_IN_MILLIS);
        stats2.recordData(TEST_START_2, TEST_START_2 + MINUTE_IN_MILLIS * 5, 50L, 50L);

        // combine together with minute bucket size
        stats = new NetworkStatsHistory(MINUTE_IN_MILLIS);
        stats.recordEntireHistory(stats1);
        stats.recordEntireHistory(stats2);

        // first verify that totals match up
        assertValues(stats, TEST_START - WEEK_IN_MILLIS, TEST_START + WEEK_IN_MILLIS, 650L, 650L);

        // now inspect internal buckets
        assertValues(stats, 0, 10L, 10L);
        assertValues(stats, 1, 20L, 20L);
        assertValues(stats, 2, 20L, 20L);
        assertValues(stats, 3, 20L, 20L);
        assertValues(stats, 4, 20L, 20L);
        assertValues(stats, 5, 20L, 20L);
        assertValues(stats, 6, 10L, 10L);

        // now combine using 15min buckets
        stats = new NetworkStatsHistory(HOUR_IN_MILLIS / 4);
        stats.recordEntireHistory(stats1);
        stats.recordEntireHistory(stats2);

        // first verify that totals match up
        assertValues(stats, TEST_START - WEEK_IN_MILLIS, TEST_START + WEEK_IN_MILLIS, 650L, 650L);

        // and inspect buckets
        assertValues(stats, 0, 200L, 200L);
        assertValues(stats, 1, 150L, 150L);
        assertValues(stats, 2, 150L, 150L);
        assertValues(stats, 3, 150L, 150L);
    }

    public void testRemove() throws Exception {
        stats = new NetworkStatsHistory(HOUR_IN_MILLIS);

        // record some data across 24 buckets
        stats.recordData(TEST_START, TEST_START + DAY_IN_MILLIS, 24L, 24L);
        assertEquals(24, stats.size());

        // try removing far before buckets; should be no change
        stats.removeBucketsBefore(TEST_START - YEAR_IN_MILLIS);
        assertEquals(24, stats.size());

        // try removing just moments into first bucket; should be no change
        // since that bucket contains data beyond the cutoff
        stats.removeBucketsBefore(TEST_START + SECOND_IN_MILLIS);
        assertEquals(24, stats.size());

        // try removing single bucket
        stats.removeBucketsBefore(TEST_START + HOUR_IN_MILLIS);
        assertEquals(23, stats.size());

        // try removing multiple buckets
        stats.removeBucketsBefore(TEST_START + (4 * HOUR_IN_MILLIS));
        assertEquals(20, stats.size());

        // try removing all buckets
        stats.removeBucketsBefore(TEST_START + YEAR_IN_MILLIS);
        assertEquals(0, stats.size());
    }

    public void testTotalData() throws Exception {
        final long BUCKET_SIZE = HOUR_IN_MILLIS;
        stats = new NetworkStatsHistory(BUCKET_SIZE);

        // record uniform data across day
        stats.recordData(TEST_START, TEST_START + DAY_IN_MILLIS, 2400L, 4800L);

        // verify that total outside range is 0
        assertValues(stats, TEST_START - WEEK_IN_MILLIS, TEST_START - DAY_IN_MILLIS, 0L, 0L);

        // verify total in first hour
        assertValues(stats, TEST_START, TEST_START + HOUR_IN_MILLIS, 100L, 200L);

        // verify total across 1.5 hours
        assertValues(stats, TEST_START, TEST_START + (long) (1.5 * HOUR_IN_MILLIS), 150L, 300L);

        // verify total beyond end
        assertValues(stats, TEST_START + (23 * HOUR_IN_MILLIS), TEST_START + WEEK_IN_MILLIS, 100L, 200L);

        // verify everything total
        assertValues(stats, TEST_START - WEEK_IN_MILLIS, TEST_START + WEEK_IN_MILLIS, 2400L, 4800L);

    }

    @Suppress
    public void testFuzzing() throws Exception {
        try {
            // fuzzing with random events, looking for crashes
            final NetworkStats.Entry entry = new NetworkStats.Entry();
            final Random r = new Random();
            for (int i = 0; i < 500; i++) {
                stats = new NetworkStatsHistory(r.nextLong());
                for (int j = 0; j < 10000; j++) {
                    if (r.nextBoolean()) {
                        // add range
                        final long start = r.nextLong();
                        final long end = start + r.nextInt();
                        entry.rxBytes = nextPositiveLong(r);
                        entry.rxPackets = nextPositiveLong(r);
                        entry.txBytes = nextPositiveLong(r);
                        entry.txPackets = nextPositiveLong(r);
                        entry.operations = nextPositiveLong(r);
                        stats.recordData(start, end, entry);
                    } else {
                        // trim something
                        stats.removeBucketsBefore(r.nextLong());
                    }
                }
                assertConsistent(stats);
            }
        } catch (Throwable e) {
            Log.e(TAG, String.valueOf(stats));
            throw new RuntimeException(e);
        }
    }

    private static long nextPositiveLong(Random r) {
        final long value = r.nextLong();
        return value < 0 ? -value : value;
    }

    public void testIgnoreFields() throws Exception {
        final NetworkStatsHistory history = new NetworkStatsHistory(
                MINUTE_IN_MILLIS, 0, FIELD_RX_BYTES | FIELD_TX_BYTES);

        history.recordData(0, MINUTE_IN_MILLIS,
                new NetworkStats.Entry(1024L, 10L, 2048L, 20L, 4L));
        history.recordData(0, 2 * MINUTE_IN_MILLIS,
                new NetworkStats.Entry(2L, 2L, 2L, 2L, 2L));

        assertFullValues(history, UNKNOWN, 1026L, UNKNOWN, 2050L, UNKNOWN, UNKNOWN);
    }

    public void testIgnoreFieldsRecordIn() throws Exception {
        final NetworkStatsHistory full = new NetworkStatsHistory(MINUTE_IN_MILLIS, 0, FIELD_ALL);
        final NetworkStatsHistory partial = new NetworkStatsHistory(
                MINUTE_IN_MILLIS, 0, FIELD_RX_PACKETS | FIELD_OPERATIONS);

        full.recordData(0, MINUTE_IN_MILLIS,
                new NetworkStats.Entry(1024L, 10L, 2048L, 20L, 4L));
        partial.recordEntireHistory(full);

        assertFullValues(partial, UNKNOWN, UNKNOWN, 10L, UNKNOWN, UNKNOWN, 4L);
    }

    public void testIgnoreFieldsRecordOut() throws Exception {
        final NetworkStatsHistory full = new NetworkStatsHistory(MINUTE_IN_MILLIS, 0, FIELD_ALL);
        final NetworkStatsHistory partial = new NetworkStatsHistory(
                MINUTE_IN_MILLIS, 0, FIELD_RX_PACKETS | FIELD_OPERATIONS);

        partial.recordData(0, MINUTE_IN_MILLIS,
                new NetworkStats.Entry(1024L, 10L, 2048L, 20L, 4L));
        full.recordEntireHistory(partial);

        assertFullValues(full, MINUTE_IN_MILLIS, 0L, 10L, 0L, 0L, 4L);
    }

    public void testSerialize() throws Exception {
        final NetworkStatsHistory before = new NetworkStatsHistory(MINUTE_IN_MILLIS, 40, FIELD_ALL);
        before.recordData(0, 4 * MINUTE_IN_MILLIS,
                new NetworkStats.Entry(1024L, 10L, 2048L, 20L, 4L));
        before.recordData(DAY_IN_MILLIS, DAY_IN_MILLIS + MINUTE_IN_MILLIS,
                new NetworkStats.Entry(10L, 20L, 30L, 40L, 50L));

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        before.writeToStream(new DataOutputStream(out));
        out.close();

        final ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        final NetworkStatsHistory after = new NetworkStatsHistory(new DataInputStream(in));

        // must have identical totals before and after
        assertFullValues(before, 5 * MINUTE_IN_MILLIS, 1034L, 30L, 2078L, 60L, 54L);
        assertFullValues(after, 5 * MINUTE_IN_MILLIS, 1034L, 30L, 2078L, 60L, 54L);
    }

    public void testVarLong() throws Exception {
        assertEquals(0L, performVarLong(0L));
        assertEquals(-1L, performVarLong(-1L));
        assertEquals(1024L, performVarLong(1024L));
        assertEquals(-1024L, performVarLong(-1024L));
        assertEquals(40 * MB_IN_BYTES, performVarLong(40 * MB_IN_BYTES));
        assertEquals(512 * GB_IN_BYTES, performVarLong(512 * GB_IN_BYTES));
        assertEquals(Long.MIN_VALUE, performVarLong(Long.MIN_VALUE));
        assertEquals(Long.MAX_VALUE, performVarLong(Long.MAX_VALUE));
        assertEquals(Long.MIN_VALUE + 40, performVarLong(Long.MIN_VALUE + 40));
        assertEquals(Long.MAX_VALUE - 40, performVarLong(Long.MAX_VALUE - 40));
    }

    private static long performVarLong(long before) throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarLong(new DataOutputStream(out), before);

        final ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        return readVarLong(new DataInputStream(in));
    }

    private static void assertConsistent(NetworkStatsHistory stats) {
        // verify timestamps are monotonic
        long lastStart = Long.MIN_VALUE;
        NetworkStatsHistory.Entry entry = null;
        for (int i = 0; i < stats.size(); i++) {
            entry = stats.getValues(i, entry);
            assertTrue(lastStart < entry.bucketStart);
            lastStart = entry.bucketStart;
        }
    }

    private static void assertValues(
            NetworkStatsHistory stats, int index, long rxBytes, long txBytes) {
        final NetworkStatsHistory.Entry entry = stats.getValues(index, null);
        assertEquals("unexpected rxBytes", rxBytes, entry.rxBytes);
        assertEquals("unexpected txBytes", txBytes, entry.txBytes);
    }

    private static void assertValues(
            NetworkStatsHistory stats, long start, long end, long rxBytes, long txBytes) {
        final NetworkStatsHistory.Entry entry = stats.getValues(start, end, null);
        assertEquals("unexpected rxBytes", rxBytes, entry.rxBytes);
        assertEquals("unexpected txBytes", txBytes, entry.txBytes);
    }

    private static void assertValues(NetworkStatsHistory stats, int index, long activeTime,
            long rxBytes, long rxPackets, long txBytes, long txPackets, long operations) {
        final NetworkStatsHistory.Entry entry = stats.getValues(index, null);
        assertEquals("unexpected activeTime", activeTime, entry.activeTime);
        assertEquals("unexpected rxBytes", rxBytes, entry.rxBytes);
        assertEquals("unexpected rxPackets", rxPackets, entry.rxPackets);
        assertEquals("unexpected txBytes", txBytes, entry.txBytes);
        assertEquals("unexpected txPackets", txPackets, entry.txPackets);
        assertEquals("unexpected operations", operations, entry.operations);
    }

    private static void assertFullValues(NetworkStatsHistory stats, long activeTime, long rxBytes,
            long rxPackets, long txBytes, long txPackets, long operations) {
        assertValues(stats, Long.MIN_VALUE, Long.MAX_VALUE, activeTime, rxBytes, rxPackets, txBytes,
                txPackets, operations);
    }

    private static void assertValues(NetworkStatsHistory stats, long start, long end,
            long activeTime, long rxBytes, long rxPackets, long txBytes, long txPackets,
            long operations) {
        final NetworkStatsHistory.Entry entry = stats.getValues(start, end, null);
        assertEquals("unexpected activeTime", activeTime, entry.activeTime);
        assertEquals("unexpected rxBytes", rxBytes, entry.rxBytes);
        assertEquals("unexpected rxPackets", rxPackets, entry.rxPackets);
        assertEquals("unexpected txBytes", txBytes, entry.txBytes);
        assertEquals("unexpected txPackets", txPackets, entry.txPackets);
        assertEquals("unexpected operations", operations, entry.operations);
    }
}
