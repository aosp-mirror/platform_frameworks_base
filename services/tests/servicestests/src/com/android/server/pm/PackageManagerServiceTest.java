/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static com.android.compatibility.common.util.ShellUtils.runShellCommand;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.fail;

import static java.lang.reflect.Modifier.isFinal;
import static java.lang.reflect.Modifier.isPublic;
import static java.lang.reflect.Modifier.isStatic;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppGlobals;
import android.content.IIntentReceiver;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.Postsubmit;
import android.util.SparseArray;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.HexDump;
import com.android.server.pm.PerPackageReadTimeouts.Timeouts;
import com.android.server.pm.PerPackageReadTimeouts.VersionCodes;

import com.google.android.collect.Lists;

import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

// atest PackageManagerServiceTest
// runtest -c com.android.server.pm.PackageManagerServiceTest frameworks-services
// bit FrameworksServicesTests:com.android.server.pm.PackageManagerServiceTest
@Postsubmit
@RunWith(AndroidJUnit4.class)
public class PackageManagerServiceTest {

    private static final String PACKAGE_NAME = "com.android.frameworks.servicestests";

    private static final String TEST_DATA_PATH = "/data/local/tmp/servicestests/";
    private static final String TEST_APP_APK = "StubTestApp.apk";
    private static final String TEST_PKG_NAME = "com.android.servicestests.apps.stubapp";

    private IPackageManager mIPackageManager;

    @Before
    public void setUp() throws Exception {
        mIPackageManager = AppGlobals.getPackageManager();
    }

    @After
    public void tearDown() throws Exception {
    }

    @Test
    public void testPackageRemoval() {
        class PackageSenderImpl implements PackageSender {
            public void sendPackageBroadcast(final String action, final String pkg,
                    final Bundle extras, final int flags, final String targetPkg,
                    final IIntentReceiver finishedReceiver, final int[] userIds,
                    int[] instantUserIds, SparseArray<int[]> broadcastAllowList,
                    @Nullable Bundle bOptions) {
            }

            public void sendPackageAddedForNewUsers(@NonNull Computer snapshot, String packageName,
                    boolean sendBootComplete, boolean includeStopped, int appId,
                    int[] userIds, int[] instantUserIds, int dataLoaderType) {
            }

            @Override
            public void notifyPackageAdded(String packageName, int uid) {
            }

            @Override
            public void notifyPackageChanged(String packageName, int uid) {

            }

            @Override
            public void notifyPackageRemoved(String packageName, int uid) {
            }
        }

        PackageSenderImpl sender = new PackageSenderImpl();
        PackageSetting setting = null;
        PackageRemovedInfo pri = new PackageRemovedInfo(sender);

        // Initial conditions: nothing there
        Assert.assertNull(pri.mRemovedUsers);
        Assert.assertNull(pri.mBroadcastUsers);

        // populateUsers with nothing leaves nothing
        pri.populateUsers(null, setting);
        Assert.assertNull(pri.mBroadcastUsers);

        // Create a real (non-null) PackageSetting and confirm that the removed
        // users are copied properly
        setting = new PackageSettingBuilder()
                .setName("name")
                .setRealName("realName")
                .setCodePath("codePath")
                .setLegacyNativeLibraryPathString("legacyNativeLibraryPathString")
                .setPrimaryCpuAbiString("primaryCpuAbiString")
                .setSecondaryCpuAbiString("secondaryCpuAbiString")
                .setCpuAbiOverrideString("cpuAbiOverrideString")
                .build();
        pri.populateUsers(new int[] {
                1, 2, 3, 4, 5
        }, setting);
        Assert.assertNotNull(pri.mBroadcastUsers);
        Assert.assertEquals(5, pri.mBroadcastUsers.length);
        Assert.assertNotNull(pri.mInstantUserIds);
        Assert.assertEquals(0, pri.mInstantUserIds.length);

        // Exclude a user
        pri.mBroadcastUsers = null;
        final int EXCLUDED_USER_ID = 4;
        setting.setInstantApp(true, EXCLUDED_USER_ID);
        pri.populateUsers(new int[] {
                1, 2, 3, EXCLUDED_USER_ID, 5
        }, setting);
        Assert.assertNotNull(pri.mBroadcastUsers);
        Assert.assertEquals(4, pri.mBroadcastUsers.length);
        Assert.assertNotNull(pri.mInstantUserIds);
        Assert.assertEquals(1, pri.mInstantUserIds.length);

        // TODO: test that sendApplicationHiddenForUser() actually fills in
        // broadcastUsers
    }

