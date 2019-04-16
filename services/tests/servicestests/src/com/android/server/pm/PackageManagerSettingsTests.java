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

package com.android.server.pm;

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageParser;
import android.content.pm.PackageUserState;
import android.content.pm.UserInfo;
import android.os.BaseBundle;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.os.UserManagerInternal;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.LongSparseArray;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.AtomicFile;
import com.android.server.LocalServices;
import com.android.server.pm.permission.PermissionManagerInternal;
import com.android.server.pm.permission.PermissionManagerService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class PackageManagerSettingsTests {
    private static final String PACKAGE_NAME_2 = "com.android.app2";
    private static final String PACKAGE_NAME_3 = "com.android.app3";
    private static final String PACKAGE_NAME_1 = "com.android.app1";
    public static final String TAG = "PackageManagerSettingsTests";
    protected final String PREFIX = "android.content.pm";

    /** make sure our initialized KeySetManagerService metadata matches packages.xml */
    @Test
    public void testReadKeySetSettings()
            throws ReflectiveOperationException, IllegalAccessException {
        /* write out files and read */
        writeOldFiles();
        final Context context = InstrumentationRegistry.getContext();
        final Object lock = new Object();
        PermissionManagerInternal pmInt = PermissionManagerService.create(context, null, lock);
        Settings settings =
                new Settings(context.getFilesDir(), pmInt.getPermissionSettings(), lock);
        assertThat(settings.readLPw(createFakeUsers()), is(true));
        verifyKeySetMetaData(settings);
    }

    /** read in data, write it out, and read it back in.  Verify same. */
    @Test
    public void testWriteKeySetSettings()
            throws ReflectiveOperationException, IllegalAccessException {
        // write out files and read
        writeOldFiles();
        final Context context = InstrumentationRegistry.getContext();
        final Object lock = new Object();
        PermissionManagerInternal pmInt = PermissionManagerService.create(context, null, lock);
        Settings settings =
                new Settings(context.getFilesDir(), pmInt.getPermissionSettings(), lock);
        assertThat(settings.readLPw(createFakeUsers()), is(true));

        // write out, read back in and verify the same
        settings.writeLPr();
        assertThat(settings.readLPw(createFakeUsers()), is(true));
        verifyKeySetMetaData(settings);
    }

    @Test
    public void testSettingsReadOld() {
        // Write the package files and make sure they're parsed properly the first time
        writeOldFiles();
        final Context context = InstrumentationRegistry.getContext();
        final Object lock = new Object();
        PermissionManagerInternal pmInt = PermissionManagerService.create(context, null, lock);
        Settings settings =
                new Settings(context.getFilesDir(), pmInt.getPermissionSettings(), lock);
        assertThat(settings.readLPw(createFakeUsers()), is(true));
        assertThat(settings.getPackageLPr(PACKAGE_NAME_3), is(notNullValue()));
        assertThat(settings.getPackageLPr(PACKAGE_NAME_1), is(notNullValue()));

        PackageSetting ps = settings.getPackageLPr(PACKAGE_NAME_1);
        assertThat(ps.getEnabled(0), is(COMPONENT_ENABLED_STATE_DEFAULT));
        assertThat(ps.getNotLaunched(0), is(true));

        ps = settings.getPackageLPr(PACKAGE_NAME_2);
        assertThat(ps.getStopped(0), is(false));
        assertThat(ps.getEnabled(0), is(COMPONENT_ENABLED_STATE_DISABLED_USER));
        assertThat(ps.getEnabled(1), is(COMPONENT_ENABLED_STATE_DEFAULT));
    }

    @Test
    public void testNewPackageRestrictionsFile() throws ReflectiveOperationException {
        // Write the package files and make sure they're parsed properly the first time
        writeOldFiles();
        final Context context = InstrumentationRegistry.getContext();
        final Object lock = new Object();
        PermissionManagerInternal pmInt = PermissionManagerService.create(context, null, lock);
        Settings settings =
                new Settings(context.getFilesDir(), pmInt.getPermissionSettings(), lock);
        assertThat(settings.readLPw(createFakeUsers()), is(true));
        settings.writeLPr();

        // Create Settings again to make it read from the new files
        settings =
                new Settings(context.getFilesDir(), pmInt.getPermissionSettings(), lock);
        assertThat(settings.readLPw(createFakeUsers()), is(true));

        PackageSetting ps = settings.getPackageLPr(PACKAGE_NAME_2);
        assertThat(ps.getEnabled(0), is(COMPONENT_ENABLED_STATE_DISABLED_USER));
        assertThat(ps.getEnabled(1), is(COMPONENT_ENABLED_STATE_DEFAULT));
    }

    private PersistableBundle getPersistableBundle(String packageName, long longVal,
            double doubleVal, boolean boolVal, String textVal) {
        final PersistableBundle bundle = new PersistableBundle();
        bundle.putString(packageName + ".TEXT_VALUE", textVal);
        bundle.putLong(packageName + ".LONG_VALUE", longVal);
        bundle.putBoolean(packageName + ".BOOL_VALUE", boolVal);
        bundle.putDouble(packageName + ".DOUBLE_VALUE", doubleVal);
        return bundle;
    }

    @Test
    public void testReadPackageRestrictions_oldSuspendInfo() {
        writePackageRestrictions_oldSuspendInfoXml(0);
        final Object lock = new Object();
        final Context context = InstrumentationRegistry.getTargetContext();
        final Settings settingsUnderTest = new Settings(context.getFilesDir(), null, lock);
        settingsUnderTest.mPackages.put(PACKAGE_NAME_1, createPackageSetting(PACKAGE_NAME_1));
        settingsUnderTest.mPackages.put(PACKAGE_NAME_2, createPackageSetting(PACKAGE_NAME_2));
        settingsUnderTest.readPackageRestrictionsLPr(0);

        final PackageSetting ps1 = settingsUnderTest.mPackages.get(PACKAGE_NAME_1);
        final PackageUserState packageUserState1 = ps1.readUserState(0);
        assertThat(packageUserState1.suspended, is(true));
        assertThat("android".equals(packageUserState1.suspendingPackage), is(true));

        final PackageSetting ps2 = settingsUnderTest.mPackages.get(PACKAGE_NAME_2);
        final PackageUserState packageUserState2 = ps2.readUserState(0);
        assertThat(packageUserState2.suspended, is(false));
        assertThat(packageUserState2.suspendingPackage, is(nullValue()));
    }

    @Test
    public void testReadWritePackageRestrictions_newSuspendInfo() {
        final Context context = InstrumentationRegistry.getTargetContext();
        final Settings settingsUnderTest = new Settings(context.getFilesDir(), null, new Object());
        final PackageSetting ps1 = createPackageSetting(PACKAGE_NAME_1);
        final PackageSetting ps2 = createPackageSetting(PACKAGE_NAME_2);
        final PackageSetting ps3 = createPackageSetting(PACKAGE_NAME_3);

        final PersistableBundle appExtras1 = getPersistableBundle(
                PACKAGE_NAME_1, 1L, 0.01, true, "appString1");
        final PersistableBundle launcherExtras1 = getPersistableBundle(
                PACKAGE_NAME_1, 10L, 0.1, false, "launcherString1");
        ps1.setSuspended(true, "suspendingPackage1", "dialogMsg1", appExtras1, launcherExtras1, 0);
        settingsUnderTest.mPackages.put(PACKAGE_NAME_1, ps1);

        ps2.setSuspended(true, "suspendingPackage2", "dialogMsg2", null, null, 0);
        settingsUnderTest.mPackages.put(PACKAGE_NAME_2, ps2);

        ps3.setSuspended(false, "irrelevant", "irrevelant2", null, null, 0);
        settingsUnderTest.mPackages.put(PACKAGE_NAME_3, ps3);

        settingsUnderTest.writePackageRestrictionsLPr(0);

        settingsUnderTest.mPackages.clear();
        settingsUnderTest.mPackages.put(PACKAGE_NAME_1, createPackageSetting(PACKAGE_NAME_1));
        settingsUnderTest.mPackages.put(PACKAGE_NAME_2, createPackageSetting(PACKAGE_NAME_2));
        settingsUnderTest.mPackages.put(PACKAGE_NAME_3, createPackageSetting(PACKAGE_NAME_3));
        // now read and verify
        settingsUnderTest.readPackageRestrictionsLPr(0);
        final PackageUserState readPus1 = settingsUnderTest.mPackages.get(PACKAGE_NAME_1).
                readUserState(0);
        assertThat(readPus1.suspended, is(true));
        assertThat(readPus1.suspendingPackage, equalTo("suspendingPackage1"));
        assertThat(readPus1.dialogMessage, equalTo("dialogMsg1"));
        assertThat(BaseBundle.kindofEquals(readPus1.suspendedAppExtras, appExtras1), is(true));
        assertThat(BaseBundle.kindofEquals(readPus1.suspendedLauncherExtras, launcherExtras1),
                is(true));

        final PackageUserState readPus2 = settingsUnderTest.mPackages.get(PACKAGE_NAME_2).
                readUserState(0);
        assertThat(readPus2.suspended, is(true));
        assertThat(readPus2.suspendingPackage, equalTo("suspendingPackage2"));
        assertThat(readPus2.dialogMessage, equalTo("dialogMsg2"));
        assertThat(readPus2.suspendedAppExtras, is(nullValue()));
        assertThat(readPus2.suspendedLauncherExtras, is(nullValue()));

        final PackageUserState readPus3 = settingsUnderTest.mPackages.get(PACKAGE_NAME_3).
                readUserState(0);
        assertThat(readPus3.suspended, is(false));
        assertThat(readPus3.suspendingPackage, is(nullValue()));
        assertThat(readPus3.dialogMessage, is(nullValue()));
        assertThat(readPus3.suspendedAppExtras, is(nullValue()));
        assertThat(readPus3.suspendedLauncherExtras, is(nullValue()));
    }

    @Test
    public void testPackageRestrictionsSuspendedDefault() {
        final PackageSetting defaultSetting =  createPackageSetting(PACKAGE_NAME_1);
        assertThat(defaultSetting.getSuspended(0), is(false));
    }

    @Test
    public void testEnableDisable() {
        // Write the package files and make sure they're parsed properly the first time
        writeOldFiles();
        final Context context = InstrumentationRegistry.getContext();
        final Object lock = new Object();
        PermissionManagerInternal pmInt = PermissionManagerService.create(context, null, lock);
        Settings settings =
                new Settings(context.getFilesDir(), pmInt.getPermissionSettings(), lock);
        assertThat(settings.readLPw(createFakeUsers()), is(true));

        // Enable/Disable a package
        PackageSetting ps = settings.getPackageLPr(PACKAGE_NAME_1);
        ps.setEnabled(COMPONENT_ENABLED_STATE_DISABLED, 0, null);
        ps.setEnabled(COMPONENT_ENABLED_STATE_ENABLED, 1, null);
        assertThat(ps.getEnabled(0), is(COMPONENT_ENABLED_STATE_DISABLED));
        assertThat(ps.getEnabled(1), is(COMPONENT_ENABLED_STATE_ENABLED));

        // Enable/Disable a component
        ArraySet<String> components = new ArraySet<String>();
        String component1 = PACKAGE_NAME_1 + "/.Component1";
        components.add(component1);
        ps.setDisabledComponents(components, 0);
        ArraySet<String> componentsDisabled = ps.getDisabledComponents(0);
        assertThat(componentsDisabled.size(), is(1));
        assertThat(componentsDisabled.toArray()[0], is(component1));
        boolean hasEnabled =
                ps.getEnabledComponents(0) != null && ps.getEnabledComponents(1).size() > 0;
        assertThat(hasEnabled, is(false));

        // User 1 should not have any disabled components
        boolean hasDisabled =
                ps.getDisabledComponents(1) != null && ps.getDisabledComponents(1).size() > 0;
        assertThat(hasDisabled, is(false));
        ps.setEnabledComponents(components, 1);
        assertThat(ps.getEnabledComponents(1).size(), is(1));
        hasEnabled = ps.getEnabledComponents(0) != null && ps.getEnabledComponents(0).size() > 0;
        assertThat(hasEnabled, is(false));
    }

    private static final String PACKAGE_NAME = "com.android.bar";
    private static final String REAL_PACKAGE_NAME = "com.android.foo";
    private static final String PARENT_PACKAGE_NAME = "com.android.bar.parent";
    private static final String CHILD_PACKAGE_NAME_01 = "com.android.bar.child01";
    private static final String CHILD_PACKAGE_NAME_02 = "com.android.bar.child02";
    private static final String CHILD_PACKAGE_NAME_03 = "com.android.bar.child03";
    private static final File INITIAL_CODE_PATH =
            new File(InstrumentationRegistry.getContext().getFilesDir(), "com.android.bar-1");
    private static final File UPDATED_CODE_PATH =
            new File(InstrumentationRegistry.getContext().getFilesDir(), "com.android.bar-2");
    private static final long INITIAL_VERSION_CODE = 10023L;
    private static final long UPDATED_VERSION_CODE = 10025L;

    @Test
    public void testPackageStateCopy01() {
        final List<String> childPackageNames = new ArrayList<>();
        childPackageNames.add(CHILD_PACKAGE_NAME_01);
        childPackageNames.add(CHILD_PACKAGE_NAME_02);
        childPackageNames.add(CHILD_PACKAGE_NAME_03);
        final PackageSetting origPkgSetting01 = new PackageSetting(
                PACKAGE_NAME,
                REAL_PACKAGE_NAME,
                INITIAL_CODE_PATH /*codePath*/,
                INITIAL_CODE_PATH /*resourcePath*/,
                null /*legacyNativeLibraryPathString*/,
                "x86_64" /*primaryCpuAbiString*/,
                "x86" /*secondaryCpuAbiString*/,
                null /*cpuAbiOverrideString*/,
                INITIAL_VERSION_CODE,
                ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_HAS_CODE,
                ApplicationInfo.PRIVATE_FLAG_PRIVILEGED|ApplicationInfo.PRIVATE_FLAG_HIDDEN,
                PARENT_PACKAGE_NAME,
                childPackageNames,
                0,
                null /*usesStaticLibraries*/,
                null /*usesStaticLibrariesVersions*/);
        final PackageSetting testPkgSetting01 = new PackageSetting(origPkgSetting01);
        verifySettingCopy(origPkgSetting01, testPkgSetting01);
    }

    @Test
    public void testPackageStateCopy02() {
        final List<String> childPackageNames = new ArrayList<>();
        childPackageNames.add(CHILD_PACKAGE_NAME_01);
        childPackageNames.add(CHILD_PACKAGE_NAME_02);
        childPackageNames.add(CHILD_PACKAGE_NAME_03);
        final PackageSetting origPkgSetting01 = new PackageSetting(
                PACKAGE_NAME /*pkgName*/,
                REAL_PACKAGE_NAME /*realPkgName*/,
                INITIAL_CODE_PATH /*codePath*/,
                INITIAL_CODE_PATH /*resourcePath*/,
                null /*legacyNativeLibraryPathString*/,
                "x86_64" /*primaryCpuAbiString*/,
                "x86" /*secondaryCpuAbiString*/,
                null /*cpuAbiOverrideString*/,
                INITIAL_VERSION_CODE,
                ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_HAS_CODE,
                ApplicationInfo.PRIVATE_FLAG_PRIVILEGED|ApplicationInfo.PRIVATE_FLAG_HIDDEN,
                PARENT_PACKAGE_NAME,
                childPackageNames,
                0,
                null /*usesStaticLibraries*/,
                null /*usesStaticLibrariesVersions*/);
        final PackageSetting testPkgSetting01 = new PackageSetting(
                PACKAGE_NAME /*pkgName*/,
                REAL_PACKAGE_NAME /*realPkgName*/,
                UPDATED_CODE_PATH /*codePath*/,
                UPDATED_CODE_PATH /*resourcePath*/,
                null /*legacyNativeLibraryPathString*/,
                null /*primaryCpuAbiString*/,
                null /*secondaryCpuAbiString*/,
                null /*cpuAbiOverrideString*/,
                UPDATED_VERSION_CODE,
                0 /*pkgFlags*/,
                0 /*pkgPrivateFlags*/,
                null /*parentPkgName*/,
                null /*childPkgNames*/,
                0,
                null /*usesStaticLibraries*/,
                null /*usesStaticLibrariesVersions*/);
        testPkgSetting01.copyFrom(origPkgSetting01);
        verifySettingCopy(origPkgSetting01, testPkgSetting01);
    }

    /** Update package */
    @Test
    public void testUpdatePackageSetting01() throws PackageManagerException {
        final PackageSetting testPkgSetting01 =
                createPackageSetting(0 /*sharedUserId*/, 0 /*pkgFlags*/);
        testPkgSetting01.setInstalled(false /*installed*/, 0 /*userId*/);
        assertThat(testPkgSetting01.pkgFlags, is(0));
        assertThat(testPkgSetting01.pkgPrivateFlags, is(0));
        final PackageSetting oldPkgSetting01 = new PackageSetting(testPkgSetting01);
        Settings.updatePackageSetting(
                testPkgSetting01,
                null /*disabledPkg*/,
                null /*sharedUser*/,
                UPDATED_CODE_PATH /*codePath*/,
                UPDATED_CODE_PATH /*resourcePath*/,
                null /*legacyNativeLibraryPath*/,
                "arm64-v8a" /*primaryCpuAbi*/,
                "armeabi" /*secondaryCpuAbi*/,
                0 /*pkgFlags*/,
                0 /*pkgPrivateFlags*/,
                null /*childPkgNames*/,
                UserManagerService.getInstance(),
                null /*usesStaticLibraries*/,
                null /*usesStaticLibrariesVersions*/);
        assertThat(testPkgSetting01.primaryCpuAbiString, is("arm64-v8a"));
        assertThat(testPkgSetting01.secondaryCpuAbiString, is("armeabi"));
        assertThat(testPkgSetting01.pkgFlags, is(0));
        assertThat(testPkgSetting01.pkgPrivateFlags, is(0));
        final PackageUserState userState = testPkgSetting01.readUserState(0);
        final PackageUserState oldUserState = oldPkgSetting01.readUserState(0);
        verifyUserState(userState, oldUserState, false /*userStateChanged*/, false /*notLaunched*/,
                false /*stopped*/, false /*installed*/);
    }

    /** Update package; package now on /system, install for user '0' */
    @Test
    public void testUpdatePackageSetting02() throws PackageManagerException {
        final PackageSetting testPkgSetting01 =
                createPackageSetting(0 /*sharedUserId*/, 0 /*pkgFlags*/);
        testPkgSetting01.setInstalled(false /*installed*/, 0 /*userId*/);
        assertThat(testPkgSetting01.pkgFlags, is(0));
        assertThat(testPkgSetting01.pkgPrivateFlags, is(0));
        final PackageSetting oldPkgSetting01 = new PackageSetting(testPkgSetting01);
        Settings.updatePackageSetting(
                testPkgSetting01,
                null /*disabledPkg*/,
                null /*sharedUser*/,
                UPDATED_CODE_PATH /*codePath*/,
                UPDATED_CODE_PATH /*resourcePath*/,
                null /*legacyNativeLibraryPath*/,
                "arm64-v8a" /*primaryCpuAbi*/,
                "armeabi" /*secondaryCpuAbi*/,
                ApplicationInfo.FLAG_SYSTEM /*pkgFlags*/,
                ApplicationInfo.PRIVATE_FLAG_PRIVILEGED /*pkgPrivateFlags*/,
                null /*childPkgNames*/,
                UserManagerService.getInstance(),
                null /*usesStaticLibraries*/,
                null /*usesStaticLibrariesVersions*/);
        assertThat(testPkgSetting01.primaryCpuAbiString, is("arm64-v8a"));
        assertThat(testPkgSetting01.secondaryCpuAbiString, is("armeabi"));
        assertThat(testPkgSetting01.pkgFlags, is(ApplicationInfo.FLAG_SYSTEM));
        assertThat(testPkgSetting01.pkgPrivateFlags, is(ApplicationInfo.PRIVATE_FLAG_PRIVILEGED));
        final PackageUserState userState = testPkgSetting01.readUserState(0);
        final PackageUserState oldUserState = oldPkgSetting01.readUserState(0);
        // WARNING: When creating a shallow copy of the PackageSetting we do NOT create
        // new contained objects. For example, this means that changes to the user state
        // in testPkgSetting01 will also change the user state in its copy.
        verifyUserState(userState, oldUserState, false /*userStateChanged*/, false /*notLaunched*/,
                false /*stopped*/, true /*installed*/);
    }

    /** Update package; changing shared user throws exception */
    @Test
    public void testUpdatePackageSetting03() {
        final Context context = InstrumentationRegistry.getContext();
        final Object lock = new Object();
        PermissionManagerInternal pmInt = PermissionManagerService.create(context, null, lock);
        final Settings testSettings01 =
                new Settings(context.getFilesDir(), pmInt.getPermissionSettings(), lock);
        final SharedUserSetting testUserSetting01 = createSharedUserSetting(
                testSettings01, "TestUser", 10064, 0 /*pkgFlags*/, 0 /*pkgPrivateFlags*/);
        final PackageSetting testPkgSetting01 =
                createPackageSetting(0 /*sharedUserId*/, 0 /*pkgFlags*/);
        try {
            Settings.updatePackageSetting(
                    testPkgSetting01,
                    null /*disabledPkg*/,
                    testUserSetting01 /*sharedUser*/,
                    UPDATED_CODE_PATH /*codePath*/,
                    null /*resourcePath*/,
                    null /*legacyNativeLibraryPath*/,
                    "arm64-v8a" /*primaryCpuAbi*/,
                    "armeabi" /*secondaryCpuAbi*/,
                    0 /*pkgFlags*/,
                    0 /*pkgPrivateFlags*/,
                    null /*childPkgNames*/,
                    UserManagerService.getInstance(),
                    null /*usesStaticLibraries*/,
                    null /*usesStaticLibrariesVersions*/);
            fail("Expected a PackageManagerException");
        } catch (PackageManagerException expected) {
        }
    }

    /** Create a new PackageSetting based on an original package setting */
    @Test
    public void testCreateNewSetting01() {
        final PackageSetting originalPkgSetting01 =
                createPackageSetting(0 /*sharedUserId*/, 0 /*pkgFlags*/);
        final PackageSignatures originalSignatures = originalPkgSetting01.signatures;
        final PackageSetting testPkgSetting01 = Settings.createNewSetting(
                REAL_PACKAGE_NAME,
                originalPkgSetting01 /*originalPkg*/,
                null /*disabledPkg*/,
                null /*realPkgName*/,
                null /*sharedUser*/,
                UPDATED_CODE_PATH /*codePath*/,
                UPDATED_CODE_PATH /*resourcePath*/,
                null /*legacyNativeLibraryPath*/,
                "arm64-v8a" /*primaryCpuAbi*/,
                "armeabi" /*secondaryCpuAbi*/,
                UPDATED_VERSION_CODE /*versionCode*/,
                ApplicationInfo.FLAG_SYSTEM /*pkgFlags*/,
                ApplicationInfo.PRIVATE_FLAG_PRIVILEGED /*pkgPrivateFlags*/,
                null /*installUser*/,
                false /*allowInstall*/,
                false /*instantApp*/,
                false /*virtualPreload*/,
                null /*parentPkgName*/,
                null /*childPkgNames*/,
                UserManagerService.getInstance(),
                null /*usesStaticLibraries*/,
                null /*usesStaticLibrariesVersions*/);
        assertThat(testPkgSetting01.codePath, is(UPDATED_CODE_PATH));
        assertThat(testPkgSetting01.name, is(PACKAGE_NAME));
        assertThat(testPkgSetting01.pkgFlags, is(ApplicationInfo.FLAG_SYSTEM));
        assertThat(testPkgSetting01.pkgPrivateFlags, is(ApplicationInfo.PRIVATE_FLAG_PRIVILEGED));
        assertThat(testPkgSetting01.primaryCpuAbiString, is("arm64-v8a"));
        assertThat(testPkgSetting01.resourcePath, is(UPDATED_CODE_PATH));
        assertThat(testPkgSetting01.secondaryCpuAbiString, is("armeabi"));
        // signatures object must be different
        assertNotSame(testPkgSetting01.signatures, originalSignatures);
        assertThat(testPkgSetting01.versionCode, is(UPDATED_VERSION_CODE));
        final PackageUserState userState = testPkgSetting01.readUserState(0);
        verifyUserState(userState, null /*oldUserState*/, false /*userStateChanged*/,
                false /*notLaunched*/, false /*stopped*/, true /*installed*/);
    }

    /** Create a new non-system PackageSetting */
    @Test
    public void testCreateNewSetting02() {
        final PackageSetting testPkgSetting01 = Settings.createNewSetting(
                PACKAGE_NAME,
                null /*originalPkg*/,
                null /*disabledPkg*/,
                null /*realPkgName*/,
                null /*sharedUser*/,
                INITIAL_CODE_PATH /*codePath*/,
                INITIAL_CODE_PATH /*resourcePath*/,
                null /*legacyNativeLibraryPath*/,
                "x86_64" /*primaryCpuAbiString*/,
                "x86" /*secondaryCpuAbiString*/,
                INITIAL_VERSION_CODE /*versionCode*/,
                0 /*pkgFlags*/,
                0 /*pkgPrivateFlags*/,
                UserHandle.SYSTEM /*installUser*/,
                true /*allowInstall*/,
                false /*instantApp*/,
                false /*virtualPreload*/,
                null /*parentPkgName*/,
                null /*childPkgNames*/,
                UserManagerService.getInstance(),
                null /*usesStaticLibraries*/,
                null /*usesStaticLibrariesVersions*/);
        assertThat(testPkgSetting01.appId, is(0));
        assertThat(testPkgSetting01.codePath, is(INITIAL_CODE_PATH));
        assertThat(testPkgSetting01.name, is(PACKAGE_NAME));
        assertThat(testPkgSetting01.pkgFlags, is(0));
        assertThat(testPkgSetting01.pkgPrivateFlags, is(0));
        assertThat(testPkgSetting01.primaryCpuAbiString, is("x86_64"));
        assertThat(testPkgSetting01.resourcePath, is(INITIAL_CODE_PATH));
        assertThat(testPkgSetting01.secondaryCpuAbiString, is("x86"));
        assertThat(testPkgSetting01.versionCode, is(INITIAL_VERSION_CODE));
        // by default, the package is considered stopped
        final PackageUserState userState = testPkgSetting01.readUserState(0);
        verifyUserState(userState, null /*oldUserState*/, false /*userStateChanged*/,
                true /*notLaunched*/, true /*stopped*/, true /*installed*/);
    }

    /** Create PackageSetting for a shared user */
    @Test
    public void testCreateNewSetting03() {
        final Context context = InstrumentationRegistry.getContext();
        final Object lock = new Object();
        PermissionManagerInternal pmInt = PermissionManagerService.create(context, null, lock);
        final Settings testSettings01 =
                new Settings(context.getFilesDir(), pmInt.getPermissionSettings(), lock);
        final SharedUserSetting testUserSetting01 = createSharedUserSetting(
                testSettings01, "TestUser", 10064, 0 /*pkgFlags*/, 0 /*pkgPrivateFlags*/);
        final PackageSetting testPkgSetting01 = Settings.createNewSetting(
                PACKAGE_NAME,
                null /*originalPkg*/,
                null /*disabledPkg*/,
                null /*realPkgName*/,
                testUserSetting01 /*sharedUser*/,
                INITIAL_CODE_PATH /*codePath*/,
                INITIAL_CODE_PATH /*resourcePath*/,
                null /*legacyNativeLibraryPath*/,
                "x86_64" /*primaryCpuAbiString*/,
                "x86" /*secondaryCpuAbiString*/,
                INITIAL_VERSION_CODE /*versionCode*/,
                0 /*pkgFlags*/,
                0 /*pkgPrivateFlags*/,
                null /*installUser*/,
                false /*allowInstall*/,
                false /*instantApp*/,
                false /*virtualPreload*/,
                null /*parentPkgName*/,
                null /*childPkgNames*/,
                UserManagerService.getInstance(),
                null /*usesStaticLibraries*/,
                null /*usesStaticLibrariesVersions*/);
        assertThat(testPkgSetting01.appId, is(10064));
        assertThat(testPkgSetting01.codePath, is(INITIAL_CODE_PATH));
        assertThat(testPkgSetting01.name, is(PACKAGE_NAME));
        assertThat(testPkgSetting01.pkgFlags, is(0));
        assertThat(testPkgSetting01.pkgPrivateFlags, is(0));
        assertThat(testPkgSetting01.primaryCpuAbiString, is("x86_64"));
        assertThat(testPkgSetting01.resourcePath, is(INITIAL_CODE_PATH));
        assertThat(testPkgSetting01.secondaryCpuAbiString, is("x86"));
        assertThat(testPkgSetting01.versionCode, is(INITIAL_VERSION_CODE));
        final PackageUserState userState = testPkgSetting01.readUserState(0);
        verifyUserState(userState, null /*oldUserState*/, false /*userStateChanged*/,
                false /*notLaunched*/, false /*stopped*/, true /*installed*/);
    }

    /** Create a new PackageSetting based on a disabled package setting */
    @Test
    public void testCreateNewSetting04() {
        final PackageSetting disabledPkgSetting01 =
                createPackageSetting(0 /*sharedUserId*/, 0 /*pkgFlags*/);
        disabledPkgSetting01.appId = 10064;
        final PackageSignatures disabledSignatures = disabledPkgSetting01.signatures;
        final PackageSetting testPkgSetting01 = Settings.createNewSetting(
                PACKAGE_NAME,
                null /*originalPkg*/,
                disabledPkgSetting01 /*disabledPkg*/,
                null /*realPkgName*/,
                null /*sharedUser*/,
                UPDATED_CODE_PATH /*codePath*/,
                UPDATED_CODE_PATH /*resourcePath*/,
                null /*legacyNativeLibraryPath*/,
                "arm64-v8a" /*primaryCpuAbi*/,
                "armeabi" /*secondaryCpuAbi*/,
                UPDATED_VERSION_CODE /*versionCode*/,
                0 /*pkgFlags*/,
                0 /*pkgPrivateFlags*/,
                null /*installUser*/,
                false /*allowInstall*/,
                false /*instantApp*/,
                false /*virtualPreload*/,
                null /*parentPkgName*/,
                null /*childPkgNames*/,
                UserManagerService.getInstance(),
                null /*usesStaticLibraries*/,
                null /*usesStaticLibrariesVersions*/);
        assertThat(testPkgSetting01.appId, is(10064));
        assertThat(testPkgSetting01.codePath, is(UPDATED_CODE_PATH));
        assertThat(testPkgSetting01.name, is(PACKAGE_NAME));
        assertThat(testPkgSetting01.pkgFlags, is(0));
        assertThat(testPkgSetting01.pkgPrivateFlags, is(0));
        assertThat(testPkgSetting01.primaryCpuAbiString, is("arm64-v8a"));
        assertThat(testPkgSetting01.resourcePath, is(UPDATED_CODE_PATH));
        assertThat(testPkgSetting01.secondaryCpuAbiString, is("armeabi"));
        assertNotSame(testPkgSetting01.signatures, disabledSignatures);
        assertThat(testPkgSetting01.versionCode, is(UPDATED_VERSION_CODE));
        final PackageUserState userState = testPkgSetting01.readUserState(0);
        verifyUserState(userState, null /*oldUserState*/, false /*userStateChanged*/,
                false /*notLaunched*/, false /*stopped*/, true /*installed*/);
    }

    private <T> void assertArrayEquals(T[] a, T[] b) {
        assertTrue("Expected: " + Arrays.toString(a) + ", actual: " + Arrays.toString(b),
                Arrays.equals(a, b));
    }

    private void assertArrayEquals(int[] a, int[] b) {
        assertTrue("Expected: " + Arrays.toString(a) + ", actual: " + Arrays.toString(b),
                Arrays.equals(a, b));
    }

    private void assertArrayEquals(long[] a, long[] b) {
        assertTrue("Expected: " + Arrays.toString(a) + ", actual: " + Arrays.toString(b),
                Arrays.equals(a, b));
    }

    private void verifyUserState(PackageUserState userState, PackageUserState oldUserState,
            boolean userStateChanged) {
        verifyUserState(userState, oldUserState, userStateChanged, false /*notLaunched*/,
                false /*stopped*/, true /*installed*/);
    }

    private void verifyUserState(PackageUserState userState, PackageUserState oldUserState,
            boolean userStateChanged, boolean notLaunched, boolean stopped, boolean installed) {
        assertThat(userState.enabled, is(0));
        assertThat(userState.hidden, is(false));
        assertThat(userState.installed, is(installed));
        assertThat(userState.notLaunched, is(notLaunched));
        assertThat(userState.stopped, is(stopped));
        assertThat(userState.suspended, is(false));
        if (oldUserState != null) {
            assertThat(userState.equals(oldUserState), is(not(userStateChanged)));
        }
    }

    private void verifySettingCopy(PackageSetting origPkgSetting, PackageSetting testPkgSetting) {
        assertThat(origPkgSetting, is(not(testPkgSetting)));
        assertThat(origPkgSetting.appId, is(testPkgSetting.appId));
        // different but equal objects
        assertNotSame(origPkgSetting.childPackageNames, testPkgSetting.childPackageNames);
        assertThat(origPkgSetting.childPackageNames, is(testPkgSetting.childPackageNames));
        assertSame(origPkgSetting.codePath, testPkgSetting.codePath);
        assertThat(origPkgSetting.codePath, is(testPkgSetting.codePath));
        assertSame(origPkgSetting.codePathString, testPkgSetting.codePathString);
        assertThat(origPkgSetting.codePathString, is(testPkgSetting.codePathString));
        assertSame(origPkgSetting.cpuAbiOverrideString, testPkgSetting.cpuAbiOverrideString);
        assertThat(origPkgSetting.cpuAbiOverrideString, is(testPkgSetting.cpuAbiOverrideString));
        assertThat(origPkgSetting.firstInstallTime, is(testPkgSetting.firstInstallTime));
        assertSame(origPkgSetting.installerPackageName, testPkgSetting.installerPackageName);
        assertThat(origPkgSetting.installerPackageName, is(testPkgSetting.installerPackageName));
        assertThat(origPkgSetting.installPermissionsFixed,
                is(testPkgSetting.installPermissionsFixed));
        assertThat(origPkgSetting.isOrphaned, is(testPkgSetting.isOrphaned));
        assertSame(origPkgSetting.keySetData, testPkgSetting.keySetData);
        assertThat(origPkgSetting.keySetData, is(testPkgSetting.keySetData));
        assertThat(origPkgSetting.lastUpdateTime, is(testPkgSetting.lastUpdateTime));
        assertSame(origPkgSetting.legacyNativeLibraryPathString,
                testPkgSetting.legacyNativeLibraryPathString);
        assertThat(origPkgSetting.legacyNativeLibraryPathString,
                is(testPkgSetting.legacyNativeLibraryPathString));
        assertNotSame(origPkgSetting.mPermissionsState, testPkgSetting.mPermissionsState);
        assertThat(origPkgSetting.mPermissionsState, is(testPkgSetting.mPermissionsState));
        assertThat(origPkgSetting.name, is(testPkgSetting.name));
        // oldCodePaths is _not_ copied
        // assertNotSame(origPkgSetting.oldCodePaths, testPkgSetting.oldCodePaths);
        // assertThat(origPkgSetting.oldCodePaths, is(not(testPkgSetting.oldCodePaths)));
        assertSame(origPkgSetting.parentPackageName, testPkgSetting.parentPackageName);
        assertThat(origPkgSetting.parentPackageName, is(testPkgSetting.parentPackageName));
        assertSame(origPkgSetting.pkg, testPkgSetting.pkg);
        // No equals() method for this object
        // assertThat(origPkgSetting.pkg, is(testPkgSetting.pkg));
        assertThat(origPkgSetting.pkgFlags, is(testPkgSetting.pkgFlags));
        assertThat(origPkgSetting.pkgPrivateFlags, is(testPkgSetting.pkgPrivateFlags));
        assertSame(origPkgSetting.primaryCpuAbiString, testPkgSetting.primaryCpuAbiString);
        assertThat(origPkgSetting.primaryCpuAbiString, is(testPkgSetting.primaryCpuAbiString));
        assertThat(origPkgSetting.realName, is(testPkgSetting.realName));
        assertSame(origPkgSetting.resourcePath, testPkgSetting.resourcePath);
        assertThat(origPkgSetting.resourcePath, is(testPkgSetting.resourcePath));
        assertSame(origPkgSetting.resourcePathString, testPkgSetting.resourcePathString);
        assertThat(origPkgSetting.resourcePathString, is(testPkgSetting.resourcePathString));
        assertSame(origPkgSetting.secondaryCpuAbiString, testPkgSetting.secondaryCpuAbiString);
        assertThat(origPkgSetting.secondaryCpuAbiString, is(testPkgSetting.secondaryCpuAbiString));
        assertSame(origPkgSetting.sharedUser, testPkgSetting.sharedUser);
        assertThat(origPkgSetting.sharedUser, is(testPkgSetting.sharedUser));
        assertSame(origPkgSetting.signatures, testPkgSetting.signatures);
        assertThat(origPkgSetting.signatures, is(testPkgSetting.signatures));
        assertThat(origPkgSetting.timeStamp, is(testPkgSetting.timeStamp));
        assertThat(origPkgSetting.uidError, is(testPkgSetting.uidError));
        assertNotSame(origPkgSetting.getUserState(), is(testPkgSetting.getUserState()));
        // No equals() method for SparseArray object
        // assertThat(origPkgSetting.getUserState(), is(testPkgSetting.getUserState()));
        assertSame(origPkgSetting.verificationInfo, testPkgSetting.verificationInfo);
        assertThat(origPkgSetting.verificationInfo, is(testPkgSetting.verificationInfo));
        assertThat(origPkgSetting.versionCode, is(testPkgSetting.versionCode));
        assertSame(origPkgSetting.volumeUuid, testPkgSetting.volumeUuid);
        assertThat(origPkgSetting.volumeUuid, is(testPkgSetting.volumeUuid));
    }

    private SharedUserSetting createSharedUserSetting(Settings settings, String userName,
            int sharedUserId, int pkgFlags, int pkgPrivateFlags) {
        return settings.addSharedUserLPw(
                userName,
                sharedUserId,
                pkgFlags,
                pkgPrivateFlags);
    }
    private PackageSetting createPackageSetting(int sharedUserId, int pkgFlags) {
        return new PackageSetting(
                PACKAGE_NAME,
                REAL_PACKAGE_NAME,
                INITIAL_CODE_PATH /*codePath*/,
                INITIAL_CODE_PATH /*resourcePath*/,
                null /*legacyNativeLibraryPathString*/,
                "x86_64" /*primaryCpuAbiString*/,
                "x86" /*secondaryCpuAbiString*/,
                null /*cpuAbiOverrideString*/,
                INITIAL_VERSION_CODE,
                pkgFlags,
                0 /*privateFlags*/,
                null /*parentPackageName*/,
                null /*childPackageNames*/,
                sharedUserId,
                null /*usesStaticLibraries*/,
                null /*usesStaticLibrariesVersions*/);
    }

    private PackageSetting createPackageSetting(String packageName) {
        return new PackageSetting(
                packageName,
                packageName,
                INITIAL_CODE_PATH /*codePath*/,
                INITIAL_CODE_PATH /*resourcePath*/,
                null /*legacyNativeLibraryPathString*/,
                "x86_64" /*primaryCpuAbiString*/,
                "x86" /*secondaryCpuAbiString*/,
                null /*cpuAbiOverrideString*/,
                INITIAL_VERSION_CODE,
                0,
                0 /*privateFlags*/,
                null /*parentPackageName*/,
                null /*childPackageNames*/,
                0,
                null /*usesStaticLibraries*/,
                null /*usesStaticLibrariesVersions*/);
    }

    private @NonNull List<UserInfo> createFakeUsers() {
        ArrayList<UserInfo> users = new ArrayList<>();
        users.add(new UserInfo(UserHandle.USER_SYSTEM, "test user", UserInfo.FLAG_INITIALIZED));
        return users;
    }

    private void writeFile(File file, byte[] data) {
        file.mkdirs();
        try {
            AtomicFile aFile = new AtomicFile(file);
            FileOutputStream fos = aFile.startWrite();
            fos.write(data);
            aFile.finishWrite(fos);
        } catch (IOException ioe) {
            Log.e(TAG, "Cannot write file " + file.getPath());
        }
    }

    private void writePackagesXml() {
        writeFile(new File(InstrumentationRegistry.getContext().getFilesDir(), "system/packages.xml"),
                ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<packages>"
                + "<last-platform-version internal=\"15\" external=\"0\" fingerprint=\"foo\" />"
                + "<permission-trees>"
                + "<item name=\"com.google.android.permtree\" package=\"com.google.android.permpackage\" />"
                + "</permission-trees>"
                + "<permissions>"
                + "<item name=\"android.permission.WRITE_CALL_LOG\" package=\"android\" protection=\"1\" />"
                + "<item name=\"android.permission.ASEC_ACCESS\" package=\"android\" protection=\"2\" />"
                + "<item name=\"android.permission.ACCESS_WIMAX_STATE\" package=\"android\" />"
                + "<item name=\"android.permission.REBOOT\" package=\"android\" protection=\"18\" />"
                + "</permissions>"
                + "<package name=\"com.android.app1\" codePath=\"/system/app/app1.apk\" nativeLibraryPath=\"/data/data/com.android.app1/lib\" flags=\"1\" ft=\"1360e2caa70\" it=\"135f2f80d08\" ut=\"1360e2caa70\" version=\"1109\" sharedUserId=\"11000\">"
                + "<sigs count=\"1\">"
                + "<cert index=\"0\" key=\"" + KeySetStrings.ctsKeySetCertA + "\" />"
                + "</sigs>"
                + "<proper-signing-keyset identifier=\"1\" />"
                + "</package>"
                + "<package name=\"com.android.app2\" codePath=\"/system/app/app2.apk\" nativeLibraryPath=\"/data/data/com.android.app2/lib\" flags=\"1\" ft=\"1360e578718\" it=\"135f2f80d08\" ut=\"1360e578718\" version=\"15\" enabled=\"3\" userId=\"11001\">"
                + "<sigs count=\"1\">"
                + "<cert index=\"0\" />"
                + "</sigs>"
                + "<proper-signing-keyset identifier=\"1\" />"
                + "<defined-keyset alias=\"AB\" identifier=\"4\" />"
                + "</package>"
                + "<package name=\"com.android.app3\" codePath=\"/system/app/app3.apk\" nativeLibraryPath=\"/data/data/com.android.app3/lib\" flags=\"1\" ft=\"1360e577b60\" it=\"135f2f80d08\" ut=\"1360e577b60\" version=\"15\" userId=\"11030\">"
                + "<sigs count=\"1\">"
                + "<cert index=\"1\" key=\"" + KeySetStrings.ctsKeySetCertB + "\" />"
                + "</sigs>"
                + "<proper-signing-keyset identifier=\"2\" />"
                + "<upgrade-keyset identifier=\"3\" />"
                + "<defined-keyset alias=\"C\" identifier=\"3\" />"
                + "</package>"
                + "<shared-user name=\"com.android.shared1\" userId=\"11000\">"
                + "<sigs count=\"1\">"
                + "<cert index=\"1\" />"
                + "</sigs>"
                + "<perms>"
                + "<item name=\"android.permission.REBOOT\" />"
                + "</perms>"
                + "</shared-user>"
                + "<keyset-settings version=\"1\">"
                + "<keys>"
                + "<public-key identifier=\"1\" value=\"" + KeySetStrings.ctsKeySetPublicKeyA + "\" />"
                + "<public-key identifier=\"2\" value=\"" + KeySetStrings.ctsKeySetPublicKeyB + "\" />"
                + "<public-key identifier=\"3\" value=\"" + KeySetStrings.ctsKeySetPublicKeyC + "\" />"
                + "</keys>"
                + "<keysets>"
                + "<keyset identifier=\"1\">"
                + "<key-id identifier=\"1\" />"
                + "</keyset>"
                + "<keyset identifier=\"2\">"
                + "<key-id identifier=\"2\" />"
                + "</keyset>"
                + "<keyset identifier=\"3\">"
                + "<key-id identifier=\"3\" />"
                + "</keyset>"
                + "<keyset identifier=\"4\">"
                + "<key-id identifier=\"1\" />"
                + "<key-id identifier=\"2\" />"
                + "</keyset>"
                + "</keysets>"
                + "<lastIssuedKeyId value=\"3\" />"
                + "<lastIssuedKeySetId value=\"4\" />"
                + "</keyset-settings>"
                + "</packages>").getBytes());
    }

    private void writePackageRestrictions_oldSuspendInfoXml(final int userId) {
        writeFile(new File(InstrumentationRegistry.getContext().getFilesDir(), "system/users/"
                        + userId + "/package-restrictions.xml"),
                ( "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                        + "<package-restrictions>\n"
                        + "    <pkg name=\"" + PACKAGE_NAME_1 + "\" suspended=\"true\" />"
                        + "    <pkg name=\"" + PACKAGE_NAME_2 + "\" suspended=\"false\" />"
                        + "    <preferred-activities />\n"
                        + "    <persistent-preferred-activities />\n"
                        + "    <crossProfile-intent-filters />\n"
                        + "    <default-apps />\n"
                        + "</package-restrictions>\n")
                        .getBytes());
    }

    private void writeStoppedPackagesXml() {
        writeFile(new File(InstrumentationRegistry.getContext().getFilesDir(), "system/packages-stopped.xml"),
                ( "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<stopped-packages>"
                + "<pkg name=\"com.android.app1\" nl=\"1\" />"
                + "<pkg name=\"com.android.app3\" nl=\"1\" />"
                + "</stopped-packages>")
                .getBytes());
    }

    private void writePackagesList() {
        writeFile(new File(InstrumentationRegistry.getContext().getFilesDir(), "system/packages.list"),
                ( "com.android.app1 11000 0 /data/data/com.android.app1 seinfo1"
                + "com.android.app2 11001 0 /data/data/com.android.app2 seinfo2"
                + "com.android.app3 11030 0 /data/data/com.android.app3 seinfo3")
                .getBytes());
    }

    private void deleteSystemFolder() {
        File systemFolder = new File(InstrumentationRegistry.getContext().getFilesDir(), "system");
        deleteFolder(systemFolder);
    }

    private static void deleteFolder(File folder) {
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                deleteFolder(file);
            }
        }
        folder.delete();
    }

    private void writeOldFiles() {
        deleteSystemFolder();
        writePackagesXml();
        writeStoppedPackagesXml();
        writePackagesList();
    }

    @Before
    public void createUserManagerServiceRef() throws ReflectiveOperationException {
        InstrumentationRegistry.getInstrumentation().runOnMainSync((Runnable) () -> {
            try {
                // unregister the user manager from the local service
                LocalServices.removeServiceForTest(UserManagerInternal.class);
                new UserManagerService(InstrumentationRegistry.getContext());
            } catch (Exception e) {
                e.printStackTrace();
                fail("Could not create user manager service; " + e);
            }
        });
    }

    @After
    public void tearDown() throws Exception {
        deleteFolder(InstrumentationRegistry.getTargetContext().getFilesDir());
    }

    private void verifyKeySetMetaData(Settings settings)
            throws ReflectiveOperationException, IllegalAccessException {
        ArrayMap<String, PackageSetting> packages = settings.mPackages;
        KeySetManagerService ksms = settings.mKeySetManagerService;

        /* verify keyset and public key ref counts */
        assertThat(KeySetUtils.getKeySetRefCount(ksms, 1), is(2));
        assertThat(KeySetUtils.getKeySetRefCount(ksms, 2), is(1));
        assertThat(KeySetUtils.getKeySetRefCount(ksms, 3), is(1));
        assertThat(KeySetUtils.getKeySetRefCount(ksms, 4), is(1));
        assertThat(KeySetUtils.getPubKeyRefCount(ksms, 1), is(2));
        assertThat(KeySetUtils.getPubKeyRefCount(ksms, 2), is(2));
        assertThat(KeySetUtils.getPubKeyRefCount(ksms, 3), is(1));

        /* verify public keys properly read */
        PublicKey keyA = PackageParser.parsePublicKey(KeySetStrings.ctsKeySetPublicKeyA);
        PublicKey keyB = PackageParser.parsePublicKey(KeySetStrings.ctsKeySetPublicKeyB);
        PublicKey keyC = PackageParser.parsePublicKey(KeySetStrings.ctsKeySetPublicKeyC);
        assertThat(KeySetUtils.getPubKey(ksms, 1), is(keyA));
        assertThat(KeySetUtils.getPubKey(ksms, 2), is(keyB));
        assertThat(KeySetUtils.getPubKey(ksms, 3), is(keyC));

        /* verify mapping is correct (ks -> pub keys) */
        LongSparseArray<ArraySet<Long>> ksMapping = KeySetUtils.getKeySetMapping(ksms);
        ArraySet<Long> mapping = ksMapping.get(1);
        assertThat(mapping.size(), is(1));
        assertThat(mapping.contains(new Long(1)), is(true));
        mapping = ksMapping.get(2);
        assertThat(mapping.size(), is(1));
        assertThat(mapping.contains(new Long(2)), is(true));
        mapping = ksMapping.get(3);
        assertThat(mapping.size(), is(1));
        assertThat(mapping.contains(new Long(3)), is(true));
        mapping = ksMapping.get(4);
        assertThat(mapping.size(), is(2));
        assertThat(mapping.contains(new Long(1)), is(true));
        assertThat(mapping.contains(new Long(2)), is(true));

        /* verify lastIssuedIds are consistent */
        assertThat(KeySetUtils.getLastIssuedKeyId(ksms), is(3L));
        assertThat(KeySetUtils.getLastIssuedKeySetId(ksms), is(4L));

        /* verify packages have been given the appropriate information */
        PackageSetting ps = packages.get("com.android.app1");
        assertThat(ps.keySetData.getProperSigningKeySet(), is(1L));
        ps = packages.get("com.android.app2");
        assertThat(ps.keySetData.getProperSigningKeySet(), is(1L));
        assertThat(ps.keySetData.getAliases().get("AB"), is(4L));
        ps = packages.get("com.android.app3");
        assertThat(ps.keySetData.getProperSigningKeySet(), is(2L));
        assertThat(ps.keySetData.getAliases().get("C"), is(3L));
        assertThat(ps.keySetData.getUpgradeKeySets().length, is(1));
        assertThat(ps.keySetData.getUpgradeKeySets()[0], is(3L));
    }
}
