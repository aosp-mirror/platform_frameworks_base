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

import static android.os.UserManager.USER_TYPE_FULL_GUEST;
import static android.os.UserManager.USER_TYPE_FULL_SECONDARY;
import static android.os.UserManager.USER_TYPE_PROFILE_MANAGED;

import static com.android.server.pm.UserSystemPackageInstaller.PACKAGE_WHITELIST_MODE_PROP;
import static com.android.server.pm.UserSystemPackageInstaller.USER_TYPE_PACKAGE_WHITELIST_MODE_DEVICE_DEFAULT;
import static com.android.server.pm.UserSystemPackageInstaller.USER_TYPE_PACKAGE_WHITELIST_MODE_DISABLE;
import static com.android.server.pm.UserSystemPackageInstaller.USER_TYPE_PACKAGE_WHITELIST_MODE_ENFORCE;
import static com.android.server.pm.UserSystemPackageInstaller.USER_TYPE_PACKAGE_WHITELIST_MODE_IGNORE_OTA;
import static com.android.server.pm.UserSystemPackageInstaller.USER_TYPE_PACKAGE_WHITELIST_MODE_IMPLICIT_WHITELIST;
import static com.android.server.pm.UserSystemPackageInstaller.USER_TYPE_PACKAGE_WHITELIST_MODE_IMPLICIT_WHITELIST_SYSTEM;
import static com.android.server.pm.UserSystemPackageInstaller.USER_TYPE_PACKAGE_WHITELIST_MODE_LOG;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.PropertyInvalidatedCache;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
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
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.PackageImpl;
import com.android.server.pm.parsing.pkg.ParsedPackage;

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
        // Disable binder caches in this process.
        PropertyInvalidatedCache.disableForTestMode();

        LocalServices.removeServiceForTest(UserManagerInternal.class);
        UserManagerService ums = new UserManagerService(InstrumentationRegistry.getContext());

        ArrayMap<String, UserTypeDetails> userTypes = UserTypeFactory.getUserTypes();
        mUserSystemPackageInstaller = new UserSystemPackageInstaller(ums, userTypes);
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
                        "PROFILE", "SYSTEM", USER_TYPE_FULL_GUEST, "invalid-garbage1")));
                r.put("com.android.package2", new ArraySet<>(Arrays.asList(
                        USER_TYPE_PROFILE_MANAGED)));
                r.put("com.android.package3", new ArraySet<>(Arrays.asList("FULL")));
                return r;
            }

            @Override
            public ArrayMap<String, Set<String>> getAndClearPackageToUserTypeBlacklist() {
                ArrayMap<String, Set<String>> r = new ArrayMap<>();
                r.put("com.android.package1", new ArraySet<>(Arrays.asList(
                        USER_TYPE_PROFILE_MANAGED, "invalid-garbage2")));
                // com.android.package2 has nothing blacklisted
                r.put("com.android.package3", new ArraySet<>(Arrays.asList("SYSTEM")));
                return r;
            }
        };

        final ArrayMap<String, UserTypeDetails> userTypes = UserTypeFactory.getUserTypes();
        // Determine the expected userTypeBitSets based on getUserTypeMask.
        long expectedUserTypeBitSet1 = 0;
        expectedUserTypeBitSet1
                |= mUserSystemPackageInstaller.getUserTypeMask(USER_TYPE_FULL_GUEST);
        for (int i = 0; i < userTypes.size(); i++) {
            final String userType = userTypes.keyAt(i);
            final UserTypeDetails details = userTypes.valueAt(i);
            if (details.isSystem() || details.isProfile()) {
                expectedUserTypeBitSet1 |= mUserSystemPackageInstaller.getUserTypeMask(userType);
            }
        }
        expectedUserTypeBitSet1
                &= ~mUserSystemPackageInstaller.getUserTypeMask(USER_TYPE_PROFILE_MANAGED);

        final long expectedUserTypeBitSet2 =
                mUserSystemPackageInstaller.getUserTypeMask(USER_TYPE_PROFILE_MANAGED);

        long expectedUserTypeBitSet3 = 0;
        for (int i = 0; i < userTypes.size(); i++) {
            final String userType = userTypes.keyAt(i);
            final UserTypeDetails details = userTypes.valueAt(i);
            if (details.isFull() && !details.isSystem()) {
                expectedUserTypeBitSet3 |= mUserSystemPackageInstaller.getUserTypeMask(userType);
            }
        }

        final ArrayMap<String, Long> expectedOutput = getNewPackageToWhitelistedBitSetMap();
        expectedOutput.put("com.android.package1", expectedUserTypeBitSet1);
        expectedOutput.put("com.android.package2", expectedUserTypeBitSet2);
        expectedOutput.put("com.android.package3", expectedUserTypeBitSet3);

        final ArrayMap<String, Long> actualOutput =
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

        final ArrayMap<String, Long> expectedOutput = getNewPackageToWhitelistedBitSetMap();
        expectedOutput.put("com.android.package2", 0L);
        expectedOutput.put("com.android.package3", 0L);
        expectedOutput.put("com.android.package4", 0L);

        final ArrayMap<String, Long> actualOutput =
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

        // Whitelist: user type bitset for each pkg (for the test, all that matters is 0 vs non-0).
        final ArrayMap<String, Long> pkgBitSetMap = new ArrayMap<>();
        pkgBitSetMap.put(packageName1, 0b01L);
        pkgBitSetMap.put(packageName2, 0L);
        pkgBitSetMap.put(packageName3, 0b10L);

        // Whitelist of pkgs for this specific user, i.e. subset of pkgBitSetMap for this user.
        final Set<String> userWhitelist = new ArraySet<>();
        userWhitelist.add(packageName1);

        final AndroidPackage pkg1 = ((ParsedPackage) PackageImpl.forTesting(packageName1)
                .hideAsParsed()).hideAsFinal();
        final AndroidPackage pkg2 = ((ParsedPackage) PackageImpl.forTesting(packageName2)
                .hideAsParsed()).hideAsFinal();
        final AndroidPackage pkg3 = ((ParsedPackage) PackageImpl.forTesting(packageName3)
                .hideAsParsed()).hideAsFinal();
        final AndroidPackage pkg4 = ((ParsedPackage) PackageImpl.forTesting(packageName4)
                .hideAsParsed()).hideAsFinal();

        // No implicit whitelist, so only install pkg1.
        boolean implicit = false;
        assertTrue(UserSystemPackageInstaller.shouldInstallPackage(
                pkg1, pkgBitSetMap, userWhitelist, implicit));
        assertFalse(UserSystemPackageInstaller.shouldInstallPackage(
                pkg2, pkgBitSetMap, userWhitelist, implicit));
        assertFalse(UserSystemPackageInstaller.shouldInstallPackage(
                pkg3, pkgBitSetMap, userWhitelist, implicit));
        assertFalse(UserSystemPackageInstaller.shouldInstallPackage(
                pkg4, pkgBitSetMap, userWhitelist, implicit));

        // Use implicit whitelist, so install pkg1 and pkg4
        implicit = true;
        assertTrue(UserSystemPackageInstaller.shouldInstallPackage(
                pkg1, pkgBitSetMap, userWhitelist, implicit));
        assertFalse(UserSystemPackageInstaller.shouldInstallPackage(
                pkg2, pkgBitSetMap, userWhitelist, implicit));
        assertFalse(UserSystemPackageInstaller.shouldInstallPackage(
                pkg3, pkgBitSetMap, userWhitelist, implicit));
        assertTrue(UserSystemPackageInstaller.shouldInstallPackage(
                pkg4, pkgBitSetMap, userWhitelist, implicit));
    }

    /**
     * Tests that getWhitelistedPackagesForUserType works properly, assuming that
     * mWhitelistedPackagesForUserTypes (i.e. determineWhitelistedPackagesForUserTypes) is correct.
     */
    @Test
    public void testGetWhitelistedPackagesForUserType() {
        final String[] sortedUserTypes = new String[]{"type_a", "type_b", "type_c", "type_d"};
        final String nameOfTypeA = sortedUserTypes[0];
        final String nameOfTypeB = sortedUserTypes[1];
        final String nameOfTypeC = sortedUserTypes[2];
        final long maskOfTypeA = 0b0001L;
        final long maskOfTypeC = 0b0100L;

        final String packageName1 = "pkg1"; // whitelisted for user type A
        final String packageName2 = "pkg2"; // blacklisted whenever whitelisted
        final String packageName3 = "pkg3"; // whitelisted for user type C
        final String packageName4 = "pkg4"; // whitelisted for user type A

        final ArrayMap<String, Long> pkgBitSetMap = new ArrayMap<>(); // Whitelist: bitset per pkg
        pkgBitSetMap.put(packageName1, maskOfTypeA);
        pkgBitSetMap.put(packageName2, 0L);
        pkgBitSetMap.put(packageName3, maskOfTypeC);
        pkgBitSetMap.put(packageName4, maskOfTypeA);

        UserSystemPackageInstaller uspi =
                new UserSystemPackageInstaller(null, pkgBitSetMap, sortedUserTypes);

        Set<String> output = uspi.getWhitelistedPackagesForUserType(nameOfTypeA);
        assertEquals("Whitelist for FULL is the wrong size", 2, output.size());
        assertTrue("Whitelist for A doesn't contain pkg1", output.contains(packageName1));
        assertTrue("Whitelist for A doesn't contain pkg4", output.contains(packageName4));

        output = uspi.getWhitelistedPackagesForUserType(nameOfTypeB);
        assertEquals("Whitelist for B is the wrong size", 0, output.size());

        output = uspi.getWhitelistedPackagesForUserType(nameOfTypeC);
        assertEquals("Whitelist for C is the wrong size", 1, output.size());
        assertTrue("Whitelist for C doesn't contain pkg1", output.contains(packageName3));
    }

    /**
     * Test that a newly created FULL user has the expected system packages.
     *
     * Assumes that SystemConfig and UserManagerService.determineWhitelistedPackagesForUserTypes
     * work correctly (they are tested separately).
     */
    @Test
    public void testPackagesForCreateUser_full() {
        final String userTypeToCreate = USER_TYPE_FULL_SECONDARY;
        final long userTypeMask = mUserSystemPackageInstaller.getUserTypeMask(userTypeToCreate);
        setUserTypePackageWhitelistMode(USER_TYPE_PACKAGE_WHITELIST_MODE_ENFORCE);
        PackageManager pm = mContext.getPackageManager();

        final SystemConfig sysConfig = new SystemConfigTestClass(true);
        final ArrayMap<String, Long> packageMap =
                mUserSystemPackageInstaller.determineWhitelistedPackagesForUserTypes(sysConfig);
        final Set<String> expectedPackages = new ArraySet<>(packageMap.size());
        for (int i = 0; i < packageMap.size(); i++) {
            if ((userTypeMask & packageMap.valueAt(i)) != 0) {
                expectedPackages.add(packageMap.keyAt(i));
            }
        }

        final UserManager um = UserManager.get(mContext);
        final UserInfo user = um.createUser("Test User", userTypeToCreate, 0);
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

        // Add auto-generated RRO package to expectedPackages since they are not (supposed to be)
        // in the whitelist but they should be installed.
        for (PackageInfo p : packageInfos) {
            if (p.isOverlayPackage()
                        && UserSystemPackageInstaller.hasAutoGeneratedRROSuffix(p.packageName)
                        && expectedPackages.contains(p.overlayTarget)) {
                expectedPackages.add(p.packageName);
            }
        }
        checkPackageDifferences(expectedPackages, actualPackages);
    }

    @Test
    public void testAutoGeneratedRROMatchesSuffix() {
        final List<PackageInfo> packageInfos = mContext.getPackageManager()
                .getInstalledPackages(PackageManager.GET_UNINSTALLED_PACKAGES);

        Log.v(TAG, "Found total packages: " + packageInfos.size());

        for (PackageInfo p : packageInfos) {
            if (p.packageName.contains(".auto_generated_rro_")) {
                assertTrue("Auto-generated RRO package name does not match the suffix: "
                        + p.packageName,
                        UserSystemPackageInstaller.hasAutoGeneratedRROSuffix(p.packageName));
            }
        }
    }

    /**
     * Test that overlay package not in whitelist should be installed for all user at Explicit mode.
     */
    @Test
    public void testInstallOverlayPackagesExplicitMode() {
        setUserTypePackageWhitelistMode(USER_TYPE_PACKAGE_WHITELIST_MODE_ENFORCE);

        final String[] userTypes = new String[]{"type"};
        final long maskOfType = 0b0001L;

        final String packageName1 = "whitelistedPkg";
        final String packageName2 = "nonWhitelistedPkg";
        final String overlayName1 = String.format("%s.auto_generated_rro_product__", packageName1);
        final String overlayName2 = String.format("%s.auto_generated_rro_product__", packageName2);

        final AndroidPackage overlayPackage1 = ((ParsedPackage) PackageImpl.forTesting(overlayName1)
                .setOverlay(true)
                .setOverlayTarget(packageName1)
                .hideAsParsed())
                .hideAsFinal();

        final AndroidPackage overlayPackage2 = ((ParsedPackage) PackageImpl.forTesting(overlayName2)
                .setOverlay(true)
                .setOverlayTarget(packageName2)
                .hideAsParsed())
                .hideAsFinal();

        final ArrayMap<String, Long> userTypeWhitelist = new ArrayMap<>();
        userTypeWhitelist.put(packageName1, maskOfType);

        final Set<String> userWhitelist = new ArraySet<>();
        userWhitelist.add(packageName1);

        boolean implicit = false;
        assertTrue("Overlay for package1 should be installed", UserSystemPackageInstaller
                .shouldInstallPackage(overlayPackage1, userTypeWhitelist, userWhitelist, implicit));
        assertFalse("Overlay for package2 should not be installed", UserSystemPackageInstaller
                .shouldInstallPackage(overlayPackage2, userTypeWhitelist, userWhitelist, implicit));
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
        assertFalse(mUserSystemPackageInstaller.isImplicitWhitelistSystemMode());
        assertFalse(mUserSystemPackageInstaller.isIgnoreOtaMode());

        setUserTypePackageWhitelistMode(USER_TYPE_PACKAGE_WHITELIST_MODE_LOG);
        assertTrue(mUserSystemPackageInstaller.isLogMode());
        assertFalse(mUserSystemPackageInstaller.isEnforceMode());
        assertFalse(mUserSystemPackageInstaller.isImplicitWhitelistMode());
        assertFalse(mUserSystemPackageInstaller.isImplicitWhitelistSystemMode());
        assertFalse(mUserSystemPackageInstaller.isIgnoreOtaMode());

        setUserTypePackageWhitelistMode(USER_TYPE_PACKAGE_WHITELIST_MODE_ENFORCE);
        assertFalse(mUserSystemPackageInstaller.isLogMode());
        assertTrue(mUserSystemPackageInstaller.isEnforceMode());
        assertFalse(mUserSystemPackageInstaller.isImplicitWhitelistMode());
        assertFalse(mUserSystemPackageInstaller.isImplicitWhitelistSystemMode());
        assertFalse(mUserSystemPackageInstaller.isIgnoreOtaMode());

        setUserTypePackageWhitelistMode(USER_TYPE_PACKAGE_WHITELIST_MODE_IMPLICIT_WHITELIST);
        assertFalse(mUserSystemPackageInstaller.isLogMode());
        assertFalse(mUserSystemPackageInstaller.isEnforceMode());
        assertTrue(mUserSystemPackageInstaller.isImplicitWhitelistMode());
        assertFalse(mUserSystemPackageInstaller.isImplicitWhitelistSystemMode());
        assertFalse(mUserSystemPackageInstaller.isIgnoreOtaMode());

        setUserTypePackageWhitelistMode(USER_TYPE_PACKAGE_WHITELIST_MODE_IMPLICIT_WHITELIST_SYSTEM);
        assertFalse(mUserSystemPackageInstaller.isLogMode());
        assertFalse(mUserSystemPackageInstaller.isEnforceMode());
        assertFalse(mUserSystemPackageInstaller.isImplicitWhitelistMode());
        assertTrue(mUserSystemPackageInstaller.isImplicitWhitelistSystemMode());
        assertFalse(mUserSystemPackageInstaller.isIgnoreOtaMode());

        setUserTypePackageWhitelistMode(USER_TYPE_PACKAGE_WHITELIST_MODE_IGNORE_OTA);
        assertFalse(mUserSystemPackageInstaller.isLogMode());
        assertFalse(mUserSystemPackageInstaller.isEnforceMode());
        assertFalse(mUserSystemPackageInstaller.isImplicitWhitelistMode());
        assertFalse(mUserSystemPackageInstaller.isImplicitWhitelistSystemMode());
        assertTrue(mUserSystemPackageInstaller.isIgnoreOtaMode());

        setUserTypePackageWhitelistMode(
                USER_TYPE_PACKAGE_WHITELIST_MODE_LOG | USER_TYPE_PACKAGE_WHITELIST_MODE_ENFORCE);
        assertTrue(mUserSystemPackageInstaller.isLogMode());
        assertTrue(mUserSystemPackageInstaller.isEnforceMode());
        assertFalse(mUserSystemPackageInstaller.isImplicitWhitelistMode());
        assertFalse(mUserSystemPackageInstaller.isImplicitWhitelistSystemMode());
        assertFalse(mUserSystemPackageInstaller.isIgnoreOtaMode());

        setUserTypePackageWhitelistMode(USER_TYPE_PACKAGE_WHITELIST_MODE_IMPLICIT_WHITELIST
                | USER_TYPE_PACKAGE_WHITELIST_MODE_ENFORCE);
        assertFalse(mUserSystemPackageInstaller.isLogMode());
        assertTrue(mUserSystemPackageInstaller.isEnforceMode());
        assertTrue(mUserSystemPackageInstaller.isImplicitWhitelistMode());
        assertFalse(mUserSystemPackageInstaller.isImplicitWhitelistSystemMode());
        assertFalse(mUserSystemPackageInstaller.isIgnoreOtaMode());
    }

    /** Sets the whitelist mode to the desired value via adb's setprop. */
    private void setUserTypePackageWhitelistMode(int mode) {
        UiDevice uiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try {
            String result = uiDevice.executeShellCommand(String.format("setprop %s %d",
                    PACKAGE_WHITELIST_MODE_PROP, mode));
            assertFalse("Failed to set sysprop " + PACKAGE_WHITELIST_MODE_PROP + ": " + result,
                    result != null && result.contains("Failed"));
        } catch (IOException e) {
            fail("Failed to set sysprop " + PACKAGE_WHITELIST_MODE_PROP + ":\n" + e);
        }
    }

    /** @see UserSystemPackageInstaller#mWhitelistedPackagesForUserTypes */
    private ArrayMap<String, Long> getNewPackageToWhitelistedBitSetMap() {
        final ArrayMap<String, Long> pkgBitSetMap = new ArrayMap<>();
        // "android" is always treated as whitelisted for all types, regardless of the xml file.
        pkgBitSetMap.put("android", ~0L);
        return pkgBitSetMap;
    }
}
