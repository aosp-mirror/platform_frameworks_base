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
import android.view.Display;
import android.view.KeyCharacterMap;

import com.android.internal.util.FrameworkStatsLog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Provides information about the keyboard gesture event being triggered by an external keyboard.
 *
 * @hide
 */
public final class KeyGestureEvent {

    @NonNull
    private AidlKeyGestureEvent mKeyGestureEvent;

    public static final int KEY_GESTURE_TYPE_UNSPECIFIED = 0;
    public static final int KEY_GESTURE_TYPE_HOME = 1;
    public static final int KEY_GESTURE_TYPE_RECENT_APPS = 2;
    public static final int KEY_GESTURE_TYPE_BACK = 3;
    public static final int KEY_GESTURE_TYPE_APP_SWITCH = 4;
    public static final int KEY_GESTURE_TYPE_LAUNCH_ASSISTANT = 5;
    public static final int KEY_GESTURE_TYPE_LAUNCH_VOICE_ASSISTANT = 6;
    public static final int KEY_GESTURE_TYPE_LAUNCH_SYSTEM_SETTINGS = 7;
    public static final int KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL = 8;
    public static final int KEY_GESTURE_TYPE_TOGGLE_TASKBAR = 9;
    public static final int KEY_GESTURE_TYPE_TAKE_SCREENSHOT = 10;
    public static final int KEY_GESTURE_TYPE_OPEN_SHORTCUT_HELPER = 11;
    public static final int KEY_GESTURE_TYPE_BRIGHTNESS_UP = 12;
    public static final int KEY_GESTURE_TYPE_BRIGHTNESS_DOWN = 13;
    public static final int KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_UP = 14;
    public static final int KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_DOWN = 15;
    public static final int KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_TOGGLE = 16;
    public static final int KEY_GESTURE_TYPE_VOLUME_UP = 17;
    public static final int KEY_GESTURE_TYPE_VOLUME_DOWN = 18;
    public static final int KEY_GESTURE_TYPE_VOLUME_MUTE = 19;
    public static final int KEY_GESTURE_TYPE_ALL_APPS = 20;
    public static final int KEY_GESTURE_TYPE_LAUNCH_SEARCH = 21;
    public static final int KEY_GESTURE_TYPE_LANGUAGE_SWITCH = 22;
    public static final int KEY_GESTURE_TYPE_ACCESSIBILITY_ALL_APPS = 23;
    public static final int KEY_GESTURE_TYPE_TOGGLE_CAPS_LOCK = 24;
    public static final int KEY_GESTURE_TYPE_SYSTEM_MUTE = 25;
    public static final int KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_LEFT = 26;
    public static final int KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_RIGHT = 27;
    public static final int KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_LEFT = 28;
    public static final int KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_RIGHT = 29;
    public static final int KEY_GESTURE_TYPE_TRIGGER_BUG_REPORT = 30;
    public static final int KEY_GESTURE_TYPE_LOCK_SCREEN = 31;
    public static final int KEY_GESTURE_TYPE_OPEN_NOTES = 32;
    public static final int KEY_GESTURE_TYPE_TOGGLE_POWER = 33;
    public static final int KEY_GESTURE_TYPE_SYSTEM_NAVIGATION = 34;
    public static final int KEY_GESTURE_TYPE_SLEEP = 35;
    public static final int KEY_GESTURE_TYPE_WAKEUP = 36;
    public static final int KEY_GESTURE_TYPE_MEDIA_KEY = 37;
    public static final int KEY_GESTURE_TYPE_LAUNCH_DEFAULT_BROWSER = 38;
    public static final int KEY_GESTURE_TYPE_LAUNCH_DEFAULT_EMAIL = 39;
    public static final int KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CONTACTS = 40;
    public static final int KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALENDAR = 41;
    public static final int KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALCULATOR = 42;
    public static final int KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MUSIC = 43;
    public static final int KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MAPS = 44;
    public static final int KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MESSAGING = 45;
    public static final int KEY_GESTURE_TYPE_LAUNCH_DEFAULT_GALLERY = 46;
    public static final int KEY_GESTURE_TYPE_LAUNCH_DEFAULT_FILES = 47;
    public static final int KEY_GESTURE_TYPE_LAUNCH_DEFAULT_WEATHER = 48;
    public static final int KEY_GESTURE_TYPE_LAUNCH_DEFAULT_FITNESS = 49;
    public static final int KEY_GESTURE_TYPE_LAUNCH_APPLICATION_BY_PACKAGE_NAME = 50;
    public static final int KEY_GESTURE_TYPE_DESKTOP_MODE = 51;
    public static final int KEY_GESTURE_TYPE_MULTI_WINDOW_NAVIGATION = 52;
    public static final int KEY_GESTURE_TYPE_RECENT_APPS_SWITCHER = 53;

