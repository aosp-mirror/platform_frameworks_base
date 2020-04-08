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

import static com.android.internal.accessibility.common.ShortcutConstants.SERVICES_SEPARATOR;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType;

import android.annotation.NonNull;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityManager.ShortcutType;

import java.util.List;
import java.util.StringJoiner;

/**
 * Collection of utilities for accessibility shortcut.
 */
public final class ShortcutUtils {
    private ShortcutUtils() {}

    private static final TextUtils.SimpleStringSplitter sStringColonSplitter =
            new TextUtils.SimpleStringSplitter(SERVICES_SEPARATOR);

    /**
     * Opts in component id into colon-separated {@link UserShortcutType}
     * key's string from Settings.
     *
     * @param context The current context.
     * @param shortcutType The preferred shortcut type user selected.
     * @param componentId The component id that need to be opted in Settings.
     */
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
     */
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
    public static boolean isShortcutContained(Context context, @ShortcutType int shortcutType,
            @NonNull String componentId) {
        final AccessibilityManager am = context.getSystemService(AccessibilityManager.class);
        final List<String> requiredTargets = am.getAccessibilityShortcutTargets(shortcutType);
        return requiredTargets.contains(componentId);
    }

    /**
     * Converts {@link UserShortcutType} to {@link Settings.Secure} key.
     *
     * @param type The shortcut type.
     * @return Mapping key in Settings.
     */
    public static String convertToKey(@UserShortcutType int type) {
        switch (type) {
            case UserShortcutType.SOFTWARE:
                return Settings.Secure.ACCESSIBILITY_BUTTON_TARGET_COMPONENT;
            case UserShortcutType.HARDWARE:
                return Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE;
            case UserShortcutType.TRIPLETAP:
                return Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED;
            default:
                throw new IllegalArgumentException(
                        "Unsupported user shortcut type: " + type);
        }
    }

    /**
     * Converts {@link ShortcutType} to {@link UserShortcutType}.
     *
     * @param type The shortcut type.
     * @return Mapping type from {@link UserShortcutType}.
     */
    public static @UserShortcutType int convertToUserType(@ShortcutType int type) {
        switch (type) {
            case ACCESSIBILITY_BUTTON:
                return UserShortcutType.SOFTWARE;
            case ACCESSIBILITY_SHORTCUT_KEY:
                return UserShortcutType.HARDWARE;
            default:
                throw new IllegalArgumentException(
                        "Unsupported shortcut type:" + type);
        }
    }
}
