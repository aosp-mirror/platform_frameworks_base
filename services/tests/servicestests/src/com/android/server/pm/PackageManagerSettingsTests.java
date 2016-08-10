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

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageParser;
import android.content.pm.PackageUserState;
import android.content.pm.UserInfo;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManagerInternal;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.LongSparseArray;

import com.android.internal.os.AtomicFile;
import com.android.internal.util.FastPrintWriter;
import com.android.server.LocalServices;

import org.hamcrest.core.IsNot;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class PackageManagerSettingsTests {
    private static final String PACKAGE_NAME_2 = "com.google.app2";
    private static final String PACKAGE_NAME_3 = "com.android.app3";
    private static final String PACKAGE_NAME_1 = "com.google.app1";
    public static final String TAG = "PackageManagerSettingsTests";
    protected final String PREFIX = "android.content.pm";

    /** make sure our initialized KeySetManagerService metadata matches packages.xml */
    @Test
    public void testReadKeySetSettings()
            throws ReflectiveOperationException, IllegalAccessException {
        /* write out files and read */
        writeOldFiles();
        Settings settings =
                new Settings(InstrumentationRegistry.getContext().getFilesDir(), new Object());
        assertThat(settings.readLPw(createFakeUsers()), is(true));
        verifyKeySetMetaData(settings);
    }

    /** read in data, write it out, and read it back in.  Verify same. */
    @Test
    public void testWriteKeySetSettings()
            throws ReflectiveOperationException, IllegalAccessException {
        // write out files and read
        writeOldFiles();
        Settings settings =
                new Settings(InstrumentationRegistry.getContext().getFilesDir(), new Object());
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
        Settings settings =
                new Settings(InstrumentationRegistry.getContext().getFilesDir(), new Object());
        assertThat(settings.readLPw(createFakeUsers()), is(true));
        assertThat(settings.peekPackageLPr(PACKAGE_NAME_3), is(notNullValue()));
        assertThat(settings.peekPackageLPr(PACKAGE_NAME_1), is(notNullValue()));

        PackageSetting ps = settings.peekPackageLPr(PACKAGE_NAME_1);
        assertThat(ps.getEnabled(0), is(COMPONENT_ENABLED_STATE_DEFAULT));
        assertThat(ps.getNotLaunched(0), is(true));

        ps = settings.peekPackageLPr(PACKAGE_NAME_2);
        assertThat(ps.getStopped(0), is(false));
        assertThat(ps.getEnabled(0), is(COMPONENT_ENABLED_STATE_DISABLED_USER));
        assertThat(ps.getEnabled(1), is(COMPONENT_ENABLED_STATE_DEFAULT));
    }

    @Test
    public void testNewPackageRestrictionsFile() throws ReflectiveOperationException {
        // Write the package files and make sure they're parsed properly the first time
        writeOldFiles();
        Settings settings =
                new Settings(InstrumentationRegistry.getContext().getFilesDir(), new Object());
        assertThat(settings.readLPw(createFakeUsers()), is(true));
        settings.writeLPr();

        // Create Settings again to make it read from the new files
        settings = new Settings(InstrumentationRegistry.getContext().getFilesDir(), new Object());
        assertThat(settings.readLPw(createFakeUsers()), is(true));

        PackageSetting ps = settings.peekPackageLPr(PACKAGE_NAME_2);
        assertThat(ps.getEnabled(0), is(COMPONENT_ENABLED_STATE_DISABLED_USER));
        assertThat(ps.getEnabled(1), is(COMPONENT_ENABLED_STATE_DEFAULT));
    }

    @Test
    public void testEnableDisable() {
        // Write the package files and make sure they're parsed properly the first time
        writeOldFiles();
        Settings settings =
                new Settings(InstrumentationRegistry.getContext().getFilesDir(), new Object());
        assertThat(settings.readLPw(createFakeUsers()), is(true));

        // Enable/Disable a package
        PackageSetting ps = settings.peekPackageLPr(PACKAGE_NAME_1);
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
    private static final File INITIAL_CODE_PATH =
            new File(InstrumentationRegistry.getContext().getFilesDir(), "com.android.bar-1");
    private static final File UPDATED_CODE_PATH =
            new File(InstrumentationRegistry.getContext().getFilesDir(), "com.android.bar-2");
    private static final int INITIAL_VERSION_CODE = 10023;
    private static final int UPDATED_VERSION_CODE = 10025;

    /** Update existing package; don't install */
    @Test
    public void testUpdatePackageSetting01()
            throws ReflectiveOperationException, PackageManagerException {
        final PackageSetting pkgSetting01 = createPackageSetting(0 /*pkgFlags*/);
        final PackageSetting oldPkgSetting01 = new PackageSetting(pkgSetting01);
        final PackageSetting testPkgSetting01 = Settings.updatePackageSetting(
                pkgSetting01,
                PACKAGE_NAME,
                null /*realPkgName*/,
                null /*originalPkg*/,
                null /*disabledPkg*/,
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
                null /*parentPkgName*/,
                null /*childPkgNames*/,
                UserManagerService.getInstance());
        assertThat(testPkgSetting01, is(pkgSetting01));
        assertThat(testPkgSetting01.primaryCpuAbiString, is("arm64-v8a"));
        assertThat(testPkgSetting01.secondaryCpuAbiString, is("armeabi"));
        assertThat(testPkgSetting01.origPackage, is(nullValue()));
        assertThat(testPkgSetting01.pkgFlags, is(ApplicationInfo.FLAG_SYSTEM));
        assertThat(testPkgSetting01.pkgPrivateFlags, is(ApplicationInfo.PRIVATE_FLAG_PRIVILEGED));
        final PackageUserState userState = testPkgSetting01.readUserState(UserHandle.USER_SYSTEM);
        final PackageUserState oldUserState = oldPkgSetting01.readUserState(UserHandle.USER_SYSTEM);
        verifyUserState(userState, oldUserState, false /*userStateChanged*/);
    }

    /** Update existing package; install for UserHandle.SYSTEM */
    @Test
    public void testUpdatePackageSetting02()
            throws ReflectiveOperationException, PackageManagerException {
        final PackageSetting pkgSetting01 = createPackageSetting(0 /*pkgFlags*/);
        final PackageSetting oldPkgSetting01 = new PackageSetting(pkgSetting01);
        final PackageSetting testPkgSetting01 = Settings.updatePackageSetting(
                pkgSetting01,
                PACKAGE_NAME,
                null /*realPkgName*/,
                null /*originalPkg*/,
                null /*disabledPkg*/,
                null /*sharedUser*/,
                UPDATED_CODE_PATH /*codePath*/,
                UPDATED_CODE_PATH /*resourcePath*/,
                null /*legacyNativeLibraryPath*/,
                "arm64-v8a" /*primaryCpuAbi*/,
                "armeabi" /*secondaryCpuAbi*/,
                UPDATED_VERSION_CODE /*versionCode*/,
                0 /*pkgFlags*/,
                0 /*pkgPrivateFlags*/,
                UserHandle.SYSTEM /*installUser*/,
                true /*allowInstall*/,
                null /*parentPkgName*/,
                null /*childPkgNames*/,
                UserManagerService.getInstance());
        assertThat(testPkgSetting01, is(pkgSetting01));
        assertThat(testPkgSetting01.primaryCpuAbiString, is("arm64-v8a"));
        assertThat(testPkgSetting01.secondaryCpuAbiString, is("armeabi"));
        assertThat(testPkgSetting01.origPackage, is(nullValue()));
        assertThat(testPkgSetting01.pkgFlags, is(0));
        assertThat(testPkgSetting01.pkgPrivateFlags, is(0));
        final PackageUserState userState = testPkgSetting01.readUserState(UserHandle.USER_SYSTEM);
        final PackageUserState oldUserState = oldPkgSetting01.readUserState(UserHandle.USER_SYSTEM);
        // The user state won't be changed in this scenario; the default user state is for
        // the package to be installed.
        verifyUserState(userState, oldUserState, false /*userStateChanged*/,
                false /*notLaunched*/, false /*stopped*/);
    }

    /** Update existing package; install for {@code null} */
    @Test
    public void testUpdatePackageSetting03()
            throws ReflectiveOperationException, PackageManagerException {
        final PackageSetting pkgSetting01 = createPackageSetting(0 /*pkgFlags*/);
        final PackageSetting oldPkgSetting01 = new PackageSetting(pkgSetting01);
        final PackageSetting testPkgSetting01 = Settings.updatePackageSetting(
                pkgSetting01,
                PACKAGE_NAME,
                null /*realPkgName*/,
                null /*originalPkg*/,
                null /*disabledPkg*/,
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
                true /*allowInstall*/,
                null /*parentPkgName*/,
                null /*childPkgNames*/,
                UserManagerService.getInstance());
        assertThat(testPkgSetting01, is(pkgSetting01));
        assertThat(testPkgSetting01.primaryCpuAbiString, is("arm64-v8a"));
        assertThat(testPkgSetting01.secondaryCpuAbiString, is("armeabi"));
        assertThat(testPkgSetting01.origPackage, is(nullValue()));
        assertThat(testPkgSetting01.pkgFlags, is(0));
        assertThat(testPkgSetting01.pkgPrivateFlags, is(0));
        final PackageUserState userState = testPkgSetting01.readUserState(UserHandle.USER_SYSTEM);
        final PackageUserState oldUserState = oldPkgSetting01.readUserState(UserHandle.USER_SYSTEM);
        verifyUserState(userState, oldUserState, false /*userStateChanged*/);
    }

    /** Update renamed package */
    @Test
    public void testUpdatePackageSetting04()
            throws ReflectiveOperationException, PackageManagerException {
        final PackageSetting originalPkgSetting = createPackageSetting(0 /*pkgFlags*/);
        final PackageSetting testPkgSetting01 = Settings.updatePackageSetting(
                null /*pkgSetting*/,
                PACKAGE_NAME,
                null /*realPkgName*/,
                originalPkgSetting /*originalPkg*/,
                null /*disabledPkg*/,
                null /*sharedUser*/,
                UPDATED_CODE_PATH /*codePath*/,
                UPDATED_CODE_PATH /*resourcePath*/,
                null /*legacyNativeLibraryPath*/,
                "arm64-v8a" /*primaryCpuAbi*/,
                "armeabi" /*secondaryCpuAbi*/,
                UPDATED_VERSION_CODE /*versionCode*/,
                0 /*pkgFlags*/,
                0 /*pkgPrivateFlags*/,
                UserHandle.SYSTEM /*installUser*/,
                false /*allowInstall*/,
                null /*parentPkgName*/,
                null /*childPkgNames*/,
                UserManagerService.getInstance());
        assertThat(testPkgSetting01, is(not(originalPkgSetting)));
        // ABI isn't pulled from the original package setting
        assertThat(testPkgSetting01.primaryCpuAbiString, is("x86_64"));
        assertThat(testPkgSetting01.secondaryCpuAbiString, is("x86"));
        assertThat(testPkgSetting01.origPackage, is(originalPkgSetting));
        assertThat(testPkgSetting01.pkgFlags, is(0));
        assertThat(testPkgSetting01.pkgPrivateFlags, is(0));
        final PackageUserState userState = testPkgSetting01.readUserState(UserHandle.USER_SYSTEM);
        verifyUserState(userState, PackageSettingBase.DEFAULT_USER_STATE /*oldUserState*/,
                false /*userStateChanged*/);
    }

    /** Update new package */
    @Test
    public void testUpdatePackageSetting05()
            throws ReflectiveOperationException, PackageManagerException {
        final PackageSetting testPkgSetting01 = Settings.updatePackageSetting(
                null /*pkgSetting*/,
                PACKAGE_NAME,
                null /*realPkgName*/,
                null /*originalPkg*/,
                null /*disabledPkg*/,
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
                null /*parentPkgName*/,
                null /*childPkgNames*/,
                UserManagerService.getInstance());
        assertThat(testPkgSetting01, is(notNullValue()));
        assertThat(testPkgSetting01.primaryCpuAbiString, is("arm64-v8a"));
        assertThat(testPkgSetting01.secondaryCpuAbiString, is("armeabi"));
        assertThat(testPkgSetting01.origPackage, is(nullValue()));
        assertThat(testPkgSetting01.pkgFlags, is(0));
        assertThat(testPkgSetting01.pkgPrivateFlags, is(0));
        final PackageUserState userState = testPkgSetting01.readUserState(UserHandle.USER_SYSTEM);
        verifyUserState(userState, PackageSettingBase.DEFAULT_USER_STATE /*oldUserState*/,
                false /*userStateChanged*/);
    }

    /** Update new package; install for {@code null} user */
    @Test
    public void testUpdatePackageSetting06()
            throws ReflectiveOperationException, PackageManagerException {
        final PackageSetting testPkgSetting01 = Settings.updatePackageSetting(
                null /*pkgSetting*/,
                PACKAGE_NAME,
                null /*realPkgName*/,
                null /*originalPkg*/,
                null /*disabledPkg*/,
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
                true /*allowInstall*/,
                null /*parentPkgName*/,
                null /*childPkgNames*/,
                UserManagerService.getInstance());
        assertThat(testPkgSetting01, is(notNullValue()));
        assertThat(testPkgSetting01.primaryCpuAbiString, is("arm64-v8a"));
        assertThat(testPkgSetting01.secondaryCpuAbiString, is("armeabi"));
        assertThat(testPkgSetting01.origPackage, is(nullValue()));
        assertThat(testPkgSetting01.pkgFlags, is(0));
        assertThat(testPkgSetting01.pkgPrivateFlags, is(0));
        final PackageUserState userState = testPkgSetting01.readUserState(UserHandle.USER_SYSTEM);
        verifyUserState(userState, PackageSettingBase.DEFAULT_USER_STATE /*oldUserState*/,
                true /*userStateChanged*/, true /*notLaunched*/, true /*stopped*/);
    }

    /** Update new package; install for UserHandle.SYSTEM */
    @Test
    public void testUpdatePackageSetting07()
            throws ReflectiveOperationException, PackageManagerException {
        final PackageSetting testPkgSetting01 = Settings.updatePackageSetting(
                null /*pkgSetting*/,
                PACKAGE_NAME,
                null /*realPkgName*/,
                null /*originalPkg*/,
                null /*disabledPkg*/,
                null /*sharedUser*/,
                UPDATED_CODE_PATH /*codePath*/,
                UPDATED_CODE_PATH /*resourcePath*/,
                null /*legacyNativeLibraryPath*/,
                "arm64-v8a" /*primaryCpuAbi*/,
                "armeabi" /*secondaryCpuAbi*/,
                UPDATED_VERSION_CODE /*versionCode*/,
                0 /*pkgFlags*/,
                0 /*pkgPrivateFlags*/,
                UserHandle.SYSTEM /*installUser*/,
                true /*allowInstall*/,
                null /*parentPkgName*/,
                null /*childPkgNames*/,
                UserManagerService.getInstance());
        assertThat(testPkgSetting01, is(notNullValue()));
        assertThat(testPkgSetting01.primaryCpuAbiString, is("arm64-v8a"));
        assertThat(testPkgSetting01.secondaryCpuAbiString, is("armeabi"));
        assertThat(testPkgSetting01.origPackage, is(nullValue()));
        assertThat(testPkgSetting01.pkgFlags, is(0));
        assertThat(testPkgSetting01.pkgPrivateFlags, is(0));
        final PackageUserState userState = testPkgSetting01.readUserState(UserHandle.USER_SYSTEM);
        verifyUserState(userState, PackageSettingBase.DEFAULT_USER_STATE /*oldUserState*/,
                true /*userStateChanged*/, true /*notLaunched*/, true /*stopped*/);
    }

    /** Update package, but, shared user changed; ensure old setting not modified */
    @Test
    public void testUpdatePackageSetting08()
            throws ReflectiveOperationException, PackageManagerException {
        final SharedUserSetting testSharedUser =
                new SharedUserSetting("testSharedUser", 0 /*pkgFlags*/, 0 /*_pkgPrivateFlags*/);
        testSharedUser.userId = Process.FIRST_APPLICATION_UID + 9995;
        final PackageSetting pkgSetting01 = createPackageSetting(0 /*pkgFlags*/);
        final PackageSetting oldPkgSetting01 = new PackageSetting(pkgSetting01);
        final PackageSetting testPkgSetting01 = Settings.updatePackageSetting(
                pkgSetting01,
                PACKAGE_NAME,
                null /*realPkgName*/,
                null /*originalPkg*/,
                null /*disabledPkg*/,
                testSharedUser /*sharedUser*/,
                UPDATED_CODE_PATH /*codePath*/,
                UPDATED_CODE_PATH /*resourcePath*/,
                null /*legacyNativeLibraryPath*/,
                "arm64-v8a" /*primaryCpuAbi*/,
                "armeabi" /*secondaryCpuAbi*/,
                UPDATED_VERSION_CODE /*versionCode*/,
                0 /*pkgFlags*/,
                0 /*pkgPrivateFlags*/,
                UserHandle.SYSTEM /*installUser*/,
                true /*allowInstall*/,
                null /*parentPkgName*/,
                null /*childPkgNames*/,
                UserManagerService.getInstance());
        assertThat(testPkgSetting01, is(not(pkgSetting01)));
        assertThat(testPkgSetting01.primaryCpuAbiString, is("arm64-v8a"));
        assertThat(testPkgSetting01.secondaryCpuAbiString, is("armeabi"));
        assertThat(testPkgSetting01.origPackage, is(nullValue()));
        assertThat(testPkgSetting01.appId, is(19995));
        assertThat(testPkgSetting01.pkgFlags, is(0));
        assertThat(testPkgSetting01.pkgPrivateFlags, is(0));
        // package setting should not have been modified
        assertThat(pkgSetting01.primaryCpuAbiString, is("x86_64"));
        assertThat(pkgSetting01.secondaryCpuAbiString, is("x86"));
        final PackageUserState userState = testPkgSetting01.readUserState(UserHandle.USER_SYSTEM);
        final PackageUserState oldUserState = oldPkgSetting01.readUserState(UserHandle.USER_SYSTEM);
        verifyUserState(userState, oldUserState, true /*userStateChanged*/,
                true /*notLaunched*/, true /*stopped*/);
    }

    private void verifyUserState(PackageUserState userState, PackageUserState oldUserState,
            boolean userStateChanged) {
        verifyUserState(userState, oldUserState, userStateChanged, false /*notLaunched*/,
                false /*stopped*/);
    }

    private void verifyUserState(PackageUserState userState, PackageUserState oldUserState,
            boolean userStateChanged, boolean notLaunched, boolean stopped) {
        assertThat(userState.blockUninstall, is(false));
        assertThat(userState.enabled, is(0));
        assertThat(userState.hidden, is(false));
        assertThat(userState.installed, is(true));
        assertThat(userState.notLaunched, is(notLaunched));
        assertThat(userState.stopped, is(stopped));
        assertThat(userState.suspended, is(false));
        if (oldUserState != null) {
            assertThat(userState.equals(oldUserState), is(not(userStateChanged)));
        }
    }

    private PackageSetting createPackageSetting(int pkgFlags) {
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
                0 /*sharedUserId*/);
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
                + "<package name=\"com.google.app1\" codePath=\"/system/app/app1.apk\" nativeLibraryPath=\"/data/data/com.google.app1/lib\" flags=\"1\" ft=\"1360e2caa70\" it=\"135f2f80d08\" ut=\"1360e2caa70\" version=\"1109\" sharedUserId=\"11000\">"
                + "<sigs count=\"1\">"
                + "<cert index=\"0\" key=\"" + KeySetStrings.ctsKeySetCertA + "\" />"
                + "</sigs>"
                + "<proper-signing-keyset identifier=\"1\" />"
                + "</package>"
                + "<package name=\"com.google.app2\" codePath=\"/system/app/app2.apk\" nativeLibraryPath=\"/data/data/com.google.app2/lib\" flags=\"1\" ft=\"1360e578718\" it=\"135f2f80d08\" ut=\"1360e578718\" version=\"15\" enabled=\"3\" userId=\"11001\">"
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

    private void writeStoppedPackagesXml() {
        writeFile(new File(InstrumentationRegistry.getContext().getFilesDir(), "system/packages-stopped.xml"),
                ( "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<stopped-packages>"
                + "<pkg name=\"com.google.app1\" nl=\"1\" />"
                + "<pkg name=\"com.android.app3\" nl=\"1\" />"
                + "</stopped-packages>")
                .getBytes());
    }

    private void writePackagesList() {
        writeFile(new File(InstrumentationRegistry.getContext().getFilesDir(), "system/packages.list"),
                ( "com.google.app1 11000 0 /data/data/com.google.app1 seinfo1"
                + "com.google.app2 11001 0 /data/data/com.google.app2 seinfo2"
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
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                Constructor<UserManagerService> umsc;
                try {
                    // unregister the user manager from the local service
                    Method removeServiceForTest = LocalServices.class.getDeclaredMethod(
                            "removeServiceForTest", Class.class);
                    removeServiceForTest.invoke(null, UserManagerInternal.class);

                    // now create a new user manager [which registers again with the local service]
                    umsc = UserManagerService.class.getDeclaredConstructor(
                            Context.class,
                            PackageManagerService.class,
                            Object.class,
                            File.class);
                    umsc.setAccessible(true);
                    UserManagerService ums = umsc.newInstance(InstrumentationRegistry.getContext(),
                            null /*PackageManagerService*/, new Object() /*packagesLock*/,
                            new File(InstrumentationRegistry.getContext().getFilesDir(), "user"));
                } catch (SecurityException
                        | ReflectiveOperationException
                        | IllegalArgumentException e) {
                    fail("Could not create user manager service; " + e);
                }
            }
        });
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
        PackageSetting ps = packages.get("com.google.app1");
        assertThat(ps.keySetData.getProperSigningKeySet(), is(1L));
        ps = packages.get("com.google.app2");
        assertThat(ps.keySetData.getProperSigningKeySet(), is(1L));
        assertThat(ps.keySetData.getAliases().get("AB"), is(4L));
        ps = packages.get("com.android.app3");
        assertThat(ps.keySetData.getProperSigningKeySet(), is(2L));
        assertThat(ps.keySetData.getAliases().get("C"), is(3L));
        assertThat(ps.keySetData.getUpgradeKeySets().length, is(1));
        assertThat(ps.keySetData.getUpgradeKeySets()[0], is(3L));
    }
}
