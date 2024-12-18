/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.server.policy;

import static android.view.KeyEvent.KEYCODE_POWER;
import static android.view.KeyEvent.KEYCODE_VOLUME_DOWN;
import static android.view.KeyEvent.KEYCODE_VOLUME_UP;

import static com.android.server.policy.PhoneWindowManager.POWER_VOLUME_UP_BEHAVIOR_GLOBAL_ACTIONS;
import static com.android.server.policy.PhoneWindowManager.POWER_VOLUME_UP_BEHAVIOR_MUTE;

import android.view.ViewConfiguration;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test class for combination key shortcuts.
 *
 * Build/Install/Run:
 *  atest WmTests:CombinationKeyTests
 */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class CombinationKeyTests extends ShortcutKeyTestBase {
    private static final long A11Y_KEY_HOLD_MILLIS = 3500;

    @Before
    public void setUp() {
        setUpPhoneWindowManager();
    }

    /**
     * Power-VolDown to take screenshot.
     */
    @Test
    public void testPowerVolumeDown() {
        sendKeyCombination(new int[]{KEYCODE_POWER, KEYCODE_VOLUME_DOWN},
                ViewConfiguration.get(mContext).getScreenshotChordKeyTimeout());
        mPhoneWindowManager.assertTakeScreenshotCalled();
    }

    /**
     * Power-VolUp to show global actions or mute audio. (Phone default behavior)
     */
    @Test
    public void testPowerVolumeUp() {
        // Show global actions.
        mPhoneWindowManager.overridePowerVolumeUp(POWER_VOLUME_UP_BEHAVIOR_GLOBAL_ACTIONS);
        sendKeyCombination(new int[]{KEYCODE_POWER, KEYCODE_VOLUME_UP}, 0);
        mPhoneWindowManager.assertShowGlobalActionsCalled();

        // Mute audio (hold over 100ms).
        mPhoneWindowManager.overridePowerVolumeUp(POWER_VOLUME_UP_BEHAVIOR_MUTE);
        sendKeyCombination(new int[]{KEYCODE_POWER, KEYCODE_VOLUME_UP}, 100);
        mPhoneWindowManager.assertVolumeMute();
    }

    /**
     * VolDown-VolUp and hold 3 secs to enable accessibility service.
     */
    @Test
    public void testVolumeDownVolumeUp() {
        sendKeyCombination(new int[]{KEYCODE_VOLUME_DOWN, KEYCODE_VOLUME_UP}, A11Y_KEY_HOLD_MILLIS);
        mPhoneWindowManager.assertAccessibilityKeychordCalled();
    }
}
