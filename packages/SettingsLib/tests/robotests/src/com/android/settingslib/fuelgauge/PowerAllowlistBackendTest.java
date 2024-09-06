/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settingslib.fuelgauge;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.IDeviceIdleController;

import com.android.settingslib.testutils.shadow.ShadowDefaultDialerManager;
import com.android.settingslib.testutils.shadow.ShadowSmsApplication;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPackageManager;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = {ShadowDefaultDialerManager.class, ShadowSmsApplication.class})
public class PowerAllowlistBackendTest {

    private static final String PACKAGE_ONE = "com.example.packageone";
    private static final String PACKAGE_TWO = "com.example.packagetwo";
    private static final int UID = 12345;

    @Mock
    private IDeviceIdleController mDeviceIdleService;
    @Mock
    private DevicePolicyManager mDevicePolicyManager;
    @Mock
    private AppOpsManager mAppOpsManager;
    private PowerAllowlistBackend mPowerAllowlistBackend;
    private ShadowPackageManager mPackageManager;
    private Context mContext;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext = spy(RuntimeEnvironment.application);
        doReturn(mContext).when(mContext).getApplicationContext();
        doReturn(new String[] {}).when(mDeviceIdleService).getFullPowerWhitelist();
        doReturn(new String[] {}).when(mDeviceIdleService).getSystemPowerWhitelist();
        doReturn(new String[] {}).when(mDeviceIdleService).getSystemPowerWhitelistExceptIdle();
        doNothing().when(mDeviceIdleService).addPowerSaveWhitelistApp(anyString());
        doNothing().when(mDeviceIdleService).removePowerSaveWhitelistApp(anyString());
        mPackageManager = Shadow.extract(mContext.getPackageManager());
        mPackageManager.setSystemFeature(PackageManager.FEATURE_TELEPHONY, true);
        doReturn(mDevicePolicyManager).when(mContext).getSystemService(DevicePolicyManager.class);
        doReturn(mAppOpsManager).when(mContext).getSystemService(AppOpsManager.class);
        doReturn(AppOpsManager.MODE_DEFAULT).when(mAppOpsManager).checkOpNoThrow(
                AppOpsManager.OP_SYSTEM_EXEMPT_FROM_POWER_RESTRICTIONS, UID, PACKAGE_ONE);
        doReturn(AppOpsManager.MODE_DEFAULT).when(mAppOpsManager).checkOpNoThrow(
                AppOpsManager.OP_SYSTEM_EXEMPT_FROM_POWER_RESTRICTIONS, UID, PACKAGE_TWO);

