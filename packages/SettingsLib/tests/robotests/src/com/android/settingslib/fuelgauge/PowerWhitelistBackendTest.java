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
import static org.mockito.Mockito.verify;

import android.os.IDeviceIdleController;

import com.android.settingslib.SettingsLibRobolectricTestRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(SettingsLibRobolectricTestRunner.class)
public class PowerWhitelistBackendTest {

    private static final String PACKAGE_ONE = "com.example.packageone";
    private static final String PACKAGE_TWO = "com.example.packagetwo";

    private PowerWhitelistBackend mPowerWhitelistBackend;
    @Mock
    private IDeviceIdleController mDeviceIdleService;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        doReturn(new String[] {}).when(mDeviceIdleService).getFullPowerWhitelist();
        doReturn(new String[] {}).when(mDeviceIdleService).getSystemPowerWhitelist();
        doReturn(new String[] {}).when(mDeviceIdleService).getSystemPowerWhitelistExceptIdle();
        doNothing().when(mDeviceIdleService).addPowerSaveWhitelistApp(anyString());
        doNothing().when(mDeviceIdleService).removePowerSaveWhitelistApp(anyString());
        mPowerWhitelistBackend = new PowerWhitelistBackend(mDeviceIdleService);
    }

    @Test
    public void testIsWhitelisted() throws Exception {
        doReturn(new String[] {PACKAGE_ONE}).when(mDeviceIdleService).getFullPowerWhitelist();
        mPowerWhitelistBackend.refreshList();

        assertThat(mPowerWhitelistBackend.isWhitelisted(PACKAGE_ONE)).isTrue();
        assertThat(mPowerWhitelistBackend.isWhitelisted(PACKAGE_TWO)).isFalse();

        mPowerWhitelistBackend.addApp(PACKAGE_TWO);

        verify(mDeviceIdleService, atLeastOnce()).addPowerSaveWhitelistApp(PACKAGE_TWO);
        assertThat(mPowerWhitelistBackend.isWhitelisted(PACKAGE_ONE)).isTrue();
        assertThat(mPowerWhitelistBackend.isWhitelisted(PACKAGE_TWO)).isTrue();

        mPowerWhitelistBackend.removeApp(PACKAGE_TWO);

        verify(mDeviceIdleService, atLeastOnce()).removePowerSaveWhitelistApp(PACKAGE_TWO);
        assertThat(mPowerWhitelistBackend.isWhitelisted(PACKAGE_ONE)).isTrue();
        assertThat(mPowerWhitelistBackend.isWhitelisted(PACKAGE_TWO)).isFalse();

        mPowerWhitelistBackend.removeApp(PACKAGE_ONE);

        verify(mDeviceIdleService, atLeastOnce()).removePowerSaveWhitelistApp(PACKAGE_ONE);
        assertThat(mPowerWhitelistBackend.isWhitelisted(PACKAGE_ONE)).isFalse();
        assertThat(mPowerWhitelistBackend.isWhitelisted(PACKAGE_TWO)).isFalse();
    }

    @Test
    public void testIsSystemWhitelisted() throws Exception {
        doReturn(new String[] {PACKAGE_ONE}).when(mDeviceIdleService).getSystemPowerWhitelist();
        mPowerWhitelistBackend.refreshList();

        assertThat(mPowerWhitelistBackend.isSysWhitelisted(PACKAGE_ONE)).isTrue();
        assertThat(mPowerWhitelistBackend.isSysWhitelisted(PACKAGE_TWO)).isFalse();
        assertThat(mPowerWhitelistBackend.isWhitelisted(PACKAGE_ONE)).isFalse();
    }

    @Test
    public void testIsSystemWhitelistedExceptIdle_onePackage() throws Exception {
        doReturn(new String[]{PACKAGE_TWO}).when(
                mDeviceIdleService).getSystemPowerWhitelistExceptIdle();
        mPowerWhitelistBackend.refreshList();

        assertThat(mPowerWhitelistBackend.isSysWhitelistedExceptIdle(PACKAGE_ONE)).isFalse();
        assertThat(mPowerWhitelistBackend.isSysWhitelistedExceptIdle(PACKAGE_TWO)).isTrue();
    }

    @Test
    public void testIsSystemWhitelistedExceptIdle_packageArray() throws Exception {
        doReturn(new String[]{PACKAGE_TWO}).when(
                mDeviceIdleService).getSystemPowerWhitelistExceptIdle();
        mPowerWhitelistBackend.refreshList();

        final String[] idlePackages = {PACKAGE_ONE, PACKAGE_TWO};
        final String[] normalPackages = {PACKAGE_ONE};

        assertThat(mPowerWhitelistBackend.isSysWhitelistedExceptIdle(normalPackages)).isFalse();
        assertThat(mPowerWhitelistBackend.isSysWhitelistedExceptIdle(idlePackages)).isTrue();
    }
}