    public static final int FLAG_CANCELLED = 1;

    // NOTE: Valid KeyGestureEvent streams:
    //       - GESTURE_START -> GESTURE_CANCEL
    //       - GESTURE_START -> GESTURE_COMPLETE
    //       - GESTURE_COMPLETE

    /** Key gesture started (e.g. Key down of the relevant key) */
    public static final int ACTION_GESTURE_START = 1;
    /** Key gesture completed (e.g. Key up of the relevant key) */
    public static final int ACTION_GESTURE_COMPLETE = 2;

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
            KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_LEFT,
            KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_RIGHT,
            KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_LEFT,
            KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_RIGHT,
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
            KEY_GESTURE_TYPE_MULTI_WINDOW_NAVIGATION,
            KEY_GESTURE_TYPE_RECENT_APPS_SWITCHER
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface KeyGestureType {
    }

    public KeyGestureEvent(@NonNull AidlKeyGestureEvent keyGestureEvent) {
        this.mKeyGestureEvent = keyGestureEvent;
    }

    /**
     * Tests whether this keyboard shortcut event has the given modifiers (i.e. all of the given
     * modifiers were pressed when this shortcut was triggered).
     */
    public boolean hasModifiers(int modifiers) {
        return (getModifierState() & modifiers) == modifiers;
    }

    /**
     * Key gesture event builder used to create a KeyGestureEvent for tests in Java.
     *
     * @hide
     */
    public static class Builder {
        private int mDeviceId = KeyCharacterMap.VIRTUAL_KEYBOARD;
        private int[] mKeycodes = new int[0];
        private int mModifierState = 0;
        @KeyGestureType
        private int mKeyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_UNSPECIFIED;
        private int mAction = KeyGestureEvent.ACTION_GESTURE_COMPLETE;
        private int mDisplayId = Display.DEFAULT_DISPLAY;
        private int mFlags = 0;

        /**
         * @see KeyGestureEvent#getDeviceId()
         */
        public Builder setDeviceId(int deviceId) {
            mDeviceId = deviceId;
            return this;
        }

        /**
         * @see KeyGestureEvent#getKeycodes()
         */
        public Builder setKeycodes(@NonNull int[] keycodes) {
            mKeycodes = keycodes;
            return this;
        }

        /**
         * @see KeyGestureEvent#getModifierState()
         */
        public Builder setModifierState(int modifierState) {
            mModifierState = modifierState;
            return this;
        }

        /**
         * @see KeyGestureEvent#getKeyGestureType()
         */
        public Builder setKeyGestureType(@KeyGestureEvent.KeyGestureType int keyGestureType) {
            mKeyGestureType = keyGestureType;
            return this;
        }

        /**
         * @see KeyGestureEvent#getAction()
         */
        public Builder setAction(int action) {
            mAction = action;
            return this;
        }

        /**
         * @see KeyGestureEvent#getDisplayId()
         */
        public Builder setDisplayId(int displayId) {
            mDisplayId = displayId;
            return this;
        }

        /**
         * @see KeyGestureEvent#getFlags()
         */
        public Builder setFlags(int flags) {
            mFlags = flags;
            return this;
        }

        /**
         * Build {@link KeyGestureEvent}
         */
        public KeyGestureEvent build() {
            AidlKeyGestureEvent event = new AidlKeyGestureEvent();
            event.deviceId = mDeviceId;
            event.keycodes = mKeycodes;
            event.modifierState = mModifierState;
            event.gestureType = mKeyGestureType;
            event.action = mAction;
            event.displayId = mDisplayId;
            event.flags = mFlags;
            return new KeyGestureEvent(event);
        }
    }

    public int getDeviceId() {
        return mKeyGestureEvent.deviceId;
    }

    public @NonNull int[] getKeycodes() {
        return mKeyGestureEvent.keycodes;
    }

