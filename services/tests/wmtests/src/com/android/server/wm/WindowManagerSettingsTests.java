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

package com.android.server.wm;

import static android.provider.Settings.Global.DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT;
import static android.provider.Settings.Global.DEVELOPMENT_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS;
import static android.provider.Settings.Global.DEVELOPMENT_FORCE_RESIZABLE_ACTIVITIES;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;

import android.content.ContentResolver;
import android.net.Uri;
import android.provider.Settings;

import androidx.test.filters.SmallTest;

import org.junit.Test;

/**
 * Test for {@link WindowManagerService.SettingsObserver}.
 *
 * Build/Install/Run:
 *  atest WmTests:WindowManagerSettingsTests
 */
@SmallTest
public class WindowManagerSettingsTests extends WindowTestsBase {

    @Test
    public void testForceDesktopModeOnExternalDisplays() {
        try (SettingsSession forceDesktopModeSession = new
                SettingsSession(DEVELOPMENT_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS)) {
            final boolean forceDesktopMode = !forceDesktopModeSession.getSetting();
            final Uri forceDesktopModeUri = forceDesktopModeSession.setSetting(forceDesktopMode);
            mWm.mSettingsObserver.onChange(false, forceDesktopModeUri);

            assertEquals(mWm.mForceDesktopModeOnExternalDisplays, forceDesktopMode);
        }
    }

    @Test
    public void testFreeformWindow() {
        try (SettingsSession freeformWindowSession = new
                SettingsSession(DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT)) {
            final boolean freeformWindow = !freeformWindowSession.getSetting();
            final Uri freeformWindowUri = freeformWindowSession.setSetting(freeformWindow);
            mWm.mSettingsObserver.onChange(false, freeformWindowUri);

            assertEquals(mWm.mAtmService.mSupportsFreeformWindowManagement, freeformWindow);
        }
    }

    @Test
    public void testForceResizableMode() {
        try (SettingsSession forceResizableSession = new
                SettingsSession(DEVELOPMENT_FORCE_RESIZABLE_ACTIVITIES)) {
            final boolean forceResizableMode = !forceResizableSession.getSetting();
            final Uri forceResizableUri =  forceResizableSession.setSetting(forceResizableMode);
            mWm.mSettingsObserver.onChange(false, forceResizableUri);

            assertEquals(mWm.mAtmService.mForceResizableActivities, forceResizableMode);
        }
    }

    private class SettingsSession implements AutoCloseable {

        private static final int SETTING_VALUE_OFF = 0;
        private static final int SETTING_VALUE_ON = 1;
        private final String mSettingName;
        private final int mInitialValue;

        SettingsSession(String name) {
            mSettingName = name;
            mInitialValue = getSetting() ? SETTING_VALUE_ON : SETTING_VALUE_OFF;
        }

        boolean getSetting() {
            return Settings.Global.getInt(getContentResolver(), mSettingName, 0) != 0;
        }

        Uri setSetting(boolean enable) {
            Settings.Global.putInt(getContentResolver(), mSettingName,
                    enable ? SETTING_VALUE_ON : SETTING_VALUE_OFF);
            return Settings.Global.getUriFor(mSettingName);
        }

        private ContentResolver getContentResolver() {
            return getInstrumentation().getTargetContext().getContentResolver();
        }

        @Override
        public void close() {
            setSetting(mInitialValue == SETTING_VALUE_ON);
        }
    }
}
