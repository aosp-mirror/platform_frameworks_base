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
package com.android.server.pm;

import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureGroupInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageParser;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.os.Bundle;
import android.os.Parcel;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.MediumTest;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

import android.util.ArrayMap;
import android.util.ArraySet;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import libcore.io.IoUtils;

@RunWith(AndroidJUnit4.class)
@MediumTest
public class PackageParserTest {
    private File mTmpDir;
    private static final File FRAMEWORK = new File("/system/framework/framework-res.apk");

    @Before
    public void setUp() {
        // Create a new temporary directory for each of our tests.
        mTmpDir = IoUtils.createTemporaryDirectory("PackageParserTest");
    }

    @Test
    public void testParse_noCache() throws Exception {
        PackageParser pp = new CachePackageNameParser();
        PackageParser.Package pkg = pp.parsePackage(FRAMEWORK, 0 /* parseFlags */,
                false /* useCaches */);
        assertNotNull(pkg);

        pp.setCacheDir(mTmpDir);
        pkg = pp.parsePackage(FRAMEWORK, 0 /* parseFlags */,
                false /* useCaches */);
        assertNotNull(pkg);

        // Make sure that we always write out a cache entry for future reference,
        // whether or not we're asked to use caches.
        assertEquals(1, mTmpDir.list().length);
    }

    @Test
    public void testParse_withCache() throws Exception {
        PackageParser pp = new CachePackageNameParser();

        pp.setCacheDir(mTmpDir);
        // The first parse will write this package to the cache.
        pp.parsePackage(FRAMEWORK, 0 /* parseFlags */, true /* useCaches */);

        // Now attempt to parse the package again, should return the
        // cached result.
        PackageParser.Package pkg = pp.parsePackage(FRAMEWORK, 0 /* parseFlags */,
                true /* useCaches */);
        assertEquals("cache_android", pkg.packageName);

        // Try again, with useCaches == false, shouldn't return the parsed
        // result.
        pkg = pp.parsePackage(FRAMEWORK, 0 /* parseFlags */, false /* useCaches */);
        assertEquals("android", pkg.packageName);

        // We haven't set a cache directory here : the parse should still succeed,
        // just not using the cached results.
        pp = new CachePackageNameParser();
        pkg = pp.parsePackage(FRAMEWORK, 0 /* parseFlags */, true /* useCaches */);
        assertEquals("android", pkg.packageName);

        pkg = pp.parsePackage(FRAMEWORK, 0 /* parseFlags */, false /* useCaches */);
        assertEquals("android", pkg.packageName);
    }

    @Test
    public void test_serializePackage() throws Exception {
        PackageParser pp = new PackageParser();
        pp.setCacheDir(mTmpDir);

        PackageParser.Package pkg = pp.parsePackage(FRAMEWORK, 0 /* parseFlags */,
            true /* useCaches */);

        Parcel p = Parcel.obtain();
        pkg.writeToParcel(p, 0 /* flags */);

        p.setDataPosition(0);
        PackageParser.Package deserialized = new PackageParser.Package(p);

        assertPackagesEqual(pkg, deserialized);
    }

    @Test
    @SmallTest
    @Presubmit
    public void test_roundTripKnownFields() throws Exception {
        PackageParser.Package pkg = new PackageParser.Package("foo");
        setKnownFields(pkg);

        Parcel p = Parcel.obtain();
        pkg.writeToParcel(p, 0 /* flags */);

        p.setDataPosition(0);
        PackageParser.Package deserialized = new PackageParser.Package(p);
        assertAllFieldsExist(deserialized);
    }

