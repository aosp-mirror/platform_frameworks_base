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

import static android.provider.Settings.Secure.ACCESSIBILITY_BUTTON_MODE;
import static android.provider.Settings.Secure.ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU;
import static android.provider.Settings.Secure.ACCESSIBILITY_BUTTON_MODE_GESTURE;
import static android.provider.Settings.Secure.ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR;

import static com.android.internal.accessibility.AccessibilityShortcutController.MAGNIFICATION_CONTROLLER_NAME;
import static com.android.internal.accessibility.common.ShortcutConstants.AccessibilityFragmentType.INVISIBLE_TOGGLE;
import static com.android.internal.accessibility.common.ShortcutConstants.SERVICES_SEPARATOR;
import static com.android.internal.accessibility.common.ShortcutConstants.USER_SHORTCUT_TYPES;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.GESTURE;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.HARDWARE;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.QUICK_SETTINGS;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.SOFTWARE;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.TRIPLETAP;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.TWOFINGER_DOUBLETAP;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Slog;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Collection of utilities for accessibility shortcut.
 */
public final class ShortcutUtils {
    private ShortcutUtils() {}

    private static final TextUtils.SimpleStringSplitter sStringColonSplitter =
            new TextUtils.SimpleStringSplitter(SERVICES_SEPARATOR);
    private static final String TAG = "AccessibilityShortcutUtils";

    /**
     * Opts in component id into colon-separated {@link UserShortcutType}
     * key's string from Settings.
     *
     * @param context      The current context.
     * @param shortcutType The preferred shortcut type user selected.
     * @param componentId  The component id that need to be opted in Settings.
     * @deprecated Use
     * {@link AccessibilityManager#enableShortcutsForTargets(boolean, int, Set, int)}
     */
    @Deprecated
    public static void optInValueToSettings(Context context, @UserShortcutType int shortcutType,
            @NonNull String componentId) {
        final StringJoiner joiner = new StringJoiner(String.valueOf(SERVICES_SEPARATOR));
        final String targetKey = convertToKey(shortcutType);
        final String targetString = Settings.Secure.getString(context.getContentResolver(),
                targetKey);

        if (isComponentIdExistingInSettings(context, shortcutType, componentId)) {
            return;
        }

        if (!TextUtils.isEmpty(targetString)) {
            joiner.add(targetString);
        }
        joiner.add(componentId);

        Settings.Secure.putString(context.getContentResolver(), targetKey, joiner.toString());
    }

    /**
     * Opts out of component id into colon-separated {@link UserShortcutType} key's string from
     * Settings.
     *
     * @param context The current context.
     * @param shortcutType The preferred shortcut type user selected.
     * @param componentId The component id that need to be opted out of Settings.
     *
     * @deprecated Use
     * {@link AccessibilityManager#enableShortcutForTargets(boolean, int, Set, int)}
     */
    @Deprecated
    public static void optOutValueFromSettings(
            Context context, @UserShortcutType int shortcutType, @NonNull String componentId) {
        final StringJoiner joiner = new StringJoiner(String.valueOf(SERVICES_SEPARATOR));
        final String targetsKey = convertToKey(shortcutType);
        final String targetsValue = Settings.Secure.getString(context.getContentResolver(),
                targetsKey);

        if (TextUtils.isEmpty(targetsValue)) {
            return;
        }

        sStringColonSplitter.setString(targetsValue);
        while (sStringColonSplitter.hasNext()) {
            final String id = sStringColonSplitter.next();
            if (TextUtils.isEmpty(id) || componentId.equals(id)) {
                continue;
            }
            joiner.add(id);
        }

        Settings.Secure.putString(context.getContentResolver(), targetsKey, joiner.toString());
    }

