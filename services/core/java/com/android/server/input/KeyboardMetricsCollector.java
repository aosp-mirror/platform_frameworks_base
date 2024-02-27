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

package com.android.server.input;

import static android.hardware.input.KeyboardLayoutSelectionResult.LAYOUT_SELECTION_CRITERIA_USER;
import static android.hardware.input.KeyboardLayoutSelectionResult.LAYOUT_SELECTION_CRITERIA_DEVICE;
import static android.hardware.input.KeyboardLayoutSelectionResult.LAYOUT_SELECTION_CRITERIA_VIRTUAL_KEYBOARD;
import static android.hardware.input.KeyboardLayoutSelectionResult.LAYOUT_SELECTION_CRITERIA_DEFAULT;
import static android.hardware.input.KeyboardLayoutSelectionResult.layoutSelectionCriteriaToString;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.role.RoleManager;
import android.content.Intent;
import android.hardware.input.KeyboardLayout;
import android.hardware.input.KeyboardLayoutSelectionResult.LayoutSelectionCriteria;
import android.icu.util.ULocale;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.inputmethod.InputMethodSubtype;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.KeyboardConfiguredProto.KeyboardLayoutConfig;
import com.android.internal.os.KeyboardConfiguredProto.RepeatedKeyboardLayoutConfig;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.policy.ModifierShortcutManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Collect Keyboard metrics
 */
public final class KeyboardMetricsCollector {
    private static final String TAG = "KeyboardMetricCollector";

    // To enable these logs, run: 'adb shell setprop log.tag.KeyboardMetricCollector DEBUG'
    // (requires restart)
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    @VisibleForTesting
    static final String DEFAULT_LAYOUT_NAME = "Default";

    @VisibleForTesting
    public static final String DEFAULT_LANGUAGE_TAG = "None";