    @Test
    public void testPartitions() {
        String[] partitions = { "system", "vendor", "odm", "oem", "product", "system_ext" };
        String[] appdir = { "app", "priv-app" };
        for (int i = 0; i < partitions.length; i++) {
            final ScanPartition scanPartition =
                    PackageManagerService.SYSTEM_PARTITIONS.get(i);
            for (int j = 0; j < appdir.length; j++) {
                File path = new File(String.format("%s/%s/A.apk", partitions[i], appdir[j]));
                Assert.assertEquals(j == 1 && i != 3, scanPartition.containsPrivApp(path));

                final int scanFlag = scanPartition.scanFlag;
                Assert.assertEquals(i == 1, scanFlag == PackageManagerService.SCAN_AS_VENDOR);
                Assert.assertEquals(i == 2, scanFlag == PackageManagerService.SCAN_AS_ODM);
                Assert.assertEquals(i == 3, scanFlag == PackageManagerService.SCAN_AS_OEM);
                Assert.assertEquals(i == 4, scanFlag == PackageManagerService.SCAN_AS_PRODUCT);
                Assert.assertEquals(i == 5, scanFlag == PackageManagerService.SCAN_AS_SYSTEM_EXT);
            }
        }
    }

    @Test
    public void testKnownPackageToString_shouldNotGetUnknown() {
        final List<String> packageNames = new ArrayList<>();
        for (int i = 0; i <= KnownPackages.LAST_KNOWN_PACKAGE; i++) {
            packageNames.add(KnownPackages.knownPackageToString(i));
        }
        assertWithMessage(
                "The Ids of KnownPackage should be continuous and the string representation "
                        + "should not be unknown.").that(
                packageNames).containsNoneIn(Lists.newArrayList("Unknown"));
    }

    @Test
    public void testKnownPackage_lastKnownPackageIsTheLast() throws Exception {
        final List<Integer> knownPackageIds = getKnownPackageIdsList();
        assertWithMessage(
                "The last KnownPackage Id should be assigned to PackageManagerInternal"
                        + ".LAST_KNOWN_PACKAGE.").that(
                knownPackageIds.get(knownPackageIds.size() - 1)).isEqualTo(
                KnownPackages.LAST_KNOWN_PACKAGE);
    }

    @Test
    public void testKnownPackage_IdsShouldBeUniqueAndContinuous() throws Exception {
        final List<Integer> knownPackageIds = getKnownPackageIdsList();
        for (int i = 0, size = knownPackageIds.size(); i < size - 1; i++) {
            assertWithMessage(
                    "The KnownPackage Ids should be unique and continuous. KnownPackageIds = "
                            + Arrays.toString(knownPackageIds.toArray())).that(
                    knownPackageIds.get(i) + 1).isEqualTo(knownPackageIds.get(i + 1));
        }
    }

