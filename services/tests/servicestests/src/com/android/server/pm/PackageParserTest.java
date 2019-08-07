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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureGroupInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageParser;
import android.content.pm.PackageUserState;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.Signature;
import android.content.pm.parsing.AndroidPackage;
import android.content.pm.parsing.ComponentParseUtils;
import android.content.pm.parsing.ComponentParseUtils.ParsedActivity;
import android.content.pm.parsing.ComponentParseUtils.ParsedComponent;
import android.content.pm.parsing.ComponentParseUtils.ParsedInstrumentation;
import android.content.pm.parsing.ComponentParseUtils.ParsedIntentInfo;
import android.content.pm.parsing.ComponentParseUtils.ParsedPermission;
import android.content.pm.parsing.ComponentParseUtils.ParsedPermissionGroup;
import android.content.pm.parsing.ComponentParseUtils.ParsedProvider;
import android.content.pm.parsing.ComponentParseUtils.ParsedService;
import android.content.pm.parsing.PackageImpl;
import android.content.pm.parsing.PackageInfoUtils;
import android.content.pm.parsing.ParsedPackage;
import android.content.pm.parsing.ParsingPackage;
import android.os.Bundle;
import android.os.Parcel;
import android.platform.test.annotations.Presubmit;
import android.util.ArraySet;

import androidx.test.filters.MediumTest;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.ArrayUtils;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
@MediumTest
public class PackageParserTest {
    // TODO(b/135203078): Update this test with all fields and validate equality. Initial change
    //  was just migrating to new interfaces. Consider adding actual equals() methods.

    @Rule
    public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    private File mTmpDir;
    private static final File FRAMEWORK = new File("/system/framework/framework-res.apk");

    @Before
    public void setUp() throws IOException {
        // Create a new temporary directory for each of our tests.
        mTmpDir = mTemporaryFolder.newFolder("PackageParserTest");
    }

