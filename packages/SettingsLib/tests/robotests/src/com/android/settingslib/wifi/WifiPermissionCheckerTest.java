/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.wifi;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.ACCESS_WIFI_STATE;
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.app.IActivityManager;
import android.content.pm.PackageManager;
import android.os.RemoteException;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class WifiPermissionCheckerTest {

    static final String LAUNCHED_PACKAGE = "TestPackage";

    @Rule
    public final MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Mock
    PackageManager mPackageManager;
    @Mock
    IActivityManager mActivityManager;
    @Mock
    Activity mActivity;

    WifiPermissionChecker mWifiPermissionChecker;

    @Before
    public void setUp() {
        when(mActivity.getPackageManager()).thenReturn(mPackageManager);
        fakeGetLaunchedFromPackage(LAUNCHED_PACKAGE);

        mWifiPermissionChecker = new WifiPermissionChecker(mActivity, mActivityManager);
    }

    @Test
    public void getLaunchedPackage_returnLaunchedFromPackage() {
        assertThat(mWifiPermissionChecker.getLaunchedPackage()).isEqualTo(LAUNCHED_PACKAGE);
    }

    @Test
    public void canAccessWifiState_noPermission_returnFalse() {
        when(mPackageManager.checkPermission(ACCESS_WIFI_STATE, LAUNCHED_PACKAGE))
                .thenReturn(PERMISSION_DENIED);

        assertThat(mWifiPermissionChecker.canAccessWifiState()).isFalse();
    }

    @Test
    public void canAccessWifiState_hasPermission_returnTrue() {
        when(mPackageManager.checkPermission(ACCESS_WIFI_STATE, LAUNCHED_PACKAGE))
                .thenReturn(PERMISSION_GRANTED);

        assertThat(mWifiPermissionChecker.canAccessWifiState()).isTrue();
    }

    @Test
    public void canAccessFineLocation_noPermission_returnFalse() {
        when(mPackageManager.checkPermission(ACCESS_FINE_LOCATION, LAUNCHED_PACKAGE))
                .thenReturn(PERMISSION_DENIED);

        assertThat(mWifiPermissionChecker.canAccessFineLocation()).isFalse();
    }

    @Test
    public void canAccessFineLocation_hasPermission_returnTrue() {
        when(mPackageManager.checkPermission(ACCESS_FINE_LOCATION, LAUNCHED_PACKAGE))
                .thenReturn(PERMISSION_GRANTED);

        assertThat(mWifiPermissionChecker.canAccessFineLocation()).isTrue();
    }

    void fakeGetLaunchedFromPackage(String packageName) {
        try {
            when(mActivityManager.getLaunchedFromPackage(any())).thenReturn(packageName);
        } catch (RemoteException e) {
            // Do nothing
        }
    }
}