    @Test
    public void test_stringInterning() throws Exception {
        PackageParser.Package pkg = new PackageParser.Package("foo");
        setKnownFields(pkg);

        Parcel p = Parcel.obtain();
        pkg.writeToParcel(p, 0 /* flags */);

        p.setDataPosition(0);
        PackageParser.Package deserialized = new PackageParser.Package(p);

        p.setDataPosition(0);
        PackageParser.Package deserialized2 = new PackageParser.Package(p);

        assertSame(deserialized.packageName, deserialized2.packageName);
        assertSame(deserialized.applicationInfo.permission,
                deserialized2.applicationInfo.permission);
        assertSame(deserialized.requestedPermissions.get(0),
                deserialized2.requestedPermissions.get(0));
        assertSame(deserialized.protectedBroadcasts.get(0),
                deserialized2.protectedBroadcasts.get(0));
        assertSame(deserialized.usesLibraries.get(0),
                deserialized2.usesLibraries.get(0));
        assertSame(deserialized.usesOptionalLibraries.get(0),
                deserialized2.usesOptionalLibraries.get(0));
        assertSame(deserialized.mVersionName, deserialized2.mVersionName);
        assertSame(deserialized.mSharedUserId, deserialized2.mSharedUserId);
    }


    /**
     * A trivial subclass of package parser that only caches the package name, and throws away
     * all other information.
     */
    public static class CachePackageNameParser extends PackageParser {
        @Override
        public byte[] toCacheEntry(Package pkg) {
            return ("cache_" + pkg.packageName).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public Package fromCacheEntry(byte[] cacheEntry) {
            return new Package(new String(cacheEntry, StandardCharsets.UTF_8));
        }
    }

    // NOTE: The equality assertions below are based on code autogenerated by IntelliJ.

    public static void assertPackagesEqual(PackageParser.Package a, PackageParser.Package b) {
        assertEquals(a.baseRevisionCode, b.baseRevisionCode);
        assertEquals(a.baseHardwareAccelerated, b.baseHardwareAccelerated);
        assertEquals(a.mVersionCode, b.mVersionCode);
        assertEquals(a.mSharedUserLabel, b.mSharedUserLabel);
        assertEquals(a.mPreferredOrder, b.mPreferredOrder);
        assertEquals(a.installLocation, b.installLocation);
        assertEquals(a.coreApp, b.coreApp);
        assertEquals(a.mRequiredForAllUsers, b.mRequiredForAllUsers);
        assertEquals(a.mCompileSdkVersion, b.mCompileSdkVersion);
        assertEquals(a.mCompileSdkVersionCodename, b.mCompileSdkVersionCodename);
        assertEquals(a.use32bitAbi, b.use32bitAbi);
        assertEquals(a.packageName, b.packageName);
        assertTrue(Arrays.equals(a.splitNames, b.splitNames));
        assertEquals(a.volumeUuid, b.volumeUuid);
        assertEquals(a.codePath, b.codePath);
        assertEquals(a.baseCodePath, b.baseCodePath);
        assertTrue(Arrays.equals(a.splitCodePaths, b.splitCodePaths));
        assertTrue(Arrays.equals(a.splitRevisionCodes, b.splitRevisionCodes));
        assertTrue(Arrays.equals(a.splitFlags, b.splitFlags));
        assertTrue(Arrays.equals(a.splitPrivateFlags, b.splitPrivateFlags));
        assertApplicationInfoEqual(a.applicationInfo, b.applicationInfo);

        assertEquals(a.permissions.size(), b.permissions.size());
        for (int i = 0; i < a.permissions.size(); ++i) {
            assertPermissionsEqual(a.permissions.get(i), b.permissions.get(i));
            assertSame(a.permissions.get(i).owner, a);
            assertSame(b.permissions.get(i).owner, b);
        }

        assertEquals(a.permissionGroups.size(), b.permissionGroups.size());
        for (int i = 0; i < a.permissionGroups.size(); ++i) {
            assertPermissionGroupsEqual(a.permissionGroups.get(i), b.permissionGroups.get(i));
        }

        assertEquals(a.activities.size(), b.activities.size());
        for (int i = 0; i < a.activities.size(); ++i) {
            assertActivitiesEqual(a.activities.get(i), b.activities.get(i));
        }

        assertEquals(a.receivers.size(), b.receivers.size());
        for (int i = 0; i < a.receivers.size(); ++i) {
            assertActivitiesEqual(a.receivers.get(i), b.receivers.get(i));
        }

        assertEquals(a.providers.size(), b.providers.size());
        for (int i = 0; i < a.providers.size(); ++i) {
            assertProvidersEqual(a.providers.get(i), b.providers.get(i));
        }

        assertEquals(a.services.size(), b.services.size());
        for (int i = 0; i < a.services.size(); ++i) {
            assertServicesEqual(a.services.get(i), b.services.get(i));
        }

        assertEquals(a.instrumentation.size(), b.instrumentation.size());
        for (int i = 0; i < a.instrumentation.size(); ++i) {
            assertInstrumentationEqual(a.instrumentation.get(i), b.instrumentation.get(i));
        }

        assertEquals(a.requestedPermissions, b.requestedPermissions);
        assertEquals(a.protectedBroadcasts, b.protectedBroadcasts);
        assertEquals(a.parentPackage, b.parentPackage);
        assertEquals(a.childPackages, b.childPackages);
        assertEquals(a.libraryNames, b.libraryNames);
        assertEquals(a.usesLibraries, b.usesLibraries);
        assertEquals(a.usesOptionalLibraries, b.usesOptionalLibraries);
        assertTrue(Arrays.equals(a.usesLibraryFiles, b.usesLibraryFiles));
        assertEquals(a.mOriginalPackages, b.mOriginalPackages);
        assertEquals(a.mRealPackage, b.mRealPackage);
        assertEquals(a.mAdoptPermissions, b.mAdoptPermissions);
        assertBundleApproximateEquals(a.mAppMetaData, b.mAppMetaData);
        assertEquals(a.mVersionName, b.mVersionName);
        assertEquals(a.mSharedUserId, b.mSharedUserId);
        assertTrue(Arrays.equals(a.mSigningDetails.signatures, b.mSigningDetails.signatures));
        assertTrue(Arrays.equals(a.mLastPackageUsageTimeInMills, b.mLastPackageUsageTimeInMills));
        assertEquals(a.mExtras, b.mExtras);
        assertEquals(a.mRestrictedAccountType, b.mRestrictedAccountType);
        assertEquals(a.mRequiredAccountType, b.mRequiredAccountType);
        assertEquals(a.mOverlayTarget, b.mOverlayTarget);
        assertEquals(a.mOverlayCategory, b.mOverlayCategory);
        assertEquals(a.mOverlayPriority, b.mOverlayPriority);
        assertEquals(a.mOverlayIsStatic, b.mOverlayIsStatic);
        assertEquals(a.mSigningDetails.publicKeys, b.mSigningDetails.publicKeys);
        assertEquals(a.mUpgradeKeySets, b.mUpgradeKeySets);
        assertEquals(a.mKeySetMapping, b.mKeySetMapping);
        assertEquals(a.cpuAbiOverride, b.cpuAbiOverride);
        assertTrue(Arrays.equals(a.restrictUpdateHash, b.restrictUpdateHash));
    }

