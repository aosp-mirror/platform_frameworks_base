/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.provider.Settings.Global.STEM_PRIMARY_BUTTON_DOUBLE_PRESS;
import static android.provider.Settings.Global.STEM_PRIMARY_BUTTON_LONG_PRESS;
import static android.provider.Settings.Global.STEM_PRIMARY_BUTTON_SHORT_PRESS;
import static android.provider.Settings.Global.STEM_PRIMARY_BUTTON_TRIPLE_PRESS;
import static android.view.KeyEvent.KEYCODE_STEM_PRIMARY;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.server.policy.PhoneWindowManager.DOUBLE_PRESS_PRIMARY_LAUNCH_DEFAULT_FITNESS_APP;
import static com.android.server.policy.PhoneWindowManager.DOUBLE_PRESS_PRIMARY_SWITCH_RECENT_APP;
import static com.android.server.policy.PhoneWindowManager.LONG_PRESS_PRIMARY_LAUNCH_VOICE_ASSISTANT;
import static com.android.server.policy.PhoneWindowManager.SHORT_PRESS_PRIMARY_LAUNCH_ALL_APPS;
import static com.android.server.policy.PhoneWindowManager.SHORT_PRESS_PRIMARY_LAUNCH_TARGET_ACTIVITY;
import static com.android.server.policy.PhoneWindowManager.TRIPLE_PRESS_PRIMARY_TOGGLE_ACCESSIBILITY;

import android.app.ActivityManager.RecentTaskInfo;
import android.app.ActivityTaskManager.RootTaskInfo;
import android.content.ComponentName;
import android.hardware.input.KeyGestureEvent;
import android.os.RemoteException;
import android.provider.Settings;
import android.view.Display;

import org.junit.Before;
import org.junit.Test;

/**
 * Test class for stem key gesture.
 *
 * Build/Install/Run:
 * atest WmTests:StemKeyGestureTests
 */
public class StemKeyGestureTests extends ShortcutKeyTestBase {

    private static final String TEST_TARGET_ACTIVITY = "com.android.server.policy/.TestActivity";

    @Before
    public void setup() {
        super.setup();
        overrideResource(com.android.internal.R.integer.config_longPressOnStemPrimaryBehavior,
                LONG_PRESS_PRIMARY_LAUNCH_VOICE_ASSISTANT);
    }

    /**
     * Stem single key should not launch behavior during set up.
     */
    @Test
    public void stemSingleKey_duringSetup_doNothing() {
        overrideBehavior(STEM_PRIMARY_BUTTON_SHORT_PRESS, SHORT_PRESS_PRIMARY_LAUNCH_ALL_APPS);
        setUpPhoneWindowManager(/* supportSettingsUpdate= */ true);
        mPhoneWindowManager.overrideShouldEarlyShortPressOnStemPrimary(false);
        mPhoneWindowManager.setKeyguardServiceDelegateIsShowing(false);
        mPhoneWindowManager.overrideIsUserSetupComplete(false);

        sendKey(KEYCODE_STEM_PRIMARY);

        mPhoneWindowManager.assertNotOpenAllAppView();
    }

    /**
     * Stem single key should launch all app after set up.
     */
    @Test
    public void stemSingleKey_AfterSetup_openAllApp() {
        overrideBehavior(STEM_PRIMARY_BUTTON_SHORT_PRESS, SHORT_PRESS_PRIMARY_LAUNCH_ALL_APPS);
        setUpPhoneWindowManager(/* supportSettingsUpdate= */ true);
        mPhoneWindowManager.overrideShouldEarlyShortPressOnStemPrimary(false);
        mPhoneWindowManager.overrideStartActivity();
        mPhoneWindowManager.setKeyguardServiceDelegateIsShowing(false);
        mPhoneWindowManager.overrideIsUserSetupComplete(true);

        sendKey(KEYCODE_STEM_PRIMARY);

        mPhoneWindowManager.assertOpenAllAppView();
    }

