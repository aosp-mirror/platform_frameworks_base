/*
 * Copyright 2023 The Android Open Source Project
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

import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.server.policy.PhoneWindowManager.DOUBLE_TAP_HOME_RECENT_SYSTEM_UI;
import static com.android.server.policy.PhoneWindowManager.LONG_PRESS_HOME_ALL_APPS;
import static com.android.server.policy.PhoneWindowManager.LONG_PRESS_HOME_ASSIST;
import static com.android.server.policy.PhoneWindowManager.LONG_PRESS_HOME_NOTIFICATION_PANEL;
import static com.android.server.policy.PhoneWindowManager.POWER_VOLUME_UP_BEHAVIOR_GLOBAL_ACTIONS;
import static com.android.server.policy.PhoneWindowManager.POWER_VOLUME_UP_BEHAVIOR_MUTE;
import static com.android.server.policy.PhoneWindowManager.SETTINGS_KEY_BEHAVIOR_NOTIFICATION_PANEL;

import android.hardware.input.InputSettings;
import android.hardware.input.KeyGestureEvent;
import android.os.RemoteException;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.view.KeyEvent;

import androidx.test.filters.MediumTest;

import com.android.hardware.input.Flags;
import com.android.internal.annotations.Keep;

import junit.framework.Assert;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@MediumTest
@RunWith(JUnitParamsRunner.class)
public class KeyGestureEventTests extends ShortcutKeyTestBase {

    private static final int META_KEY = KeyEvent.KEYCODE_META_LEFT;
    private static final int META_ON = MODIFIER.get(KeyEvent.KEYCODE_META_LEFT);
    private static final int ALT_KEY = KeyEvent.KEYCODE_ALT_LEFT;
    private static final int ALT_ON = MODIFIER.get(KeyEvent.KEYCODE_ALT_LEFT);
    private static final int CTRL_KEY = KeyEvent.KEYCODE_CTRL_LEFT;
    private static final int CTRL_ON = MODIFIER.get(KeyEvent.KEYCODE_CTRL_LEFT);

    @Keep
    private static Object[][] shortcutTestArgumentsNotMigratedToKeyGestureController() {
        // testName, testKeys, expectedKeyGestureType, expectedKey, expectedModifierState
        return new Object[][]{
                {"HOME key -> Open Home", new int[]{KeyEvent.KEYCODE_HOME},
                        KeyGestureEvent.KEY_GESTURE_TYPE_HOME,
                        KeyEvent.KEYCODE_HOME, 0},
                {"BACK key -> Go back", new int[]{KeyEvent.KEYCODE_BACK},
                        KeyGestureEvent.KEY_GESTURE_TYPE_BACK,
                        KeyEvent.KEYCODE_BACK, 0},
                {"VOLUME_UP key -> Increase Volume", new int[]{KeyEvent.KEYCODE_VOLUME_UP},
                        KeyGestureEvent.KEY_GESTURE_TYPE_VOLUME_UP,
                        KeyEvent.KEYCODE_VOLUME_UP, 0},
                {"VOLUME_DOWN key -> Decrease Volume", new int[]{KeyEvent.KEYCODE_VOLUME_DOWN},
                        KeyGestureEvent.KEY_GESTURE_TYPE_VOLUME_DOWN,
                        KeyEvent.KEYCODE_VOLUME_DOWN, 0},
                {"VOLUME_MUTE key -> Mute Volume", new int[]{KeyEvent.KEYCODE_VOLUME_MUTE},
                        KeyGestureEvent.KEY_GESTURE_TYPE_VOLUME_MUTE,
                        KeyEvent.KEYCODE_VOLUME_MUTE, 0},
                {"MUTE key -> Mute System Microphone", new int[]{KeyEvent.KEYCODE_MUTE},
                        KeyGestureEvent.KEY_GESTURE_TYPE_SYSTEM_MUTE, KeyEvent.KEYCODE_MUTE,
                        0},
                {"POWER key -> Toggle Power", new int[]{KeyEvent.KEYCODE_POWER},
                        KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_POWER, KeyEvent.KEYCODE_POWER,
                        0},
                {"TV_POWER key -> Toggle Power", new int[]{KeyEvent.KEYCODE_TV_POWER},
                        KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_POWER,
                        KeyEvent.KEYCODE_TV_POWER, 0},
                {"SYSTEM_NAVIGATION_DOWN key -> System Navigation",
                        new int[]{KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN},
                        KeyGestureEvent.KEY_GESTURE_TYPE_SYSTEM_NAVIGATION,
                        KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN,
                        0},
                {"SYSTEM_NAVIGATION_UP key -> System Navigation",
                        new int[]{KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP},
                        KeyGestureEvent.KEY_GESTURE_TYPE_SYSTEM_NAVIGATION,
                        KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP,
                        0},
                {"SYSTEM_NAVIGATION_LEFT key -> System Navigation",
                        new int[]{KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT},
                        KeyGestureEvent.KEY_GESTURE_TYPE_SYSTEM_NAVIGATION,
                        KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT,
                        0},
                {"SYSTEM_NAVIGATION_RIGHT key -> System Navigation",
                        new int[]{KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT},
                        KeyGestureEvent.KEY_GESTURE_TYPE_SYSTEM_NAVIGATION,
                        KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT, 0},
                {"SLEEP key -> System Sleep", new int[]{KeyEvent.KEYCODE_SLEEP},
                        KeyGestureEvent.KEY_GESTURE_TYPE_SLEEP, KeyEvent.KEYCODE_SLEEP, 0},
                {"SOFT_SLEEP key -> System Sleep", new int[]{KeyEvent.KEYCODE_SOFT_SLEEP},
                        KeyGestureEvent.KEY_GESTURE_TYPE_SLEEP, KeyEvent.KEYCODE_SOFT_SLEEP,
                        0},
                {"WAKEUP key -> System Wakeup", new int[]{KeyEvent.KEYCODE_WAKEUP},
                        KeyGestureEvent.KEY_GESTURE_TYPE_WAKEUP, KeyEvent.KEYCODE_WAKEUP, 0},
                {"MEDIA_PLAY key -> Media Control", new int[]{KeyEvent.KEYCODE_MEDIA_PLAY},
                        KeyGestureEvent.KEY_GESTURE_TYPE_MEDIA_KEY,
                        KeyEvent.KEYCODE_MEDIA_PLAY, 0},
                {"MEDIA_PAUSE key -> Media Control", new int[]{KeyEvent.KEYCODE_MEDIA_PAUSE},
                        KeyGestureEvent.KEY_GESTURE_TYPE_MEDIA_KEY,
                        KeyEvent.KEYCODE_MEDIA_PAUSE, 0},
                {"MEDIA_PLAY_PAUSE key -> Media Control",
                        new int[]{KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE},
                        KeyGestureEvent.KEY_GESTURE_TYPE_MEDIA_KEY,
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0}};
    }

    @Keep
    private static Object[][] shortcutTestArgumentsMigratedToKeyGestureController() {
        // testName, testKeys, expectedKeyGestureType, expectedKey, expectedModifierState
        return new Object[][]{
                {"Meta + H -> Open Home", new int[]{META_KEY, KeyEvent.KEYCODE_H},
                        KeyGestureEvent.KEY_GESTURE_TYPE_HOME, KeyEvent.KEYCODE_H, META_ON},
                {"Meta + Enter -> Open Home", new int[]{META_KEY, KeyEvent.KEYCODE_ENTER},
                        KeyGestureEvent.KEY_GESTURE_TYPE_HOME, KeyEvent.KEYCODE_ENTER,
                        META_ON},
                {"RECENT_APPS key -> Open Overview", new int[]{KeyEvent.KEYCODE_RECENT_APPS},
                        KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS,
                        KeyEvent.KEYCODE_RECENT_APPS, 0},
                {"Meta + Tab -> Open Overview", new int[]{META_KEY, KeyEvent.KEYCODE_TAB},
                        KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS, KeyEvent.KEYCODE_TAB,
                        META_ON},
                {"Alt + Tab -> Open Overview", new int[]{ALT_KEY, KeyEvent.KEYCODE_TAB},
                        KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS, KeyEvent.KEYCODE_TAB,
                        ALT_ON},
                {"Meta + Escape -> Go back", new int[]{META_KEY, KeyEvent.KEYCODE_ESCAPE},
                        KeyGestureEvent.KEY_GESTURE_TYPE_BACK, KeyEvent.KEYCODE_ESCAPE,
                        META_ON},
                {"Meta + Left arrow -> Go back", new int[]{META_KEY, KeyEvent.KEYCODE_DPAD_LEFT},
                        KeyGestureEvent.KEY_GESTURE_TYPE_BACK, KeyEvent.KEYCODE_DPAD_LEFT,
                        META_ON},
                {"Meta + Del -> Go back", new int[]{META_KEY, KeyEvent.KEYCODE_DEL},
                        KeyGestureEvent.KEY_GESTURE_TYPE_BACK, KeyEvent.KEYCODE_DEL, META_ON},
                {"APP_SWITCH key -> Open App switcher", new int[]{KeyEvent.KEYCODE_APP_SWITCH},
                        KeyGestureEvent.KEY_GESTURE_TYPE_APP_SWITCH,
                        KeyEvent.KEYCODE_APP_SWITCH, 0},
                {"ASSIST key -> Launch assistant", new int[]{KeyEvent.KEYCODE_ASSIST},
                        KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_ASSISTANT,
                        KeyEvent.KEYCODE_ASSIST, 0},
                {"Meta + A -> Launch assistant", new int[]{META_KEY, KeyEvent.KEYCODE_A},
                        KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_ASSISTANT, KeyEvent.KEYCODE_A,
                        META_ON},
                {"VOICE_ASSIST key -> Launch Voice Assistant",
                        new int[]{KeyEvent.KEYCODE_VOICE_ASSIST},
                        KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_VOICE_ASSISTANT,
                        KeyEvent.KEYCODE_VOICE_ASSIST, 0},
                {"Meta + I -> Launch System Settings", new int[]{META_KEY, KeyEvent.KEYCODE_I},
                        KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_SYSTEM_SETTINGS,
                        KeyEvent.KEYCODE_I, META_ON},
                {"Meta + N -> Toggle Notification panel", new int[]{META_KEY, KeyEvent.KEYCODE_N},
                        KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL,
                        KeyEvent.KEYCODE_N, META_ON},
                {"NOTIFICATION key -> Toggle Notification Panel",
                        new int[]{KeyEvent.KEYCODE_NOTIFICATION},
                        KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL,
                        KeyEvent.KEYCODE_NOTIFICATION,
                        0},
                {"Meta + Ctrl + S -> Take Screenshot",
                        new int[]{META_KEY, CTRL_KEY, KeyEvent.KEYCODE_S},
                        KeyGestureEvent.KEY_GESTURE_TYPE_TAKE_SCREENSHOT, KeyEvent.KEYCODE_S,
                        META_ON | CTRL_ON},
                {"Meta + / -> Open Shortcut Helper", new int[]{META_KEY, KeyEvent.KEYCODE_SLASH},
                        KeyGestureEvent.KEY_GESTURE_TYPE_OPEN_SHORTCUT_HELPER,
                        KeyEvent.KEYCODE_SLASH, META_ON},
                {"BRIGHTNESS_UP key -> Increase Brightness",
                        new int[]{KeyEvent.KEYCODE_BRIGHTNESS_UP},
                        KeyGestureEvent.KEY_GESTURE_TYPE_BRIGHTNESS_UP,
                        KeyEvent.KEYCODE_BRIGHTNESS_UP, 0},
                {"BRIGHTNESS_DOWN key -> Decrease Brightness",
                        new int[]{KeyEvent.KEYCODE_BRIGHTNESS_DOWN},
                        KeyGestureEvent.KEY_GESTURE_TYPE_BRIGHTNESS_DOWN,
                        KeyEvent.KEYCODE_BRIGHTNESS_DOWN, 0},
                {"KEYBOARD_BACKLIGHT_UP key -> Increase Keyboard Backlight",
                        new int[]{KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_UP},
                        KeyGestureEvent.KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_UP,
                        KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_UP, 0},
                {"KEYBOARD_BACKLIGHT_DOWN key -> Decrease Keyboard Backlight",
                        new int[]{KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_DOWN},
                        KeyGestureEvent.KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_DOWN,
                        KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_DOWN, 0},
                {"KEYBOARD_BACKLIGHT_TOGGLE key -> Toggle Keyboard Backlight",
                        new int[]{KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_TOGGLE},
                        KeyGestureEvent.KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_TOGGLE,
                        KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_TOGGLE, 0},
                {"ALL_APPS key -> Open App Drawer",
                        new int[]{KeyEvent.KEYCODE_ALL_APPS},
                        KeyGestureEvent.KEY_GESTURE_TYPE_ALL_APPS,
                        KeyEvent.KEYCODE_ALL_APPS, 0},
                {"SEARCH key -> Launch Search Activity", new int[]{KeyEvent.KEYCODE_SEARCH},
                        KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_SEARCH,
                        KeyEvent.KEYCODE_SEARCH, 0},
                {"LANGUAGE_SWITCH key -> Switch Keyboard Language",
                        new int[]{KeyEvent.KEYCODE_LANGUAGE_SWITCH},
                        KeyGestureEvent.KEY_GESTURE_TYPE_LANGUAGE_SWITCH,
                        KeyEvent.KEYCODE_LANGUAGE_SWITCH, 0},
                {"META key -> Open App Drawer", new int[]{META_KEY},
                        KeyGestureEvent.KEY_GESTURE_TYPE_ALL_APPS, META_KEY,
                        META_ON},
                {"Meta + Alt -> Toggle CapsLock", new int[]{META_KEY, ALT_KEY},
                        KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_CAPS_LOCK, ALT_KEY,
                        META_ON | ALT_ON},
                {"Alt + Meta -> Toggle CapsLock", new int[]{ALT_KEY, META_KEY},
                        KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_CAPS_LOCK, META_KEY,
                        META_ON | ALT_ON},
                {"CAPS_LOCK key -> Toggle CapsLock", new int[]{KeyEvent.KEYCODE_CAPS_LOCK},
                        KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_CAPS_LOCK,
                        KeyEvent.KEYCODE_CAPS_LOCK, 0},
                {"Meta + Ctrl + DPAD_UP -> Split screen navigation",
                        new int[]{META_KEY, CTRL_KEY, KeyEvent.KEYCODE_DPAD_UP},
                        KeyGestureEvent.KEY_GESTURE_TYPE_MULTI_WINDOW_NAVIGATION,
                        KeyEvent.KEYCODE_DPAD_UP,
                        META_ON | CTRL_ON},
                {"Meta + Ctrl + DPAD_LEFT -> Split screen navigation",
                        new int[]{META_KEY, CTRL_KEY, KeyEvent.KEYCODE_DPAD_LEFT},
                        KeyGestureEvent.KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_LEFT,
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        META_ON | CTRL_ON},
                {"Meta + Ctrl + DPAD_RIGHT -> Split screen navigation",
                        new int[]{META_KEY, CTRL_KEY, KeyEvent.KEYCODE_DPAD_RIGHT},
                        KeyGestureEvent.KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_RIGHT,
                        KeyEvent.KEYCODE_DPAD_RIGHT,
                        META_ON | CTRL_ON},
                {"Meta + L -> Lock Homescreen", new int[]{META_KEY, KeyEvent.KEYCODE_L},
                        KeyGestureEvent.KEY_GESTURE_TYPE_LOCK_SCREEN, KeyEvent.KEYCODE_L,
                        META_ON},
                {"Meta + Ctrl + N -> Open Notes", new int[]{META_KEY, CTRL_KEY, KeyEvent.KEYCODE_N},
                        KeyGestureEvent.KEY_GESTURE_TYPE_OPEN_NOTES, KeyEvent.KEYCODE_N,
                        META_ON | CTRL_ON},
                {"Meta + Ctrl + DPAD_DOWN -> Enter desktop mode",
                        new int[]{META_KEY, CTRL_KEY, KeyEvent.KEYCODE_DPAD_DOWN},
                        KeyGestureEvent.KEY_GESTURE_TYPE_DESKTOP_MODE,
                        KeyEvent.KEYCODE_DPAD_DOWN,
                        META_ON | CTRL_ON},
                {"Meta + B -> Launch Default Browser", new int[]{META_KEY, KeyEvent.KEYCODE_B},
                        KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_BROWSER,
                        KeyEvent.KEYCODE_B, META_ON},
                {"EXPLORER key -> Launch Default Browser", new int[]{KeyEvent.KEYCODE_EXPLORER},
                        KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_BROWSER,
                        KeyEvent.KEYCODE_EXPLORER, 0},
                {"Meta + P -> Launch Default Contacts", new int[]{META_KEY, KeyEvent.KEYCODE_P},
                        KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CONTACTS,
                        KeyEvent.KEYCODE_P, META_ON},
                {"CONTACTS key -> Launch Default Contacts", new int[]{KeyEvent.KEYCODE_CONTACTS},
                        KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CONTACTS,
                        KeyEvent.KEYCODE_CONTACTS, 0},
                {"Meta + E -> Launch Default Email", new int[]{META_KEY, KeyEvent.KEYCODE_E},
                        KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_EMAIL,
                        KeyEvent.KEYCODE_E, META_ON},
                {"ENVELOPE key -> Launch Default Email", new int[]{KeyEvent.KEYCODE_ENVELOPE},
                        KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_EMAIL,
                        KeyEvent.KEYCODE_ENVELOPE, 0},
                {"Meta + C -> Launch Default Calendar", new int[]{META_KEY, KeyEvent.KEYCODE_C},
                        KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALENDAR,
                        KeyEvent.KEYCODE_C, META_ON},
                {"CALENDAR key -> Launch Default Calendar", new int[]{KeyEvent.KEYCODE_CALENDAR},
                        KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALENDAR,
                        KeyEvent.KEYCODE_CALENDAR, 0},
                {"MUSIC key -> Launch Default Music", new int[]{KeyEvent.KEYCODE_MUSIC},
                        KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MUSIC,
                        KeyEvent.KEYCODE_MUSIC, 0},
                {"Meta + U -> Launch Default Calculator", new int[]{META_KEY, KeyEvent.KEYCODE_U},
                        KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALCULATOR,
                        KeyEvent.KEYCODE_U, META_ON},
                {"CALCULATOR key -> Launch Default Calculator",
                        new int[]{KeyEvent.KEYCODE_CALCULATOR},
                        KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALCULATOR,
                        KeyEvent.KEYCODE_CALCULATOR, 0},
                {"Meta + M -> Launch Default Maps", new int[]{META_KEY, KeyEvent.KEYCODE_M},
                        KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MAPS,
                        KeyEvent.KEYCODE_M, META_ON}};
    }

    @Keep
    private static Object[][] longPressOnHomeTestArguments() {
        // testName, testKeys, longPressOnHomeBehavior, expectedKeyGestureType, expectedKey,
        // expectedModifierState
        return new Object[][]{
                {"Long press HOME key -> Toggle Notification panel",
                        new int[]{KeyEvent.KEYCODE_HOME}, LONG_PRESS_HOME_NOTIFICATION_PANEL,
                        KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL,
                        KeyEvent.KEYCODE_HOME, 0},
                {"Long press HOME key -> Launch assistant",
                        new int[]{KeyEvent.KEYCODE_HOME}, LONG_PRESS_HOME_ASSIST,
                        KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_ASSISTANT,
                        KeyEvent.KEYCODE_HOME, 0},
                {"Long press HOME key -> Open App Drawer",
                        new int[]{KeyEvent.KEYCODE_HOME}, LONG_PRESS_HOME_ALL_APPS,
                        KeyGestureEvent.KEY_GESTURE_TYPE_ALL_APPS,
                        KeyEvent.KEYCODE_HOME, 0}};
    }

    @Before
    public void setUp() {
        setUpPhoneWindowManager(/*supportSettingsUpdate*/ true);
        mPhoneWindowManager.overrideLaunchHome();
        mPhoneWindowManager.overrideSearchKeyBehavior(
                PhoneWindowManager.SEARCH_KEY_BEHAVIOR_TARGET_ACTIVITY);
        mPhoneWindowManager.overrideEnableBugReportTrigger(true);
        mPhoneWindowManager.overrideStatusBarManagerInternal();
        mPhoneWindowManager.overrideStartActivity();
        mPhoneWindowManager.overrideSendBroadcast();
        mPhoneWindowManager.overrideUserSetupComplete();
        mPhoneWindowManager.setupAssistForLaunch();
        mPhoneWindowManager.overrideTogglePanel();
        mPhoneWindowManager.overrideInjectKeyEvent();
        mPhoneWindowManager.overrideRoleManager();
    }

    @Test
    @Parameters(method = "shortcutTestArgumentsNotMigratedToKeyGestureController")
    public void testShortcuts_notMigratedToKeyGestureController(String testName,
            int[] testKeys, @KeyGestureEvent.KeyGestureType int expectedKeyGestureType,
            int expectedKey, int expectedModifierState) {
        testShortcutInternal(testName, testKeys, expectedKeyGestureType, expectedKey,
                expectedModifierState);
    }

    @Test
    @Parameters(method = "shortcutTestArgumentsMigratedToKeyGestureController")
    @DisableFlags(com.android.hardware.input.Flags.FLAG_USE_KEY_GESTURE_EVENT_HANDLER)
    public void testShortcuts_migratedToKeyGestureController(String testName, int[] testKeys,
            @KeyGestureEvent.KeyGestureType int expectedKeyGestureType, int expectedKey,
            int expectedModifierState) {
        testShortcutInternal(testName, testKeys, expectedKeyGestureType, expectedKey,
                expectedModifierState);
    }

    @Test
    @Parameters(method = "longPressOnHomeTestArguments")
    public void testLongPressOnHome(String testName, int[] testKeys, int longPressOnHomeBehavior,
            @KeyGestureEvent.KeyGestureType int expectedKeyGestureType, int expectedKey,
            int expectedModifierState) {
        mPhoneWindowManager.overrideLongPressOnHomeBehavior(longPressOnHomeBehavior);
        sendLongPressKeyCombination(testKeys);
        mPhoneWindowManager.assertKeyGestureCompleted(
                new int[]{expectedKey}, expectedModifierState, expectedKeyGestureType,
                "Failed while executing " + testName);
    }

    @Test
    public void testDoubleTapOnHomeBehavior_AppSwitchBehavior() {
        mPhoneWindowManager.overriderDoubleTapOnHomeBehavior(DOUBLE_TAP_HOME_RECENT_SYSTEM_UI);
        sendKeyCombination(new int[]{KeyEvent.KEYCODE_HOME}, 0 /* duration */);
        sendKeyCombination(new int[]{KeyEvent.KEYCODE_HOME}, 0 /* duration */);
        mPhoneWindowManager.assertKeyGestureCompleted(
                new int[]{KeyEvent.KEYCODE_HOME}, /* modifierState = */0,
                KeyGestureEvent.KEY_GESTURE_TYPE_APP_SWITCH,
                "Failed while executing Double tap HOME -> Open App switcher");
    }

    @Test
    @DisableFlags(com.android.hardware.input.Flags.FLAG_USE_KEY_GESTURE_EVENT_HANDLER)
    public void testSettingsKey_ToggleNotificationBehavior() {
        mPhoneWindowManager.overrideSettingsKeyBehavior(SETTINGS_KEY_BEHAVIOR_NOTIFICATION_PANEL);
        testShortcutInternal("SETTINGS key -> Toggle Notification panel",
                new int[]{KeyEvent.KEYCODE_SETTINGS},
                KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL,
                KeyEvent.KEYCODE_SETTINGS, 0);
    }

    @Test
    @EnableFlags(com.android.server.flags.Flags.FLAG_NEW_BUGREPORT_KEYBOARD_SHORTCUT)
    @DisableFlags(com.android.hardware.input.Flags.FLAG_USE_KEY_GESTURE_EVENT_HANDLER)
    public void testBugreportShortcutPress() {
        testShortcutInternal("Meta + Ctrl + Del -> Trigger bug report",
                new int[]{META_KEY, CTRL_KEY, KeyEvent.KEYCODE_DEL},
                KeyGestureEvent.KEY_GESTURE_TYPE_TRIGGER_BUG_REPORT, KeyEvent.KEYCODE_DEL,
                META_ON | CTRL_ON);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_TALKBACK_AND_MAGNIFIER_KEY_GESTURES)
    @DisableFlags(com.android.hardware.input.Flags.FLAG_USE_KEY_GESTURE_EVENT_HANDLER)
    public void testToggleTalkbackPress() {
        testShortcutInternal("Meta + Alt + T -> Toggle talkback",
                new int[]{META_KEY, ALT_KEY, KeyEvent.KEYCODE_T},
                KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_TALKBACK,
                KeyEvent.KEYCODE_T,
                META_ON | ALT_ON);
    }

    @Test
    @EnableFlags({com.android.hardware.input.Flags.FLAG_KEYBOARD_A11Y_SHORTCUT_CONTROL,
            com.android.hardware.input.Flags.FLAG_KEYBOARD_A11Y_BOUNCE_KEYS_FLAG,
            com.android.hardware.input.Flags.FLAG_KEYBOARD_A11Y_SLOW_KEYS_FLAG,
            com.android.hardware.input.Flags.FLAG_KEYBOARD_A11Y_STICKY_KEYS_FLAG,
            com.android.hardware.input.Flags.FLAG_KEYBOARD_A11Y_MOUSE_KEYS})
    @DisableFlags(com.android.hardware.input.Flags.FLAG_USE_KEY_GESTURE_EVENT_HANDLER)
    public void testKeyboardAccessibilityToggleShortcutPress() {
        testShortcutInternal("Meta + Alt + 3 -> Toggle Bounce Keys",
                new int[]{META_KEY, ALT_KEY, KeyEvent.KEYCODE_3},
                KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_BOUNCE_KEYS,
                KeyEvent.KEYCODE_3,
                META_ON | ALT_ON);
        testShortcutInternal("Meta + Alt + 4 -> Toggle Mouse Keys",
                new int[]{META_KEY, ALT_KEY, KeyEvent.KEYCODE_4},
                KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_MOUSE_KEYS,
                KeyEvent.KEYCODE_4,
                META_ON | ALT_ON);
        testShortcutInternal("Meta + Alt + 5 -> Toggle Sticky Keys",
                new int[]{META_KEY, ALT_KEY, KeyEvent.KEYCODE_5},
                KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_STICKY_KEYS,
                KeyEvent.KEYCODE_5,
                META_ON | ALT_ON);
        testShortcutInternal("Meta + Alt + 6 -> Toggle Slow Keys",
                new int[]{META_KEY, ALT_KEY, KeyEvent.KEYCODE_6},
                KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_SLOW_KEYS,
                KeyEvent.KEYCODE_6,
                META_ON | ALT_ON);
    }

    private void testShortcutInternal(String testName, int[] testKeys,
            @KeyGestureEvent.KeyGestureType int expectedKeyGestureType, int expectedKey,
            int expectedModifierState) {
        sendKeyCombination(testKeys, 0 /* duration */);
        mPhoneWindowManager.assertKeyGestureCompleted(
                new int[]{expectedKey}, expectedModifierState, expectedKeyGestureType,
                "Failed while executing " + testName);
    }

    @Test
    public void testKeyGestureRecentApps() {
        Assert.assertTrue(
                sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS));
        mPhoneWindowManager.assertShowRecentApps();
    }

    @Test
    public void testKeyGestureAppSwitch() {
        Assert.assertTrue(
                sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_APP_SWITCH));
        mPhoneWindowManager.assertToggleRecentApps();
    }

    @Test
    public void testKeyGestureLaunchAssistant() {
        Assert.assertTrue(
                sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_ASSISTANT));
        mPhoneWindowManager.assertSearchManagerLaunchAssist();
    }

    @Test
    public void testKeyGestureLaunchVoiceAssistant() {
        Assert.assertTrue(
                sendKeyGestureEventComplete(
                        KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_VOICE_ASSISTANT));
        mPhoneWindowManager.assertSearchManagerLaunchAssist();
    }

    @Test
    public void testKeyGestureGoHome() {
        Assert.assertTrue(
                sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_HOME));
        mPhoneWindowManager.assertGoToHomescreen();
    }

    @Test
    public void testKeyGestureLaunchSystemSettings() {
        Assert.assertTrue(
                sendKeyGestureEventComplete(
                        KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_SYSTEM_SETTINGS));
        mPhoneWindowManager.assertLaunchSystemSettings();
    }

    @Test
    public void testKeyGestureLock() {
        Assert.assertTrue(
                sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_LOCK_SCREEN));
        mPhoneWindowManager.assertLockedAfterAppTransitionFinished();
    }

    @Test
    public void testKeyGestureToggleNotificationPanel() throws RemoteException {
        Assert.assertTrue(
                sendKeyGestureEventComplete(
                        KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL));
        mPhoneWindowManager.assertTogglePanel();
    }

    @Test
    public void testKeyGestureScreenshot() {
        Assert.assertTrue(
                sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_TAKE_SCREENSHOT));
        mPhoneWindowManager.assertTakeScreenshotCalled();
    }

    @Test
    public void testKeyGestureTriggerBugReport() throws RemoteException {
        Assert.assertTrue(
                sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_TRIGGER_BUG_REPORT));
        mPhoneWindowManager.assertTakeBugreport(true);
    }

    @Test
    public void testKeyGestureBack() {
        Assert.assertTrue(sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_BACK));
        mPhoneWindowManager.assertBackEventInjected();
    }

    @Test
    public void testKeyGestureMultiWindowNavigation() {
        Assert.assertTrue(sendKeyGestureEventComplete(
                KeyGestureEvent.KEY_GESTURE_TYPE_MULTI_WINDOW_NAVIGATION));
        mPhoneWindowManager.assertMoveFocusedTaskToFullscreen();
    }

    @Test
    public void testKeyGestureDesktopMode() {
        Assert.assertTrue(
                sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_DESKTOP_MODE));
        mPhoneWindowManager.assertMoveFocusedTaskToDesktop();
    }

    @Test
    public void testKeyGestureSplitscreenNavigation() {
        Assert.assertTrue(sendKeyGestureEventComplete(
                KeyGestureEvent.KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_LEFT));
        mPhoneWindowManager.assertMoveFocusedTaskToStageSplit(true);

        Assert.assertTrue(sendKeyGestureEventComplete(
                KeyGestureEvent.KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_RIGHT));
        mPhoneWindowManager.assertMoveFocusedTaskToStageSplit(false);
    }

    @Test
    public void testKeyGestureSplitscreenFocus() {
        Assert.assertTrue(sendKeyGestureEventComplete(
                KeyGestureEvent.KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_LEFT));
        mPhoneWindowManager.assertSetSplitscreenFocus(true);

        Assert.assertTrue(sendKeyGestureEventComplete(
                KeyGestureEvent.KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_RIGHT));
        mPhoneWindowManager.assertSetSplitscreenFocus(false);
    }

    @Test
    public void testKeyGestureShortcutHelper() {
        Assert.assertTrue(sendKeyGestureEventComplete(
                KeyGestureEvent.KEY_GESTURE_TYPE_OPEN_SHORTCUT_HELPER));
        mPhoneWindowManager.assertToggleShortcutsMenu();
    }

    @Test
    public void testKeyGestureBrightnessChange() {
        float[] currentBrightness = new float[]{0.1f, 0.05f, 0.0f};
        float[] newBrightness = new float[]{0.065738f, 0.0275134f, 0.0f};

        for (int i = 0; i < currentBrightness.length; i++) {
            mPhoneWindowManager.prepareBrightnessDecrease(currentBrightness[i]);
            Assert.assertTrue(
                    sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_BRIGHTNESS_DOWN));
            mPhoneWindowManager.verifyNewBrightness(newBrightness[i]);
        }
    }

    @Test
    public void testKeyGestureRecentAppSwitcher() {
        Assert.assertTrue(sendKeyGestureEventStart(
                KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS_SWITCHER));
        mPhoneWindowManager.assertShowRecentApps();

        Assert.assertTrue(sendKeyGestureEventComplete(
                KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS_SWITCHER));
        mPhoneWindowManager.assertHideRecentApps();
    }

    @Test
    public void testKeyGestureLanguageSwitch() {
        Assert.assertTrue(
                sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_LANGUAGE_SWITCH));
        mPhoneWindowManager.assertSwitchKeyboardLayout(1, DEFAULT_DISPLAY);

        Assert.assertTrue(
                sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_LANGUAGE_SWITCH,
                        KeyEvent.META_SHIFT_ON));
        mPhoneWindowManager.assertSwitchKeyboardLayout(-1, DEFAULT_DISPLAY);
    }

    @Test
    public void testKeyGestureLaunchSearch() {
        Assert.assertTrue(
                sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_SEARCH));
        mPhoneWindowManager.assertLaunchSearch();
    }

    @Test
    public void testKeyGestureScreenshotChord() {
        Assert.assertTrue(
                sendKeyGestureEventStart(KeyGestureEvent.KEY_GESTURE_TYPE_SCREENSHOT_CHORD));
        mPhoneWindowManager.moveTimeForward(500);
        Assert.assertTrue(
                sendKeyGestureEventCancel(KeyGestureEvent.KEY_GESTURE_TYPE_SCREENSHOT_CHORD));
        mPhoneWindowManager.assertTakeScreenshotCalled();
    }

    @Test
    public void testKeyGestureScreenshotChordCancelled() {
        Assert.assertTrue(
                sendKeyGestureEventStart(KeyGestureEvent.KEY_GESTURE_TYPE_SCREENSHOT_CHORD));
        Assert.assertTrue(
                sendKeyGestureEventCancel(KeyGestureEvent.KEY_GESTURE_TYPE_SCREENSHOT_CHORD));
        mPhoneWindowManager.assertTakeScreenshotNotCalled();
    }

    @Test
    public void testKeyGestureAccessibilityShortcutChord() {
        Assert.assertTrue(
                sendKeyGestureEventStart(
                        KeyGestureEvent.KEY_GESTURE_TYPE_ACCESSIBILITY_SHORTCUT_CHORD));
        mPhoneWindowManager.moveTimeForward(5000);
        Assert.assertTrue(
                sendKeyGestureEventCancel(
                        KeyGestureEvent.KEY_GESTURE_TYPE_ACCESSIBILITY_SHORTCUT_CHORD));
        mPhoneWindowManager.assertAccessibilityKeychordCalled();
    }

    @Test
    public void testKeyGestureAccessibilityShortcutChordCancelled() {
        Assert.assertTrue(
                sendKeyGestureEventStart(
                        KeyGestureEvent.KEY_GESTURE_TYPE_ACCESSIBILITY_SHORTCUT_CHORD));
        Assert.assertTrue(
                sendKeyGestureEventCancel(
                        KeyGestureEvent.KEY_GESTURE_TYPE_ACCESSIBILITY_SHORTCUT_CHORD));
        mPhoneWindowManager.assertAccessibilityKeychordNotCalled();
    }

    @Test
    public void testKeyGestureRingerToggleChord() {
        mPhoneWindowManager.overridePowerVolumeUp(POWER_VOLUME_UP_BEHAVIOR_MUTE);
        Assert.assertTrue(
                sendKeyGestureEventStart(KeyGestureEvent.KEY_GESTURE_TYPE_RINGER_TOGGLE_CHORD));
        mPhoneWindowManager.moveTimeForward(500);
        Assert.assertTrue(
                sendKeyGestureEventCancel(KeyGestureEvent.KEY_GESTURE_TYPE_RINGER_TOGGLE_CHORD));
        mPhoneWindowManager.assertVolumeMute();
    }

    @Test
    public void testKeyGestureRingerToggleChordCancelled() {
        mPhoneWindowManager.overridePowerVolumeUp(POWER_VOLUME_UP_BEHAVIOR_MUTE);
        Assert.assertTrue(
                sendKeyGestureEventStart(KeyGestureEvent.KEY_GESTURE_TYPE_RINGER_TOGGLE_CHORD));
        Assert.assertTrue(
                sendKeyGestureEventCancel(KeyGestureEvent.KEY_GESTURE_TYPE_RINGER_TOGGLE_CHORD));
        mPhoneWindowManager.assertVolumeNotMuted();
    }

    @Test
    public void testKeyGestureGlobalAction() {
        mPhoneWindowManager.overridePowerVolumeUp(POWER_VOLUME_UP_BEHAVIOR_GLOBAL_ACTIONS);
        Assert.assertTrue(
                sendKeyGestureEventStart(KeyGestureEvent.KEY_GESTURE_TYPE_GLOBAL_ACTIONS));
        mPhoneWindowManager.moveTimeForward(500);
        Assert.assertTrue(
                sendKeyGestureEventCancel(KeyGestureEvent.KEY_GESTURE_TYPE_GLOBAL_ACTIONS));
        mPhoneWindowManager.assertShowGlobalActionsCalled();
    }

    @Test
    public void testKeyGestureGlobalActionCancelled() {
        mPhoneWindowManager.overridePowerVolumeUp(POWER_VOLUME_UP_BEHAVIOR_GLOBAL_ACTIONS);
        Assert.assertTrue(
                sendKeyGestureEventStart(KeyGestureEvent.KEY_GESTURE_TYPE_GLOBAL_ACTIONS));
        Assert.assertTrue(
                sendKeyGestureEventCancel(KeyGestureEvent.KEY_GESTURE_TYPE_GLOBAL_ACTIONS));
        mPhoneWindowManager.assertShowGlobalActionsNotCalled();
    }

    @Test
    public void testKeyGestureAccessibilityTvShortcutChord() {
        Assert.assertTrue(
                sendKeyGestureEventStart(
                        KeyGestureEvent.KEY_GESTURE_TYPE_TV_ACCESSIBILITY_SHORTCUT_CHORD));
        mPhoneWindowManager.moveTimeForward(5000);
        Assert.assertTrue(
                sendKeyGestureEventCancel(
                        KeyGestureEvent.KEY_GESTURE_TYPE_TV_ACCESSIBILITY_SHORTCUT_CHORD));
        mPhoneWindowManager.assertAccessibilityKeychordCalled();
    }

    @Test
    public void testKeyGestureAccessibilityTvShortcutChordCancelled() {
        Assert.assertTrue(
                sendKeyGestureEventStart(
                        KeyGestureEvent.KEY_GESTURE_TYPE_TV_ACCESSIBILITY_SHORTCUT_CHORD));
        Assert.assertTrue(
                sendKeyGestureEventCancel(
                        KeyGestureEvent.KEY_GESTURE_TYPE_TV_ACCESSIBILITY_SHORTCUT_CHORD));
        mPhoneWindowManager.assertAccessibilityKeychordNotCalled();
    }

    @Test
    public void testKeyGestureTvTriggerBugReport() {
        Assert.assertTrue(
                sendKeyGestureEventStart(KeyGestureEvent.KEY_GESTURE_TYPE_TV_TRIGGER_BUG_REPORT));
        mPhoneWindowManager.moveTimeForward(1000);
        Assert.assertTrue(
                sendKeyGestureEventCancel(KeyGestureEvent.KEY_GESTURE_TYPE_TV_TRIGGER_BUG_REPORT));
        mPhoneWindowManager.assertBugReportTakenForTv();
    }

    @Test
    public void testKeyGestureTvTriggerBugReportCancelled() {
        Assert.assertTrue(
                sendKeyGestureEventStart(KeyGestureEvent.KEY_GESTURE_TYPE_TV_TRIGGER_BUG_REPORT));
        Assert.assertTrue(
                sendKeyGestureEventCancel(KeyGestureEvent.KEY_GESTURE_TYPE_TV_TRIGGER_BUG_REPORT));
        mPhoneWindowManager.assertBugReportNotTakenForTv();
    }

    @Test
    public void testKeyGestureAccessibilityShortcut() {
        Assert.assertTrue(
                sendKeyGestureEventComplete(
                        KeyGestureEvent.KEY_GESTURE_TYPE_ACCESSIBILITY_SHORTCUT));
        mPhoneWindowManager.assertAccessibilityKeychordCalled();
    }

    @Test
    public void testKeyGestureCloseAllDialogs() {
        Assert.assertTrue(
                sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_CLOSE_ALL_DIALOGS));
        mPhoneWindowManager.assertCloseAllDialogs();
    }

    @Test
    @EnableFlags(com.android.hardware.input.Flags.FLAG_ENABLE_TALKBACK_AND_MAGNIFIER_KEY_GESTURES)
    public void testKeyGestureToggleTalkback() {
        Assert.assertTrue(
                sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_TALKBACK));
        mPhoneWindowManager.assertTalkBack(true);

        Assert.assertTrue(
                sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_TALKBACK));
        mPhoneWindowManager.assertTalkBack(false);
    }

    @Test
    @EnableFlags({com.android.hardware.input.Flags.FLAG_KEYBOARD_A11Y_SHORTCUT_CONTROL,
            com.android.hardware.input.Flags.FLAG_KEYBOARD_A11Y_STICKY_KEYS_FLAG})
    public void testKeyGestureToggleStickyKeys() {
        Assert.assertTrue(
                sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_STICKY_KEYS));
        Assert.assertTrue(InputSettings.isAccessibilityStickyKeysEnabled(mContext));

        Assert.assertTrue(
                sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_STICKY_KEYS));
        Assert.assertFalse(InputSettings.isAccessibilityStickyKeysEnabled(mContext));
    }

    @Test
    @EnableFlags({com.android.hardware.input.Flags.FLAG_KEYBOARD_A11Y_SHORTCUT_CONTROL,
            com.android.hardware.input.Flags.FLAG_KEYBOARD_A11Y_SLOW_KEYS_FLAG})
    public void testKeyGestureToggleSlowKeys() {
        Assert.assertTrue(
                sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_SLOW_KEYS));
        Assert.assertTrue(InputSettings.isAccessibilitySlowKeysEnabled(mContext));

        Assert.assertTrue(
                sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_SLOW_KEYS));
        Assert.assertFalse(InputSettings.isAccessibilitySlowKeysEnabled(mContext));
    }

    @Test
    @EnableFlags({com.android.hardware.input.Flags.FLAG_KEYBOARD_A11Y_SHORTCUT_CONTROL,
            com.android.hardware.input.Flags.FLAG_KEYBOARD_A11Y_MOUSE_KEYS})
    public void testKeyGestureToggleMouseKeys() {
        Assert.assertTrue(
                sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_MOUSE_KEYS));
        Assert.assertTrue(InputSettings.isAccessibilityMouseKeysEnabled(mContext));

        Assert.assertTrue(
                sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_MOUSE_KEYS));
        Assert.assertFalse(InputSettings.isAccessibilityMouseKeysEnabled(mContext));
    }

    @Test
    @EnableFlags({com.android.hardware.input.Flags.FLAG_KEYBOARD_A11Y_SHORTCUT_CONTROL,
            com.android.hardware.input.Flags.FLAG_KEYBOARD_A11Y_BOUNCE_KEYS_FLAG})
    public void testKeyGestureToggleBounceKeys() {
        Assert.assertTrue(
                sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_BOUNCE_KEYS));
        Assert.assertTrue(InputSettings.isAccessibilityBounceKeysEnabled(mContext));

        Assert.assertTrue(
                sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_BOUNCE_KEYS));
        Assert.assertFalse(InputSettings.isAccessibilityBounceKeysEnabled(mContext));
    }
}
