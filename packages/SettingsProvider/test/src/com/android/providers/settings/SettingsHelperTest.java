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

package com.android.providers.settings;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertEquals;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.Resources;
import android.media.AudioManager;
import android.net.Uri;
import android.os.LocaleList;
import android.provider.Settings;
import android.telephony.TelephonyManager;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for the SettingsHelperTest
 */
@RunWith(AndroidJUnit4.class)
public class SettingsHelperTest {
    private static final String SETTING_KEY = "setting_key";
    private static final String SETTING_VALUE = "setting_value";
    private static final String SETTING_REAL_VALUE = "setting_real_value";

    private SettingsHelper mSettingsHelper;

    @Mock private Context mContext;
    @Mock private Resources mResources;
    @Mock private ContentResolver mContentResolver;
    @Mock private AudioManager mAudioManager;
    @Mock private TelephonyManager mTelephonyManager;

    @Before
    public void setUp() {
        clearLongPressPowerValues();
        MockitoAnnotations.initMocks(this);
        when(mContext.getSystemService(eq(Context.AUDIO_SERVICE))).thenReturn(mAudioManager);
        when(mContext.getSystemService(eq(Context.TELEPHONY_SERVICE))).thenReturn(
                mTelephonyManager);
        when(mContext.getResources()).thenReturn(mResources);
        when(mContext.getApplicationContext()).thenReturn(mContext);
        when(mContext.getContentResolver()).thenReturn(getContentResolver());

        mSettingsHelper = spy(new SettingsHelper(mContext));
    }

    @After
    public void tearDown() {
        clearLongPressPowerValues();
    }

    @Test
    public void testOnBackupValue_settingReplaced_returnsRealValue() {
        when(mSettingsHelper.isReplacedSystemSetting(eq(SETTING_KEY))).thenReturn(true);
        doReturn(SETTING_REAL_VALUE).when(mSettingsHelper).getRealValueForSystemSetting(
                eq(SETTING_KEY));

        assertEquals(SETTING_REAL_VALUE, mSettingsHelper.onBackupValue(SETTING_KEY, SETTING_VALUE));
    }

    @Test
    public void testGetRealValue_settingNotReplaced_returnsSameValue() {
        when(mSettingsHelper.isReplacedSystemSetting(eq(SETTING_KEY))).thenReturn(false);

        assertEquals(SETTING_VALUE, mSettingsHelper.onBackupValue(SETTING_KEY, SETTING_VALUE));
    }

    @Test
    public void testRestoreValue_settingReplaced_doesNotRestore() {
        when(mSettingsHelper.isReplacedSystemSetting(eq(SETTING_KEY))).thenReturn(true);
        mSettingsHelper.restoreValue(mContext, mContentResolver, new ContentValues(), Uri.EMPTY,
                SETTING_KEY, SETTING_VALUE, /* restoredFromSdkInt */ 0);

        verifyZeroInteractions(mContentResolver);
    }

    @Test
    public void testRestoreValue_lppForAssistantEnabled_updatesValue() {
        ContentResolver cr =
                InstrumentationRegistry.getInstrumentation().getTargetContext()
                        .getContentResolver();
        when(mResources.getBoolean(
                R.bool.config_longPressOnPowerForAssistantSettingAvailable)).thenReturn(
                true);

        mSettingsHelper.restoreValue(mContext, cr, new ContentValues(), Uri.EMPTY,
                Settings.Global.POWER_BUTTON_LONG_PRESS, "5", 0);

        assertThat(
                Settings.Global.getInt(cr, Settings.Global.POWER_BUTTON_LONG_PRESS, -1))
                    .isEqualTo(5);
        assertThat(Settings.Global.getInt(cr, Settings.Global.KEY_CHORD_POWER_VOLUME_UP,
                -1)).isEqualTo(2);
    }

    @Test
    public void testRestoreValue_lppForAssistantNotEnabled_updatesValueToDefaultConfig() {
        ContentResolver cr =
                InstrumentationRegistry.getInstrumentation().getTargetContext()
                        .getContentResolver();
        when(mResources.getBoolean(
                R.bool.config_longPressOnPowerForAssistantSettingAvailable)).thenReturn(
                true);

        when(mResources.getInteger(
                R.integer.config_longPressOnPowerBehavior)).thenReturn(
                1);
        when(mResources.getInteger(
                R.integer.config_keyChordPowerVolumeUp)).thenReturn(
                1);

        mSettingsHelper.restoreValue(mContext, cr, new ContentValues(), Uri.EMPTY,
                Settings.Global.POWER_BUTTON_LONG_PRESS, "2", 0);

        assertThat(
                Settings.Global.getInt(cr, Settings.Global.POWER_BUTTON_LONG_PRESS, -1))
                .isEqualTo(1);
        assertThat(Settings.Global.getInt(cr, Settings.Global.KEY_CHORD_POWER_VOLUME_UP,
                -1)).isEqualTo(1);
    }