    /**
     * Stem single key should not launch behavior during set up.
     */
    @Test
    public void stemSingleKey_launchTargetActivity() {
        overrideBehavior(
                STEM_PRIMARY_BUTTON_SHORT_PRESS,
                SHORT_PRESS_PRIMARY_LAUNCH_TARGET_ACTIVITY);
        setUpPhoneWindowManager(/* supportSettingsUpdate= */ true);
        mPhoneWindowManager.overrideShouldEarlyShortPressOnStemPrimary(false);
        mPhoneWindowManager.overrideStartActivity();
        mPhoneWindowManager.setKeyguardServiceDelegateIsShowing(false);
        mPhoneWindowManager.overrideIsUserSetupComplete(true);
        mPhoneWindowManager.assumeResolveActivityNotNull();

        ComponentName targetComponent = ComponentName.unflattenFromString(TEST_TARGET_ACTIVITY);
        mPhoneWindowManager.overrideStemPressTargetActivity(targetComponent);

        sendKey(KEYCODE_STEM_PRIMARY);

        mPhoneWindowManager.assertActivityTargetLaunched(targetComponent);
    }

    @Test
    public void stemSingleKey_launchTargetActivity_whenScreenIsOff() {
        overrideBehavior(
                STEM_PRIMARY_BUTTON_SHORT_PRESS,
                SHORT_PRESS_PRIMARY_LAUNCH_TARGET_ACTIVITY);
        setUpPhoneWindowManager(/* supportSettingsUpdate= */ true);
        mPhoneWindowManager.overrideShouldEarlyShortPressOnStemPrimary(false);
        mPhoneWindowManager.overrideStartActivity();
        mPhoneWindowManager.setKeyguardServiceDelegateIsShowing(false);
        mPhoneWindowManager.overrideIsUserSetupComplete(true);
        mPhoneWindowManager.assumeResolveActivityNotNull();
        mPhoneWindowManager.overrideDisplayState(Display.STATE_OFF);
        ComponentName targetComponent = ComponentName.unflattenFromString(TEST_TARGET_ACTIVITY);
        mPhoneWindowManager.overrideStemPressTargetActivity(targetComponent);
        mPhoneWindowManager.overrideKeyEventPolicyFlags(0);

        sendKey(KEYCODE_STEM_PRIMARY);

        mPhoneWindowManager.assertActivityTargetLaunched(targetComponent);
    }

    @Test
    public void stemSingleKey_appHasOverridePermission_consumedByApp_notOpenAllApp() {
        overrideBehavior(STEM_PRIMARY_BUTTON_SHORT_PRESS, SHORT_PRESS_PRIMARY_LAUNCH_ALL_APPS);
        setUpPhoneWindowManager(/* supportSettingsUpdate= */ true);
        mPhoneWindowManager.overrideStartActivity();
        mPhoneWindowManager.setKeyguardServiceDelegateIsShowing(false);
        mPhoneWindowManager.overrideIsUserSetupComplete(true);
        mPhoneWindowManager.overrideFocusedWindowButtonOverridePermission(true);

        setDispatchedKeyHandler(keyEvent -> true);

        sendKey(KEYCODE_STEM_PRIMARY);

        mPhoneWindowManager.assertNotOpenAllAppView();
    }

    @Test
    public void stemSingleKey_appHasOverridePermission_notConsumedByApp_openAllApp() {
        overrideBehavior(STEM_PRIMARY_BUTTON_SHORT_PRESS, SHORT_PRESS_PRIMARY_LAUNCH_ALL_APPS);
        setUpPhoneWindowManager(/* supportSettingsUpdate= */ true);
        mPhoneWindowManager.overrideStartActivity();
        mPhoneWindowManager.setKeyguardServiceDelegateIsShowing(false);
        mPhoneWindowManager.overrideIsUserSetupComplete(true);
        mPhoneWindowManager.overrideFocusedWindowButtonOverridePermission(true);

        sendKey(KEYCODE_STEM_PRIMARY);

        mPhoneWindowManager.assertOpenAllAppView();
    }

