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

import static com.android.server.pm.dex.PackageDexUsage.DexUseInfo;
import static com.android.server.pm.dex.PackageDexUsage.PackageUseInfo;
import static com.android.server.pm.dex.PackageDynamicCodeLoading.DynamicCodeFile;
import static com.android.server.pm.dex.PackageDynamicCodeLoading.PackageDynamicCode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.UserHandle;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.pm.Installer;

import dalvik.system.DelegateLastClassLoader;
import dalvik.system.PathClassLoader;
import dalvik.system.VMRuntime;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.quality.Strictness;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class DexManagerTests {
    private static final String PATH_CLASS_LOADER_NAME = PathClassLoader.class.getName();
    private static final String DELEGATE_LAST_CLASS_LOADER_NAME =
            DelegateLastClassLoader.class.getName();
    private static final String UNSUPPORTED_CLASS_LOADER_NAME = "unsupported.class_loader";

    @Rule public MockitoRule mockito = MockitoJUnit.rule().strictness(Strictness.STRICT_STUBS);
    @Mock Installer mInstaller;
    @Mock IPackageManager mPM;
    private final Object mInstallLock = new Object();

    private DexManager mDexManager;

    private TestData mFooUser0;
    private TestData mBarUser0;
    private TestData mBarUser1;
    private TestData mInvalidIsa;
    private TestData mDoesNotExist;

    private TestData mBarUser0UnsupportedClassLoader;
    private TestData mBarUser0DelegateLastClassLoader;

    private int mUser0;
    private int mUser1;

    @Before
    public void setup() {
        mUser0 = 0;
        mUser1 = 1;

        String isa = VMRuntime.getInstructionSet(Build.SUPPORTED_ABIS[0]);
        String foo = "foo";
        String bar = "bar";

        mFooUser0 = new TestData(foo, isa, mUser0, PATH_CLASS_LOADER_NAME);
        mBarUser0 = new TestData(bar, isa, mUser0, PATH_CLASS_LOADER_NAME);
        mBarUser1 = new TestData(bar, isa, mUser1, PATH_CLASS_LOADER_NAME);
        mInvalidIsa = new TestData("INVALID", "INVALID_ISA", mUser0);
        mDoesNotExist = new TestData("DOES.NOT.EXIST", isa, mUser1);

        mBarUser0UnsupportedClassLoader = new TestData(bar, isa, mUser0,
                UNSUPPORTED_CLASS_LOADER_NAME);
        mBarUser0DelegateLastClassLoader = new TestData(bar, isa, mUser0,
                DELEGATE_LAST_CLASS_LOADER_NAME);

        mDexManager = new DexManager(/*Context*/ null, mPM, /*PackageDexOptimizer*/ null,
                mInstaller, mInstallLock);

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
        assertNoUseInfo(mFooUser0);

        // A package loading its own code is not stored as DCL.
        assertNoDclInfo(mFooUser0);
    }

    @Test
    public void testNotifyPrimaryForeignUse() {
        // Foo loads Bar main apks.
        notifyDexLoad(mFooUser0, mBarUser0.getBaseAndSplitDexPaths(), mUser0);

        // Bar is used by others now and should be in our records
        PackageUseInfo pui = getPackageUseInfo(mBarUser0);
        assertIsUsedByOtherApps(mBarUser0, pui, true);
        assertTrue(pui.getDexUseInfoMap().isEmpty());

        assertHasDclInfo(mBarUser0, mFooUser0, mBarUser0.getBaseAndSplitDexPaths());
    }

    @Test
    public void testNotifySecondary() {
        // Foo loads its own secondary files.
        List<String> fooSecondaries = mFooUser0.getSecondaryDexPaths();
        notifyDexLoad(mFooUser0, fooSecondaries, mUser0);

        PackageUseInfo pui = getPackageUseInfo(mFooUser0);
        assertIsUsedByOtherApps(mFooUser0, pui, false);
        assertEquals(fooSecondaries.size(), pui.getDexUseInfoMap().size());
        assertSecondaryUse(mFooUser0, pui, fooSecondaries, /*isUsedByOtherApps*/false, mUser0);

        assertHasDclInfo(mFooUser0, mFooUser0, fooSecondaries);
    }

    @Test
    public void testNotifySecondaryForeign() {
        // Foo loads bar secondary files.
        List<String> barSecondaries = mBarUser0.getSecondaryDexPaths();
        notifyDexLoad(mFooUser0, barSecondaries, mUser0);

        PackageUseInfo pui = getPackageUseInfo(mBarUser0);
        assertIsUsedByOtherApps(mBarUser0, pui, false);
        assertEquals(barSecondaries.size(), pui.getDexUseInfoMap().size());
        assertSecondaryUse(mFooUser0, pui, barSecondaries, /*isUsedByOtherApps*/true, mUser0);

        assertHasDclInfo(mBarUser0, mFooUser0, barSecondaries);
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
        assertIsUsedByOtherApps(mBarUser0, pui, true);
        assertEquals(barSecondaries.size() + barSecondariesForOwnUse.size(),
                pui.getDexUseInfoMap().size());

        assertSecondaryUse(mFooUser0, pui, barSecondaries, /*isUsedByOtherApps*/true, mUser0);
        assertSecondaryUse(mFooUser0, pui, barSecondariesForOwnUse,
                /*isUsedByOtherApps*/false, mUser0);

        // Check foo usage. Should not be used by other app.
        pui = getPackageUseInfo(mFooUser0);
        assertIsUsedByOtherApps(mFooUser0, pui, false);
        assertEquals(fooSecondaries.size(), pui.getDexUseInfoMap().size());
        assertSecondaryUse(mFooUser0, pui, fooSecondaries, /*isUsedByOtherApps*/false, mUser0);
    }

    @Test
    public void testNoNotify() {
        // Assert we don't get back data we did not previously record.
        assertNoUseInfo(mFooUser0);
        assertNoDclInfo(mFooUser0);
    }

    @Test
    public void testInvalidIsa() {
        // Notifying with an invalid ISA should be ignored.
        notifyDexLoad(mInvalidIsa, mInvalidIsa.getSecondaryDexPaths(), mUser0);
        assertNoUseInfo(mInvalidIsa);
        assertNoDclInfo(mInvalidIsa);
    }

    @Test
    public void testNotExistingPackage() {
        // Notifying about the load of a package which was previously not
        // register in DexManager#load should be ignored.
        notifyDexLoad(mDoesNotExist, mDoesNotExist.getBaseAndSplitDexPaths(), mUser0);
        assertNoUseInfo(mDoesNotExist);
        assertNoDclInfo(mDoesNotExist);
    }

    @Test
    public void testCrossUserAttempt() {
        // Bar from User1 tries to load secondary dex files from User0 Bar.
        // Request should be ignored.
        notifyDexLoad(mBarUser1, mBarUser0.getSecondaryDexPaths(), mUser1);
        assertNoUseInfo(mBarUser1);

        assertNoDclInfo(mBarUser1);
    }

    @Test
    public void testPackageNotInstalledForUser() {
        // User1 tries to load Foo which is installed for User0 but not for User1.
        // Note that the PackageManagerService already filters this out but we
        // still check that nothing goes unexpected in DexManager.
        notifyDexLoad(mBarUser0, mFooUser0.getBaseAndSplitDexPaths(), mUser1);
        assertNoUseInfo(mBarUser1);
        assertNoUseInfo(mFooUser0);

        assertNoDclInfo(mBarUser1);
        assertNoDclInfo(mFooUser0);
    }

    @Test
    public void testNotifyPackageInstallUsedByOther() {
        TestData newPackage = new TestData("newPackage",
                VMRuntime.getInstructionSet(Build.SUPPORTED_ABIS[0]), mUser0);

        List<String> newSecondaries = newPackage.getSecondaryDexPaths();
        // Before we notify about the installation of the newPackage if mFoo
        // is trying to load something from it we should not find it.
        notifyDexLoad(mFooUser0, newSecondaries, mUser0);
        assertNoUseInfo(newPackage);
        assertNoDclInfo(newPackage);

        // Notify about newPackage install and let mFoo load its dexes.
        mDexManager.notifyPackageInstalled(newPackage.mPackageInfo, mUser0);
        notifyDexLoad(mFooUser0, newSecondaries, mUser0);

        // We should get back the right info.
        PackageUseInfo pui = getPackageUseInfo(newPackage);
        assertIsUsedByOtherApps(newPackage, pui, false);
        assertEquals(newSecondaries.size(), pui.getDexUseInfoMap().size());
        assertSecondaryUse(newPackage, pui, newSecondaries, /*isUsedByOtherApps*/true, mUser0);
        assertHasDclInfo(newPackage, mFooUser0, newSecondaries);
    }

    @Test
    public void testNotifyPackageInstallSelfUse() {
        TestData newPackage = new TestData("newPackage",
                VMRuntime.getInstructionSet(Build.SUPPORTED_ABIS[0]), mUser0);

        List<String> newSecondaries = newPackage.getSecondaryDexPaths();
        // Packages should be able to find their own dex files even if the notification about
        // their installation is delayed.
        notifyDexLoad(newPackage, newSecondaries, mUser0);

        PackageUseInfo pui = getPackageUseInfo(newPackage);
        assertIsUsedByOtherApps(newPackage, pui, false);
        assertEquals(newSecondaries.size(), pui.getDexUseInfoMap().size());
        assertSecondaryUse(newPackage, pui, newSecondaries, /*isUsedByOtherApps*/false, mUser0);
        assertHasDclInfo(newPackage, newPackage, newSecondaries);
    }

    @Test
    public void testNotifyPackageUpdated() {
        // Foo loads Bar main apks.
        notifyDexLoad(mFooUser0, mBarUser0.getBaseAndSplitDexPaths(), mUser0);

        // Bar is used by others now and should be in our records.
        PackageUseInfo pui = getPackageUseInfo(mBarUser0);
        assertIsUsedByOtherApps(mBarUser0, pui, true);
        assertTrue(pui.getDexUseInfoMap().isEmpty());

        // Notify that bar is updated.
        mDexManager.notifyPackageUpdated(mBarUser0.getPackageName(),
                mBarUser0.mPackageInfo.applicationInfo.sourceDir,
                mBarUser0.mPackageInfo.applicationInfo.splitSourceDirs);

        // The usedByOtherApps flag should be clear now.
        pui = getPackageUseInfo(mBarUser0);
        assertIsUsedByOtherApps(mBarUser0, pui, false);
    }

    @Test
    public void testNotifyPackageUpdatedCodeLocations() {
        // Simulate a split update.
        String newSplit = mBarUser0.replaceLastSplit();
        List<String> newSplits = new ArrayList<>();
        newSplits.add(newSplit);

        // We shouldn't find yet the new split as we didn't notify the package update.
        notifyDexLoad(mFooUser0, newSplits, mUser0);
        assertNoUseInfo(mBarUser0);
        assertNoDclInfo(mBarUser0);

        // Notify that bar is updated. splitSourceDirs will contain the updated path.
        mDexManager.notifyPackageUpdated(mBarUser0.getPackageName(),
                mBarUser0.mPackageInfo.applicationInfo.sourceDir,
                mBarUser0.mPackageInfo.applicationInfo.splitSourceDirs);

        // Now, when the split is loaded we will find it and we should mark Bar as usedByOthers.
        notifyDexLoad(mFooUser0, newSplits, mUser0);
        PackageUseInfo pui = getPackageUseInfo(mBarUser0);
        assertIsUsedByOtherApps(newSplits, pui, true);
        assertHasDclInfo(mBarUser0, mFooUser0, newSplits);
    }

    @Test
    public void testNotifyPackageDataDestroyForOne() {
        // Bar loads its own secondary files.
        notifyDexLoad(mBarUser0, mBarUser0.getSecondaryDexPaths(), mUser0);
        notifyDexLoad(mBarUser1, mBarUser1.getSecondaryDexPaths(), mUser1);

        mDexManager.notifyPackageDataDestroyed(mBarUser0.getPackageName(), mUser0);

        // Data for user 1 should still be present
        PackageUseInfo pui = getPackageUseInfo(mBarUser1);
        assertSecondaryUse(mBarUser1, pui, mBarUser1.getSecondaryDexPaths(),
                /*isUsedByOtherApps*/false, mUser1);
        assertHasDclInfo(mBarUser1, mBarUser1, mBarUser1.getSecondaryDexPaths());

        // But not user 0
        assertNoUseInfo(mBarUser0, mUser0);
        assertNoDclInfo(mBarUser0, mUser0);
    }

    @Test
    public void testNotifyPackageDataDestroyForeignUse() {
        // Foo loads its own secondary files.
        List<String> fooSecondaries = mFooUser0.getSecondaryDexPaths();
        notifyDexLoad(mFooUser0, fooSecondaries, mUser0);

        // Bar loads Foo main apks.
        notifyDexLoad(mBarUser0, mFooUser0.getBaseAndSplitDexPaths(), mUser0);

        mDexManager.notifyPackageDataDestroyed(mFooUser0.getPackageName(), mUser0);

        // Foo should still be around since it's used by other apps but with no
        // secondary dex info.
        PackageUseInfo pui = getPackageUseInfo(mFooUser0);
        assertIsUsedByOtherApps(mFooUser0, pui, true);
        assertTrue(pui.getDexUseInfoMap().isEmpty());

        assertNoDclInfo(mFooUser0);
    }

    @Test
    public void testNotifyPackageDataDestroyComplete() {
        // Foo loads its own secondary files.
        List<String> fooSecondaries = mFooUser0.getSecondaryDexPaths();
        notifyDexLoad(mFooUser0, fooSecondaries, mUser0);

        mDexManager.notifyPackageDataDestroyed(mFooUser0.getPackageName(), mUser0);

        // Foo should not be around since all its secondary dex info were deleted
        // and it is not used by other apps.
        assertNoUseInfo(mFooUser0);
        assertNoDclInfo(mFooUser0);
    }

    @Test
    public void testNotifyPackageDataDestroyForAll() {
        // Foo loads its own secondary files.
        notifyDexLoad(mBarUser0, mBarUser0.getSecondaryDexPaths(), mUser0);
        notifyDexLoad(mBarUser1, mBarUser1.getSecondaryDexPaths(), mUser1);

        mDexManager.notifyPackageDataDestroyed(mBarUser0.getPackageName(), UserHandle.USER_ALL);

        // Bar should not be around since it was removed for all users.
        assertNoUseInfo(mBarUser0);
        assertNoDclInfo(mBarUser0);
    }

    @Test
    public void testNotifyFrameworkLoad() {
        String frameworkDex = "/system/framework/com.android.location.provider.jar";
        // Load a dex file from framework.
        notifyDexLoad(mFooUser0, Arrays.asList(frameworkDex), mUser0);
        // The dex file should not be recognized as owned by the package.
        assertFalse(mDexManager.hasInfoOnPackage(mFooUser0.getPackageName()));

        assertNull(getPackageDynamicCodeInfo(mFooUser0));
    }

    @Test
    public void testNotifySecondaryFromProtected() {
        // Foo loads its own secondary files.
        List<String> fooSecondaries = mFooUser0.getSecondaryDexPathsFromProtectedDirs();
        notifyDexLoad(mFooUser0, fooSecondaries, mUser0);

        PackageUseInfo pui = getPackageUseInfo(mFooUser0);
        assertIsUsedByOtherApps(mFooUser0, pui, false);
        assertEquals(fooSecondaries.size(), pui.getDexUseInfoMap().size());
        assertSecondaryUse(mFooUser0, pui, fooSecondaries, /*isUsedByOtherApps*/false, mUser0);

        assertHasDclInfo(mFooUser0, mFooUser0, fooSecondaries);
    }

    @Test
    public void testNotifyUnsupportedClassLoader() {
        List<String> secondaries = mBarUser0UnsupportedClassLoader.getSecondaryDexPaths();
        notifyDexLoad(mBarUser0UnsupportedClassLoader, secondaries, mUser0);

        // We don't record the dex usage
        assertNoUseInfo(mBarUser0UnsupportedClassLoader);

        // But we do record this as an intance of dynamic code loading
        assertHasDclInfo(
                mBarUser0UnsupportedClassLoader, mBarUser0UnsupportedClassLoader, secondaries);
    }

    @Test
    public void testNotifySupportedAndUnsupportedClassLoader() {
        String classPath = String.join(File.pathSeparator, mBarUser0.getSecondaryDexPaths());
        List<String> classLoaders =
                Arrays.asList(PATH_CLASS_LOADER_NAME, UNSUPPORTED_CLASS_LOADER_NAME);
        List<String> classPaths = Arrays.asList(classPath, classPath);
        notifyDexLoad(mBarUser0, classLoaders, classPaths, mUser0);

        assertNoUseInfo(mBarUser0);

        assertHasDclInfo(mBarUser0, mBarUser0, mBarUser0.getSecondaryDexPaths());
    }

    @Test
    public void testNotifyNullClassPath() {
        notifyDexLoad(mBarUser0, null, mUser0);

        assertNoUseInfo(mBarUser0);
        assertNoDclInfo(mBarUser0);
    }

    @Test
    public void testNotifyVariableClassLoader() {
        // Record bar secondaries with the default PathClassLoader.
        List<String> secondaries = mBarUser0.getSecondaryDexPaths();

        notifyDexLoad(mBarUser0, secondaries, mUser0);
        PackageUseInfo pui = getPackageUseInfo(mBarUser0);
        assertIsUsedByOtherApps(mBarUser0, pui, false);
        assertEquals(secondaries.size(), pui.getDexUseInfoMap().size());
        assertSecondaryUse(mFooUser0, pui, secondaries, /*isUsedByOtherApps*/false, mUser0);

        // Record bar secondaries again with a different class loader. This will change the context.
        notifyDexLoad(mBarUser0DelegateLastClassLoader, secondaries, mUser0);

        pui = getPackageUseInfo(mBarUser0);
        assertIsUsedByOtherApps(mBarUser0, pui, false);
        assertEquals(secondaries.size(), pui.getDexUseInfoMap().size());
        // We expect that all the contexts to be changed to variable now.
        String[] expectedContexts =
                Collections.nCopies(secondaries.size(),
                        PackageDexUsage.VARIABLE_CLASS_LOADER_CONTEXT).toArray(new String[0]);
        assertSecondaryUse(mFooUser0, pui, secondaries, /*isUsedByOtherApps*/false, mUser0,
                expectedContexts);
    }

    @Test
    public void testNotifyUnsupportedClassLoaderDoesNotChangeExisting() {
        List<String> secondaries = mBarUser0.getSecondaryDexPaths();

        notifyDexLoad(mBarUser0, secondaries, mUser0);
        PackageUseInfo pui = getPackageUseInfo(mBarUser0);
        assertSecondaryUse(mBarUser0, pui, secondaries, /*isUsedByOtherApps*/false, mUser0);
        assertHasDclInfo(mBarUser0, mBarUser0, secondaries);

        // Record bar secondaries again with an unsupported class loader. This should not change the
        // context.
        notifyDexLoad(mBarUser0UnsupportedClassLoader, secondaries, mUser0);
        pui = getPackageUseInfo(mBarUser0);
        assertSecondaryUse(mBarUser0, pui, secondaries, /*isUsedByOtherApps*/false, mUser0);
        assertHasDclInfo(mBarUser0, mBarUser0, secondaries);
    }

    private void assertSecondaryUse(TestData testData, PackageUseInfo pui,
            List<String> secondaries, boolean isUsedByOtherApps, int ownerUserId,
            String[] expectedContexts) {
        assertNotNull(expectedContexts);
        assertEquals(expectedContexts.length, secondaries.size());
        int index = 0;
        for (String dex : secondaries) {
            DexUseInfo dui = pui.getDexUseInfoMap().get(dex);
            assertNotNull(dui);
            assertEquals(isUsedByOtherApps, dui.isUsedByOtherApps());
            assertEquals(ownerUserId, dui.getOwnerUserId());
            assertEquals(1, dui.getLoaderIsas().size());
            assertTrue(dui.getLoaderIsas().contains(testData.mLoaderIsa));
            assertEquals(expectedContexts[index++], dui.getClassLoaderContext());
        }
    }
    private void assertSecondaryUse(TestData testData, PackageUseInfo pui,
            List<String> secondaries, boolean isUsedByOtherApps, int ownerUserId) {
        String[] expectedContexts = DexoptUtils.processContextForDexLoad(
                Arrays.asList(testData.mClassLoader),
                Arrays.asList(String.join(File.pathSeparator, secondaries)));
        assertSecondaryUse(testData, pui, secondaries, isUsedByOtherApps, ownerUserId,
                expectedContexts);
    }

    private void assertIsUsedByOtherApps(TestData testData, PackageUseInfo pui,
            boolean isUsedByOtherApps) {
        assertIsUsedByOtherApps(testData.getBaseAndSplitDexPaths(), pui, isUsedByOtherApps);
    }

    private void assertIsUsedByOtherApps(List<String> codePaths, PackageUseInfo pui,
            boolean isUsedByOtherApps) {
        for (String codePath : codePaths) {
            assertEquals(codePath, isUsedByOtherApps, pui.isUsedByOtherApps(codePath));
        }
    }
    private void notifyDexLoad(TestData testData, List<String> dexPaths, int loaderUserId) {
        // By default, assume a single class loader in the chain.
        // This makes writing tests much easier.
        List<String> classLoaders = Arrays.asList(testData.mClassLoader);
        List<String> classPaths = (dexPaths == null)
                                  ? Arrays.asList((String) null)
                                  : Arrays.asList(String.join(File.pathSeparator, dexPaths));
        notifyDexLoad(testData, classLoaders, classPaths, loaderUserId);
    }

    private void notifyDexLoad(TestData testData, List<String> classLoaders,
            List<String> classPaths, int loaderUserId) {
        // We call the internal function so any exceptions thrown cause test failures.
        mDexManager.notifyDexLoadInternal(testData.mPackageInfo.applicationInfo, classLoaders,
                classPaths, testData.mLoaderIsa, loaderUserId);
    }

    private PackageUseInfo getPackageUseInfo(TestData testData) {
        assertTrue(mDexManager.hasInfoOnPackage(testData.getPackageName()));
        PackageUseInfo pui = mDexManager.getPackageUseInfoOrDefault(testData.getPackageName());
        assertNotNull(pui);
        return pui;
    }

    private PackageDynamicCode getPackageDynamicCodeInfo(TestData testData) {
        return mDexManager.getDexLogger().getPackageDynamicCodeInfo(testData.getPackageName());
    }

    private void assertNoUseInfo(TestData testData) {
        assertFalse(mDexManager.hasInfoOnPackage(testData.getPackageName()));
    }

    private void assertNoUseInfo(TestData testData, int userId) {
        if (!mDexManager.hasInfoOnPackage(testData.getPackageName())) {
            return;
        }
        PackageUseInfo pui = getPackageUseInfo(testData);
        for (DexUseInfo dexUseInfo : pui.getDexUseInfoMap().values()) {
            assertNotEquals(userId, dexUseInfo.getOwnerUserId());
        }
    }

    private void assertNoDclInfo(TestData testData) {
        assertNull(getPackageDynamicCodeInfo(testData));
    }

    private void assertNoDclInfo(TestData testData, int userId) {
        PackageDynamicCode info = getPackageDynamicCodeInfo(testData);
        if (info == null) {
            return;
        }

        for (DynamicCodeFile fileInfo : info.mFileUsageMap.values()) {
            assertNotEquals(userId, fileInfo.mUserId);
        }
    }

    private void assertHasDclInfo(TestData owner, TestData loader, List<String> paths) {
        PackageDynamicCode info = getPackageDynamicCodeInfo(owner);
        assertNotNull("No DCL data for owner " + owner.getPackageName(), info);
        for (String path : paths) {
            DynamicCodeFile fileInfo = info.mFileUsageMap.get(path);
            assertNotNull("No DCL data for path " + path, fileInfo);
            assertEquals(PackageDynamicCodeLoading.FILE_TYPE_DEX, fileInfo.mFileType);
            assertEquals(owner.mUserId, fileInfo.mUserId);
            assertTrue("No DCL data for loader " + loader.getPackageName(),
                    fileInfo.mLoadingPackages.contains(loader.getPackageName()));
        }
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
        ai.deviceProtectedDataDir = "/data/user_de/" + userId + "/" + packageName;
        ai.credentialProtectedDataDir = "/data/user_ce/" + userId + "/" + packageName;
        ai.packageName = packageName;
        return ai;
    }

    private static class TestData {
        private final PackageInfo mPackageInfo;
        private final String mLoaderIsa;
        private final String mClassLoader;
        private final int mUserId;

        private TestData(String packageName, String loaderIsa, int userId, String classLoader) {
            mPackageInfo = getMockPackageInfo(packageName, userId);
            mLoaderIsa = loaderIsa;
            mClassLoader = classLoader;
            mUserId = userId;
        }

        private TestData(String packageName, String loaderIsa, int userId) {
            this(packageName, loaderIsa, userId, PATH_CLASS_LOADER_NAME);
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

        List<String> getSecondaryDexPathsFromProtectedDirs() {
            List<String> paths = new ArrayList<>();
            paths.add(mPackageInfo.applicationInfo.deviceProtectedDataDir + "/secondary6.dex");
            paths.add(mPackageInfo.applicationInfo.credentialProtectedDataDir + "/secondary7.dex");
            return paths;
        }

        List<String> getBaseAndSplitDexPaths() {
            List<String> paths = new ArrayList<>();
            paths.add(mPackageInfo.applicationInfo.sourceDir);
            Collections.addAll(paths, mPackageInfo.applicationInfo.splitSourceDirs);
            return paths;
        }

        String replaceLastSplit() {
            int length = mPackageInfo.applicationInfo.splitSourceDirs.length;
            // Add an extra bogus dex extension to simulate a new split name.
            mPackageInfo.applicationInfo.splitSourceDirs[length - 1] += ".dex";
            return mPackageInfo.applicationInfo.splitSourceDirs[length - 1];
        }
    }

    private boolean shouldPackageRunOob(
            boolean isDefaultEnabled, String defaultWhitelist, String overrideEnabled,
            String overrideWhitelist, Collection<String> packageNamesInSameProcess) {
        return DexManager.isPackageSelectedToRunOobInternal(
                isDefaultEnabled, defaultWhitelist, overrideEnabled, overrideWhitelist,
                packageNamesInSameProcess);
    }

    @Test
    public void testOobPackageSelectionSwitch() {
        // Feature is off by default, not overriden
        assertFalse(shouldPackageRunOob(false, "ALL", null, null, null));

        // Feature is off by default, overriden
        assertTrue(shouldPackageRunOob(false, "ALL", "true", "ALL", null));
        assertFalse(shouldPackageRunOob(false, "ALL", "false", null, null));
        assertFalse(shouldPackageRunOob(false, "ALL", "false", "ALL", null));
        assertFalse(shouldPackageRunOob(false, "ALL", "false", null, null));

        // Feature is on by default, not overriden
        assertTrue(shouldPackageRunOob(true, "ALL", null, null, null));
        assertTrue(shouldPackageRunOob(true, "ALL", null, null, null));
        assertTrue(shouldPackageRunOob(true, "ALL", null, "ALL", null));

        // Feature is on by default, overriden
        assertTrue(shouldPackageRunOob(true, "ALL", "true", null, null));
        assertTrue(shouldPackageRunOob(true, "ALL", "true", "ALL", null));
        assertFalse(shouldPackageRunOob(true, "ALL", "false", null, null));
        assertFalse(shouldPackageRunOob(true, "ALL", "false", "ALL", null));
    }

    @Test
    public void testOobPackageSelectionWhitelist() {
        // Various whitelist of apps to run in OOB mode.
        final String kWhitelistApp0 = "com.priv.app0";
        final String kWhitelistApp1 = "com.priv.app1";
        final String kWhitelistApp2 = "com.priv.app2";
        final String kWhitelistApp1AndApp2 = "com.priv.app1,com.priv.app2";

        // Packages that shares the targeting process.
        final Collection<String> runningPackages = Arrays.asList("com.priv.app1", "com.priv.app2");

        // Feature is off, whitelist does not matter
        assertFalse(shouldPackageRunOob(false, kWhitelistApp0, null, null, runningPackages));
        assertFalse(shouldPackageRunOob(false, kWhitelistApp1, null, null, runningPackages));
        assertFalse(shouldPackageRunOob(false, "", null, kWhitelistApp1, runningPackages));
        assertFalse(shouldPackageRunOob(false, "", null, "ALL", runningPackages));
        assertFalse(shouldPackageRunOob(false, "ALL", null, "ALL", runningPackages));
        assertFalse(shouldPackageRunOob(false, "ALL", null, "", runningPackages));

        // Feature is on, app not in default or overridden whitelist
        assertFalse(shouldPackageRunOob(true, kWhitelistApp0, null, null, runningPackages));
        assertFalse(shouldPackageRunOob(true, "", null, kWhitelistApp0, runningPackages));
        assertFalse(shouldPackageRunOob(true, "ALL", null, kWhitelistApp0, runningPackages));

        // Feature is on, app in default or overridden whitelist
        assertTrue(shouldPackageRunOob(true, kWhitelistApp1, null, null, runningPackages));
        assertTrue(shouldPackageRunOob(true, kWhitelistApp2, null, null, runningPackages));
        assertTrue(shouldPackageRunOob(true, kWhitelistApp1AndApp2, null, null, runningPackages));
        assertTrue(shouldPackageRunOob(true, kWhitelistApp1, null, "ALL", runningPackages));
        assertTrue(shouldPackageRunOob(true, "", null, kWhitelistApp1, runningPackages));
        assertTrue(shouldPackageRunOob(true, "ALL", null, kWhitelistApp1, runningPackages));
    }
}