    private static void assertBundleApproximateEquals(Bundle a, Bundle b) {
        if (a == b) {
            return;
        }

        // Force the bundles to be unparceled.
        a.getBoolean("foo");
        b.getBoolean("foo");

        assertEquals(a.toString(), b.toString());
    }

    private static void assertComponentsEqual(PackageParser.Component<?> a,
                                              PackageParser.Component<?> b) {
        assertEquals(a.className, b.className);
        assertBundleApproximateEquals(a.metaData, b.metaData);
        assertEquals(a.getComponentName(), b.getComponentName());

        if (a.intents != null && b.intents != null) {
            assertEquals(a.intents.size(), b.intents.size());
        } else if (a.intents == null || b.intents == null) {
            return;
        }

        for (int i = 0; i < a.intents.size(); ++i) {
            PackageParser.IntentInfo aIntent = a.intents.get(i);
            PackageParser.IntentInfo bIntent = b.intents.get(i);

            assertEquals(aIntent.hasDefault, bIntent.hasDefault);
            assertEquals(aIntent.labelRes, bIntent.labelRes);
            assertEquals(aIntent.nonLocalizedLabel, bIntent.nonLocalizedLabel);
            assertEquals(aIntent.icon, bIntent.icon);
            assertEquals(aIntent.logo, bIntent.logo);
            assertEquals(aIntent.banner, bIntent.banner);
            assertEquals(aIntent.preferred, bIntent.preferred);
        }
    }

