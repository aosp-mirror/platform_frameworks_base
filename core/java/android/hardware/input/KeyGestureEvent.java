/*
 * Copyright 2024 The Android Open Source Project
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

package android.hardware.input;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.util.DataClass;
import com.android.internal.util.FrameworkStatsLog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Provides information about the keyboard gesture event being triggered by an external keyboard.
 *
 * @hide
 */
@DataClass(genToString = true, genEqualsHashCode = true)
public class KeyGestureEvent {

    private final int mDeviceId;
    @NonNull
    private final int[] mKeycodes;
    private final int mModifierState;
    @KeyGestureType
    private final int mKeyGestureType;


    public static final int KEY_GESTURE_TYPE_UNSPECIFIED =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__UNSPECIFIED;
    public static final int KEY_GESTURE_TYPE_HOME =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__HOME;
    public static final int KEY_GESTURE_TYPE_RECENT_APPS =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__RECENT_APPS;
    public static final int KEY_GESTURE_TYPE_BACK =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__BACK;
    public static final int KEY_GESTURE_TYPE_APP_SWITCH =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__APP_SWITCH;
    public static final int KEY_GESTURE_TYPE_LAUNCH_ASSISTANT =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_ASSISTANT;
    public static final int KEY_GESTURE_TYPE_LAUNCH_VOICE_ASSISTANT =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_VOICE_ASSISTANT;
    public static final int KEY_GESTURE_TYPE_LAUNCH_SYSTEM_SETTINGS =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_SYSTEM_SETTINGS;
    public static final int KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__TOGGLE_NOTIFICATION_PANEL;
    public static final int KEY_GESTURE_TYPE_TOGGLE_TASKBAR =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__TOGGLE_TASKBAR;
    public static final int KEY_GESTURE_TYPE_TAKE_SCREENSHOT =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__TAKE_SCREENSHOT;
    public static final int KEY_GESTURE_TYPE_OPEN_SHORTCUT_HELPER =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__OPEN_SHORTCUT_HELPER;
    public static final int KEY_GESTURE_TYPE_BRIGHTNESS_UP =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__BRIGHTNESS_UP;
    public static final int KEY_GESTURE_TYPE_BRIGHTNESS_DOWN =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__BRIGHTNESS_DOWN;
    public static final int KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_UP =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__KEYBOARD_BACKLIGHT_UP;
    public static final int KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_DOWN =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__KEYBOARD_BACKLIGHT_DOWN;
    public static final int KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_TOGGLE =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__KEYBOARD_BACKLIGHT_TOGGLE;
    public static final int KEY_GESTURE_TYPE_VOLUME_UP =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__VOLUME_UP;
    public static final int KEY_GESTURE_TYPE_VOLUME_DOWN =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__VOLUME_DOWN;
    public static final int KEY_GESTURE_TYPE_VOLUME_MUTE =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__VOLUME_MUTE;
    public static final int KEY_GESTURE_TYPE_ALL_APPS =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__ALL_APPS;
    public static final int KEY_GESTURE_TYPE_LAUNCH_SEARCH =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_SEARCH;
    public static final int KEY_GESTURE_TYPE_LANGUAGE_SWITCH =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LANGUAGE_SWITCH;
    public static final int KEY_GESTURE_TYPE_ACCESSIBILITY_ALL_APPS =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__ACCESSIBILITY_ALL_APPS;
    public static final int KEY_GESTURE_TYPE_TOGGLE_CAPS_LOCK =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__TOGGLE_CAPS_LOCK;
    public static final int KEY_GESTURE_TYPE_SYSTEM_MUTE =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__SYSTEM_MUTE;
    public static final int KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__SPLIT_SCREEN_NAVIGATION;
    public static final int KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__CHANGE_SPLITSCREEN_FOCUS;
    public static final int KEY_GESTURE_TYPE_TRIGGER_BUG_REPORT =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__TRIGGER_BUG_REPORT;
    public static final int KEY_GESTURE_TYPE_LOCK_SCREEN =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LOCK_SCREEN;
    public static final int KEY_GESTURE_TYPE_OPEN_NOTES =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__OPEN_NOTES;
    public static final int KEY_GESTURE_TYPE_TOGGLE_POWER =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__TOGGLE_POWER;
    public static final int KEY_GESTURE_TYPE_SYSTEM_NAVIGATION =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__SYSTEM_NAVIGATION;
    public static final int KEY_GESTURE_TYPE_SLEEP =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__SLEEP;
    public static final int KEY_GESTURE_TYPE_WAKEUP =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__WAKEUP;
    public static final int KEY_GESTURE_TYPE_MEDIA_KEY =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__MEDIA_KEY;
    public static final int KEY_GESTURE_TYPE_LAUNCH_DEFAULT_BROWSER =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_BROWSER;
    public static final int KEY_GESTURE_TYPE_LAUNCH_DEFAULT_EMAIL =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_EMAIL;
    public static final int KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CONTACTS =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_CONTACTS;
    public static final int KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALENDAR =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_CALENDAR;
    public static final int KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALCULATOR =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_CALCULATOR;
    public static final int KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MUSIC =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_MUSIC;
    public static final int KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MAPS =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_MAPS;
    public static final int KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MESSAGING =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_MESSAGING;
    public static final int KEY_GESTURE_TYPE_LAUNCH_DEFAULT_GALLERY =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_GALLERY;
    public static final int KEY_GESTURE_TYPE_LAUNCH_DEFAULT_FILES =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_FILES;
    public static final int KEY_GESTURE_TYPE_LAUNCH_DEFAULT_WEATHER =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_WEATHER;
    public static final int KEY_GESTURE_TYPE_LAUNCH_DEFAULT_FITNESS =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_FITNESS;
    public static final int KEY_GESTURE_TYPE_LAUNCH_APPLICATION_BY_PACKAGE_NAME =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_APPLICATION_BY_PACKAGE_NAME;
    public static final int KEY_GESTURE_TYPE_DESKTOP_MODE =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__DESKTOP_MODE;
    public static final int KEY_GESTURE_TYPE_MULTI_WINDOW_NAVIGATION =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__MULTI_WINDOW_NAVIGATION;



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/hardware/input/KeyGestureEvent.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    @IntDef(prefix = "KEY_GESTURE_TYPE_", value = {
        KEY_GESTURE_TYPE_UNSPECIFIED,
        KEY_GESTURE_TYPE_HOME,
        KEY_GESTURE_TYPE_RECENT_APPS,
        KEY_GESTURE_TYPE_BACK,
        KEY_GESTURE_TYPE_APP_SWITCH,
        KEY_GESTURE_TYPE_LAUNCH_ASSISTANT,
        KEY_GESTURE_TYPE_LAUNCH_VOICE_ASSISTANT,
        KEY_GESTURE_TYPE_LAUNCH_SYSTEM_SETTINGS,
        KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL,
        KEY_GESTURE_TYPE_TOGGLE_TASKBAR,
        KEY_GESTURE_TYPE_TAKE_SCREENSHOT,
        KEY_GESTURE_TYPE_OPEN_SHORTCUT_HELPER,
        KEY_GESTURE_TYPE_BRIGHTNESS_UP,
        KEY_GESTURE_TYPE_BRIGHTNESS_DOWN,
        KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_UP,
        KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_DOWN,
        KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_TOGGLE,
        KEY_GESTURE_TYPE_VOLUME_UP,
        KEY_GESTURE_TYPE_VOLUME_DOWN,
        KEY_GESTURE_TYPE_VOLUME_MUTE,
        KEY_GESTURE_TYPE_ALL_APPS,
        KEY_GESTURE_TYPE_LAUNCH_SEARCH,
        KEY_GESTURE_TYPE_LANGUAGE_SWITCH,
        KEY_GESTURE_TYPE_ACCESSIBILITY_ALL_APPS,
        KEY_GESTURE_TYPE_TOGGLE_CAPS_LOCK,
        KEY_GESTURE_TYPE_SYSTEM_MUTE,
        KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION,
        KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS,
        KEY_GESTURE_TYPE_TRIGGER_BUG_REPORT,
        KEY_GESTURE_TYPE_LOCK_SCREEN,
        KEY_GESTURE_TYPE_OPEN_NOTES,
        KEY_GESTURE_TYPE_TOGGLE_POWER,
        KEY_GESTURE_TYPE_SYSTEM_NAVIGATION,
        KEY_GESTURE_TYPE_SLEEP,
        KEY_GESTURE_TYPE_WAKEUP,
        KEY_GESTURE_TYPE_MEDIA_KEY,
        KEY_GESTURE_TYPE_LAUNCH_DEFAULT_BROWSER,
        KEY_GESTURE_TYPE_LAUNCH_DEFAULT_EMAIL,
        KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CONTACTS,
        KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALENDAR,
        KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALCULATOR,
        KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MUSIC,
        KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MAPS,
        KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MESSAGING,
        KEY_GESTURE_TYPE_LAUNCH_DEFAULT_GALLERY,
        KEY_GESTURE_TYPE_LAUNCH_DEFAULT_FILES,
        KEY_GESTURE_TYPE_LAUNCH_DEFAULT_WEATHER,
        KEY_GESTURE_TYPE_LAUNCH_DEFAULT_FITNESS,
        KEY_GESTURE_TYPE_LAUNCH_APPLICATION_BY_PACKAGE_NAME,
        KEY_GESTURE_TYPE_DESKTOP_MODE,
        KEY_GESTURE_TYPE_MULTI_WINDOW_NAVIGATION
    })
    @Retention(RetentionPolicy.SOURCE)
    @DataClass.Generated.Member
    public @interface KeyGestureType {}