    @Test
    public void testRestoreValue_lppForAssistantNotEnabledDefaultConfig_updatesValue() {
        ContentResolver cr =
                InstrumentationRegistry.getInstrumentation().getTargetContext()
                        .getContentResolver();
        when(mResources.getBoolean(
                R.bool.config_longPressOnPowerForAssistantSettingAvailable)).thenReturn(
                true);

        when(mResources.getInteger(
                R.integer.config_longPressOnPowerBehavior)).thenReturn(
                5);
        when(mResources.getInteger(
                R.integer.config_keyChordPowerVolumeUp)).thenReturn(
                1);

        mSettingsHelper.restoreValue(mContext, cr, new ContentValues(), Uri.EMPTY,
                Settings.Global.POWER_BUTTON_LONG_PRESS, "2", 0);

        assertThat(
                Settings.Global.getInt(cr, Settings.Global.POWER_BUTTON_LONG_PRESS, -1))
                    .isEqualTo(1);
        assertThat(Settings.Global.getInt(cr, Settings.Global.KEY_CHORD_POWER_VOLUME_UP,
                -1)).isEqualTo(1);
    }

    @Test
    public void testRestoreValue_lppForAssistantNotAvailable_doesNotRestore() {
        ContentResolver cr =
                InstrumentationRegistry.getInstrumentation().getTargetContext()
                        .getContentResolver();
        when(mResources.getBoolean(
                R.bool.config_longPressOnPowerForAssistantSettingAvailable)).thenReturn(
                false);

        mSettingsHelper.restoreValue(mContext, cr, new ContentValues(), Uri.EMPTY,
                Settings.Global.POWER_BUTTON_LONG_PRESS, "5", 0);

        assertThat((Settings.Global.getInt(cr, Settings.Global.POWER_BUTTON_LONG_PRESS,
                -1))).isEqualTo(-1);
    }


    @Test
    public void testRestoreValue_lppForAssistantInvalid_doesNotRestore() {
        ContentResolver cr =
                InstrumentationRegistry.getInstrumentation().getTargetContext()
                        .getContentResolver();
        when(mResources.getBoolean(
                R.bool.config_longPressOnPowerForAssistantSettingAvailable)).thenReturn(
                false);

        mSettingsHelper.restoreValue(mContext, cr, new ContentValues(), Uri.EMPTY,
                Settings.Global.POWER_BUTTON_LONG_PRESS, "trees", 0);

        assertThat((Settings.Global.getInt(cr, Settings.Global.POWER_BUTTON_LONG_PRESS,
                -1))).isEqualTo(-1);
    }

