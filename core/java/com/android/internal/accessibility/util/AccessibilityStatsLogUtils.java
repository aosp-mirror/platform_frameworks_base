/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.accessibility.util;

import static android.provider.Settings.Secure.ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU;
import static android.provider.Settings.Secure.ACCESSIBILITY_BUTTON_MODE_GESTURE;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_ALL;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW;
import static android.view.accessibility.AccessibilityManager.ACCESSIBILITY_BUTTON;
import static android.view.accessibility.AccessibilityManager.ACCESSIBILITY_SHORTCUT_KEY;

import static com.android.internal.accessibility.AccessibilityShortcutController.MAGNIFICATION_COMPONENT_NAME;
import static com.android.internal.util.FrameworkStatsLog.ACCESSIBILITY_SHORTCUT_REPORTED__SERVICE_STATUS__DISABLED;
import static com.android.internal.util.FrameworkStatsLog.ACCESSIBILITY_SHORTCUT_REPORTED__SERVICE_STATUS__ENABLED;
import static com.android.internal.util.FrameworkStatsLog.ACCESSIBILITY_SHORTCUT_REPORTED__SERVICE_STATUS__UNKNOWN;
import static com.android.internal.util.FrameworkStatsLog.ACCESSIBILITY_SHORTCUT_REPORTED__SHORTCUT_TYPE__A11Y_BUTTON;
import static com.android.internal.util.FrameworkStatsLog.ACCESSIBILITY_SHORTCUT_REPORTED__SHORTCUT_TYPE__A11Y_BUTTON_LONG_PRESS;
import static com.android.internal.util.FrameworkStatsLog.ACCESSIBILITY_SHORTCUT_REPORTED__SHORTCUT_TYPE__A11Y_FLOATING_MENU;
import static com.android.internal.util.FrameworkStatsLog.ACCESSIBILITY_SHORTCUT_REPORTED__SHORTCUT_TYPE__A11Y_GESTURE;
import static com.android.internal.util.FrameworkStatsLog.ACCESSIBILITY_SHORTCUT_REPORTED__SHORTCUT_TYPE__TRIPLE_TAP;
import static com.android.internal.util.FrameworkStatsLog.ACCESSIBILITY_SHORTCUT_REPORTED__SHORTCUT_TYPE__UNKNOWN_TYPE;
import static com.android.internal.util.FrameworkStatsLog.ACCESSIBILITY_SHORTCUT_REPORTED__SHORTCUT_TYPE__VOLUME_KEY;
import static com.android.internal.util.FrameworkStatsLog.MAGNIFICATION_USAGE_REPORTED__ACTIVATED_MODE__MAGNIFICATION_ALL;
import static com.android.internal.util.FrameworkStatsLog.MAGNIFICATION_USAGE_REPORTED__ACTIVATED_MODE__MAGNIFICATION_FULL_SCREEN;
import static com.android.internal.util.FrameworkStatsLog.MAGNIFICATION_USAGE_REPORTED__ACTIVATED_MODE__MAGNIFICATION_UNKNOWN_MODE;
import static com.android.internal.util.FrameworkStatsLog.MAGNIFICATION_USAGE_REPORTED__ACTIVATED_MODE__MAGNIFICATION_WINDOW;
import static com.android.internal.util.FrameworkStatsLog.NON_A11Y_TOOL_SERVICE_WARNING_REPORTED__STATUS__WARNING_CLICKED;
import static com.android.internal.util.FrameworkStatsLog.NON_A11Y_TOOL_SERVICE_WARNING_REPORTED__STATUS__WARNING_SERVICE_DISABLED;
import static com.android.internal.util.FrameworkStatsLog.NON_A11Y_TOOL_SERVICE_WARNING_REPORTED__STATUS__WARNING_SHOWN;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityManager.ShortcutType;

import com.android.internal.util.FrameworkStatsLog;

/** Methods for logging accessibility states. */
public final class AccessibilityStatsLogUtils {
    /** The status represents an accessibility privacy warning has been shown. */
    public static int ACCESSIBILITY_PRIVACY_WARNING_STATUS_SHOWN =
            NON_A11Y_TOOL_SERVICE_WARNING_REPORTED__STATUS__WARNING_SHOWN;
    /** The status represents an accessibility privacy warning has been clicked to review. */
    public static int ACCESSIBILITY_PRIVACY_WARNING_STATUS_CLICKED =
            NON_A11Y_TOOL_SERVICE_WARNING_REPORTED__STATUS__WARNING_CLICKED;
    /** The status represents an accessibility privacy warning service has been disabled. */
    public static int ACCESSIBILITY_PRIVACY_WARNING_STATUS_SERVICE_DISABLED =
            NON_A11Y_TOOL_SERVICE_WARNING_REPORTED__STATUS__WARNING_SERVICE_DISABLED;

