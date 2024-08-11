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

import static com.android.server.policy.PhoneWindowManager.DOUBLE_TAP_HOME_RECENT_SYSTEM_UI;
import static com.android.server.policy.PhoneWindowManager.LONG_PRESS_HOME_ALL_APPS;
import static com.android.server.policy.PhoneWindowManager.LONG_PRESS_HOME_ASSIST;
import static com.android.server.policy.PhoneWindowManager.LONG_PRESS_HOME_NOTIFICATION_PANEL;
import static com.android.server.policy.PhoneWindowManager.SETTINGS_KEY_BEHAVIOR_NOTIFICATION_PANEL;

import android.hardware.input.KeyboardSystemShortcut;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.view.KeyEvent;

import androidx.test.filters.MediumTest;

import com.android.internal.annotations.Keep;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@MediumTest
@RunWith(JUnitParamsRunner.class)
public class KeyboardSystemShortcutTests extends ShortcutKeyTestBase {

    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();

    private static final int META_KEY = KeyEvent.KEYCODE_META_LEFT;
    private static final int META_ON = MODIFIER.get(KeyEvent.KEYCODE_META_LEFT);
    private static final int ALT_KEY = KeyEvent.KEYCODE_ALT_LEFT;
    private static final int ALT_ON = MODIFIER.get(KeyEvent.KEYCODE_ALT_LEFT);
    private static final int CTRL_KEY = KeyEvent.KEYCODE_CTRL_LEFT;
    private static final int CTRL_ON = MODIFIER.get(KeyEvent.KEYCODE_CTRL_LEFT);
    private static final int SHIFT_KEY = KeyEvent.KEYCODE_SHIFT_LEFT;
    private static final int SHIFT_ON = MODIFIER.get(KeyEvent.KEYCODE_SHIFT_LEFT);

