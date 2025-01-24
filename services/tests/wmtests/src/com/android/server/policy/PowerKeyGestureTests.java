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

import static com.android.hardware.input.Flags.FLAG_OVERRIDE_POWER_KEY_BEHAVIOR_IN_FOCUSED_WINDOW;
import static com.android.server.policy.PhoneWindowManager.LONG_PRESS_POWER_ASSISTANT;
import static com.android.server.policy.PhoneWindowManager.LONG_PRESS_POWER_GLOBAL_ACTIONS;
import static com.android.server.policy.PhoneWindowManager.POWER_MULTI_PRESS_TIMEOUT_MILLIS;
import static com.android.server.policy.PhoneWindowManager.SHORT_PRESS_POWER_DREAM_OR_SLEEP;
import static com.android.server.policy.PhoneWindowManager.SHORT_PRESS_POWER_GO_TO_SLEEP;

import static org.junit.Assert.assertEquals;

import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;
import android.view.Display;

import org.junit.Before;
import org.junit.Test;

/**
 * Test class for power key gesture.
 *
 * Build/Install/Run:
 *  atest WmTests:PowerKeyGestureTests
 */
@Presubmit
public class PowerKeyGestureTests extends ShortcutKeyTestBase {
    @Before
    public void setUp() {
        setUpPhoneWindowManager();
        mPhoneWindowManager.overrideStatusBarManagerInternal();
    }

    /**
     * Power single press to turn screen on/off.
     */
    @Test
    public void testPowerSinglePress() {
        mPhoneWindowManager.overrideShortPressOnPower(SHORT_PRESS_POWER_GO_TO_SLEEP);
        sendKey(KEYCODE_POWER);
        mPhoneWindowManager.assertPowerSleep();

        mPhoneWindowManager.moveTimeForward(POWER_MULTI_PRESS_TIMEOUT_MILLIS);

        // turn screen on when begin from non-interactive.
        mPhoneWindowManager.overrideDisplayState(Display.STATE_OFF);
        sendKey(KEYCODE_POWER);
        mPhoneWindowManager.assertPowerWakeUp();
        mPhoneWindowManager.assertNoPowerSleep();
    }

    /**
     * Power single press to start dreaming when so configured.
     */
    @Test
    public void testPowerSinglePressRequestsDream() {
        mPhoneWindowManager.overrideShortPressOnPower(SHORT_PRESS_POWER_DREAM_OR_SLEEP);
        mPhoneWindowManager.overrideCanStartDreaming(true);
        sendKey(KEYCODE_POWER);
        mPhoneWindowManager.assertDreamRequest();
        mPhoneWindowManager.overrideIsDreaming(true);
        mPhoneWindowManager.assertLockedAfterAppTransitionFinished();
    }

    @Test
    public void testAppTransitionFinishedCalledAfterDreamStoppedWillNotLockAgain() {
        mPhoneWindowManager.overrideShortPressOnPower(SHORT_PRESS_POWER_DREAM_OR_SLEEP);
        mPhoneWindowManager.overrideCanStartDreaming(true);
        sendKey(KEYCODE_POWER);
        mPhoneWindowManager.assertDreamRequest();
        mPhoneWindowManager.overrideIsDreaming(false);
        mPhoneWindowManager.assertDidNotLockAfterAppTransitionFinished();
    }

    /**
     * Power double-press to launch camera does not lock device when the single press behavior is to
     * dream.
     */
    @Test
    public void testPowerDoublePressWillNotLockDevice() {
        mPhoneWindowManager.overrideShortPressOnPower(SHORT_PRESS_POWER_DREAM_OR_SLEEP);
        mPhoneWindowManager.overrideCanStartDreaming(false);
        sendKey(KEYCODE_POWER);
        sendKey(KEYCODE_POWER);
        mPhoneWindowManager.assertDoublePowerLaunch();
        mPhoneWindowManager.assertDidNotLockAfterAppTransitionFinished();
    }