    @DataClass.Generated.Member
    public static String keyGestureTypeToString(@KeyGestureType int value) {
        switch (value) {
            case KEY_GESTURE_TYPE_UNSPECIFIED:
                    return "KEY_GESTURE_TYPE_UNSPECIFIED";
            case KEY_GESTURE_TYPE_HOME:
                    return "KEY_GESTURE_TYPE_HOME";
            case KEY_GESTURE_TYPE_RECENT_APPS:
                    return "KEY_GESTURE_TYPE_RECENT_APPS";
            case KEY_GESTURE_TYPE_BACK:
                    return "KEY_GESTURE_TYPE_BACK";
            case KEY_GESTURE_TYPE_APP_SWITCH:
                    return "KEY_GESTURE_TYPE_APP_SWITCH";
            case KEY_GESTURE_TYPE_LAUNCH_ASSISTANT:
                    return "KEY_GESTURE_TYPE_LAUNCH_ASSISTANT";
            case KEY_GESTURE_TYPE_LAUNCH_VOICE_ASSISTANT:
                    return "KEY_GESTURE_TYPE_LAUNCH_VOICE_ASSISTANT";
            case KEY_GESTURE_TYPE_LAUNCH_SYSTEM_SETTINGS:
                    return "KEY_GESTURE_TYPE_LAUNCH_SYSTEM_SETTINGS";
            case KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL:
                    return "KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL";
            case KEY_GESTURE_TYPE_TOGGLE_TASKBAR:
                    return "KEY_GESTURE_TYPE_TOGGLE_TASKBAR";
            case KEY_GESTURE_TYPE_TAKE_SCREENSHOT:
                    return "KEY_GESTURE_TYPE_TAKE_SCREENSHOT";
            case KEY_GESTURE_TYPE_OPEN_SHORTCUT_HELPER:
                    return "KEY_GESTURE_TYPE_OPEN_SHORTCUT_HELPER";
            case KEY_GESTURE_TYPE_BRIGHTNESS_UP:
                    return "KEY_GESTURE_TYPE_BRIGHTNESS_UP";
            case KEY_GESTURE_TYPE_BRIGHTNESS_DOWN:
                    return "KEY_GESTURE_TYPE_BRIGHTNESS_DOWN";
            case KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_UP:
                    return "KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_UP";
            case KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_DOWN:
                    return "KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_DOWN";
            case KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_TOGGLE:
                    return "KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_TOGGLE";
            case KEY_GESTURE_TYPE_VOLUME_UP:
                    return "KEY_GESTURE_TYPE_VOLUME_UP";
            case KEY_GESTURE_TYPE_VOLUME_DOWN:
                    return "KEY_GESTURE_TYPE_VOLUME_DOWN";
            case KEY_GESTURE_TYPE_VOLUME_MUTE:
                    return "KEY_GESTURE_TYPE_VOLUME_MUTE";
            case KEY_GESTURE_TYPE_ALL_APPS:
                    return "KEY_GESTURE_TYPE_ALL_APPS";
            case KEY_GESTURE_TYPE_LAUNCH_SEARCH:
                    return "KEY_GESTURE_TYPE_LAUNCH_SEARCH";
            case KEY_GESTURE_TYPE_LANGUAGE_SWITCH:
                    return "KEY_GESTURE_TYPE_LANGUAGE_SWITCH";
            case KEY_GESTURE_TYPE_ACCESSIBILITY_ALL_APPS:
                    return "KEY_GESTURE_TYPE_ACCESSIBILITY_ALL_APPS";
            case KEY_GESTURE_TYPE_TOGGLE_CAPS_LOCK:
                    return "KEY_GESTURE_TYPE_TOGGLE_CAPS_LOCK";
            case KEY_GESTURE_TYPE_SYSTEM_MUTE:
                    return "KEY_GESTURE_TYPE_SYSTEM_MUTE";
            case KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION:
                    return "KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION";
            case KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS:
                    return "KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS";
            case KEY_GESTURE_TYPE_TRIGGER_BUG_REPORT:
                    return "KEY_GESTURE_TYPE_TRIGGER_BUG_REPORT";
            case KEY_GESTURE_TYPE_LOCK_SCREEN:
                    return "KEY_GESTURE_TYPE_LOCK_SCREEN";
            case KEY_GESTURE_TYPE_OPEN_NOTES:
                    return "KEY_GESTURE_TYPE_OPEN_NOTES";
            case KEY_GESTURE_TYPE_TOGGLE_POWER:
                    return "KEY_GESTURE_TYPE_TOGGLE_POWER";
            case KEY_GESTURE_TYPE_SYSTEM_NAVIGATION:
                    return "KEY_GESTURE_TYPE_SYSTEM_NAVIGATION";
            case KEY_GESTURE_TYPE_SLEEP:
                    return "KEY_GESTURE_TYPE_SLEEP";
            case KEY_GESTURE_TYPE_WAKEUP:
                    return "KEY_GESTURE_TYPE_WAKEUP";
            case KEY_GESTURE_TYPE_MEDIA_KEY:
                    return "KEY_GESTURE_TYPE_MEDIA_KEY";
            case KEY_GESTURE_TYPE_LAUNCH_DEFAULT_BROWSER:
                    return "KEY_GESTURE_TYPE_LAUNCH_DEFAULT_BROWSER";
            case KEY_GESTURE_TYPE_LAUNCH_DEFAULT_EMAIL:
                    return "KEY_GESTURE_TYPE_LAUNCH_DEFAULT_EMAIL";
            case KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CONTACTS:
                    return "KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CONTACTS";
            case KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALENDAR:
                    return "KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALENDAR";
            case KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALCULATOR:
                    return "KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALCULATOR";
            case KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MUSIC:
                    return "KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MUSIC";
            case KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MAPS:
                    return "KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MAPS";
            case KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MESSAGING:
                    return "KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MESSAGING";
            case KEY_GESTURE_TYPE_LAUNCH_DEFAULT_GALLERY:
                    return "KEY_GESTURE_TYPE_LAUNCH_DEFAULT_GALLERY";
            case KEY_GESTURE_TYPE_LAUNCH_DEFAULT_FILES:
                    return "KEY_GESTURE_TYPE_LAUNCH_DEFAULT_FILES";
            case KEY_GESTURE_TYPE_LAUNCH_DEFAULT_WEATHER:
                    return "KEY_GESTURE_TYPE_LAUNCH_DEFAULT_WEATHER";
            case KEY_GESTURE_TYPE_LAUNCH_DEFAULT_FITNESS:
                    return "KEY_GESTURE_TYPE_LAUNCH_DEFAULT_FITNESS";
            case KEY_GESTURE_TYPE_LAUNCH_APPLICATION_BY_PACKAGE_NAME:
                    return "KEY_GESTURE_TYPE_LAUNCH_APPLICATION_BY_PACKAGE_NAME";
            case KEY_GESTURE_TYPE_DESKTOP_MODE:
                    return "KEY_GESTURE_TYPE_DESKTOP_MODE";
            case KEY_GESTURE_TYPE_MULTI_WINDOW_NAVIGATION:
                    return "KEY_GESTURE_TYPE_MULTI_WINDOW_NAVIGATION";
            default: return Integer.toHexString(value);
        }
    }