    @Test
    public void testTimeouts() {
        Timeouts defaults = Timeouts.parse("3600000001:3600000002:3600000003");
        Assert.assertEquals(3600000001L, defaults.minTimeUs);
        Assert.assertEquals(3600000002L, defaults.minPendingTimeUs);
        Assert.assertEquals(3600000003L, defaults.maxPendingTimeUs);

        Timeouts empty = Timeouts.parse("");
        Assert.assertEquals(3600000000L, empty.minTimeUs);
        Assert.assertEquals(3600000000L, empty.minPendingTimeUs);
        Assert.assertEquals(3600000000L, empty.maxPendingTimeUs);

        Timeouts partial0 = Timeouts.parse("10000::");
        Assert.assertEquals(10000L, partial0.minTimeUs);
        Assert.assertEquals(3600000000L, partial0.minPendingTimeUs);
        Assert.assertEquals(3600000000L, partial0.maxPendingTimeUs);

        Timeouts partial1 = Timeouts.parse("10000:10001:");
        Assert.assertEquals(10000L, partial1.minTimeUs);
        Assert.assertEquals(10001L, partial1.minPendingTimeUs);
        Assert.assertEquals(3600000000L, partial1.maxPendingTimeUs);

        Timeouts fullDefault = Timeouts.parse("3600000000:3600000000:3600000000");
        Assert.assertEquals(3600000000L, fullDefault.minTimeUs);
        Assert.assertEquals(3600000000L, fullDefault.minPendingTimeUs);
        Assert.assertEquals(3600000000L, fullDefault.maxPendingTimeUs);

        Timeouts full = Timeouts.parse("10000:10001:10002");
        Assert.assertEquals(10000L, full.minTimeUs);
        Assert.assertEquals(10001L, full.minPendingTimeUs);
        Assert.assertEquals(10002L, full.maxPendingTimeUs);

        Timeouts invalid0 = Timeouts.parse(":10000");
        Assert.assertEquals(3600000000L, invalid0.minTimeUs);
        Assert.assertEquals(3600000000L, invalid0.minPendingTimeUs);
        Assert.assertEquals(3600000000L, invalid0.maxPendingTimeUs);

        Timeouts invalid1 = Timeouts.parse(":10000::");
        Assert.assertEquals(3600000000L, invalid1.minTimeUs);
        Assert.assertEquals(3600000000L, invalid1.minPendingTimeUs);
        Assert.assertEquals(3600000000L, invalid1.maxPendingTimeUs);

        Timeouts invalid2 = Timeouts.parse("10000:10001:abcd");
        Assert.assertEquals(10000L, invalid2.minTimeUs);
        Assert.assertEquals(10001L, invalid2.minPendingTimeUs);
        Assert.assertEquals(3600000000L, invalid2.maxPendingTimeUs);

        Timeouts invalid3 = Timeouts.parse(":10000:");
        Assert.assertEquals(3600000000L, invalid3.minTimeUs);
        Assert.assertEquals(3600000000L, invalid3.minPendingTimeUs);
        Assert.assertEquals(3600000000L, invalid3.maxPendingTimeUs);

        Timeouts invalid4 = Timeouts.parse("abcd:10001:10002");
        Assert.assertEquals(3600000000L, invalid4.minTimeUs);
        Assert.assertEquals(3600000000L, invalid4.minPendingTimeUs);
        Assert.assertEquals(3600000000L, invalid4.maxPendingTimeUs);

        Timeouts invalid5 = Timeouts.parse("::1000000000000000000000000");
        Assert.assertEquals(3600000000L, invalid5.minTimeUs);
        Assert.assertEquals(3600000000L, invalid5.minPendingTimeUs);
        Assert.assertEquals(3600000000L, invalid5.maxPendingTimeUs);

        Timeouts invalid6 = Timeouts.parse("-10000:10001:10002");
        Assert.assertEquals(3600000000L, invalid6.minTimeUs);
        Assert.assertEquals(3600000000L, invalid6.minPendingTimeUs);
        Assert.assertEquals(3600000000L, invalid6.maxPendingTimeUs);
    }