    @Test
    public void stemLongKey_triggerSearchServiceToLaunchAssist() {
        overrideBehavior(
                STEM_PRIMARY_BUTTON_LONG_PRESS,
                LONG_PRESS_PRIMARY_LAUNCH_VOICE_ASSISTANT);
        setUpPhoneWindowManager(/* supportSettingsUpdate= */ true);
        mPhoneWindowManager.overrideShouldEarlyShortPressOnStemPrimary(false);
        mPhoneWindowManager.setupAssistForLaunch();
        mPhoneWindowManager.overrideIsUserSetupComplete(true);

        sendKey(KEYCODE_STEM_PRIMARY, /* longPress= */ true);
        mPhoneWindowManager.assertSearchManagerLaunchAssist();
    }

    @Test
    public void stemLongKey_whenNoSearchService_triggerStatusBarToStartAssist() {
        overrideBehavior(
                STEM_PRIMARY_BUTTON_LONG_PRESS,
                LONG_PRESS_PRIMARY_LAUNCH_VOICE_ASSISTANT);
        setUpPhoneWindowManager(/* supportSettingsUpdate= */ true);
        mPhoneWindowManager.overrideShouldEarlyShortPressOnStemPrimary(false);
        mPhoneWindowManager.setupAssistForLaunch();
        mPhoneWindowManager.overrideSearchManager(null);
        mPhoneWindowManager.overrideStatusBarManagerInternal();
        mPhoneWindowManager.overrideIsUserSetupComplete(true);

        sendKey(KEYCODE_STEM_PRIMARY, /* longPress= */ true);
        mPhoneWindowManager.assertStatusBarStartAssist();
    }

    @Test
    public void stemLongKey_appHasOverridePermission_consumedByApp_triggerStatusBarToStartAssist() {
        overrideBehavior(
                STEM_PRIMARY_BUTTON_LONG_PRESS,
                LONG_PRESS_PRIMARY_LAUNCH_VOICE_ASSISTANT);
        setUpPhoneWindowManager(/* supportSettingsUpdate= */ true);
        mPhoneWindowManager.overrideShouldEarlyShortPressOnStemPrimary(false);
        mPhoneWindowManager.setupAssistForLaunch();
        mPhoneWindowManager.overrideSearchManager(null);
        mPhoneWindowManager.overrideStatusBarManagerInternal();
        mPhoneWindowManager.overrideIsUserSetupComplete(true);
        mPhoneWindowManager.overrideFocusedWindowButtonOverridePermission(true);

        setDispatchedKeyHandler(keyEvent -> true);

        sendKey(KEYCODE_STEM_PRIMARY, /* longPress= */ true);

        mPhoneWindowManager.assertStatusBarStartAssist();
    }

    @Test
    public void stemDoubleKey_EarlyShortPress_AllAppsThenSwitchToMostRecent()
            throws RemoteException {
        overrideBehavior(STEM_PRIMARY_BUTTON_SHORT_PRESS, SHORT_PRESS_PRIMARY_LAUNCH_ALL_APPS);
        overrideBehavior(STEM_PRIMARY_BUTTON_DOUBLE_PRESS, DOUBLE_PRESS_PRIMARY_SWITCH_RECENT_APP);
        setUpPhoneWindowManager(/* supportSettingsUpdate= */ true);
        mPhoneWindowManager.overrideShouldEarlyShortPressOnStemPrimary(true);
        mPhoneWindowManager.setKeyguardServiceDelegateIsShowing(false);
        mPhoneWindowManager.overrideIsUserSetupComplete(true);
        RecentTaskInfo recentTaskInfo = new RecentTaskInfo();
        int referenceId = 666;
        recentTaskInfo.persistentId = referenceId;
        doReturn(recentTaskInfo).when(
                mPhoneWindowManager.mActivityTaskManagerInternal).getMostRecentTaskFromBackground();

        sendKey(KEYCODE_STEM_PRIMARY);
        sendKey(KEYCODE_STEM_PRIMARY);

        mPhoneWindowManager.assertOpenAllAppView();
        mPhoneWindowManager.assertSwitchToTask(referenceId);
    }

