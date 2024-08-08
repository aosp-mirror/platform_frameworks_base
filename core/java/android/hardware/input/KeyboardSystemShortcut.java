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
 * Provides information about the keyboard shortcut being triggered by an external keyboard.
 *
 * @hide
 */
@DataClass(genToString = true, genEqualsHashCode = true)
public class KeyboardSystemShortcut {

    private static final String TAG = "KeyboardSystemShortcut";

    @NonNull
    private final int[] mKeycodes;
    private final int mModifierState;
    @SystemShortcut
    private final int mSystemShortcut;


    public static final int SYSTEM_SHORTCUT_UNSPECIFIED =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__UNSPECIFIED;
    public static final int SYSTEM_SHORTCUT_HOME =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__HOME;
    public static final int SYSTEM_SHORTCUT_RECENT_APPS =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__RECENT_APPS;
    public static final int SYSTEM_SHORTCUT_BACK =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__BACK;
    public static final int SYSTEM_SHORTCUT_APP_SWITCH =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__APP_SWITCH;
    public static final int SYSTEM_SHORTCUT_LAUNCH_ASSISTANT =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_ASSISTANT;
    public static final int SYSTEM_SHORTCUT_LAUNCH_VOICE_ASSISTANT =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_VOICE_ASSISTANT;
    public static final int SYSTEM_SHORTCUT_LAUNCH_SYSTEM_SETTINGS =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_SYSTEM_SETTINGS;
    public static final int SYSTEM_SHORTCUT_TOGGLE_NOTIFICATION_PANEL =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__TOGGLE_NOTIFICATION_PANEL;
    public static final int SYSTEM_SHORTCUT_TOGGLE_TASKBAR =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__TOGGLE_TASKBAR;
    public static final int SYSTEM_SHORTCUT_TAKE_SCREENSHOT =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__TAKE_SCREENSHOT;
    public static final int SYSTEM_SHORTCUT_OPEN_SHORTCUT_HELPER =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__OPEN_SHORTCUT_HELPER;
    public static final int SYSTEM_SHORTCUT_BRIGHTNESS_UP =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__BRIGHTNESS_UP;
    public static final int SYSTEM_SHORTCUT_BRIGHTNESS_DOWN =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__BRIGHTNESS_DOWN;
    public static final int SYSTEM_SHORTCUT_KEYBOARD_BACKLIGHT_UP =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__KEYBOARD_BACKLIGHT_UP;
    public static final int SYSTEM_SHORTCUT_KEYBOARD_BACKLIGHT_DOWN =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__KEYBOARD_BACKLIGHT_DOWN;
    public static final int SYSTEM_SHORTCUT_KEYBOARD_BACKLIGHT_TOGGLE =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__KEYBOARD_BACKLIGHT_TOGGLE;
    public static final int SYSTEM_SHORTCUT_VOLUME_UP =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__VOLUME_UP;
    public static final int SYSTEM_SHORTCUT_VOLUME_DOWN =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__VOLUME_DOWN;
    public static final int SYSTEM_SHORTCUT_VOLUME_MUTE =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__VOLUME_MUTE;
    public static final int SYSTEM_SHORTCUT_ALL_APPS =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__ALL_APPS;
    public static final int SYSTEM_SHORTCUT_LAUNCH_SEARCH =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_SEARCH;
    public static final int SYSTEM_SHORTCUT_LANGUAGE_SWITCH =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LANGUAGE_SWITCH;
    public static final int SYSTEM_SHORTCUT_ACCESSIBILITY_ALL_APPS =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__ACCESSIBILITY_ALL_APPS;
    public static final int SYSTEM_SHORTCUT_TOGGLE_CAPS_LOCK =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__TOGGLE_CAPS_LOCK;
    public static final int SYSTEM_SHORTCUT_SYSTEM_MUTE =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__SYSTEM_MUTE;
    public static final int SYSTEM_SHORTCUT_SPLIT_SCREEN_NAVIGATION =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__SPLIT_SCREEN_NAVIGATION;
    public static final int SYSTEM_SHORTCUT_CHANGE_SPLITSCREEN_FOCUS =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__CHANGE_SPLITSCREEN_FOCUS;
    public static final int SYSTEM_SHORTCUT_TRIGGER_BUG_REPORT =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__TRIGGER_BUG_REPORT;
    public static final int SYSTEM_SHORTCUT_LOCK_SCREEN =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LOCK_SCREEN;
    public static final int SYSTEM_SHORTCUT_OPEN_NOTES =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__OPEN_NOTES;
    public static final int SYSTEM_SHORTCUT_TOGGLE_POWER =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__TOGGLE_POWER;
    public static final int SYSTEM_SHORTCUT_SYSTEM_NAVIGATION =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__SYSTEM_NAVIGATION;
    public static final int SYSTEM_SHORTCUT_SLEEP =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__SLEEP;
    public static final int SYSTEM_SHORTCUT_WAKEUP =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__WAKEUP;
    public static final int SYSTEM_SHORTCUT_MEDIA_KEY =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__MEDIA_KEY;
    public static final int SYSTEM_SHORTCUT_LAUNCH_DEFAULT_BROWSER =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_BROWSER;
    public static final int SYSTEM_SHORTCUT_LAUNCH_DEFAULT_EMAIL =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_EMAIL;
    public static final int SYSTEM_SHORTCUT_LAUNCH_DEFAULT_CONTACTS =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_CONTACTS;
    public static final int SYSTEM_SHORTCUT_LAUNCH_DEFAULT_CALENDAR =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_CALENDAR;
    public static final int SYSTEM_SHORTCUT_LAUNCH_DEFAULT_CALCULATOR =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_CALCULATOR;
    public static final int SYSTEM_SHORTCUT_LAUNCH_DEFAULT_MUSIC =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_MUSIC;
    public static final int SYSTEM_SHORTCUT_LAUNCH_DEFAULT_MAPS =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_MAPS;
    public static final int SYSTEM_SHORTCUT_LAUNCH_DEFAULT_MESSAGING =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_MESSAGING;
    public static final int SYSTEM_SHORTCUT_LAUNCH_DEFAULT_GALLERY =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_GALLERY;
    public static final int SYSTEM_SHORTCUT_LAUNCH_DEFAULT_FILES =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_FILES;
    public static final int SYSTEM_SHORTCUT_LAUNCH_DEFAULT_WEATHER =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_WEATHER;
    public static final int SYSTEM_SHORTCUT_LAUNCH_DEFAULT_FITNESS =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_FITNESS;
    public static final int SYSTEM_SHORTCUT_LAUNCH_APPLICATION_BY_PACKAGE_NAME =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_APPLICATION_BY_PACKAGE_NAME;
    public static final int SYSTEM_SHORTCUT_DESKTOP_MODE =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__DESKTOP_MODE;
    public static final int SYSTEM_SHORTCUT_MULTI_WINDOW_NAVIGATION =
            FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__MULTI_WINDOW_NAVIGATION;



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/hardware/input/KeyboardSystemShortcut.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    @IntDef(prefix = "SYSTEM_SHORTCUT_", value = {
        SYSTEM_SHORTCUT_UNSPECIFIED,
        SYSTEM_SHORTCUT_HOME,
        SYSTEM_SHORTCUT_RECENT_APPS,
        SYSTEM_SHORTCUT_BACK,
        SYSTEM_SHORTCUT_APP_SWITCH,
        SYSTEM_SHORTCUT_LAUNCH_ASSISTANT,
        SYSTEM_SHORTCUT_LAUNCH_VOICE_ASSISTANT,
        SYSTEM_SHORTCUT_LAUNCH_SYSTEM_SETTINGS,
        SYSTEM_SHORTCUT_TOGGLE_NOTIFICATION_PANEL,
        SYSTEM_SHORTCUT_TOGGLE_TASKBAR,
        SYSTEM_SHORTCUT_TAKE_SCREENSHOT,
        SYSTEM_SHORTCUT_OPEN_SHORTCUT_HELPER,
        SYSTEM_SHORTCUT_BRIGHTNESS_UP,
        SYSTEM_SHORTCUT_BRIGHTNESS_DOWN,
        SYSTEM_SHORTCUT_KEYBOARD_BACKLIGHT_UP,
        SYSTEM_SHORTCUT_KEYBOARD_BACKLIGHT_DOWN,
        SYSTEM_SHORTCUT_KEYBOARD_BACKLIGHT_TOGGLE,
        SYSTEM_SHORTCUT_VOLUME_UP,
        SYSTEM_SHORTCUT_VOLUME_DOWN,
        SYSTEM_SHORTCUT_VOLUME_MUTE,
        SYSTEM_SHORTCUT_ALL_APPS,
        SYSTEM_SHORTCUT_LAUNCH_SEARCH,
        SYSTEM_SHORTCUT_LANGUAGE_SWITCH,
        SYSTEM_SHORTCUT_ACCESSIBILITY_ALL_APPS,
        SYSTEM_SHORTCUT_TOGGLE_CAPS_LOCK,
        SYSTEM_SHORTCUT_SYSTEM_MUTE,
        SYSTEM_SHORTCUT_SPLIT_SCREEN_NAVIGATION,
        SYSTEM_SHORTCUT_CHANGE_SPLITSCREEN_FOCUS,
        SYSTEM_SHORTCUT_TRIGGER_BUG_REPORT,
        SYSTEM_SHORTCUT_LOCK_SCREEN,
        SYSTEM_SHORTCUT_OPEN_NOTES,
        SYSTEM_SHORTCUT_TOGGLE_POWER,
        SYSTEM_SHORTCUT_SYSTEM_NAVIGATION,
        SYSTEM_SHORTCUT_SLEEP,
        SYSTEM_SHORTCUT_WAKEUP,
        SYSTEM_SHORTCUT_MEDIA_KEY,
        SYSTEM_SHORTCUT_LAUNCH_DEFAULT_BROWSER,
        SYSTEM_SHORTCUT_LAUNCH_DEFAULT_EMAIL,
        SYSTEM_SHORTCUT_LAUNCH_DEFAULT_CONTACTS,
        SYSTEM_SHORTCUT_LAUNCH_DEFAULT_CALENDAR,
        SYSTEM_SHORTCUT_LAUNCH_DEFAULT_CALCULATOR,
        SYSTEM_SHORTCUT_LAUNCH_DEFAULT_MUSIC,
        SYSTEM_SHORTCUT_LAUNCH_DEFAULT_MAPS,
        SYSTEM_SHORTCUT_LAUNCH_DEFAULT_MESSAGING,
        SYSTEM_SHORTCUT_LAUNCH_DEFAULT_GALLERY,
        SYSTEM_SHORTCUT_LAUNCH_DEFAULT_FILES,
        SYSTEM_SHORTCUT_LAUNCH_DEFAULT_WEATHER,
        SYSTEM_SHORTCUT_LAUNCH_DEFAULT_FITNESS,
        SYSTEM_SHORTCUT_LAUNCH_APPLICATION_BY_PACKAGE_NAME,
        SYSTEM_SHORTCUT_DESKTOP_MODE,
        SYSTEM_SHORTCUT_MULTI_WINDOW_NAVIGATION
    })
    @Retention(RetentionPolicy.SOURCE)
    @DataClass.Generated.Member
    public @interface SystemShortcut {}

