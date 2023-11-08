/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.pm.parsing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.parsing.FrameworkParsingPackageUtils;
import android.content.pm.parsing.result.ParseResult;
import android.content.pm.parsing.result.ParseTypeImpl;
import android.os.Build;
import android.os.Bundle;
import android.os.FileUtils;
import android.platform.test.annotations.Presubmit;
import android.util.Pair;
import android.util.SparseIntArray;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.pm.pkg.component.ParsedComponent;
import com.android.internal.pm.pkg.component.ParsedIntentInfo;
import com.android.internal.pm.pkg.component.ParsedPermission;
import com.android.internal.util.ArrayUtils;
import com.android.server.pm.PackageManagerException;
import com.android.server.pm.parsing.pkg.ParsedPackage;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.component.ParsedActivityUtils;
import com.android.server.pm.pkg.component.ParsedPermissionUtils;
import com.android.server.pm.test.service.server.R;

import com.google.common.truth.Expect;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * {@link ParsedPackage} was moved to the server, so this test moved along with it.
 *
 * This should be eventually refactored to a comprehensive parsing test, combined with its
 * server variant in the parent package.
 *
 * TODO(b/135203078): Remove this test and replicate the cases in the actual com.android.server
 *  variant.
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class PackageParserLegacyCoreTest {
    private static final String RELEASED = null;
    private static final String OLDER_PRE_RELEASE = "Q";
    private static final String PRE_RELEASE = "R";
    private static final String NEWER_PRE_RELEASE = "Z";

    // Codenames with a fingerprint attached to them. These may only be present in the apps
    // declared min SDK and not as platform codenames.
    private static final String OLDER_PRE_RELEASE_WITH_FINGERPRINT = "Q.fingerprint";
    private static final String PRE_RELEASE_WITH_FINGERPRINT = "R.fingerprint";
    private static final String NEWER_PRE_RELEASE_WITH_FINGERPRINT = "Z.fingerprint";

    private static final String[] CODENAMES_RELEASED = { /* empty */};
    private static final String[] CODENAMES_PRE_RELEASE = {PRE_RELEASE};

    private static final int OLDER_VERSION = 10;
    private static final int PLATFORM_VERSION = 20;
    private static final int NEWER_VERSION = 30;

    private static final int DISALLOW_PRERELEASE = -1;
    private static final int DISALLOW_RELEASED = -1;

    @Rule public final Expect expect = Expect.create();

    private void verifyComputeMinSdkVersion(int minSdkVersion, String minSdkCodename,
            boolean isPlatformReleased, int expectedMinSdk) {
        final ParseTypeImpl input = ParseTypeImpl.forParsingWithoutPlatformCompat();
        final ParseResult<Integer> result = FrameworkParsingPackageUtils.computeMinSdkVersion(
                minSdkVersion,
                minSdkCodename,
                PLATFORM_VERSION,
                isPlatformReleased ? CODENAMES_RELEASED : CODENAMES_PRE_RELEASE,
                input);

        if (expectedMinSdk == -1) {
            assertTrue(result.isError());
        } else {
            assertTrue(result.isSuccess());
            assertEquals(expectedMinSdk, (int) result.getResult());
        }
    }

    @Test
    public void testComputeMinSdkVersion_preReleasePlatform() {
        // Do allow older release minSdkVersion on pre-release platform.
        // APP: Released API 10
        // DEV: Pre-release API 20
        verifyComputeMinSdkVersion(OLDER_VERSION, RELEASED, false, OLDER_VERSION);

        // Do allow same release minSdkVersion on pre-release platform.
        // APP: Released API 20
        // DEV: Pre-release API 20
        verifyComputeMinSdkVersion(PLATFORM_VERSION, RELEASED, false, PLATFORM_VERSION);

        // Don't allow newer release minSdkVersion on pre-release platform.
        // APP: Released API 30
        // DEV: Pre-release API 20
        verifyComputeMinSdkVersion(NEWER_VERSION, RELEASED, false, -1);

        // Don't allow older pre-release minSdkVersion on pre-release platform.
        // APP: Pre-release API 10
        // DEV: Pre-release API 20
        verifyComputeMinSdkVersion(OLDER_VERSION, OLDER_PRE_RELEASE, false, -1);
        verifyComputeMinSdkVersion(OLDER_VERSION, OLDER_PRE_RELEASE_WITH_FINGERPRINT, false, -1);

        // Do allow same pre-release minSdkVersion on pre-release platform,
        // but overwrite the specified version with CUR_DEVELOPMENT.
        // APP: Pre-release API 20
        // DEV: Pre-release API 20
        verifyComputeMinSdkVersion(PLATFORM_VERSION, PRE_RELEASE, false,
                Build.VERSION_CODES.CUR_DEVELOPMENT);
        verifyComputeMinSdkVersion(PLATFORM_VERSION, PRE_RELEASE_WITH_FINGERPRINT, false,
                Build.VERSION_CODES.CUR_DEVELOPMENT);


        // Don't allow newer pre-release minSdkVersion on pre-release platform.
        // APP: Pre-release API 30
        // DEV: Pre-release API 20
        verifyComputeMinSdkVersion(NEWER_VERSION, NEWER_PRE_RELEASE, false,
                DISALLOW_PRERELEASE);
        verifyComputeMinSdkVersion(NEWER_VERSION, NEWER_PRE_RELEASE_WITH_FINGERPRINT, false,
                DISALLOW_PRERELEASE);
    }

    @Test
    public void testComputeMinSdkVersion_releasedPlatform() {
        // Do allow older release minSdkVersion on released platform.
        // APP: Released API 10
        // DEV: Released API 20
        verifyComputeMinSdkVersion(OLDER_VERSION, RELEASED, true, OLDER_VERSION);

        // Do allow same release minSdkVersion on released platform.
        // APP: Released API 20
        // DEV: Released API 20
        verifyComputeMinSdkVersion(PLATFORM_VERSION, RELEASED, true, PLATFORM_VERSION);

        // Don't allow newer release minSdkVersion on released platform.
        // APP: Released API 30
        // DEV: Released API 20
        verifyComputeMinSdkVersion(NEWER_VERSION, RELEASED, true, -1);

        // Don't allow older pre-release minSdkVersion on released platform.
        // APP: Pre-release API 10
        // DEV: Released API 20
        verifyComputeMinSdkVersion(OLDER_VERSION, OLDER_PRE_RELEASE, true,
                DISALLOW_RELEASED);
        verifyComputeMinSdkVersion(OLDER_VERSION, OLDER_PRE_RELEASE_WITH_FINGERPRINT, true,
                DISALLOW_RELEASED);

        // Don't allow same pre-release minSdkVersion on released platform.
        // APP: Pre-release API 20
        // DEV: Released API 20
        verifyComputeMinSdkVersion(PLATFORM_VERSION, PRE_RELEASE, true,
                DISALLOW_RELEASED);
        verifyComputeMinSdkVersion(PLATFORM_VERSION, PRE_RELEASE_WITH_FINGERPRINT, true,
                DISALLOW_RELEASED);


        // Don't allow newer pre-release minSdkVersion on released platform.
        // APP: Pre-release API 30
        // DEV: Released API 20
        verifyComputeMinSdkVersion(NEWER_VERSION, NEWER_PRE_RELEASE, true,
                DISALLOW_RELEASED);
        verifyComputeMinSdkVersion(NEWER_VERSION, NEWER_PRE_RELEASE_WITH_FINGERPRINT, true,
                DISALLOW_RELEASED);
    }

    private void verifyComputeTargetSdkVersion(int targetSdkVersion, String targetSdkCodename,
            boolean isPlatformReleased, boolean allowUnknownCodenames, int expectedTargetSdk) {
        final ParseTypeImpl input = ParseTypeImpl.forParsingWithoutPlatformCompat();
        final ParseResult<Integer> result = FrameworkParsingPackageUtils.computeTargetSdkVersion(
                targetSdkVersion,
                targetSdkCodename,
                isPlatformReleased ? CODENAMES_RELEASED : CODENAMES_PRE_RELEASE,
                input,
                allowUnknownCodenames);

        if (expectedTargetSdk == -1) {
            assertTrue(result.isError());
        } else {
            assertTrue(result.isSuccess());
            assertEquals(expectedTargetSdk, (int) result.getResult());
        }
    }

    @Test
    public void testComputeTargetSdkVersion_preReleasePlatform() {
        // Do allow older release targetSdkVersion on pre-release platform.
        // APP: Released API 10
        // DEV: Pre-release API 20
        verifyComputeTargetSdkVersion(OLDER_VERSION, RELEASED, false, false, OLDER_VERSION);

        // Do allow same release targetSdkVersion on pre-release platform.
        // APP: Released API 20
        // DEV: Pre-release API 20
        verifyComputeTargetSdkVersion(PLATFORM_VERSION, RELEASED, false, false, PLATFORM_VERSION);

        // Do allow newer release targetSdkVersion on pre-release platform.
        // APP: Released API 30
        // DEV: Pre-release API 20
        verifyComputeTargetSdkVersion(NEWER_VERSION, RELEASED, false, false, NEWER_VERSION);

        // Don't allow older pre-release targetSdkVersion on pre-release platform.
        // APP: Pre-release API 10
        // DEV: Pre-release API 20
        verifyComputeTargetSdkVersion(OLDER_VERSION, OLDER_PRE_RELEASE, false, false, -1);
        verifyComputeTargetSdkVersion(OLDER_VERSION, OLDER_PRE_RELEASE_WITH_FINGERPRINT, false,
                false, -1
        );

        // Don't allow older pre-release targetSdkVersion on pre-release platform when
        // allowUnknownCodenames is true.
        // APP: Pre-release API 10
        // DEV: Pre-release API 20
        verifyComputeTargetSdkVersion(OLDER_VERSION, OLDER_PRE_RELEASE, false,
                true, -1);
        verifyComputeTargetSdkVersion(OLDER_VERSION, OLDER_PRE_RELEASE_WITH_FINGERPRINT, false,
                true, -1);

        // Do allow same pre-release targetSdkVersion on pre-release platform,
        // but overwrite the specified version with CUR_DEVELOPMENT.
        // APP: Pre-release API 20
        // DEV: Pre-release API 20
        verifyComputeTargetSdkVersion(PLATFORM_VERSION, PRE_RELEASE, false,
                false, Build.VERSION_CODES.CUR_DEVELOPMENT);
        verifyComputeTargetSdkVersion(PLATFORM_VERSION, PRE_RELEASE_WITH_FINGERPRINT, false,
                false, Build.VERSION_CODES.CUR_DEVELOPMENT);

        // Don't allow newer pre-release targetSdkVersion on pre-release platform.
        // APP: Pre-release API 30
        // DEV: Pre-release API 20
        verifyComputeTargetSdkVersion(NEWER_VERSION, NEWER_PRE_RELEASE, false, false,
                DISALLOW_PRERELEASE);
        verifyComputeTargetSdkVersion(NEWER_VERSION, NEWER_PRE_RELEASE_WITH_FINGERPRINT, false,
                false, DISALLOW_PRERELEASE);

        // Do allow newer pre-release targetSdkVersion on pre-release platform when
        // allowUnknownCodenames is true.
        // APP: Pre-release API 30
        // DEV: Pre-release API 20
        verifyComputeTargetSdkVersion(NEWER_VERSION, NEWER_PRE_RELEASE, false,
                true, Build.VERSION_CODES.CUR_DEVELOPMENT);
        verifyComputeTargetSdkVersion(NEWER_VERSION, NEWER_PRE_RELEASE_WITH_FINGERPRINT, false,
                true, Build.VERSION_CODES.CUR_DEVELOPMENT);

    }

    @Test
    public void testComputeTargetSdkVersion_releasedPlatform() {
        // Do allow older release targetSdkVersion on released platform.
        // APP: Released API 10
        // DEV: Released API 20
        verifyComputeTargetSdkVersion(OLDER_VERSION, RELEASED, true, false, OLDER_VERSION);

        // Do allow same release targetSdkVersion on released platform.
        // APP: Released API 20
        // DEV: Released API 20
        verifyComputeTargetSdkVersion(PLATFORM_VERSION, RELEASED, true, false, PLATFORM_VERSION);

        // Do allow newer release targetSdkVersion on released platform.
        // APP: Released API 30
        // DEV: Released API 20
        verifyComputeTargetSdkVersion(NEWER_VERSION, RELEASED, true, false, NEWER_VERSION);

        // Don't allow older pre-release targetSdkVersion on released platform.
        // APP: Pre-release API 10
        // DEV: Released API 20
        verifyComputeTargetSdkVersion(OLDER_VERSION, OLDER_PRE_RELEASE, true, false,
                DISALLOW_RELEASED);
        verifyComputeTargetSdkVersion(OLDER_VERSION, OLDER_PRE_RELEASE_WITH_FINGERPRINT, true,
                false, DISALLOW_RELEASED);

        // Don't allow same pre-release targetSdkVersion on released platform.
        // APP: Pre-release API 20
        // DEV: Released API 20
        verifyComputeTargetSdkVersion(PLATFORM_VERSION, PRE_RELEASE, true, false,
                DISALLOW_RELEASED);
        verifyComputeTargetSdkVersion(PLATFORM_VERSION, PRE_RELEASE_WITH_FINGERPRINT, true, false,
                DISALLOW_RELEASED);

        // Don't allow same pre-release targetSdkVersion on released platform when
        // allowUnknownCodenames is true.
        // APP: Pre-release API 20
        // DEV: Released API 20
        verifyComputeTargetSdkVersion(PLATFORM_VERSION, PRE_RELEASE, true, true,
                DISALLOW_RELEASED);
        verifyComputeTargetSdkVersion(PLATFORM_VERSION, PRE_RELEASE_WITH_FINGERPRINT, true, true,
                DISALLOW_RELEASED);

        // Don't allow newer pre-release targetSdkVersion on released platform.
        // APP: Pre-release API 30
        // DEV: Released API 20
        verifyComputeTargetSdkVersion(NEWER_VERSION, NEWER_PRE_RELEASE, true, false,
                DISALLOW_RELEASED);
        verifyComputeTargetSdkVersion(NEWER_VERSION, NEWER_PRE_RELEASE_WITH_FINGERPRINT, true,
                false, DISALLOW_RELEASED);
        // Do allow newer pre-release targetSdkVersion on released platform when
        // allowUnknownCodenames is true.
        // APP: Pre-release API 30
        // DEV: Released API 20
        verifyComputeTargetSdkVersion(NEWER_VERSION, NEWER_PRE_RELEASE, true, true,
                Build.VERSION_CODES.CUR_DEVELOPMENT);
        verifyComputeTargetSdkVersion(NEWER_VERSION, NEWER_PRE_RELEASE_WITH_FINGERPRINT, true,
                true, Build.VERSION_CODES.CUR_DEVELOPMENT);
    }

    /**
     * Unit test for PackageParser.getActivityConfigChanges().
     * If the bit is 1 in the original configChanges, it is still 1 in the final configChanges.
     * If the bit is 0 in the original configChanges and the bit is not set to 1 in
     * recreateOnConfigChanges, the bit is changed to 1 in the final configChanges by default.
     */
    @Test
    public void testGetActivityConfigChanges() {
        // Not set in either configChanges or recreateOnConfigChanges.
        int configChanges = 0x0000; // 00000000.
        int recreateOnConfigChanges = 0x0000; // 00000000.
        int finalConfigChanges = ParsedActivityUtils.getActivityConfigChanges(configChanges,
                recreateOnConfigChanges);
        assertEquals(0x0003, finalConfigChanges); // Should be 00000011.

        // Not set in configChanges, but set in recreateOnConfigChanges.
        configChanges = 0x0000; // 00000000.
        recreateOnConfigChanges = 0x0003; // 00000011.
        finalConfigChanges = ParsedActivityUtils.getActivityConfigChanges(configChanges,
                recreateOnConfigChanges);
        assertEquals(0x0000, finalConfigChanges); // Should be 00000000.

        // Set in configChanges.
        configChanges = 0x0003; // 00000011.
        recreateOnConfigChanges = 0X0000; // 00000000.
        finalConfigChanges = ParsedActivityUtils.getActivityConfigChanges(configChanges,
                recreateOnConfigChanges);
        assertEquals(0x0003, finalConfigChanges); // Should be 00000011.

        recreateOnConfigChanges = 0x0003; // 00000011.
        finalConfigChanges = ParsedActivityUtils.getActivityConfigChanges(configChanges,
                recreateOnConfigChanges);
        assertEquals(0x0003, finalConfigChanges); // Should still be 00000011.

        // Other bit set in configChanges.
        configChanges = 0x0080; // 10000000, orientation.
        recreateOnConfigChanges = 0x0000; // 00000000.
        finalConfigChanges = ParsedActivityUtils.getActivityConfigChanges(configChanges,
                recreateOnConfigChanges);
        assertEquals(0x0083, finalConfigChanges); // Should be 10000011.
    }

    /**
     * Copies a specified {@code resourceId} to a file. Returns a non-null file if the copy
     * succeeded, or {@code null} otherwise.
     */
    File copyRawResourceToFile(String baseName, int resourceId) {
        // Copy the resource to a file.
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InputStream is = context.getResources().openRawResource(resourceId);
        File outFile = null;
        try {
            outFile = new File(context.getFilesDir(), baseName);
            assertTrue(FileUtils.copyToFile(is, outFile));
            return outFile;
        } catch (Exception e) {
            if (outFile != null) {
                outFile.delete();
            }

            return null;
        }
    }

    /**
     * Attempts to parse a package.
     *
     * APKs are put into coretests/apks/packageparser_*.
     *
     * @param apkFileName   temporary file name to store apk extracted from resources
     * @param apkResourceId identifier of the apk as a resource
     */
    ParsedPackage parsePackage(String apkFileName, int apkResourceId,
            Function<ParsedPackage, ParsedPackage> converter) throws Exception {
        // Copy the resource to a file.
        File outFile = null;
        try {
            outFile = copyRawResourceToFile(apkFileName, apkResourceId);
            return converter.apply(new TestPackageParser2()
                    .parsePackage(outFile, 0 /* flags */, false));
        } finally {
            if (outFile != null) {
                outFile.delete();
            }
        }
    }

    /**
     * Asserts basic properties about a component.
     */
    private void assertComponent(String className, int numIntents, ParsedComponent component) {
        assertEquals(className, component.getName());
        assertEquals(numIntents, component.getIntents().size());
    }

    /**
     * Asserts four regularly-named components of each type: one Activity, one Service, one
     * Provider, and one Receiver.
     *
     * @param template templated string with %s subbed with Activity, Service, Provider, Receiver
     */
    private void assertOneComponentOfEachType(String template, AndroidPackage p) {
        assertEquals(1, p.getActivities().size());
        assertComponent(String.format(template, "Activity"),
                0 /* intents */, p.getActivities().get(0));
        assertEquals(1, p.getServices().size());
        assertComponent(String.format(template, "Service"),
                0 /* intents */, p.getServices().get(0));
        assertEquals(1, p.getProviders().size());
        assertComponent(String.format(template, "Provider"),
                0 /* intents */, p.getProviders().get(0));
        assertEquals(1, p.getReceivers().size());
        assertComponent(String.format(template, "Receiver"),
                0 /* intents */, p.getReceivers().get(0));
    }

    private void assertPermission(String name, int protectionLevel, ParsedPermission permission) {
        assertEquals(name, permission.getName());
        assertEquals(protectionLevel, ParsedPermissionUtils.getProtection(permission));
    }

    private void assertMetadata(Bundle b, String... keysAndValues) {
        assertTrue("Odd number of elements in keysAndValues", (keysAndValues.length % 2) == 0);

        assertNotNull(b);
        assertEquals(keysAndValues.length / 2, b.size());

        for (int i = 0; i < keysAndValues.length; i += 2) {
            final String key = keysAndValues[i];
            final String value = keysAndValues[i + 1];

            assertEquals(value, b.getString(key));
        }
    }

    // TODO Add a "_cached" test for testMultiPackageComponents() too, after fixing b/64295061.
    // Package.writeToParcel can't handle circular package references.

    @Test
    public void testPackageWithComponents_no_cache() throws Exception {
        checkPackageWithComponents(p -> p);
    }

    @Test
    public void testPackageWithComponents_cached() throws Exception {
        checkPackageWithComponents(p ->
                PackageCacher.fromCacheEntryStatic(PackageCacher.toCacheEntryStatic(p)));
    }

    private void checkPackageWithComponents(
            Function<ParsedPackage, ParsedPackage> converter) throws Exception {
        ParsedPackage p = parsePackage(
                "install_complete_package_info.apk", R.raw.install_complete_package_info,
                converter);
        String packageName = "com.android.frameworks.coretests.install_complete_package_info";

        assertEquals(packageName, p.getPackageName());
        assertEquals(1, p.getPermissions().size());
        assertPermission(
                "com.android.frameworks.coretests.install_complete_package_info.test_permission",
                PermissionInfo.PROTECTION_NORMAL, p.getPermissions().get(0));

        findAndRemoveAppDetailsActivity(p);

        assertOneComponentOfEachType("com.android.frameworks.coretests.Test%s", p);

        assertMetadata(p.getMetaData(),
                "key1", "value1",
                "key2", "this_is_app");
        assertMetadata(p.getActivities().get(0).getMetaData(),
                "key1", "value1",
                "key2", "this_is_activity");
        assertMetadata(p.getServices().get(0).getMetaData(),
                "key1", "value1",
                "key2", "this_is_service");
        assertMetadata(p.getReceivers().get(0).getMetaData(),
                "key1", "value1",
                "key2", "this_is_receiver");
        assertMetadata(p.getProviders().get(0).getMetaData(),
                "key1", "value1",
                "key2", "this_is_provider");

    }

    private void findAndRemoveAppDetailsActivity(ParsedPackage p) {
        // Hidden "app details" activity is added to every package.
        boolean foundAppDetailsActivity = false;
        for (int i = 0; i < ArrayUtils.size(p.getActivities()); i++) {
            if (p.getActivities().get(i).getClassName().equals(
                    PackageManager.APP_DETAILS_ACTIVITY_CLASS_NAME)) {
                foundAppDetailsActivity = true;
                p.getActivities().remove(i);
                break;
            }
        }
        assertTrue("Did not find app details activity", foundAppDetailsActivity);
    }

    @Test
    public void testPackageWithIntentFilters_no_cache() throws Exception {
        checkPackageWithIntentFilters(p -> p);
    }

    @Test
    public void testPackageWithIntentFilters_cached() throws Exception {
        checkPackageWithIntentFilters(p ->
                PackageCacher.fromCacheEntryStatic(PackageCacher.toCacheEntryStatic(p)));
    }

    private void checkPackageWithIntentFilters(
            Function<ParsedPackage, ParsedPackage> converter) throws Exception {
        ParsedPackage p = parsePackage(
                "install_intent_filters.apk", R.raw.install_intent_filters,
                converter);
        String packageName = "com.android.frameworks.servicestests.install_intent_filters";

        assertEquals(packageName, p.getPackageName());

        findAndRemoveAppDetailsActivity(p);

        assertEquals("Expected exactly one activity", 1, p.getActivities().size());
        List<ParsedIntentInfo> intentInfos = p.getActivities().get(0).getIntents();
        assertEquals("Expected exactly one intent filter", 1, intentInfos.size());
        IntentFilter intentFilter = intentInfos.get(0).getIntentFilter();
        assertEquals("Expected exactly one mime group in intent filter", 1,
                intentFilter.countMimeGroups());
        assertTrue("Did not find expected mime group 'mime_group_1'",
                intentFilter.hasMimeGroup("mime_group_1"));
    }

    @Test
    public void testUsesSdk() throws Exception {
        ParsedPackage pkg;
        SparseIntArray minExtVers;
        pkg = parsePackage("install_uses_sdk.apk_r0", R.raw.install_uses_sdk_r0, x -> x);
        minExtVers = pkg.getMinExtensionVersions();
        assertEquals(1, minExtVers.size());
        assertEquals(0, minExtVers.get(30, -1));

        pkg = parsePackage("install_uses_sdk.apk_r0_s0", R.raw.install_uses_sdk_r0_s0, x -> x);
        minExtVers = pkg.getMinExtensionVersions();
        assertEquals(2, minExtVers.size());
        assertEquals(0, minExtVers.get(30, -1));
        assertEquals(0, minExtVers.get(31, -1));

        Map<Pair<String, Integer>, Integer> appToError = new HashMap<>();
        appToError.put(Pair.create("install_uses_sdk.apk_r10000", R.raw.install_uses_sdk_r10000),
                       PackageManager.INSTALL_FAILED_OLDER_SDK);
        appToError.put(
                Pair.create("install_uses_sdk.apk_r0_s10000", R.raw.install_uses_sdk_r0_s10000),
                PackageManager.INSTALL_FAILED_OLDER_SDK);

        appToError.put(Pair.create("install_uses_sdk.apk_q0", R.raw.install_uses_sdk_q0),
                       PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED);
        appToError.put(Pair.create("install_uses_sdk.apk_q0_r0", R.raw.install_uses_sdk_q0_r0),
                       PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED);
        appToError.put(Pair.create("install_uses_sdk.apk_r_none", R.raw.install_uses_sdk_r_none),
                       PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED);
        appToError.put(Pair.create("install_uses_sdk.apk_0", R.raw.install_uses_sdk_0),
                       PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED);

        for (Map.Entry<Pair<String, Integer>, Integer> entry : appToError.entrySet()) {
            String filename = entry.getKey().first;
            int resId = entry.getKey().second;
            int result = entry.getValue();
            try {
                parsePackage(filename, resId, x -> x);
                expect.withMessage("Expected parsing error %s from %s", result, filename).fail();
            } catch (PackageManagerException expected) {
                expect.that(expected.error).isEqualTo(result);
            }
        }
    }
}
