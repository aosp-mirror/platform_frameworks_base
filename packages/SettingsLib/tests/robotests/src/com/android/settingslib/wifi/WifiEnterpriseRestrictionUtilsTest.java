/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.os.UserManager.DISALLOW_CHANGE_WIFI_STATE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.UserManager;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
public class WifiEnterpriseRestrictionUtilsTest {

    private Context mContext;
    @Mock
    private UserManager mUserManager;
    @Mock
    private Bundle mBundle;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = spy(ApplicationProvider.getApplicationContext());
        when(mContext.getSystemService(UserManager.class)).thenReturn(mUserManager);
        when(mUserManager.getUserRestrictions()).thenReturn(mBundle);
        ReflectionHelpers.setStaticField(
                Build.VERSION.class, "SDK_INT", Build.VERSION_CODES.TIRAMISU);
    }

    @Test
    public void isWifiTetheringAllowed_setSDKForS_shouldReturnTrue() {
        ReflectionHelpers.setStaticField(Build.VERSION.class, "SDK_INT", Build.VERSION_CODES.S);
        when(mBundle.getBoolean(UserManager.DISALLOW_WIFI_TETHERING)).thenReturn(true);

        assertThat(WifiEnterpriseRestrictionUtils.isWifiTetheringAllowed(mContext)).isTrue();
    }

    @Test
    public void isWifiTetheringAllowed_setSDKForTAndDisallowForRestriction_shouldReturnFalse() {
        ReflectionHelpers.setStaticField(
                Build.VERSION.class, "SDK_INT", Build.VERSION_CODES.TIRAMISU);
        when(mBundle.getBoolean(UserManager.DISALLOW_WIFI_TETHERING)).thenReturn(true);

        assertThat(WifiEnterpriseRestrictionUtils.isWifiTetheringAllowed(mContext)).isFalse();
    }

    @Test
    public void isWifiTetheringAllowed_setSDKForTAndAllowForRestriction_shouldReturnTrue() {
        ReflectionHelpers.setStaticField(
            Build.VERSION.class, "SDK_INT", Build.VERSION_CODES.TIRAMISU);
        when(mBundle.getBoolean(UserManager.DISALLOW_WIFI_TETHERING)).thenReturn(false);

        assertThat(WifiEnterpriseRestrictionUtils.isWifiTetheringAllowed(mContext)).isTrue();
    }

    @Test
    public void isWifiDirectAllowed_setSDKForS_shouldReturnTrue() {
        ReflectionHelpers.setStaticField(Build.VERSION.class, "SDK_INT", Build.VERSION_CODES.S);
        when(mBundle.getBoolean(UserManager.DISALLOW_WIFI_DIRECT)).thenReturn(true);

        assertThat(WifiEnterpriseRestrictionUtils.isWifiDirectAllowed(mContext)).isTrue();
    }

    @Test
    public void isWifiDirectAllowed_setSDKForTAndDisallowForRestriction_shouldReturnFalse() {
        ReflectionHelpers.setStaticField(
            Build.VERSION.class, "SDK_INT", Build.VERSION_CODES.TIRAMISU);
        when(mBundle.getBoolean(UserManager.DISALLOW_WIFI_DIRECT)).thenReturn(true);

        assertThat(WifiEnterpriseRestrictionUtils.isWifiDirectAllowed(mContext)).isFalse();
    }

    @Test
    public void isWifiDirectAllowed_setSDKForTAndAllowForRestriction_shouldReturnTrue() {
        ReflectionHelpers.setStaticField(
            Build.VERSION.class, "SDK_INT", Build.VERSION_CODES.TIRAMISU);
        when(mBundle.getBoolean(UserManager.DISALLOW_WIFI_DIRECT)).thenReturn(false);

        assertThat(WifiEnterpriseRestrictionUtils.isWifiDirectAllowed(mContext)).isTrue();
    }

    @Test
    public void isAddWifiConfigAllowed_setSDKForS_shouldReturnTrue() {
        ReflectionHelpers.setStaticField(Build.VERSION.class, "SDK_INT", Build.VERSION_CODES.S);
        when(mBundle.getBoolean(UserManager.DISALLOW_ADD_WIFI_CONFIG)).thenReturn(true);

        assertThat(WifiEnterpriseRestrictionUtils.isAddWifiConfigAllowed(mContext)).isTrue();
    }

    @Test
    public void isAddWifiConfigAllowed_setSDKForTAndDisallowForRestriction_shouldReturnFalse() {
        ReflectionHelpers.setStaticField(
            Build.VERSION.class, "SDK_INT", Build.VERSION_CODES.TIRAMISU);
        when(mBundle.getBoolean(UserManager.DISALLOW_ADD_WIFI_CONFIG)).thenReturn(true);

        assertThat(WifiEnterpriseRestrictionUtils.isAddWifiConfigAllowed(mContext)).isFalse();
    }

    @Test
    public void isAddWifiConfigAllowed_setSDKForTAndAllowForRestriction_shouldReturnTrue() {
        ReflectionHelpers.setStaticField(
            Build.VERSION.class, "SDK_INT", Build.VERSION_CODES.TIRAMISU);
        when(mBundle.getBoolean(UserManager.DISALLOW_ADD_WIFI_CONFIG)).thenReturn(false);

        assertThat(WifiEnterpriseRestrictionUtils.isAddWifiConfigAllowed(mContext)).isTrue();
    }

    @Test
    public void isChangeWifiStateAllowed_hasDisallowRestriction_shouldReturnFalse() {
        when(mUserManager.hasUserRestriction(DISALLOW_CHANGE_WIFI_STATE)).thenReturn(true);

        assertThat(WifiEnterpriseRestrictionUtils.isChangeWifiStateAllowed(mContext)).isFalse();
    }

    @Test
    public void isChangeWifiStateAllowed_hasNoDisallowRestriction_shouldReturnTrue() {
        when(mUserManager.hasUserRestriction(DISALLOW_CHANGE_WIFI_STATE)).thenReturn(false);

        assertThat(WifiEnterpriseRestrictionUtils.isChangeWifiStateAllowed(mContext)).isTrue();
    }

    @Test
    public void hasUserRestrictionFromT_setSDKForS_shouldReturnTrue() {
        ReflectionHelpers.setStaticField(Build.VERSION.class, "SDK_INT", Build.VERSION_CODES.S);

        assertThat(WifiEnterpriseRestrictionUtils.hasUserRestrictionFromT(mContext, "key"))
                .isFalse();
    }

    @Test
    public void hasUserRestrictionFromT_setSDKForT_shouldReturnHasUserRestriction() {
        ReflectionHelpers.setStaticField(
                Build.VERSION.class, "SDK_INT", Build.VERSION_CODES.TIRAMISU);
        when(mUserManager.hasUserRestriction(anyString())).thenReturn(false);

        assertThat(WifiEnterpriseRestrictionUtils.hasUserRestrictionFromT(mContext, "key"))
                .isFalse();

        when(mUserManager.hasUserRestriction(anyString())).thenReturn(true);

        assertThat(WifiEnterpriseRestrictionUtils.hasUserRestrictionFromT(mContext, "key"))
                .isTrue();
    }
}