    @Keep
    private static Object[][] shortcutTestArguments() {
        // testName, testKeys, expectedSystemShortcut, expectedKey, expectedModifierState
        return new Object[][]{
                {"Meta + H -> Open Home", new int[]{META_KEY, KeyEvent.KEYCODE_H},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_HOME, KeyEvent.KEYCODE_H, META_ON},
                {"Meta + Enter -> Open Home", new int[]{META_KEY, KeyEvent.KEYCODE_ENTER},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_HOME, KeyEvent.KEYCODE_ENTER,
                        META_ON},
                {"HOME key -> Open Home", new int[]{KeyEvent.KEYCODE_HOME},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_HOME,
                        KeyEvent.KEYCODE_HOME, 0},
                {"RECENT_APPS key -> Open Overview", new int[]{KeyEvent.KEYCODE_RECENT_APPS},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_RECENT_APPS,
                        KeyEvent.KEYCODE_RECENT_APPS, 0},
                {"Meta + Tab -> Open Overview", new int[]{META_KEY, KeyEvent.KEYCODE_TAB},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_RECENT_APPS, KeyEvent.KEYCODE_TAB,
                        META_ON},
                {"Alt + Tab -> Open Overview", new int[]{ALT_KEY, KeyEvent.KEYCODE_TAB},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_RECENT_APPS, KeyEvent.KEYCODE_TAB,
                        ALT_ON},
                {"BACK key -> Go back", new int[]{KeyEvent.KEYCODE_BACK},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_BACK,
                        KeyEvent.KEYCODE_BACK, 0},
                {"Meta + Escape -> Go back", new int[]{META_KEY, KeyEvent.KEYCODE_ESCAPE},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_BACK, KeyEvent.KEYCODE_ESCAPE,
                        META_ON},
                {"Meta + Left arrow -> Go back", new int[]{META_KEY, KeyEvent.KEYCODE_DPAD_LEFT},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_BACK, KeyEvent.KEYCODE_DPAD_LEFT,
                        META_ON},
                {"Meta + Del -> Go back", new int[]{META_KEY, KeyEvent.KEYCODE_DEL},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_BACK, KeyEvent.KEYCODE_DEL, META_ON},
                {"APP_SWITCH key -> Open App switcher", new int[]{KeyEvent.KEYCODE_APP_SWITCH},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_APP_SWITCH,
                        KeyEvent.KEYCODE_APP_SWITCH, 0},
                {"ASSIST key -> Launch assistant", new int[]{KeyEvent.KEYCODE_ASSIST},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_LAUNCH_ASSISTANT,
                        KeyEvent.KEYCODE_ASSIST, 0},
                {"Meta + A -> Launch assistant", new int[]{META_KEY, KeyEvent.KEYCODE_A},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_LAUNCH_ASSISTANT, KeyEvent.KEYCODE_A,
                        META_ON},
                {"VOICE_ASSIST key -> Launch Voice Assistant",
                        new int[]{KeyEvent.KEYCODE_VOICE_ASSIST},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_LAUNCH_VOICE_ASSISTANT,
                        KeyEvent.KEYCODE_VOICE_ASSIST, 0},
                {"Meta + I -> Launch System Settings", new int[]{META_KEY, KeyEvent.KEYCODE_I},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_LAUNCH_SYSTEM_SETTINGS,
                        KeyEvent.KEYCODE_I, META_ON},
                {"Meta + N -> Toggle Notification panel", new int[]{META_KEY, KeyEvent.KEYCODE_N},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_TOGGLE_NOTIFICATION_PANEL,
                        KeyEvent.KEYCODE_N, META_ON},
                {"NOTIFICATION key -> Toggle Notification Panel",
                        new int[]{KeyEvent.KEYCODE_NOTIFICATION},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_TOGGLE_NOTIFICATION_PANEL,
                        KeyEvent.KEYCODE_NOTIFICATION,
                        0},
                {"Meta + Ctrl + S -> Take Screenshot",
                        new int[]{META_KEY, CTRL_KEY, KeyEvent.KEYCODE_S},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_TAKE_SCREENSHOT, KeyEvent.KEYCODE_S,
                        META_ON | CTRL_ON},
                {"Meta + / -> Open Shortcut Helper", new int[]{META_KEY, KeyEvent.KEYCODE_SLASH},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_OPEN_SHORTCUT_HELPER,
                        KeyEvent.KEYCODE_SLASH, META_ON},
                {"BRIGHTNESS_UP key -> Increase Brightness",
                        new int[]{KeyEvent.KEYCODE_BRIGHTNESS_UP},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_BRIGHTNESS_UP,
                        KeyEvent.KEYCODE_BRIGHTNESS_UP, 0},
                {"BRIGHTNESS_DOWN key -> Decrease Brightness",
                        new int[]{KeyEvent.KEYCODE_BRIGHTNESS_DOWN},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_BRIGHTNESS_DOWN,
                        KeyEvent.KEYCODE_BRIGHTNESS_DOWN, 0},
                {"KEYBOARD_BACKLIGHT_UP key -> Increase Keyboard Backlight",
                        new int[]{KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_UP},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_KEYBOARD_BACKLIGHT_UP,
                        KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_UP, 0},
                {"KEYBOARD_BACKLIGHT_DOWN key -> Decrease Keyboard Backlight",
                        new int[]{KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_DOWN},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_KEYBOARD_BACKLIGHT_DOWN,
                        KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_DOWN, 0},
                {"KEYBOARD_BACKLIGHT_TOGGLE key -> Toggle Keyboard Backlight",
                        new int[]{KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_TOGGLE},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_KEYBOARD_BACKLIGHT_TOGGLE,
                        KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_TOGGLE, 0},
                {"VOLUME_UP key -> Increase Volume", new int[]{KeyEvent.KEYCODE_VOLUME_UP},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_VOLUME_UP,
                        KeyEvent.KEYCODE_VOLUME_UP, 0},
                {"VOLUME_DOWN key -> Decrease Volume", new int[]{KeyEvent.KEYCODE_VOLUME_DOWN},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_VOLUME_DOWN,
                        KeyEvent.KEYCODE_VOLUME_DOWN, 0},
                {"VOLUME_MUTE key -> Mute Volume", new int[]{KeyEvent.KEYCODE_VOLUME_MUTE},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_VOLUME_MUTE,
                        KeyEvent.KEYCODE_VOLUME_MUTE, 0},
                {"ALL_APPS key -> Open App Drawer in Accessibility mode",
                        new int[]{KeyEvent.KEYCODE_ALL_APPS},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_ACCESSIBILITY_ALL_APPS,
                        KeyEvent.KEYCODE_ALL_APPS, 0},
                {"SEARCH key -> Launch Search Activity", new int[]{KeyEvent.KEYCODE_SEARCH},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_LAUNCH_SEARCH,
                        KeyEvent.KEYCODE_SEARCH, 0},
                {"LANGUAGE_SWITCH key -> Switch Keyboard Language",
                        new int[]{KeyEvent.KEYCODE_LANGUAGE_SWITCH},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_LANGUAGE_SWITCH,
                        KeyEvent.KEYCODE_LANGUAGE_SWITCH, 0},
                {"META key -> Open App Drawer in Accessibility mode", new int[]{META_KEY},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_ACCESSIBILITY_ALL_APPS, META_KEY,
                        META_ON},
                {"Meta + Alt -> Toggle CapsLock", new int[]{META_KEY, ALT_KEY},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_TOGGLE_CAPS_LOCK, ALT_KEY,
                        META_ON | ALT_ON},
                {"Alt + Meta -> Toggle CapsLock", new int[]{ALT_KEY, META_KEY},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_TOGGLE_CAPS_LOCK, META_KEY,
                        META_ON | ALT_ON},
                {"CAPS_LOCK key -> Toggle CapsLock", new int[]{KeyEvent.KEYCODE_CAPS_LOCK},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_TOGGLE_CAPS_LOCK,
                        KeyEvent.KEYCODE_CAPS_LOCK, 0},
                {"MUTE key -> Mute System Microphone", new int[]{KeyEvent.KEYCODE_MUTE},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_SYSTEM_MUTE, KeyEvent.KEYCODE_MUTE,
                        0},
                {"Meta + Ctrl + DPAD_UP -> Split screen navigation",
                        new int[]{META_KEY, CTRL_KEY, KeyEvent.KEYCODE_DPAD_UP},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_MULTI_WINDOW_NAVIGATION,
                        KeyEvent.KEYCODE_DPAD_UP,
                        META_ON | CTRL_ON},
                {"Meta + Ctrl + DPAD_LEFT -> Split screen navigation",
                        new int[]{META_KEY, CTRL_KEY, KeyEvent.KEYCODE_DPAD_LEFT},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_SPLIT_SCREEN_NAVIGATION,
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        META_ON | CTRL_ON},
                {"Meta + Ctrl + DPAD_RIGHT -> Split screen navigation",
                        new int[]{META_KEY, CTRL_KEY, KeyEvent.KEYCODE_DPAD_RIGHT},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_SPLIT_SCREEN_NAVIGATION,
                        KeyEvent.KEYCODE_DPAD_RIGHT,
                        META_ON | CTRL_ON},
                {"Meta + L -> Lock Homescreen", new int[]{META_KEY, KeyEvent.KEYCODE_L},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_LOCK_SCREEN, KeyEvent.KEYCODE_L,
                        META_ON},
                {"Meta + Ctrl + N -> Open Notes", new int[]{META_KEY, CTRL_KEY, KeyEvent.KEYCODE_N},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_OPEN_NOTES, KeyEvent.KEYCODE_N,
                        META_ON | CTRL_ON},
                {"POWER key -> Toggle Power", new int[]{KeyEvent.KEYCODE_POWER},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_TOGGLE_POWER, KeyEvent.KEYCODE_POWER,
                        0},
                {"TV_POWER key -> Toggle Power", new int[]{KeyEvent.KEYCODE_TV_POWER},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_TOGGLE_POWER,
                        KeyEvent.KEYCODE_TV_POWER, 0},
                {"SYSTEM_NAVIGATION_DOWN key -> System Navigation",
                        new int[]{KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_SYSTEM_NAVIGATION,
                        KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN,
                        0},
                {"SYSTEM_NAVIGATION_UP key -> System Navigation",
                        new int[]{KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_SYSTEM_NAVIGATION,
                        KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP,
                        0},
                {"SYSTEM_NAVIGATION_LEFT key -> System Navigation",
                        new int[]{KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_SYSTEM_NAVIGATION,
                        KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT,
                        0},
                {"SYSTEM_NAVIGATION_RIGHT key -> System Navigation",
                        new int[]{KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_SYSTEM_NAVIGATION,
                        KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT, 0},
                {"SLEEP key -> System Sleep", new int[]{KeyEvent.KEYCODE_SLEEP},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_SLEEP, KeyEvent.KEYCODE_SLEEP, 0},
                {"SOFT_SLEEP key -> System Sleep", new int[]{KeyEvent.KEYCODE_SOFT_SLEEP},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_SLEEP, KeyEvent.KEYCODE_SOFT_SLEEP,
                        0},
                {"WAKEUP key -> System Wakeup", new int[]{KeyEvent.KEYCODE_WAKEUP},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_WAKEUP, KeyEvent.KEYCODE_WAKEUP, 0},
                {"MEDIA_PLAY key -> Media Control", new int[]{KeyEvent.KEYCODE_MEDIA_PLAY},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_MEDIA_KEY,
                        KeyEvent.KEYCODE_MEDIA_PLAY, 0},
                {"MEDIA_PAUSE key -> Media Control", new int[]{KeyEvent.KEYCODE_MEDIA_PAUSE},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_MEDIA_KEY,
                        KeyEvent.KEYCODE_MEDIA_PAUSE, 0},
                {"MEDIA_PLAY_PAUSE key -> Media Control",
                        new int[]{KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_MEDIA_KEY,
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0},
                {"Meta + B -> Launch Default Browser", new int[]{META_KEY, KeyEvent.KEYCODE_B},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_LAUNCH_DEFAULT_BROWSER,
                        KeyEvent.KEYCODE_B, META_ON},
                {"EXPLORER key -> Launch Default Browser", new int[]{KeyEvent.KEYCODE_EXPLORER},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_LAUNCH_DEFAULT_BROWSER,
                        KeyEvent.KEYCODE_EXPLORER, 0},
                {"Meta + C -> Launch Default Contacts", new int[]{META_KEY, KeyEvent.KEYCODE_C},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_LAUNCH_DEFAULT_CONTACTS,
                        KeyEvent.KEYCODE_C, META_ON},
                {"CONTACTS key -> Launch Default Contacts", new int[]{KeyEvent.KEYCODE_CONTACTS},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_LAUNCH_DEFAULT_CONTACTS,
                        KeyEvent.KEYCODE_CONTACTS, 0},
                {"Meta + E -> Launch Default Email", new int[]{META_KEY, KeyEvent.KEYCODE_E},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_LAUNCH_DEFAULT_EMAIL,
                        KeyEvent.KEYCODE_E, META_ON},
                {"ENVELOPE key -> Launch Default Email", new int[]{KeyEvent.KEYCODE_ENVELOPE},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_LAUNCH_DEFAULT_EMAIL,
                        KeyEvent.KEYCODE_ENVELOPE, 0},
                {"Meta + K -> Launch Default Calendar", new int[]{META_KEY, KeyEvent.KEYCODE_K},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_LAUNCH_DEFAULT_CALENDAR,
                        KeyEvent.KEYCODE_K, META_ON},
                {"CALENDAR key -> Launch Default Calendar", new int[]{KeyEvent.KEYCODE_CALENDAR},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_LAUNCH_DEFAULT_CALENDAR,
                        KeyEvent.KEYCODE_CALENDAR, 0},
                {"Meta + P -> Launch Default Music", new int[]{META_KEY, KeyEvent.KEYCODE_P},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_LAUNCH_DEFAULT_MUSIC,
                        KeyEvent.KEYCODE_P, META_ON},
                {"MUSIC key -> Launch Default Music", new int[]{KeyEvent.KEYCODE_MUSIC},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_LAUNCH_DEFAULT_MUSIC,
                        KeyEvent.KEYCODE_MUSIC, 0},
                {"Meta + U -> Launch Default Calculator", new int[]{META_KEY, KeyEvent.KEYCODE_U},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_LAUNCH_DEFAULT_CALCULATOR,
                        KeyEvent.KEYCODE_U, META_ON},
                {"CALCULATOR key -> Launch Default Calculator",
                        new int[]{KeyEvent.KEYCODE_CALCULATOR},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_LAUNCH_DEFAULT_CALCULATOR,
                        KeyEvent.KEYCODE_CALCULATOR, 0},
                {"Meta + M -> Launch Default Maps", new int[]{META_KEY, KeyEvent.KEYCODE_M},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_LAUNCH_DEFAULT_MAPS,
                        KeyEvent.KEYCODE_M, META_ON},
                {"Meta + S -> Launch Default Messaging App",
                        new int[]{META_KEY, KeyEvent.KEYCODE_S},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_LAUNCH_DEFAULT_MESSAGING,
                        KeyEvent.KEYCODE_S, META_ON},
                {"Meta + Ctrl + DPAD_DOWN -> Enter desktop mode",
                        new int[]{META_KEY, CTRL_KEY, KeyEvent.KEYCODE_DPAD_DOWN},
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_DESKTOP_MODE,
                        KeyEvent.KEYCODE_DPAD_DOWN,
                        META_ON | CTRL_ON}};
    }

    @Keep
    private static Object[][] longPressOnHomeTestArguments() {
        // testName, testKeys, longPressOnHomeBehavior, expectedSystemShortcut, expectedKey,
        // expectedModifierState
        return new Object[][]{
                {"Long press HOME key -> Toggle Notification panel",
                        new int[]{KeyEvent.KEYCODE_HOME}, LONG_PRESS_HOME_NOTIFICATION_PANEL,
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_TOGGLE_NOTIFICATION_PANEL,
                        KeyEvent.KEYCODE_HOME, 0},
                {"Long press META + ENTER -> Toggle Notification panel",
                        new int[]{META_KEY, KeyEvent.KEYCODE_ENTER},
                        LONG_PRESS_HOME_NOTIFICATION_PANEL,
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_TOGGLE_NOTIFICATION_PANEL,
                        KeyEvent.KEYCODE_ENTER,
                        META_ON},
                {"Long press META + H -> Toggle Notification panel",
                        new int[]{META_KEY, KeyEvent.KEYCODE_H}, LONG_PRESS_HOME_NOTIFICATION_PANEL,
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_TOGGLE_NOTIFICATION_PANEL,
                        KeyEvent.KEYCODE_H, META_ON},
                {"Long press HOME key -> Launch assistant",
                        new int[]{KeyEvent.KEYCODE_HOME}, LONG_PRESS_HOME_ASSIST,
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_LAUNCH_ASSISTANT,
                        KeyEvent.KEYCODE_HOME, 0},
                {"Long press META + ENTER -> Launch assistant",
                        new int[]{META_KEY, KeyEvent.KEYCODE_ENTER}, LONG_PRESS_HOME_ASSIST,
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_LAUNCH_ASSISTANT,
                        KeyEvent.KEYCODE_ENTER, META_ON},
                {"Long press META + H -> Launch assistant",
                        new int[]{META_KEY, KeyEvent.KEYCODE_H}, LONG_PRESS_HOME_ASSIST,
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_LAUNCH_ASSISTANT, KeyEvent.KEYCODE_H,
                        META_ON},
                {"Long press HOME key -> Open App Drawer in Accessibility mode",
                        new int[]{KeyEvent.KEYCODE_HOME}, LONG_PRESS_HOME_ALL_APPS,
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_ACCESSIBILITY_ALL_APPS,
                        KeyEvent.KEYCODE_HOME, 0},
                {"Long press META + ENTER -> Open App Drawer in Accessibility mode",
                        new int[]{META_KEY, KeyEvent.KEYCODE_ENTER}, LONG_PRESS_HOME_ALL_APPS,
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_ACCESSIBILITY_ALL_APPS,
                        KeyEvent.KEYCODE_ENTER, META_ON},
                {"Long press META + H -> Open App Drawer in Accessibility mode",
                        new int[]{META_KEY, KeyEvent.KEYCODE_H},
                        LONG_PRESS_HOME_ALL_APPS,
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_ACCESSIBILITY_ALL_APPS,
                        KeyEvent.KEYCODE_H, META_ON}};
    }

    @Keep
    private static Object[][] doubleTapOnHomeTestArguments() {
        // testName, testKeys, doubleTapOnHomeBehavior, expectedSystemShortcut, expectedKey,
        // expectedModifierState
        return new Object[][]{
                {"Double tap HOME -> Open App switcher",
                        new int[]{KeyEvent.KEYCODE_HOME}, DOUBLE_TAP_HOME_RECENT_SYSTEM_UI,
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_APP_SWITCH, KeyEvent.KEYCODE_HOME,
                        0},
                {"Double tap META + ENTER -> Open App switcher",
                        new int[]{META_KEY, KeyEvent.KEYCODE_ENTER},
                        DOUBLE_TAP_HOME_RECENT_SYSTEM_UI,
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_APP_SWITCH,
                        KeyEvent.KEYCODE_ENTER, META_ON},
                {"Double tap META + H -> Open App switcher",
                        new int[]{META_KEY, KeyEvent.KEYCODE_H}, DOUBLE_TAP_HOME_RECENT_SYSTEM_UI,
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_APP_SWITCH, KeyEvent.KEYCODE_H,
                        META_ON}};
    }

    @Keep
    private static Object[][] settingsKeyTestArguments() {
        // testName, testKeys, settingsKeyBehavior, expectedSystemShortcut, expectedKey,
        // expectedModifierState
        return new Object[][]{
                {"SETTINGS key -> Toggle Notification panel", new int[]{KeyEvent.KEYCODE_SETTINGS},
                        SETTINGS_KEY_BEHAVIOR_NOTIFICATION_PANEL,
                        KeyboardSystemShortcut.SYSTEM_SHORTCUT_TOGGLE_NOTIFICATION_PANEL,
                        KeyEvent.KEYCODE_SETTINGS, 0}};
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
    }

    @Test
    @Parameters(method = "shortcutTestArguments")
    public void testShortcut(String testName, int[] testKeys,
            @KeyboardSystemShortcut.SystemShortcut int expectedSystemShortcut, int expectedKey,
            int expectedModifierState) {
        testShortcutInternal(testName, testKeys, expectedSystemShortcut, expectedKey,
                expectedModifierState);
    }

    @Test
    @Parameters(method = "longPressOnHomeTestArguments")
    public void testLongPressOnHome(String testName, int[] testKeys, int longPressOnHomeBehavior,
            @KeyboardSystemShortcut.SystemShortcut int expectedSystemShortcut, int expectedKey,
            int expectedModifierState) {
        mPhoneWindowManager.overrideLongPressOnHomeBehavior(longPressOnHomeBehavior);
        sendLongPressKeyCombination(testKeys);
        mPhoneWindowManager.assertKeyboardShortcutTriggered(
                new int[]{expectedKey}, expectedModifierState, expectedSystemShortcut,
                "Failed while executing " + testName);
    }

    @Test
    @Parameters(method = "doubleTapOnHomeTestArguments")
    public void testDoubleTapOnHomeBehavior(String testName, int[] testKeys,
            int doubleTapOnHomeBehavior,
            @KeyboardSystemShortcut.SystemShortcut int expectedSystemShortcut, int expectedKey,
            int expectedModifierState) {
        mPhoneWindowManager.overriderDoubleTapOnHomeBehavior(doubleTapOnHomeBehavior);
        sendKeyCombination(testKeys, 0 /* duration */);
        sendKeyCombination(testKeys, 0 /* duration */);
        mPhoneWindowManager.assertKeyboardShortcutTriggered(
                new int[]{expectedKey}, expectedModifierState, expectedSystemShortcut,
                "Failed while executing " + testName);
    }

    @Test
    @Parameters(method = "settingsKeyTestArguments")
    public void testSettingsKey(String testName, int[] testKeys, int settingsKeyBehavior,
            @KeyboardSystemShortcut.SystemShortcut int expectedSystemShortcut, int expectedKey,
            int expectedModifierState) {
        mPhoneWindowManager.overrideSettingsKeyBehavior(settingsKeyBehavior);
        testShortcutInternal(testName, testKeys, expectedSystemShortcut, expectedKey,
                expectedModifierState);
    }

    @Test
    @RequiresFlagsEnabled(com.android.server.flags.Flags.FLAG_NEW_BUGREPORT_KEYBOARD_SHORTCUT)
    public void testBugreportShortcutPress() {
        testShortcutInternal("Meta + Ctrl + Del -> Trigger bug report",
                new int[]{META_KEY, CTRL_KEY, KeyEvent.KEYCODE_DEL},
                KeyboardSystemShortcut.SYSTEM_SHORTCUT_TRIGGER_BUG_REPORT, KeyEvent.KEYCODE_DEL,
                META_ON | CTRL_ON);
    }

    private void testShortcutInternal(String testName, int[] testKeys,
            @KeyboardSystemShortcut.SystemShortcut int expectedSystemShortcut, int expectedKey,
            int expectedModifierState) {
        sendKeyCombination(testKeys, 0 /* duration */);
        mPhoneWindowManager.assertKeyboardShortcutTriggered(
                new int[]{expectedKey}, expectedModifierState, expectedSystemShortcut,
                "Failed while executing " + testName);
    }
}
