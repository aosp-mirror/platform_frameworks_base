/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.content.pm.UserInfo.FLAG_FULL;
import static android.content.pm.UserInfo.FLAG_GUEST;
import static android.content.pm.UserInfo.FLAG_MANAGED_PROFILE;
import static android.content.pm.UserInfo.FLAG_SYSTEM;

import static com.android.server.pm.UserSystemPackageInstaller.PACKAGE_WHITELIST_MODE_PROP;
import static com.android.server.pm.UserSystemPackageInstaller.USER_TYPE_PACKAGE_WHITELIST_MODE_DEVICE_DEFAULT;
import static com.android.server.pm.UserSystemPackageInstaller.USER_TYPE_PACKAGE_WHITELIST_MODE_DISABLE;
import static com.android.server.pm.UserSystemPackageInstaller.USER_TYPE_PACKAGE_WHITELIST_MODE_ENFORCE;
import static com.android.server.pm.UserSystemPackageInstaller.USER_TYPE_PACKAGE_WHITELIST_MODE_IMPLICIT_WHITELIST;
import static com.android.server.pm.UserSystemPackageInstaller.USER_TYPE_PACKAGE_WHITELIST_MODE_LOG;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.UserInfo;
import android.os.Looper;
import android.os.SystemProperties;
import android.os.UserManager;
import android.os.UserManagerInternal;
import android.support.test.uiautomator.UiDevice;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;
import com.android.server.SystemConfig;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Tests for UserSystemPackageInstaller.
 *
 * <p>Run with:<pre>
 * atest com.android.server.pm.UserSystemPackageInstallerTest
 * </pre>
 */
@RunWith(AndroidJUnit4.class)
@MediumTest
public class UserSystemPackageInstallerTest {
    private static final String TAG = "UserSystemPackageInstallerTest";

    private UserSystemPackageInstaller mUserSystemPackageInstaller;

    private Context mContext;

    /** Any users created during this test, for them to be removed when it's done. */
    private final List<Integer> mRemoveUsers = new ArrayList<>();
    /** Original value of PACKAGE_WHITELIST_MODE_PROP before the test, to reset at end. */
    private final int mOriginalWhitelistMode = SystemProperties.getInt(
            PACKAGE_WHITELIST_MODE_PROP, USER_TYPE_PACKAGE_WHITELIST_MODE_DEVICE_DEFAULT);

    @Before
    public void setup() {
        // Currently UserManagerService cannot be instantiated twice inside a VM without a cleanup
        // TODO: Remove once UMS supports proper dependency injection
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        LocalServices.removeServiceForTest(UserManagerInternal.class);
        UserManagerService ums = new UserManagerService(InstrumentationRegistry.getContext());

        mUserSystemPackageInstaller = new UserSystemPackageInstaller(ums);
        mContext = InstrumentationRegistry.getTargetContext();
    }

    @After
    public void tearDown() {
        UserManager um = UserManager.get(mContext);
        for (int userId : mRemoveUsers) {
            um.removeUser(userId);
        }
        setUserTypePackageWhitelistMode(mOriginalWhitelistMode);
    }

    /**
     * Subclass of SystemConfig without running the constructor.
     */
    private class SystemConfigTestClass extends SystemConfig {
        SystemConfigTestClass(boolean readPermissions) {
            super(readPermissions);
        }
    }

    /**
     * Test that determineWhitelistedPackagesForUserTypes reads SystemConfig information properly.
     */
    @Test
    public void testDetermineWhitelistedPackagesForUserTypes() {
        SystemConfig sysConfig = new SystemConfigTestClass(false) {
            @Override
            public ArrayMap<String, Set<String>> getAndClearPackageToUserTypeWhitelist() {
                ArrayMap<String, Set<String>> r = new ArrayMap<>();
                r.put("com.android.package1", new ArraySet<>(Arrays.asList(
                        "PROFILE", "SYSTEM", "GUEST", "FULL", "invalid-garbage1")));
                r.put("com.android.package2", new ArraySet<>(Arrays.asList(
                        "MANAGED_PROFILE")));
                return r;
            }

            @Override
            public ArrayMap<String, Set<String>> getAndClearPackageToUserTypeBlacklist() {
                ArrayMap<String, Set<String>> r = new ArrayMap<>();
                r.put("com.android.package1", new ArraySet<>(Arrays.asList(
                        "FULL", "RESTRICTED", "invalid-garbage2")));
                return r;
            }
        };

        final ArrayMap<String, Integer> expectedOutput = getNewPackageToWhitelistedFlagsMap();
        expectedOutput.put("com.android.package1",
                UserInfo.PROFILE_FLAGS_MASK | FLAG_SYSTEM | FLAG_GUEST);
        expectedOutput.put("com.android.package2",
                UserInfo.FLAG_MANAGED_PROFILE);

        final ArrayMap<String, Integer> actualOutput =
                mUserSystemPackageInstaller.determineWhitelistedPackagesForUserTypes(sysConfig);

        assertEquals("Incorrect package-to-user mapping.", expectedOutput, actualOutput);
    }