        mPowerAllowlistBackend = new PowerAllowlistBackend(mContext, mDeviceIdleService);
    }

    @Test
    public void testIsAllowlisted() throws Exception {
        doReturn(new String[] {PACKAGE_ONE}).when(mDeviceIdleService).getFullPowerWhitelist();
        mPowerAllowlistBackend.refreshList();

        assertThat(mPowerAllowlistBackend.isAllowlisted(PACKAGE_ONE, UID)).isTrue();
        assertThat(mPowerAllowlistBackend.isAllowlisted(PACKAGE_TWO, UID)).isFalse();
        assertThat(mPowerAllowlistBackend.isAllowlisted(new String[] {PACKAGE_ONE}, UID)).isTrue();
        assertThat(mPowerAllowlistBackend.isAllowlisted(new String[] {PACKAGE_TWO}, UID)).isFalse();

        mPowerAllowlistBackend.addApp(PACKAGE_TWO, UID);

        verify(mDeviceIdleService, atLeastOnce()).addPowerSaveWhitelistApp(PACKAGE_TWO);
        assertThat(mPowerAllowlistBackend.isAllowlisted(PACKAGE_ONE, UID)).isTrue();
        assertThat(mPowerAllowlistBackend.isAllowlisted(PACKAGE_TWO, UID)).isTrue();
        assertThat(mPowerAllowlistBackend.isAllowlisted(
                new String[] {PACKAGE_ONE, PACKAGE_TWO}, UID)).isTrue();

        mPowerAllowlistBackend.removeApp(PACKAGE_TWO, UID);

        verify(mDeviceIdleService, atLeastOnce()).removePowerSaveWhitelistApp(PACKAGE_TWO);
        assertThat(mPowerAllowlistBackend.isAllowlisted(PACKAGE_ONE, UID)).isTrue();
        assertThat(mPowerAllowlistBackend.isAllowlisted(PACKAGE_TWO, UID)).isFalse();
        assertThat(mPowerAllowlistBackend.isAllowlisted(new String[] {PACKAGE_ONE}, UID)).isTrue();
        assertThat(mPowerAllowlistBackend.isAllowlisted(new String[] {PACKAGE_TWO}, UID)).isFalse();

        mPowerAllowlistBackend.removeApp(PACKAGE_ONE, UID);

        verify(mDeviceIdleService, atLeastOnce()).removePowerSaveWhitelistApp(PACKAGE_ONE);
        assertThat(mPowerAllowlistBackend.isAllowlisted(PACKAGE_ONE, UID)).isFalse();
        assertThat(mPowerAllowlistBackend.isAllowlisted(PACKAGE_TWO, UID)).isFalse();
        assertThat(mPowerAllowlistBackend.isAllowlisted(
                new String[] {PACKAGE_ONE, PACKAGE_TWO}, UID)).isFalse();
    }

    @Test
    public void isAllowlisted_shouldAllowlistDefaultSms() {
        final String testSms = "com.android.test.defaultsms";
        ShadowSmsApplication.setDefaultSmsApplication(new ComponentName(testSms, "receiver"));

        mPowerAllowlistBackend.refreshList();

        assertThat(mPowerAllowlistBackend.isAllowlisted(testSms, UID)).isTrue();
        assertThat(mPowerAllowlistBackend.isDefaultActiveApp(testSms, UID)).isTrue();
    }

    @Test
    public void isAllowlisted_shouldAllowlistDefaultDialer() {
        final String testDialer = "com.android.test.defaultdialer";
        ShadowDefaultDialerManager.setDefaultDialerApplication(testDialer);

        mPowerAllowlistBackend.refreshList();

        assertThat(mPowerAllowlistBackend.isAllowlisted(testDialer, UID)).isTrue();
        assertThat(mPowerAllowlistBackend.isDefaultActiveApp(testDialer, UID)).isTrue();
    }

    @Test
    public void isAllowlisted_shouldAllowlistActiveDeviceAdminApp() {
        doReturn(true).when(mDevicePolicyManager).packageHasActiveAdmins(PACKAGE_ONE);

        assertThat(mPowerAllowlistBackend.isAllowlisted(PACKAGE_ONE, UID)).isTrue();
        assertThat(mPowerAllowlistBackend.isDefaultActiveApp(PACKAGE_ONE, UID)).isTrue();
    }

    @Test
    public void isAllowlisted_shouldAllowlistAppWithSystemExemptAppOp() {
        doReturn(AppOpsManager.MODE_ALLOWED).when(mAppOpsManager).checkOpNoThrow(
                AppOpsManager.OP_SYSTEM_EXEMPT_FROM_POWER_RESTRICTIONS, UID, PACKAGE_ONE);

        assertThat(mPowerAllowlistBackend.isAllowlisted(PACKAGE_ONE, UID)).isTrue();
        assertThat(mPowerAllowlistBackend.isDefaultActiveApp(PACKAGE_ONE, UID)).isTrue();
    }

    @Test
    public void testIsSystemAllowlisted() throws Exception {
        doReturn(new String[] {PACKAGE_ONE}).when(mDeviceIdleService).getSystemPowerWhitelist();
        mPowerAllowlistBackend.refreshList();

        assertThat(mPowerAllowlistBackend.isSysAllowlisted(PACKAGE_ONE)).isTrue();
        assertThat(mPowerAllowlistBackend.isSysAllowlisted(PACKAGE_TWO)).isFalse();
        assertThat(mPowerAllowlistBackend.isAllowlisted(PACKAGE_ONE, UID)).isFalse();
    }

    @Test
    public void testIsPowerSaveWhitelistExceptIdleApp() throws Exception {
        doReturn(true).when(mDeviceIdleService)
                .isPowerSaveWhitelistExceptIdleApp(PACKAGE_ONE);

        mPowerAllowlistBackend.refreshList();

        assertThat(mPowerAllowlistBackend.isAllowlistedExceptIdle(PACKAGE_ONE)).isTrue();
    }
}
