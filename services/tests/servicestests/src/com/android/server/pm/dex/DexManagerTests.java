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
 * limitations under the License
 */

package com.android.server.pm.dex;

import android.os.Build;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import dalvik.system.VMRuntime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

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
public class DexManagerTests {
    private DexManager mDexManager;

    private TestData mFooUser0;
    private TestData mBarUser0;
    private TestData mBarUser1;
    private TestData mInvalidIsa;
    private TestData mDoesNotExist;

    private int mUser0;
    private int mUser1;
    @Before
    public void setup() {

        mUser0 = 0;
        mUser1 = 1;

        String isa = VMRuntime.getInstructionSet(Build.SUPPORTED_ABIS[0]);
        String foo = "foo";
        String bar = "bar";

        mFooUser0 = new TestData(foo, isa, mUser0);
        mBarUser0 = new TestData(bar, isa, mUser0);
        mBarUser1 = new TestData(bar, isa, mUser1);
        mInvalidIsa = new TestData("INVALID", "INVALID_ISA", mUser0);
        mDoesNotExist = new TestData("DOES.NOT.EXIST", isa, mUser1);


        mDexManager = new DexManager();

        // Foo and Bar are available to user0.
        // Only Bar is available to user1;
        Map<Integer, List<PackageInfo>> existingPackages = new HashMap<>();
        existingPackages.put(mUser0, Arrays.asList(mFooUser0.mPackageInfo, mBarUser0.mPackageInfo));
        existingPackages.put(mUser1, Arrays.asList(mBarUser1.mPackageInfo));
        mDexManager.load(existingPackages);
    }

    @Test
    public void testNotifyPrimaryUse() {
        // The main dex file and splits are re-loaded by the app.
        notifyDexLoad(mFooUser0, mFooUser0.getBaseAndSplitDexPaths(), mUser0);

        // Package is not used by others, so we should get nothing back.
        assertNull(getPackageUseInfo(mFooUser0));
    }

    @Test
    public void testNotifyPrimaryForeignUse() {
        // Foo loads Bar main apks.
        notifyDexLoad(mFooUser0, mBarUser0.getBaseAndSplitDexPaths(), mUser0);

        // Bar is used by others now and should be in our records
        PackageUseInfo pui = getPackageUseInfo(mBarUser0);
        assertNotNull(pui);
        assertTrue(pui.isUsedByOtherApps());
        assertTrue(pui.getDexUseInfoMap().isEmpty());
    }

    @Test
    public void testNotifySecondary() {
        // Foo loads its own secondary files.
        List<String> fooSecondaries = mFooUser0.getSecondaryDexPaths();
        notifyDexLoad(mFooUser0, fooSecondaries, mUser0);

        PackageUseInfo pui = getPackageUseInfo(mFooUser0);
        assertNotNull(pui);
        assertFalse(pui.isUsedByOtherApps());
        assertEquals(fooSecondaries.size(), pui.getDexUseInfoMap().size());
        assertSecondaryUse(mFooUser0, pui, fooSecondaries, /*isUsedByOtherApps*/false, mUser0);
    }

    @Test
    public void testNotifySecondaryForeign() {
        // Foo loads bar secondary files.
        List<String> barSecondaries = mBarUser0.getSecondaryDexPaths();
        notifyDexLoad(mFooUser0, barSecondaries, mUser0);

        PackageUseInfo pui = getPackageUseInfo(mBarUser0);
        assertNotNull(pui);
        assertFalse(pui.isUsedByOtherApps());
        assertEquals(barSecondaries.size(), pui.getDexUseInfoMap().size());
        assertSecondaryUse(mFooUser0, pui, barSecondaries, /*isUsedByOtherApps*/true, mUser0);
    }

    @Test
    public void testNotifySequence() {
        // Foo loads its own secondary files.
        List<String> fooSecondaries = mFooUser0.getSecondaryDexPaths();
        notifyDexLoad(mFooUser0, fooSecondaries, mUser0);
        // Foo loads Bar own secondary files.
        List<String> barSecondaries = mBarUser0.getSecondaryDexPaths();
        notifyDexLoad(mFooUser0, barSecondaries, mUser0);
        // Foo loads Bar primary files.
        notifyDexLoad(mFooUser0, mBarUser0.getBaseAndSplitDexPaths(), mUser0);
        // Bar loads its own secondary files.
        notifyDexLoad(mBarUser0, barSecondaries, mUser0);
        // Bar loads some own secondary files which foo didn't load.
        List<String> barSecondariesForOwnUse = mBarUser0.getSecondaryDexPathsForOwnUse();
        notifyDexLoad(mBarUser0, barSecondariesForOwnUse, mUser0);

        // Check bar usage. Should be used by other app (for primary and barSecondaries).
        PackageUseInfo pui = getPackageUseInfo(mBarUser0);
        assertNotNull(pui);
        assertTrue(pui.isUsedByOtherApps());
        assertEquals(barSecondaries.size() + barSecondariesForOwnUse.size(),
                pui.getDexUseInfoMap().size());

        assertSecondaryUse(mFooUser0, pui, barSecondaries, /*isUsedByOtherApps*/true, mUser0);
        assertSecondaryUse(mFooUser0, pui, barSecondariesForOwnUse,
                /*isUsedByOtherApps*/false, mUser0);

        // Check foo usage. Should not be used by other app.
        pui = getPackageUseInfo(mFooUser0);
        assertNotNull(pui);
        assertFalse(pui.isUsedByOtherApps());
        assertEquals(fooSecondaries.size(), pui.getDexUseInfoMap().size());
        assertSecondaryUse(mFooUser0, pui, fooSecondaries, /*isUsedByOtherApps*/false, mUser0);
    }

