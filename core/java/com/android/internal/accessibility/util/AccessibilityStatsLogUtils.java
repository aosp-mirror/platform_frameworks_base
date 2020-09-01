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

import static android.view.accessibility.AccessibilityManager.ACCESSIBILITY_BUTTON;
import static android.view.accessibility.AccessibilityManager.ACCESSIBILITY_SHORTCUT_KEY;

import static com.android.internal.accessibility.AccessibilityShortcutController.MAGNIFICATION_COMPONENT_NAME;
import static com.android.internal.util.FrameworkStatsLog.ACCESSIBILITY_SHORTCUT_REPORTED__SERVICE_STATUS__DISABLED;
import static com.android.internal.util.FrameworkStatsLog.ACCESSIBILITY_SHORTCUT_REPORTED__SERVICE_STATUS__ENABLED;
import static com.android.internal.util.FrameworkStatsLog.ACCESSIBILITY_SHORTCUT_REPORTED__SERVICE_STATUS__UNKNOWN;
import static com.android.internal.util.FrameworkStatsLog.ACCESSIBILITY_SHORTCUT_REPORTED__SHORTCUT_TYPE__A11Y_BUTTON;
import static com.android.internal.util.FrameworkStatsLog.ACCESSIBILITY_SHORTCUT_REPORTED__SHORTCUT_TYPE__A11Y_BUTTON_LONG_PRESS;
import static com.android.internal.util.FrameworkStatsLog.ACCESSIBILITY_SHORTCUT_REPORTED__SHORTCUT_TYPE__TRIPLE_TAP;
import static com.android.internal.util.FrameworkStatsLog.ACCESSIBILITY_SHORTCUT_REPORTED__SHORTCUT_TYPE__UNKNOWN_TYPE;
import static com.android.internal.util.FrameworkStatsLog.ACCESSIBILITY_SHORTCUT_REPORTED__SHORTCUT_TYPE__VOLUME_KEY;

import android.content.ComponentName;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityManager.ShortcutType;

import com.android.internal.util.FrameworkStatsLog;

/** Methods for logging accessibility states. */
public final class AccessibilityStatsLogUtils {
    private static final int UNKNOWN_STATUS =
            ACCESSIBILITY_SHORTCUT_REPORTED__SERVICE_STATUS__UNKNOWN;

    private AccessibilityStatsLogUtils() {}

    /**
     * Logs accessibility feature name that is assigned to the shortcut also its shortcut type.
     * Calls this when clicking the shortcut {@link AccessibilityManager#ACCESSIBILITY_BUTTON} or
     * {@link AccessibilityManager#ACCESSIBILITY_SHORTCUT_KEY}
     *
     * @param componentName component name of the accessibility feature
     * @param shortcutType  accessibility shortcut type {@link ShortcutType}
     */
    public static void logAccessibilityShortcutActivated(ComponentName componentName,
            @ShortcutType int shortcutType) {
        logAccessibilityShortcutActivated(componentName, shortcutType, UNKNOWN_STATUS);
    }

    /**
     * Logs accessibility feature name that is assigned to the shortcut also its shortcut type and
     * enabled status. Calls this when clicking the shortcut
     * {@link AccessibilityManager#ACCESSIBILITY_BUTTON}
     * or {@link AccessibilityManager#ACCESSIBILITY_SHORTCUT_KEY}
     *
     * @param componentName  component name of the accessibility feature
     * @param shortcutType   accessibility shortcut type
     * @param serviceEnabled {@code true} if the service is enabled
     */
    public static void logAccessibilityShortcutActivated(ComponentName componentName,
            @ShortcutType int shortcutType, boolean serviceEnabled) {
        logAccessibilityShortcutActivated(componentName, shortcutType,
                convertToLoggingServiceStatus(serviceEnabled));
    }

    /**
     * Logs accessibility feature name that is assigned to the shortcut also its shortcut type and
     * status code. Calls this when clicking the shortcut
     * {@link AccessibilityManager#ACCESSIBILITY_BUTTON}
     * or {@link AccessibilityManager#ACCESSIBILITY_SHORTCUT_KEY}
     *
     * @param componentName component name of the accessibility feature
     * @param shortcutType  accessibility shortcut type {@link ShortcutType}
     * @param serviceStatus The service status code. 0 denotes unknown_status, 1 denotes enabled, 2
     *                      denotes disabled.
     */
    private static void logAccessibilityShortcutActivated(ComponentName componentName,
            @ShortcutType int shortcutType, int serviceStatus) {
        FrameworkStatsLog.write(FrameworkStatsLog.ACCESSIBILITY_SHORTCUT_REPORTED,
                componentName.flattenToString(), convertToLoggingShortcutType(shortcutType),
                serviceStatus);
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

    private static int convertToLoggingShortcutType(@ShortcutType int shortcutType) {
        switch (shortcutType) {
            case ACCESSIBILITY_BUTTON:
                return ACCESSIBILITY_SHORTCUT_REPORTED__SHORTCUT_TYPE__A11Y_BUTTON;
            case ACCESSIBILITY_SHORTCUT_KEY:
                return ACCESSIBILITY_SHORTCUT_REPORTED__SHORTCUT_TYPE__VOLUME_KEY;
        }
        return ACCESSIBILITY_SHORTCUT_REPORTED__SHORTCUT_TYPE__UNKNOWN_TYPE;
    }

    private static int convertToLoggingServiceStatus(boolean enabled) {
        return enabled ? ACCESSIBILITY_SHORTCUT_REPORTED__SERVICE_STATUS__ENABLED
                : ACCESSIBILITY_SHORTCUT_REPORTED__SERVICE_STATUS__DISABLED;
    }
}
