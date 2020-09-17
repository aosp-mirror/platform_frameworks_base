/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.stats.pull;

import static android.os.UserHandle.USER_SYSTEM;

import static com.android.internal.util.FrameworkStatsLog.SETTING_SNAPSHOT;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.provider.DeviceConfig;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Build/Install/Run:
 * atest FrameworksServicesTests:SettingsStatsUtilTest
 */
@RunWith(AndroidJUnit4.class)
public class SettingsStatsUtilTest {
    private static final String[] KEYS = new String[]{
            "screen_auto_brightness_adj",
            "font_scale"
    };
    private static final String ENCODED = "ChpzY3JlZW5fYXV0b19icmlnaHRuZXNzX2FkagoKZm9udF9zY2FsZQ";
    private static final String FLAG = "testflag";
    private Context mContext;

    @Before
    public void setUp() {
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_SETTINGS_STATS,
                FLAG,
                "",
                false /* makeDefault*/);
        mContext = InstrumentationRegistry.getInstrumentation().getContext();
    }

    @Test
    public void getList_emptyString_nullValue() {
        assertNull(SettingsStatsUtil.getList(FLAG));
    }

    @Test
    public void getList_notValidString_nullValue() {
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_SETTINGS_STATS, FLAG, "abcd", false);

        assertNull(SettingsStatsUtil.getList(FLAG));
    }

    @Test
    public void getList_validString_correctValue() {
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_SETTINGS_STATS, FLAG, ENCODED, false);

        assertArrayEquals(KEYS, SettingsStatsUtil.getList(FLAG).element);
    }

    @Test
    public void logGlobalSettings_noWhitelist_correctSize() {
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_SETTINGS_STATS,
                "GlobalFeature__boolean_whitelist", "", false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_SETTINGS_STATS,
                "GlobalFeature__integer_whitelist", "", false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_SETTINGS_STATS,
                "GlobalFeature__float_whitelist", "", false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_SETTINGS_STATS,
                "GlobalFeature__string_whitelist", "", false);

        assertEquals(0, SettingsStatsUtil.logGlobalSettings(mContext, SETTING_SNAPSHOT,
                USER_SYSTEM).size());
    }

    @Test
    public void logGlobalSettings_correctSize() {
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_SETTINGS_STATS,
                "GlobalFeature__boolean_whitelist", ENCODED, false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_SETTINGS_STATS,
                "GlobalFeature__integer_whitelist", ENCODED, false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_SETTINGS_STATS,
                "GlobalFeature__float_whitelist", ENCODED, false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_SETTINGS_STATS,
                "GlobalFeature__string_whitelist", ENCODED, false);

        assertEquals(KEYS.length * 4,
                SettingsStatsUtil.logGlobalSettings(mContext, SETTING_SNAPSHOT,
                        USER_SYSTEM).size());
    }
}