    private static void assertPermissionsEqual(PackageParser.Permission a,
                                               PackageParser.Permission b) {
        assertComponentsEqual(a, b);
        assertEquals(a.tree, b.tree);

        // Verify basic flags in PermissionInfo to make sure they're consistent. We don't perform
        // a full structural equality here because the code that serializes them isn't parser
        // specific and is tested elsewhere.
        assertEquals(a.info.protectionLevel, b.info.protectionLevel);
        assertEquals(a.info.group, b.info.group);
        assertEquals(a.info.flags, b.info.flags);

        if (a.group != null && b.group != null) {
            assertPermissionGroupsEqual(a.group, b.group);
        } else if (a.group != null || b.group != null) {
            throw new AssertionError();
        }
    }

    private static void assertInstrumentationEqual(PackageParser.Instrumentation a,
                                                   PackageParser.Instrumentation b) {
        assertComponentsEqual(a, b);

        // Sanity check for InstrumentationInfo.
        assertEquals(a.info.targetPackage, b.info.targetPackage);
        assertEquals(a.info.targetProcesses, b.info.targetProcesses);
        assertEquals(a.info.sourceDir, b.info.sourceDir);
        assertEquals(a.info.publicSourceDir, b.info.publicSourceDir);
    }

    private static void assertServicesEqual(PackageParser.Service a, PackageParser.Service b) {
        assertComponentsEqual(a, b);

        // Sanity check for ServiceInfo.
        assertApplicationInfoEqual(a.info.applicationInfo, b.info.applicationInfo);
        assertEquals(a.info.name, b.info.name);
    }

    private static void assertProvidersEqual(PackageParser.Provider a, PackageParser.Provider b) {
        assertComponentsEqual(a, b);

        // Sanity check for ProviderInfo
        assertApplicationInfoEqual(a.info.applicationInfo, b.info.applicationInfo);
        assertEquals(a.info.name, b.info.name);
    }

    private static void assertActivitiesEqual(PackageParser.Activity a, PackageParser.Activity b) {
        assertComponentsEqual(a, b);

        // Sanity check for ActivityInfo.
        assertApplicationInfoEqual(a.info.applicationInfo, b.info.applicationInfo);
        assertEquals(a.info.name, b.info.name);
    }

    private static void assertPermissionGroupsEqual(PackageParser.PermissionGroup a,
                                                    PackageParser.PermissionGroup b) {
        assertComponentsEqual(a, b);

        // Sanity check for PermissionGroupInfo.
        assertEquals(a.info.name, b.info.name);
        assertEquals(a.info.descriptionRes, b.info.descriptionRes);
    }

    private static void assertApplicationInfoEqual(ApplicationInfo a, ApplicationInfo that) {
        assertEquals(a.descriptionRes, that.descriptionRes);
        assertEquals(a.theme, that.theme);
        assertEquals(a.fullBackupContent, that.fullBackupContent);
        assertEquals(a.uiOptions, that.uiOptions);
        assertEquals(a.flags, that.flags);
        assertEquals(a.privateFlags, that.privateFlags);
        assertEquals(a.requiresSmallestWidthDp, that.requiresSmallestWidthDp);
        assertEquals(a.compatibleWidthLimitDp, that.compatibleWidthLimitDp);
        assertEquals(a.largestWidthLimitDp, that.largestWidthLimitDp);
        assertEquals(a.nativeLibraryRootRequiresIsa, that.nativeLibraryRootRequiresIsa);
        assertEquals(a.uid, that.uid);
        assertEquals(a.minSdkVersion, that.minSdkVersion);
        assertEquals(a.targetSdkVersion, that.targetSdkVersion);
        assertEquals(a.versionCode, that.versionCode);
        assertEquals(a.enabled, that.enabled);
        assertEquals(a.enabledSetting, that.enabledSetting);
        assertEquals(a.installLocation, that.installLocation);
        assertEquals(a.networkSecurityConfigRes, that.networkSecurityConfigRes);
        assertEquals(a.taskAffinity, that.taskAffinity);
        assertEquals(a.permission, that.permission);
        assertEquals(a.processName, that.processName);
        assertEquals(a.className, that.className);
        assertEquals(a.manageSpaceActivityName, that.manageSpaceActivityName);
        assertEquals(a.backupAgentName, that.backupAgentName);
        assertEquals(a.volumeUuid, that.volumeUuid);
        assertEquals(a.scanSourceDir, that.scanSourceDir);
        assertEquals(a.scanPublicSourceDir, that.scanPublicSourceDir);
        assertEquals(a.sourceDir, that.sourceDir);
        assertEquals(a.publicSourceDir, that.publicSourceDir);
        assertTrue(Arrays.equals(a.splitSourceDirs, that.splitSourceDirs));
        assertTrue(Arrays.equals(a.splitPublicSourceDirs, that.splitPublicSourceDirs));
        assertTrue(Arrays.equals(a.resourceDirs, that.resourceDirs));
        assertEquals(a.seInfo, that.seInfo);
        assertTrue(Arrays.equals(a.sharedLibraryFiles, that.sharedLibraryFiles));
        assertEquals(a.dataDir, that.dataDir);
        assertEquals(a.deviceProtectedDataDir, that.deviceProtectedDataDir);
        assertEquals(a.credentialProtectedDataDir, that.credentialProtectedDataDir);
        assertEquals(a.nativeLibraryDir, that.nativeLibraryDir);
        assertEquals(a.secondaryNativeLibraryDir, that.secondaryNativeLibraryDir);
        assertEquals(a.nativeLibraryRootDir, that.nativeLibraryRootDir);
        assertEquals(a.primaryCpuAbi, that.primaryCpuAbi);
        assertEquals(a.secondaryCpuAbi, that.secondaryCpuAbi);
    }