    public enum KeyboardLogEvent {
        UNSPECIFIED(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__UNSPECIFIED,
                "INVALID_KEYBOARD_EVENT"),
        HOME(FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__HOME,
                "HOME"),
        RECENT_APPS(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__RECENT_APPS,
                "RECENT_APPS"),
        BACK(FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__BACK,
                "BACK"),
        APP_SWITCH(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__APP_SWITCH,
                "APP_SWITCH"),
        LAUNCH_ASSISTANT(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_ASSISTANT,
                "LAUNCH_ASSISTANT"),
        LAUNCH_VOICE_ASSISTANT(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_VOICE_ASSISTANT,
                "LAUNCH_VOICE_ASSISTANT"),
        LAUNCH_SYSTEM_SETTINGS(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_SYSTEM_SETTINGS,
                "LAUNCH_SYSTEM_SETTINGS"),
        TOGGLE_NOTIFICATION_PANEL(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__TOGGLE_NOTIFICATION_PANEL,
                "TOGGLE_NOTIFICATION_PANEL"),
        TOGGLE_TASKBAR(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__TOGGLE_TASKBAR,
                "TOGGLE_TASKBAR"),
        TAKE_SCREENSHOT(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__TAKE_SCREENSHOT,
                "TAKE_SCREENSHOT"),
        OPEN_SHORTCUT_HELPER(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__OPEN_SHORTCUT_HELPER,
                "OPEN_SHORTCUT_HELPER"),
        BRIGHTNESS_UP(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__BRIGHTNESS_UP,
                "BRIGHTNESS_UP"),
        BRIGHTNESS_DOWN(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__BRIGHTNESS_DOWN,
                "BRIGHTNESS_DOWN"),
        KEYBOARD_BACKLIGHT_UP(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__KEYBOARD_BACKLIGHT_UP,
                "KEYBOARD_BACKLIGHT_UP"),
        KEYBOARD_BACKLIGHT_DOWN(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__KEYBOARD_BACKLIGHT_DOWN,
                "KEYBOARD_BACKLIGHT_DOWN"),
        KEYBOARD_BACKLIGHT_TOGGLE(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__KEYBOARD_BACKLIGHT_TOGGLE,
                "KEYBOARD_BACKLIGHT_TOGGLE"),
        VOLUME_UP(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__VOLUME_UP,
                "VOLUME_UP"),
        VOLUME_DOWN(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__VOLUME_DOWN,
                "VOLUME_DOWN"),
        VOLUME_MUTE(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__VOLUME_MUTE,
                "VOLUME_MUTE"),
        ALL_APPS(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__ALL_APPS,
                "ALL_APPS"),
        LAUNCH_SEARCH(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_SEARCH,
                "LAUNCH_SEARCH"),
        LANGUAGE_SWITCH(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LANGUAGE_SWITCH,
                "LANGUAGE_SWITCH"),
        ACCESSIBILITY_ALL_APPS(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__ACCESSIBILITY_ALL_APPS,
                "ACCESSIBILITY_ALL_APPS"),
        TOGGLE_CAPS_LOCK(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__TOGGLE_CAPS_LOCK,
                "TOGGLE_CAPS_LOCK"),
        SYSTEM_MUTE(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__SYSTEM_MUTE,
                "SYSTEM_MUTE"),
        SPLIT_SCREEN_NAVIGATION(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__SPLIT_SCREEN_NAVIGATION,
                "SPLIT_SCREEN_NAVIGATION"),
        TRIGGER_BUG_REPORT(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__TRIGGER_BUG_REPORT,
                "TRIGGER_BUG_REPORT"),
        LOCK_SCREEN(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LOCK_SCREEN,
                "LOCK_SCREEN"),
        OPEN_NOTES(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__OPEN_NOTES,
                "OPEN_NOTES"),
        TOGGLE_POWER(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__TOGGLE_POWER,
                "TOGGLE_POWER"),
        SYSTEM_NAVIGATION(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__SYSTEM_NAVIGATION,
                "SYSTEM_NAVIGATION"),
        SLEEP(FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__SLEEP,
                "SLEEP"),
        WAKEUP(FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__WAKEUP,
                "WAKEUP"),
        MEDIA_KEY(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__MEDIA_KEY,
                "MEDIA_KEY"),
        LAUNCH_DEFAULT_BROWSER(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_BROWSER,
                "LAUNCH_DEFAULT_BROWSER"),
        LAUNCH_DEFAULT_EMAIL(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_EMAIL,
                "LAUNCH_DEFAULT_EMAIL"),
        LAUNCH_DEFAULT_CONTACTS(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_CONTACTS,
                "LAUNCH_DEFAULT_CONTACTS"),
        LAUNCH_DEFAULT_CALENDAR(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_CALENDAR,
                "LAUNCH_DEFAULT_CALENDAR"),
        LAUNCH_DEFAULT_CALCULATOR(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_CALCULATOR,
                "LAUNCH_DEFAULT_CALCULATOR"),
        LAUNCH_DEFAULT_MUSIC(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_MUSIC,
                "LAUNCH_DEFAULT_MUSIC"),
        LAUNCH_DEFAULT_MAPS(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_MAPS,
                "LAUNCH_DEFAULT_MAPS"),
        LAUNCH_DEFAULT_MESSAGING(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_MESSAGING,
                "LAUNCH_DEFAULT_MESSAGING"),
        LAUNCH_DEFAULT_GALLERY(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_GALLERY,
                "LAUNCH_DEFAULT_GALLERY"),
        LAUNCH_DEFAULT_FILES(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_FILES,
                "LAUNCH_DEFAULT_FILES"),
        LAUNCH_DEFAULT_WEATHER(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_WEATHER,
                "LAUNCH_DEFAULT_WEATHER"),
        LAUNCH_DEFAULT_FITNESS(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_DEFAULT_FITNESS,
                "LAUNCH_DEFAULT_FITNESS"),
        LAUNCH_APPLICATION_BY_PACKAGE_NAME(
                FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__LAUNCH_APPLICATION_BY_PACKAGE_NAME,
                "LAUNCH_APPLICATION_BY_PACKAGE_NAME"),
        DESKTOP_MODE(
                FrameworkStatsLog
                        .KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__DESKTOP_MODE,
                "DESKTOP_MODE"),
        MULTI_WINDOW_NAVIGATION(FrameworkStatsLog
                .KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__MULTI_WINDOW_NAVIGATION,
                "MULTIWINDOW_NAVIGATION");


        private final int mValue;
        private final String mName;

        private static final SparseArray<KeyboardLogEvent> VALUE_TO_ENUM_MAP = new SparseArray<>();

        static {
            for (KeyboardLogEvent type : KeyboardLogEvent.values()) {
                VALUE_TO_ENUM_MAP.put(type.mValue, type);
            }
        }