    @DataClass.Generated.Member
    public static String systemShortcutToString(@SystemShortcut int value) {
        switch (value) {
            case SYSTEM_SHORTCUT_UNSPECIFIED:
                    return "SYSTEM_SHORTCUT_UNSPECIFIED";
            case SYSTEM_SHORTCUT_HOME:
                    return "SYSTEM_SHORTCUT_HOME";
            case SYSTEM_SHORTCUT_RECENT_APPS:
                    return "SYSTEM_SHORTCUT_RECENT_APPS";
            case SYSTEM_SHORTCUT_BACK:
                    return "SYSTEM_SHORTCUT_BACK";
            case SYSTEM_SHORTCUT_APP_SWITCH:
                    return "SYSTEM_SHORTCUT_APP_SWITCH";
            case SYSTEM_SHORTCUT_LAUNCH_ASSISTANT:
                    return "SYSTEM_SHORTCUT_LAUNCH_ASSISTANT";
            case SYSTEM_SHORTCUT_LAUNCH_VOICE_ASSISTANT:
                    return "SYSTEM_SHORTCUT_LAUNCH_VOICE_ASSISTANT";
            case SYSTEM_SHORTCUT_LAUNCH_SYSTEM_SETTINGS:
                    return "SYSTEM_SHORTCUT_LAUNCH_SYSTEM_SETTINGS";
            case SYSTEM_SHORTCUT_TOGGLE_NOTIFICATION_PANEL:
                    return "SYSTEM_SHORTCUT_TOGGLE_NOTIFICATION_PANEL";
            case SYSTEM_SHORTCUT_TOGGLE_TASKBAR:
                    return "SYSTEM_SHORTCUT_TOGGLE_TASKBAR";
            case SYSTEM_SHORTCUT_TAKE_SCREENSHOT:
                    return "SYSTEM_SHORTCUT_TAKE_SCREENSHOT";
            case SYSTEM_SHORTCUT_OPEN_SHORTCUT_HELPER:
                    return "SYSTEM_SHORTCUT_OPEN_SHORTCUT_HELPER";
            case SYSTEM_SHORTCUT_BRIGHTNESS_UP:
                    return "SYSTEM_SHORTCUT_BRIGHTNESS_UP";
            case SYSTEM_SHORTCUT_BRIGHTNESS_DOWN:
                    return "SYSTEM_SHORTCUT_BRIGHTNESS_DOWN";
            case SYSTEM_SHORTCUT_KEYBOARD_BACKLIGHT_UP:
                    return "SYSTEM_SHORTCUT_KEYBOARD_BACKLIGHT_UP";
            case SYSTEM_SHORTCUT_KEYBOARD_BACKLIGHT_DOWN:
                    return "SYSTEM_SHORTCUT_KEYBOARD_BACKLIGHT_DOWN";
            case SYSTEM_SHORTCUT_KEYBOARD_BACKLIGHT_TOGGLE:
                    return "SYSTEM_SHORTCUT_KEYBOARD_BACKLIGHT_TOGGLE";
            case SYSTEM_SHORTCUT_VOLUME_UP:
                    return "SYSTEM_SHORTCUT_VOLUME_UP";
            case SYSTEM_SHORTCUT_VOLUME_DOWN:
                    return "SYSTEM_SHORTCUT_VOLUME_DOWN";
            case SYSTEM_SHORTCUT_VOLUME_MUTE:
                    return "SYSTEM_SHORTCUT_VOLUME_MUTE";
            case SYSTEM_SHORTCUT_ALL_APPS:
                    return "SYSTEM_SHORTCUT_ALL_APPS";
            case SYSTEM_SHORTCUT_LAUNCH_SEARCH:
                    return "SYSTEM_SHORTCUT_LAUNCH_SEARCH";
            case SYSTEM_SHORTCUT_LANGUAGE_SWITCH:
                    return "SYSTEM_SHORTCUT_LANGUAGE_SWITCH";
            case SYSTEM_SHORTCUT_ACCESSIBILITY_ALL_APPS:
                    return "SYSTEM_SHORTCUT_ACCESSIBILITY_ALL_APPS";
            case SYSTEM_SHORTCUT_TOGGLE_CAPS_LOCK:
                    return "SYSTEM_SHORTCUT_TOGGLE_CAPS_LOCK";
            case SYSTEM_SHORTCUT_SYSTEM_MUTE:
                    return "SYSTEM_SHORTCUT_SYSTEM_MUTE";
            case SYSTEM_SHORTCUT_SPLIT_SCREEN_NAVIGATION:
                    return "SYSTEM_SHORTCUT_SPLIT_SCREEN_NAVIGATION";
            case SYSTEM_SHORTCUT_CHANGE_SPLITSCREEN_FOCUS:
                    return "SYSTEM_SHORTCUT_CHANGE_SPLITSCREEN_FOCUS";
            case SYSTEM_SHORTCUT_TRIGGER_BUG_REPORT:
                    return "SYSTEM_SHORTCUT_TRIGGER_BUG_REPORT";
            case SYSTEM_SHORTCUT_LOCK_SCREEN:
                    return "SYSTEM_SHORTCUT_LOCK_SCREEN";
            case SYSTEM_SHORTCUT_OPEN_NOTES:
                    return "SYSTEM_SHORTCUT_OPEN_NOTES";
            case SYSTEM_SHORTCUT_TOGGLE_POWER:
                    return "SYSTEM_SHORTCUT_TOGGLE_POWER";
            case SYSTEM_SHORTCUT_SYSTEM_NAVIGATION:
                    return "SYSTEM_SHORTCUT_SYSTEM_NAVIGATION";
            case SYSTEM_SHORTCUT_SLEEP:
                    return "SYSTEM_SHORTCUT_SLEEP";
            case SYSTEM_SHORTCUT_WAKEUP:
                    return "SYSTEM_SHORTCUT_WAKEUP";
            case SYSTEM_SHORTCUT_MEDIA_KEY:
                    return "SYSTEM_SHORTCUT_MEDIA_KEY";
            case SYSTEM_SHORTCUT_LAUNCH_DEFAULT_BROWSER:
                    return "SYSTEM_SHORTCUT_LAUNCH_DEFAULT_BROWSER";
            case SYSTEM_SHORTCUT_LAUNCH_DEFAULT_EMAIL:
                    return "SYSTEM_SHORTCUT_LAUNCH_DEFAULT_EMAIL";
            case SYSTEM_SHORTCUT_LAUNCH_DEFAULT_CONTACTS:
                    return "SYSTEM_SHORTCUT_LAUNCH_DEFAULT_CONTACTS";
            case SYSTEM_SHORTCUT_LAUNCH_DEFAULT_CALENDAR:
                    return "SYSTEM_SHORTCUT_LAUNCH_DEFAULT_CALENDAR";
            case SYSTEM_SHORTCUT_LAUNCH_DEFAULT_CALCULATOR:
                    return "SYSTEM_SHORTCUT_LAUNCH_DEFAULT_CALCULATOR";
            case SYSTEM_SHORTCUT_LAUNCH_DEFAULT_MUSIC:
                    return "SYSTEM_SHORTCUT_LAUNCH_DEFAULT_MUSIC";
            case SYSTEM_SHORTCUT_LAUNCH_DEFAULT_MAPS:
                    return "SYSTEM_SHORTCUT_LAUNCH_DEFAULT_MAPS";
            case SYSTEM_SHORTCUT_LAUNCH_DEFAULT_MESSAGING:
                    return "SYSTEM_SHORTCUT_LAUNCH_DEFAULT_MESSAGING";
            case SYSTEM_SHORTCUT_LAUNCH_DEFAULT_GALLERY:
                    return "SYSTEM_SHORTCUT_LAUNCH_DEFAULT_GALLERY";
            case SYSTEM_SHORTCUT_LAUNCH_DEFAULT_FILES:
                    return "SYSTEM_SHORTCUT_LAUNCH_DEFAULT_FILES";
            case SYSTEM_SHORTCUT_LAUNCH_DEFAULT_WEATHER:
                    return "SYSTEM_SHORTCUT_LAUNCH_DEFAULT_WEATHER";
            case SYSTEM_SHORTCUT_LAUNCH_DEFAULT_FITNESS:
                    return "SYSTEM_SHORTCUT_LAUNCH_DEFAULT_FITNESS";
            case SYSTEM_SHORTCUT_LAUNCH_APPLICATION_BY_PACKAGE_NAME:
                    return "SYSTEM_SHORTCUT_LAUNCH_APPLICATION_BY_PACKAGE_NAME";
            case SYSTEM_SHORTCUT_DESKTOP_MODE:
                    return "SYSTEM_SHORTCUT_DESKTOP_MODE";
            case SYSTEM_SHORTCUT_MULTI_WINDOW_NAVIGATION:
                    return "SYSTEM_SHORTCUT_MULTI_WINDOW_NAVIGATION";
            default: return Integer.toHexString(value);
        }
    }