    @Test
    public void testVersionCodes() {
        final VersionCodes defaults = VersionCodes.parse("");
        Assert.assertEquals(Long.MIN_VALUE, defaults.minVersionCode);
        Assert.assertEquals(Long.MAX_VALUE, defaults.maxVersionCode);

        VersionCodes single = VersionCodes.parse("191000070");
        Assert.assertEquals(191000070, single.minVersionCode);
        Assert.assertEquals(191000070, single.maxVersionCode);

        VersionCodes single2 = VersionCodes.parse("191000070-191000070");
        Assert.assertEquals(191000070, single2.minVersionCode);
        Assert.assertEquals(191000070, single2.maxVersionCode);

        VersionCodes upto = VersionCodes.parse("-191000070");
        Assert.assertEquals(Long.MIN_VALUE, upto.minVersionCode);
        Assert.assertEquals(191000070, upto.maxVersionCode);

        VersionCodes andabove = VersionCodes.parse("191000070-");
        Assert.assertEquals(191000070, andabove.minVersionCode);
        Assert.assertEquals(Long.MAX_VALUE, andabove.maxVersionCode);

        VersionCodes range = VersionCodes.parse("191000070-201000070");
        Assert.assertEquals(191000070, range.minVersionCode);
        Assert.assertEquals(201000070, range.maxVersionCode);

        VersionCodes invalid0 = VersionCodes.parse("201000070-191000070");
        Assert.assertEquals(Long.MIN_VALUE, invalid0.minVersionCode);
        Assert.assertEquals(Long.MAX_VALUE, invalid0.maxVersionCode);

        VersionCodes invalid1 = VersionCodes.parse("abcd-191000070");
        Assert.assertEquals(Long.MIN_VALUE, invalid1.minVersionCode);
        Assert.assertEquals(191000070, invalid1.maxVersionCode);

        VersionCodes invalid2 = VersionCodes.parse("abcd");
        Assert.assertEquals(Long.MIN_VALUE, invalid2.minVersionCode);
        Assert.assertEquals(Long.MAX_VALUE, invalid2.maxVersionCode);

        VersionCodes invalid3 = VersionCodes.parse("191000070-abcd");
        Assert.assertEquals(191000070, invalid3.minVersionCode);
        Assert.assertEquals(Long.MAX_VALUE, invalid3.maxVersionCode);
    }

    @Test
    public void testPerPackageReadTimeouts() {
        final String sha256 = "336faefc91bb2dddf9b21829106fbc607b862132fecd273e1b6b3ea55f09d4e1";
        final VersionCodes defVCs = VersionCodes.parse("");
        final Timeouts defTs = Timeouts.parse("3600000001:3600000002:3600000003");

        PerPackageReadTimeouts empty = PerPackageReadTimeouts.parse("", defVCs, defTs);
        Assert.assertNull(empty);

        PerPackageReadTimeouts packageOnly = PerPackageReadTimeouts.parse("package.com", defVCs,
                defTs);
        Assert.assertEquals("package.com", packageOnly.packageName);
        Assert.assertEquals(null, packageOnly.sha256certificate);
        Assert.assertEquals(Long.MIN_VALUE, packageOnly.versionCodes.minVersionCode);
        Assert.assertEquals(Long.MAX_VALUE, packageOnly.versionCodes.maxVersionCode);
        Assert.assertEquals(3600000001L, packageOnly.timeouts.minTimeUs);
        Assert.assertEquals(3600000002L, packageOnly.timeouts.minPendingTimeUs);
        Assert.assertEquals(3600000003L, packageOnly.timeouts.maxPendingTimeUs);

        PerPackageReadTimeouts packageHash = PerPackageReadTimeouts.parse(
                "package.com:" + sha256, defVCs, defTs);
        Assert.assertEquals("package.com", packageHash.packageName);
        Assert.assertEquals(sha256, bytesToHexString(packageHash.sha256certificate));
        Assert.assertEquals(Long.MIN_VALUE, packageHash.versionCodes.minVersionCode);
        Assert.assertEquals(Long.MAX_VALUE, packageHash.versionCodes.maxVersionCode);
        Assert.assertEquals(3600000001L, packageHash.timeouts.minTimeUs);
        Assert.assertEquals(3600000002L, packageHash.timeouts.minPendingTimeUs);
        Assert.assertEquals(3600000003L, packageHash.timeouts.maxPendingTimeUs);

        PerPackageReadTimeouts packageVersionCode = PerPackageReadTimeouts.parse(
                "package.com::191000070", defVCs, defTs);
        Assert.assertEquals("package.com", packageVersionCode.packageName);
        Assert.assertEquals(null, packageVersionCode.sha256certificate);
        Assert.assertEquals(191000070, packageVersionCode.versionCodes.minVersionCode);
        Assert.assertEquals(191000070, packageVersionCode.versionCodes.maxVersionCode);
        Assert.assertEquals(3600000001L, packageVersionCode.timeouts.minTimeUs);
        Assert.assertEquals(3600000002L, packageVersionCode.timeouts.minPendingTimeUs);
        Assert.assertEquals(3600000003L, packageVersionCode.timeouts.maxPendingTimeUs);

        PerPackageReadTimeouts full = PerPackageReadTimeouts.parse(
                "package.com:" + sha256 + ":191000070-201000070:10001:10002:10003", defVCs, defTs);
        Assert.assertEquals("package.com", full.packageName);
        Assert.assertEquals(sha256, bytesToHexString(full.sha256certificate));
        Assert.assertEquals(191000070, full.versionCodes.minVersionCode);
        Assert.assertEquals(201000070, full.versionCodes.maxVersionCode);
        Assert.assertEquals(10001L, full.timeouts.minTimeUs);
        Assert.assertEquals(10002L, full.timeouts.minPendingTimeUs);
        Assert.assertEquals(10003L, full.timeouts.maxPendingTimeUs);
    }

