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

package com.android.systemui.qrcodescanner.controller;

import static android.provider.Settings.Secure.LOCK_SCREEN_SHOW_QR_CODE_SCANNER;

import static com.android.systemui.qrcodescanner.controller.QRCodeScannerController.DEFAULT_QR_CODE_SCANNER_CHANGE;
import static com.android.systemui.qrcodescanner.controller.QRCodeScannerController.QR_CODE_SCANNER_PREFERENCE_CHANGE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.testing.TestableLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.DeviceConfigProxyFake;
import com.android.systemui.util.settings.FakeSettings;

import com.google.common.util.concurrent.MoreExecutors;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper
@SmallTest
public class QRCodeScannerControllerTest extends SysuiTestCase {
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private QRCodeScannerController.Callback mCallback;
    @Mock
    private UserTracker mUserTracker;

    private FakeSettings mSecureSettings;
    private QRCodeScannerController mController;
    private DeviceConfigProxyFake mProxyFake;

    private void setUpLocal(String deviceConfigActivity, String defaultActivity,
            boolean validateActivity, boolean enableSetting, boolean enableOnLockScreen) {
        MockitoAnnotations.initMocks(this);
        int enableSettingInt = enableSetting ? 1 : 0;

        mSecureSettings = new FakeSettings();
        mSecureSettings.putInt(LOCK_SCREEN_SHOW_QR_CODE_SCANNER, enableSettingInt);

        mContext.setMockPackageManager(mPackageManager);

        List<ResolveInfo> resolveInfoList = new ArrayList();
        if (validateActivity) {
            resolveInfoList = new ArrayList(Collections.singleton(new ResolveInfo()));
        }
        when(mPackageManager.queryIntentActivities(any(Intent.class),
                any(Integer.class))).thenReturn(resolveInfoList);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)).thenReturn(true);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.string.config_defaultQrCodeComponent, defaultActivity);

        mContext.getOrCreateTestableResources().addOverride(
                android.R.bool.config_enableQrCodeScannerOnLockScreen, enableOnLockScreen);

        mProxyFake = new DeviceConfigProxyFake();
        mProxyFake.setProperty(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.DEFAULT_QR_CODE_SCANNER,
                deviceConfigActivity, false);

        when(mUserTracker.getUserId()).thenReturn(UserHandle.USER_CURRENT);

        mController = new QRCodeScannerController(
                mContext,
                MoreExecutors.directExecutor(),
                mSecureSettings, mProxyFake, mUserTracker);
        mController.addCallback(mCallback);
        mController.registerQRCodeScannerChangeObservers(DEFAULT_QR_CODE_SCANNER_CHANGE,
                QR_CODE_SCANNER_PREFERENCE_CHANGE);
    }

    private String getSettingsQRCodeDefaultComponent() {
        return mSecureSettings.getStringForUser(Settings.Secure.SHOW_QR_CODE_SCANNER_SETTING,
                UserHandle.USER_CURRENT);
    }

    private void verifyActivityDetails(String componentName) {
        if (componentName != null) {
            assertThat(mController.getIntent()).isNotNull();
            assertThat(componentName).isEqualTo(getSettingsQRCodeDefaultComponent());
        } else {
            assertThat(mController.getIntent()).isNull();
            assertThat(getSettingsQRCodeDefaultComponent()).isNull();
        }
    }

    @Test
    public void qrCodeScannerInit_withoutDefaultValue() {
        setUpLocal(/* deviceConfigActivity */ null, /* defaultActivity */
                "", /* validateActivity */ true, /* enableSetting */ true,
                /* enableOnLockScreen */ true);
        verifyActivityDetails(null);
        assertThat(mController.isEnabledForLockScreenButton()).isFalse();
        assertThat(mController.isAbleToLaunchScannerActivity()).isFalse();
    }

    @Test
    public void qrCodeScannerInit_withIncorrectDefaultValue() {
        setUpLocal(/* deviceConfigActivity */ null, /* defaultActivity */
                "abc/.def", /* validateActivity */ false, /* enableSetting */ true,
                /* enableOnLockScreen */ true);
        verifyActivityDetails(null);
        assertThat(mController.isEnabledForLockScreenButton()).isFalse();
    }

    @Test
    public void qrCodeScannerInit_withCorrectDefaultValue() {
        setUpLocal(/* deviceConfigActivity */ null, /* defaultActivity */
                "abc/.def", /* validateActivity */ true, /* enableSetting */true,
                /* enableOnLockScreen */ true);
        verifyActivityDetails("abc/.def");
        assertThat(mController.isEnabledForLockScreenButton()).isTrue();
        assertThat(mController.isAbleToLaunchScannerActivity()).isTrue();
    }

    @Test
    public void qrCodeScannerInit_withCorrectDeviceConfig() {
        setUpLocal(/* deviceConfigActivity */ "abc/.def", /* defaultActivity */
                "", /* validateActivity */ true, /* enableSetting */true,
                /* enableOnLockScreen */ true);
        verifyActivityDetails("abc/.def");
        assertThat(mController.isEnabledForLockScreenButton()).isTrue();
        assertThat(mController.isAbleToLaunchScannerActivity()).isTrue();
    }

    @Test
    public void qrCodeScannerInit_withCorrectDeviceConfig_withCorrectDefaultValue() {
        setUpLocal(/* deviceConfigActivity */ "abc/.def", /* defaultActivity */
                "xyz/.qrs", /* validateActivity */true, /* enableSetting */ true,
                /* enableOnLockScreen */ true);
        verifyActivityDetails("abc/.def");
        assertThat(mController.isEnabledForLockScreenButton()).isTrue();
        assertThat(mController.isAbleToLaunchScannerActivity()).isTrue();
    }

    @Test
    public void qrCodeScannerInit_withCorrectDeviceConfig_fullActivity() {
        setUpLocal(/* deviceConfigActivity */ "abc/abc.def", /* defaultActivity */
                "", /* validateActivity */  true, /* enableSetting */ true,
                /* enableOnLockScreen */ true);
        verifyActivityDetails("abc/abc.def");
        assertThat(mController.isEnabledForLockScreenButton()).isTrue();
        assertThat(mController.isAbleToLaunchScannerActivity()).isTrue();
    }

    @Test
    public void qrCodeScannerInit_withIncorrectDeviceConfig() {
        setUpLocal(/* deviceConfigActivity */ "def/.efg", /* defaultActivity */
                "", /* validateActivity */ false, /* enableSetting */ true,
                /* enableOnLockScreen */ true);
        verifyActivityDetails(null);
        assertThat(mController.isEnabledForLockScreenButton()).isFalse();
        assertThat(mController.isAbleToLaunchScannerActivity()).isFalse();
    }

    @Test
    public void verifyDeviceConfigChange_withDefaultActivity() {
        setUpLocal(/* deviceConfigActivity */ null, /* defaultActivity */
                "abc/.def", /* validateActivity */ true, /* enableSetting */true,
                /* enableOnLockScreen */ true);
        verifyActivityDetails("abc/.def");
        assertThat(mController.isEnabledForLockScreenButton()).isTrue();
        assertThat(mController.isAbleToLaunchScannerActivity()).isTrue();

        mProxyFake.setProperty(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.DEFAULT_QR_CODE_SCANNER,
                "def/.ijk", false);
        verifyActivityDetails("def/.ijk");
        assertThat(mController.isEnabledForLockScreenButton()).isTrue();
        assertThat(mController.isAbleToLaunchScannerActivity()).isTrue();

        mProxyFake.setProperty(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.DEFAULT_QR_CODE_SCANNER,
                null, false);
        verifyActivityDetails("abc/.def");
        assertThat(mController.isEnabledForLockScreenButton()).isTrue();
        assertThat(mController.isAbleToLaunchScannerActivity()).isTrue();

        // twice from this function
        verify(mCallback, times(2)).onQRCodeScannerActivityChanged();
    }

    @Test
    public void verifyDeviceConfigChange_withoutDefaultActivity() {
        setUpLocal(/* deviceConfigActivity */ null, /* defaultActivity */
                "", /* validateActivity */ true, /* enableSetting */ true,
                /* enableOnLockScreen */ true);
        verifyActivityDetails(null);
        assertThat(mController.isEnabledForLockScreenButton()).isFalse();
        assertThat(mController.isAbleToLaunchScannerActivity()).isFalse();

        mProxyFake.setProperty(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.DEFAULT_QR_CODE_SCANNER,
                "def/.ijk", false);

        verifyActivityDetails("def/.ijk");
        assertThat(mController.isEnabledForLockScreenButton()).isTrue();
        assertThat(mController.isAbleToLaunchScannerActivity()).isTrue();

        mProxyFake.setProperty(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.DEFAULT_QR_CODE_SCANNER,
                null, false);
        verifyActivityDetails(null);
        assertThat(mController.isEnabledForLockScreenButton()).isFalse();
        assertThat(mController.isAbleToLaunchScannerActivity()).isFalse();
        verify(mCallback, times(2)).onQRCodeScannerActivityChanged();
    }

    @Test
    public void verifyDeviceConfigChangeToSameValue() {
        setUpLocal(/* deviceConfigActivity */ null, /* defaultActivity */
                "", /* validateActivity */ true, /* enableSetting */true,
                /* enableOnLockScreen */ true);

        mProxyFake.setProperty(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.DEFAULT_QR_CODE_SCANNER,
                "def/.ijk", false);
        mProxyFake.setProperty(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.DEFAULT_QR_CODE_SCANNER,
                "def/.ijk", false);

        mProxyFake.setProperty(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.DEFAULT_QR_CODE_SCANNER,
                null, false);
        mProxyFake.setProperty(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.DEFAULT_QR_CODE_SCANNER,
                null, false);

        verify(mCallback, times(2)).onQRCodeScannerActivityChanged();
    }

    @Test
    public void verifyPreferenceChange() {
        setUpLocal(/* deviceConfigActivity */ null, /* defaultActivity */
                "abc/.def", /* validateActivity */ true, /* enableSetting */true,
                /* enableOnLockScreen */ true);
        mSecureSettings.putStringForUser(LOCK_SCREEN_SHOW_QR_CODE_SCANNER, "0",
                UserHandle.USER_CURRENT);
        mSecureSettings.putStringForUser(LOCK_SCREEN_SHOW_QR_CODE_SCANNER, "0",
                UserHandle.USER_CURRENT);

        mSecureSettings.putStringForUser(LOCK_SCREEN_SHOW_QR_CODE_SCANNER, "1",
                UserHandle.USER_CURRENT);
        mSecureSettings.putStringForUser(LOCK_SCREEN_SHOW_QR_CODE_SCANNER, "1",
                UserHandle.USER_CURRENT);
        // Once from setup + twice from this function
        verify(mCallback, times(3)).onQRCodeScannerPreferenceChanged();
    }

    @Test
    public void verifyPreferenceChangeToSameValue() {
        setUpLocal(/* deviceConfigActivity */ null, /* defaultActivity */
                "abc/.def", /* validateActivity */ true, /* enableSetting */true,
                /* enableOnLockScreen */ true);
        verifyActivityDetails("abc/.def");
        assertThat(mController.isEnabledForLockScreenButton()).isTrue();
        assertThat(mController.isAbleToLaunchScannerActivity()).isTrue();

        mSecureSettings.putStringForUser(LOCK_SCREEN_SHOW_QR_CODE_SCANNER, "0",
                UserHandle.USER_CURRENT);
        verifyActivityDetails("abc/.def");
        assertThat(mController.isEnabledForLockScreenButton()).isFalse();
        assertThat(mController.isAllowedOnLockScreen()).isTrue();
        assertThat(mController.isAbleToLaunchScannerActivity()).isTrue();

        mSecureSettings.putStringForUser(LOCK_SCREEN_SHOW_QR_CODE_SCANNER, "1",
                UserHandle.USER_CURRENT);
        verifyActivityDetails("abc/.def");
        assertThat(mController.isEnabledForLockScreenButton()).isTrue();
        assertThat(mController.isAbleToLaunchScannerActivity()).isTrue();
        // Once from setup + twice from this function
        verify(mCallback, times(3)).onQRCodeScannerPreferenceChanged();
    }

    @Test
    public void verifyUnregisterRegisterChangeObservers() {
        setUpLocal(/* deviceConfigActivity */ null, /* defaultActivity */
                "abc/.def", /* validateActivity */ true, /* enableSetting */true,
                /* enableOnLockScreen */ true);
        verifyActivityDetails("abc/.def");
        assertThat(mController.isEnabledForLockScreenButton()).isTrue();
        assertThat(mController.isAbleToLaunchScannerActivity()).isTrue();

        // even if unregistered, intent and activity details are retained
        mController.unregisterQRCodeScannerChangeObservers(DEFAULT_QR_CODE_SCANNER_CHANGE,
                QR_CODE_SCANNER_PREFERENCE_CHANGE);
        assertThat(mController.isAbleToLaunchScannerActivity()).isTrue();
        assertThat(mController.isAllowedOnLockScreen()).isTrue();

        // Unregister once again and make sure it affects the next register event
        mController.unregisterQRCodeScannerChangeObservers(DEFAULT_QR_CODE_SCANNER_CHANGE,
                QR_CODE_SCANNER_PREFERENCE_CHANGE);
        mController.registerQRCodeScannerChangeObservers(DEFAULT_QR_CODE_SCANNER_CHANGE,
                QR_CODE_SCANNER_PREFERENCE_CHANGE);
        verifyActivityDetails("abc/.def");
        assertThat(mController.isEnabledForLockScreenButton()).isTrue();
        assertThat(mController.isAbleToLaunchScannerActivity()).isTrue();
    }

    @Test
    public void verifyDisableLockscreenButton() {
        setUpLocal(/* deviceConfigActivity */ null, /* defaultActivity */
                "abc/.def", /* validateActivity */ true, /* enableSetting */true,
                /* enableOnLockScreen */ false);
        assertThat(mController.getIntent()).isNotNull();
        assertThat(mController.isEnabledForLockScreenButton()).isFalse();
        assertThat(mController.isAbleToLaunchScannerActivity()).isTrue();
        assertThat(getSettingsQRCodeDefaultComponent()).isNull();
    }
}