    @Test
    public void testResolveLocales() throws Exception {
        // Empty string from backup server
        assertEquals(LocaleList.forLanguageTags("en-US"),
                SettingsHelper.resolveLocales(
                        LocaleList.forLanguageTags(""),  // restore
                        LocaleList.forLanguageTags("en-US"),  // current
                        new String[] { "en-US" }));  // supported

        // Same as current settings
        assertEquals(LocaleList.forLanguageTags("en-US"),
                SettingsHelper.resolveLocales(
                        LocaleList.forLanguageTags("en-US"),  // restore
                        LocaleList.forLanguageTags("en-US"),  // current
                        new String[] { "en-US" }));  // supported

        assertEquals(LocaleList.forLanguageTags("en-US,ja-JP"),
                SettingsHelper.resolveLocales(
                        LocaleList.forLanguageTags("en-US,ja-JP"),  // restore
                        LocaleList.forLanguageTags("en-US,ja-JP"),  // current
                        new String[] { "en-US", "ja-JP" }));  // supported

        // Current locale must be kept at the first place.
        assertEquals(LocaleList.forLanguageTags("ja-JP,en-US"),
                SettingsHelper.resolveLocales(
                        LocaleList.forLanguageTags("en-US"),  // restore
                        LocaleList.forLanguageTags("ja-JP"),  // current
                        new String[] { "en-US", "ja-JP" }));  // supported

        assertEquals(LocaleList.forLanguageTags("ja-JP,ko-KR,en-US"),
                SettingsHelper.resolveLocales(
                        LocaleList.forLanguageTags("en-US"),  // restore
                        LocaleList.forLanguageTags("ja-JP,ko-KR"),  // current
                        new String[] { "en-US", "ja-JP", "ko-KR" }));  // supported

        assertEquals(LocaleList.forLanguageTags("ja-JP,en-US,ko-KR"),
                SettingsHelper.resolveLocales(
                        LocaleList.forLanguageTags("en-US,ko-KR"),  // restore
                        LocaleList.forLanguageTags("ja-JP"),  // current
                        new String[] { "en-US", "ja-JP", "ko-KR" }));  // supported

        // Duplicated entries must be removed.
        assertEquals(LocaleList.forLanguageTags("ja-JP,ko-KR,en-US"),
                SettingsHelper.resolveLocales(
                        LocaleList.forLanguageTags("en-US,ko-KR"),  // restore
                        LocaleList.forLanguageTags("ja-JP,ko-KR"),  // current
                        new String[] { "en-US", "ja-JP", "ko-KR" }));  // supported

        // Drop unsupported locales.
        assertEquals(LocaleList.forLanguageTags("en-US"),
                SettingsHelper.resolveLocales(
                        LocaleList.forLanguageTags("en-US,zh-CN"),  // restore
                        LocaleList.forLanguageTags("en-US"),  // current
                        new String[] { "en-US" }));  // supported

        // Comparison happens on fully-expanded locale.
        assertEquals(LocaleList.forLanguageTags("en-US,sr-Latn-SR,sr-Cryl-SR"),
                SettingsHelper.resolveLocales(
                        LocaleList.forLanguageTags("sr-Cryl-SR"),  // restore
                        LocaleList.forLanguageTags("en-US,sr-Latn-SR"),  // current
                        new String[] { "en-US", "sr-Latn-SR", "sr-Cryl-SR" }));  // supported

        assertEquals(LocaleList.forLanguageTags("en-US"),
                SettingsHelper.resolveLocales(
                        LocaleList.forLanguageTags("kk-Cryl-KZ"),  // restore
                        LocaleList.forLanguageTags("en-US"),  // current
                        new String[] { "en-US", "kk-Latn-KZ" }));  // supported

        assertEquals(LocaleList.forLanguageTags("en-US,kk-Cryl-KZ"),
                SettingsHelper.resolveLocales(
                        LocaleList.forLanguageTags("kk-Cryl-KZ"),  // restore
                        LocaleList.forLanguageTags("en-US"),  // current
                        new String[] { "en-US", "kk-Cryl-KZ" }));  // supported

        assertEquals(LocaleList.forLanguageTags("en-US,zh-CN"),
                SettingsHelper.resolveLocales(
                        LocaleList.forLanguageTags("zh-Hans-CN"),  // restore
                        LocaleList.forLanguageTags("en-US"),  // current
                        new String[] { "en-US", "zh-CN" }));  // supported

        assertEquals(LocaleList.forLanguageTags("en-US,zh-Hans-CN"),
                SettingsHelper.resolveLocales(
                        LocaleList.forLanguageTags("zh-CN"),  // restore
                        LocaleList.forLanguageTags("en-US"),  // current
                        new String[] { "en-US", "zh-Hans-CN" }));  // supported

        // Old langauge code should be updated.
        assertEquals(LocaleList.forLanguageTags("en-US,he-IL,id-ID,yi"),
                SettingsHelper.resolveLocales(
                        LocaleList.forLanguageTags("iw-IL,in-ID,ji"),  // restore
                        LocaleList.forLanguageTags("en-US"),  // current
                        new String[] { "he-IL", "id-ID", "yi" }));  // supported
    }

    @Test
    public void restoreValue_autoRotation_deviceStateAutoRotationDisabled_restoresValue() {
        when(mResources.getStringArray(R.array.config_perDeviceStateRotationLockDefaults))
                .thenReturn(new String[]{});
        int previousValue = 0;
        int newValue = 1;
        setAutoRotationSettingValue(previousValue);

        restoreAutoRotationSetting(newValue);

        assertThat(getAutoRotationSettingValue()).isEqualTo(newValue);
    }

    @Test
    public void restoreValue_autoRotation_deviceStateAutoRotationEnabled_doesNotRestoreValue() {
        when(mResources.getStringArray(R.array.config_perDeviceStateRotationLockDefaults))
                .thenReturn(new String[]{"0:1", "1:1"});
        int previousValue = 0;
        int newValue = 1;
        setAutoRotationSettingValue(previousValue);

        restoreAutoRotationSetting(newValue);

        assertThat(getAutoRotationSettingValue()).isEqualTo(previousValue);
    }

    private int getAutoRotationSettingValue() {
        return Settings.System.getInt(
                getContentResolver(),
                Settings.System.ACCELEROMETER_ROTATION,
                /* default= */ -1);
    }

    private void setAutoRotationSettingValue(int value) {
        Settings.System.putInt(
                getContentResolver(),
                Settings.System.ACCELEROMETER_ROTATION,
                value
        );
    }

    private void restoreAutoRotationSetting(int newValue) {
        mSettingsHelper.restoreValue(
                mContext,
                getContentResolver(),
                new ContentValues(),
                /* destination= */ Settings.System.CONTENT_URI,
                /* name= */ Settings.System.ACCELEROMETER_ROTATION,
                /* value= */ String.valueOf(newValue),
                /* restoredFromSdkInt= */ 0);
    }

    private ContentResolver getContentResolver() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getContentResolver();
    }

    private void clearLongPressPowerValues() {
        ContentResolver cr = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getContentResolver();
        Settings.Global.putString(cr, Settings.Global.POWER_BUTTON_LONG_PRESS, null);
        Settings.Global.putString(cr, Settings.Global.KEY_CHORD_POWER_VOLUME_UP, null);
    }
}