    @Test
    public void stemDoubleKey_behaviorIsLaunchFitness_gestureEventFired() {
        overrideBehavior(
                STEM_PRIMARY_BUTTON_DOUBLE_PRESS, DOUBLE_PRESS_PRIMARY_LAUNCH_DEFAULT_FITNESS_APP);
        setUpPhoneWindowManager(/* supportSettingsUpdate= */ true);

        sendKey(KEYCODE_STEM_PRIMARY);
        sendKey(KEYCODE_STEM_PRIMARY);

        mPhoneWindowManager.assertKeyGestureEventSentToKeyGestureController(
                KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_FITNESS);
    }

    @Test
    public void stemTripleKey_EarlyShortPress_AllAppsThenBackToOriginalThenToggleA11y()
            throws RemoteException {
        overrideBehavior(STEM_PRIMARY_BUTTON_SHORT_PRESS, SHORT_PRESS_PRIMARY_LAUNCH_ALL_APPS);
        overrideBehavior(
                STEM_PRIMARY_BUTTON_TRIPLE_PRESS, TRIPLE_PRESS_PRIMARY_TOGGLE_ACCESSIBILITY);
        setUpPhoneWindowManager(/* supportSettingsUpdate= */ true);
        mPhoneWindowManager.overrideShouldEarlyShortPressOnStemPrimary(true);
        mPhoneWindowManager.overrideTalkbackShortcutGestureEnabled(true);
        mPhoneWindowManager.setKeyguardServiceDelegateIsShowing(false);
        mPhoneWindowManager.overrideIsUserSetupComplete(true);
        RootTaskInfo allAppsTask = new RootTaskInfo();
        int referenceId = 777;
        allAppsTask.taskId = referenceId;
        doReturn(allAppsTask)
                .when(mPhoneWindowManager.mActivityManagerService)
                .getFocusedRootTaskInfo();

        mPhoneWindowManager.assertTalkBack(/* expectEnabled= */ false);

        sendKey(KEYCODE_STEM_PRIMARY);
        sendKey(KEYCODE_STEM_PRIMARY);
        sendKey(KEYCODE_STEM_PRIMARY);

        mPhoneWindowManager.assertOpenAllAppView();
        mPhoneWindowManager.assertSwitchToTask(referenceId);
        mPhoneWindowManager.assertTalkBack(/* expectEnabled= */ true);
    }

    @Test
    public void stemMultiKey_NoEarlyPress_NoOpenAllApp() throws RemoteException {
        overrideBehavior(STEM_PRIMARY_BUTTON_SHORT_PRESS, SHORT_PRESS_PRIMARY_LAUNCH_ALL_APPS);
        overrideBehavior(STEM_PRIMARY_BUTTON_DOUBLE_PRESS, SHORT_PRESS_PRIMARY_LAUNCH_ALL_APPS);
        overrideBehavior(
                STEM_PRIMARY_BUTTON_TRIPLE_PRESS, TRIPLE_PRESS_PRIMARY_TOGGLE_ACCESSIBILITY);
        setUpPhoneWindowManager(/* supportSettingsUpdate= */ true);
        mPhoneWindowManager.overrideShouldEarlyShortPressOnStemPrimary(false);
        mPhoneWindowManager.overrideTalkbackShortcutGestureEnabled(true);
        mPhoneWindowManager.setKeyguardServiceDelegateIsShowing(false);
        mPhoneWindowManager.overrideIsUserSetupComplete(true);
        RecentTaskInfo recentTaskInfo = new RecentTaskInfo();
        int referenceId = 666;
        recentTaskInfo.persistentId = referenceId;
        doReturn(recentTaskInfo).when(
                mPhoneWindowManager.mActivityTaskManagerInternal).getMostRecentTaskFromBackground();

        sendKey(KEYCODE_STEM_PRIMARY);
        sendKey(KEYCODE_STEM_PRIMARY);
        sendKey(KEYCODE_STEM_PRIMARY);

        mPhoneWindowManager.assertNotOpenAllAppView();
        mPhoneWindowManager.assertTalkBack(/* expectEnabled= */ true);

        sendKey(KEYCODE_STEM_PRIMARY);
        sendKey(KEYCODE_STEM_PRIMARY);

        mPhoneWindowManager.assertNotOpenAllAppView();
        mPhoneWindowManager.assertSwitchToTask(referenceId);
    }

