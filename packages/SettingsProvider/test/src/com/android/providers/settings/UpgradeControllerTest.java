/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.providers.settings;

import static android.provider.Settings.System.MIN_REFRESH_RATE;
import static android.provider.Settings.System.PEAK_REFRESH_RATE;

import static com.android.providers.settings.SettingsProvider.SETTINGS_TYPE_GLOBAL;
import static com.android.providers.settings.SettingsProvider.SETTINGS_TYPE_SECURE;
import static com.android.providers.settings.SettingsProvider.SETTINGS_TYPE_SYSTEM;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.IPackageManager;
import android.os.Looper;
import android.os.SystemConfigManager;
import android.os.UserHandle;
import android.os.UserManager;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class UpgradeControllerTest {
    private static final int USER_ID = UserHandle.USER_SYSTEM;
    private static final float HIGHEST_REFRESH_RATE = 130f;

    private final Context mContext =
            spy(new ContextWrapper(ApplicationProvider.getApplicationContext()));
    private final SettingsProvider.SettingsRegistry.UpgradeController.Injector mInjector =
            new SettingsProvider.SettingsRegistry.UpgradeController.Injector() {
                @Override
                float findHighestRefreshRateForDefaultDisplay(Context context) {
                    return HIGHEST_REFRESH_RATE;
                }
            };
    private final SettingsProvider mSettingsProvider = new SettingsProvider() {
        @Override
        public boolean onCreate() {
            return true;
        }
    };
    private final SettingsProvider.SettingsRegistry mSettingsRegistry =
            mSettingsProvider.new SettingsRegistry(Looper.getMainLooper());
    private final SettingsProvider.SettingsRegistry.UpgradeController mUpgradeController =
            mSettingsRegistry.new UpgradeController(mInjector, USER_ID);

    @Mock
    private UserManager mUserManager;

    @Mock
    private IPackageManager mPackageManager;

    @Mock
    private SystemConfigManager mSysConfigManager;

    @Mock
    private SettingsState mSystemSettings;

    @Mock
    private SettingsState mSecureSettings;

    @Mock
    private SettingsState mGlobalSettings;

    @Mock
    private SettingsState.Setting mMockSetting;

    @Mock
    private SettingsState.Setting mPeakRefreshRateSetting;

    @Mock
    private SettingsState.Setting mMinRefreshRateSetting;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mSettingsProvider.attachInfoForTesting(mContext, /* info= */ null);
        mSettingsProvider.injectServices(mUserManager, mPackageManager, mSysConfigManager);
        when(mSystemSettings.getSettingLocked(any())).thenReturn(mMockSetting);
        when(mSecureSettings.getSettingLocked(any())).thenReturn(mMockSetting);
        when(mGlobalSettings.getSettingLocked(any())).thenReturn(mMockSetting);
        when(mMockSetting.isNull()).thenReturn(true);
        when(mMockSetting.getValue()).thenReturn("0");

        when(mSystemSettings.getSettingLocked(PEAK_REFRESH_RATE))
                .thenReturn(mPeakRefreshRateSetting);
        when(mSystemSettings.getSettingLocked(MIN_REFRESH_RATE))
                .thenReturn(mMinRefreshRateSetting);

        mSettingsRegistry.injectSettings(mSystemSettings, SETTINGS_TYPE_SYSTEM, USER_ID);
        mSettingsRegistry.injectSettings(mSecureSettings, SETTINGS_TYPE_SECURE, USER_ID);
        mSettingsRegistry.injectSettings(mGlobalSettings, SETTINGS_TYPE_GLOBAL, USER_ID);

        // Lowest version so that all upgrades are run
        when(mSecureSettings.getVersionLocked()).thenReturn(118);
    }

    @Test
    public void testUpgrade_refreshRateSettings_defaultValues() {
        when(mPeakRefreshRateSetting.isNull()).thenReturn(true);
        when(mMinRefreshRateSetting.isNull()).thenReturn(true);

        mUpgradeController.upgradeIfNeededLocked();

        // Should remain unchanged
        verify(mSystemSettings, never()).insertSettingLocked(eq(PEAK_REFRESH_RATE),
                /* value= */ any(), /* tag= */ any(), /* makeDefault= */ anyBoolean(),
                /* packageName= */ any());
        verify(mSystemSettings, never()).insertSettingLocked(eq(MIN_REFRESH_RATE),
                /* value= */ any(), /* tag= */ any(), /* makeDefault= */ anyBoolean(),
                /* packageName= */ any());
    }

    @Test
    public void testUpgrade_refreshRateSettings_enabled() {
        when(mPeakRefreshRateSetting.isNull()).thenReturn(false);
        when(mMinRefreshRateSetting.isNull()).thenReturn(false);
        when(mPeakRefreshRateSetting.getValue()).thenReturn(String.valueOf(HIGHEST_REFRESH_RATE));
        when(mMinRefreshRateSetting.getValue()).thenReturn(String.valueOf(HIGHEST_REFRESH_RATE));

        mUpgradeController.upgradeIfNeededLocked();

        // Highest refresh rate gets converted to infinity
        verify(mSystemSettings).insertSettingLocked(eq(PEAK_REFRESH_RATE),
                eq(String.valueOf(Float.POSITIVE_INFINITY)), /* tag= */ any(),
                /* makeDefault= */ anyBoolean(), /* packageName= */ any());
        verify(mSystemSettings).insertSettingLocked(eq(MIN_REFRESH_RATE),
                eq(String.valueOf(Float.POSITIVE_INFINITY)), /* tag= */ any(),
                /* makeDefault= */ anyBoolean(), /* packageName= */ any());
    }

    @Test
    public void testUpgrade_refreshRateSettings_disabled() {
        when(mPeakRefreshRateSetting.isNull()).thenReturn(false);
        when(mMinRefreshRateSetting.isNull()).thenReturn(false);
        when(mPeakRefreshRateSetting.getValue()).thenReturn("70f");
        when(mMinRefreshRateSetting.getValue()).thenReturn("70f");

        mUpgradeController.upgradeIfNeededLocked();

        // Should remain unchanged
        verify(mSystemSettings, never()).insertSettingLocked(eq(PEAK_REFRESH_RATE),
                /* value= */ any(), /* tag= */ any(), /* makeDefault= */ anyBoolean(),
                /* packageName= */ any());
        verify(mSystemSettings, never()).insertSettingLocked(eq(MIN_REFRESH_RATE),
                /* value= */ any(), /* tag= */ any(), /* makeDefault= */ anyBoolean(),
                /* packageName= */ any());
    }
}