    /**
     * Power double press to trigger camera.
     */
    @Test
    public void testPowerDoublePress() {
        sendKey(KEYCODE_POWER);
        sendKey(KEYCODE_POWER);
        mPhoneWindowManager.assertDoublePowerLaunch();
    }

    /**
     * Power long press to show assistant or global actions.
     */
    @Test
    public void testPowerLongPress() {
        // Show assistant.
        mPhoneWindowManager.overrideLongPressOnPower(LONG_PRESS_POWER_ASSISTANT);
        sendKey(KEYCODE_POWER, SingleKeyGestureDetector.sDefaultLongPressTimeout);
        mPhoneWindowManager.assertSearchManagerLaunchAssist();

        mPhoneWindowManager.moveTimeForward(POWER_MULTI_PRESS_TIMEOUT_MILLIS);

        // Show global actions.
        mPhoneWindowManager.overrideLongPressOnPower(LONG_PRESS_POWER_GLOBAL_ACTIONS);
        sendKey(KEYCODE_POWER, SingleKeyGestureDetector.sDefaultLongPressTimeout);
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

    /**
     * When a phone call is active, and INCALL_POWER_BUTTON_BEHAVIOR_HANGUP is enabled, then the
     * power button should only stop phone call. The screen should not be turned off (power sleep
     * should not be activated).
     */
    @Test
    public void testIgnoreSinglePressWhenEndCall() {
        mPhoneWindowManager.overrideIncallPowerBehavior(
                Settings.Secure.INCALL_POWER_BUTTON_BEHAVIOR_HANGUP);
        sendKey(KEYCODE_POWER);
        mPhoneWindowManager.assertNoPowerSleep();
    }


    /**
     * Double press of power when the window handles the power key events. The
     * system double power gesture launch should not be performed.
     */
    @Test
    @EnableFlags(FLAG_OVERRIDE_POWER_KEY_BEHAVIOR_IN_FOCUSED_WINDOW)
    public void testPowerDoublePress_windowHasOverridePermissionAndKeysHandled() {
        mPhoneWindowManager.overrideCanWindowOverridePowerKey(true);
        setDispatchedKeyHandler(keyEvent -> true);

        sendKey(KEYCODE_POWER);
        sendKey(KEYCODE_POWER);

        mPhoneWindowManager.assertDidNotLockAfterAppTransitionFinished();

        mPhoneWindowManager.assertNoDoublePowerLaunch();
    }

    /**
     * Double press of power when the window doesn't handle the power key events.
     * The system default gesture launch should be performed and the app should receive both events.
     */
    @Test
    @EnableFlags(FLAG_OVERRIDE_POWER_KEY_BEHAVIOR_IN_FOCUSED_WINDOW)
    public void testPowerDoublePress_windowHasOverridePermissionAndKeysUnHandled() {
        mPhoneWindowManager.overrideCanWindowOverridePowerKey(true);
        setDispatchedKeyHandler(keyEvent -> false);

        sendKey(KEYCODE_POWER);
        sendKey(KEYCODE_POWER);

        mPhoneWindowManager.assertDidNotLockAfterAppTransitionFinished();
        mPhoneWindowManager.assertDoublePowerLaunch();
        assertEquals(getDownKeysDispatched(), 2);
        assertEquals(getUpKeysDispatched(), 2);
    }

    /**
     * Triple press of power when the window handles the power key double press gesture.
     * The system default gesture launch should not be performed, and the app only receives the
     * first two presses.
     */
    @Test
    @EnableFlags(FLAG_OVERRIDE_POWER_KEY_BEHAVIOR_IN_FOCUSED_WINDOW)
    public void testPowerTriplePress_windowHasOverridePermissionAndKeysHandled() {
        mPhoneWindowManager.overrideCanWindowOverridePowerKey(true);
        setDispatchedKeyHandler(keyEvent -> true);

        sendKey(KEYCODE_POWER);
        sendKey(KEYCODE_POWER);
        sendKey(KEYCODE_POWER);

        mPhoneWindowManager.assertDidNotLockAfterAppTransitionFinished();
        mPhoneWindowManager.assertNoDoublePowerLaunch();
        assertEquals(getDownKeysDispatched(), 2);
        assertEquals(getUpKeysDispatched(), 2);
    }

    /**
     * Tests a single press, followed by a double press when the window can handle the power key.
     * The app should receive all 3 events.
     */
    @Test
    @EnableFlags(FLAG_OVERRIDE_POWER_KEY_BEHAVIOR_IN_FOCUSED_WINDOW)
    public void testPowerTriplePressWithDelay_windowHasOverridePermissionAndKeysHandled() {
        mPhoneWindowManager.overrideCanWindowOverridePowerKey(true);
        setDispatchedKeyHandler(keyEvent -> true);

        sendKey(KEYCODE_POWER);
        mPhoneWindowManager.moveTimeForward(POWER_MULTI_PRESS_TIMEOUT_MILLIS);
        sendKey(KEYCODE_POWER);
        sendKey(KEYCODE_POWER);

        mPhoneWindowManager.assertNoDoublePowerLaunch();
        assertEquals(getDownKeysDispatched(), 3);
        assertEquals(getUpKeysDispatched(), 3);
    }

    /**
     * Tests single press when window doesn't handle the power key. Phone should go to sleep.
     */
    @Test
    @EnableFlags(FLAG_OVERRIDE_POWER_KEY_BEHAVIOR_IN_FOCUSED_WINDOW)
    public void testPowerSinglePress_windowHasOverridePermissionAndKeyUnhandledByApp() {
        mPhoneWindowManager.overrideCanWindowOverridePowerKey(true);
        setDispatchedKeyHandler(keyEvent -> false);
        mPhoneWindowManager.overrideShortPressOnPower(SHORT_PRESS_POWER_GO_TO_SLEEP);

        sendKey(KEYCODE_POWER);

        mPhoneWindowManager.assertPowerSleep();
    }

    /**
     * Tests single press when the window handles the power key. Phone should go to sleep after a
     * delay of {POWER_MULTI_PRESS_TIMEOUT_MILLIS}
     */
    @Test
    @EnableFlags(FLAG_OVERRIDE_POWER_KEY_BEHAVIOR_IN_FOCUSED_WINDOW)
    public void testPowerSinglePress_windowHasOverridePermissionAndKeyHandledByApp() {
        mPhoneWindowManager.overrideCanWindowOverridePowerKey(true);
        setDispatchedKeyHandler(keyEvent -> true);
        mPhoneWindowManager.overrideDisplayState(Display.STATE_ON);
        mPhoneWindowManager.overrideShortPressOnPower(SHORT_PRESS_POWER_GO_TO_SLEEP);

        sendKey(KEYCODE_POWER);

        mPhoneWindowManager.moveTimeForward(POWER_MULTI_PRESS_TIMEOUT_MILLIS);

        mPhoneWindowManager.assertPowerSleep();
    }


    /**
     * Tests 5x press when the window handles the power key. Emergency gesture should still be
     * launched.
     */
    @Test
    @EnableFlags(FLAG_OVERRIDE_POWER_KEY_BEHAVIOR_IN_FOCUSED_WINDOW)
    public void testPowerFiveTimesPress_windowHasOverridePermissionAndKeyHandledByApp() {
        mPhoneWindowManager.overrideCanWindowOverridePowerKey(true);
        setDispatchedKeyHandler(keyEvent -> true);
        mPhoneWindowManager.overrideDisplayState(Display.STATE_ON);
        mPhoneWindowManager.overrideShortPressOnPower(SHORT_PRESS_POWER_GO_TO_SLEEP);

        int minEmergencyGestureDurationMillis = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_defaultMinEmergencyGestureTapDurationMillis);
        int durationMillis = minEmergencyGestureDurationMillis / 4;
        for (int i = 0; i < 5; ++i) {
            sendKey(KEYCODE_POWER);
            mPhoneWindowManager.moveTimeForward(durationMillis);
        }

        mPhoneWindowManager.assertEmergencyLaunch();
        assertEquals(getDownKeysDispatched(), 2);
        assertEquals(getUpKeysDispatched(), 2);
    }
}
