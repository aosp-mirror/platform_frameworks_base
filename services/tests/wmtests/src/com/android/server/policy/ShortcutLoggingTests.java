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
import static com.android.server.policy.PhoneWindowManager.SHORT_PRESS_SETTINGS_NOTIFICATION_PANEL;

import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.view.KeyEvent;

import androidx.test.filters.MediumTest;

import com.android.internal.annotations.Keep;
import com.android.server.input.KeyboardMetricsCollector.KeyboardLogEvent;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@MediumTest
@RunWith(JUnitParamsRunner.class)
public class ShortcutLoggingTests extends ShortcutKeyTestBase {

    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();

    private static final int VENDOR_ID = 0x123;
    private static final int PRODUCT_ID = 0x456;
    private static final int DEVICE_BUS = 0x789;
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
        // testName, testKeys, expectedLogEvent, expectedKey, expectedModifierState
        return new Object[][]{
                {"Meta + H -> Open Home", new int[]{META_KEY, KeyEvent.KEYCODE_H},
                        KeyboardLogEvent.HOME, KeyEvent.KEYCODE_H, META_ON},
                {"Meta + Enter -> Open Home", new int[]{META_KEY, KeyEvent.KEYCODE_ENTER},
                        KeyboardLogEvent.HOME, KeyEvent.KEYCODE_ENTER, META_ON},
                {"HOME key -> Open Home", new int[]{KeyEvent.KEYCODE_HOME}, KeyboardLogEvent.HOME,
                        KeyEvent.KEYCODE_HOME, 0},
                {"RECENT_APPS key -> Open Overview", new int[]{KeyEvent.KEYCODE_RECENT_APPS},
                        KeyboardLogEvent.RECENT_APPS, KeyEvent.KEYCODE_RECENT_APPS, 0},
                {"Meta + Tab -> Open OVerview", new int[]{META_KEY, KeyEvent.KEYCODE_TAB},
                        KeyboardLogEvent.RECENT_APPS, KeyEvent.KEYCODE_TAB, META_ON},
                {"Alt + Tab -> Open Overview", new int[]{ALT_KEY, KeyEvent.KEYCODE_TAB},
                        KeyboardLogEvent.RECENT_APPS, KeyEvent.KEYCODE_TAB, ALT_ON},
                {"BACK key -> Go back", new int[]{KeyEvent.KEYCODE_BACK}, KeyboardLogEvent.BACK,
                        KeyEvent.KEYCODE_BACK, 0},
                {"Meta + Escape -> Go back", new int[]{META_KEY, KeyEvent.KEYCODE_ESCAPE},
                        KeyboardLogEvent.BACK, KeyEvent.KEYCODE_ESCAPE, META_ON},
                {"Meta + Left arrow -> Go back", new int[]{META_KEY, KeyEvent.KEYCODE_DPAD_LEFT},
                        KeyboardLogEvent.BACK, KeyEvent.KEYCODE_DPAD_LEFT, META_ON},
                {"Meta + Del -> Go back", new int[]{META_KEY, KeyEvent.KEYCODE_DEL},
                        KeyboardLogEvent.BACK, KeyEvent.KEYCODE_DEL, META_ON},
                {"APP_SWITCH key -> Open App switcher", new int[]{KeyEvent.KEYCODE_APP_SWITCH},
                        KeyboardLogEvent.APP_SWITCH, KeyEvent.KEYCODE_APP_SWITCH, 0},
                {"ASSIST key -> Launch assistant", new int[]{KeyEvent.KEYCODE_ASSIST},
                        KeyboardLogEvent.LAUNCH_ASSISTANT, KeyEvent.KEYCODE_ASSIST, 0},
                {"Meta + A -> Launch assistant", new int[]{META_KEY, KeyEvent.KEYCODE_A},
                        KeyboardLogEvent.LAUNCH_ASSISTANT, KeyEvent.KEYCODE_A, META_ON},
                {"VOICE_ASSIST key -> Launch Voice Assistant",
                        new int[]{KeyEvent.KEYCODE_VOICE_ASSIST},
                        KeyboardLogEvent.LAUNCH_VOICE_ASSISTANT, KeyEvent.KEYCODE_VOICE_ASSIST, 0},
                {"Meta + I -> Launch System Settings", new int[]{META_KEY, KeyEvent.KEYCODE_I},
                        KeyboardLogEvent.LAUNCH_SYSTEM_SETTINGS, KeyEvent.KEYCODE_I, META_ON},
                {"Meta + N -> Toggle Notification panel", new int[]{META_KEY, KeyEvent.KEYCODE_N},
                        KeyboardLogEvent.TOGGLE_NOTIFICATION_PANEL, KeyEvent.KEYCODE_N, META_ON},
                {"NOTIFICATION key -> Toggle Notification Panel",
                        new int[]{KeyEvent.KEYCODE_NOTIFICATION},
                        KeyboardLogEvent.TOGGLE_NOTIFICATION_PANEL, KeyEvent.KEYCODE_NOTIFICATION,
                        0},
                {"Meta + Ctrl + S -> Take Screenshot",
                        new int[]{META_KEY, CTRL_KEY, KeyEvent.KEYCODE_S},
                        KeyboardLogEvent.TAKE_SCREENSHOT, KeyEvent.KEYCODE_S, META_ON | CTRL_ON},
                {"Meta + / -> Open Shortcut Helper", new int[]{META_KEY, KeyEvent.KEYCODE_SLASH},
                        KeyboardLogEvent.OPEN_SHORTCUT_HELPER, KeyEvent.KEYCODE_SLASH, META_ON},
                {"BRIGHTNESS_UP key -> Increase Brightness",
                        new int[]{KeyEvent.KEYCODE_BRIGHTNESS_UP}, KeyboardLogEvent.BRIGHTNESS_UP,
                        KeyEvent.KEYCODE_BRIGHTNESS_UP, 0},
                {"BRIGHTNESS_DOWN key -> Decrease Brightness",
                        new int[]{KeyEvent.KEYCODE_BRIGHTNESS_DOWN},
                        KeyboardLogEvent.BRIGHTNESS_DOWN, KeyEvent.KEYCODE_BRIGHTNESS_DOWN, 0},
                {"KEYBOARD_BACKLIGHT_UP key -> Increase Keyboard Backlight",
                        new int[]{KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_UP},
                        KeyboardLogEvent.KEYBOARD_BACKLIGHT_UP,
                        KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_UP, 0},
                {"KEYBOARD_BACKLIGHT_DOWN key -> Decrease Keyboard Backlight",
                        new int[]{KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_DOWN},
                        KeyboardLogEvent.KEYBOARD_BACKLIGHT_DOWN,
                        KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_DOWN, 0},
                {"KEYBOARD_BACKLIGHT_TOGGLE key -> Toggle Keyboard Backlight",
                        new int[]{KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_TOGGLE},
                        KeyboardLogEvent.KEYBOARD_BACKLIGHT_TOGGLE,
                        KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_TOGGLE, 0},
                {"VOLUME_UP key -> Increase Volume", new int[]{KeyEvent.KEYCODE_VOLUME_UP},
                        KeyboardLogEvent.VOLUME_UP, KeyEvent.KEYCODE_VOLUME_UP, 0},
                {"VOLUME_DOWN key -> Decrease Volume", new int[]{KeyEvent.KEYCODE_VOLUME_DOWN},
                        KeyboardLogEvent.VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, 0},
                {"VOLUME_MUTE key -> Mute Volume", new int[]{KeyEvent.KEYCODE_VOLUME_MUTE},
                        KeyboardLogEvent.VOLUME_MUTE, KeyEvent.KEYCODE_VOLUME_MUTE, 0},
                {"ALL_APPS key -> Open App Drawer", new int[]{KeyEvent.KEYCODE_ALL_APPS},
                        KeyboardLogEvent.ALL_APPS, KeyEvent.KEYCODE_ALL_APPS, 0},
                {"SEARCH key -> Launch Search Activity", new int[]{KeyEvent.KEYCODE_SEARCH},
                        KeyboardLogEvent.LAUNCH_SEARCH, KeyEvent.KEYCODE_SEARCH, 0},
                {"LANGUAGE_SWITCH key -> Switch Keyboard Language",
                        new int[]{KeyEvent.KEYCODE_LANGUAGE_SWITCH},
                        KeyboardLogEvent.LANGUAGE_SWITCH, KeyEvent.KEYCODE_LANGUAGE_SWITCH, 0},
                {"META key -> Open App Drawer in Accessibility mode", new int[]{META_KEY},
                        KeyboardLogEvent.ACCESSIBILITY_ALL_APPS, META_KEY, META_ON},
                {"Meta + Alt -> Toggle CapsLock", new int[]{META_KEY, ALT_KEY},
                        KeyboardLogEvent.TOGGLE_CAPS_LOCK, ALT_KEY, META_ON | ALT_ON},
                {"Alt + Meta -> Toggle CapsLock", new int[]{ALT_KEY, META_KEY},
                        KeyboardLogEvent.TOGGLE_CAPS_LOCK, META_KEY, META_ON | ALT_ON},
                {"CAPS_LOCK key -> Toggle CapsLock", new int[]{KeyEvent.KEYCODE_CAPS_LOCK},
                        KeyboardLogEvent.TOGGLE_CAPS_LOCK, KeyEvent.KEYCODE_CAPS_LOCK, 0},
                {"MUTE key -> Mute System Microphone", new int[]{KeyEvent.KEYCODE_MUTE},
                        KeyboardLogEvent.SYSTEM_MUTE, KeyEvent.KEYCODE_MUTE, 0},
                {"Meta + Ctrl + DPAD_UP -> Split screen navigation",
                        new int[]{META_KEY, CTRL_KEY, KeyEvent.KEYCODE_DPAD_UP},
                        KeyboardLogEvent.MULTI_WINDOW_NAVIGATION, KeyEvent.KEYCODE_DPAD_UP,
                        META_ON | CTRL_ON},
                {"Meta + Ctrl + DPAD_LEFT -> Split screen navigation",
                        new int[]{META_KEY, CTRL_KEY, KeyEvent.KEYCODE_DPAD_LEFT},
                        KeyboardLogEvent.SPLIT_SCREEN_NAVIGATION, KeyEvent.KEYCODE_DPAD_LEFT,
                        META_ON | CTRL_ON},
                {"Meta + Ctrl + DPAD_RIGHT -> Split screen navigation",
                        new int[]{META_KEY, CTRL_KEY, KeyEvent.KEYCODE_DPAD_RIGHT},
                        KeyboardLogEvent.SPLIT_SCREEN_NAVIGATION, KeyEvent.KEYCODE_DPAD_RIGHT,
                        META_ON | CTRL_ON},
                {"Meta + L -> Lock Homescreen", new int[]{META_KEY, KeyEvent.KEYCODE_L},
                        KeyboardLogEvent.LOCK_SCREEN, KeyEvent.KEYCODE_L, META_ON},
                {"Meta + Ctrl + N -> Open Notes", new int[]{META_KEY, CTRL_KEY, KeyEvent.KEYCODE_N},
                        KeyboardLogEvent.OPEN_NOTES, KeyEvent.KEYCODE_N, META_ON | CTRL_ON},
                {"POWER key -> Toggle Power", new int[]{KeyEvent.KEYCODE_POWER},
                        KeyboardLogEvent.TOGGLE_POWER, KeyEvent.KEYCODE_POWER, 0},
                {"TV_POWER key -> Toggle Power", new int[]{KeyEvent.KEYCODE_TV_POWER},
                        KeyboardLogEvent.TOGGLE_POWER, KeyEvent.KEYCODE_TV_POWER, 0},
                {"SYSTEM_NAVIGATION_DOWN key -> System Navigation",
                        new int[]{KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN},
                        KeyboardLogEvent.SYSTEM_NAVIGATION, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN,
                        0},
                {"SYSTEM_NAVIGATION_UP key -> System Navigation",
                        new int[]{KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP},
                        KeyboardLogEvent.SYSTEM_NAVIGATION, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP,
                        0},
                {"SYSTEM_NAVIGATION_LEFT key -> System Navigation",
                        new int[]{KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT},
                        KeyboardLogEvent.SYSTEM_NAVIGATION, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT,
                        0},
                {"SYSTEM_NAVIGATION_RIGHT key -> System Navigation",
                        new int[]{KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT},
                        KeyboardLogEvent.SYSTEM_NAVIGATION,
                        KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT, 0},
                {"SLEEP key -> System Sleep", new int[]{KeyEvent.KEYCODE_SLEEP},
                        KeyboardLogEvent.SLEEP, KeyEvent.KEYCODE_SLEEP, 0},
                {"SOFT_SLEEP key -> System Sleep", new int[]{KeyEvent.KEYCODE_SOFT_SLEEP},
                        KeyboardLogEvent.SLEEP, KeyEvent.KEYCODE_SOFT_SLEEP, 0},
                {"WAKEUP key -> System Wakeup", new int[]{KeyEvent.KEYCODE_WAKEUP},
                        KeyboardLogEvent.WAKEUP, KeyEvent.KEYCODE_WAKEUP, 0},
                {"MEDIA_PLAY key -> Media Control", new int[]{KeyEvent.KEYCODE_MEDIA_PLAY},
                        KeyboardLogEvent.MEDIA_KEY, KeyEvent.KEYCODE_MEDIA_PLAY, 0},
                {"MEDIA_PAUSE key -> Media Control", new int[]{KeyEvent.KEYCODE_MEDIA_PAUSE},
                        KeyboardLogEvent.MEDIA_KEY, KeyEvent.KEYCODE_MEDIA_PAUSE, 0},
                {"MEDIA_PLAY_PAUSE key -> Media Control",
                        new int[]{KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE}, KeyboardLogEvent.MEDIA_KEY,
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0},
                {"Meta + B -> Launch Default Browser", new int[]{META_KEY, KeyEvent.KEYCODE_B},
                        KeyboardLogEvent.LAUNCH_DEFAULT_BROWSER, KeyEvent.KEYCODE_B, META_ON},
                {"EXPLORER key -> Launch Default Browser", new int[]{KeyEvent.KEYCODE_EXPLORER},
                        KeyboardLogEvent.LAUNCH_DEFAULT_BROWSER, KeyEvent.KEYCODE_EXPLORER, 0},
                {"Meta + C -> Launch Default Contacts", new int[]{META_KEY, KeyEvent.KEYCODE_C},
                        KeyboardLogEvent.LAUNCH_DEFAULT_CONTACTS, KeyEvent.KEYCODE_C, META_ON},
                {"CONTACTS key -> Launch Default Contacts", new int[]{KeyEvent.KEYCODE_CONTACTS},
                        KeyboardLogEvent.LAUNCH_DEFAULT_CONTACTS, KeyEvent.KEYCODE_CONTACTS, 0},
                {"Meta + E -> Launch Default Email", new int[]{META_KEY, KeyEvent.KEYCODE_E},
                        KeyboardLogEvent.LAUNCH_DEFAULT_EMAIL, KeyEvent.KEYCODE_E, META_ON},
                {"ENVELOPE key -> Launch Default Email", new int[]{KeyEvent.KEYCODE_ENVELOPE},
                        KeyboardLogEvent.LAUNCH_DEFAULT_EMAIL, KeyEvent.KEYCODE_ENVELOPE, 0},
                {"Meta + K -> Launch Default Calendar", new int[]{META_KEY, KeyEvent.KEYCODE_K},
                        KeyboardLogEvent.LAUNCH_DEFAULT_CALENDAR, KeyEvent.KEYCODE_K, META_ON},
                {"CALENDAR key -> Launch Default Calendar", new int[]{KeyEvent.KEYCODE_CALENDAR},
                        KeyboardLogEvent.LAUNCH_DEFAULT_CALENDAR, KeyEvent.KEYCODE_CALENDAR, 0},
                {"Meta + P -> Launch Default Music", new int[]{META_KEY, KeyEvent.KEYCODE_P},
                        KeyboardLogEvent.LAUNCH_DEFAULT_MUSIC, KeyEvent.KEYCODE_P, META_ON},
                {"MUSIC key -> Launch Default Music", new int[]{KeyEvent.KEYCODE_MUSIC},
                        KeyboardLogEvent.LAUNCH_DEFAULT_MUSIC, KeyEvent.KEYCODE_MUSIC, 0},
                {"Meta + U -> Launch Default Calculator", new int[]{META_KEY, KeyEvent.KEYCODE_U},
                        KeyboardLogEvent.LAUNCH_DEFAULT_CALCULATOR, KeyEvent.KEYCODE_U, META_ON},
                {"CALCULATOR key -> Launch Default Calculator",
                        new int[]{KeyEvent.KEYCODE_CALCULATOR},
                        KeyboardLogEvent.LAUNCH_DEFAULT_CALCULATOR, KeyEvent.KEYCODE_CALCULATOR, 0},
                {"Meta + M -> Launch Default Maps", new int[]{META_KEY, KeyEvent.KEYCODE_M},
                        KeyboardLogEvent.LAUNCH_DEFAULT_MAPS, KeyEvent.KEYCODE_M, META_ON},
                {"Meta + S -> Launch Default Messaging App",
                        new int[]{META_KEY, KeyEvent.KEYCODE_S},
                        KeyboardLogEvent.LAUNCH_DEFAULT_MESSAGING, KeyEvent.KEYCODE_S, META_ON},
                {"Meta + Ctrl + DPAD_DOWN -> Enter desktop mode",
                        new int[]{META_KEY, CTRL_KEY, KeyEvent.KEYCODE_DPAD_DOWN},
                        KeyboardLogEvent.DESKTOP_MODE, KeyEvent.KEYCODE_DPAD_DOWN,
                        META_ON | CTRL_ON}};
    }

    @Keep
    private static Object[][] longPressOnHomeTestArguments() {
        // testName, testKeys, longPressOnHomeBehavior, expectedLogEvent, expectedKey,
        // expectedModifierState
        return new Object[][]{
                {"Long press HOME key -> Toggle Notification panel",
                        new int[]{KeyEvent.KEYCODE_HOME}, LONG_PRESS_HOME_NOTIFICATION_PANEL,
                        KeyboardLogEvent.TOGGLE_NOTIFICATION_PANEL, KeyEvent.KEYCODE_HOME, 0},
                {"Long press META + ENTER -> Toggle Notification panel",
                        new int[]{META_KEY, KeyEvent.KEYCODE_ENTER},
                        LONG_PRESS_HOME_NOTIFICATION_PANEL,
                        KeyboardLogEvent.TOGGLE_NOTIFICATION_PANEL, KeyEvent.KEYCODE_ENTER,
                        META_ON},
                {"Long press META + H -> Toggle Notification panel",
                        new int[]{META_KEY, KeyEvent.KEYCODE_H}, LONG_PRESS_HOME_NOTIFICATION_PANEL,
                        KeyboardLogEvent.TOGGLE_NOTIFICATION_PANEL, KeyEvent.KEYCODE_H, META_ON},
                {"Long press HOME key -> Launch assistant",
                        new int[]{KeyEvent.KEYCODE_HOME}, LONG_PRESS_HOME_ASSIST,
                        KeyboardLogEvent.LAUNCH_ASSISTANT, KeyEvent.KEYCODE_HOME, 0},
                {"Long press META + ENTER -> Launch assistant",
                        new int[]{META_KEY, KeyEvent.KEYCODE_ENTER}, LONG_PRESS_HOME_ASSIST,
                        KeyboardLogEvent.LAUNCH_ASSISTANT, KeyEvent.KEYCODE_ENTER, META_ON},
                {"Long press META + H -> Launch assistant",
                        new int[]{META_KEY, KeyEvent.KEYCODE_H}, LONG_PRESS_HOME_ASSIST,
                        KeyboardLogEvent.LAUNCH_ASSISTANT, KeyEvent.KEYCODE_H, META_ON},
                {"Long press HOME key -> Open App Drawer",
                        new int[]{KeyEvent.KEYCODE_HOME}, LONG_PRESS_HOME_ALL_APPS,
                        KeyboardLogEvent.ALL_APPS, KeyEvent.KEYCODE_HOME, 0},
                {"Long press META + ENTER -> Open App Drawer",
                        new int[]{META_KEY, KeyEvent.KEYCODE_ENTER}, LONG_PRESS_HOME_ALL_APPS,
                        KeyboardLogEvent.ALL_APPS, KeyEvent.KEYCODE_ENTER, META_ON},
                {"Long press META + H -> Open App Drawer", new int[]{META_KEY, KeyEvent.KEYCODE_H},
                        LONG_PRESS_HOME_ALL_APPS, KeyboardLogEvent.ALL_APPS,
                        KeyEvent.KEYCODE_H, META_ON}};
    }

    @Keep
    private static Object[][] doubleTapOnHomeTestArguments() {
        // testName, testKeys, doubleTapOnHomeBehavior, expectedLogEvent, expectedKey,
        // expectedModifierState
        return new Object[][]{
                {"Double tap HOME -> Open App switcher",
                        new int[]{KeyEvent.KEYCODE_HOME}, DOUBLE_TAP_HOME_RECENT_SYSTEM_UI,
                        KeyboardLogEvent.APP_SWITCH, KeyEvent.KEYCODE_HOME, 0},
                {"Double tap META + ENTER -> Open App switcher",
                        new int[]{META_KEY, KeyEvent.KEYCODE_ENTER},
                        DOUBLE_TAP_HOME_RECENT_SYSTEM_UI, KeyboardLogEvent.APP_SWITCH,
                        KeyEvent.KEYCODE_ENTER, META_ON},
                {"Double tap META + H -> Open App switcher",
                        new int[]{META_KEY, KeyEvent.KEYCODE_H}, DOUBLE_TAP_HOME_RECENT_SYSTEM_UI,
                        KeyboardLogEvent.APP_SWITCH, KeyEvent.KEYCODE_H, META_ON}};
    }

    @Keep
    private static Object[][] shortPressOnSettingsTestArguments() {
        // testName, testKeys, shortPressOnSettingsBehavior, expectedLogEvent, expectedKey,
        // expectedModifierState
        return new Object[][]{
                {"SETTINGS key -> Toggle Notification panel", new int[]{KeyEvent.KEYCODE_SETTINGS},
                        SHORT_PRESS_SETTINGS_NOTIFICATION_PANEL,
                        KeyboardLogEvent.TOGGLE_NOTIFICATION_PANEL, KeyEvent.KEYCODE_SETTINGS, 0}};
    }

    @Before
    public void setUp() {
        setUpPhoneWindowManager(/*supportSettingsUpdate*/ true);
        mPhoneWindowManager.overrideKeyEventSource(VENDOR_ID, PRODUCT_ID, DEVICE_BUS);
        mPhoneWindowManager.overrideLaunchHome();
        mPhoneWindowManager.overrideSearchKeyBehavior(
                PhoneWindowManager.SEARCH_BEHAVIOR_TARGET_ACTIVITY);
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
    public void testShortcuts(String testName, int[] testKeys, KeyboardLogEvent expectedLogEvent,
            int expectedKey, int expectedModifierState) {
        sendKeyCombination(testKeys, 0 /* duration */);
        mPhoneWindowManager.assertShortcutLogged(VENDOR_ID, PRODUCT_ID, expectedLogEvent,
                expectedKey, expectedModifierState, DEVICE_BUS,
                "Failed while executing " + testName);
    }

    @Test
    @Parameters(method = "longPressOnHomeTestArguments")
    public void testLongPressOnHome(String testName, int[] testKeys, int longPressOnHomeBehavior,
            KeyboardLogEvent expectedLogEvent, int expectedKey, int expectedModifierState) {
        mPhoneWindowManager.overrideLongPressOnHomeBehavior(longPressOnHomeBehavior);
        sendLongPressKeyCombination(testKeys);
        mPhoneWindowManager.assertShortcutLogged(VENDOR_ID, PRODUCT_ID, expectedLogEvent,
                expectedKey, expectedModifierState, DEVICE_BUS,
                "Failed while executing " + testName);
    }

    @Test
    @Parameters(method = "doubleTapOnHomeTestArguments")
    public void testDoubleTapOnHomeBehavior(String testName, int[] testKeys,
            int doubleTapOnHomeBehavior, KeyboardLogEvent expectedLogEvent, int expectedKey,
            int expectedModifierState) {
        mPhoneWindowManager.overriderDoubleTapOnHomeBehavior(doubleTapOnHomeBehavior);
        sendKeyCombination(testKeys, 0 /* duration */);
        sendKeyCombination(testKeys, 0 /* duration */);
        mPhoneWindowManager.assertShortcutLogged(VENDOR_ID, PRODUCT_ID, expectedLogEvent,
                expectedKey, expectedModifierState, DEVICE_BUS,
                "Failed while executing " + testName);
    }

    @Test
    @Parameters(method = "shortPressOnSettingsTestArguments")
    public void testShortPressOnSettings(String testName, int[] testKeys,
            int shortPressOnSettingsBehavior, KeyboardLogEvent expectedLogEvent, int expectedKey,
            int expectedModifierState) {
        mPhoneWindowManager.overrideShortPressOnSettingsBehavior(shortPressOnSettingsBehavior);
        sendKeyCombination(testKeys, 0 /* duration */);
        mPhoneWindowManager.assertShortcutLogged(VENDOR_ID, PRODUCT_ID, expectedLogEvent,
                expectedKey, expectedModifierState, DEVICE_BUS,
                "Failed while executing " + testName);
    }

    @Test
    @RequiresFlagsEnabled(com.android.server.flags.Flags.FLAG_NEW_BUGREPORT_KEYBOARD_SHORTCUT)
    public void testBugreportShortcutPress() {
        sendKeyCombination(new int[]{META_KEY, CTRL_KEY, KeyEvent.KEYCODE_DEL}, 0);
        mPhoneWindowManager.assertShortcutLogged(VENDOR_ID, PRODUCT_ID,
                KeyboardLogEvent.TRIGGER_BUG_REPORT, KeyEvent.KEYCODE_DEL, META_ON | CTRL_ON,
                DEVICE_BUS, "Failed to log bugreport shortcut.");
    }
}
