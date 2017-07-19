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
import static junit.framework.Assert.assertSame;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.fail;

import com.android.internal.app.LocalePicker;
import com.android.providers.settings.SettingsHelper;

import android.os.LocaleList;
import android.support.test.runner.AndroidJUnit4;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for the SettingsHelperTest
 */
@RunWith(AndroidJUnit4.class)
public class SettingsHelperTest {
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
