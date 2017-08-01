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

package com.android.settingslib.development;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.provider.Settings;

import com.android.settingslib.SettingsLibRobolectricTestRunner;
import com.android.settingslib.TestConfig;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(SettingsLibRobolectricTestRunner.class)
@Config(manifest = TestConfig.MANIFEST_PATH, sdk = TestConfig.SDK_VERSION)
public class DevelopmentSettingsEnablerTest {

    private Context mContext;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.application;
    }

    @Test
    public void testEnabling() {
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0);

        assertThat(DevelopmentSettingsEnabler.isDevelopmentSettingsEnabled(mContext)).isFalse();

        DevelopmentSettingsEnabler.setDevelopmentSettingsEnabled(mContext, true);

        assertThat(DevelopmentSettingsEnabler.isDevelopmentSettingsEnabled(mContext)).isTrue();
    }

    @Test
    public void testDisabling() {
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1);

        assertThat(DevelopmentSettingsEnabler.isDevelopmentSettingsEnabled(mContext)).isTrue();

        DevelopmentSettingsEnabler.setDevelopmentSettingsEnabled(mContext, false);

        assertThat(DevelopmentSettingsEnabler.isDevelopmentSettingsEnabled(mContext)).isFalse();
    }
}