    private static final int UNKNOWN_STATUS =
            ACCESSIBILITY_SHORTCUT_REPORTED__SERVICE_STATUS__UNKNOWN;

    private AccessibilityStatsLogUtils() {}

    /**
     * Logs accessibility feature name that is assigned to the given {@code shortcutType}.
     * Calls this when clicking the shortcut {@link AccessibilityManager#ACCESSIBILITY_BUTTON} or
     * {@link AccessibilityManager#ACCESSIBILITY_SHORTCUT_KEY}.
     *
     * @param context context used to retrieve the {@link Settings} provider
     * @param componentName component name of the accessibility feature
     * @param shortcutType  accessibility shortcut type
     */
    public static void logAccessibilityShortcutActivated(Context context,
            ComponentName componentName, @ShortcutType int shortcutType) {
        logAccessibilityShortcutActivatedInternal(componentName,
                convertToLoggingShortcutType(context, shortcutType), UNKNOWN_STATUS);
    }

    /**
     * Logs accessibility feature name that is assigned to the given {@code shortcutType} and the
     * {@code serviceEnabled} status.
     * Calls this when clicking the shortcut {@link AccessibilityManager#ACCESSIBILITY_BUTTON}
     * or {@link AccessibilityManager#ACCESSIBILITY_SHORTCUT_KEY}.
     *
     * @param context context used to retrieve the {@link Settings} provider
     * @param componentName  component name of the accessibility feature
     * @param shortcutType   accessibility shortcut type
     * @param serviceEnabled {@code true} if the service is enabled
     */
    public static void logAccessibilityShortcutActivated(Context context,
            ComponentName componentName, @ShortcutType int shortcutType, boolean serviceEnabled) {
        logAccessibilityShortcutActivatedInternal(componentName,
                convertToLoggingShortcutType(context, shortcutType),
                convertToLoggingServiceStatus(serviceEnabled));
    }

    /**
     * Logs accessibility feature name that is assigned to the given {@code loggingShortcutType} and
     * {@code loggingServiceStatus} code.
     *
     * @param componentName        component name of the accessibility feature
     * @param loggingShortcutType  accessibility shortcut type for logging. 0 denotes
     *                             unknown_type, 1 denotes accessibility button, 2 denotes volume
     *                             key, 3 denotes triple tap on the screen, 4 denotes long press on
     *                             accessibility button, 5 denotes accessibility floating menu.
     * @param loggingServiceStatus The service status code for logging. 0 denotes unknown_status, 1
     *                             denotes enabled, 2 denotes disabled.
     */
    private static void logAccessibilityShortcutActivatedInternal(ComponentName componentName,
            int loggingShortcutType, int loggingServiceStatus) {
        FrameworkStatsLog.write(FrameworkStatsLog.ACCESSIBILITY_SHORTCUT_REPORTED,
                componentName.flattenToString(), loggingShortcutType, loggingServiceStatus);
    }

    /**
     * Logs magnification that is assigned to the triple tap shortcut. Calls this when triggering
     * the magnification triple tap shortcut.
     */
    public static void logMagnificationTripleTap(boolean enabled) {
        FrameworkStatsLog.write(FrameworkStatsLog.ACCESSIBILITY_SHORTCUT_REPORTED,
                MAGNIFICATION_COMPONENT_NAME.flattenToString(),
                ACCESSIBILITY_SHORTCUT_REPORTED__SHORTCUT_TYPE__TRIPLE_TAP,
                convertToLoggingServiceStatus(enabled));
    }

    /**
     * Logs accessibility feature name that is assigned to the long pressed accessibility button
     * shortcut. Calls this when clicking the long pressed accessibility button shortcut.
     *
     * @param componentName The component name of the accessibility feature.
     */
    public static void logAccessibilityButtonLongPressStatus(ComponentName componentName) {
        FrameworkStatsLog.write(FrameworkStatsLog.ACCESSIBILITY_SHORTCUT_REPORTED,
                componentName.flattenToString(),
                ACCESSIBILITY_SHORTCUT_REPORTED__SHORTCUT_TYPE__A11Y_BUTTON_LONG_PRESS,
                UNKNOWN_STATUS);
    }