    @Test
    public void testGetPerPackageReadTimeouts() {
        Assert.assertEquals(0, getPerPackageReadTimeouts(null).length);
        Assert.assertEquals(0, getPerPackageReadTimeouts("").length);
        Assert.assertEquals(0, getPerPackageReadTimeouts(",,,,").length);

        final String sha256 = "0fae93f1a7925b4c68bbea80ad3eaa41acfc9bc6f10bf1054f5d93a2bd556093";

        PerPackageReadTimeouts[] singlePackage = getPerPackageReadTimeouts(
                "package.com:" + sha256 + ":191000070-201000070:10001:10002:10003");
        Assert.assertEquals(1, singlePackage.length);
        Assert.assertEquals("package.com", singlePackage[0].packageName);
        Assert.assertEquals(sha256, bytesToHexString(singlePackage[0].sha256certificate));
        Assert.assertEquals(191000070, singlePackage[0].versionCodes.minVersionCode);
        Assert.assertEquals(201000070, singlePackage[0].versionCodes.maxVersionCode);
        Assert.assertEquals(10001L, singlePackage[0].timeouts.minTimeUs);
        Assert.assertEquals(10002L, singlePackage[0].timeouts.minPendingTimeUs);
        Assert.assertEquals(10003L, singlePackage[0].timeouts.maxPendingTimeUs);

        PerPackageReadTimeouts[] multiPackage = getPerPackageReadTimeouts("package.com:" + sha256
                + ":191000070-201000070:10001:10002:10003,package1.com::123456");
        Assert.assertEquals(2, multiPackage.length);
        Assert.assertEquals("package.com", multiPackage[0].packageName);
        Assert.assertEquals(sha256, bytesToHexString(multiPackage[0].sha256certificate));
        Assert.assertEquals(191000070, multiPackage[0].versionCodes.minVersionCode);
        Assert.assertEquals(201000070, multiPackage[0].versionCodes.maxVersionCode);
        Assert.assertEquals(10001L, multiPackage[0].timeouts.minTimeUs);
        Assert.assertEquals(10002L, multiPackage[0].timeouts.minPendingTimeUs);
        Assert.assertEquals(10003L, multiPackage[0].timeouts.maxPendingTimeUs);
        Assert.assertEquals("package1.com", multiPackage[1].packageName);
        Assert.assertEquals(null, multiPackage[1].sha256certificate);
        Assert.assertEquals(123456, multiPackage[1].versionCodes.minVersionCode);
        Assert.assertEquals(123456, multiPackage[1].versionCodes.maxVersionCode);
        Assert.assertEquals(3600000001L, multiPackage[1].timeouts.minTimeUs);
        Assert.assertEquals(3600000002L, multiPackage[1].timeouts.minPendingTimeUs);
        Assert.assertEquals(3600000003L, multiPackage[1].timeouts.maxPendingTimeUs);
    }

    // Report an error from the Computer structure validation test.
    private void flag(String name, String msg) {
        fail(name + " " + msg);
    }

    // Return a string that identifies a Method.  This is not very efficient but it is not
    // called very often.
    private String displayName(Method m) {
        String r = m.getName();
        String p = Arrays.toString(m.getGenericParameterTypes())
                   .replaceAll("([a-zA-Z0-9]+\\.)+", "")
                   .replace("class ", "")
                   .replaceAll("^\\[", "(")
                   .replaceAll("\\]$", ")");
        return r + p;
    }