    /**
     * Test that determineWhitelistedPackagesForUserTypes does not include packages that were never
     * whitelisted properly, but does include packages that were whitelisted but then blacklisted.
     */
    @Test
    public void testDetermineWhitelistedPackagesForUserTypes_noNetWhitelisting() {
        SystemConfig sysConfig = new SystemConfigTestClass(false) {
            @Override
            public ArrayMap<String, Set<String>> getAndClearPackageToUserTypeWhitelist() {
                ArrayMap<String, Set<String>> r = new ArrayMap<>();
                r.put("com.android.package1", new ArraySet<>(Arrays.asList("invalid1")));
                // com.android.package2 has no whitelisting
                r.put("com.android.package3", new ArraySet<>(Arrays.asList("PROFILE", "FULL")));
                r.put("com.android.package4", new ArraySet<>(Arrays.asList("PROFILE")));
                r.put("com.android.package5", new ArraySet<>());
                // com.android.package6 has no whitelisting
                return r;
            }

            @Override
            public ArrayMap<String, Set<String>> getAndClearPackageToUserTypeBlacklist() {
                ArrayMap<String, Set<String>> r = new ArrayMap<>();
                // com.android.package1 has no blacklisting
                r.put("com.android.package2", new ArraySet<>(Arrays.asList("FULL")));
                r.put("com.android.package3", new ArraySet<>(Arrays.asList("PROFILE", "FULL")));
                r.put("com.android.package4", new ArraySet<>(Arrays.asList("PROFILE", "invalid4")));
                // com.android.package5 has no blacklisting
                r.put("com.android.package6", new ArraySet<>(Arrays.asList("invalid6")));
                return r;
            }
        };

        final ArrayMap<String, Integer> expectedOutput = getNewPackageToWhitelistedFlagsMap();
        expectedOutput.put("com.android.package3", 0);
        expectedOutput.put("com.android.package4", 0);

        final ArrayMap<String, Integer> actualOutput =
                mUserSystemPackageInstaller.determineWhitelistedPackagesForUserTypes(sysConfig);

        assertEquals("Incorrect package-to-user mapping.", expectedOutput, actualOutput);
    }

    /**
     * Tests that shouldInstallPackage correctly determines which packages should be installed.
     */
    @Test
    public void testShouldInstallPackage() {
        final String packageName1 = "pkg1"; // whitelisted
        final String packageName2 = "pkg2"; // whitelisted and blacklisted
        final String packageName3 = "pkg3"; // whitelisted for a different user type
        final String packageName4 = "pkg4"; // not whitelisted nor blacklisted at all

        final ArrayMap<String, Integer> pkgFlgMap = new ArrayMap<>(); // Whitelist: pkgs per flags
        pkgFlgMap.put(packageName1, FLAG_FULL);
        pkgFlgMap.put(packageName2, 0);
        pkgFlgMap.put(packageName3, FLAG_MANAGED_PROFILE);

        // Whitelist of pkgs for this specific user, i.e. subset of pkgFlagMap for this user.
        final Set<String> userWhitelist = new ArraySet<>();
        userWhitelist.add(packageName1);

        final UserSystemPackageInstaller uspi = new UserSystemPackageInstaller(null, pkgFlgMap);

        final PackageParser.Package pkg1 = new PackageParser.Package(packageName1);
        final PackageParser.Package pkg2 = new PackageParser.Package(packageName2);
        final PackageParser.Package pkg3 = new PackageParser.Package(packageName3);
        final PackageParser.Package pkg4 = new PackageParser.Package(packageName4);

        // No implicit whitelist, so only install pkg1.
        boolean implicit = false;
        boolean isSysUser = false;
        assertTrue(uspi.shouldInstallPackage(pkg1, pkgFlgMap, userWhitelist, implicit, isSysUser));
        assertFalse(uspi.shouldInstallPackage(pkg2, pkgFlgMap, userWhitelist, implicit, isSysUser));
        assertFalse(uspi.shouldInstallPackage(pkg3, pkgFlgMap, userWhitelist, implicit, isSysUser));
        assertFalse(uspi.shouldInstallPackage(pkg4, pkgFlgMap, userWhitelist, implicit, isSysUser));

        // Use implicit whitelist, so install pkg1 and pkg4
        implicit = true;
        isSysUser = false;
        assertTrue(uspi.shouldInstallPackage(pkg1, pkgFlgMap, userWhitelist, implicit, isSysUser));
        assertFalse(uspi.shouldInstallPackage(pkg2, pkgFlgMap, userWhitelist, implicit, isSysUser));
        assertFalse(uspi.shouldInstallPackage(pkg3, pkgFlgMap, userWhitelist, implicit, isSysUser));
        assertTrue(uspi.shouldInstallPackage(pkg4, pkgFlgMap, userWhitelist, implicit, isSysUser));

        // For user 0 specifically, we always implicitly whitelist.
        implicit = false;
        isSysUser = true;
        assertTrue(uspi.shouldInstallPackage(pkg1, pkgFlgMap, userWhitelist, implicit, isSysUser));
        assertFalse(uspi.shouldInstallPackage(pkg2, pkgFlgMap, userWhitelist, implicit, isSysUser));
        assertFalse(uspi.shouldInstallPackage(pkg3, pkgFlgMap, userWhitelist, implicit, isSysUser));
        assertTrue(uspi.shouldInstallPackage(pkg4, pkgFlgMap, userWhitelist, implicit, isSysUser));
    }

