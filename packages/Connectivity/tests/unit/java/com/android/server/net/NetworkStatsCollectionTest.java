/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.net;

import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.NetworkIdentity.OEM_NONE;
import static android.net.NetworkStats.SET_ALL;
import static android.net.NetworkStats.SET_DEFAULT;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.NetworkStats.UID_ALL;
import static android.net.NetworkStatsHistory.FIELD_ALL;
import static android.net.NetworkTemplate.buildTemplateMobileAll;
import static android.os.Process.myUid;
import static android.text.format.DateUtils.HOUR_IN_MILLIS;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;

import static com.android.internal.net.NetworkUtilsInternal.multiplySafeByRational;
import static com.android.testutils.MiscAsserts.assertThrows;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.NetworkIdentity;
import android.net.NetworkStats;
import android.net.NetworkStatsHistory;
import android.net.NetworkTemplate;
import android.os.Process;
import android.os.UserHandle;
import android.telephony.SubscriptionPlan;
import android.telephony.TelephonyManager;
import android.text.format.DateUtils;
import android.util.RecurrenceRule;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.tests.net.R;

import libcore.io.IoUtils;
import libcore.io.Streams;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests for {@link NetworkStatsCollection}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class NetworkStatsCollectionTest {

    private static final String TEST_FILE = "test.bin";
    private static final String TEST_IMSI = "310260000000000";

    private static final long TIME_A = 1326088800000L; // UTC: Monday 9th January 2012 06:00:00 AM
    private static final long TIME_B = 1326110400000L; // UTC: Monday 9th January 2012 12:00:00 PM
    private static final long TIME_C = 1326132000000L; // UTC: Monday 9th January 2012 06:00:00 PM

    private static Clock sOriginalClock;

    @Before
    public void setUp() throws Exception {
        sOriginalClock = RecurrenceRule.sClock;
        // ignore any device overlay while testing
        NetworkTemplate.forceAllNetworkTypes();
    }

    @After
    public void tearDown() throws Exception {
        RecurrenceRule.sClock = sOriginalClock;
        NetworkTemplate.resetForceAllNetworkTypes();
    }

    private void setClock(Instant instant) {
        RecurrenceRule.sClock = Clock.fixed(instant, ZoneId.systemDefault());
    }

    @Test
    public void testReadLegacyNetwork() throws Exception {
        final File testFile =
                new File(InstrumentationRegistry.getContext().getFilesDir(), TEST_FILE);
        stageFile(R.raw.netstats_v1, testFile);

        final NetworkStatsCollection collection = new NetworkStatsCollection(30 * MINUTE_IN_MILLIS);
        collection.readLegacyNetwork(testFile);

        // verify that history read correctly
        assertSummaryTotal(collection, buildTemplateMobileAll(TEST_IMSI),
                636016770L, 709306L, 88038768L, 518836L, NetworkStatsAccess.Level.DEVICE);

        // now export into a unified format
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        collection.write(bos);

        // clear structure completely
        collection.reset();
        assertSummaryTotal(collection, buildTemplateMobileAll(TEST_IMSI),
                0L, 0L, 0L, 0L, NetworkStatsAccess.Level.DEVICE);

        // and read back into structure, verifying that totals are same
        collection.read(new ByteArrayInputStream(bos.toByteArray()));
        assertSummaryTotal(collection, buildTemplateMobileAll(TEST_IMSI),
                636016770L, 709306L, 88038768L, 518836L, NetworkStatsAccess.Level.DEVICE);
    }

    @Test
    public void testReadLegacyUid() throws Exception {
        final File testFile =
                new File(InstrumentationRegistry.getContext().getFilesDir(), TEST_FILE);
        stageFile(R.raw.netstats_uid_v4, testFile);

        final NetworkStatsCollection collection = new NetworkStatsCollection(30 * MINUTE_IN_MILLIS);
        collection.readLegacyUid(testFile, false);

        // verify that history read correctly
        assertSummaryTotal(collection, buildTemplateMobileAll(TEST_IMSI),
                637076152L, 711413L, 88343717L, 521022L, NetworkStatsAccess.Level.DEVICE);

        // now export into a unified format
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        collection.write(bos);

        // clear structure completely
        collection.reset();
        assertSummaryTotal(collection, buildTemplateMobileAll(TEST_IMSI),
                0L, 0L, 0L, 0L, NetworkStatsAccess.Level.DEVICE);

        // and read back into structure, verifying that totals are same
        collection.read(new ByteArrayInputStream(bos.toByteArray()));
        assertSummaryTotal(collection, buildTemplateMobileAll(TEST_IMSI),
                637076152L, 711413L, 88343717L, 521022L, NetworkStatsAccess.Level.DEVICE);
    }

    @Test
    public void testReadLegacyUidTags() throws Exception {
        final File testFile =
                new File(InstrumentationRegistry.getContext().getFilesDir(), TEST_FILE);
        stageFile(R.raw.netstats_uid_v4, testFile);

        final NetworkStatsCollection collection = new NetworkStatsCollection(30 * MINUTE_IN_MILLIS);
        collection.readLegacyUid(testFile, true);

        // verify that history read correctly
        assertSummaryTotalIncludingTags(collection, buildTemplateMobileAll(TEST_IMSI),
                77017831L, 100995L, 35436758L, 92344L);

        // now export into a unified format
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        collection.write(bos);

        // clear structure completely
        collection.reset();
        assertSummaryTotalIncludingTags(collection, buildTemplateMobileAll(TEST_IMSI),
                0L, 0L, 0L, 0L);

        // and read back into structure, verifying that totals are same
        collection.read(new ByteArrayInputStream(bos.toByteArray()));
        assertSummaryTotalIncludingTags(collection, buildTemplateMobileAll(TEST_IMSI),
                77017831L, 100995L, 35436758L, 92344L);
    }

    @Test
    public void testStartEndAtomicBuckets() throws Exception {
        final NetworkStatsCollection collection = new NetworkStatsCollection(HOUR_IN_MILLIS);

        // record empty data straddling between buckets
        final NetworkStats.Entry entry = new NetworkStats.Entry();
        entry.rxBytes = 32;
        collection.recordData(null, UID_ALL, SET_DEFAULT, TAG_NONE, 30 * MINUTE_IN_MILLIS,
                90 * MINUTE_IN_MILLIS, entry);

        // assert that we report boundary in atomic buckets
        assertEquals(0, collection.getStartMillis());
        assertEquals(2 * HOUR_IN_MILLIS, collection.getEndMillis());
    }

    @Test
    public void testAccessLevels() throws Exception {
        final NetworkStatsCollection collection = new NetworkStatsCollection(HOUR_IN_MILLIS);
        final NetworkStats.Entry entry = new NetworkStats.Entry();
        final NetworkIdentitySet identSet = new NetworkIdentitySet();
        identSet.add(new NetworkIdentity(TYPE_MOBILE, TelephonyManager.NETWORK_TYPE_UNKNOWN,
                TEST_IMSI, null, false, true, true, OEM_NONE));

        int myUid = Process.myUid();
        int otherUidInSameUser = Process.myUid() + 1;
        int uidInDifferentUser = Process.myUid() + UserHandle.PER_USER_RANGE;

        // Record one entry for the current UID.
        entry.rxBytes = 32;
        collection.recordData(identSet, myUid, SET_DEFAULT, TAG_NONE, 0, 60 * MINUTE_IN_MILLIS,
                entry);

        // Record one entry for another UID in this user.
        entry.rxBytes = 64;
        collection.recordData(identSet, otherUidInSameUser, SET_DEFAULT, TAG_NONE, 0,
                60 * MINUTE_IN_MILLIS, entry);

        // Record one entry for the system UID.
        entry.rxBytes = 128;
        collection.recordData(identSet, Process.SYSTEM_UID, SET_DEFAULT, TAG_NONE, 0,
                60 * MINUTE_IN_MILLIS, entry);

        // Record one entry for a UID in a different user.
        entry.rxBytes = 256;
        collection.recordData(identSet, uidInDifferentUser, SET_DEFAULT, TAG_NONE, 0,
                60 * MINUTE_IN_MILLIS, entry);

        // Verify the set of relevant UIDs for each access level.
        assertArrayEquals(new int[] { myUid },
                collection.getRelevantUids(NetworkStatsAccess.Level.DEFAULT));
        assertArrayEquals(new int[] { Process.SYSTEM_UID, myUid, otherUidInSameUser },
                collection.getRelevantUids(NetworkStatsAccess.Level.USER));
        assertArrayEquals(
                new int[] { Process.SYSTEM_UID, myUid, otherUidInSameUser, uidInDifferentUser },
                collection.getRelevantUids(NetworkStatsAccess.Level.DEVICE));

        // Verify security check in getHistory.
        assertNotNull(collection.getHistory(buildTemplateMobileAll(TEST_IMSI), null, myUid, SET_DEFAULT,
                TAG_NONE, 0, 0L, 0L, NetworkStatsAccess.Level.DEFAULT, myUid));
        try {
            collection.getHistory(buildTemplateMobileAll(TEST_IMSI), null, otherUidInSameUser,
                    SET_DEFAULT, TAG_NONE, 0, 0L, 0L, NetworkStatsAccess.Level.DEFAULT, myUid);
            fail("Should have thrown SecurityException for accessing different UID");
        } catch (SecurityException e) {
            // expected
        }

        // Verify appropriate aggregation in getSummary.
        assertSummaryTotal(collection, buildTemplateMobileAll(TEST_IMSI), 32, 0, 0, 0,
                NetworkStatsAccess.Level.DEFAULT);
        assertSummaryTotal(collection, buildTemplateMobileAll(TEST_IMSI), 32 + 64 + 128, 0, 0, 0,
                NetworkStatsAccess.Level.USER);
        assertSummaryTotal(collection, buildTemplateMobileAll(TEST_IMSI), 32 + 64 + 128 + 256, 0, 0,
                0, NetworkStatsAccess.Level.DEVICE);
    }

    @Test
    public void testAugmentPlan() throws Exception {
        final File testFile =
                new File(InstrumentationRegistry.getContext().getFilesDir(), TEST_FILE);
        stageFile(R.raw.netstats_v1, testFile);

        final NetworkStatsCollection emptyCollection = new NetworkStatsCollection(30 * MINUTE_IN_MILLIS);
        final NetworkStatsCollection collection = new NetworkStatsCollection(30 * MINUTE_IN_MILLIS);
        collection.readLegacyNetwork(testFile);

        // We're in the future, but not that far off
        setClock(Instant.parse("2012-06-01T00:00:00.00Z"));

        // Test a bunch of plans that should result in no augmentation
        final List<SubscriptionPlan> plans = new ArrayList<>();

        // No plan
        plans.add(null);
        // No usage anchor
        plans.add(SubscriptionPlan.Builder
                .createRecurringMonthly(ZonedDateTime.parse("2011-01-14T00:00:00.00Z")).build());
        // Usage anchor far in past
        plans.add(SubscriptionPlan.Builder
                .createRecurringMonthly(ZonedDateTime.parse("2011-01-14T00:00:00.00Z"))
                .setDataUsage(1000L, TIME_A - DateUtils.YEAR_IN_MILLIS).build());
        // Usage anchor far in future
        plans.add(SubscriptionPlan.Builder
                .createRecurringMonthly(ZonedDateTime.parse("2011-01-14T00:00:00.00Z"))
                .setDataUsage(1000L, TIME_A + DateUtils.YEAR_IN_MILLIS).build());
        // Usage anchor near but outside cycle
        plans.add(SubscriptionPlan.Builder
                .createNonrecurring(ZonedDateTime.parse("2012-01-09T09:00:00.00Z"),
                        ZonedDateTime.parse("2012-01-09T15:00:00.00Z"))
                .setDataUsage(1000L, TIME_C).build());

        for (SubscriptionPlan plan : plans) {
            int i;
            NetworkStatsHistory history;

            // Empty collection should be untouched
            history = getHistory(emptyCollection, plan, TIME_A, TIME_C);
            assertEquals(0L, history.getTotalBytes());

            // Normal collection should be untouched
            history = getHistory(collection, plan, TIME_A, TIME_C); i = 0;
            assertEntry(100647, 197, 23649, 185, history.getValues(i++, null));
            assertEntry(100647, 196, 23648, 185, history.getValues(i++, null));
            assertEntry(18323, 76, 15032, 76, history.getValues(i++, null));
            assertEntry(18322, 75, 15031, 75, history.getValues(i++, null));
            assertEntry(527798, 761, 78570, 652, history.getValues(i++, null));
            assertEntry(527797, 760, 78570, 651, history.getValues(i++, null));
            assertEntry(10747, 50, 16839, 55, history.getValues(i++, null));
            assertEntry(10747, 49, 16837, 54, history.getValues(i++, null));
            assertEntry(89191, 151, 18021, 140, history.getValues(i++, null));
            assertEntry(89190, 150, 18020, 139, history.getValues(i++, null));
            assertEntry(3821, 23, 4525, 26, history.getValues(i++, null));
            assertEntry(3820, 21, 4524, 26, history.getValues(i++, null));
            assertEntry(91686, 159, 18576, 146, history.getValues(i++, null));
            assertEntry(91685, 159, 18574, 146, history.getValues(i++, null));
            assertEntry(8289, 36, 6864, 39, history.getValues(i++, null));
            assertEntry(8289, 34, 6862, 37, history.getValues(i++, null));
            assertEntry(113914, 174, 18364, 157, history.getValues(i++, null));
            assertEntry(113913, 173, 18364, 157, history.getValues(i++, null));
            assertEntry(11378, 49, 9261, 50, history.getValues(i++, null));
            assertEntry(11377, 48, 9261, 48, history.getValues(i++, null));
            assertEntry(201766, 328, 41808, 291, history.getValues(i++, null));
            assertEntry(201764, 328, 41807, 290, history.getValues(i++, null));
            assertEntry(106106, 219, 39918, 202, history.getValues(i++, null));
            assertEntry(106105, 216, 39916, 200, history.getValues(i++, null));
            assertEquals(history.size(), i);

            // Slice from middle should be untouched
            history = getHistory(collection, plan, TIME_B - HOUR_IN_MILLIS,
                    TIME_B + HOUR_IN_MILLIS); i = 0;
            assertEntry(3821, 23, 4525, 26, history.getValues(i++, null));
            assertEntry(3820, 21, 4524, 26, history.getValues(i++, null));
            assertEntry(91686, 159, 18576, 146, history.getValues(i++, null));
            assertEntry(91685, 159, 18574, 146, history.getValues(i++, null));
            assertEquals(history.size(), i);
        }

        // Lower anchor in the middle of plan
        {
            int i;
            NetworkStatsHistory history;

            final SubscriptionPlan plan = SubscriptionPlan.Builder
                    .createNonrecurring(ZonedDateTime.parse("2012-01-09T09:00:00.00Z"),
                            ZonedDateTime.parse("2012-01-09T15:00:00.00Z"))
                    .setDataUsage(200000L, TIME_B).build();

            // Empty collection should be augmented
            history = getHistory(emptyCollection, plan, TIME_A, TIME_C);
            assertEquals(200000L, history.getTotalBytes());

            // Normal collection should be augmented
            history = getHistory(collection, plan, TIME_A, TIME_C); i = 0;
            assertEntry(100647, 197, 23649, 185, history.getValues(i++, null));
            assertEntry(100647, 196, 23648, 185, history.getValues(i++, null));
            assertEntry(18323, 76, 15032, 76, history.getValues(i++, null));
            assertEntry(18322, 75, 15031, 75, history.getValues(i++, null));
            assertEntry(527798, 761, 78570, 652, history.getValues(i++, null));
            assertEntry(527797, 760, 78570, 651, history.getValues(i++, null));
            // Cycle point; start data normalization
            assertEntry(7507, 0, 11763, 0, history.getValues(i++, null));
            assertEntry(7507, 0, 11762, 0, history.getValues(i++, null));
            assertEntry(62309, 0, 12589, 0, history.getValues(i++, null));
            assertEntry(62309, 0, 12588, 0, history.getValues(i++, null));
            assertEntry(2669, 0, 3161, 0, history.getValues(i++, null));
            assertEntry(2668, 0, 3160, 0, history.getValues(i++, null));
            // Anchor point; end data normalization
            assertEntry(91686, 159, 18576, 146, history.getValues(i++, null));
            assertEntry(91685, 159, 18574, 146, history.getValues(i++, null));
            assertEntry(8289, 36, 6864, 39, history.getValues(i++, null));
            assertEntry(8289, 34, 6862, 37, history.getValues(i++, null));
            assertEntry(113914, 174, 18364, 157, history.getValues(i++, null));
            assertEntry(113913, 173, 18364, 157, history.getValues(i++, null));
            // Cycle point
            assertEntry(11378, 49, 9261, 50, history.getValues(i++, null));
            assertEntry(11377, 48, 9261, 48, history.getValues(i++, null));
            assertEntry(201766, 328, 41808, 291, history.getValues(i++, null));
            assertEntry(201764, 328, 41807, 290, history.getValues(i++, null));
            assertEntry(106106, 219, 39918, 202, history.getValues(i++, null));
            assertEntry(106105, 216, 39916, 200, history.getValues(i++, null));
            assertEquals(history.size(), i);

            // Slice from middle should be augmented
            history = getHistory(collection, plan, TIME_B - HOUR_IN_MILLIS,
                    TIME_B + HOUR_IN_MILLIS); i = 0;
            assertEntry(2669, 0, 3161, 0, history.getValues(i++, null));
            assertEntry(2668, 0, 3160, 0, history.getValues(i++, null));
            assertEntry(91686, 159, 18576, 146, history.getValues(i++, null));
            assertEntry(91685, 159, 18574, 146, history.getValues(i++, null));
            assertEquals(history.size(), i);
        }

        // Higher anchor in the middle of plan
        {
            int i;
            NetworkStatsHistory history;

            final SubscriptionPlan plan = SubscriptionPlan.Builder
                    .createNonrecurring(ZonedDateTime.parse("2012-01-09T09:00:00.00Z"),
                            ZonedDateTime.parse("2012-01-09T15:00:00.00Z"))
                    .setDataUsage(400000L, TIME_B + MINUTE_IN_MILLIS).build();

            // Empty collection should be augmented
            history = getHistory(emptyCollection, plan, TIME_A, TIME_C);
            assertEquals(400000L, history.getTotalBytes());

            // Normal collection should be augmented
            history = getHistory(collection, plan, TIME_A, TIME_C); i = 0;
            assertEntry(100647, 197, 23649, 185, history.getValues(i++, null));
            assertEntry(100647, 196, 23648, 185, history.getValues(i++, null));
            assertEntry(18323, 76, 15032, 76, history.getValues(i++, null));
            assertEntry(18322, 75, 15031, 75, history.getValues(i++, null));
            assertEntry(527798, 761, 78570, 652, history.getValues(i++, null));
            assertEntry(527797, 760, 78570, 651, history.getValues(i++, null));
            // Cycle point; start data normalization
            assertEntry(15015, 0, 23527, 0, history.getValues(i++, null));
            assertEntry(15015, 0, 23524, 0, history.getValues(i++, null));
            assertEntry(124619, 0, 25179, 0, history.getValues(i++, null));
            assertEntry(124618, 0, 25177, 0, history.getValues(i++, null));
            assertEntry(5338, 0, 6322, 0, history.getValues(i++, null));
            assertEntry(5337, 0, 6320, 0, history.getValues(i++, null));
            // Anchor point; end data normalization
            assertEntry(91686, 159, 18576, 146, history.getValues(i++, null));
            assertEntry(91685, 159, 18574, 146, history.getValues(i++, null));
            assertEntry(8289, 36, 6864, 39, history.getValues(i++, null));
            assertEntry(8289, 34, 6862, 37, history.getValues(i++, null));
            assertEntry(113914, 174, 18364, 157, history.getValues(i++, null));
            assertEntry(113913, 173, 18364, 157, history.getValues(i++, null));
            // Cycle point
            assertEntry(11378, 49, 9261, 50, history.getValues(i++, null));
            assertEntry(11377, 48, 9261, 48, history.getValues(i++, null));
            assertEntry(201766, 328, 41808, 291, history.getValues(i++, null));
            assertEntry(201764, 328, 41807, 290, history.getValues(i++, null));
            assertEntry(106106, 219, 39918, 202, history.getValues(i++, null));
            assertEntry(106105, 216, 39916, 200, history.getValues(i++, null));

            // Slice from middle should be augmented
            history = getHistory(collection, plan, TIME_B - HOUR_IN_MILLIS,
                    TIME_B + HOUR_IN_MILLIS); i = 0;
            assertEntry(5338, 0, 6322, 0, history.getValues(i++, null));
            assertEntry(5337, 0, 6320, 0, history.getValues(i++, null));
            assertEntry(91686, 159, 18576, 146, history.getValues(i++, null));
            assertEntry(91685, 159, 18574, 146, history.getValues(i++, null));
            assertEquals(history.size(), i);
        }
    }

    @Test
    public void testAugmentPlanGigantic() throws Exception {
        // We're in the future, but not that far off
        setClock(Instant.parse("2012-06-01T00:00:00.00Z"));

        // Create a simple history with a ton of measured usage
        final NetworkStatsCollection large = new NetworkStatsCollection(HOUR_IN_MILLIS);
        final NetworkIdentitySet ident = new NetworkIdentitySet();
        ident.add(new NetworkIdentity(ConnectivityManager.TYPE_MOBILE, -1, TEST_IMSI, null,
                false, true, true, OEM_NONE));
        large.recordData(ident, UID_ALL, SET_ALL, TAG_NONE, TIME_A, TIME_B,
                new NetworkStats.Entry(12_730_893_164L, 1, 0, 0, 0));

        // Verify untouched total
        assertEquals(12_730_893_164L, getHistory(large, null, TIME_A, TIME_C).getTotalBytes());

        // Verify anchor that might cause overflows
        final SubscriptionPlan plan = SubscriptionPlan.Builder
                .createRecurringMonthly(ZonedDateTime.parse("2012-01-09T00:00:00.00Z"))
                .setDataUsage(4_939_212_390L, TIME_B).build();
        assertEquals(4_939_212_386L, getHistory(large, plan, TIME_A, TIME_C).getTotalBytes());
    }

    @Test
    public void testRounding() throws Exception {
        final NetworkStatsCollection coll = new NetworkStatsCollection(HOUR_IN_MILLIS);

        // Special values should remain unchanged
        for (long time : new long[] {
                Long.MIN_VALUE, Long.MAX_VALUE, SubscriptionPlan.TIME_UNKNOWN
        }) {
            assertEquals(time, coll.roundUp(time));
            assertEquals(time, coll.roundDown(time));
        }

        assertEquals(TIME_A, coll.roundUp(TIME_A));
        assertEquals(TIME_A, coll.roundDown(TIME_A));

        assertEquals(TIME_A + HOUR_IN_MILLIS, coll.roundUp(TIME_A + 1));
        assertEquals(TIME_A, coll.roundDown(TIME_A + 1));

        assertEquals(TIME_A, coll.roundUp(TIME_A - 1));
        assertEquals(TIME_A - HOUR_IN_MILLIS, coll.roundDown(TIME_A - 1));
    }

    @Test
    public void testMultiplySafeRational() {
        assertEquals(25, multiplySafeByRational(50, 1, 2));
        assertEquals(100, multiplySafeByRational(50, 2, 1));

        assertEquals(-10, multiplySafeByRational(30, -1, 3));
        assertEquals(0, multiplySafeByRational(30, 0, 3));
        assertEquals(10, multiplySafeByRational(30, 1, 3));
        assertEquals(20, multiplySafeByRational(30, 2, 3));
        assertEquals(30, multiplySafeByRational(30, 3, 3));
        assertEquals(40, multiplySafeByRational(30, 4, 3));

        assertEquals(100_000_000_000L,
                multiplySafeByRational(300_000_000_000L, 10_000_000_000L, 30_000_000_000L));
        assertEquals(100_000_000_010L,
                multiplySafeByRational(300_000_000_000L, 10_000_000_001L, 30_000_000_000L));
        assertEquals(823_202_048L,
                multiplySafeByRational(4_939_212_288L, 2_121_815_528L, 12_730_893_165L));

        assertThrows(ArithmeticException.class, () -> multiplySafeByRational(30, 3, 0));
    }

    /**
     * Copy a {@link Resources#openRawResource(int)} into {@link File} for
     * testing purposes.
     */
    private void stageFile(int rawId, File file) throws Exception {
        new File(file.getParent()).mkdirs();
        InputStream in = null;
        OutputStream out = null;
        try {
            in = InstrumentationRegistry.getContext().getResources().openRawResource(rawId);
            out = new FileOutputStream(file);
            Streams.copy(in, out);
        } finally {
            IoUtils.closeQuietly(in);
            IoUtils.closeQuietly(out);
        }
    }

    private static NetworkStatsHistory getHistory(NetworkStatsCollection collection,
            SubscriptionPlan augmentPlan, long start, long end) {
        return collection.getHistory(buildTemplateMobileAll(TEST_IMSI), augmentPlan, UID_ALL,
                SET_ALL, TAG_NONE, FIELD_ALL, start, end, NetworkStatsAccess.Level.DEVICE, myUid());
    }

    private static void assertSummaryTotal(NetworkStatsCollection collection,
            NetworkTemplate template, long rxBytes, long rxPackets, long txBytes, long txPackets,
            @NetworkStatsAccess.Level int accessLevel) {
        final NetworkStats.Entry actual = collection.getSummary(
                template, Long.MIN_VALUE, Long.MAX_VALUE, accessLevel, myUid())
                .getTotal(null);
        assertEntry(rxBytes, rxPackets, txBytes, txPackets, actual);
    }

    private static void assertSummaryTotalIncludingTags(NetworkStatsCollection collection,
            NetworkTemplate template, long rxBytes, long rxPackets, long txBytes, long txPackets) {
        final NetworkStats.Entry actual = collection.getSummary(
                template, Long.MIN_VALUE, Long.MAX_VALUE, NetworkStatsAccess.Level.DEVICE, myUid())
                .getTotalIncludingTags(null);
        assertEntry(rxBytes, rxPackets, txBytes, txPackets, actual);
    }

    private static void assertEntry(long rxBytes, long rxPackets, long txBytes, long txPackets,
            NetworkStats.Entry actual) {
        assertEntry(new NetworkStats.Entry(rxBytes, rxPackets, txBytes, txPackets, 0L), actual);
    }

    private static void assertEntry(long rxBytes, long rxPackets, long txBytes, long txPackets,
            NetworkStatsHistory.Entry actual) {
        assertEntry(new NetworkStats.Entry(rxBytes, rxPackets, txBytes, txPackets, 0L), actual);
    }

    private static void assertEntry(NetworkStats.Entry expected,
            NetworkStatsHistory.Entry actual) {
        assertEntry(expected, new NetworkStats.Entry(actual.rxBytes, actual.rxPackets,
                actual.txBytes, actual.txPackets, 0L));
    }

    private static void assertEntry(NetworkStats.Entry expected,
            NetworkStats.Entry actual) {
        assertEquals("unexpected rxBytes", expected.rxBytes, actual.rxBytes);
        assertEquals("unexpected rxPackets", expected.rxPackets, actual.rxPackets);
        assertEquals("unexpected txBytes", expected.txBytes, actual.txBytes);
        assertEquals("unexpected txPackets", expected.txPackets, actual.txPackets);
    }
}