    /**
     * Returns if component id existed in Settings.
     *
     * @param context The current context.
     * @param shortcutType The preferred shortcut type user selected.
     * @param componentId The component id that need to be checked existed in Settings.
     * @return {@code true} if component id existed in Settings.
     */
    public static boolean isComponentIdExistingInSettings(Context context,
            @UserShortcutType int shortcutType, @NonNull String componentId) {
        final String targetKey = convertToKey(shortcutType);
        final String targetString = Settings.Secure.getString(context.getContentResolver(),
                targetKey);

        if (TextUtils.isEmpty(targetString)) {
            return false;
        }

        sStringColonSplitter.setString(targetString);
        while (sStringColonSplitter.hasNext()) {
            final String id = sStringColonSplitter.next();
            if (componentId.equals(id)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns if a {@code shortcutType} shortcut contains {@code componentId}.
     *
     * @param context The current context.
     * @param shortcutType The preferred shortcut type user selected.
     * @param componentId The component id that need to be checked.
     * @return {@code true} if a component id is contained.
     */
    public static boolean isShortcutContained(Context context, @UserShortcutType int shortcutType,
            @NonNull String componentId) {
        final AccessibilityManager am = (AccessibilityManager) context.getSystemService(
                Context.ACCESSIBILITY_SERVICE);
        final List<String> requiredTargets = am.getAccessibilityShortcutTargets(shortcutType);
        return requiredTargets.contains(componentId);
    }

    /**
     * Converts {@link UserShortcutType} to {@link Settings.Secure} key.
     *
     * @param type The shortcut type.
     * @return Mapping key in Settings.
     */
    @SuppressLint("SwitchIntDef")
    public static String convertToKey(@UserShortcutType int type) {
        return switch (type) {
            case SOFTWARE -> Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS;
            case GESTURE -> Settings.Secure.ACCESSIBILITY_GESTURE_TARGETS;
            case HARDWARE -> Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE;
            case TRIPLETAP -> Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED;
            case TWOFINGER_DOUBLETAP ->
                    Settings.Secure.ACCESSIBILITY_MAGNIFICATION_TWO_FINGER_TRIPLE_TAP_ENABLED;
            case QUICK_SETTINGS -> Settings.Secure.ACCESSIBILITY_QS_TARGETS;
            default -> throw new IllegalArgumentException(
                    "Unsupported user shortcut type: " + type);
        };
    }

    /**
     * Converts {@link Settings.Secure} key to {@link UserShortcutType}.
     *
     * @param key The shortcut key in Settings.
     * @return The mapped type
     */
    @UserShortcutType
    public static int convertToType(String key) {
        return switch (key) {
            case Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS -> SOFTWARE;
            case Settings.Secure.ACCESSIBILITY_GESTURE_TARGETS -> GESTURE;
            case Settings.Secure.ACCESSIBILITY_QS_TARGETS -> QUICK_SETTINGS;
            case Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE -> HARDWARE;
            case Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED ->
                    TRIPLETAP;
            case Settings.Secure.ACCESSIBILITY_MAGNIFICATION_TWO_FINGER_TRIPLE_TAP_ENABLED ->
                    TWOFINGER_DOUBLETAP;
            default -> throw new IllegalArgumentException(
                    "Unsupported user shortcut key: " + key);
        };
    }

    /**
     * Updates an accessibility state if the accessibility service is a Always-On a11y service,
     * a.k.a. AccessibilityServices that has FLAG_REQUEST_ACCESSIBILITY_BUTTON
     * <p>
     * Turn on the accessibility service when there is any shortcut associated to it.
     * <p>
     * Turn off the accessibility service when there is no shortcut associated to it.
     *
     * @param componentNames the a11y shortcut target's component names
     */
    public static void updateInvisibleToggleAccessibilityServiceEnableState(
            Context context, Set<String> componentNames, int userId) {
        final AccessibilityManager am = (AccessibilityManager) context.getSystemService(
                Context.ACCESSIBILITY_SERVICE);
        if (am == null) return;

        final List<AccessibilityServiceInfo> installedServices =
                am.getInstalledAccessibilityServiceList();

        final Set<String> invisibleToggleServices = new ArraySet<>();
        for (AccessibilityServiceInfo serviceInfo : installedServices) {
            if (AccessibilityUtils.getAccessibilityServiceFragmentType(serviceInfo)
                    == INVISIBLE_TOGGLE) {
                invisibleToggleServices.add(serviceInfo.getComponentName().flattenToString());
            }
        }

        final Set<String> servicesWithShortcuts = new ArraySet<>();
        for (int shortcutType: USER_SHORTCUT_TYPES) {
            // The call to update always-on service might modify the shortcut setting right before
            // calling #updateAccessibilityServiceStateIfNeeded in the same call.
            // To avoid getting the shortcut target from out-dated value, use values from Settings
            // instead.
            servicesWithShortcuts.addAll(
                    getShortcutTargetsFromSettings(context, shortcutType, userId));
        }

        for (String componentName : componentNames) {
            // Only needs to update the Always-On A11yService's state when the shortcut changes.
            if (invisibleToggleServices.contains(componentName)) {

                boolean enableA11yService = servicesWithShortcuts.contains(componentName);
                AccessibilityUtils.setAccessibilityServiceState(
                        context,
                        ComponentName.unflattenFromString(componentName),
                        enableA11yService,
                        userId);
            }
        }
    }

    /**
     * Returns the target component names of a given user shortcut type from Settings.
     *
     * <p>
     * Note: grab shortcut targets from Settings is only needed
     * if you depends on a value being set in the same call.
     * For example, you disable a single shortcut,
     * and you're checking if there is any shortcut remaining.
     *
     * <p>
     * If you just want to know the current state, you can use
     * {@link AccessibilityManager#getAccessibilityShortcutTargets(int)}
     */
    @NonNull
    public static Set<String> getShortcutTargetsFromSettings(
            Context context, @UserShortcutType int shortcutType, int userId) {
        final String targetKey = convertToKey(shortcutType);
        if (Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED.equals(targetKey)
                || Settings.Secure.ACCESSIBILITY_MAGNIFICATION_TWO_FINGER_TRIPLE_TAP_ENABLED
                .equals(targetKey)) {
            boolean magnificationEnabled = Settings.Secure.getIntForUser(
                    context.getContentResolver(), targetKey, /* def= */ 0, userId) == 1;
            return magnificationEnabled ? Set.of(MAGNIFICATION_CONTROLLER_NAME)
                    : Collections.emptySet();

        } else {
            final String targetString = Settings.Secure.getStringForUser(
                    context.getContentResolver(), targetKey, userId);

            if (TextUtils.isEmpty(targetString)) {
                return Collections.emptySet();
            }

            Set<String> targets = new ArraySet<>();
            sStringColonSplitter.setString(targetString);
            while (sStringColonSplitter.hasNext()) {
                targets.add(sStringColonSplitter.next());
            }
            return Collections.unmodifiableSet(targets);
        }
    }

    /**
     * Retrieves the button mode of the provided context.
     * Returns -1 if the button mode is undefined.
     * Valid button modes:
     * {@link Settings.Secure#ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR},
     * {@link Settings.Secure#ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU},
     * {@link Settings.Secure#ACCESSIBILITY_BUTTON_MODE_GESTURE}
     */
    public static int getButtonMode(Context context, @UserIdInt int userId) {
        return Settings.Secure.getIntForUser(context.getContentResolver(),
                ACCESSIBILITY_BUTTON_MODE, /* default value = */ -1, userId);
    }

    /**
     * Sets the button mode of the provided context.
     * Must be a valid button mode, or it will return false.
     * Returns true if the setting was changed, false otherwise.
     * Valid button modes:
     * {@link Settings.Secure#ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR},
     * {@link Settings.Secure#ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU},
     * {@link Settings.Secure#ACCESSIBILITY_BUTTON_MODE_GESTURE}
     */
    public static boolean setButtonMode(Context context, int mode, @UserIdInt int userId) {
        // Input validation
        if (getButtonMode(context, userId) == mode) {
            return false;
        }
        if ((mode
                & (ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR
                | ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU
                | ACCESSIBILITY_BUTTON_MODE_GESTURE)) != mode) {
            Slog.w(TAG, "Tried to set button mode to unexpected value " + mode);
            return false;
        }
        return Settings.Secure.putIntForUser(
                context.getContentResolver(), ACCESSIBILITY_BUTTON_MODE, mode, userId);
    }
}