    @Test
    public void testParse_noCache() throws Exception {
        PackageParser pp = new CachePackageNameParser();
        ParsedPackage pkg = pp.parseParsedPackage(FRAMEWORK, 0 /* parseFlags */,
                false /* useCaches */);
        assertNotNull(pkg);

        pp.setCacheDir(mTmpDir);
        pkg = pp.parseParsedPackage(FRAMEWORK, 0 /* parseFlags */,
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
        pp.parseParsedPackage(FRAMEWORK, 0 /* parseFlags */, true /* useCaches */);

        // Now attempt to parse the package again, should return the
        // cached result.
        ParsedPackage pkg = pp.parseParsedPackage(FRAMEWORK, 0 /* parseFlags */,
                true /* useCaches */);
        assertEquals("cache_android", pkg.getPackageName());

        // Try again, with useCaches == false, shouldn't return the parsed
        // result.
        pkg = pp.parseParsedPackage(FRAMEWORK, 0 /* parseFlags */, false /* useCaches */);
        assertEquals("android", pkg.getPackageName());

        // We haven't set a cache directory here : the parse should still succeed,
        // just not using the cached results.
        pp = new CachePackageNameParser();
        pkg = pp.parseParsedPackage(FRAMEWORK, 0 /* parseFlags */, true /* useCaches */);
        assertEquals("android", pkg.getPackageName());

        pkg = pp.parseParsedPackage(FRAMEWORK, 0 /* parseFlags */, false /* useCaches */);
        assertEquals("android", pkg.getPackageName());
    }

    @Test
    public void test_serializePackage() throws Exception {
        PackageParser pp = new PackageParser();
        pp.setCacheDir(mTmpDir);

        ParsedPackage pkg = pp.parseParsedPackage(FRAMEWORK, 0 /* parseFlags */,
                true /* useCaches */);

        Parcel p = Parcel.obtain();
        pkg.writeToParcel(p, 0 /* flags */);

        p.setDataPosition(0);
        ParsedPackage deserialized = new PackageImpl(p);

        assertPackagesEqual(pkg, deserialized);
    }

    @Test
    @SmallTest
    @Presubmit
    public void test_roundTripKnownFields() throws Exception {
        ParsingPackage pkg = PackageImpl.forParsing("foo");
        setKnownFields(pkg);

        Parcel p = Parcel.obtain();
        pkg.writeToParcel(p, 0 /* flags */);

        p.setDataPosition(0);
        ParsedPackage deserialized = new PackageImpl(p);
        assertAllFieldsExist(deserialized);
    }

    @Test
    public void test_stringInterning() throws Exception {
        ParsingPackage pkg = PackageImpl.forParsing("foo");
        setKnownFields(pkg);

        Parcel p = Parcel.obtain();
        pkg.writeToParcel(p, 0 /* flags */);

        p.setDataPosition(0);
        ParsingPackage deserialized = new PackageImpl(p);

        p.setDataPosition(0);
        ParsingPackage deserialized2 = new PackageImpl(p);

        assertSame(deserialized.getPackageName(), deserialized2.getPackageName());
        assertSame(deserialized.getPermission(),
                deserialized2.getPermission());
        assertSame(deserialized.getRequestedPermissions().get(0),
                deserialized2.getRequestedPermissions().get(0));

        List<String> protectedBroadcastsOne = new ArrayList<>(1);
        protectedBroadcastsOne.addAll(deserialized.getProtectedBroadcasts());

        List<String> protectedBroadcastsTwo = new ArrayList<>(1);
        protectedBroadcastsTwo.addAll(deserialized2.getProtectedBroadcasts());

        assertSame(protectedBroadcastsOne.get(0), protectedBroadcastsTwo.get(0));

        assertSame(deserialized.getUsesLibraries().get(0),
                deserialized2.getUsesLibraries().get(0));
        assertSame(deserialized.getUsesOptionalLibraries().get(0),
                deserialized2.getUsesOptionalLibraries().get(0));
        assertSame(deserialized.getVersionName(), deserialized2.getVersionName());
        assertSame(deserialized.getSharedUserId(), deserialized2.getSharedUserId());
    }

    /**
     * A trivial subclass of package parser that only caches the package name, and throws away
     * all other information.
     */
    public static class CachePackageNameParser extends PackageParser {
        @Override
        public byte[] toCacheEntry(ParsedPackage pkg) {
            return ("cache_" + pkg.getPackageName()).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public ParsedPackage fromCacheEntry(byte[] cacheEntry) {
            return PackageImpl.forParsing(new String(cacheEntry, StandardCharsets.UTF_8))
                    .hideAsParsed();
        }
    }

    // NOTE: The equality assertions below are based on code autogenerated by IntelliJ.

    public static void assertPackagesEqual(AndroidPackage a, AndroidPackage b) {
        assertEquals(a.getBaseRevisionCode(), b.getBaseRevisionCode());
        assertEquals(a.isBaseHardwareAccelerated(), b.isBaseHardwareAccelerated());
        assertEquals(a.getVersionCode(), b.getVersionCode());
        assertEquals(a.getSharedUserLabel(), b.getSharedUserLabel());
        assertEquals(a.getPreferredOrder(), b.getPreferredOrder());
        assertEquals(a.getInstallLocation(), b.getInstallLocation());
        assertEquals(a.isCoreApp(), b.isCoreApp());
        assertEquals(a.isRequiredForAllUsers(), b.isRequiredForAllUsers());
        assertEquals(a.getCompileSdkVersion(), b.getCompileSdkVersion());
        assertEquals(a.getCompileSdkVersionCodeName(), b.getCompileSdkVersionCodeName());
        assertEquals(a.isUse32BitAbi(), b.isUse32BitAbi());
        assertEquals(a.getPackageName(), b.getPackageName());
        assertArrayEquals(a.getSplitNames(), b.getSplitNames());
        assertEquals(a.getVolumeUuid(), b.getVolumeUuid());
        assertEquals(a.getCodePath(), b.getCodePath());
        assertEquals(a.getBaseCodePath(), b.getBaseCodePath());
        assertArrayEquals(a.getSplitCodePaths(), b.getSplitCodePaths());
        assertArrayEquals(a.getSplitRevisionCodes(), b.getSplitRevisionCodes());
        assertArrayEquals(a.getSplitFlags(), b.getSplitFlags());

        PackageInfo aInfo = PackageInfoUtils.generate(a, new int[]{}, 0, 0, 0,
                Collections.emptySet(), new PackageUserState(), 0);
        PackageInfo bInfo = PackageInfoUtils.generate(b, new int[]{}, 0, 0, 0,
                Collections.emptySet(), new PackageUserState(), 0);
        assertApplicationInfoEqual(aInfo.applicationInfo, bInfo.applicationInfo);

        assertEquals(ArrayUtils.size(a.getPermissions()), ArrayUtils.size(b.getPermissions()));
        for (int i = 0; i < ArrayUtils.size(a.getPermissions()); ++i) {
            assertPermissionsEqual(a.getPermissions().get(i), b.getPermissions().get(i));
        }

        assertEquals(ArrayUtils.size(a.getPermissionGroups()),
                ArrayUtils.size(b.getPermissionGroups()));
        for (int i = 0; i < a.getPermissionGroups().size(); ++i) {
            assertPermissionGroupsEqual(a.getPermissionGroups().get(i),
                    b.getPermissionGroups().get(i));
        }

        assertEquals(ArrayUtils.size(a.getActivities()), ArrayUtils.size(b.getActivities()));
        for (int i = 0; i < ArrayUtils.size(a.getActivities()); ++i) {
            assertActivitiesEqual(a, a.getActivities().get(i), b, b.getActivities().get(i));
        }

        assertEquals(ArrayUtils.size(a.getReceivers()), ArrayUtils.size(b.getReceivers()));
        for (int i = 0; i < ArrayUtils.size(a.getReceivers()); ++i) {
            assertActivitiesEqual(a, a.getReceivers().get(i), b, b.getReceivers().get(i));
        }

        assertEquals(ArrayUtils.size(a.getProviders()), ArrayUtils.size(b.getProviders()));
        for (int i = 0; i < ArrayUtils.size(a.getProviders()); ++i) {
            assertProvidersEqual(a, a.getProviders().get(i), b, b.getProviders().get(i));
        }

        assertEquals(ArrayUtils.size(a.getServices()), ArrayUtils.size(b.getServices()));
        for (int i = 0; i < ArrayUtils.size(a.getServices()); ++i) {
            assertServicesEqual(a, a.getServices().get(i), b, b.getServices().get(i));
        }

        assertEquals(ArrayUtils.size(a.getInstrumentations()),
                ArrayUtils.size(b.getInstrumentations()));
        for (int i = 0; i < ArrayUtils.size(a.getInstrumentations()); ++i) {
            assertInstrumentationEqual(a.getInstrumentations().get(i),
                    b.getInstrumentations().get(i));
        }

        assertEquals(a.getRequestedPermissions(), b.getRequestedPermissions());
        assertEquals(a.getProtectedBroadcasts(), b.getProtectedBroadcasts());
        assertEquals(a.getLibraryNames(), b.getLibraryNames());
        assertEquals(a.getUsesLibraries(), b.getUsesLibraries());
        assertEquals(a.getUsesOptionalLibraries(), b.getUsesOptionalLibraries());
        assertArrayEquals(a.getUsesLibraryFiles(), b.getUsesLibraryFiles());
        assertEquals(a.getOriginalPackages(), b.getOriginalPackages());
        assertEquals(a.getRealPackage(), b.getRealPackage());
        assertEquals(a.getAdoptPermissions(), b.getAdoptPermissions());
        assertBundleApproximateEquals(a.getAppMetaData(), b.getAppMetaData());
        assertEquals(a.getVersionName(), b.getVersionName());
        assertEquals(a.getSharedUserId(), b.getSharedUserId());
        assertArrayEquals(a.getSigningDetails().signatures, b.getSigningDetails().signatures);
        assertArrayEquals(a.getLastPackageUsageTimeInMills(), b.getLastPackageUsageTimeInMills());
        assertEquals(a.getRestrictedAccountType(), b.getRestrictedAccountType());
        assertEquals(a.getRequiredAccountType(), b.getRequiredAccountType());
        assertEquals(a.getOverlayTarget(), b.getOverlayTarget());
        assertEquals(a.getOverlayTargetName(), b.getOverlayTargetName());
        assertEquals(a.getOverlayCategory(), b.getOverlayCategory());
        assertEquals(a.getOverlayPriority(), b.getOverlayPriority());
        assertEquals(a.isOverlayIsStatic(), b.isOverlayIsStatic());
        assertEquals(a.getSigningDetails().publicKeys, b.getSigningDetails().publicKeys);
        assertEquals(a.getUpgradeKeySets(), b.getUpgradeKeySets());
        assertEquals(a.getKeySetMapping(), b.getKeySetMapping());
        assertEquals(a.getCpuAbiOverride(), b.getCpuAbiOverride());
        assertArrayEquals(a.getRestrictUpdateHash(), b.getRestrictUpdateHash());
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

    private static void assertComponentsEqual(ParsedComponent<?> a,
            ParsedComponent<?> b) {
        assertEquals(a.className, b.className);
        assertBundleApproximateEquals(a.getMetaData(), b.getMetaData());
        assertEquals(a.getComponentName(), b.getComponentName());

        if (a.intents != null && b.intents != null) {
            assertEquals(a.intents.size(), b.intents.size());
        } else if (a.intents == null || b.intents == null) {
            return;
        }

        for (int i = 0; i < a.intents.size(); ++i) {
            ParsedIntentInfo aIntent = a.intents.get(i);
            ParsedIntentInfo bIntent = b.intents.get(i);

            assertEquals(aIntent.hasDefault, bIntent.hasDefault);
            assertEquals(aIntent.labelRes, bIntent.labelRes);
            assertEquals(aIntent.nonLocalizedLabel, bIntent.nonLocalizedLabel);
            assertEquals(aIntent.icon, bIntent.icon);
        }
    }

    private static void assertPermissionsEqual(ParsedPermission a,
            ParsedPermission b) {
        assertComponentsEqual(a, b);
        assertEquals(a.tree, b.tree);

        // Verify basic flags in PermissionInfo to make sure they're consistent. We don't perform
        // a full structural equality here because the code that serializes them isn't parser
        // specific and is tested elsewhere.
        assertEquals(a.getProtection(), b.getProtection());
        assertEquals(a.getGroup(), b.getGroup());
        assertEquals(a.flags, b.flags);

        if (a.parsedPermissionGroup != null && b.parsedPermissionGroup != null) {
            assertPermissionGroupsEqual(a.parsedPermissionGroup, b.parsedPermissionGroup);
        } else if (a.parsedPermissionGroup != null || b.parsedPermissionGroup != null) {
            throw new AssertionError();
        }
    }

    private static void assertInstrumentationEqual(ParsedInstrumentation a,
            ParsedInstrumentation b) {
        assertComponentsEqual(a, b);

        // Sanity check for InstrumentationInfo.
        assertEquals(a.getTargetPackage(), b.getTargetPackage());
        assertEquals(a.getTargetProcesses(), b.getTargetProcesses());
        assertEquals(a.sourceDir, b.sourceDir);
        assertEquals(a.publicSourceDir, b.publicSourceDir);
    }

    private static void assertServicesEqual(
            AndroidPackage aPkg,
            ParsedService a,
            AndroidPackage bPkg,
            ParsedService b
    ) {
        assertComponentsEqual(a, b);

        // Sanity check for ServiceInfo.
        ServiceInfo aInfo = PackageInfoUtils.generateServiceInfo(aPkg, a, 0, new PackageUserState(),
                0);
        ServiceInfo bInfo = PackageInfoUtils.generateServiceInfo(bPkg, b, 0, new PackageUserState(),
                0);
        assertApplicationInfoEqual(aInfo.applicationInfo, bInfo.applicationInfo);
        assertEquals(a.getName(), b.getName());
    }

    private static void assertProvidersEqual(
            AndroidPackage aPkg,
            ParsedProvider a,
            AndroidPackage bPkg,
            ParsedProvider b
    ) {
        assertComponentsEqual(a, b);

        // Sanity check for ProviderInfo
        ProviderInfo aInfo = PackageInfoUtils.generateProviderInfo(aPkg, a, 0,
                new PackageUserState(), 0);
        ProviderInfo bInfo = PackageInfoUtils.generateProviderInfo(bPkg, b, 0,
                new PackageUserState(), 0);
        assertApplicationInfoEqual(aInfo.applicationInfo, bInfo.applicationInfo);
        assertEquals(a.getName(), b.getName());
    }

    private static void assertActivitiesEqual(
            AndroidPackage aPkg,
            ParsedActivity a,
            AndroidPackage bPkg,
            ParsedActivity b
    ) {
        assertComponentsEqual(a, b);

        // Sanity check for ActivityInfo.
        ActivityInfo aInfo = PackageInfoUtils.generateActivityInfo(aPkg, a, 0,
                new PackageUserState(), 0);
        ActivityInfo bInfo = PackageInfoUtils.generateActivityInfo(bPkg, b, 0,
                new PackageUserState(), 0);
        assertApplicationInfoEqual(aInfo.applicationInfo, bInfo.applicationInfo);
        assertEquals(a.getName(), b.getName());
    }

    private static void assertPermissionGroupsEqual(ParsedPermissionGroup a,
            ParsedPermissionGroup b) {
        assertComponentsEqual(a, b);

        // Sanity check for PermissionGroupInfo.
        assertEquals(a.getName(), b.getName());
        assertEquals(a.descriptionRes, b.descriptionRes);
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
        assertArrayEquals(a.splitSourceDirs, that.splitSourceDirs);
        assertArrayEquals(a.splitPublicSourceDirs, that.splitPublicSourceDirs);
        assertArrayEquals(a.resourceDirs, that.resourceDirs);
        assertEquals(a.seInfo, that.seInfo);
        assertArrayEquals(a.sharedLibraryFiles, that.sharedLibraryFiles);
        assertEquals(a.dataDir, that.dataDir);
        assertEquals(a.deviceProtectedDataDir, that.deviceProtectedDataDir);
        assertEquals(a.credentialProtectedDataDir, that.credentialProtectedDataDir);
        assertEquals(a.nativeLibraryDir, that.nativeLibraryDir);
        assertEquals(a.secondaryNativeLibraryDir, that.secondaryNativeLibraryDir);
        assertEquals(a.nativeLibraryRootDir, that.nativeLibraryRootDir);
        assertEquals(a.primaryCpuAbi, that.primaryCpuAbi);
        assertEquals(a.secondaryCpuAbi, that.secondaryCpuAbi);
    }

    public static void setKnownFields(ParsingPackage pkg) {
        Bundle bundle = new Bundle();
        bundle.putString("key", "value");

        ParsedPermission permission = new ParsedPermission();
        permission.parsedPermissionGroup = new ParsedPermissionGroup();

        pkg.setBaseRevisionCode(100)
                .setBaseHardwareAccelerated(true)
                .setSharedUserLabel(100)
                .setPreferredOrder(100)
                .setInstallLocation(100)
                .setRequiredForAllUsers(true)
                .asSplit(
                        new String[]{"foo2"},
                        new String[]{"foo6"},
                        new int[]{100},
                        null
                )
                .setUse32BitAbi(true)
                .setVolumeUuid("foo3")
                .setCodePath("foo4")
                .addPermission(permission)
                .addPermissionGroup(new ParsedPermissionGroup())
                .addActivity(new ParsedActivity())
                .addReceiver(new ParsedActivity())
                .addProvider(new ParsedProvider())
                .addService(new ParsedService())
                .addInstrumentation(new ParsedInstrumentation())
                .addRequestedPermission("foo7")
                .addImplicitPermission("foo25")
                .addProtectedBroadcast("foo8")
                .setStaticSharedLibName("foo23")
                .setStaticSharedLibVersion(100)
                .addUsesStaticLibrary("foo23")
                .addUsesStaticLibraryCertDigests(new String[]{"digest"})
                .addUsesStaticLibraryVersion(100)
                .addLibraryName("foo10")
                .addUsesLibrary("foo11")
                .addUsesOptionalLibrary("foo12")
                .addOriginalPackage("foo14")
                .setRealPackage("foo15")
                .addAdoptPermission("foo16")
                .setAppMetaData(bundle)
                .setVersionName("foo17")
                .setSharedUserId("foo18")
                .setSigningDetails(
                        new PackageParser.SigningDetails(
                                new Signature[]{new Signature(new byte[16])},
                                2,
                                new ArraySet<>(),
                                null)
                )
                .setRestrictedAccountType("foo19")
                .setRequiredAccountType("foo20")
                .setOverlayTarget("foo21")
                .setOverlayPriority(100)
                .setUpgradeKeySets(new ArraySet<>())
                .addPreferredActivityFilter(
                        new ComponentParseUtils.ParsedActivityIntentInfo("foo", "className"))
                .addConfigPreference(new ConfigurationInfo())
                .addReqFeature(new FeatureInfo())
                .addFeatureGroup(new FeatureGroupInfo())
                .setCompileSdkVersionCodename("foo23")
                .setCompileSdkVersion(100)
                .setOverlayCategory("foo24")
                .setOverlayIsStatic(true)
                .setOverlayTargetName("foo26")
                .setVisibleToInstantApps(true)
                .setSplitHasCode(0, true)
                .hideAsParsed()
                .setBaseCodePath("foo5")
                .setVersionCode(100)
                .setCpuAbiOverride("foo22")
                .setRestrictUpdateHash(new byte[16])
                .setVersionCodeMajor(100)
                .setCoreApp(true)
                .hideAsFinal()
                .mutate()
                .setUsesLibraryInfos(Arrays.asList(
                        new SharedLibraryInfo(null, null, null, null, 0L, 0, null, null, null)
                ))
                .setUsesLibraryFiles(new String[]{"foo13"});
    }

    private static void assertAllFieldsExist(ParsedPackage pkg) throws Exception {
        Field[] fields = ParsedPackage.class.getDeclaredFields();

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
                assertTrue("Bad value for field: " + f, value);
            } else {
                // All other fields: Check that they're set.
                Object o = f.get(pkg);
                assertNotNull("Field was null: " + f, o);
            }
        }
    }
}

