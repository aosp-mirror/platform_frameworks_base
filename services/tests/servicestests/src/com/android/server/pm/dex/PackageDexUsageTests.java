/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.pm.dex;

import android.os.Build;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import dalvik.system.VMRuntime;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import static com.android.server.pm.dex.PackageDexUsage.PackageUseInfo;
import static com.android.server.pm.dex.PackageDexUsage.DexUseInfo;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class PackageDexUsageTests {
    private PackageDexUsage mPackageDexUsage;

    private TestData mFooBaseUser0;
    private TestData mFooSplit1User0;
    private TestData mFooSplit2UsedByOtherApps0;
    private TestData mFooSecondary1User0;
    private TestData mFooSecondary1User1;
    private TestData mFooSecondary2UsedByOtherApps0;
    private TestData mInvalidIsa;

    private TestData mBarBaseUser0;
    private TestData mBarSecondary1User0;
    private TestData mBarSecondary2User1;

    @Before
    public void setup() {
        mPackageDexUsage = new PackageDexUsage();

        String fooPackageName = "com.google.foo";
        String fooCodeDir = "/data/app/com.google.foo/";
        String fooDataDir = "/data/user/0/com.google.foo/";

        String isa = VMRuntime.getInstructionSet(Build.SUPPORTED_ABIS[0]);

        mFooBaseUser0 = new TestData(fooPackageName,
                fooCodeDir + "base.apk", 0, isa, false, true);

        mFooSplit1User0 = new TestData(fooPackageName,
                fooCodeDir + "split-1.apk", 0, isa, false, true);

        mFooSplit2UsedByOtherApps0 = new TestData(fooPackageName,
                fooCodeDir + "split-2.apk", 0, isa, true, true);

        mFooSecondary1User0 = new TestData(fooPackageName,
                fooDataDir + "sec-1.dex", 0, isa, false, false);

        mFooSecondary1User1 = new TestData(fooPackageName,
                fooDataDir + "sec-1.dex", 1, isa, false, false);

        mFooSecondary2UsedByOtherApps0 = new TestData(fooPackageName,
                fooDataDir + "sec-2.dex", 0, isa, true, false);

        mInvalidIsa = new TestData(fooPackageName,
                fooCodeDir + "base.apk", 0, "INVALID_ISA", false, true);

        String barPackageName = "com.google.bar";
        String barCodeDir = "/data/app/com.google.bar/";
        String barDataDir = "/data/user/0/com.google.bar/";
        String barDataDir1 = "/data/user/1/com.google.bar/";

        mBarBaseUser0 = new TestData(barPackageName,
                barCodeDir + "base.apk", 0, isa, false, true);
        mBarSecondary1User0 = new TestData(barPackageName,
                barDataDir + "sec-1.dex", 0, isa, false, false);
        mBarSecondary2User1 = new TestData(barPackageName,
                barDataDir1 + "sec-2.dex", 1, isa, false, false);
    }

    @Test
    public void testRecordPrimary() {
        // Assert new information.
        assertTrue(record(mFooBaseUser0));

        assertPackageDexUsage(mFooBaseUser0);
        writeAndReadBack();
        assertPackageDexUsage(mFooBaseUser0);
    }

    @Test
    public void testRecordSplit() {
        // Assert new information.
        assertTrue(record(mFooSplit1User0));

        assertPackageDexUsage(mFooSplit1User0);
        writeAndReadBack();
        assertPackageDexUsage(mFooSplit1User0);
    }

    @Test
    public void testRecordSplitPrimarySequence() {
        // Assert new information.
        assertTrue(record(mFooBaseUser0));
        // Assert no new information.
        assertFalse(record(mFooSplit1User0));

        assertPackageDexUsage(mFooBaseUser0);
        writeAndReadBack();
        assertPackageDexUsage(mFooBaseUser0);

        // Write Split2 which is used by other apps.
        // Assert new information.
        assertTrue(record(mFooSplit2UsedByOtherApps0));
        assertPackageDexUsage(mFooSplit2UsedByOtherApps0);
        writeAndReadBack();
        assertPackageDexUsage(mFooSplit2UsedByOtherApps0);
    }

    @Test
    public void testRecordSecondary() {
        assertTrue(record(mFooSecondary1User0));

        assertPackageDexUsage(null, mFooSecondary1User0);
        writeAndReadBack();
        assertPackageDexUsage(null, mFooSecondary1User0);

        // Recording again does not add more data.
        assertFalse(record(mFooSecondary1User0));
        assertPackageDexUsage(null, mFooSecondary1User0);
    }

    @Test
    public void testRecordBaseAndSecondarySequence() {
        // Write split.
        assertTrue(record(mFooSplit2UsedByOtherApps0));
        // Write secondary.
        assertTrue(record(mFooSecondary1User0));

        // Check.
        assertPackageDexUsage(mFooSplit2UsedByOtherApps0, mFooSecondary1User0);
        writeAndReadBack();
        assertPackageDexUsage(mFooSplit2UsedByOtherApps0, mFooSecondary1User0);

        // Write another secondary.
        assertTrue(record(mFooSecondary2UsedByOtherApps0));

        // Check.
        assertPackageDexUsage(
                mFooSplit2UsedByOtherApps0, mFooSecondary1User0, mFooSecondary2UsedByOtherApps0);
        writeAndReadBack();
        assertPackageDexUsage(
                mFooSplit2UsedByOtherApps0, mFooSecondary1User0, mFooSecondary2UsedByOtherApps0);
    }

    @Test
    public void testMultiplePackages() {
        assertTrue(record(mFooBaseUser0));
        assertTrue(record(mFooSecondary1User0));
        assertTrue(record(mFooSecondary2UsedByOtherApps0));
        assertTrue(record(mBarBaseUser0));
        assertTrue(record(mBarSecondary1User0));
        assertTrue(record(mBarSecondary2User1));

        assertPackageDexUsage(mFooBaseUser0, mFooSecondary1User0, mFooSecondary2UsedByOtherApps0);
        assertPackageDexUsage(mBarBaseUser0, mBarSecondary1User0, mBarSecondary2User1);
        writeAndReadBack();
        assertPackageDexUsage(mFooBaseUser0, mFooSecondary1User0, mFooSecondary2UsedByOtherApps0);
        assertPackageDexUsage(mBarBaseUser0, mBarSecondary1User0, mBarSecondary2User1);
    }

    @Test
    public void testPackageNotFound() {
        assertNull(mPackageDexUsage.getPackageUseInfo("missing.package"));
    }

    @Test
    public void testAttemptToChangeOwner() {
        assertTrue(record(mFooSecondary1User0));
        try {
            record(mFooSecondary1User1);
            fail("Expected exception");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    @Test
    public void testInvalidIsa() {
        try {
            record(mInvalidIsa);
            fail("Expected exception");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    @Test
    public void testReadWriteEmtpy() {
        // Expect no exceptions when writing/reading without data.
        writeAndReadBack();
    }

    @Test
    public void testSyncData() {
        // Write some records.
        assertTrue(record(mFooBaseUser0));
        assertTrue(record(mFooSecondary1User0));
        assertTrue(record(mFooSecondary2UsedByOtherApps0));
        assertTrue(record(mBarBaseUser0));
        assertTrue(record(mBarSecondary1User0));
        assertTrue(record(mBarSecondary2User1));

        // Verify all is good.
        assertPackageDexUsage(mFooBaseUser0, mFooSecondary1User0, mFooSecondary2UsedByOtherApps0);
        assertPackageDexUsage(mBarBaseUser0, mBarSecondary1User0, mBarSecondary2User1);
        writeAndReadBack();
        assertPackageDexUsage(mFooBaseUser0, mFooSecondary1User0, mFooSecondary2UsedByOtherApps0);
        assertPackageDexUsage(mBarBaseUser0, mBarSecondary1User0, mBarSecondary2User1);

        // Simulate that only user 1 is available.
        Map<String, Set<Integer>> packageToUsersMap = new HashMap<>();
        packageToUsersMap.put(mBarSecondary2User1.mPackageName,
                new HashSet<>(Arrays.asList(mBarSecondary2User1.mOwnerUserId)));
        mPackageDexUsage.syncData(packageToUsersMap);

        // Assert that only user 1 files are there.
        assertPackageDexUsage(mBarBaseUser0, mBarSecondary2User1);
        assertNull(mPackageDexUsage.getPackageUseInfo(mFooBaseUser0.mPackageName));
    }

    private void assertPackageDexUsage(TestData primary, TestData... secondaries) {
        String packageName = primary == null ? secondaries[0].mPackageName : primary.mPackageName;
        boolean primaryUsedByOtherApps = primary == null ? false : primary.mUsedByOtherApps;
        PackageUseInfo pInfo = mPackageDexUsage.getPackageUseInfo(packageName);

        // Check package use info
        assertNotNull(pInfo);
        assertEquals(primaryUsedByOtherApps, pInfo.isUsedByOtherApps());
        Map<String, DexUseInfo> dexUseInfoMap = pInfo.getDexUseInfoMap();
        assertEquals(secondaries.length, dexUseInfoMap.size());

        // Check dex use info
        for (TestData testData : secondaries) {
            DexUseInfo dInfo = dexUseInfoMap.get(testData.mDexFile);
            assertNotNull(dInfo);
            assertEquals(testData.mUsedByOtherApps, dInfo.isUsedByOtherApps());
            assertEquals(testData.mOwnerUserId, dInfo.getOwnerUserId());
            assertEquals(1, dInfo.getLoaderIsas().size());
            assertTrue(dInfo.getLoaderIsas().contains(testData.mLoaderIsa));
        }
    }

    private boolean record(TestData testData) {
        return mPackageDexUsage.record(testData.mPackageName, testData.mDexFile,
                testData.mOwnerUserId, testData.mLoaderIsa, testData.mUsedByOtherApps,
                testData.mPrimaryOrSplit);
    }

    private void writeAndReadBack() {
        try {
            StringWriter writer = new StringWriter();
            mPackageDexUsage.write(writer);

            mPackageDexUsage = new PackageDexUsage();
            mPackageDexUsage.read(new StringReader(writer.toString()));
        } catch (IOException e) {
            fail("Unexpected IOException: " + e.getMessage());
        }
    }

    private static class TestData {
        private final String mPackageName;
        private final String mDexFile;
        private final int mOwnerUserId;
        private final String mLoaderIsa;
        private final boolean mUsedByOtherApps;
        private final boolean mPrimaryOrSplit;

        private TestData(String packageName, String dexFile, int ownerUserId,
                 String loaderIsa, boolean isUsedByOtherApps, boolean primaryOrSplit) {
            mPackageName = packageName;
            mDexFile = dexFile;
            mOwnerUserId = ownerUserId;
            mLoaderIsa = loaderIsa;
            mUsedByOtherApps = isUsedByOtherApps;
            mPrimaryOrSplit = primaryOrSplit;
        }

    }
}