    public int getModifierState() {
        return mKeyGestureEvent.modifierState;
    }

    public @KeyGestureType int getKeyGestureType() {
        return mKeyGestureEvent.gestureType;
    }

    public int getAction() {
        return mKeyGestureEvent.action;
    }

    public int getDisplayId() {
        return mKeyGestureEvent.displayId;
    }

    public int getFlags() {
        return mKeyGestureEvent.flags;
    }

    public boolean isCancelled() {
        return (mKeyGestureEvent.flags & FLAG_CANCELLED) != 0;
    }

    @Override
    public String toString() {
        return "KeyGestureEvent { "
                + "deviceId = " + mKeyGestureEvent.deviceId + ", "
                + "keycodes = " + java.util.Arrays.toString(mKeyGestureEvent.keycodes) + ", "
                + "modifierState = " + mKeyGestureEvent.modifierState + ", "
                + "keyGestureType = " + keyGestureTypeToString(mKeyGestureEvent.gestureType) + ", "
                + "action = " + mKeyGestureEvent.action + ", "
                + "displayId = " + mKeyGestureEvent.displayId + ", "
                + "flags = " + mKeyGestureEvent.flags
                + " }";
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        KeyGestureEvent that = (KeyGestureEvent) o;
        return mKeyGestureEvent.deviceId == that.mKeyGestureEvent.deviceId
                && java.util.Arrays.equals(mKeyGestureEvent.keycodes, that.mKeyGestureEvent.keycodes)
                && mKeyGestureEvent.modifierState == that.mKeyGestureEvent.modifierState
                && mKeyGestureEvent.gestureType == that.mKeyGestureEvent.gestureType
                && mKeyGestureEvent.action == that.mKeyGestureEvent.action
                && mKeyGestureEvent.displayId == that.mKeyGestureEvent.displayId
                && mKeyGestureEvent.flags == that.mKeyGestureEvent.flags;
    }

    @Override
    public int hashCode() {
        int _hash = 1;
        _hash = 31 * _hash + mKeyGestureEvent.deviceId;
        _hash = 31 * _hash + java.util.Arrays.hashCode(mKeyGestureEvent.keycodes);
        _hash = 31 * _hash + mKeyGestureEvent.modifierState;
        _hash = 31 * _hash + mKeyGestureEvent.gestureType;
        _hash = 31 * _hash + mKeyGestureEvent.action;
        _hash = 31 * _hash + mKeyGestureEvent.displayId;
        _hash = 31 * _hash + mKeyGestureEvent.flags;
        return _hash;
    }

