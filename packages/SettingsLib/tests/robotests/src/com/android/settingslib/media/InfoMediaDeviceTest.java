/*
 * Copyright 2019 The Android Open Source Project
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

package com.android.settingslib.media;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageStats;
import android.media.MediaRoute2Info;
import android.media.MediaRouter2Manager;

import com.android.settingslib.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowPackageManager;

@RunWith(RobolectricTestRunner.class)
public class InfoMediaDeviceTest {

    private static final String TEST_PACKAGE_NAME = "com.test.packagename";
    private static final String TEST_PACKAGE_NAME2 = "com.test.packagename2";
    private static final String TEST_ID = "test_id";
    private static final String TEST_NAME = "test_name";

    @Mock
    private MediaRouter2Manager mRouterManager;
    @Mock
    private MediaRoute2Info mRouteInfo;


    private Context mContext;
    private InfoMediaDevice mInfoMediaDevice;
    private ShadowPackageManager mShadowPackageManager;
    private ApplicationInfo mAppInfo;
    private PackageInfo mPackageInfo;
    private PackageStats mPackageStats;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = RuntimeEnvironment.application;
        mShadowPackageManager = Shadows.shadowOf(mContext.getPackageManager());
        mAppInfo = new ApplicationInfo();
        mAppInfo.flags = ApplicationInfo.FLAG_INSTALLED;
        mAppInfo.packageName = TEST_PACKAGE_NAME;
        mAppInfo.name = TEST_NAME;
        mPackageInfo = new PackageInfo();
        mPackageInfo.packageName = TEST_PACKAGE_NAME;
        mPackageInfo.applicationInfo = mAppInfo;
        mPackageStats = new PackageStats(TEST_PACKAGE_NAME);

        mInfoMediaDevice = new InfoMediaDevice(mContext, mRouterManager, mRouteInfo,
                TEST_PACKAGE_NAME);
    }

    @Test
    public void getName_shouldReturnName() {
        when(mRouteInfo.getName()).thenReturn(TEST_NAME);

        assertThat(mInfoMediaDevice.getName()).isEqualTo(TEST_NAME);
    }

    @Test
    public void getSummary_clientPackageNameIsNull_returnNull() {
        when(mRouteInfo.getClientPackageName()).thenReturn(null);

        assertThat(mInfoMediaDevice.getSummary()).isEqualTo(null);
    }

    @Test
    public void getSummary_clientPackageNameIsNotNull_returnActive() {
        when(mRouteInfo.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME);

        assertThat(mInfoMediaDevice.getSummary())
                .isEqualTo(mContext.getString(R.string.bluetooth_active_no_battery_level));
    }

    @Test
    public void getId_shouldReturnId() {
        when(mRouteInfo.getId()).thenReturn(TEST_ID);

        assertThat(mInfoMediaDevice.getId()).isEqualTo(TEST_ID);
    }

    @Test
    public void getClientPackageName_returnPackageName() {
        when(mRouteInfo.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME);

        assertThat(mInfoMediaDevice.getClientPackageName()).isEqualTo(TEST_PACKAGE_NAME);
    }

    @Test
    public void getClientAppLabel_matchedPackageName_returnLabel() {
        when(mRouteInfo.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME);

        assertThat(mInfoMediaDevice.getClientAppLabel()).isEqualTo(
                mContext.getResources().getString(R.string.unknown));

        mShadowPackageManager.addPackage(mPackageInfo, mPackageStats);

        assertThat(mInfoMediaDevice.getClientAppLabel()).isEqualTo(TEST_NAME);
    }

    @Test
    public void getClientAppLabel_noMatchedPackageName_returnDefault() {
        mShadowPackageManager.addPackage(mPackageInfo, mPackageStats);
        when(mRouteInfo.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME2);

        assertThat(mInfoMediaDevice.getClientAppLabel()).isEqualTo(
                mContext.getResources().getString(R.string.unknown));
    }
}
