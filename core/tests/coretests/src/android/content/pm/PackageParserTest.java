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

package android.content.pm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.pm.PackageParser.Component;
import android.content.pm.PackageParser.Package;
import android.content.pm.PackageParser.Permission;
import android.os.Build;
import android.os.Bundle;
import android.os.FileUtils;
import android.os.SystemProperties;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.coretests.R;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.InputStream;
import java.util.Arrays;
import java.util.function.Function;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class PackageParserTest {
    private static final String RELEASED = null;
    private static final String OLDER_PRE_RELEASE = "A";
    private static final String PRE_RELEASE = "B";
    private static final String NEWER_PRE_RELEASE = "C";

    // Codenames with a fingerprint attached to them. These may only be present in the apps
    // declared min SDK and not as platform codenames.
    private static final String OLDER_PRE_RELEASE_WITH_FINGERPRINT = "A.fingerprint";
    private static final String PRE_RELEASE_WITH_FINGERPRINT = "B.fingerprint";
    private static final String NEWER_PRE_RELEASE_WITH_FINGERPRINT = "C.fingerprint";

    private static final String[] CODENAMES_RELEASED = { /* empty */ };
    private static final String[] CODENAMES_PRE_RELEASE = { PRE_RELEASE };

    private static final int OLDER_VERSION = 10;
    private static final int PLATFORM_VERSION = 20;
    private static final int NEWER_VERSION = 30;

    private void verifyComputeMinSdkVersion(int minSdkVersion, String minSdkCodename,
            boolean isPlatformReleased, int expectedMinSdk) {
        final String[] outError = new String[1];
        final int result = PackageParser.computeMinSdkVersion(
                minSdkVersion,
                minSdkCodename,
                PLATFORM_VERSION,
                isPlatformReleased ? CODENAMES_RELEASED : CODENAMES_PRE_RELEASE,
                outError);

        assertEquals("Error msg: " + outError[0], expectedMinSdk, result);

        if (expectedMinSdk == -1) {
            assertNotNull(outError[0]);
        } else {
            assertNull(outError[0]);
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
        verifyComputeMinSdkVersion(NEWER_VERSION, NEWER_PRE_RELEASE, false, -1);
        verifyComputeMinSdkVersion(NEWER_VERSION, NEWER_PRE_RELEASE_WITH_FINGERPRINT, false, -1);
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
        verifyComputeMinSdkVersion(OLDER_VERSION, OLDER_PRE_RELEASE, true, -1);
        verifyComputeMinSdkVersion(OLDER_VERSION, OLDER_PRE_RELEASE_WITH_FINGERPRINT, true, -1);

        // Don't allow same pre-release minSdkVersion on released platform.
        // APP: Pre-release API 20
        // DEV: Released API 20
        verifyComputeMinSdkVersion(PLATFORM_VERSION, PRE_RELEASE, true, -1);
        verifyComputeMinSdkVersion(PLATFORM_VERSION, PRE_RELEASE_WITH_FINGERPRINT, true, -1);


        // Don't allow newer pre-release minSdkVersion on released platform.
        // APP: Pre-release API 30
        // DEV: Released API 20
        verifyComputeMinSdkVersion(NEWER_VERSION, NEWER_PRE_RELEASE, true, -1);
        verifyComputeMinSdkVersion(NEWER_VERSION, NEWER_PRE_RELEASE_WITH_FINGERPRINT, true, -1);
    }

    private void verifyComputeTargetSdkVersion(int targetSdkVersion, String targetSdkCodename,
            boolean isPlatformReleased, int expectedTargetSdk, boolean forceCurrentDev) {
        final String[] outError = new String[1];
        final int result = PackageParser.computeTargetSdkVersion(
                targetSdkVersion,
                targetSdkCodename,
                isPlatformReleased ? CODENAMES_RELEASED : CODENAMES_PRE_RELEASE,
                outError,
                forceCurrentDev);

        assertEquals(result, expectedTargetSdk);

        if (expectedTargetSdk == -1) {
            assertNotNull(outError[0]);
        } else {
            assertNull(outError[0]);
        }
    }

    @Test
    public void testComputeTargetSdkVersion_preReleasePlatform() {
        // Do allow older release targetSdkVersion on pre-release platform.
        // APP: Released API 10
        // DEV: Pre-release API 20
        verifyComputeTargetSdkVersion(OLDER_VERSION, RELEASED, false, OLDER_VERSION,
                false /* forceCurrentDev */);

        // Do allow same release targetSdkVersion on pre-release platform.
        // APP: Released API 20
        // DEV: Pre-release API 20
        verifyComputeTargetSdkVersion(PLATFORM_VERSION, RELEASED, false, PLATFORM_VERSION,
                false /* forceCurrentDev */);

        // Do allow newer release targetSdkVersion on pre-release platform.
        // APP: Released API 30
        // DEV: Pre-release API 20
        verifyComputeTargetSdkVersion(NEWER_VERSION, RELEASED, false, NEWER_VERSION,
                false /* forceCurrentDev */);

        // Don't allow older pre-release targetSdkVersion on pre-release platform.
        // APP: Pre-release API 10
        // DEV: Pre-release API 20
        verifyComputeTargetSdkVersion(OLDER_VERSION, OLDER_PRE_RELEASE, false, -1,
                false /* forceCurrentDev */);
        verifyComputeTargetSdkVersion(OLDER_VERSION, OLDER_PRE_RELEASE_WITH_FINGERPRINT, false, -1,
                false /* forceCurrentDev */);


        // Do allow same pre-release targetSdkVersion on pre-release platform,
        // but overwrite the specified version with CUR_DEVELOPMENT.
        // APP: Pre-release API 20
        // DEV: Pre-release API 20
        verifyComputeTargetSdkVersion(PLATFORM_VERSION, PRE_RELEASE, false,
                Build.VERSION_CODES.CUR_DEVELOPMENT, false /* forceCurrentDev */);
        verifyComputeTargetSdkVersion(PLATFORM_VERSION, PRE_RELEASE_WITH_FINGERPRINT, false,
                Build.VERSION_CODES.CUR_DEVELOPMENT, false /* forceCurrentDev */);


        // Don't allow newer pre-release targetSdkVersion on pre-release platform.
        // APP: Pre-release API 30
        // DEV: Pre-release API 20
        verifyComputeTargetSdkVersion(NEWER_VERSION, NEWER_PRE_RELEASE, false, -1,
                false /* forceCurrentDev */);
        verifyComputeTargetSdkVersion(NEWER_VERSION, NEWER_PRE_RELEASE_WITH_FINGERPRINT, false, -1,
                false /* forceCurrentDev */);


        // Force newer pre-release targetSdkVersion to current pre-release platform.
        // APP: Pre-release API 30
        // DEV: Pre-release API 20
        verifyComputeTargetSdkVersion(NEWER_VERSION, NEWER_PRE_RELEASE, false,
                Build.VERSION_CODES.CUR_DEVELOPMENT, true /* forceCurrentDev */);
        verifyComputeTargetSdkVersion(NEWER_VERSION, NEWER_PRE_RELEASE_WITH_FINGERPRINT, false,
                Build.VERSION_CODES.CUR_DEVELOPMENT, true /* forceCurrentDev */);
    }

    @Test
    public void testComputeTargetSdkVersion_releasedPlatform() {
        // Do allow older release targetSdkVersion on released platform.
        // APP: Released API 10
        // DEV: Released API 20
        verifyComputeTargetSdkVersion(OLDER_VERSION, RELEASED, true, OLDER_VERSION,
                false /* forceCurrentDev */);

        // Do allow same release targetSdkVersion on released platform.
        // APP: Released API 20
        // DEV: Released API 20
        verifyComputeTargetSdkVersion(PLATFORM_VERSION, RELEASED, true, PLATFORM_VERSION,
                false /* forceCurrentDev */);

        // Do allow newer release targetSdkVersion on released platform.
        // APP: Released API 30
        // DEV: Released API 20
        verifyComputeTargetSdkVersion(NEWER_VERSION, RELEASED, true, NEWER_VERSION,
                false /* forceCurrentDev */);

        // Don't allow older pre-release targetSdkVersion on released platform.
        // APP: Pre-release API 10
        // DEV: Released API 20
        verifyComputeTargetSdkVersion(OLDER_VERSION, OLDER_PRE_RELEASE, true, -1,
                false /* forceCurrentDev */);
        verifyComputeTargetSdkVersion(OLDER_VERSION, OLDER_PRE_RELEASE_WITH_FINGERPRINT, true, -1,
                false /* forceCurrentDev */);

        // Don't allow same pre-release targetSdkVersion on released platform.
        // APP: Pre-release API 20
        // DEV: Released API 20
        verifyComputeTargetSdkVersion(PLATFORM_VERSION, PRE_RELEASE, true, -1,
                false /* forceCurrentDev */);
        verifyComputeTargetSdkVersion(PLATFORM_VERSION, PRE_RELEASE_WITH_FINGERPRINT, true, -1,
                false /* forceCurrentDev */);


        // Don't allow newer pre-release targetSdkVersion on released platform.
        // APP: Pre-release API 30
        // DEV: Released API 20
        verifyComputeTargetSdkVersion(NEWER_VERSION, NEWER_PRE_RELEASE, true, -1,
                false /* forceCurrentDev */);
        verifyComputeTargetSdkVersion(NEWER_VERSION, NEWER_PRE_RELEASE_WITH_FINGERPRINT, true, -1,
                false /* forceCurrentDev */);
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
        int finalConfigChanges =
                PackageParser.getActivityConfigChanges(configChanges, recreateOnConfigChanges);
        assertEquals(0x0003, finalConfigChanges); // Should be 00000011.

        // Not set in configChanges, but set in recreateOnConfigChanges.
        configChanges = 0x0000; // 00000000.
        recreateOnConfigChanges = 0x0003; // 00000011.
        finalConfigChanges =
                PackageParser.getActivityConfigChanges(configChanges, recreateOnConfigChanges);
        assertEquals(0x0000, finalConfigChanges); // Should be 00000000.

        // Set in configChanges.
        configChanges = 0x0003; // 00000011.
        recreateOnConfigChanges = 0X0000; // 00000000.
        finalConfigChanges =
                PackageParser.getActivityConfigChanges(configChanges, recreateOnConfigChanges);
        assertEquals(0x0003, finalConfigChanges); // Should be 00000011.

        recreateOnConfigChanges = 0x0003; // 00000011.
        finalConfigChanges =
                PackageParser.getActivityConfigChanges(configChanges, recreateOnConfigChanges);
        assertEquals(0x0003, finalConfigChanges); // Should still be 00000011.

        // Other bit set in configChanges.
        configChanges = 0x0080; // 10000000, orientation.
        recreateOnConfigChanges = 0x0000; // 00000000.
        finalConfigChanges =
                PackageParser.getActivityConfigChanges(configChanges, recreateOnConfigChanges);
        assertEquals(0x0083, finalConfigChanges); // Should be 10000011.
    }

    Package parsePackage(String apkFileName, int apkResourceId) throws Exception {
        return parsePackage(apkFileName, apkResourceId, p -> p);
    }

    /**
     * Copies a specified {@code resourceId} to a file. Returns a non-null file if the copy
     * succeeded, or {@code null} otherwise.
     */
    File copyRawResourceToFile(String baseName, int resourceId) throws Exception {
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
     * @param apkFileName temporary file name to store apk extracted from resources
     * @param apkResourceId identifier of the apk as a resource
     */
    Package parsePackage(String apkFileName, int apkResourceId,
            Function<Package, Package> converter) throws Exception {
        // Copy the resource to a file.
        File outFile = null;
        try {
            outFile = copyRawResourceToFile(apkFileName, apkResourceId);
            return converter.apply(new PackageParser().parsePackage(outFile, 0 /* flags */));
        } finally {
            if (outFile != null) {
                outFile.delete();
            }
        }
    }

    /**
     * Asserts basic properties about a component.
     */
    private void assertComponent(String className, String packageName, int numIntents,
            Component<?> component) {
        assertEquals(className, component.className);
        assertEquals(packageName, component.owner.packageName);
        assertEquals(numIntents, component.intents.size());
    }

    /**
     * Asserts four regularly-named components of each type: one Activity, one Service, one
     * Provider, and one Receiver.
     * @param template templated string with %s subbed with Activity, Service, Provider, Receiver
     */
    private void assertOneComponentOfEachType(String template, Package p) {
        String packageName = p.packageName;

        assertEquals(1, p.activities.size());
        assertComponent(String.format(template, "Activity"),
                packageName, 0 /* intents */, p.activities.get(0));
        assertEquals(1, p.services.size());
        assertComponent(String.format(template, "Service"),
                packageName, 0 /* intents */, p.services.get(0));
        assertEquals(1, p.providers.size());
        assertComponent(String.format(template, "Provider"),
                packageName, 0 /* intents */, p.providers.get(0));
        assertEquals(1, p.receivers.size());
        assertComponent(String.format(template, "Receiver"),
                packageName, 0 /* intents */, p.receivers.get(0));
    }

    private void assertPermission(String name, String packageName, int protectionLevel,
            Permission permission) {
        assertEquals(packageName, permission.owner.packageName);
        assertEquals(name, permission.info.name);
        assertEquals(protectionLevel, permission.info.protectionLevel);
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
                PackageParser.fromCacheEntryStatic(PackageParser.toCacheEntryStatic(p)));
    }

    private void checkPackageWithComponents(
            Function<Package, Package> converter) throws Exception {
        Package p = parsePackage(
                "install_complete_package_info.apk", R.raw.install_complete_package_info,
                converter);
        String packageName = "com.android.frameworks.coretests.install_complete_package_info";

        assertEquals(packageName, p.packageName);
        assertEquals(1, p.permissions.size());
        assertPermission(
                "com.android.frameworks.coretests.install_complete_package_info.test_permission",
                packageName, PermissionInfo.PROTECTION_NORMAL, p.permissions.get(0));

        assertOneComponentOfEachType("com.android.frameworks.coretests.Test%s", p);

        assertMetadata(p.mAppMetaData,
                "key1", "value1",
                "key2", "this_is_app");
        assertMetadata(p.activities.get(0).metaData,
                "key1", "value1",
                "key2", "this_is_activity");
        assertMetadata(p.services.get(0).metaData,
                "key1", "value1",
                "key2", "this_is_service");
        assertMetadata(p.receivers.get(0).metaData,
                "key1", "value1",
                "key2", "this_is_receiver");
        assertMetadata(p.providers.get(0).metaData,
                "key1", "value1",
                "key2", "this_is_provider");
    }

    /**
     * Determines if the current device supports multi-package APKs.
     */
    private boolean supportsMultiPackageApk() {
        return SystemProperties.getBoolean("persist.sys.child_packages_enabled", false);
    }

    @Test
    public void testMultiPackageComponents() throws Exception {
        // TODO(gboyer): Remove once we decide to launch multi-package APKs.
        if (!supportsMultiPackageApk()) {
            return;
        }
        String parentName = "com.android.frameworks.coretests.install_multi_package";
        String firstChildName =
                "com.android.frameworks.coretests.install_multi_package.first_child";
        String secondChildName =  // NOTE: intentionally inconsistent!
                "com.android.frameworks.coretests.blah.second_child";

        Package parent = parsePackage("install_multi_package.apk", R.raw.install_multi_package);
        assertEquals(parentName, parent.packageName);
        assertEquals(2, parent.childPackages.size());
        assertOneComponentOfEachType("com.android.frameworks.coretests.Test%s", parent);
        assertEquals(1, parent.permissions.size());
        assertPermission(parentName + ".test_permission", parentName,
                PermissionInfo.PROTECTION_NORMAL, parent.permissions.get(0));
        assertEquals(Arrays.asList("android.permission.INTERNET"),
                parent.requestedPermissions);

        Package firstChild = parent.childPackages.get(0);
        assertEquals(firstChildName, firstChild.packageName);
        assertOneComponentOfEachType(
                "com.android.frameworks.coretests.FirstChildTest%s", firstChild);
        assertEquals(0, firstChild.permissions.size());  // Child APKs cannot declare permissions.
        assertEquals(Arrays.asList("android.permission.NFC"),
                firstChild.requestedPermissions);

        Package secondChild = parent.childPackages.get(1);
        assertEquals(secondChildName, secondChild.packageName);
        assertOneComponentOfEachType(
                "com.android.frameworks.coretests.SecondChildTest%s", secondChild);
        assertEquals(0, secondChild.permissions.size());  // Child APKs cannot declare permissions.
        assertEquals(
                Arrays.asList(
                        "android.permission.ACCESS_NETWORK_STATE",
                        "android.permission.READ_CONTACTS"),
                secondChild.requestedPermissions);
    }

    @Test
    public void testApexPackageInfoGeneration() throws Exception {
        File apexFile = copyRawResourceToFile("com.android.tzdata.apex",
                R.raw.com_android_tzdata);
        PackageInfo pi = PackageParser.generatePackageInfoFromApex(apexFile, false);
        assertEquals("com.google.android.tzdata", pi.packageName);
        assertEquals("com.google.android.tzdata", pi.applicationInfo.packageName);
        assertEquals(1, pi.getLongVersionCode());
        assertEquals(1, pi.applicationInfo.longVersionCode);
        assertNull(pi.signingInfo);

        pi = PackageParser.generatePackageInfoFromApex(apexFile, true);
        assertEquals("com.google.android.tzdata", pi.packageName);
        assertEquals("com.google.android.tzdata", pi.applicationInfo.packageName);
        assertEquals(1, pi.getLongVersionCode());
        assertEquals(1, pi.applicationInfo.longVersionCode);
        assertNotNull(pi.signingInfo);
        assertTrue(pi.signingInfo.getApkContentsSigners().length > 0);
    }
}
