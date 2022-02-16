/*
 * Copyright 2017, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.devicepolicy;

import static android.app.admin.DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE;
import static android.app.admin.DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE;
import static android.app.admin.DevicePolicyManager.ACTION_PROVISION_MANAGED_USER;
import static android.app.admin.DevicePolicyManager.REQUIRED_APP_MANAGED_DEVICE;
import static android.app.admin.DevicePolicyManager.REQUIRED_APP_MANAGED_PROFILE;
import static android.app.admin.DevicePolicyManager.REQUIRED_APP_MANAGED_USER;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ModuleInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.os.Bundle;
import android.test.mock.MockPackageManager;
import android.view.inputmethod.InputMethodInfo;

import androidx.annotation.NonNull;
import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Run this test with:
 *
 * {@code atest FrameworksServicesTests:com.android.server.devicepolicy.OwnersTest}
 *
 */
@RunWith(AndroidJUnit4.class)
public class OverlayPackagesProviderTest {
    private static final String TEST_DPC_PACKAGE_NAME = "dpc.package.name";
    private static final ComponentName TEST_MDM_COMPONENT_NAME = new ComponentName(
            TEST_DPC_PACKAGE_NAME, "pc.package.name.DeviceAdmin");
    private static final int TEST_USER_ID = 123;

    private @Mock Resources mResources;

    private @Mock OverlayPackagesProvider.Injector mInjector;
    private @Mock Context mTestContext;
    private Resources mRealResources;

    private FakePackageManager mPackageManager;
    private String[] mSystemAppsWithLauncher;
    private Set<String> mRegularMainlineModules = new HashSet<>();
    private Map<String, String> mMainlineModuleToDeclaredMetadataMap = new HashMap<>();
    private OverlayPackagesProvider mHelper;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mPackageManager = new FakePackageManager();
        when(mTestContext.getResources()).thenReturn(mResources);
        when(mTestContext.getPackageManager()).thenReturn(mPackageManager);
        when(mTestContext.getFilesDir()).thenReturn(
                InstrumentationRegistry.getTargetContext().getCacheDir());

        setSystemInputMethods();
        setRequiredAppsManagedDevice();
        setVendorRequiredAppsManagedDevice();
        setDisallowedAppsManagedDevice();
        setVendorDisallowedAppsManagedDevice();
        setRequiredAppsManagedProfile();
        setVendorRequiredAppsManagedProfile();
        setDisallowedAppsManagedProfile();
        setVendorDisallowedAppsManagedProfile();
        setRequiredAppsManagedUser();
        setVendorRequiredAppsManagedUser();
        setDisallowedAppsManagedUser();
        setVendorDisallowedAppsManagedUser();