    /**
     * Logs the magnification activated mode and its duration of the usage.
     * Calls this when the magnification is disabled.
     *
     * @param mode The activated magnification mode.
     * @param duration The duration in milliseconds during the magnification is activated.
     */
    public static void logMagnificationUsageState(int mode, long duration) {
        FrameworkStatsLog.write(FrameworkStatsLog.MAGNIFICATION_USAGE_REPORTED,
                convertToLoggingMagnificationMode(mode),
                duration);
    }

    /**
     * Logs the activated mode of the magnification when the IME window is shown on the screen.
     * Calls this when the magnification is enabled and the IME window is shown on the screen.
     *
     * @param mode The activated magnification mode.
     */
    public static void logMagnificationModeWithImeOn(int mode) {
        FrameworkStatsLog.write(FrameworkStatsLog.MAGNIFICATION_MODE_WITH_IME_ON_REPORTED,
                convertToLoggingMagnificationMode(mode));
    }

    /**
     * Logs the duration for the window magnifier's following typing focus session.
     *
     * @param duration The duration of a triple-tap-and-hold activation session.
     */
    public static void logMagnificationFollowTypingFocusSession(long duration) {
        FrameworkStatsLog.write(
                FrameworkStatsLog.MAGNIFICATION_FOLLOW_TYPING_FOCUS_ACTIVATED_SESSION_REPORTED,
                duration);
    }

    /**
     * Logs the warning status of the non-a11yTool service. Calls this when the warning status is
     * changed.
     *
     * @param packageName    The package name of the non-a11yTool service
     * @param status         The warning status of the non-a11yTool service, it should be one of
     *                       {@code ACCESSIBILITY_PRIVACY_WARNING_STATUS_SHOWN},{@code
     *                       ACCESSIBILITY_PRIVACY_WARNING_STATUS_CLICKED} and {@code
     *                       ACCESSIBILITY_PRIVACY_WARNING_STATUS_SERVICE_DISABLED}
     * @param durationMillis The duration in milliseconds between current and previous status
     */
    public static void logNonA11yToolServiceWarningReported(String packageName, int status,
            long durationMillis) {
        FrameworkStatsLog.write(FrameworkStatsLog.NON_A11Y_TOOL_SERVICE_WARNING_REPORT,
                packageName, status, durationMillis);
    }

    private static boolean isAccessibilityFloatingMenuEnabled(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_MODE, /* def= */ -1)
                == ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU;
    }

    private static boolean isAccessibilityGestureEnabled(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_MODE, /* def= */ -1)
                == ACCESSIBILITY_BUTTON_MODE_GESTURE;
    }

    private static int convertToLoggingShortcutType(Context context,
            @ShortcutType int shortcutType) {
        switch (shortcutType) {
            case ACCESSIBILITY_BUTTON:
                if (isAccessibilityFloatingMenuEnabled(context)) {
                    return ACCESSIBILITY_SHORTCUT_REPORTED__SHORTCUT_TYPE__A11Y_FLOATING_MENU;
                } else if (isAccessibilityGestureEnabled(context)) {
                    return ACCESSIBILITY_SHORTCUT_REPORTED__SHORTCUT_TYPE__A11Y_GESTURE;
                } else {
                    return ACCESSIBILITY_SHORTCUT_REPORTED__SHORTCUT_TYPE__A11Y_BUTTON;
                }
            case ACCESSIBILITY_SHORTCUT_KEY:
                return ACCESSIBILITY_SHORTCUT_REPORTED__SHORTCUT_TYPE__VOLUME_KEY;
        }
        return ACCESSIBILITY_SHORTCUT_REPORTED__SHORTCUT_TYPE__UNKNOWN_TYPE;
    }

    private static int convertToLoggingServiceStatus(boolean enabled) {
        return enabled ? ACCESSIBILITY_SHORTCUT_REPORTED__SERVICE_STATUS__ENABLED
                : ACCESSIBILITY_SHORTCUT_REPORTED__SERVICE_STATUS__DISABLED;
    }

    private static int convertToLoggingMagnificationMode(int mode) {
        switch (mode) {
            case ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN:
                return MAGNIFICATION_USAGE_REPORTED__ACTIVATED_MODE__MAGNIFICATION_FULL_SCREEN;
            case ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW:
                return MAGNIFICATION_USAGE_REPORTED__ACTIVATED_MODE__MAGNIFICATION_WINDOW;
            case ACCESSIBILITY_MAGNIFICATION_MODE_ALL:
                return MAGNIFICATION_USAGE_REPORTED__ACTIVATED_MODE__MAGNIFICATION_ALL;

            default:
                return MAGNIFICATION_USAGE_REPORTED__ACTIVATED_MODE__MAGNIFICATION_UNKNOWN_MODE;
        }
    }
}
