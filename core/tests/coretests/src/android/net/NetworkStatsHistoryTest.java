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

import java.io.DataInputStream;
import java.util.Random;

@SmallTest
public class NetworkStatsHistoryTest extends AndroidTestCase {
    private static final String TAG = "NetworkStatsHistoryTest";

    private static final long TEST_START = 1194220800000L;

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
        stats.recordData(TEST_START, TEST_START + SECOND_IN_MILLIS, 1024L, 2048L);

        assertEquals(1, stats.size());
        assertValues(stats, 0, 1024L, 2048L);
    }

    public void testRecordEqualBuckets() throws Exception {
        final long bucketDuration = HOUR_IN_MILLIS;
        stats = new NetworkStatsHistory(bucketDuration);

        // split equally across two buckets
        final long recordStart = TEST_START + (bucketDuration / 2);
        stats.recordData(recordStart, recordStart + bucketDuration, 1024L, 128L);

        assertEquals(2, stats.size());
        assertValues(stats, 0, 512L, 64L);
        assertValues(stats, 1, 512L, 64L);
    }

    public void testRecordTouchingBuckets() throws Exception {
        final long BUCKET_SIZE = 15 * MINUTE_IN_MILLIS;
        stats = new NetworkStatsHistory(BUCKET_SIZE);

        // split almost completely into middle bucket, but with a few minutes
        // overlap into neighboring buckets. total record is 20 minutes.
        final long recordStart = (TEST_START + BUCKET_SIZE) - MINUTE_IN_MILLIS;
        final long recordEnd = (TEST_START + (BUCKET_SIZE * 2)) + (MINUTE_IN_MILLIS * 4);
        stats.recordData(recordStart, recordEnd, 1000L, 5000L);

        assertEquals(3, stats.size());
        // first bucket should have (1/20 of value)
        assertValues(stats, 0, 50L, 250L);
        // second bucket should have (15/20 of value)
        assertValues(stats, 1, 750L, 3750L);
        // final bucket should have (4/20 of value)
        assertValues(stats, 2, 200L, 1000L);
    }

    public void testRecordGapBuckets() throws Exception {
        final long BUCKET_SIZE = HOUR_IN_MILLIS;
        stats = new NetworkStatsHistory(BUCKET_SIZE);

        // record some data today and next week with large gap
        final long firstStart = TEST_START;
        final long lastStart = TEST_START + WEEK_IN_MILLIS;
        stats.recordData(firstStart, firstStart + SECOND_IN_MILLIS, 128L, 256L);
        stats.recordData(lastStart, lastStart + SECOND_IN_MILLIS, 64L, 512L);

        // we should have two buckets, far apart from each other
        assertEquals(2, stats.size());
        assertValues(stats, 0, 128L, 256L);
        assertValues(stats, 1, 64L, 512L);

        // now record something in middle, spread across two buckets
        final long middleStart = TEST_START + DAY_IN_MILLIS;
        final long middleEnd = middleStart + (HOUR_IN_MILLIS * 2);
        stats.recordData(middleStart, middleEnd, 2048L, 2048L);

        // now should have four buckets, with new record in middle two buckets
        assertEquals(4, stats.size());
        assertValues(stats, 0, 128L, 256L);
        assertValues(stats, 1, 1024L, 1024L);
        assertValues(stats, 2, 1024L, 1024L);
        assertValues(stats, 3, 64L, 512L);
    }

    public void testRecordOverlapBuckets() throws Exception {
        final long BUCKET_SIZE = HOUR_IN_MILLIS;
        stats = new NetworkStatsHistory(BUCKET_SIZE);

        // record some data in one bucket, and another overlapping buckets
        stats.recordData(TEST_START, TEST_START + SECOND_IN_MILLIS, 256L, 256L);
        final long midStart = TEST_START + (HOUR_IN_MILLIS / 2);
        stats.recordData(midStart, midStart + HOUR_IN_MILLIS, 1024L, 1024L);

        // should have two buckets, with some data mixed together
        assertEquals(2, stats.size());
        assertValues(stats, 0, 768L, 768L);
        assertValues(stats, 1, 512L, 512L);
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
            final Random r = new Random();
            for (int i = 0; i < 500; i++) {
                stats = new NetworkStatsHistory(r.nextLong());
                for (int j = 0; j < 10000; j++) {
                    if (r.nextBoolean()) {
                        // add range
                        final long start = r.nextLong();
                        final long end = start + r.nextInt();
                        stats.recordData(start, end, r.nextLong(), r.nextLong());
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

}