        KeyboardLogEvent(int enumValue, String enumName) {
            mValue = enumValue;
            mName = enumName;
        }

        public int getIntValue() {
            return mValue;
        }

        /**
         * Convert int value to corresponding KeyboardLogEvent enum. If can't find any matching
         * value will return {@code null}
         */
        @Nullable
        public static KeyboardLogEvent from(int value) {
            return VALUE_TO_ENUM_MAP.get(value);
        }

        /**
         * Find KeyboardLogEvent corresponding to volume up/down/mute key events.
         */
        @Nullable
        public static KeyboardLogEvent getVolumeEvent(int keycode) {
            switch (keycode) {
                case KeyEvent.KEYCODE_VOLUME_DOWN:
                    return VOLUME_DOWN;
                case KeyEvent.KEYCODE_VOLUME_UP:
                    return VOLUME_UP;
                case KeyEvent.KEYCODE_VOLUME_MUTE:
                    return VOLUME_MUTE;
                default:
                    return null;
            }
        }

        /**
         * Find KeyboardLogEvent corresponding to brightness up/down key events.
         */
        @Nullable
        public static KeyboardLogEvent getBrightnessEvent(int keycode) {
            switch (keycode) {
                case KeyEvent.KEYCODE_BRIGHTNESS_DOWN:
                    return BRIGHTNESS_DOWN;
                case KeyEvent.KEYCODE_BRIGHTNESS_UP:
                    return BRIGHTNESS_UP;
                default:
                    return null;
            }
        }

        /**
         * Find KeyboardLogEvent corresponding to intent filter category. Returns
         * {@code null if no matching event found}
         */
        @Nullable
        public static KeyboardLogEvent getLogEventFromIntent(Intent intent) {
            Intent selectorIntent = intent.getSelector();
            if (selectorIntent != null) {
                Set<String> selectorCategories = selectorIntent.getCategories();
                if (selectorCategories != null && !selectorCategories.isEmpty()) {
                    for (String intentCategory : selectorCategories) {
                        KeyboardLogEvent logEvent = getEventFromSelectorCategory(intentCategory);
                        if (logEvent == null) {
                            continue;
                        }
                        return logEvent;
                    }
                }
            }

            // The shortcut may be targeting a system role rather than using an intent selector,
            // so check for that.
            String role = intent.getStringExtra(ModifierShortcutManager.EXTRA_ROLE);
            if (!TextUtils.isEmpty(role)) {
                return getLogEventFromRole(role);
            }

            Set<String> intentCategories = intent.getCategories();
            if (intentCategories == null || intentCategories.isEmpty()
                    || !intentCategories.contains(Intent.CATEGORY_LAUNCHER)) {
                return null;
            }
            if (intent.getComponent() == null) {
                return null;
            }

            // TODO(b/280423320): Add new field package name associated in the
            //  KeyboardShortcutEvent atom and log it accordingly.
            return LAUNCH_APPLICATION_BY_PACKAGE_NAME;
        }

        @Nullable
        private static KeyboardLogEvent getEventFromSelectorCategory(String category) {
            switch (category) {
                case Intent.CATEGORY_APP_BROWSER:
                    return LAUNCH_DEFAULT_BROWSER;
                case Intent.CATEGORY_APP_EMAIL:
                    return LAUNCH_DEFAULT_EMAIL;
                case Intent.CATEGORY_APP_CONTACTS:
                    return LAUNCH_DEFAULT_CONTACTS;
                case Intent.CATEGORY_APP_CALENDAR:
                    return LAUNCH_DEFAULT_CALENDAR;
                case Intent.CATEGORY_APP_CALCULATOR:
                    return LAUNCH_DEFAULT_CALCULATOR;
                case Intent.CATEGORY_APP_MUSIC:
                    return LAUNCH_DEFAULT_MUSIC;
                case Intent.CATEGORY_APP_MAPS:
                    return LAUNCH_DEFAULT_MAPS;
                case Intent.CATEGORY_APP_MESSAGING:
                    return LAUNCH_DEFAULT_MESSAGING;
                case Intent.CATEGORY_APP_GALLERY:
                    return LAUNCH_DEFAULT_GALLERY;
                case Intent.CATEGORY_APP_FILES:
                    return LAUNCH_DEFAULT_FILES;
                case Intent.CATEGORY_APP_WEATHER:
                    return LAUNCH_DEFAULT_WEATHER;
                case Intent.CATEGORY_APP_FITNESS:
                    return LAUNCH_DEFAULT_FITNESS;
                default:
                    return null;
            }
        }