    // Match a method to an array of Methods.  Matching is on method signature: name and
    // parameter types.  If a method in the declared array matches, return it.  Otherwise
    // return null.
    private Method matchMethod(Method m, Method[] declared) {
        String n = m.getName();
        Type[] t = m.getGenericParameterTypes();
        for (int i = 0; i < declared.length; i++) {
            Method l = declared[i];
            if (l != null && l.getName().equals(n)
                    && Arrays.equals(l.getGenericParameterTypes(), t)) {
                Method result = l;
                // Set the method to null since it has been visited already.
                declared[i] = null;
                return result;
            }
        }
        return null;
    }

    private static PerPackageReadTimeouts[] getPerPackageReadTimeouts(String knownDigestersList) {
        final String defaultTimeouts = "3600000001:3600000002:3600000003";
        List<PerPackageReadTimeouts> result = PerPackageReadTimeouts.parseDigestersList(
                defaultTimeouts, knownDigestersList);
        if (result == null) {
            return null;
        }
        return result.toArray(new PerPackageReadTimeouts[result.size()]);
    }

    private static String bytesToHexString(byte[] bytes) {
        return HexDump.toHexString(bytes, 0, bytes.length, /*upperCase=*/ false);
    }

    private List<Integer> getKnownPackageIdsList() throws IllegalAccessException {
        final ArrayList<Integer> knownPackageIds = new ArrayList<>();
        final Field[] allFields = KnownPackages.class.getDeclaredFields();
        for (Field field : allFields) {
            final int modifier = field.getModifiers();
            if (isPublic(modifier) && isStatic(modifier) && isFinal(modifier)
                    && Pattern.matches("PACKAGE(_[A-Z]+)+", field.getName())) {
                knownPackageIds.add(field.getInt(null));
            }
        }
        Collections.sort(knownPackageIds);
        return knownPackageIds;
    }

    @Test
    public void testInstallReason_afterUpdate_keepUnchanged() throws Exception {
        final File testApk = new File(TEST_DATA_PATH, TEST_APP_APK);
        try {
            // Try to install test APK with reason INSTALL_REASON_DEVICE_SETUP
            runShellCommand("pm install --install-reason 3 " + testApk);
            assertWithMessage("The install reason of test APK is incorrect.").that(
                    mIPackageManager.getInstallReason(TEST_PKG_NAME,
                            UserHandle.myUserId())).isEqualTo(
                    PackageManager.INSTALL_REASON_DEVICE_SETUP);

            // Try to update test APK with different reason INSTALL_REASON_USER
            runShellCommand("pm install --install-reason 4 " + testApk);
            assertWithMessage("The install reason should keep unchanged after update.").that(
                    mIPackageManager.getInstallReason(TEST_PKG_NAME,
                            UserHandle.myUserId())).isEqualTo(
                    PackageManager.INSTALL_REASON_DEVICE_SETUP);
        } finally {
            runShellCommand("pm uninstall " + TEST_PKG_NAME);
        }
    }

    @Test
    public void testInstallReason_userRemainsUninstalled_keepUnknown() throws Exception {
        Assume.assumeTrue(UserManager.supportsMultipleUsers());
        final UserManager um = UserManager.get(
                InstrumentationRegistry.getInstrumentation().getContext());
        final File testApk = new File(TEST_DATA_PATH, TEST_APP_APK);
        int userId = UserHandle.USER_NULL;
        try {
            // Try to install test APK with reason INSTALL_REASON_DEVICE_SETUP
            runShellCommand("pm install --install-reason 3 " + testApk);
            assertWithMessage("The install reason of test APK is incorrect.").that(
                    mIPackageManager.getInstallReason(TEST_PKG_NAME,
                            UserHandle.myUserId())).isEqualTo(
                    PackageManager.INSTALL_REASON_DEVICE_SETUP);

            // Create and start the 2nd user.
            userId = um.createUser("Test User", 0 /* flags */).getUserHandle().getIdentifier();
            runShellCommand("am start-user -w " + userId);
            // Since the test APK isn't installed on the 2nd user, the reason should be unknown.
            assertWithMessage("The test APK should not be installed in the 2nd user").that(
                    mIPackageManager.getPackageInfo(TEST_PKG_NAME, 0 /* flags */, userId)).isNull();
            assertWithMessage("The install reason in 2nd user should be unknown.").that(
                    mIPackageManager.getInstallReason(TEST_PKG_NAME, userId)).isEqualTo(
                    PackageManager.INSTALL_REASON_UNKNOWN);
        } finally {
            runShellCommand("pm uninstall " + TEST_PKG_NAME);
            if (userId != UserHandle.USER_NULL) {
                um.removeUser(userId);
            }
        }
    }

