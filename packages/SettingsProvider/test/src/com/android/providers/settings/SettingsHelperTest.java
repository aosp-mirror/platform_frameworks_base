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

import static junit.framework.Assert.assertEquals;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.AudioManager;
import android.net.Uri;
import android.os.LocaleList;
import android.telephony.TelephonyManager;

import androidx.test.runner.AndroidJUnit4;

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
    @Mock private ContentResolver mContentResolver;
    @Mock private AudioManager mAudioManager;
    @Mock private TelephonyManager mTelephonyManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mContext.getSystemService(eq(Context.AUDIO_SERVICE))).thenReturn(mAudioManager);
        when(mContext.getSystemService(eq(Context.TELEPHONY_SERVICE))).thenReturn(
                mTelephonyManager);

        mSettingsHelper = spy(new SettingsHelper(mContext));
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
}