    public static void setKnownFields(PackageParser.Package pkg) {
        pkg.baseRevisionCode = 100;
        pkg.baseHardwareAccelerated = true;
        pkg.mVersionCode = 100;
        pkg.mSharedUserLabel = 100;
        pkg.mPreferredOrder = 100;
        pkg.installLocation = 100;
        pkg.coreApp = true;
        pkg.mRequiredForAllUsers = true;
        pkg.use32bitAbi = true;
        pkg.packageName = "foo";
        pkg.splitNames = new String[] { "foo2" };
        pkg.volumeUuid = "foo3";
        pkg.codePath = "foo4";
        pkg.baseCodePath = "foo5";
        pkg.splitCodePaths = new String[] { "foo6" };
        pkg.splitRevisionCodes = new int[] { 100 };
        pkg.splitFlags = new int[] { 100 };
        pkg.splitPrivateFlags = new int[] { 100 };
        pkg.applicationInfo = new ApplicationInfo();

        pkg.permissions.add(new PackageParser.Permission(pkg));
        pkg.permissionGroups.add(new PackageParser.PermissionGroup(pkg));

        final PackageParser.ParseComponentArgs dummy = new PackageParser.ParseComponentArgs(
                pkg, new String[1], 0, 0, 0, 0, 0, 0, null, 0, 0, 0);

        pkg.activities.add(new PackageParser.Activity(dummy, new ActivityInfo()));
        pkg.receivers.add(new PackageParser.Activity(dummy, new ActivityInfo()));
        pkg.providers.add(new PackageParser.Provider(dummy, new ProviderInfo()));
        pkg.services.add(new PackageParser.Service(dummy, new ServiceInfo()));
        pkg.instrumentation.add(new PackageParser.Instrumentation(dummy, new InstrumentationInfo()));
        pkg.requestedPermissions.add("foo7");

        pkg.protectedBroadcasts = new ArrayList<>();
        pkg.protectedBroadcasts.add("foo8");

        pkg.parentPackage = new PackageParser.Package("foo9");

        pkg.childPackages = new ArrayList<>();
        pkg.childPackages.add(new PackageParser.Package("bar"));

        pkg.staticSharedLibName = "foo23";
        pkg.staticSharedLibVersion = 100;
        pkg.usesStaticLibraries = new ArrayList<>();
        pkg.usesStaticLibraries.add("foo23");
        pkg.usesStaticLibrariesCertDigests = new String[1][];
        pkg.usesStaticLibrariesCertDigests[0] = new String[] { "digest" };
        pkg.usesStaticLibrariesVersions = new long[] { 100 };

        pkg.libraryNames = new ArrayList<>();
        pkg.libraryNames.add("foo10");

        pkg.usesLibraries = new ArrayList<>();
        pkg.usesLibraries.add("foo11");

        pkg.usesOptionalLibraries = new ArrayList<>();
        pkg.usesOptionalLibraries.add("foo12");

        pkg.usesLibraryFiles = new String[] { "foo13"};

        pkg.mOriginalPackages = new ArrayList<>();
        pkg.mOriginalPackages.add("foo14");

        pkg.mRealPackage = "foo15";

        pkg.mAdoptPermissions = new ArrayList<>();
        pkg.mAdoptPermissions.add("foo16");

        pkg.mAppMetaData = new Bundle();
        pkg.mVersionName = "foo17";
        pkg.mSharedUserId = "foo18";
        pkg.mSigningDetails =
                new PackageParser.SigningDetails(
                        new Signature[] { new Signature(new byte[16]) },
                        2,
                        new ArraySet<>(),
                        null,
                        null);
        pkg.mExtras = new Bundle();
        pkg.mRestrictedAccountType = "foo19";
        pkg.mRequiredAccountType = "foo20";
        pkg.mOverlayTarget = "foo21";
        pkg.mOverlayPriority = 100;
        pkg.mUpgradeKeySets = new ArraySet<>();
        pkg.mKeySetMapping = new ArrayMap<>();
        pkg.cpuAbiOverride = "foo22";
        pkg.restrictUpdateHash = new byte[16];

        pkg.preferredActivityFilters = new ArrayList<>();
        pkg.preferredActivityFilters.add(new PackageParser.ActivityIntentInfo(
                new PackageParser.Activity(dummy, new ActivityInfo())));

        pkg.configPreferences = new ArrayList<>();
        pkg.configPreferences.add(new ConfigurationInfo());

        pkg.reqFeatures = new ArrayList<>();
        pkg.reqFeatures.add(new FeatureInfo());

        pkg.featureGroups = new ArrayList<>();
        pkg.featureGroups.add(new FeatureGroupInfo());

        pkg.mCompileSdkVersionCodename = "foo23";
        pkg.mCompileSdkVersion = 100;
        pkg.mVersionCodeMajor = 100;

        pkg.mOverlayCategory = "foo24";
        pkg.mOverlayIsStatic = true;

        pkg.baseHardwareAccelerated = true;
        pkg.coreApp = true;
        pkg.mRequiredForAllUsers = true;
        pkg.visibleToInstantApps = true;
        pkg.use32bitAbi = true;
    }

