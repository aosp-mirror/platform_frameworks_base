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

import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.NetworkStatsHistory.UID_ALL;
import static android.text.format.DateUtils.DAY_IN_MILLIS;
import static android.text.format.DateUtils.HOUR_IN_MILLIS;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;
import static android.text.format.DateUtils.SECOND_IN_MILLIS;
import static android.text.format.DateUtils.WEEK_IN_MILLIS;
import static android.text.format.DateUtils.YEAR_IN_MILLIS;

import android.test.suitebuilder.annotation.SmallTest;
import android.test.suitebuilder.annotation.Suppress;
import android.util.Log;

import junit.framework.TestCase;

import java.util.Random;

@SmallTest
public class NetworkStatsHistoryTest extends TestCase {
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

    public void testRecordSingleBucket() throws Exception {
        final long BUCKET_SIZE = HOUR_IN_MILLIS;
        stats = buildStats(BUCKET_SIZE);

        // record data into narrow window to get single bucket
        stats.recordData(TEST_START, TEST_START + SECOND_IN_MILLIS, 1024L, 2048L);

        assertEquals(1, stats.bucketCount);
        assertBucket(stats, 0, 1024L, 2048L);
    }

    public void testRecordEqualBuckets() throws Exception {
        final long bucketDuration = HOUR_IN_MILLIS;
        stats = buildStats(bucketDuration);

        // split equally across two buckets
        final long recordStart = TEST_START + (bucketDuration / 2);
        stats.recordData(recordStart, recordStart + bucketDuration, 1024L, 128L);

        assertEquals(2, stats.bucketCount);
        assertBucket(stats, 0, 512L, 64L);
        assertBucket(stats, 1, 512L, 64L);
    }

    public void testRecordTouchingBuckets() throws Exception {
        final long BUCKET_SIZE = 15 * MINUTE_IN_MILLIS;
        stats = buildStats(BUCKET_SIZE);

        // split almost completely into middle bucket, but with a few minutes
        // overlap into neighboring buckets. total record is 20 minutes.
        final long recordStart = (TEST_START + BUCKET_SIZE) - MINUTE_IN_MILLIS;
        final long recordEnd = (TEST_START + (BUCKET_SIZE * 2)) + (MINUTE_IN_MILLIS * 4);
        stats.recordData(recordStart, recordEnd, 1000L, 5000L);

        assertEquals(3, stats.bucketCount);
        // first bucket should have (1/20 of value)
        assertBucket(stats, 0, 50L, 250L);
        // second bucket should have (15/20 of value)
        assertBucket(stats, 1, 750L, 3750L);
        // final bucket should have (4/20 of value)
        assertBucket(stats, 2, 200L, 1000L);
    }

    public void testRecordGapBuckets() throws Exception {
        final long BUCKET_SIZE = HOUR_IN_MILLIS;
        stats = buildStats(BUCKET_SIZE);

        // record some data today and next week with large gap
        final long firstStart = TEST_START;
        final long lastStart = TEST_START + WEEK_IN_MILLIS;
        stats.recordData(firstStart, firstStart + SECOND_IN_MILLIS, 128L, 256L);
        stats.recordData(lastStart, lastStart + SECOND_IN_MILLIS, 64L, 512L);

        // we should have two buckets, far apart from each other
        assertEquals(2, stats.bucketCount);
        assertBucket(stats, 0, 128L, 256L);
        assertBucket(stats, 1, 64L, 512L);

        // now record something in middle, spread across two buckets
        final long middleStart = TEST_START + DAY_IN_MILLIS;
        final long middleEnd = middleStart + (HOUR_IN_MILLIS * 2);
        stats.recordData(middleStart, middleEnd, 2048L, 2048L);

        // now should have four buckets, with new record in middle two buckets
        assertEquals(4, stats.bucketCount);
        assertBucket(stats, 0, 128L, 256L);
        assertBucket(stats, 1, 1024L, 1024L);
        assertBucket(stats, 2, 1024L, 1024L);
        assertBucket(stats, 3, 64L, 512L);
    }

    public void testRecordOverlapBuckets() throws Exception {
        final long BUCKET_SIZE = HOUR_IN_MILLIS;
        stats = buildStats(BUCKET_SIZE);

        // record some data in one bucket, and another overlapping buckets
        stats.recordData(TEST_START, TEST_START + SECOND_IN_MILLIS, 256L, 256L);
        final long midStart = TEST_START + (HOUR_IN_MILLIS / 2);
        stats.recordData(midStart, midStart + HOUR_IN_MILLIS, 1024L, 1024L);

        // should have two buckets, with some data mixed together
        assertEquals(2, stats.bucketCount);
        assertBucket(stats, 0, 768L, 768L);
        assertBucket(stats, 1, 512L, 512L);
    }

    public void testRemove() throws Exception {
        final long BUCKET_SIZE = HOUR_IN_MILLIS;
        stats = buildStats(BUCKET_SIZE);

        // record some data across 24 buckets
        stats.recordData(TEST_START, TEST_START + DAY_IN_MILLIS, 24L, 24L);
        assertEquals(24, stats.bucketCount);

        // try removing far before buckets; should be no change
        stats.removeBucketsBefore(TEST_START - YEAR_IN_MILLIS);
        assertEquals(24, stats.bucketCount);

        // try removing just moments into first bucket; should be no change
        // since that bucket contains data beyond the cutoff
        stats.removeBucketsBefore(TEST_START + SECOND_IN_MILLIS);
        assertEquals(24, stats.bucketCount);

        // try removing single bucket
        stats.removeBucketsBefore(TEST_START + HOUR_IN_MILLIS);
        assertEquals(23, stats.bucketCount);

        // try removing multiple buckets
        stats.removeBucketsBefore(TEST_START + (4 * HOUR_IN_MILLIS));
        assertEquals(20, stats.bucketCount);

        // try removing all buckets
        stats.removeBucketsBefore(TEST_START + YEAR_IN_MILLIS);
        assertEquals(0, stats.bucketCount);
    }

    @Suppress
    public void testFuzzing() throws Exception {
        try {
            // fuzzing with random events, looking for crashes
            final Random r = new Random();
            for (int i = 0; i < 500; i++) {
                stats = buildStats(r.nextLong());
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

    private static NetworkStatsHistory buildStats(long bucketSize) {
        return new NetworkStatsHistory(TYPE_MOBILE, null, UID_ALL, bucketSize);
    }

    private static void assertConsistent(NetworkStatsHistory stats) {
        // verify timestamps are monotonic
        for (int i = 1; i < stats.bucketCount; i++) {
            assertTrue(stats.bucketStart[i - 1] < stats.bucketStart[i]);
        }
    }

    private static void assertBucket(NetworkStatsHistory stats, int index, long rx, long tx) {
        assertEquals("unexpected rx", rx, stats.rx[index]);
        assertEquals("unexpected tx", tx, stats.tx[index]);
    }

}