        /**
         * Find KeyboardLogEvent corresponding to the provide system role name.
         * Returns {@code null} if no matching event found.
         */
        @Nullable
        private static KeyboardLogEvent getLogEventFromRole(String role) {
            if (RoleManager.ROLE_BROWSER.equals(role)) {
                return LAUNCH_DEFAULT_BROWSER;
            } else if (RoleManager.ROLE_SMS.equals(role)) {
                return LAUNCH_DEFAULT_MESSAGING;
            } else {
                Log.w(TAG, "Keyboard shortcut to launch "
                        + role + " not supported for logging");
                return null;
            }
        }
    }

    /**
     * Log keyboard system shortcuts for the proto
     * {@link com.android.os.input.KeyboardSystemsEventReported}
     * defined in "stats/atoms/input/input_extension_atoms.proto"
     */
    public static void logKeyboardSystemsEventReportedAtom(@Nullable InputDevice inputDevice,
            @Nullable KeyboardLogEvent keyboardSystemEvent, int modifierState, int... keyCodes) {
        // Logging Keyboard system event only for an external HW keyboard. We should not log events
        // for virtual keyboards or internal Key events.
        if (inputDevice == null || inputDevice.isVirtual() || !inputDevice.isFullKeyboard()) {
            return;
        }
        if (keyboardSystemEvent == null) {
            Slog.w(TAG, "Invalid keyboard event logging, keycode = " + Arrays.toString(keyCodes)
                    + ", modifier state = " + modifierState);
            return;
        }
        FrameworkStatsLog.write(FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED,
                inputDevice.getVendorId(), inputDevice.getProductId(),
                keyboardSystemEvent.getIntValue(), keyCodes, modifierState,
                inputDevice.getDeviceBus());

        if (DEBUG) {
            Slog.d(TAG, "Logging Keyboard system event: " + keyboardSystemEvent.mName);
        }
    }

    /**
     * Function to log the KeyboardConfigured
     * {@link com.android.os.input.KeyboardConfigured} atom
     *
     * @param event {@link KeyboardConfigurationEvent} contains information about keyboard
     *              configuration. Use {@link KeyboardConfigurationEvent.Builder} to create the
     *              configuration event to log.
     */
    public static void logKeyboardConfiguredAtom(KeyboardConfigurationEvent event) {
        // Creating proto to log nested field KeyboardLayoutConfig in atom
        ProtoOutputStream proto = new ProtoOutputStream();

        for (LayoutConfiguration layoutConfiguration : event.getLayoutConfigurations()) {
            addKeyboardLayoutConfigurationToProto(proto, layoutConfiguration);
        }
        // Push the atom to Statsd
        FrameworkStatsLog.write(FrameworkStatsLog.KEYBOARD_CONFIGURED,
                event.isFirstConfiguration(), event.getVendorId(), event.getProductId(),
                proto.getBytes(), event.getDeviceBus());

        if (DEBUG) {
            Slog.d(TAG, "Logging Keyboard configuration event: " + event);
        }
    }

    /**
     * Populate the KeyboardLayoutConfig proto which is a repeated proto
     * in the RepeatedKeyboardLayoutConfig proto with values from the
     * {@link LayoutConfiguration} class
     * The proto definitions can be found at:
     * "frameworks/proto_logging/stats/atoms/input/input_extension_atoms.proto"
     *
     * @param proto Representing the nested proto RepeatedKeyboardLayoutConfig
     * @param layoutConfiguration Class containing the fields for populating the
     * KeyboardLayoutConfig proto
     */
    private static void addKeyboardLayoutConfigurationToProto(ProtoOutputStream proto,
            LayoutConfiguration layoutConfiguration) {
        // Start a new KeyboardLayoutConfig proto.
        long keyboardLayoutConfigToken = proto.start(
                RepeatedKeyboardLayoutConfig.KEYBOARD_LAYOUT_CONFIG);
        proto.write(KeyboardLayoutConfig.KEYBOARD_LANGUAGE_TAG,
                layoutConfiguration.keyboardLanguageTag);
        proto.write(KeyboardLayoutConfig.KEYBOARD_LAYOUT_TYPE,
                layoutConfiguration.keyboardLayoutType);
        proto.write(KeyboardLayoutConfig.KEYBOARD_LAYOUT_NAME,
                layoutConfiguration.keyboardLayoutName);
        proto.write(KeyboardLayoutConfig.LAYOUT_SELECTION_CRITERIA,
                layoutConfiguration.layoutSelectionCriteria);
        proto.write(KeyboardLayoutConfig.IME_LANGUAGE_TAG,
                layoutConfiguration.imeLanguageTag);
        proto.write(KeyboardLayoutConfig.IME_LAYOUT_TYPE,
                layoutConfiguration.imeLayoutType);
        proto.end(keyboardLayoutConfigToken);
    }