    @DataClass.Generated.Member
    public KeyGestureEvent(
            int deviceId,
            @NonNull int[] keycodes,
            int modifierState,
            @KeyGestureType int keyGestureType) {
        this.mDeviceId = deviceId;
        this.mKeycodes = keycodes;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mKeycodes);
        this.mModifierState = modifierState;
        this.mKeyGestureType = keyGestureType;

        if (!(mKeyGestureType == KEY_GESTURE_TYPE_UNSPECIFIED)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_HOME)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_RECENT_APPS)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_BACK)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_APP_SWITCH)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_LAUNCH_ASSISTANT)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_LAUNCH_VOICE_ASSISTANT)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_LAUNCH_SYSTEM_SETTINGS)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_TOGGLE_TASKBAR)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_TAKE_SCREENSHOT)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_OPEN_SHORTCUT_HELPER)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_BRIGHTNESS_UP)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_BRIGHTNESS_DOWN)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_UP)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_DOWN)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_TOGGLE)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_VOLUME_UP)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_VOLUME_DOWN)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_VOLUME_MUTE)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_ALL_APPS)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_LAUNCH_SEARCH)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_LANGUAGE_SWITCH)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_ACCESSIBILITY_ALL_APPS)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_TOGGLE_CAPS_LOCK)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_SYSTEM_MUTE)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_TRIGGER_BUG_REPORT)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_LOCK_SCREEN)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_OPEN_NOTES)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_TOGGLE_POWER)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_SYSTEM_NAVIGATION)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_SLEEP)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_WAKEUP)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_MEDIA_KEY)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_LAUNCH_DEFAULT_BROWSER)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_LAUNCH_DEFAULT_EMAIL)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CONTACTS)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALENDAR)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALCULATOR)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MUSIC)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MAPS)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MESSAGING)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_LAUNCH_DEFAULT_GALLERY)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_LAUNCH_DEFAULT_FILES)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_LAUNCH_DEFAULT_WEATHER)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_LAUNCH_DEFAULT_FITNESS)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_LAUNCH_APPLICATION_BY_PACKAGE_NAME)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_DESKTOP_MODE)
                && !(mKeyGestureType == KEY_GESTURE_TYPE_MULTI_WINDOW_NAVIGATION)) {
            throw new java.lang.IllegalArgumentException(
                    "keyGestureType was " + mKeyGestureType + " but must be one of: "
                            + "KEY_GESTURE_TYPE_UNSPECIFIED(" + KEY_GESTURE_TYPE_UNSPECIFIED + "), "
                            + "KEY_GESTURE_TYPE_HOME(" + KEY_GESTURE_TYPE_HOME + "), "
                            + "KEY_GESTURE_TYPE_RECENT_APPS(" + KEY_GESTURE_TYPE_RECENT_APPS + "), "
                            + "KEY_GESTURE_TYPE_BACK(" + KEY_GESTURE_TYPE_BACK + "), "
                            + "KEY_GESTURE_TYPE_APP_SWITCH(" + KEY_GESTURE_TYPE_APP_SWITCH + "), "
                            + "KEY_GESTURE_TYPE_LAUNCH_ASSISTANT(" + KEY_GESTURE_TYPE_LAUNCH_ASSISTANT + "), "
                            + "KEY_GESTURE_TYPE_LAUNCH_VOICE_ASSISTANT(" + KEY_GESTURE_TYPE_LAUNCH_VOICE_ASSISTANT + "), "
                            + "KEY_GESTURE_TYPE_LAUNCH_SYSTEM_SETTINGS(" + KEY_GESTURE_TYPE_LAUNCH_SYSTEM_SETTINGS + "), "
                            + "KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL(" + KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL + "), "
                            + "KEY_GESTURE_TYPE_TOGGLE_TASKBAR(" + KEY_GESTURE_TYPE_TOGGLE_TASKBAR + "), "
                            + "KEY_GESTURE_TYPE_TAKE_SCREENSHOT(" + KEY_GESTURE_TYPE_TAKE_SCREENSHOT + "), "
                            + "KEY_GESTURE_TYPE_OPEN_SHORTCUT_HELPER(" + KEY_GESTURE_TYPE_OPEN_SHORTCUT_HELPER + "), "
                            + "KEY_GESTURE_TYPE_BRIGHTNESS_UP(" + KEY_GESTURE_TYPE_BRIGHTNESS_UP + "), "
                            + "KEY_GESTURE_TYPE_BRIGHTNESS_DOWN(" + KEY_GESTURE_TYPE_BRIGHTNESS_DOWN + "), "
                            + "KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_UP(" + KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_UP + "), "
                            + "KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_DOWN(" + KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_DOWN + "), "
                            + "KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_TOGGLE(" + KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_TOGGLE + "), "
                            + "KEY_GESTURE_TYPE_VOLUME_UP(" + KEY_GESTURE_TYPE_VOLUME_UP + "), "
                            + "KEY_GESTURE_TYPE_VOLUME_DOWN(" + KEY_GESTURE_TYPE_VOLUME_DOWN + "), "
                            + "KEY_GESTURE_TYPE_VOLUME_MUTE(" + KEY_GESTURE_TYPE_VOLUME_MUTE + "), "
                            + "KEY_GESTURE_TYPE_ALL_APPS(" + KEY_GESTURE_TYPE_ALL_APPS + "), "
                            + "KEY_GESTURE_TYPE_LAUNCH_SEARCH(" + KEY_GESTURE_TYPE_LAUNCH_SEARCH + "), "
                            + "KEY_GESTURE_TYPE_LANGUAGE_SWITCH(" + KEY_GESTURE_TYPE_LANGUAGE_SWITCH + "), "
                            + "KEY_GESTURE_TYPE_ACCESSIBILITY_ALL_APPS(" + KEY_GESTURE_TYPE_ACCESSIBILITY_ALL_APPS + "), "
                            + "KEY_GESTURE_TYPE_TOGGLE_CAPS_LOCK(" + KEY_GESTURE_TYPE_TOGGLE_CAPS_LOCK + "), "
                            + "KEY_GESTURE_TYPE_SYSTEM_MUTE(" + KEY_GESTURE_TYPE_SYSTEM_MUTE + "), "
                            + "KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION(" + KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION + "), "
                            + "KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS(" + KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS + "), "
                            + "KEY_GESTURE_TYPE_TRIGGER_BUG_REPORT(" + KEY_GESTURE_TYPE_TRIGGER_BUG_REPORT + "), "
                            + "KEY_GESTURE_TYPE_LOCK_SCREEN(" + KEY_GESTURE_TYPE_LOCK_SCREEN + "), "
                            + "KEY_GESTURE_TYPE_OPEN_NOTES(" + KEY_GESTURE_TYPE_OPEN_NOTES + "), "
                            + "KEY_GESTURE_TYPE_TOGGLE_POWER(" + KEY_GESTURE_TYPE_TOGGLE_POWER + "), "
                            + "KEY_GESTURE_TYPE_SYSTEM_NAVIGATION(" + KEY_GESTURE_TYPE_SYSTEM_NAVIGATION + "), "
                            + "KEY_GESTURE_TYPE_SLEEP(" + KEY_GESTURE_TYPE_SLEEP + "), "
                            + "KEY_GESTURE_TYPE_WAKEUP(" + KEY_GESTURE_TYPE_WAKEUP + "), "
                            + "KEY_GESTURE_TYPE_MEDIA_KEY(" + KEY_GESTURE_TYPE_MEDIA_KEY + "), "
                            + "KEY_GESTURE_TYPE_LAUNCH_DEFAULT_BROWSER(" + KEY_GESTURE_TYPE_LAUNCH_DEFAULT_BROWSER + "), "
                            + "KEY_GESTURE_TYPE_LAUNCH_DEFAULT_EMAIL(" + KEY_GESTURE_TYPE_LAUNCH_DEFAULT_EMAIL + "), "
                            + "KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CONTACTS(" + KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CONTACTS + "), "
                            + "KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALENDAR(" + KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALENDAR + "), "
                            + "KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALCULATOR(" + KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALCULATOR + "), "
                            + "KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MUSIC(" + KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MUSIC + "), "
                            + "KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MAPS(" + KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MAPS + "), "
                            + "KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MESSAGING(" + KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MESSAGING + "), "
                            + "KEY_GESTURE_TYPE_LAUNCH_DEFAULT_GALLERY(" + KEY_GESTURE_TYPE_LAUNCH_DEFAULT_GALLERY + "), "
                            + "KEY_GESTURE_TYPE_LAUNCH_DEFAULT_FILES(" + KEY_GESTURE_TYPE_LAUNCH_DEFAULT_FILES + "), "
                            + "KEY_GESTURE_TYPE_LAUNCH_DEFAULT_WEATHER(" + KEY_GESTURE_TYPE_LAUNCH_DEFAULT_WEATHER + "), "
                            + "KEY_GESTURE_TYPE_LAUNCH_DEFAULT_FITNESS(" + KEY_GESTURE_TYPE_LAUNCH_DEFAULT_FITNESS + "), "
                            + "KEY_GESTURE_TYPE_LAUNCH_APPLICATION_BY_PACKAGE_NAME(" + KEY_GESTURE_TYPE_LAUNCH_APPLICATION_BY_PACKAGE_NAME + "), "
                            + "KEY_GESTURE_TYPE_DESKTOP_MODE(" + KEY_GESTURE_TYPE_DESKTOP_MODE + "), "
                            + "KEY_GESTURE_TYPE_MULTI_WINDOW_NAVIGATION(" + KEY_GESTURE_TYPE_MULTI_WINDOW_NAVIGATION + ")");
        }


        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public int getDeviceId() {
        return mDeviceId;
    }

    @DataClass.Generated.Member
    public @NonNull int[] getKeycodes() {
        return mKeycodes;
    }

    @DataClass.Generated.Member
    public int getModifierState() {
        return mModifierState;
    }

    @DataClass.Generated.Member
    public @KeyGestureType int getKeyGestureType() {
        return mKeyGestureType;
    }

    @Override
    @DataClass.Generated.Member
    public String toString() {
        // You can override field toString logic by defining methods like:
        // String fieldNameToString() { ... }

        return "KeyGestureEvent { " +
                "deviceId = " + mDeviceId + ", " +
                "keycodes = " + java.util.Arrays.toString(mKeycodes) + ", " +
                "modifierState = " + mModifierState + ", " +
                "keyGestureType = " + keyGestureTypeToString(mKeyGestureType) +
        " }";
    }

    @Override
    @DataClass.Generated.Member
    public boolean equals(@Nullable Object o) {
        // You can override field equality logic by defining either of the methods like:
        // boolean fieldNameEquals(KeyGestureEvent other) { ... }
        // boolean fieldNameEquals(FieldType otherValue) { ... }

        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        @SuppressWarnings("unchecked")
        KeyGestureEvent that = (KeyGestureEvent) o;
        //noinspection PointlessBooleanExpression
        return true
                && mDeviceId == that.mDeviceId
                && java.util.Arrays.equals(mKeycodes, that.mKeycodes)
                && mModifierState == that.mModifierState
                && mKeyGestureType == that.mKeyGestureType;
    }

    @Override
    @DataClass.Generated.Member
    public int hashCode() {
        // You can override field hashCode logic by defining methods like:
        // int fieldNameHashCode() { ... }

        int _hash = 1;
        _hash = 31 * _hash + mDeviceId;
        _hash = 31 * _hash + java.util.Arrays.hashCode(mKeycodes);
        _hash = 31 * _hash + mModifierState;
        _hash = 31 * _hash + mKeyGestureType;
        return _hash;
    }

    @DataClass.Generated(
            time = 1723409092192L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/core/java/android/hardware/input/KeyGestureEvent.java",
            inputSignatures = "private final  int mDeviceId\nprivate final @android.annotation.NonNull int[] mKeycodes\nprivate final  int mModifierState\nprivate final @android.hardware.input.KeyGestureEvent.KeyGestureType int mKeyGestureType\npublic static final  int KEY_GESTURE_TYPE_UNSPECIFIED\npublic static final  int KEY_GESTURE_TYPE_HOME\npublic static final  int KEY_GESTURE_TYPE_RECENT_APPS\npublic static final  int KEY_GESTURE_TYPE_BACK\npublic static final  int KEY_GESTURE_TYPE_APP_SWITCH\npublic static final  int KEY_GESTURE_TYPE_LAUNCH_ASSISTANT\npublic static final  int KEY_GESTURE_TYPE_LAUNCH_VOICE_ASSISTANT\npublic static final  int KEY_GESTURE_TYPE_LAUNCH_SYSTEM_SETTINGS\npublic static final  int KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL\npublic static final  int KEY_GESTURE_TYPE_TOGGLE_TASKBAR\npublic static final  int KEY_GESTURE_TYPE_TAKE_SCREENSHOT\npublic static final  int KEY_GESTURE_TYPE_OPEN_SHORTCUT_HELPER\npublic static final  int KEY_GESTURE_TYPE_BRIGHTNESS_UP\npublic static final  int KEY_GESTURE_TYPE_BRIGHTNESS_DOWN\npublic static final  int KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_UP\npublic static final  int KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_DOWN\npublic static final  int KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_TOGGLE\npublic static final  int KEY_GESTURE_TYPE_VOLUME_UP\npublic static final  int KEY_GESTURE_TYPE_VOLUME_DOWN\npublic static final  int KEY_GESTURE_TYPE_VOLUME_MUTE\npublic static final  int KEY_GESTURE_TYPE_ALL_APPS\npublic static final  int KEY_GESTURE_TYPE_LAUNCH_SEARCH\npublic static final  int KEY_GESTURE_TYPE_LANGUAGE_SWITCH\npublic static final  int KEY_GESTURE_TYPE_ACCESSIBILITY_ALL_APPS\npublic static final  int KEY_GESTURE_TYPE_TOGGLE_CAPS_LOCK\npublic static final  int KEY_GESTURE_TYPE_SYSTEM_MUTE\npublic static final  int KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION\npublic static final  int KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS\npublic static final  int KEY_GESTURE_TYPE_TRIGGER_BUG_REPORT\npublic static final  int KEY_GESTURE_TYPE_LOCK_SCREEN\npublic static final  int KEY_GESTURE_TYPE_OPEN_NOTES\npublic static final  int KEY_GESTURE_TYPE_TOGGLE_POWER\npublic static final  int KEY_GESTURE_TYPE_SYSTEM_NAVIGATION\npublic static final  int KEY_GESTURE_TYPE_SLEEP\npublic static final  int KEY_GESTURE_TYPE_WAKEUP\npublic static final  int KEY_GESTURE_TYPE_MEDIA_KEY\npublic static final  int KEY_GESTURE_TYPE_LAUNCH_DEFAULT_BROWSER\npublic static final  int KEY_GESTURE_TYPE_LAUNCH_DEFAULT_EMAIL\npublic static final  int KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CONTACTS\npublic static final  int KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALENDAR\npublic static final  int KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALCULATOR\npublic static final  int KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MUSIC\npublic static final  int KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MAPS\npublic static final  int KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MESSAGING\npublic static final  int KEY_GESTURE_TYPE_LAUNCH_DEFAULT_GALLERY\npublic static final  int KEY_GESTURE_TYPE_LAUNCH_DEFAULT_FILES\npublic static final  int KEY_GESTURE_TYPE_LAUNCH_DEFAULT_WEATHER\npublic static final  int KEY_GESTURE_TYPE_LAUNCH_DEFAULT_FITNESS\npublic static final  int KEY_GESTURE_TYPE_LAUNCH_APPLICATION_BY_PACKAGE_NAME\npublic static final  int KEY_GESTURE_TYPE_DESKTOP_MODE\npublic static final  int KEY_GESTURE_TYPE_MULTI_WINDOW_NAVIGATION\nclass KeyGestureEvent extends java.lang.Object implements []\n@com.android.internal.util.DataClass(genToString=true, genEqualsHashCode=true)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