    private static void assertAllFieldsExist(PackageParser.Package pkg) throws Exception {
        Field[] fields = PackageParser.Package.class.getDeclaredFields();

        Set<String> nonSerializedFields = new HashSet<>();
        nonSerializedFields.add("mExtras");
        nonSerializedFields.add("packageUsageTimeMillis");
        nonSerializedFields.add("isStub");

        for (Field f : fields) {
            final Class<?> fieldType = f.getType();

            if (nonSerializedFields.contains(f.getName())) {
                continue;
            }

            if (List.class.isAssignableFrom(fieldType)) {
                // Sanity check for list fields: Assume they're non-null and contain precisely
                // one element.
                List<?> list = (List<?>) f.get(pkg);
                assertNotNull("List was null: " + f, list);
                assertEquals(1, list.size());
            } else if (fieldType.getComponentType() != null) {
                // Sanity check for array fields: Assume they're non-null and contain precisely
                // one element.
                Object array = f.get(pkg);
                assertNotNull(Array.get(array, 0));
            } else if (fieldType == String.class) {
                // String fields: Check that they're set to "foo".
                String value = (String) f.get(pkg);

                assertTrue("Bad value for field: " + f, value != null && value.startsWith("foo"));
            } else if (fieldType == int.class) {
                // int fields: Check that they're set to 100.
                int value = (int) f.get(pkg);
                assertEquals("Bad value for field: " + f, 100, value);
            } else if (fieldType == boolean.class) {
                // boolean fields: Check that they're set to true.
                boolean value = (boolean) f.get(pkg);
                assertEquals("Bad value for field: " + f, true, value);
            } else {
                // All other fields: Check that they're set.
                Object o = f.get(pkg);
                assertNotNull("Field was null: " + f, o);
            }
        }
    }
}