    /**
     * Class representing the proto KeyboardLayoutConfig defined in
     * "frameworks/proto_logging/stats/atoms/input/input_extension_atoms.proto
     *
     * @see com.android.os.input.KeyboardConfigured
     */
    public static class KeyboardConfigurationEvent {

        private final InputDevice mInputDevice;
        private final boolean mIsFirstConfiguration;
        private final List<LayoutConfiguration> mLayoutConfigurations;

        private KeyboardConfigurationEvent(InputDevice inputDevice, boolean isFirstConfiguration,
                List<LayoutConfiguration> layoutConfigurations) {
            mInputDevice = inputDevice;
            mIsFirstConfiguration = isFirstConfiguration;
            mLayoutConfigurations = layoutConfigurations;
        }

        public int getVendorId() {
            return mInputDevice.getVendorId();
        }

        public int getProductId() {
            return mInputDevice.getProductId();
        }

        public int getDeviceBus() {
            return mInputDevice.getDeviceBus();
        }

        public boolean isFirstConfiguration() {
            return mIsFirstConfiguration;
        }

        public List<LayoutConfiguration> getLayoutConfigurations() {
            return mLayoutConfigurations;
        }

        @Override
        public String toString() {
            return "InputDevice = {VendorId = " + Integer.toHexString(getVendorId())
                    + ", ProductId = " + Integer.toHexString(getProductId())
                    + ", Device Bus = " + Integer.toHexString(getDeviceBus())
                    + "}, isFirstConfiguration = " + mIsFirstConfiguration
                    + ", LayoutConfigurations = " + mLayoutConfigurations;
        }

        /**
         * Builder class to help create {@link KeyboardConfigurationEvent}.
         */
        public static class Builder {
            @NonNull
            private final InputDevice mInputDevice;
            private boolean mIsFirstConfiguration;
            private final List<InputMethodSubtype> mImeSubtypeList = new ArrayList<>();
            private final List<String> mSelectedLayoutList = new ArrayList<>();
            private final List<Integer> mLayoutSelectionCriteriaList = new ArrayList<>();

            public Builder(@NonNull InputDevice inputDevice) {
                Objects.requireNonNull(inputDevice, "InputDevice provided should not be null");
                mInputDevice = inputDevice;
            }

            /**
             * Set whether this is the first time this keyboard is configured.
             */
            public Builder setIsFirstTimeConfiguration(boolean isFirstTimeConfiguration) {
                mIsFirstConfiguration = isFirstTimeConfiguration;
                return this;
            }

            /**
             * Adds keyboard layout configuration info for a particular IME subtype language
             */
            public Builder addLayoutSelection(@NonNull InputMethodSubtype imeSubtype,
                    @Nullable String selectedLayout,
                    @LayoutSelectionCriteria int layoutSelectionCriteria) {
                Objects.requireNonNull(imeSubtype, "IME subtype provided should not be null");
                if (!isValidSelectionCriteria(layoutSelectionCriteria)) {
                    throw new IllegalStateException("Invalid layout selection criteria");
                }
                mImeSubtypeList.add(imeSubtype);
                mSelectedLayoutList.add(selectedLayout);
                mLayoutSelectionCriteriaList.add(layoutSelectionCriteria);
                return this;
            }

