/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.contentprotection;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.Manifest.permission;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManager.PackageInfoFlags;
import android.testing.TestableContext;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Test for {@link ContentProtectionPackageManager}.
 *
 * <p>Run with: {@code atest
 * FrameworksServicesTests:com.android.server.contentprotection.ContentProtectionPackageManagerTest}
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class ContentProtectionPackageManagerTest {
    private static final String PACKAGE_NAME = "PACKAGE_NAME";

    private static final PackageInfo EMPTY_PACKAGE_INFO = new PackageInfo();

    private static final PackageInfo SYSTEM_APP_PACKAGE_INFO = createSystemAppPackageInfo();

    private static final PackageInfo UPDATED_SYSTEM_APP_PACKAGE_INFO =
            createUpdatedSystemAppPackageInfo();

    @Rule public final MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Rule
    public final TestableContext mContext =
            new TestableContext(ApplicationProvider.getApplicationContext());

    @Mock private PackageManager mMockPackageManager;

    private ContentProtectionPackageManager mContentProtectionPackageManager;

    @Before
    public void setup() {
        mContext.setMockPackageManager(mMockPackageManager);
        mContentProtectionPackageManager = new ContentProtectionPackageManager(mContext);
    }

    @Test
    public void getPackageInfo_found() throws Exception {
        PackageInfo expected = createPackageInfo(/* flags= */ 0);
        when(mMockPackageManager.getPackageInfo(eq(PACKAGE_NAME), any(PackageInfoFlags.class)))
                .thenReturn(expected);

        PackageInfo actual = mContentProtectionPackageManager.getPackageInfo(PACKAGE_NAME);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    public void getPackageInfo_notFound() throws Exception {
        when(mMockPackageManager.getPackageInfo(eq(PACKAGE_NAME), any(PackageInfoFlags.class)))
                .thenThrow(new NameNotFoundException());

        PackageInfo actual = mContentProtectionPackageManager.getPackageInfo(PACKAGE_NAME);

        assertThat(actual).isNull();
    }

    @Test
    public void getPackageInfo_null() {
        PackageInfo actual = mContentProtectionPackageManager.getPackageInfo(PACKAGE_NAME);

        assertThat(actual).isNull();
    }

    @Test
    public void isSystemApp_true() {
        boolean actual = mContentProtectionPackageManager.isSystemApp(SYSTEM_APP_PACKAGE_INFO);

        assertThat(actual).isTrue();
    }

    @Test
    public void isSystemApp_false() {
        boolean actual =
                mContentProtectionPackageManager.isSystemApp(UPDATED_SYSTEM_APP_PACKAGE_INFO);

        assertThat(actual).isFalse();
    }

    @Test
    public void isSystemApp_noApplicationInfo() {
        boolean actual = mContentProtectionPackageManager.isSystemApp(EMPTY_PACKAGE_INFO);

        assertThat(actual).isFalse();
    }

    @Test
    public void isUpdatedSystemApp_true() {
        boolean actual =
                mContentProtectionPackageManager.isUpdatedSystemApp(
                        UPDATED_SYSTEM_APP_PACKAGE_INFO);

        assertThat(actual).isTrue();
    }

    @Test
    public void isUpdatedSystemApp_false() {
        boolean actual =
                mContentProtectionPackageManager.isUpdatedSystemApp(SYSTEM_APP_PACKAGE_INFO);

        assertThat(actual).isFalse();
    }

    @Test
    public void isUpdatedSystemApp_noApplicationInfo() {
        boolean actual = mContentProtectionPackageManager.isUpdatedSystemApp(EMPTY_PACKAGE_INFO);

        assertThat(actual).isFalse();
    }

    @Test
    public void hasRequestedInternetPermissions_true() {
        PackageInfo packageInfo = createPackageInfo(new String[] {permission.INTERNET});

        boolean actual =
                mContentProtectionPackageManager.hasRequestedInternetPermissions(packageInfo);

        assertThat(actual).isTrue();
    }

    @Test
    public void hasRequestedInternetPermissions_false() {
        PackageInfo packageInfo = createPackageInfo(new String[] {permission.ACCESS_FINE_LOCATION});

        boolean actual =
                mContentProtectionPackageManager.hasRequestedInternetPermissions(packageInfo);

        assertThat(actual).isFalse();
    }

    @Test
    public void hasRequestedInternetPermissions_noRequestedPermissions() {
        boolean actual =
                mContentProtectionPackageManager.hasRequestedInternetPermissions(
                        EMPTY_PACKAGE_INFO);

        assertThat(actual).isFalse();
    }

    private static PackageInfo createSystemAppPackageInfo() {
        return createPackageInfo(ApplicationInfo.FLAG_SYSTEM);
    }

    private static PackageInfo createUpdatedSystemAppPackageInfo() {
        return createPackageInfo(ApplicationInfo.FLAG_UPDATED_SYSTEM_APP);
    }

    private static PackageInfo createPackageInfo(int flags) {
        return createPackageInfo(flags, /* requestedPermissions= */ new String[0]);
    }

    private static PackageInfo createPackageInfo(String[] requestedPermissions) {
        return createPackageInfo(/* flags= */ 0, requestedPermissions);
    }

    private static PackageInfo createPackageInfo(int flags, String[] requestedPermissions) {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = PACKAGE_NAME;
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.packageName = PACKAGE_NAME;
        packageInfo.applicationInfo.flags = flags;
        packageInfo.requestedPermissions = requestedPermissions;
        return packageInfo;
    }
}
