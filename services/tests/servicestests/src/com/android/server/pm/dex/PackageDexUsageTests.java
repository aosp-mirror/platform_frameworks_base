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

import static com.android.server.pm.dex.PackageDexUsage.DexUseInfo;
import static com.android.server.pm.dex.PackageDexUsage.MAX_SECONDARY_FILES_PER_OWNER;
import static com.android.server.pm.dex.PackageDexUsage.PackageUseInfo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.os.Build;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import dalvik.system.VMRuntime;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class PackageDexUsageTests {
    private static final String ISA = VMRuntime.getInstructionSet(Build.SUPPORTED_ABIS[0]);

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

        mFooBaseUser0 = new TestData(fooPackageName,
                fooCodeDir + "base.apk", 0, ISA, false, true, fooPackageName);

        mFooSplit1User0 = new TestData(fooPackageName,
                fooCodeDir + "split-1.apk", 0, ISA, false, true, fooPackageName);

        mFooSplit2UsedByOtherApps0 = new TestData(fooPackageName,
                fooCodeDir + "split-2.apk", 0, ISA, true, true, "used.by.other.com");

        mFooSecondary1User0 = new TestData(fooPackageName,
                fooDataDir + "sec-1.dex", 0, ISA, false, false, fooPackageName);

        mFooSecondary1User1 = new TestData(fooPackageName,
                fooDataDir + "sec-1.dex", 1, ISA, false, false, fooPackageName);

        mFooSecondary2UsedByOtherApps0 = new TestData(fooPackageName,
                fooDataDir + "sec-2.dex", 0, ISA, true, false, "used.by.other.com");

        mInvalidIsa = new TestData(fooPackageName,
                fooCodeDir + "base.apk", 0, "INVALID_ISA", false, true, "INALID_USER");

        String barPackageName = "com.google.bar";
        String barCodeDir = "/data/app/com.google.bar/";
        String barDataDir = "/data/user/0/com.google.bar/";
        String barDataDir1 = "/data/user/1/com.google.bar/";

        mBarBaseUser0 = new TestData(barPackageName,
                barCodeDir + "base.apk", 0, ISA, false, true, barPackageName);
        mBarSecondary1User0 = new TestData(barPackageName,
                barDataDir + "sec-1.dex", 0, ISA, false, false, barPackageName);
        mBarSecondary2User1 = new TestData(barPackageName,
                barDataDir1 + "sec-2.dex", 1, ISA, false, false, barPackageName);
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
    public void testRecordTooManySecondaries() {
        int tooManyFiles = MAX_SECONDARY_FILES_PER_OWNER + 1;
        List<TestData> expectedSecondaries = new ArrayList<>();
        for (int i = 1; i <= tooManyFiles; i++) {
            String fooPackageName = "com.google.foo";
            TestData testData = new TestData(fooPackageName,
                    "/data/user/0/" + fooPackageName + "/sec-" + i + "1.dex", 0, ISA, false, false,
                    fooPackageName);
            if (i < tooManyFiles) {
                assertTrue("Adding " + testData.mDexFile, record(testData));
                expectedSecondaries.add(testData);
            } else {
                assertFalse("Adding " + testData.mDexFile, record(testData));
            }
            assertPackageDexUsage(mPackageDexUsage, null, null, expectedSecondaries);
        }
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
        Map<String, Set<String>> packageToCodePaths = new HashMap<>();
        packageToCodePaths.put(mBarBaseUser0.mPackageName,
                new HashSet<>(Arrays.asList(mBarBaseUser0.mDexFile)));
        mPackageDexUsage.syncData(packageToUsersMap, packageToCodePaths);

        // Assert that only user 1 files are there.
        assertPackageDexUsage(mBarBaseUser0, mBarSecondary2User1);
        assertNull(mPackageDexUsage.getPackageUseInfo(mFooBaseUser0.mPackageName));
    }

    @Test
    public void testRemovePackage() {
        // Record Bar secondaries for two different users.
        assertTrue(record(mBarSecondary1User0));
        assertTrue(record(mBarSecondary2User1));

        // Remove the package.
        assertTrue(mPackageDexUsage.removePackage(mBarSecondary1User0.mPackageName));
        // Assert that we can't find the package anymore.
        assertNull(mPackageDexUsage.getPackageUseInfo(mBarSecondary1User0.mPackageName));
    }

    @Test
    public void testRemoveNonexistentPackage() {
        // Record Bar secondaries for two different users.
        assertTrue(record(mBarSecondary1User0));

        // Remove the package.
        assertTrue(mPackageDexUsage.removePackage(mBarSecondary1User0.mPackageName));
        // Remove the package again. It should return false because the package no longer
        // has a record in the use info.
        assertFalse(mPackageDexUsage.removePackage(mBarSecondary1User0.mPackageName));
    }

    @Test
    public void testRemoveUserPackage() {
        // Record Bar secondaries for two different users.
        assertTrue(record(mBarSecondary1User0));
        assertTrue(record(mBarSecondary2User1));

        // Remove user 0 files.
        assertTrue(mPackageDexUsage.removeUserPackage(mBarSecondary1User0.mPackageName,
                mBarSecondary1User0.mOwnerUserId));
        // Assert that only user 1 files are there.
        assertPackageDexUsage(null, mBarSecondary2User1);
    }

    @Test
    public void testRemoveDexFile() {
        // Record Bar secondaries for two different users.
        assertTrue(record(mBarSecondary1User0));
        assertTrue(record(mBarSecondary2User1));

        // Remove mBarSecondary1User0 file.
        assertTrue(mPackageDexUsage.removeDexFile(mBarSecondary1User0.mPackageName,
                mBarSecondary1User0.mDexFile, mBarSecondary1User0.mOwnerUserId));
        // Assert that only user 1 files are there.
        assertPackageDexUsage(null, mBarSecondary2User1);
    }

    @Test
    public void testClearUsedByOtherApps() {
        // Write a package which is used by other apps.
        assertTrue(record(mFooSplit2UsedByOtherApps0));
        assertTrue(mPackageDexUsage.clearUsedByOtherApps(mFooSplit2UsedByOtherApps0.mPackageName));

        // Check that the package is no longer used by other apps.
        TestData noLongerUsedByOtherApps = new TestData(
            mFooSplit2UsedByOtherApps0.mPackageName,
            mFooSplit2UsedByOtherApps0.mDexFile,
            mFooSplit2UsedByOtherApps0.mOwnerUserId,
            mFooSplit2UsedByOtherApps0.mLoaderIsa,
            /*mIsUsedByOtherApps*/false,
            mFooSplit2UsedByOtherApps0.mPrimaryOrSplit,
            mFooSplit2UsedByOtherApps0.mUsedBy);
        assertPackageDexUsage(noLongerUsedByOtherApps);
    }

    @Test
    public void testClearUsedByOtherAppsNonexistent() {
        // Write a package which is used by other apps.
        assertTrue(record(mFooSplit2UsedByOtherApps0));
        assertTrue(mPackageDexUsage.clearUsedByOtherApps(mFooSplit2UsedByOtherApps0.mPackageName));
        // Clearing again should return false as there should be no update on the use info.
        assertFalse(mPackageDexUsage.clearUsedByOtherApps(mFooSplit2UsedByOtherApps0.mPackageName));
    }

    @Test
    public void testRecordDexFileUsers() {
        PackageDexUsage packageDexUsageRecordUsers = new PackageDexUsage();
        Set<String> users = new HashSet<>(Arrays.asList(
                new String[] {"another.package.1"}));
        Set<String> usersExtra = new HashSet<>(Arrays.asList(
                new String[] {"another.package.2", "another.package.3"}));

        assertTrue(record(packageDexUsageRecordUsers, mFooSplit2UsedByOtherApps0, users));
        assertTrue(record(packageDexUsageRecordUsers, mFooSplit2UsedByOtherApps0, usersExtra));

        assertTrue(record(packageDexUsageRecordUsers, mFooSecondary1User0, users));
        assertTrue(record(packageDexUsageRecordUsers, mFooSecondary1User0, usersExtra));

        packageDexUsageRecordUsers = writeAndReadBack(packageDexUsageRecordUsers);
        // Verify that the users were recorded.
        Set<String> userAll = new HashSet<>(users);
        userAll.addAll(usersExtra);
        assertPackageDexUsage(packageDexUsageRecordUsers, userAll, mFooSplit2UsedByOtherApps0,
                mFooSecondary1User0);
    }

    @Test
    public void testRecordDexFileUsersNotTheOwningPackage() {
        PackageDexUsage packageDexUsageRecordUsers = new PackageDexUsage();
        Set<String> users = new HashSet<>(Arrays.asList(
                new String[] {mFooSplit2UsedByOtherApps0.mPackageName}));
        Set<String> usersExtra = new HashSet<>(Arrays.asList(
                new String[] {"another.package.2", "another.package.3"}));

        assertTrue(record(packageDexUsageRecordUsers, mFooSplit2UsedByOtherApps0, users));
        assertTrue(record(packageDexUsageRecordUsers, mFooSplit2UsedByOtherApps0, usersExtra));

        assertTrue(record(packageDexUsageRecordUsers, mFooSecondary1User0, users));
        assertTrue(record(packageDexUsageRecordUsers, mFooSecondary1User0, usersExtra));

        packageDexUsageRecordUsers = writeAndReadBack(packageDexUsageRecordUsers);
        // Verify that only the non owning packages were recorded.
        assertPackageDexUsage(packageDexUsageRecordUsers, usersExtra, mFooSplit2UsedByOtherApps0,
                mFooSecondary1User0);
    }

    @Test
    public void testRecordClassLoaderContextVariableContext() {
        // Record a secondary dex file.
        assertTrue(record(mFooSecondary1User0));
        // Now update its context.
        TestData fooSecondary1User0NewContext = mFooSecondary1User0.updateClassLoaderContext(
                "PCL[new_context.dex]");
        assertTrue(record(fooSecondary1User0NewContext));

        // Not check that the context was switch to variable.
        TestData expectedContext = mFooSecondary1User0.updateClassLoaderContext(
                PackageDexUsage.VARIABLE_CLASS_LOADER_CONTEXT);

        assertPackageDexUsage(null, expectedContext);
        writeAndReadBack();
        assertPackageDexUsage(null, expectedContext);
    }

    @Test
    public void testRecordClassLoaderContextTransitionFromUnknown() {
        // Record a secondary dex file.
        TestData unknownContext = mFooSecondary1User0.updateClassLoaderContext(
                PackageDexUsage.UNKNOWN_CLASS_LOADER_CONTEXT);
        assertTrue(record(unknownContext));

        assertPackageDexUsage(null, unknownContext);
        writeAndReadBack();
        assertPackageDexUsage(null, unknownContext);

        // Now update the secondary dex record with a class loader context. This simulates the
        // version 2 to version 3 upgrade.

        assertTrue(record(mFooSecondary1User0));

        assertPackageDexUsage(null, mFooSecondary1User0);
        writeAndReadBack();
        assertPackageDexUsage(null, mFooSecondary1User0);
    }

    @Test
    public void testDexUsageClassLoaderContext() {
        final boolean isUsedByOtherApps = false;
        final int userId = 0;
        PackageDexUsage.DexUseInfo validContext = new DexUseInfo(isUsedByOtherApps, userId,
                "valid_context", "arm");
        assertFalse(validContext.isUnknownClassLoaderContext());
        assertFalse(validContext.isVariableClassLoaderContext());

        PackageDexUsage.DexUseInfo variableContext = new DexUseInfo(isUsedByOtherApps, userId,
                PackageDexUsage.VARIABLE_CLASS_LOADER_CONTEXT, "arm");
        assertFalse(variableContext.isUnknownClassLoaderContext());
        assertTrue(variableContext.isVariableClassLoaderContext());

        PackageDexUsage.DexUseInfo unknownContext = new DexUseInfo(isUsedByOtherApps, userId,
                PackageDexUsage.UNKNOWN_CLASS_LOADER_CONTEXT, "arm");
        assertTrue(unknownContext.isUnknownClassLoaderContext());
        assertFalse(unknownContext.isVariableClassLoaderContext());
    }

    @Test
    public void testUnsupportedClassLoaderDiscardedOnRead() throws Exception {
        String content = "PACKAGE_MANAGER__PACKAGE_DEX_USAGE__2\n"
                + mBarSecondary1User0.mPackageName + "\n"
                + "#" + mBarSecondary1User0.mDexFile + "\n"
                + "0,0," + mBarSecondary1User0.mLoaderIsa + "\n"
                + "@\n"
                + "=UnsupportedClassLoaderContext=\n"

                + mFooSecondary1User0.mPackageName + "\n"
                + "#" + mFooSecondary1User0.mDexFile + "\n"
                + "0,0," + mFooSecondary1User0.mLoaderIsa + "\n"
                + "@\n"
                + mFooSecondary1User0.mClassLoaderContext + "\n";

        mPackageDexUsage.read(new StringReader(content));

        assertPackageDexUsage(mFooBaseUser0, mFooSecondary1User0);
        assertPackageDexUsage(mBarBaseUser0);
    }

    @Test
    public void testReadVersion1() {
        String isa = VMRuntime.getInstructionSet(Build.SUPPORTED_ABIS[0]);
        // Equivalent to
        //   record(mFooSplit2UsedByOtherApps0);
        //   record(mFooSecondary1User0);
        //   record(mFooSecondary2UsedByOtherApps0);
        //   record(mBarBaseUser0);
        //   record(mBarSecondary1User0);
        String content = "PACKAGE_MANAGER__PACKAGE_DEX_USAGE__1\n"
                + "com.google.foo,1\n"
                + "#/data/user/0/com.google.foo/sec-1.dex\n"
                + "0,0," + isa + "\n"
                + "#/data/user/0/com.google.foo/sec-2.dex\n"
                + "0,1," + isa + "\n"
                + "com.google.bar,0\n"
                + "#/data/user/0/com.google.bar/sec-1.dex\n"
                + "0,0," + isa + "\n";

        PackageDexUsage packageDexUsage = new PackageDexUsage();
        try {
            packageDexUsage.read(new StringReader(content));
        } catch (IOException e) {
            fail();
        }

        // After the read we must sync the data to fill the missing information on the code paths.
        Map<String, Set<Integer>> packageToUsersMap = new HashMap<>();
        Map<String, Set<String>> packageToCodePaths = new HashMap<>();

        // Handle foo package.
        packageToUsersMap.put(mFooSplit2UsedByOtherApps0.mPackageName,
            new HashSet<>(Arrays.asList(mFooSplit2UsedByOtherApps0.mOwnerUserId)));
        packageToCodePaths.put(mFooSplit2UsedByOtherApps0.mPackageName,
            new HashSet<>(Arrays.asList(mFooSplit2UsedByOtherApps0.mDexFile,
                mFooSplit1User0.mDexFile, mFooBaseUser0.mDexFile)));
        // Handle bar package.
        packageToUsersMap.put(mBarBaseUser0.mPackageName,
            new HashSet<>(Arrays.asList(mBarBaseUser0.mOwnerUserId)));
        packageToCodePaths.put(mBarBaseUser0.mPackageName,
            new HashSet<>(Arrays.asList(mBarBaseUser0.mDexFile)));

        // Sync the data.
        packageDexUsage.syncData(packageToUsersMap, packageToCodePaths);

        // Update the class loaders to unknown before asserting if needed. Before version 2 we
        // didn't have any.
        String unknown = PackageDexUsage.UNKNOWN_CLASS_LOADER_CONTEXT;
        TestData fooBaseUser0 = mFooBaseUser0.updateClassLoaderContext(unknown);
        TestData fooSplit1User0 = mFooSplit1User0.updateClassLoaderContext(unknown);
        TestData fooSplit2UsedByOtherApps0 =
            mFooSplit2UsedByOtherApps0.updateClassLoaderContext(unknown);
        TestData fooSecondary1User0 = mFooSecondary1User0.updateClassLoaderContext(unknown);
        TestData fooSecondary2UsedByOtherApps0 =
            mFooSecondary2UsedByOtherApps0.updateClassLoaderContext(unknown);
        TestData barBaseUser0 = mBarBaseUser0.updateClassLoaderContext(unknown);
        TestData barSecondary1User0 = mBarSecondary1User0.updateClassLoaderContext(unknown);

        // Assert foo code paths. Note that we ignore the users during upgrade.
        final Set<String> ignoredUsers = null;
        assertPackageDexUsage(packageDexUsage, ignoredUsers,
            fooSplit2UsedByOtherApps0, fooSecondary1User0, fooSecondary2UsedByOtherApps0);
        // Because fooSplit2UsedByOtherApps0 is used by others, all the other code paths must
        // share the same data.
        assertPackageDexUsage(packageDexUsage, ignoredUsers,
            fooSplit1User0.updateUseByOthers(true),
            fooSecondary1User0, fooSecondary2UsedByOtherApps0);
        assertPackageDexUsage(packageDexUsage, ignoredUsers, fooBaseUser0.updateUseByOthers(true),
            fooSecondary1User0, fooSecondary2UsedByOtherApps0);

        // Assert bar code paths. Note that we ignore the users during upgrade.
        assertPackageDexUsage(packageDexUsage, ignoredUsers, barBaseUser0, barSecondary1User0);
    }

    private void assertPackageDexUsage(TestData primary, TestData... secondaries) {
        assertPackageDexUsage(mPackageDexUsage, null, primary, secondaries);
    }

    private void assertPackageDexUsage(PackageDexUsage packageDexUsage, Set<String> users,
            TestData primary, TestData... secondaries) {
        assertPackageDexUsage(packageDexUsage, users, primary, Arrays.asList(secondaries));
    }

    private void assertPackageDexUsage(PackageDexUsage packageDexUsage, Set<String> users,
            TestData primary, List<TestData> secondaries) {
        String packageName = primary == null
                ? secondaries.get(0).mPackageName
                : primary.mPackageName;
        boolean primaryUsedByOtherApps = primary != null && primary.mUsedByOtherApps;
        PackageUseInfo pInfo = packageDexUsage.getPackageUseInfo(packageName);

        // Check package use info
        assertNotNull(pInfo);
        if (primary != null) {
            assertEquals(primaryUsedByOtherApps, pInfo.isUsedByOtherApps(primary.mDexFile));
            if (users != null) {
                assertEquals(pInfo.getLoadingPackages(primary.mDexFile), users);
            }
        }

        Map<String, DexUseInfo> dexUseInfoMap = pInfo.getDexUseInfoMap();
        assertEquals(secondaries.size(), dexUseInfoMap.size());

        // Check dex use info
        for (TestData testData : secondaries) {
            DexUseInfo dInfo = dexUseInfoMap.get(testData.mDexFile);
            assertNotNull(dInfo);
            assertEquals(testData.mUsedByOtherApps, dInfo.isUsedByOtherApps());
            assertEquals(testData.mOwnerUserId, dInfo.getOwnerUserId());
            assertEquals(1, dInfo.getLoaderIsas().size());
            assertTrue(dInfo.getLoaderIsas().contains(testData.mLoaderIsa));
            if (users != null) {
                 assertEquals(dInfo.getLoadingPackages(), users);
            }

            assertEquals(testData.mClassLoaderContext, dInfo.getClassLoaderContext());
        }
    }

    private boolean record(TestData testData) {
        return mPackageDexUsage.record(testData.mPackageName, testData.mDexFile,
               testData.mOwnerUserId, testData.mLoaderIsa, testData.mUsedByOtherApps,
               testData.mPrimaryOrSplit, testData.mUsedBy, testData.mClassLoaderContext);
    }

    private boolean record(PackageDexUsage packageDexUsage, TestData testData, Set<String> users) {
        boolean result = true;
        for (String user : users) {
            result = result && packageDexUsage.record(testData.mPackageName, testData.mDexFile,
                    testData.mOwnerUserId, testData.mLoaderIsa, testData.mUsedByOtherApps,
                    testData.mPrimaryOrSplit, user, testData.mClassLoaderContext);
        }
        return result;
    }

    private void writeAndReadBack() {
        mPackageDexUsage = writeAndReadBack(mPackageDexUsage);
    }

    private PackageDexUsage writeAndReadBack(PackageDexUsage packageDexUsage) {
        try {
            StringWriter writer = new StringWriter();
            packageDexUsage.write(writer);

            PackageDexUsage newPackageDexUsage = new PackageDexUsage();
            newPackageDexUsage.read(new StringReader(writer.toString()));
            return newPackageDexUsage;
        } catch (IOException e) {
            fail("Unexpected IOException: " + e.getMessage());
            return null;
        }
    }

    private static class TestData {
        private final String mPackageName;
        private final String mDexFile;
        private final int mOwnerUserId;
        private final String mLoaderIsa;
        private final boolean mUsedByOtherApps;
        private final boolean mPrimaryOrSplit;
        private final String mUsedBy;
        private final String mClassLoaderContext;

        private TestData(String packageName, String dexFile, int ownerUserId,
                String loaderIsa, boolean isUsedByOtherApps, boolean primaryOrSplit, String usedBy) {
            this(packageName, dexFile, ownerUserId, loaderIsa, isUsedByOtherApps, primaryOrSplit,
                    usedBy, "DefaultClassLoaderContextFor_" + dexFile);
        }
        private TestData(String packageName, String dexFile, int ownerUserId,
                String loaderIsa, boolean isUsedByOtherApps, boolean primaryOrSplit, String usedBy,
                String classLoaderContext) {
            mPackageName = packageName;
            mDexFile = dexFile;
            mOwnerUserId = ownerUserId;
            mLoaderIsa = loaderIsa;
            mUsedByOtherApps = isUsedByOtherApps;
            mPrimaryOrSplit = primaryOrSplit;
            mUsedBy = usedBy;
            mClassLoaderContext = classLoaderContext;
        }

        private TestData updateClassLoaderContext(String newContext) {
            return new TestData(mPackageName, mDexFile, mOwnerUserId, mLoaderIsa, mUsedByOtherApps,
                    mPrimaryOrSplit, mUsedBy, newContext);
        }

        private TestData updateUseByOthers(boolean newUsedByOthers) {
            return new TestData(mPackageName, mDexFile, mOwnerUserId, mLoaderIsa, newUsedByOthers,
                mPrimaryOrSplit, mUsedBy, mClassLoaderContext);
        }
    }
}
