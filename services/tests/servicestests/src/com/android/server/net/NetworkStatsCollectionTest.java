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
import static android.net.NetworkStats.SET_DEFAULT;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.NetworkStats.UID_ALL;
import static android.net.NetworkTemplate.buildTemplateMobileAll;
import static android.text.format.DateUtils.HOUR_IN_MILLIS;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;

import android.content.res.Resources;
import android.net.NetworkIdentity;
import android.net.NetworkStats;
import android.net.NetworkTemplate;
import android.os.Process;
import android.os.UserHandle;
import android.telephony.TelephonyManager;
import android.test.AndroidTestCase;
import android.test.MoreAsserts;
import android.test.suitebuilder.annotation.MediumTest;

import com.android.frameworks.servicestests.R;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import libcore.io.IoUtils;
import libcore.io.Streams;

/**
 * Tests for {@link NetworkStatsCollection}.
 */
@MediumTest
public class NetworkStatsCollectionTest extends AndroidTestCase {

    private static final String TEST_FILE = "test.bin";
    private static final String TEST_IMSI = "310260000000000";

    @Override
    public void setUp() throws Exception {
        super.setUp();

        // ignore any device overlay while testing
        NetworkTemplate.forceAllNetworkTypes();
    }

    public void testReadLegacyNetwork() throws Exception {
        final File testFile = new File(getContext().getFilesDir(), TEST_FILE);
        stageFile(R.raw.netstats_v1, testFile);

        final NetworkStatsCollection collection = new NetworkStatsCollection(30 * MINUTE_IN_MILLIS);
        collection.readLegacyNetwork(testFile);

        // verify that history read correctly
        assertSummaryTotal(collection, buildTemplateMobileAll(TEST_IMSI),
                636016770L, 709306L, 88038768L, 518836L, NetworkStatsAccess.Level.DEVICE);

        // now export into a unified format
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        collection.write(new DataOutputStream(bos));

        // clear structure completely
        collection.reset();
        assertSummaryTotal(collection, buildTemplateMobileAll(TEST_IMSI),
                0L, 0L, 0L, 0L, NetworkStatsAccess.Level.DEVICE);

        // and read back into structure, verifying that totals are same
        collection.read(new ByteArrayInputStream(bos.toByteArray()));
        assertSummaryTotal(collection, buildTemplateMobileAll(TEST_IMSI),
                636016770L, 709306L, 88038768L, 518836L, NetworkStatsAccess.Level.DEVICE);
    }

    public void testReadLegacyUid() throws Exception {
        final File testFile = new File(getContext().getFilesDir(), TEST_FILE);
        stageFile(R.raw.netstats_uid_v4, testFile);

        final NetworkStatsCollection collection = new NetworkStatsCollection(30 * MINUTE_IN_MILLIS);
        collection.readLegacyUid(testFile, false);

        // verify that history read correctly
        assertSummaryTotal(collection, buildTemplateMobileAll(TEST_IMSI),
                637076152L, 711413L, 88343717L, 521022L, NetworkStatsAccess.Level.DEVICE);

        // now export into a unified format
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        collection.write(new DataOutputStream(bos));

        // clear structure completely
        collection.reset();
        assertSummaryTotal(collection, buildTemplateMobileAll(TEST_IMSI),
                0L, 0L, 0L, 0L, NetworkStatsAccess.Level.DEVICE);

        // and read back into structure, verifying that totals are same
        collection.read(new ByteArrayInputStream(bos.toByteArray()));
        assertSummaryTotal(collection, buildTemplateMobileAll(TEST_IMSI),
                637076152L, 711413L, 88343717L, 521022L, NetworkStatsAccess.Level.DEVICE);
    }

