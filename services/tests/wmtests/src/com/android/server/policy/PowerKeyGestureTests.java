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
import static android.view.KeyEvent.KEYCODE_VOLUME_UP;

import static com.android.server.policy.PhoneWindowManager.LONG_PRESS_POWER_ASSISTANT;
import static com.android.server.policy.PhoneWindowManager.LONG_PRESS_POWER_GLOBAL_ACTIONS;

import android.view.Display;

import org.junit.Test;

/**
 * Test class for power key gesture.
 *
 * Build/Install/Run:
 *  atest WmTests:PowerKeyGestureTests
 */
public class PowerKeyGestureTests extends ShortcutKeyTestBase {
    /**
     * Power single press to turn screen on/off.
     */
    @Test
    public void testPowerSinglePress() {
        sendKey(KEYCODE_POWER);
        mPhoneWindowManager.assertPowerSleep();

        // turn screen on when begin from non-interactive.
        mPhoneWindowManager.overrideDisplayState(Display.STATE_OFF);
        sendKey(KEYCODE_POWER);
        mPhoneWindowManager.assertPowerWakeUp();
        mPhoneWindowManager.assertNoPowerSleep();
    }

    /**
     * Power double press to trigger camera.
     */
    @Test
    public void testPowerDoublePress() {
        sendKey(KEYCODE_POWER);
        sendKey(KEYCODE_POWER);
        mPhoneWindowManager.assertCameraLaunch();
    }

    /**
     * Power long press to show assistant or global actions.
     */
    @Test
    public void testPowerLongPress() {
        // Show assistant.
        mPhoneWindowManager.overrideLongPressOnPower(LONG_PRESS_POWER_ASSISTANT);
        sendKey(KEYCODE_POWER, true);
        mPhoneWindowManager.assertAssistLaunch();

        // Show global actions.
        mPhoneWindowManager.overrideLongPressOnPower(LONG_PRESS_POWER_GLOBAL_ACTIONS);
        sendKey(KEYCODE_POWER, true);
        mPhoneWindowManager.assertShowGlobalActionsCalled();
    }

    /**
     * Ignore power press if combination key already triggered.
     */
    @Test
    public void testIgnoreSinglePressWhenCombinationKeyTriggered() {
        sendKeyCombination(new int[]{KEYCODE_POWER, KEYCODE_VOLUME_UP}, 0);
        mPhoneWindowManager.assertNoPowerSleep();
    }
}