    @Test
    public void testPackageUseInfoNotFound() {
        // Assert we don't get back data we did not previously record.
        assertNull(getPackageUseInfo(mFooUser0));
    }

    @Test
    public void testInvalidIsa() {
        // Notifying with an invalid ISA should be ignored.
        notifyDexLoad(mInvalidIsa, mInvalidIsa.getSecondaryDexPaths(), mUser0);
        assertNull(getPackageUseInfo(mInvalidIsa));
    }

    @Test
    public void testNotExistingPackate() {
        // Notifying about the load of a package which was previously not
        // register in DexManager#load should be ignored.
        notifyDexLoad(mDoesNotExist, mDoesNotExist.getBaseAndSplitDexPaths(), mUser0);
        assertNull(getPackageUseInfo(mDoesNotExist));
    }

    @Test
    public void testCrossUserAttempt() {
        // Bar from User1 tries to load secondary dex files from User0 Bar.
        // Request should be ignored.
        notifyDexLoad(mBarUser1, mBarUser0.getSecondaryDexPaths(), mUser1);
        assertNull(getPackageUseInfo(mBarUser1));
    }

    @Test
    public void testPackageNotInstalledForUser() {
        // User1 tries to load Foo which is installed for User0 but not for User1.
        // Note that the PackageManagerService already filters this out but we
        // still check that nothing goes unexpected in DexManager.
        notifyDexLoad(mBarUser0, mFooUser0.getBaseAndSplitDexPaths(), mUser1);
        assertNull(getPackageUseInfo(mBarUser1));
    }

    private void assertSecondaryUse(TestData testData, PackageUseInfo pui,
            List<String> secondaries, boolean isUsedByOtherApps, int ownerUserId) {
        for (String dex : secondaries) {
            DexUseInfo dui = pui.getDexUseInfoMap().get(dex);
            assertNotNull(dui);
            assertEquals(isUsedByOtherApps, dui.isUsedByOtherApps());
            assertEquals(ownerUserId, dui.getOwnerUserId());
            assertEquals(1, dui.getLoaderIsas().size());
            assertTrue(dui.getLoaderIsas().contains(testData.mLoaderIsa));
        }
    }

    private void notifyDexLoad(TestData testData, List<String> dexPaths, int loaderUserId) {
        mDexManager.notifyDexLoad(testData.mPackageInfo.applicationInfo, dexPaths,
                testData.mLoaderIsa, loaderUserId);
    }

    private PackageUseInfo getPackageUseInfo(TestData testData) {
        return mDexManager.getPackageUseInfo(testData.mPackageInfo.packageName);
    }

    private static PackageInfo getMockPackageInfo(String packageName, int userId) {
        PackageInfo pi = new PackageInfo();
        pi.packageName = packageName;
        pi.applicationInfo = getMockApplicationInfo(packageName, userId);
        return pi;
    }

    private static ApplicationInfo getMockApplicationInfo(String packageName, int userId) {
        ApplicationInfo ai = new ApplicationInfo();
        String codeDir = "/data/app/" + packageName;
        ai.setBaseCodePath(codeDir + "/base.dex");
        ai.setSplitCodePaths(new String[] {codeDir + "/split-1.dex", codeDir + "/split-2.dex"});
        ai.dataDir = "/data/user/" + userId + "/" + packageName;
        ai.packageName = packageName;
        return ai;
    }

    private static class TestData {
        private final PackageInfo mPackageInfo;
        private final String mLoaderIsa;

        private TestData(String  packageName, String loaderIsa, int userId) {
            mPackageInfo = getMockPackageInfo(packageName, userId);
            mLoaderIsa = loaderIsa;
        }

        private String getPackageName() {
            return mPackageInfo.packageName;
        }

        List<String> getSecondaryDexPaths() {
            List<String> paths = new ArrayList<>();
            paths.add(mPackageInfo.applicationInfo.dataDir + "/secondary1.dex");
            paths.add(mPackageInfo.applicationInfo.dataDir + "/secondary2.dex");
            paths.add(mPackageInfo.applicationInfo.dataDir + "/secondary3.dex");
            return paths;
        }

        List<String> getSecondaryDexPathsForOwnUse() {
            List<String> paths = new ArrayList<>();
            paths.add(mPackageInfo.applicationInfo.dataDir + "/secondary4.dex");
            paths.add(mPackageInfo.applicationInfo.dataDir + "/secondary5.dex");
            return paths;
        }

        List<String> getBaseAndSplitDexPaths() {
            List<String> paths = new ArrayList<>();
            paths.add(mPackageInfo.applicationInfo.sourceDir);
            for (String split : mPackageInfo.applicationInfo.splitSourceDirs) {
                paths.add(split);
            }
            return paths;
        }
    }
}
