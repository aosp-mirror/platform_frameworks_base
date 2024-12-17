/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.net.Uri;
import android.provider.Settings;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link SettingsBackupRestoreKeys}.
 */
@RunWith(AndroidJUnit4.class)
public class SettingsBackupRestoreKeysTest {

    @Test
    public void getKeyFromUri_secureUri_returnsSecureKey() {
        assertThat(SettingsBackupRestoreKeys.getKeyFromUri(Settings.Secure.CONTENT_URI))
                .isEqualTo(SettingsBackupRestoreKeys.KEY_SECURE);
    }

    @Test
    public void getKeyFromUri_systemUri_returnsSystemKey() {
        assertThat(SettingsBackupRestoreKeys.getKeyFromUri(Settings.System.CONTENT_URI))
                .isEqualTo(SettingsBackupRestoreKeys.KEY_SYSTEM);
    }

    @Test
    public void getKeyFromUri_globalUri_returnsGlobalKey() {
        assertThat(SettingsBackupRestoreKeys.getKeyFromUri(Settings.Global.CONTENT_URI))
                .isEqualTo(SettingsBackupRestoreKeys.KEY_GLOBAL);
    }

    @Test
    public void getKeyFromUri_unknownUri_returnsUnknownKey() {
        assertThat(SettingsBackupRestoreKeys.getKeyFromUri(Uri.parse("content://unknown")))
                .isEqualTo(SettingsBackupRestoreKeys.KEY_UNKNOWN);
    }
}