    /**
     * Convert KeyGestureEvent type to corresponding log event got KeyboardSystemsEvent
     */
    public static int keyGestureTypeToLogEvent(@KeyGestureType int value) {
        switch (value) {
            case KEY_GESTURE_TYPE_HOME:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__HOME;
            case KEY_GESTURE_TYPE_RECENT_APPS:
            case KEY_GESTURE_TYPE_RECENT_APPS_SWITCHER:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__RECENT_APPS;
            case KEY_GESTURE_TYPE_BACK:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__BACK;
            case KEY_GESTURE_TYPE_APP_SWITCH:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__APP_SWITCH;
            case KEY_GESTURE_TYPE_LAUNCH_ASSISTANT:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_ASSISTANT;
            case KEY_GESTURE_TYPE_LAUNCH_VOICE_ASSISTANT:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_VOICE_ASSISTANT;
            case KEY_GESTURE_TYPE_LAUNCH_SYSTEM_SETTINGS:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_SYSTEM_SETTINGS;
            case KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__TOGGLE_NOTIFICATION_PANEL;
            case KEY_GESTURE_TYPE_TOGGLE_TASKBAR:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__TOGGLE_TASKBAR;
            case KEY_GESTURE_TYPE_TAKE_SCREENSHOT:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__TAKE_SCREENSHOT;
            case KEY_GESTURE_TYPE_OPEN_SHORTCUT_HELPER:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__OPEN_SHORTCUT_HELPER;
            case KEY_GESTURE_TYPE_BRIGHTNESS_UP:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__BRIGHTNESS_UP;
            case KEY_GESTURE_TYPE_BRIGHTNESS_DOWN:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__BRIGHTNESS_DOWN;
            case KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_UP:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__KEYBOARD_BACKLIGHT_UP;
            case KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_DOWN:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__KEYBOARD_BACKLIGHT_DOWN;
            case KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_TOGGLE:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__KEYBOARD_BACKLIGHT_TOGGLE;
            case KEY_GESTURE_TYPE_VOLUME_UP:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__VOLUME_UP;
            case KEY_GESTURE_TYPE_VOLUME_DOWN:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__VOLUME_DOWN;
            case KEY_GESTURE_TYPE_VOLUME_MUTE:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__VOLUME_MUTE;
            case KEY_GESTURE_TYPE_ALL_APPS:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__ALL_APPS;
            case KEY_GESTURE_TYPE_LAUNCH_SEARCH:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_SEARCH;
            case KEY_GESTURE_TYPE_LANGUAGE_SWITCH:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LANGUAGE_SWITCH;
            case KEY_GESTURE_TYPE_ACCESSIBILITY_ALL_APPS:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__ACCESSIBILITY_ALL_APPS;
            case KEY_GESTURE_TYPE_TOGGLE_CAPS_LOCK:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__TOGGLE_CAPS_LOCK;
            case KEY_GESTURE_TYPE_SYSTEM_MUTE:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__SYSTEM_MUTE;
            case KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_LEFT:
            case KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_RIGHT:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__SPLIT_SCREEN_NAVIGATION;
            case KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_LEFT:
            case KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_RIGHT:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__CHANGE_SPLITSCREEN_FOCUS;
            case KEY_GESTURE_TYPE_TRIGGER_BUG_REPORT:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__TRIGGER_BUG_REPORT;
            case KEY_GESTURE_TYPE_LOCK_SCREEN:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LOCK_SCREEN;
            case KEY_GESTURE_TYPE_OPEN_NOTES:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__OPEN_NOTES;
            case KEY_GESTURE_TYPE_TOGGLE_POWER:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__TOGGLE_POWER;
            case KEY_GESTURE_TYPE_SYSTEM_NAVIGATION:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__SYSTEM_NAVIGATION;
            case KEY_GESTURE_TYPE_SLEEP:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__SLEEP;
            case KEY_GESTURE_TYPE_WAKEUP:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__WAKEUP;
            case KEY_GESTURE_TYPE_MEDIA_KEY:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__MEDIA_KEY;
            case KEY_GESTURE_TYPE_LAUNCH_DEFAULT_BROWSER:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_BROWSER;
            case KEY_GESTURE_TYPE_LAUNCH_DEFAULT_EMAIL:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_EMAIL;
            case KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CONTACTS:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_CONTACTS;
            case KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALENDAR:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_CALENDAR;
            case KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALCULATOR:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_CALCULATOR;
            case KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MUSIC:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_MUSIC;
            case KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MAPS:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_MAPS;
            case KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MESSAGING:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_MESSAGING;
            case KEY_GESTURE_TYPE_LAUNCH_DEFAULT_GALLERY:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_GALLERY;
            case KEY_GESTURE_TYPE_LAUNCH_DEFAULT_FILES:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_FILES;
            case KEY_GESTURE_TYPE_LAUNCH_DEFAULT_WEATHER:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_WEATHER;
            case KEY_GESTURE_TYPE_LAUNCH_DEFAULT_FITNESS:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_FITNESS;
            case KEY_GESTURE_TYPE_LAUNCH_APPLICATION_BY_PACKAGE_NAME:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_APPLICATION_BY_PACKAGE_NAME;
            case KEY_GESTURE_TYPE_DESKTOP_MODE:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__DESKTOP_MODE;
            case KEY_GESTURE_TYPE_MULTI_WINDOW_NAVIGATION:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__MULTI_WINDOW_NAVIGATION;
            default:
                return FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__UNSPECIFIED;
        }
    }

    private static String keyGestureTypeToString(@KeyGestureType int value) {
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
            case KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_LEFT:
                return "KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_LEFT";
            case KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_RIGHT:
                return "KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_RIGHT";
            case KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_LEFT:
                return "KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_LEFT";
            case KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_RIGHT:
                return "KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_RIGHT";
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
            case KEY_GESTURE_TYPE_RECENT_APPS_SWITCHER:
                return "KEY_GESTURE_TYPE_RECENT_APPS_SWITCHER";
            default:
                return Integer.toHexString(value);
        }
    }
}