    /**
     * Tests that getWhitelistedPackagesForUserType works properly, assuming that
     * mWhitelistedPackagesForUserTypes (i.e. determineWhitelistedPackagesForUserTypes) is correct.
     */
    @Test
    public void testGetWhitelistedPackagesForUserType() {
        final String packageName1 = "pkg1"; // whitelisted for FULL
        final String packageName2 = "pkg2"; // blacklisted whenever whitelisted
        final String packageName3 = "pkg3"; // whitelisted for SYSTEM
        final String packageName4 = "pkg4"; // whitelisted for FULL

        final ArrayMap<String, Integer> pkgFlagMap = new ArrayMap<>(); // Whitelist: pkgs per flags
        pkgFlagMap.put(packageName1, FLAG_FULL);
        pkgFlagMap.put(packageName2, 0);
        pkgFlagMap.put(packageName3, FLAG_SYSTEM);
        pkgFlagMap.put(packageName4, FLAG_FULL);

        // Whitelist of pkgs for this specific user, i.e. subset of pkgFlagMap for this user.
        final Set<String> expectedUserWhitelist = new ArraySet<>();
        expectedUserWhitelist.add(packageName1);

        UserSystemPackageInstaller uspi = new UserSystemPackageInstaller(null, pkgFlagMap);

        Set<String> output = uspi.getWhitelistedPackagesForUserType(FLAG_FULL);
        assertEquals("Whitelist for FULL is the wrong size", 2, output.size());
        assertTrue("Whitelist for FULL doesn't contain pkg1", output.contains(packageName1));
        assertTrue("Whitelist for FULL doesn't contain pkg4", output.contains(packageName4));

        output = uspi.getWhitelistedPackagesForUserType(FLAG_SYSTEM);
        assertEquals("Whitelist for SYSTEM is the wrong size", 1, output.size());
        assertTrue("Whitelist for SYSTEM doesn't contain pkg1", output.contains(packageName3));
    }

    /**
     * Test that a newly created FULL user has the expected system packages.
     *
     * Assumes that SystemConfig and UserManagerService.determineWhitelistedPackagesForUserTypes
     * work correctly (they are tested separately).
     */
    @Test
    public void testPackagesForCreateUser_full() {
        final int userFlags = UserInfo.FLAG_FULL;
        setUserTypePackageWhitelistMode(USER_TYPE_PACKAGE_WHITELIST_MODE_ENFORCE);
        PackageManager pm = mContext.getPackageManager();

        final SystemConfig sysConfig = new SystemConfigTestClass(true);
        final ArrayMap<String, Integer> packageMap =
                mUserSystemPackageInstaller.determineWhitelistedPackagesForUserTypes(sysConfig);
        final Set<String> expectedPackages = new ArraySet<>(packageMap.size());
        for (int i = 0; i < packageMap.size(); i++) {
            if ((userFlags & packageMap.valueAt(i)) != 0) {
                expectedPackages.add(packageMap.keyAt(i));
            }
        }

        final UserManager um = UserManager.get(mContext);
        final UserInfo user = um.createUser("Test User", userFlags);
        assertNotNull(user);
        mRemoveUsers.add(user.id);

        final List<PackageInfo> packageInfos = pm.getInstalledPackagesAsUser(
                PackageManager.MATCH_SYSTEM_ONLY
                        | PackageManager.MATCH_DISABLED_COMPONENTS
                        | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS,
                user.id);
        final Set<String> actualPackages = new ArraySet<>(packageInfos.size());
        for (PackageInfo p : packageInfos) {
            actualPackages.add(p.packageName);
        }
        checkPackageDifferences(expectedPackages, actualPackages);
    }

