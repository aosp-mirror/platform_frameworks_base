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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.LocaleList;
import android.provider.BaseColumns;
import android.provider.MediaStore;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;
import android.util.ArrayMap;

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

    private static final String DEFAULT_RINGTONE_VALUE =
            "content://media/internal/audio/media/10?title=DefaultRingtone&canonical=1";
    private static final String DEFAULT_NOTIFICATION_VALUE =
            "content://media/internal/audio/media/20?title=DefaultNotification&canonical=1";
    private static final String DEFAULT_ALARM_VALUE =
            "content://media/internal/audio/media/30?title=DefaultAlarm&canonical=1";

    private SettingsHelper mSettingsHelper;

    @Mock private Context mContext;
    @Mock private Resources mResources;
    @Mock private AudioManager mAudioManager;
    @Mock private TelephonyManager mTelephonyManager;

    @Mock private MockContentResolver mContentResolver;
    private MockSettingsProvider mSettingsProvider;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mContext.getSystemService(eq(Context.AUDIO_SERVICE))).thenReturn(mAudioManager);
        when(mContext.getSystemService(eq(Context.TELEPHONY_SERVICE))).thenReturn(
                mTelephonyManager);
        when(mContext.getResources()).thenReturn(mResources);
        when(mContext.getApplicationContext()).thenReturn(mContext);
        when(mContext.getApplicationInfo()).thenReturn(new ApplicationInfo());

        mSettingsHelper = spy(new SettingsHelper(mContext));
        mContentResolver = spy(new MockContentResolver());
        when(mContext.getContentResolver()).thenReturn(mContentResolver);
        mSettingsProvider = new MockSettingsProvider(mContext);
        mContentResolver.addProvider(Settings.AUTHORITY, mSettingsProvider);
    }

    @After
    public void tearDown() {
        Settings.Global.putString(mContentResolver, Settings.Global.POWER_BUTTON_LONG_PRESS,
                null);
        Settings.Global.putString(mContentResolver, Settings.Global.KEY_CHORD_POWER_VOLUME_UP,
                null);
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

        // The only time of interaction happened during setUp()
        verify(mContentResolver, times(1))
                .addProvider(Settings.AUTHORITY, mSettingsProvider);

        verifyNoMoreInteractions(mContentResolver);
    }

    @Test
    public void testRestoreValue_lppForAssistantEnabled_updatesValue() {
        when(mResources.getBoolean(
                R.bool.config_longPressOnPowerForAssistantSettingAvailable)).thenReturn(
                true);

        mSettingsHelper.restoreValue(mContext, mContentResolver, new ContentValues(), Uri.EMPTY,
                Settings.Global.POWER_BUTTON_LONG_PRESS, "5", 0);

        assertThat(Settings.Global.getInt(
                mContentResolver, Settings.Global.POWER_BUTTON_LONG_PRESS, -1)).isEqualTo(5);
        assertThat(Settings.Global.getInt(
                mContentResolver, Settings.Global.KEY_CHORD_POWER_VOLUME_UP, -1)).isEqualTo(2);
    }

    @Test
    public void testRestoreValue_lppForAssistantNotEnabled_updatesValueToDefaultConfig() {
        when(mResources.getBoolean(
                R.bool.config_longPressOnPowerForAssistantSettingAvailable)).thenReturn(
                true);

        when(mResources.getInteger(
                R.integer.config_longPressOnPowerBehavior)).thenReturn(
                1);
        when(mResources.getInteger(
                R.integer.config_keyChordPowerVolumeUp)).thenReturn(
                1);

        mSettingsHelper.restoreValue(mContext, mContentResolver, new ContentValues(), Uri.EMPTY,
                Settings.Global.POWER_BUTTON_LONG_PRESS, "2", 0);

        assertThat(Settings.Global.getInt(
                mContentResolver, Settings.Global.POWER_BUTTON_LONG_PRESS, -1)).isEqualTo(1);
        assertThat(Settings.Global.getInt(
                mContentResolver, Settings.Global.KEY_CHORD_POWER_VOLUME_UP, -1)).isEqualTo(1);
    }

    @Test
    public void testRestoreValue_lppForAssistantNotEnabledDefaultConfig_updatesValue() {
        when(mResources.getBoolean(
                R.bool.config_longPressOnPowerForAssistantSettingAvailable)).thenReturn(
                true);

        when(mResources.getInteger(
                R.integer.config_longPressOnPowerBehavior)).thenReturn(
                5);
        when(mResources.getInteger(
                R.integer.config_keyChordPowerVolumeUp)).thenReturn(
                1);

        mSettingsHelper.restoreValue(mContext, mContentResolver, new ContentValues(), Uri.EMPTY,
                Settings.Global.POWER_BUTTON_LONG_PRESS, "2", 0);

        assertThat(Settings.Global.getInt(
                mContentResolver, Settings.Global.POWER_BUTTON_LONG_PRESS, -1)).isEqualTo(1);
        assertThat(Settings.Global.getInt(
                mContentResolver, Settings.Global.KEY_CHORD_POWER_VOLUME_UP, -1)).isEqualTo(1);
    }

    @Test
    public void testRestoreValue_lppForAssistantNotAvailable_doesNotRestore() {
        when(mResources.getBoolean(R.bool.config_longPressOnPowerForAssistantSettingAvailable))
                .thenReturn(false);

        mSettingsHelper.restoreValue(mContext, mContentResolver, new ContentValues(), Uri.EMPTY,
                Settings.Global.POWER_BUTTON_LONG_PRESS, "500", 0);

        assertThat((Settings.Global.getInt(
                mContentResolver, Settings.Global.POWER_BUTTON_LONG_PRESS, -1))).isEqualTo(-1);
    }


    @Test
    public void testRestoreValue_lppForAssistantInvalid_doesNotRestore() {
        when(mResources.getBoolean(
                R.bool.config_longPressOnPowerForAssistantSettingAvailable)).thenReturn(
                false);

        mSettingsHelper.restoreValue(mContext, mContentResolver, new ContentValues(), Uri.EMPTY,
                Settings.Global.POWER_BUTTON_LONG_PRESS, "trees", 0);

        assertThat((Settings.Global.getInt(
                mContentResolver, Settings.Global.POWER_BUTTON_LONG_PRESS, -1))).isEqualTo(-1);
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

        // Old language code should be updated.
        assertEquals(LocaleList.forLanguageTags("en-US,he-IL,id-ID,yi"),
                SettingsHelper.resolveLocales(
                        LocaleList.forLanguageTags("iw-IL,in-ID,ji"),  // restore
                        LocaleList.forLanguageTags("en-US"),  // current
                        new String[] { "he-IL", "id-ID", "yi" }));  // supported

        // No matter the current locale has "nu" extension or not, if the restored locale has fw
        // (first day of week) or mu(temperature unit) extension, we should restore fw or mu
        // extensions as well and append these to restore and current locales.
        assertEquals(LocaleList.forLanguageTags(
                "en-US-u-fw-mon-mu-celsius,zh-Hant-TW-u-fw-mon-mu-celsius"),
                SettingsHelper.resolveLocales(
                        LocaleList.forLanguageTags("zh-Hant-TW-u-fw-mon-mu-celsius"),  // restore
                        LocaleList.forLanguageTags("en-US"),  // current
                        new String[] { "en-US", "zh-Hant-TW" }));  // supported

        // No matter the current locale has "nu" extension or not, if the restored locale has fw
        // (first day of week) or mu(temperature unit) extension, we should restore fw or mu
        // extensions as well and append these to restore and current locales.
        assertEquals(LocaleList.forLanguageTags(
                "fa-Arab-AF-u-nu-latn-fw-mon-mu-celsius,zh-Hant-TW-u-fw-mon-mu-celsius"),
                SettingsHelper.resolveLocales(
                        LocaleList.forLanguageTags("zh-Hant-TW-u-fw-mon-mu-celsius"),  // restore
                        LocaleList.forLanguageTags("fa-Arab-AF-u-nu-latn"),  // current
                        new String[] { "fa-Arab-AF-u-nu-latn", "zh-Hant-TW" }));  // supported

        // If the restored locale only has nu extension, we should not restore the nu extensions to
        // current locales.
        assertEquals(LocaleList.forLanguageTags("zh-Hant-TW,fa-Arab-AF-u-nu-latn"),
                SettingsHelper.resolveLocales(
                        LocaleList.forLanguageTags("fa-Arab-AF-u-nu-latn"),  // restore
                        LocaleList.forLanguageTags("zh-Hant-TW"),  // current
                        new String[] { "fa-Arab-AF-u-nu-latn", "zh-Hant-TW" }));  // supported


    }

    @Test
    public void testRestoreValue_customRingtone_regularUncanonicalize_Success() {
        final String sourceRingtoneValue =
                "content://media/internal/audio/media/1?title=Song&canonical=1";
        final String newRingtoneValueUncanonicalized =
                "content://media/internal/audio/media/100";
        final String newRingtoneValueCanonicalized =
                "content://media/internal/audio/media/100?title=Song&canonical=1";

        ContentProvider mockMediaContentProvider =
                new MockContentProvider(mContext) {
                    @Override
                    public Uri uncanonicalize(Uri url) {
                        assertThat(url).isEqualTo(Uri.parse(sourceRingtoneValue));
                        return Uri.parse(newRingtoneValueUncanonicalized);
                    }

                    @Override
                    public Uri canonicalize(Uri url) {
                        assertThat(url).isEqualTo(Uri.parse(newRingtoneValueUncanonicalized));
                        return Uri.parse(newRingtoneValueCanonicalized);
                    }

                    @Override
                    public String getType(Uri url) {
                        return "audio/ogg";
                    }
                };

        mContentResolver.addProvider(MediaStore.AUTHORITY, mockMediaContentProvider);

        resetRingtoneSettingsToDefault();
        assertThat(Settings.System.getString(mContentResolver, Settings.System.RINGTONE))
                .isEqualTo(DEFAULT_RINGTONE_VALUE);

        mSettingsHelper.restoreValue(
                mContext,
                mContentResolver,
                new ContentValues(),
                Uri.EMPTY,
                Settings.System.RINGTONE,
                sourceRingtoneValue,
                0);

        assertThat(Settings.System.getString(mContentResolver, Settings.System.RINGTONE))
                .isEqualTo(newRingtoneValueCanonicalized);
    }

    @Test
    public void testRestoreValue_customRingtone_useCustomLookup_success() {
        final String sourceRingtoneValue =
                "content://0@media/external/audio/media/1?title=Song&canonical=1";
        final String newRingtoneValueUncanonicalized =
                "content://0@media/external/audio/media/100";
        final String newRingtoneValueCanonicalized =
                "content://0@media/external/audio/media/100?title=Song&canonical=1";

        MatrixCursor cursor = new MatrixCursor(new String[] {BaseColumns._ID});
        cursor.addRow(new Object[] {100L});

        ContentProvider mockMediaContentProvider =
                new MockContentProvider(mContext) {
                    @Override
                    public Uri uncanonicalize(Uri url) {
                        // mock the lookup failure in regular MediaProvider.uncanonicalize.
                        return null;
                    }

                    @Override
                    public Uri canonicalize(Uri url) {
                        assertThat(url).isEqualTo(Uri.parse(newRingtoneValueUncanonicalized));
                        return Uri.parse(newRingtoneValueCanonicalized);
                    }

                    @Override
                    public String getType(Uri url) {
                        return "audio/ogg";
                    }

                    @Override
                    public Cursor query(
                            Uri uri,
                            String[] projection,
                            String selection,
                            String[] selectionArgs,
                            String sortOrder) {
                        assertThat(uri)
                                .isEqualTo(Uri.parse("content://0@media/external/audio/media"));
                        assertThat(projection).isEqualTo(new String[] {"_id"});
                        assertThat(selection).isEqualTo("is_ringtone=1 AND title=?");
                        assertThat(selectionArgs).isEqualTo(new String[] {"Song"});
                        return cursor;
                    }
                };

        mContentResolver.addProvider(MediaStore.AUTHORITY, mockMediaContentProvider);
        mContentResolver.addProvider("0@" + MediaStore.AUTHORITY, mockMediaContentProvider);

        resetRingtoneSettingsToDefault();

        mSettingsHelper.restoreValue(
                mContext,
                mContentResolver,
                new ContentValues(),
                Uri.EMPTY,
                Settings.System.RINGTONE,
                sourceRingtoneValue,
                0);

        assertThat(Settings.System.getString(mContentResolver, Settings.System.RINGTONE))
                .isEqualTo(newRingtoneValueCanonicalized);
    }

    @Test
    public void testRestoreValue_customRingtone_notificationSound_useCustomLookup_success() {
        final String sourceRingtoneValue =
                "content://0@media/external/audio/media/2?title=notificationPing&canonical=1";
        final String newRingtoneValueUncanonicalized =
                "content://0@media/external/audio/media/200";
        final String newRingtoneValueCanonicalized =
                "content://0@media/external/audio/media/200?title=notificationPing&canonicalize=1";

        MatrixCursor cursor = new MatrixCursor(new String[] {BaseColumns._ID});
        cursor.addRow(new Object[] {200L});

        ContentProvider mockMediaContentProvider =
                new MockContentProvider(mContext) {
                    @Override
                    public Uri uncanonicalize(Uri url) {
                        // mock the lookup failure in regular MediaProvider.uncanonicalize.
                        return null;
                    }

                    @Override
                    public Uri canonicalize(Uri url) {
                        assertThat(url).isEqualTo(Uri.parse(newRingtoneValueUncanonicalized));
                        return Uri.parse(newRingtoneValueCanonicalized);
                    }

                    @Override
                    public String getType(Uri url) {
                        return "audio/ogg";
                    }

                    @Override
                    public Cursor query(
                            Uri uri,
                            String[] projection,
                            String selection,
                            String[] selectionArgs,
                            String sortOrder) {
                        assertThat(uri)
                                .isEqualTo(Uri.parse("content://0@media/external/audio/media"));
                        assertThat(projection).isEqualTo(new String[] {"_id"});
                        assertThat(selection).isEqualTo("is_notification=1 AND title=?");
                        assertThat(selectionArgs).isEqualTo(new String[] {"notificationPing"});
                        return cursor;
                    }
                };

        mContentResolver.addProvider(MediaStore.AUTHORITY, mockMediaContentProvider);
        mContentResolver.addProvider("0@" + MediaStore.AUTHORITY, mockMediaContentProvider);

        resetRingtoneSettingsToDefault();

        mSettingsHelper.restoreValue(
                mContext,
                mContentResolver,
                new ContentValues(),
                Uri.EMPTY,
                Settings.System.NOTIFICATION_SOUND,
                sourceRingtoneValue,
                0);

        assertThat(
                        Settings.System.getString(
                                mContentResolver, Settings.System.NOTIFICATION_SOUND))
                .isEqualTo(newRingtoneValueCanonicalized);
    }

    @Test
    public void testRestoreValue_customRingtone_alarmSound_useCustomLookup_success() {
        final String sourceRingtoneValue =
                "content://0@media/external/audio/media/3?title=alarmSound&canonical=1";
        final String newRingtoneValueUncanonicalized =
                "content://0@media/external/audio/media/300";
        final String newRingtoneValueCanonicalized =
                "content://0@media/external/audio/media/300?title=alarmSound&canonical=1";

        MatrixCursor cursor = new MatrixCursor(new String[] {BaseColumns._ID});
        cursor.addRow(new Object[] {300L});

        ContentProvider mockMediaContentProvider =
                new MockContentProvider(mContext) {
                    @Override
                    public Uri uncanonicalize(Uri url) {
                        // mock the lookup failure in regular MediaProvider.uncanonicalize.
                        return null;
                    }

                    @Override
                    public Uri canonicalize(Uri url) {
                        assertThat(url).isEqualTo(Uri.parse(newRingtoneValueUncanonicalized));
                        return Uri.parse(newRingtoneValueCanonicalized);
                    }

                    @Override
                    public String getType(Uri url) {
                        return "audio/ogg";
                    }

                    @Override
                    public Cursor query(
                            Uri uri,
                            String[] projection,
                            String selection,
                            String[] selectionArgs,
                            String sortOrder) {
                        assertThat(uri)
                                .isEqualTo(Uri.parse("content://0@media/external/audio/media"));
                        assertThat(projection).isEqualTo(new String[] {"_id"});
                        assertThat(selection).isEqualTo("is_alarm=1 AND title=?");
                        assertThat(selectionArgs).isEqualTo(new String[] {"alarmSound"});
                        return cursor;
                    }

                    @Override
                    public AssetFileDescriptor openTypedAssetFile(Uri url, String mimeType,
                            Bundle opts) {
                        return null;
                    }
                };

        mContentResolver.addProvider(MediaStore.AUTHORITY, mockMediaContentProvider);
        mContentResolver.addProvider("0@" + MediaStore.AUTHORITY, mockMediaContentProvider);

        resetRingtoneSettingsToDefault();

        mSettingsHelper.restoreValue(
                mContext,
                mContentResolver,
                new ContentValues(),
                Uri.EMPTY,
                Settings.System.ALARM_ALERT,
                sourceRingtoneValue,
                0);

        assertThat(Settings.System.getString(mContentResolver, Settings.System.ALARM_ALERT))
                .isEqualTo(newRingtoneValueCanonicalized);
    }

    @Test
    public void testRestoreValue_customRingtone_useCustomLookup_multipleResults_notRestore() {
        final String sourceRingtoneValue =
                "content://0@media/external/audio/media/1?title=Song&canonical=1";

        // This is to mock the case that there are multiple results by querying title +
        // ringtone_type.
        MatrixCursor cursor = new MatrixCursor(new String[] {BaseColumns._ID});
        cursor.addRow(new Object[] {100L});
        cursor.addRow(new Object[] {110L});

        ContentProvider mockMediaContentProvider =
                new MockContentProvider(mContext) {
                    @Override
                    public Uri uncanonicalize(Uri url) {
                        // mock the lookup failure in regular MediaProvider.uncanonicalize.
                        return null;
                    }

                    @Override
                    public String getType(Uri url) {
                        return "audio/ogg";
                    }
                };

        mContentResolver.addProvider(MediaStore.AUTHORITY, mockMediaContentProvider);
        mContentResolver.addProvider("0@" + MediaStore.AUTHORITY, mockMediaContentProvider);

        resetRingtoneSettingsToDefault();

        mSettingsHelper.restoreValue(
                mContext,
                mContentResolver,
                new ContentValues(),
                Uri.EMPTY,
                Settings.System.RINGTONE,
                sourceRingtoneValue,
                0);

        assertThat(Settings.System.getString(mContentResolver, Settings.System.RINGTONE))
                .isEqualTo(DEFAULT_RINGTONE_VALUE);
    }

    @Test
    public void testRestoreValue_customRingtone_restoreSilentValue() {
        ContentProvider mockMediaContentProvider =
                new MockContentProvider(mContext) {
                    @Override
                    public Uri uncanonicalize(Uri url) {
                        // mock the lookup failure in regular MediaProvider.uncanonicalize.
                        return null;
                    }

                    @Override
                    public String getType(Uri url) {
                        return "audio/ogg";
                    }
                };

        mContentResolver.addProvider(MediaStore.AUTHORITY, mockMediaContentProvider);

        resetRingtoneSettingsToDefault();

        mSettingsHelper.restoreValue(
                mContext,
                mContentResolver,
                new ContentValues(),
                Uri.EMPTY,
                Settings.System.RINGTONE,
                "_silent",
                0);

        assertThat(Settings.System.getString(mContentResolver, Settings.System.RINGTONE))
                .isEqualTo(null);
    }

    private static class MockSettingsProvider extends MockContentProvider {
        private final ArrayMap<String, String> mKeyValueStore = new ArrayMap<>();
        MockSettingsProvider(Context context) {
            super(context);
        }

        @Override
        public Bundle call(String method, String request, Bundle args) {
            if (method.startsWith("PUT_")) {
                mKeyValueStore.put(request, args.getString("value"));
                return null;
            } else if (method.startsWith("GET_")) {
                return Bundle.forPair("value", mKeyValueStore.getOrDefault(request, ""));
            }
            return null;
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            String name = values.getAsString("name");
            String value = values.getAsString("value");
            mKeyValueStore.put(name, value);
            return null;
        }
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
        return Settings.System.getInt(mContentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                /* default= */ -1);
    }

    private void setAutoRotationSettingValue(int value) {
        Settings.System.putInt(mContentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                value
        );
    }

    private void restoreAutoRotationSetting(int newValue) {
        mSettingsHelper.restoreValue(
                mContext,
                mContentResolver,
                new ContentValues(),
                /* destination= */ Settings.System.CONTENT_URI,
                /* name= */ Settings.System.ACCELEROMETER_ROTATION,
                /* value= */ String.valueOf(newValue),
                /* restoredFromSdkInt= */ 0);
    }

    private void resetRingtoneSettingsToDefault() {
        Settings.System.putString(
                mContentResolver, Settings.System.RINGTONE, DEFAULT_RINGTONE_VALUE);
        Settings.System.putString(
                mContentResolver, Settings.System.NOTIFICATION_SOUND, DEFAULT_NOTIFICATION_VALUE);
        Settings.System.putString(
                mContentResolver, Settings.System.ALARM_ALERT, DEFAULT_ALARM_VALUE);

        assertThat(Settings.System.getString(mContentResolver, Settings.System.RINGTONE))
                .isEqualTo(DEFAULT_RINGTONE_VALUE);
        assertThat(Settings.System.getString(mContentResolver, Settings.System.NOTIFICATION_SOUND))
                .isEqualTo(DEFAULT_NOTIFICATION_VALUE);
        assertThat(Settings.System.getString(mContentResolver, Settings.System.ALARM_ALERT))
                .isEqualTo(DEFAULT_ALARM_VALUE);
    }
}