        mRealResources = InstrumentationRegistry.getTargetContext().getResources();
        mHelper = new OverlayPackagesProvider(mTestContext, mInjector);
    }

    @Test
    public void testAppsWithLauncherAreNonRequiredByDefault() {
        setSystemAppsWithLauncher("app.a", "app.b");

        verifyAppsAreNonRequired(ACTION_PROVISION_MANAGED_DEVICE, "app.a", "app.b");
    }

    @Test
    public void testDeviceOwnerRequiredApps() {
        setSystemAppsWithLauncher("app.a", "app.b", "app.c");
        setRequiredAppsManagedDevice("app.a");
        setVendorRequiredAppsManagedDevice("app.b");

        verifyAppsAreNonRequired(ACTION_PROVISION_MANAGED_DEVICE, "app.c");
    }

    @Test
    public void testProfileOwnerRequiredApps() {
        setSystemAppsWithLauncher("app.a", "app.b", "app.c");
        setRequiredAppsManagedProfile("app.a");
        setVendorRequiredAppsManagedProfile("app.b");

        verifyAppsAreNonRequired(ACTION_PROVISION_MANAGED_PROFILE, "app.c");
    }

    @Test
    public void testManagedUserRequiredApps() {
        setSystemAppsWithLauncher("app.a", "app.b", "app.c");
        setRequiredAppsManagedUser("app.a");
        setVendorRequiredAppsManagedUser("app.b");

        verifyAppsAreNonRequired(ACTION_PROVISION_MANAGED_USER, "app.c");
    }

    @Test
    public void testDpcIsRequired() {
        setSystemAppsWithLauncher("app.a", TEST_DPC_PACKAGE_NAME);

        verifyAppsAreNonRequired(ACTION_PROVISION_MANAGED_DEVICE, "app.a");
    }

    @Test
    public void testDisallowedAppsAreNonRequiredEvenIfNoLauncher() {
        setSystemAppsWithLauncher();
        setDisallowedAppsManagedDevice("app.a");
        setVendorDisallowedAppsManagedDevice("app.b");

        verifyAppsAreNonRequired(ACTION_PROVISION_MANAGED_DEVICE, "app.a", "app.b");
    }

    @Test
    public void testDeviceOwnerImesAreRequired() {
        setSystemAppsWithLauncher("app.a", "app.b");
        setSystemInputMethods("app.a");

        verifyAppsAreNonRequired(ACTION_PROVISION_MANAGED_DEVICE, "app.b");
    }

    @Test
    public void testProfileOwnerImesAreNonRequired() {
        setSystemAppsWithLauncher("app.a", "app.b");
        setSystemInputMethods("app.a");

        verifyAppsAreNonRequired(ACTION_PROVISION_MANAGED_PROFILE, "app.b");
    }

    @Test
    public void testManagedUserImesAreRequired() {
        setSystemAppsWithLauncher("app.a", "app.b");
        setSystemInputMethods("app.a");

        verifyAppsAreNonRequired(ACTION_PROVISION_MANAGED_USER, "app.b");
    }

    @Test
    public void testDisallowedAppsAreNonInstalled() {
        setSystemAppsWithLauncher("app.a");
        setDisallowedAppsManagedDevice("app.c");

        verifyAppsAreNonRequired(ACTION_PROVISION_MANAGED_DEVICE, "app.a", "app.c");
    }

    /**
     * If an app is listed as both required and disallowed, it should be only in the disallowed
     * list. Therefore, it should be present in the non-required list.
     */
    @Test
    public void testAllowedAndDisallowedAtTheSameTimeManagedDevice() {
        setDisallowedAppsManagedDevice(TEST_DPC_PACKAGE_NAME);
        setRequiredAppsManagedDevice(TEST_DPC_PACKAGE_NAME);

        verifyAppsAreNonRequired(ACTION_PROVISION_MANAGED_DEVICE, TEST_DPC_PACKAGE_NAME);
    }

    /**
     * @see {@link #testAllowedAndDisallowedAtTheSameTimeManagedDevice}
     */
    @Test
    public void testAllowedAndDisallowedAtTheSameTimeManagedUser() {
        setDisallowedAppsManagedUser(TEST_DPC_PACKAGE_NAME);
        setRequiredAppsManagedUser(TEST_DPC_PACKAGE_NAME);

        verifyAppsAreNonRequired(ACTION_PROVISION_MANAGED_USER, TEST_DPC_PACKAGE_NAME);
    }

    /**
     * @see {@link #testAllowedAndDisallowedAtTheSameTimeManagedDevice}
     */
    @Test
    public void testAllowedAndDisallowedAtTheSameTimeManagedProfile() {
        setDisallowedAppsManagedProfile(TEST_DPC_PACKAGE_NAME);
        setRequiredAppsManagedProfile(TEST_DPC_PACKAGE_NAME);

        verifyAppsAreNonRequired(ACTION_PROVISION_MANAGED_PROFILE, TEST_DPC_PACKAGE_NAME);
    }

    @Test
    public void testNotRequiredAndDisallowedInResManagedDevice() {
        verifyEmptyIntersection(R.array.required_apps_managed_device,
                R.array.disallowed_apps_managed_device);
    }

    @Test
    public void testNotRequiredAndDisallowedInResManagedUser() {
        verifyEmptyIntersection(R.array.required_apps_managed_user,
                R.array.disallowed_apps_managed_user);
    }

    @Test
    public void testNotRequiredAndDisallowedInResManagedProfile() {
        verifyEmptyIntersection(R.array.required_apps_managed_profile,
                R.array.disallowed_apps_managed_profile);
    }

    @Test
    public void testNotRequiredAndDisallowedInResManagedDeviceVendor() {
        verifyEmptyIntersection(R.array.vendor_required_apps_managed_device,
                R.array.vendor_disallowed_apps_managed_device);
    }

    @Test
    public void testNotRequiredAndDisallowedInResManagedUserVendor() {
        verifyEmptyIntersection(R.array.vendor_required_apps_managed_user,
                R.array.vendor_disallowed_apps_managed_user);
    }

    @Test
    public void testNotRequiredAndDisallowedInResManagedProfileVendor() {
        verifyEmptyIntersection(R.array.vendor_required_apps_managed_profile,
                R.array.vendor_disallowed_apps_managed_profile);
    }

    @Test
    public void testGetNonRequiredApps_mainlineModules_managedProfile_works() {
        setupApexModulesWithManagedProfile("package1");
        setupRegularModulesWithManagedProfile("package2");
        setSystemAppsWithLauncher("package1", "package2", "package3");

        verifyAppsAreNonRequired(ACTION_PROVISION_MANAGED_PROFILE, "package3");
    }

    @Test
    public void testGetNonRequiredApps_mainlineModules_managedDevice_works() {
        setupApexModulesWithManagedDevice("package1");
        setupRegularModulesWithManagedDevice("package2");
        setSystemAppsWithLauncher("package1", "package2", "package3");

        verifyAppsAreNonRequired(ACTION_PROVISION_MANAGED_DEVICE, "package3");
    }

    @Test
    public void testGetNonRequiredApps_mainlineModules_managedUser_works() {
        setupApexModulesWithManagedUser("package1");
        setupRegularModulesWithManagedUser("package2");
        setSystemAppsWithLauncher("package1", "package2", "package3");

        verifyAppsAreNonRequired(ACTION_PROVISION_MANAGED_USER, "package3");
    }

    @Test
    public void testGetNonRequiredApps_mainlineModules_noMetadata_works() {
        setupApexModulesWithNoMetadata("package1");
        setupRegularModulesWithNoMetadata("package2");
        setSystemAppsWithLauncher("package1", "package2", "package3");

        verifyAppsAreNonRequired(
                ACTION_PROVISION_MANAGED_PROFILE, "package1", "package2", "package3");
    }

    private void setupRegularModulesWithManagedUser(String... regularModules) {
        setupRegularModulesWithMetadata(regularModules, REQUIRED_APP_MANAGED_USER);
    }

    private void setupRegularModulesWithManagedDevice(String... regularModules) {
        setupRegularModulesWithMetadata(regularModules, REQUIRED_APP_MANAGED_DEVICE);
    }

    private void setupRegularModulesWithManagedProfile(String... regularModules) {
        setupRegularModulesWithMetadata(regularModules, REQUIRED_APP_MANAGED_PROFILE);
    }

    private void setupRegularModulesWithNoMetadata(String... regularModules) {
        mRegularMainlineModules.addAll(Arrays.asList(regularModules));
    }

    private void setupRegularModulesWithMetadata(String[] regularModules, String metadataKey) {
        for (String regularModule : regularModules) {
            mRegularMainlineModules.add(regularModule);
            mMainlineModuleToDeclaredMetadataMap.put(regularModule, metadataKey);
        }
    }

    private void setupApexModulesWithManagedUser(String... apexPackageNames) {
        setupApexModulesWithMetadata(apexPackageNames, REQUIRED_APP_MANAGED_USER);
    }

    private void setupApexModulesWithManagedDevice(String... apexPackageNames) {
        setupApexModulesWithMetadata(apexPackageNames, REQUIRED_APP_MANAGED_DEVICE);
    }

    private void setupApexModulesWithManagedProfile(String... apexPackageNames) {
        setupApexModulesWithMetadata(apexPackageNames, REQUIRED_APP_MANAGED_PROFILE);
    }

    private void setupApexModulesWithNoMetadata(String... apexPackageNames) {
        for (String apexPackageName : apexPackageNames) {
            when(mInjector.getActiveApexPackageNameContainingPackage(eq(apexPackageName)))
                    .thenReturn("apex");
        }
    }

    private void setupApexModulesWithMetadata(String[] apexPackageNames, String metadataKey) {
        for (String apexPackageName : apexPackageNames) {
            when(mInjector.getActiveApexPackageNameContainingPackage(eq(apexPackageName)))
                    .thenReturn("apex");
            mMainlineModuleToDeclaredMetadataMap.put(apexPackageName, metadataKey);
        }
    }

    private ArrayList<String> getStringArrayInRealResources(int id) {
        return new ArrayList<>(Arrays.asList(mRealResources.getStringArray(id)));
    }

    private void verifyEmptyIntersection(int requiredId, int disallowedId) {
        ArrayList<String> required = getStringArrayInRealResources(requiredId);
        ArrayList<String> disallowed = getStringArrayInRealResources(disallowedId);
        required.retainAll(disallowed);
        assertThat(required.isEmpty()).isTrue();
    }

    private void verifyAppsAreNonRequired(String action, String... appArray) {
        assertThat(mHelper.getNonRequiredApps(TEST_MDM_COMPONENT_NAME, TEST_USER_ID, action))
                .containsExactlyElementsIn(setFromArray(appArray));
    }

    private void setRequiredAppsManagedDevice(String... apps) {
        setStringArray(R.array.required_apps_managed_device, apps);
    }

    private void setVendorRequiredAppsManagedDevice(String... apps) {
        setStringArray(R.array.vendor_required_apps_managed_device, apps);
    }

    private void setDisallowedAppsManagedDevice(String... apps) {
        setStringArray(R.array.disallowed_apps_managed_device, apps);
    }

    private void setVendorDisallowedAppsManagedDevice(String... apps) {
        setStringArray(R.array.vendor_disallowed_apps_managed_device, apps);
    }

    private void setRequiredAppsManagedProfile(String... apps) {
        setStringArray(R.array.required_apps_managed_profile, apps);
    }

    private void setVendorRequiredAppsManagedProfile(String... apps) {
        setStringArray(R.array.vendor_required_apps_managed_profile, apps);
    }

    private void setDisallowedAppsManagedProfile(String... apps) {
        setStringArray(R.array.disallowed_apps_managed_profile, apps);
    }

    private void setVendorDisallowedAppsManagedProfile(String... apps) {
        setStringArray(R.array.vendor_disallowed_apps_managed_profile, apps);
    }

    private void setRequiredAppsManagedUser(String... apps) {
        setStringArray(R.array.required_apps_managed_user, apps);
    }

    private void setVendorRequiredAppsManagedUser(String... apps) {
        setStringArray(R.array.vendor_required_apps_managed_user, apps);
    }

    private void setDisallowedAppsManagedUser(String... apps) {
        setStringArray(R.array.disallowed_apps_managed_user, apps);
    }

    private void setVendorDisallowedAppsManagedUser(String... apps) {
        setStringArray(R.array.vendor_disallowed_apps_managed_user, apps);
    }

    private void setStringArray(int resourceId, String[] strs) {
        when(mResources.getStringArray(eq(resourceId)))
                .thenReturn(strs);
    }

    private void setSystemInputMethods(String... packageNames) {
        List<InputMethodInfo> inputMethods = new ArrayList<InputMethodInfo>();
        for (String packageName : packageNames) {
            ApplicationInfo aInfo = new ApplicationInfo();
            aInfo.flags = ApplicationInfo.FLAG_SYSTEM;
            ServiceInfo serviceInfo = new ServiceInfo();
            serviceInfo.applicationInfo = aInfo;
            serviceInfo.packageName = packageName;
            serviceInfo.name = "";
            ResolveInfo ri = new ResolveInfo();
            ri.serviceInfo = serviceInfo;
            InputMethodInfo inputMethodInfo = new InputMethodInfo(ri, false, null, null, 0, false);
            inputMethods.add(inputMethodInfo);
        }
        when(mInjector.getInputMethodListAsUser(eq(TEST_USER_ID))).thenReturn(inputMethods);
    }

    private void setSystemAppsWithLauncher(String... apps) {
        mSystemAppsWithLauncher = apps;
    }

    private <T> Set<T> setFromArray(T... array) {
        if (array == null) {
            return null;
        }
        return new HashSet<>(Arrays.asList(array));
    }

    class FakePackageManager extends MockPackageManager {
        @Override
        public List<ResolveInfo> queryIntentActivitiesAsUser(Intent intent, int flags, int userId) {
            assertWithMessage("Expected an intent with action ACTION_MAIN")
                    .that(Intent.ACTION_MAIN.equals(intent.getAction())).isTrue();
            assertWithMessage("Expected an intent with category CATEGORY_LAUNCHER")
                    .that(intent.getCategories()).containsExactly(Intent.CATEGORY_LAUNCHER);
            assertWithMessage("Expected the flag MATCH_UNINSTALLED_PACKAGES")
                    .that((flags & PackageManager.MATCH_UNINSTALLED_PACKAGES)).isNotEqualTo(0);
            assertWithMessage("Expected the flag MATCH_DISABLED_COMPONENTS")
                    .that((flags & PackageManager.MATCH_DISABLED_COMPONENTS)).isNotEqualTo(0);
            assertWithMessage("Expected the flag MATCH_DIRECT_BOOT_AWARE")
                    .that((flags & PackageManager.MATCH_DIRECT_BOOT_AWARE)).isNotEqualTo(0);
            assertWithMessage("Expected the flag MATCH_DIRECT_BOOT_UNAWARE")
                    .that((flags & PackageManager.MATCH_DIRECT_BOOT_UNAWARE)).isNotEqualTo(0);
            assertThat(TEST_USER_ID).isEqualTo(userId);
            List<ResolveInfo> result = new ArrayList<>();
            if (mSystemAppsWithLauncher == null) {
                return result;
            }
            for (String packageName : mSystemAppsWithLauncher) {
                ActivityInfo ai = new ActivityInfo();
                ai.packageName = packageName;
                ResolveInfo ri = new ResolveInfo();
                ri.activityInfo = ai;
                result.add(ri);
            }
            return result;
        }

        @NonNull
        @Override
        public PackageInfo getPackageInfo(String packageName, int flags) {
            final PackageInfo packageInfo = new PackageInfo();
            final ApplicationInfo applicationInfo = new ApplicationInfo();
            applicationInfo.metaData = new Bundle();
            if (mMainlineModuleToDeclaredMetadataMap.containsKey(packageName)) {
                applicationInfo.metaData.putBoolean(
                        mMainlineModuleToDeclaredMetadataMap.get(packageName), true);
            }
            packageInfo.applicationInfo = applicationInfo;
            return packageInfo;
        }

        @NonNull
        @Override
        public ModuleInfo getModuleInfo(@NonNull String packageName, int flags)
                throws NameNotFoundException {
            if (!mRegularMainlineModules.contains(packageName)) {
                throw new NameNotFoundException("package does not exist");
            }
            return new ModuleInfo().setName(packageName);
        }
    }
}