    /** Asserts that actual is a subset of expected. */
    private void checkPackageDifferences(Set<String> expected, Set<String> actual) {
        final Set<String> uniqueToExpected = new ArraySet<>(expected);
        uniqueToExpected.removeAll(actual);
        final Set<String> uniqueToActual = new ArraySet<>(actual);
        uniqueToActual.removeAll(expected);

        Log.v(TAG, "Expected list uniquely has " + uniqueToExpected);
        Log.v(TAG, "Actual list uniquely has " + uniqueToActual);

        assertTrue("User's system packages includes non-whitelisted packages: " + uniqueToActual,
                uniqueToActual.isEmpty());
    }

    /**
     * Test that setEnableUserTypePackageWhitelist() has the correct effect.
     */
    @Test
    public void testSetWhitelistEnabledMode() {
        setUserTypePackageWhitelistMode(USER_TYPE_PACKAGE_WHITELIST_MODE_DISABLE);
        assertFalse(mUserSystemPackageInstaller.isLogMode());
        assertFalse(mUserSystemPackageInstaller.isEnforceMode());
        assertFalse(mUserSystemPackageInstaller.isImplicitWhitelistMode());

        setUserTypePackageWhitelistMode(USER_TYPE_PACKAGE_WHITELIST_MODE_LOG);
        assertTrue(mUserSystemPackageInstaller.isLogMode());
        assertFalse(mUserSystemPackageInstaller.isEnforceMode());
        assertFalse(mUserSystemPackageInstaller.isImplicitWhitelistMode());

        setUserTypePackageWhitelistMode(USER_TYPE_PACKAGE_WHITELIST_MODE_ENFORCE);
        assertFalse(mUserSystemPackageInstaller.isLogMode());
        assertTrue(mUserSystemPackageInstaller.isEnforceMode());
        assertFalse(mUserSystemPackageInstaller.isImplicitWhitelistMode());

        setUserTypePackageWhitelistMode(USER_TYPE_PACKAGE_WHITELIST_MODE_IMPLICIT_WHITELIST);
        assertFalse(mUserSystemPackageInstaller.isLogMode());
        assertFalse(mUserSystemPackageInstaller.isEnforceMode());
        assertTrue(mUserSystemPackageInstaller.isImplicitWhitelistMode());

        setUserTypePackageWhitelistMode(
                USER_TYPE_PACKAGE_WHITELIST_MODE_LOG | USER_TYPE_PACKAGE_WHITELIST_MODE_ENFORCE);
        assertTrue(mUserSystemPackageInstaller.isLogMode());
        assertTrue(mUserSystemPackageInstaller.isEnforceMode());
        assertFalse(mUserSystemPackageInstaller.isImplicitWhitelistMode());

        setUserTypePackageWhitelistMode(USER_TYPE_PACKAGE_WHITELIST_MODE_IMPLICIT_WHITELIST
                | USER_TYPE_PACKAGE_WHITELIST_MODE_ENFORCE);
        assertFalse(mUserSystemPackageInstaller.isLogMode());
        assertTrue(mUserSystemPackageInstaller.isEnforceMode());
        assertTrue(mUserSystemPackageInstaller.isImplicitWhitelistMode());
    }

    /** Sets the whitelist mode to the desired value via adb's setprop. */
    private void setUserTypePackageWhitelistMode(int mode) {
        UiDevice mUiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try {
            String result = mUiDevice.executeShellCommand(String.format("setprop %s %d",
                    PACKAGE_WHITELIST_MODE_PROP, mode));
            assertFalse("Failed to set sysprop " + PACKAGE_WHITELIST_MODE_PROP + ": " + result,
                    result != null && result.contains("Failed"));
        } catch (IOException e) {
            fail("Failed to set sysprop " + PACKAGE_WHITELIST_MODE_PROP + ":\n" + e);
        }
    }

    private ArrayMap<String, Integer> getNewPackageToWhitelistedFlagsMap() {
        final ArrayMap<String, Integer> pkgFlagMap = new ArrayMap<>();
        // "android" is always treated as whitelisted, regardless of the xml file.
        pkgFlagMap.put("android", FLAG_SYSTEM | UserInfo.FLAG_FULL | UserInfo.PROFILE_FLAGS_MASK);
        return pkgFlagMap;
    }
}