    @Test
    public void stemDoubleKey_earlyShortPress_firstPressConsumedByApp_switchToMostRecent()
            throws RemoteException {
        overrideBehavior(STEM_PRIMARY_BUTTON_DOUBLE_PRESS, SHORT_PRESS_PRIMARY_LAUNCH_ALL_APPS);
        setUpPhoneWindowManager(/* supportSettingsUpdate= */ true);
        mPhoneWindowManager.overrideShouldEarlyShortPressOnStemPrimary(true);
        mPhoneWindowManager.setKeyguardServiceDelegateIsShowing(false);
        mPhoneWindowManager.overrideIsUserSetupComplete(true);
        mPhoneWindowManager.overrideFocusedWindowButtonOverridePermission(true);
        RecentTaskInfo recentTaskInfo = new RecentTaskInfo();
        int referenceId = 666;
        recentTaskInfo.persistentId = referenceId;
        doReturn(recentTaskInfo).when(
                mPhoneWindowManager.mActivityTaskManagerInternal).getMostRecentTaskFromBackground();

        setDispatchedKeyHandler(keyEvent -> true);
        sendKey(KEYCODE_STEM_PRIMARY);
        setDispatchedKeyHandler(keyEvent -> false);
        sendKey(KEYCODE_STEM_PRIMARY);

        mPhoneWindowManager.assertNotOpenAllAppView();
        mPhoneWindowManager.assertSwitchToTask(referenceId);
    }

    /**
     * Ensure the stem rule is added even when button behaviors are set to nothing.
     *
     * This makes sure that if stem key behaviors are overridden to NOTHING, then we check the
     * XML config as the source of truth upon reboot to see whether a device should have a stem
     * key rule. This test walks us through a scenario where a device powers off during Wear's
     * Touch Lock mode.
     */
    @Test
    public void stemKeyRuleIsAddedEvenWhenBehaviorsRemoved() {
        // deactivate stem button presses
        overrideBehavior(STEM_PRIMARY_BUTTON_SHORT_PRESS,
                PhoneWindowManager.SHORT_PRESS_PRIMARY_NOTHING);
        overrideBehavior(STEM_PRIMARY_BUTTON_DOUBLE_PRESS,
                PhoneWindowManager.DOUBLE_PRESS_PRIMARY_NOTHING);
        overrideBehavior(STEM_PRIMARY_BUTTON_TRIPLE_PRESS,
                PhoneWindowManager.TRIPLE_PRESS_PRIMARY_NOTHING);
        overrideBehavior(STEM_PRIMARY_BUTTON_LONG_PRESS,
                PhoneWindowManager.LONG_PRESS_PRIMARY_NOTHING);

        // pretend like we have stem keys enabled in the xmls
        overrideResource(
                com.android.internal.R.integer.config_shortPressOnStemPrimaryBehavior,
                SHORT_PRESS_PRIMARY_LAUNCH_ALL_APPS);

        // start the PhoneWindowManager, just like would happen with a reboot
        setUpPhoneWindowManager(/* supportSettingsUpdate= */ true);
        // Set the stem behavior back to something normal after boot
        overrideBehavior(STEM_PRIMARY_BUTTON_SHORT_PRESS,
                SHORT_PRESS_PRIMARY_LAUNCH_ALL_APPS);
        // manually trigger the SettingsObserver's onChange() method because subclasses of
        // ShortcutKeyTestBase cannot automatically pick up Settings changes.
        triggerSettingsObserverChange();

        // These calls are required to make the All Apps view show up
        mPhoneWindowManager.overrideShouldEarlyShortPressOnStemPrimary(false);
        mPhoneWindowManager.overrideStartActivity();
        mPhoneWindowManager.setKeyguardServiceDelegateIsShowing(false);
        mPhoneWindowManager.overrideIsUserSetupComplete(true);

        sendKey(KEYCODE_STEM_PRIMARY);

        // Because the rule was loaded and we changed the behavior back to non-zero, PWM should
        // actually perform this action. It would not perform the action if the rule was missing.
        mPhoneWindowManager.assertOpenAllAppView();
    }