    @DataClass.Generated.Member
    public KeyboardSystemShortcut(
            @NonNull int[] keycodes,
            int modifierState,
            @SystemShortcut int systemShortcut) {
        this.mKeycodes = keycodes;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mKeycodes);
        this.mModifierState = modifierState;
        this.mSystemShortcut = systemShortcut;

        if (!(mSystemShortcut == SYSTEM_SHORTCUT_UNSPECIFIED)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_HOME)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_RECENT_APPS)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_BACK)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_APP_SWITCH)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_LAUNCH_ASSISTANT)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_LAUNCH_VOICE_ASSISTANT)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_LAUNCH_SYSTEM_SETTINGS)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_TOGGLE_NOTIFICATION_PANEL)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_TOGGLE_TASKBAR)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_TAKE_SCREENSHOT)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_OPEN_SHORTCUT_HELPER)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_BRIGHTNESS_UP)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_BRIGHTNESS_DOWN)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_KEYBOARD_BACKLIGHT_UP)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_KEYBOARD_BACKLIGHT_DOWN)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_KEYBOARD_BACKLIGHT_TOGGLE)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_VOLUME_UP)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_VOLUME_DOWN)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_VOLUME_MUTE)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_ALL_APPS)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_LAUNCH_SEARCH)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_LANGUAGE_SWITCH)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_ACCESSIBILITY_ALL_APPS)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_TOGGLE_CAPS_LOCK)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_SYSTEM_MUTE)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_SPLIT_SCREEN_NAVIGATION)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_CHANGE_SPLITSCREEN_FOCUS)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_TRIGGER_BUG_REPORT)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_LOCK_SCREEN)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_OPEN_NOTES)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_TOGGLE_POWER)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_SYSTEM_NAVIGATION)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_SLEEP)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_WAKEUP)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_MEDIA_KEY)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_LAUNCH_DEFAULT_BROWSER)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_LAUNCH_DEFAULT_EMAIL)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_LAUNCH_DEFAULT_CONTACTS)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_LAUNCH_DEFAULT_CALENDAR)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_LAUNCH_DEFAULT_CALCULATOR)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_LAUNCH_DEFAULT_MUSIC)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_LAUNCH_DEFAULT_MAPS)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_LAUNCH_DEFAULT_MESSAGING)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_LAUNCH_DEFAULT_GALLERY)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_LAUNCH_DEFAULT_FILES)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_LAUNCH_DEFAULT_WEATHER)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_LAUNCH_DEFAULT_FITNESS)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_LAUNCH_APPLICATION_BY_PACKAGE_NAME)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_DESKTOP_MODE)
                && !(mSystemShortcut == SYSTEM_SHORTCUT_MULTI_WINDOW_NAVIGATION)) {
            throw new java.lang.IllegalArgumentException(
                    "systemShortcut was " + mSystemShortcut + " but must be one of: "
                            + "SYSTEM_SHORTCUT_UNSPECIFIED(" + SYSTEM_SHORTCUT_UNSPECIFIED + "), "
                            + "SYSTEM_SHORTCUT_HOME(" + SYSTEM_SHORTCUT_HOME + "), "
                            + "SYSTEM_SHORTCUT_RECENT_APPS(" + SYSTEM_SHORTCUT_RECENT_APPS + "), "
                            + "SYSTEM_SHORTCUT_BACK(" + SYSTEM_SHORTCUT_BACK + "), "
                            + "SYSTEM_SHORTCUT_APP_SWITCH(" + SYSTEM_SHORTCUT_APP_SWITCH + "), "
                            + "SYSTEM_SHORTCUT_LAUNCH_ASSISTANT(" + SYSTEM_SHORTCUT_LAUNCH_ASSISTANT + "), "
                            + "SYSTEM_SHORTCUT_LAUNCH_VOICE_ASSISTANT(" + SYSTEM_SHORTCUT_LAUNCH_VOICE_ASSISTANT + "), "
                            + "SYSTEM_SHORTCUT_LAUNCH_SYSTEM_SETTINGS(" + SYSTEM_SHORTCUT_LAUNCH_SYSTEM_SETTINGS + "), "
                            + "SYSTEM_SHORTCUT_TOGGLE_NOTIFICATION_PANEL(" + SYSTEM_SHORTCUT_TOGGLE_NOTIFICATION_PANEL + "), "
                            + "SYSTEM_SHORTCUT_TOGGLE_TASKBAR(" + SYSTEM_SHORTCUT_TOGGLE_TASKBAR + "), "
                            + "SYSTEM_SHORTCUT_TAKE_SCREENSHOT(" + SYSTEM_SHORTCUT_TAKE_SCREENSHOT + "), "
                            + "SYSTEM_SHORTCUT_OPEN_SHORTCUT_HELPER(" + SYSTEM_SHORTCUT_OPEN_SHORTCUT_HELPER + "), "
                            + "SYSTEM_SHORTCUT_BRIGHTNESS_UP(" + SYSTEM_SHORTCUT_BRIGHTNESS_UP + "), "
                            + "SYSTEM_SHORTCUT_BRIGHTNESS_DOWN(" + SYSTEM_SHORTCUT_BRIGHTNESS_DOWN + "), "
                            + "SYSTEM_SHORTCUT_KEYBOARD_BACKLIGHT_UP(" + SYSTEM_SHORTCUT_KEYBOARD_BACKLIGHT_UP + "), "
                            + "SYSTEM_SHORTCUT_KEYBOARD_BACKLIGHT_DOWN(" + SYSTEM_SHORTCUT_KEYBOARD_BACKLIGHT_DOWN + "), "
                            + "SYSTEM_SHORTCUT_KEYBOARD_BACKLIGHT_TOGGLE(" + SYSTEM_SHORTCUT_KEYBOARD_BACKLIGHT_TOGGLE + "), "
                            + "SYSTEM_SHORTCUT_VOLUME_UP(" + SYSTEM_SHORTCUT_VOLUME_UP + "), "
                            + "SYSTEM_SHORTCUT_VOLUME_DOWN(" + SYSTEM_SHORTCUT_VOLUME_DOWN + "), "
                            + "SYSTEM_SHORTCUT_VOLUME_MUTE(" + SYSTEM_SHORTCUT_VOLUME_MUTE + "), "
                            + "SYSTEM_SHORTCUT_ALL_APPS(" + SYSTEM_SHORTCUT_ALL_APPS + "), "
                            + "SYSTEM_SHORTCUT_LAUNCH_SEARCH(" + SYSTEM_SHORTCUT_LAUNCH_SEARCH + "), "
                            + "SYSTEM_SHORTCUT_LANGUAGE_SWITCH(" + SYSTEM_SHORTCUT_LANGUAGE_SWITCH + "), "
                            + "SYSTEM_SHORTCUT_ACCESSIBILITY_ALL_APPS(" + SYSTEM_SHORTCUT_ACCESSIBILITY_ALL_APPS + "), "
                            + "SYSTEM_SHORTCUT_TOGGLE_CAPS_LOCK(" + SYSTEM_SHORTCUT_TOGGLE_CAPS_LOCK + "), "
                            + "SYSTEM_SHORTCUT_SYSTEM_MUTE(" + SYSTEM_SHORTCUT_SYSTEM_MUTE + "), "
                            + "SYSTEM_SHORTCUT_SPLIT_SCREEN_NAVIGATION(" + SYSTEM_SHORTCUT_SPLIT_SCREEN_NAVIGATION + "), "
                            + "SYSTEM_SHORTCUT_CHANGE_SPLITSCREEN_FOCUS(" + SYSTEM_SHORTCUT_CHANGE_SPLITSCREEN_FOCUS + "), "
                            + "SYSTEM_SHORTCUT_TRIGGER_BUG_REPORT(" + SYSTEM_SHORTCUT_TRIGGER_BUG_REPORT + "), "
                            + "SYSTEM_SHORTCUT_LOCK_SCREEN(" + SYSTEM_SHORTCUT_LOCK_SCREEN + "), "
                            + "SYSTEM_SHORTCUT_OPEN_NOTES(" + SYSTEM_SHORTCUT_OPEN_NOTES + "), "
                            + "SYSTEM_SHORTCUT_TOGGLE_POWER(" + SYSTEM_SHORTCUT_TOGGLE_POWER + "), "
                            + "SYSTEM_SHORTCUT_SYSTEM_NAVIGATION(" + SYSTEM_SHORTCUT_SYSTEM_NAVIGATION + "), "
                            + "SYSTEM_SHORTCUT_SLEEP(" + SYSTEM_SHORTCUT_SLEEP + "), "
                            + "SYSTEM_SHORTCUT_WAKEUP(" + SYSTEM_SHORTCUT_WAKEUP + "), "
                            + "SYSTEM_SHORTCUT_MEDIA_KEY(" + SYSTEM_SHORTCUT_MEDIA_KEY + "), "
                            + "SYSTEM_SHORTCUT_LAUNCH_DEFAULT_BROWSER(" + SYSTEM_SHORTCUT_LAUNCH_DEFAULT_BROWSER + "), "
                            + "SYSTEM_SHORTCUT_LAUNCH_DEFAULT_EMAIL(" + SYSTEM_SHORTCUT_LAUNCH_DEFAULT_EMAIL + "), "
                            + "SYSTEM_SHORTCUT_LAUNCH_DEFAULT_CONTACTS(" + SYSTEM_SHORTCUT_LAUNCH_DEFAULT_CONTACTS + "), "
                            + "SYSTEM_SHORTCUT_LAUNCH_DEFAULT_CALENDAR(" + SYSTEM_SHORTCUT_LAUNCH_DEFAULT_CALENDAR + "), "
                            + "SYSTEM_SHORTCUT_LAUNCH_DEFAULT_CALCULATOR(" + SYSTEM_SHORTCUT_LAUNCH_DEFAULT_CALCULATOR + "), "
                            + "SYSTEM_SHORTCUT_LAUNCH_DEFAULT_MUSIC(" + SYSTEM_SHORTCUT_LAUNCH_DEFAULT_MUSIC + "), "
                            + "SYSTEM_SHORTCUT_LAUNCH_DEFAULT_MAPS(" + SYSTEM_SHORTCUT_LAUNCH_DEFAULT_MAPS + "), "
                            + "SYSTEM_SHORTCUT_LAUNCH_DEFAULT_MESSAGING(" + SYSTEM_SHORTCUT_LAUNCH_DEFAULT_MESSAGING + "), "
                            + "SYSTEM_SHORTCUT_LAUNCH_DEFAULT_GALLERY(" + SYSTEM_SHORTCUT_LAUNCH_DEFAULT_GALLERY + "), "
                            + "SYSTEM_SHORTCUT_LAUNCH_DEFAULT_FILES(" + SYSTEM_SHORTCUT_LAUNCH_DEFAULT_FILES + "), "
                            + "SYSTEM_SHORTCUT_LAUNCH_DEFAULT_WEATHER(" + SYSTEM_SHORTCUT_LAUNCH_DEFAULT_WEATHER + "), "
                            + "SYSTEM_SHORTCUT_LAUNCH_DEFAULT_FITNESS(" + SYSTEM_SHORTCUT_LAUNCH_DEFAULT_FITNESS + "), "
                            + "SYSTEM_SHORTCUT_LAUNCH_APPLICATION_BY_PACKAGE_NAME(" + SYSTEM_SHORTCUT_LAUNCH_APPLICATION_BY_PACKAGE_NAME + "), "
                            + "SYSTEM_SHORTCUT_DESKTOP_MODE(" + SYSTEM_SHORTCUT_DESKTOP_MODE + "), "
                            + "SYSTEM_SHORTCUT_MULTI_WINDOW_NAVIGATION(" + SYSTEM_SHORTCUT_MULTI_WINDOW_NAVIGATION + ")");
        }


        // onConstructed(); // You can define this method to get a callback
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
    public @SystemShortcut int getSystemShortcut() {
        return mSystemShortcut;
    }

    @Override
    @DataClass.Generated.Member
    public String toString() {
        // You can override field toString logic by defining methods like:
        // String fieldNameToString() { ... }

        return "KeyboardSystemShortcut { " +
                "keycodes = " + java.util.Arrays.toString(mKeycodes) + ", " +
                "modifierState = " + mModifierState + ", " +
                "systemShortcut = " + systemShortcutToString(mSystemShortcut) +
        " }";
    }

    @Override
    @DataClass.Generated.Member
    public boolean equals(@Nullable Object o) {
        // You can override field equality logic by defining either of the methods like:
        // boolean fieldNameEquals(KeyboardSystemShortcut other) { ... }
        // boolean fieldNameEquals(FieldType otherValue) { ... }

        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        @SuppressWarnings("unchecked")
        KeyboardSystemShortcut that = (KeyboardSystemShortcut) o;
        //noinspection PointlessBooleanExpression
        return true
                && java.util.Arrays.equals(mKeycodes, that.mKeycodes)
                && mModifierState == that.mModifierState
                && mSystemShortcut == that.mSystemShortcut;
    }

    @Override
    @DataClass.Generated.Member
    public int hashCode() {
        // You can override field hashCode logic by defining methods like:
        // int fieldNameHashCode() { ... }

        int _hash = 1;
        _hash = 31 * _hash + java.util.Arrays.hashCode(mKeycodes);
        _hash = 31 * _hash + mModifierState;
        _hash = 31 * _hash + mSystemShortcut;
        return _hash;
    }

    @DataClass.Generated(
            time = 1722890917041L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/core/java/android/hardware/input/KeyboardSystemShortcut.java",
            inputSignatures = "private static final  java.lang.String TAG\nprivate final @android.annotation.NonNull int[] mKeycodes\nprivate final  int mModifierState\nprivate final @android.hardware.input.KeyboardSystemShortcut.SystemShortcut int mSystemShortcut\npublic static final  int SYSTEM_SHORTCUT_UNSPECIFIED\npublic static final  int SYSTEM_SHORTCUT_HOME\npublic static final  int SYSTEM_SHORTCUT_RECENT_APPS\npublic static final  int SYSTEM_SHORTCUT_BACK\npublic static final  int SYSTEM_SHORTCUT_APP_SWITCH\npublic static final  int SYSTEM_SHORTCUT_LAUNCH_ASSISTANT\npublic static final  int SYSTEM_SHORTCUT_LAUNCH_VOICE_ASSISTANT\npublic static final  int SYSTEM_SHORTCUT_LAUNCH_SYSTEM_SETTINGS\npublic static final  int SYSTEM_SHORTCUT_TOGGLE_NOTIFICATION_PANEL\npublic static final  int SYSTEM_SHORTCUT_TOGGLE_TASKBAR\npublic static final  int SYSTEM_SHORTCUT_TAKE_SCREENSHOT\npublic static final  int SYSTEM_SHORTCUT_OPEN_SHORTCUT_HELPER\npublic static final  int SYSTEM_SHORTCUT_BRIGHTNESS_UP\npublic static final  int SYSTEM_SHORTCUT_BRIGHTNESS_DOWN\npublic static final  int SYSTEM_SHORTCUT_KEYBOARD_BACKLIGHT_UP\npublic static final  int SYSTEM_SHORTCUT_KEYBOARD_BACKLIGHT_DOWN\npublic static final  int SYSTEM_SHORTCUT_KEYBOARD_BACKLIGHT_TOGGLE\npublic static final  int SYSTEM_SHORTCUT_VOLUME_UP\npublic static final  int SYSTEM_SHORTCUT_VOLUME_DOWN\npublic static final  int SYSTEM_SHORTCUT_VOLUME_MUTE\npublic static final  int SYSTEM_SHORTCUT_ALL_APPS\npublic static final  int SYSTEM_SHORTCUT_LAUNCH_SEARCH\npublic static final  int SYSTEM_SHORTCUT_LANGUAGE_SWITCH\npublic static final  int SYSTEM_SHORTCUT_ACCESSIBILITY_ALL_APPS\npublic static final  int SYSTEM_SHORTCUT_TOGGLE_CAPS_LOCK\npublic static final  int SYSTEM_SHORTCUT_SYSTEM_MUTE\npublic static final  int SYSTEM_SHORTCUT_SPLIT_SCREEN_NAVIGATION\npublic static final  int SYSTEM_SHORTCUT_CHANGE_SPLITSCREEN_FOCUS\npublic static final  int SYSTEM_SHORTCUT_TRIGGER_BUG_REPORT\npublic static final  int SYSTEM_SHORTCUT_LOCK_SCREEN\npublic static final  int SYSTEM_SHORTCUT_OPEN_NOTES\npublic static final  int SYSTEM_SHORTCUT_TOGGLE_POWER\npublic static final  int SYSTEM_SHORTCUT_SYSTEM_NAVIGATION\npublic static final  int SYSTEM_SHORTCUT_SLEEP\npublic static final  int SYSTEM_SHORTCUT_WAKEUP\npublic static final  int SYSTEM_SHORTCUT_MEDIA_KEY\npublic static final  int SYSTEM_SHORTCUT_LAUNCH_DEFAULT_BROWSER\npublic static final  int SYSTEM_SHORTCUT_LAUNCH_DEFAULT_EMAIL\npublic static final  int SYSTEM_SHORTCUT_LAUNCH_DEFAULT_CONTACTS\npublic static final  int SYSTEM_SHORTCUT_LAUNCH_DEFAULT_CALENDAR\npublic static final  int SYSTEM_SHORTCUT_LAUNCH_DEFAULT_CALCULATOR\npublic static final  int SYSTEM_SHORTCUT_LAUNCH_DEFAULT_MUSIC\npublic static final  int SYSTEM_SHORTCUT_LAUNCH_DEFAULT_MAPS\npublic static final  int SYSTEM_SHORTCUT_LAUNCH_DEFAULT_MESSAGING\npublic static final  int SYSTEM_SHORTCUT_LAUNCH_DEFAULT_GALLERY\npublic static final  int SYSTEM_SHORTCUT_LAUNCH_DEFAULT_FILES\npublic static final  int SYSTEM_SHORTCUT_LAUNCH_DEFAULT_WEATHER\npublic static final  int SYSTEM_SHORTCUT_LAUNCH_DEFAULT_FITNESS\npublic static final  int SYSTEM_SHORTCUT_LAUNCH_APPLICATION_BY_PACKAGE_NAME\npublic static final  int SYSTEM_SHORTCUT_DESKTOP_MODE\npublic static final  int SYSTEM_SHORTCUT_MULTI_WINDOW_NAVIGATION\nclass KeyboardSystemShortcut extends java.lang.Object implements []\n@com.android.internal.util.DataClass(genToString=true, genEqualsHashCode=true)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