    @Test
    public void testInstallReason_installForAllUsers_sameReason() throws Exception {
        Assume.assumeTrue(UserManager.supportsMultipleUsers());
        final UserManager um = UserManager.get(
                InstrumentationRegistry.getInstrumentation().getContext());
        final File testApk = new File(TEST_DATA_PATH, TEST_APP_APK);
        int userId = UserHandle.USER_NULL;
        try {
            // Create and start the 2nd user.
            userId = um.createUser("Test User", 0 /* flags */).getUserHandle().getIdentifier();
            runShellCommand("am start-user -w " + userId);

            // Try to install test APK to all users with reason INSTALL_REASON_DEVICE_SETUP
            runShellCommand("pm install --install-reason 3 " + testApk);
            assertWithMessage("The install reason is inconsistent across users.").that(
                    mIPackageManager.getInstallReason(TEST_PKG_NAME,
                            UserHandle.myUserId())).isEqualTo(
                    mIPackageManager.getInstallReason(TEST_PKG_NAME, userId));
        } finally {
            runShellCommand("pm uninstall " + TEST_PKG_NAME);
            if (userId != UserHandle.USER_NULL) {
                um.removeUser(userId);
            }
        }
    }

    @Test
    public void testInstallReason_installSeparately_withSeparatedReason() throws Exception {
        Assume.assumeTrue(UserManager.supportsMultipleUsers());
        final UserManager um = UserManager.get(
                InstrumentationRegistry.getInstrumentation().getContext());
        final File testApk = new File(TEST_DATA_PATH, TEST_APP_APK);
        int userId = UserHandle.USER_NULL;
        try {
            // Create and start the 2nd user.
            userId = um.createUser("Test User", 0 /* flags */).getUserHandle().getIdentifier();
            runShellCommand("am start-user -w " + userId);

            // Try to install test APK on the current user with reason INSTALL_REASON_DEVICE_SETUP
            runShellCommand("pm install --user cur --install-reason 3 " + testApk);
            assertWithMessage("The install reason on the current user is incorrect.").that(
                    mIPackageManager.getInstallReason(TEST_PKG_NAME,
                            UserHandle.myUserId())).isEqualTo(
                    PackageManager.INSTALL_REASON_DEVICE_SETUP);

            // Try to install test APK on the 2nd user with reason INSTALL_REASON_USER
            runShellCommand("pm install --user " + userId + " --install-reason 4 " + testApk);
            assertWithMessage("The install reason on the 2nd user is incorrect.").that(
                    mIPackageManager.getInstallReason(TEST_PKG_NAME, userId)).isEqualTo(
                    PackageManager.INSTALL_REASON_USER);
        } finally {
            runShellCommand("pm uninstall " + TEST_PKG_NAME);
            if (userId != UserHandle.USER_NULL) {
                um.removeUser(userId);
            }
        }
    }

    @Test
    public void testSetSplashScreenTheme_samePackage_succeeds() throws Exception {
        mIPackageManager.setSplashScreenTheme(PACKAGE_NAME, null /* themeName */,
                UserHandle.myUserId());
        // Invoking setSplashScreenTheme on the same package shouldn't get any exception.
    }

    @Test
    public void testSetSplashScreenTheme_differentPackage_fails() throws Exception {
        final File testApk = new File(TEST_DATA_PATH, TEST_APP_APK);
        try {
            runShellCommand("pm install " + testApk);
            mIPackageManager.setSplashScreenTheme(TEST_PKG_NAME, null /* themeName */,
                    UserHandle.myUserId());
            fail("setSplashScreenTheme did not throw SecurityException as expected");
        } catch (SecurityException e) {
            // expected
        } finally {
            runShellCommand("pm uninstall " + TEST_PKG_NAME);
        }
    }
}