    /**
     * Ensure the stem rule is not added when stem behavior is not defined in the xml.
     *
     * This is the opposite of the test above.
     */
    @Test
    public void stemKeyRuleIsNotAddedWhenXmlDoesntDefineIt() {
        // deactivate stem button presses
        overrideBehavior(STEM_PRIMARY_BUTTON_SHORT_PRESS,
                PhoneWindowManager.SHORT_PRESS_PRIMARY_NOTHING);
        overrideBehavior(STEM_PRIMARY_BUTTON_DOUBLE_PRESS,
                PhoneWindowManager.DOUBLE_PRESS_PRIMARY_NOTHING);
        overrideBehavior(STEM_PRIMARY_BUTTON_TRIPLE_PRESS,
                PhoneWindowManager.TRIPLE_PRESS_PRIMARY_NOTHING);
        overrideBehavior(STEM_PRIMARY_BUTTON_LONG_PRESS,
                PhoneWindowManager.LONG_PRESS_PRIMARY_NOTHING);

        // pretend like we do not have stem keys enabled in the xmls
        overrideResource(
                com.android.internal.R.integer.config_shortPressOnStemPrimaryBehavior,
                PhoneWindowManager.SHORT_PRESS_PRIMARY_NOTHING);
        overrideResource(
                com.android.internal.R.integer.config_longPressOnStemPrimaryBehavior,
                PhoneWindowManager.LONG_PRESS_PRIMARY_NOTHING);

        // start the PhoneWindowManager, just like would happen with a reboot
        setUpPhoneWindowManager(/* supportSettingsUpdate= */ true);
        // Set the stem behavior back to something normal after boot
        // (Despite this fact, a stem press shouldn't have any behavior because there's no rule.)
        overrideBehavior(STEM_PRIMARY_BUTTON_SHORT_PRESS,
                SHORT_PRESS_PRIMARY_LAUNCH_ALL_APPS);
        // manually trigger the SettingsObserver's onChange() method because subclasses of
        // ShortcutKeyTestBase cannot automatically pick up Settings changes.
        triggerSettingsObserverChange();

        // These calls are required to make the All Apps view show up
        mPhoneWindowManager.overrideShouldEarlyShortPressOnStemPrimary(false);
        mPhoneWindowManager.overrideStartActivity();
        mPhoneWindowManager.setKeyguardServiceDelegateIsShowing(false);
        mPhoneWindowManager.overrideIsUserSetupComplete(true);

        sendKey(KEYCODE_STEM_PRIMARY);

        // Because the rule was not loaded, PWM should not actually perform this action, even
        // though the Settings override is set to non-null.
        mPhoneWindowManager.assertNotOpenAllAppView();
    }

    private void overrideBehavior(String key, int expectedBehavior) {
        Settings.Global.putLong(mContext.getContentResolver(), key, expectedBehavior);
    }
}