            /**
             * Creates {@link KeyboardConfigurationEvent} from the provided information
             */
            public KeyboardConfigurationEvent build() {
                int size = mImeSubtypeList.size();
                if (size == 0) {
                    throw new IllegalStateException("Should have at least one configuration");
                }
                List<LayoutConfiguration> configurationList = new ArrayList<>();
                for (int i = 0; i < size; i++) {
                    @LayoutSelectionCriteria int layoutSelectionCriteria =
                            mLayoutSelectionCriteriaList.get(i);
                    InputMethodSubtype imeSubtype = mImeSubtypeList.get(i);
                    String keyboardLanguageTag = mInputDevice.getKeyboardLanguageTag();
                    keyboardLanguageTag = TextUtils.isEmpty(keyboardLanguageTag)
                            ? DEFAULT_LANGUAGE_TAG : keyboardLanguageTag;
                    int keyboardLayoutType = KeyboardLayout.LayoutType.getLayoutTypeEnumValue(
                            mInputDevice.getKeyboardLayoutType());

                    ULocale pkLocale = imeSubtype.getPhysicalKeyboardHintLanguageTag();
                    String imeLanguageTag = pkLocale != null ? pkLocale.toLanguageTag()
                            : imeSubtype.getCanonicalizedLanguageTag();
                    imeLanguageTag = TextUtils.isEmpty(imeLanguageTag) ? DEFAULT_LANGUAGE_TAG
                            : imeLanguageTag;
                    int imeLayoutType = KeyboardLayout.LayoutType.getLayoutTypeEnumValue(
                            imeSubtype.getPhysicalKeyboardHintLayoutType());

                    // Sanitize null values
                    String keyboardLayoutName = mSelectedLayoutList.get(i) == null
                            ? DEFAULT_LAYOUT_NAME
                            : mSelectedLayoutList.get(i);

                    configurationList.add(
                            new LayoutConfiguration(keyboardLayoutType, keyboardLanguageTag,
                                    keyboardLayoutName, layoutSelectionCriteria,
                                    imeLayoutType, imeLanguageTag));
                }
                return new KeyboardConfigurationEvent(mInputDevice, mIsFirstConfiguration,
                        configurationList);
            }
        }
    }

    @VisibleForTesting
    static class LayoutConfiguration {
        // This should match enum values defined in "frameworks/base/core/res/res/values/attrs.xml"
        public final int keyboardLayoutType;
        public final String keyboardLanguageTag;
        public final String keyboardLayoutName;
        @LayoutSelectionCriteria
        public final int layoutSelectionCriteria;
        public final int imeLayoutType;
        public final String imeLanguageTag;

        private LayoutConfiguration(int keyboardLayoutType, String keyboardLanguageTag,
                String keyboardLayoutName, @LayoutSelectionCriteria int layoutSelectionCriteria,
                int imeLayoutType, String imeLanguageTag) {
            this.keyboardLayoutType = keyboardLayoutType;
            this.keyboardLanguageTag = keyboardLanguageTag;
            this.keyboardLayoutName = keyboardLayoutName;
            this.layoutSelectionCriteria = layoutSelectionCriteria;
            this.imeLayoutType = imeLayoutType;
            this.imeLanguageTag = imeLanguageTag;
        }

        @Override
        public String toString() {
            return "{keyboardLanguageTag = " + keyboardLanguageTag
                    + " keyboardLayoutType = "
                    + KeyboardLayout.LayoutType.getLayoutNameFromValue(keyboardLayoutType)
                    + " keyboardLayoutName = " + keyboardLayoutName
                    + " layoutSelectionCriteria = "
                    + layoutSelectionCriteriaToString(layoutSelectionCriteria)
                    + " imeLanguageTag = " + imeLanguageTag
                    + " imeLayoutType = " + KeyboardLayout.LayoutType.getLayoutNameFromValue(
                    imeLayoutType)
                    + "}";
        }
    }

    private static boolean isValidSelectionCriteria(int layoutSelectionCriteria) {
        return layoutSelectionCriteria == LAYOUT_SELECTION_CRITERIA_USER
                || layoutSelectionCriteria == LAYOUT_SELECTION_CRITERIA_DEVICE
                || layoutSelectionCriteria == LAYOUT_SELECTION_CRITERIA_VIRTUAL_KEYBOARD
                || layoutSelectionCriteria == LAYOUT_SELECTION_CRITERIA_DEFAULT;
    }
}