    public void testReadLegacyUidTags() throws Exception {
        final File testFile = new File(getContext().getFilesDir(), TEST_FILE);
        stageFile(R.raw.netstats_uid_v4, testFile);

        final NetworkStatsCollection collection = new NetworkStatsCollection(30 * MINUTE_IN_MILLIS);
        collection.readLegacyUid(testFile, true);

        // verify that history read correctly
        assertSummaryTotalIncludingTags(collection, buildTemplateMobileAll(TEST_IMSI),
                77017831L, 100995L, 35436758L, 92344L);

        // now export into a unified format
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        collection.write(new DataOutputStream(bos));

        // clear structure completely
        collection.reset();
        assertSummaryTotalIncludingTags(collection, buildTemplateMobileAll(TEST_IMSI),
                0L, 0L, 0L, 0L);

        // and read back into structure, verifying that totals are same
        collection.read(new ByteArrayInputStream(bos.toByteArray()));
        assertSummaryTotalIncludingTags(collection, buildTemplateMobileAll(TEST_IMSI),
                77017831L, 100995L, 35436758L, 92344L);
    }

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

    public void testAccessLevels() throws Exception {
        final NetworkStatsCollection collection = new NetworkStatsCollection(HOUR_IN_MILLIS);
        final NetworkStats.Entry entry = new NetworkStats.Entry();
        final NetworkIdentitySet identSet = new NetworkIdentitySet();
        identSet.add(new NetworkIdentity(TYPE_MOBILE, TelephonyManager.NETWORK_TYPE_UNKNOWN,
                TEST_IMSI, null, false, true));

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
        MoreAsserts.assertEquals(new int[] { myUid },
                collection.getRelevantUids(NetworkStatsAccess.Level.DEFAULT));
        MoreAsserts.assertEquals(new int[] { Process.SYSTEM_UID, myUid, otherUidInSameUser },
                collection.getRelevantUids(NetworkStatsAccess.Level.USER));
        MoreAsserts.assertEquals(
                new int[] { Process.SYSTEM_UID, myUid, otherUidInSameUser, uidInDifferentUser },
                collection.getRelevantUids(NetworkStatsAccess.Level.DEVICE));

        // Verify security check in getHistory.
        assertNotNull(collection.getHistory(buildTemplateMobileAll(TEST_IMSI), myUid, SET_DEFAULT,
                TAG_NONE, 0, NetworkStatsAccess.Level.DEFAULT));
        try {
            collection.getHistory(buildTemplateMobileAll(TEST_IMSI), otherUidInSameUser,
                    SET_DEFAULT, TAG_NONE, 0, NetworkStatsAccess.Level.DEFAULT);
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

    /**
     * Copy a {@link Resources#openRawResource(int)} into {@link File} for
     * testing purposes.
     */
    private void stageFile(int rawId, File file) throws Exception {
        new File(file.getParent()).mkdirs();
        InputStream in = null;
        OutputStream out = null;
        try {
            in = getContext().getResources().openRawResource(rawId);
            out = new FileOutputStream(file);
            Streams.copy(in, out);
        } finally {
            IoUtils.closeQuietly(in);
            IoUtils.closeQuietly(out);
        }
    }

    private static void assertSummaryTotal(NetworkStatsCollection collection,
            NetworkTemplate template, long rxBytes, long rxPackets, long txBytes, long txPackets,
            @NetworkStatsAccess.Level int accessLevel) {
        final NetworkStats.Entry entry = collection.getSummary(
                template, Long.MIN_VALUE, Long.MAX_VALUE, accessLevel)
                .getTotal(null);
        assertEntry(entry, rxBytes, rxPackets, txBytes, txPackets);
    }

    private static void assertSummaryTotalIncludingTags(NetworkStatsCollection collection,
            NetworkTemplate template, long rxBytes, long rxPackets, long txBytes, long txPackets) {
        final NetworkStats.Entry entry = collection.getSummary(
                template, Long.MIN_VALUE, Long.MAX_VALUE, NetworkStatsAccess.Level.DEVICE)
                .getTotalIncludingTags(null);
        assertEntry(entry, rxBytes, rxPackets, txBytes, txPackets);
    }

    private static void assertEntry(
            NetworkStats.Entry entry, long rxBytes, long rxPackets, long txBytes, long txPackets) {
        assertEquals("unexpected rxBytes", rxBytes, entry.rxBytes);
        assertEquals("unexpected rxPackets", rxPackets, entry.rxPackets);
        assertEquals("unexpected txBytes", txBytes, entry.txBytes);
        assertEquals("unexpected txPackets", txPackets, entry.txPackets);
    }
}
