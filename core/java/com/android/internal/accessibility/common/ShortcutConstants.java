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

package com.android.internal.accessibility.common;

import static com.android.internal.accessibility.AccessibilityShortcutController.COLOR_INVERSION_COMPONENT_NAME;
import static com.android.internal.accessibility.AccessibilityShortcutController.COLOR_INVERSION_TILE_COMPONENT_NAME;
import static com.android.internal.accessibility.AccessibilityShortcutController.DALTONIZER_COMPONENT_NAME;
import static com.android.internal.accessibility.AccessibilityShortcutController.DALTONIZER_TILE_COMPONENT_NAME;
import static com.android.internal.accessibility.AccessibilityShortcutController.FONT_SIZE_COMPONENT_NAME;
import static com.android.internal.accessibility.AccessibilityShortcutController.FONT_SIZE_TILE_COMPONENT_NAME;
import static com.android.internal.accessibility.AccessibilityShortcutController.ONE_HANDED_COMPONENT_NAME;
import static com.android.internal.accessibility.AccessibilityShortcutController.ONE_HANDED_TILE_COMPONENT_NAME;
import static com.android.internal.accessibility.AccessibilityShortcutController.REDUCE_BRIGHT_COLORS_COMPONENT_NAME;
import static com.android.internal.accessibility.AccessibilityShortcutController.REDUCE_BRIGHT_COLORS_TILE_SERVICE_COMPONENT_NAME;

import android.annotation.IntDef;
import android.content.ComponentName;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Map;

/**
 * Collection of common constants for accessibility shortcut.
 */
public final class ShortcutConstants {
    private ShortcutConstants() {}

    /**
     * Package name of the accessibility chooser and used for {@link android.content.Intent}.
     */
    public static final String CHOOSER_PACKAGE_NAME = "android";

    public static final char SERVICES_SEPARATOR = ':';

    /**
     * Annotation for different user shortcut type UI type.
     *
     * {@code DEFAULT} for displaying default value.
     * {@code SOFTWARE} for displaying specifying the accessibility services or features which
     * choose accessibility button in the navigation bar as preferred shortcut.
     * {@code HARDWARE} for displaying specifying the accessibility services or features which
     * choose accessibility shortcut as preferred shortcut.
     * {@code TRIPLETAP} for displaying specifying magnification to be toggled via quickly
     * tapping screen 3 times as preferred shortcut.
     * {@code TWOFINGER_DOUBLETAP} for displaying specifying magnification to be toggled via
     * quickly tapping screen 2 times with two fingers as preferred shortcut.
     * {@code QUICK_SETTINGS} for displaying specifying the accessibility services or features which
     * choose Quick Settings as preferred shortcut.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            UserShortcutType.DEFAULT,
            UserShortcutType.SOFTWARE,
            UserShortcutType.HARDWARE,
            UserShortcutType.TRIPLETAP,
            UserShortcutType.TWOFINGER_DOUBLETAP,
            UserShortcutType.QUICK_SETTINGS,
    })
    public @interface UserShortcutType {
        int DEFAULT = 0;
        // LINT.IfChange(shortcut_type_intdef)
        int SOFTWARE = 1 << 0;
        int HARDWARE = 1 << 1;
        int TRIPLETAP = 1 << 2;
        int TWOFINGER_DOUBLETAP = 1 << 3;
        int QUICK_SETTINGS = 1 << 4;
        // LINT.ThenChange(:shortcut_type_array)
    }

    /**
     * A list of possible {@link UserShortcutType}. Should stay in sync with the
     * non-default IntDef types.
     */
    public static final int[] USER_SHORTCUT_TYPES = {
            // LINT.IfChange(shortcut_type_array)
            UserShortcutType.SOFTWARE,
            UserShortcutType.HARDWARE,
            UserShortcutType.TRIPLETAP,
            UserShortcutType.TWOFINGER_DOUBLETAP,
            UserShortcutType.QUICK_SETTINGS,
            // LINT.ThenChange(:shortcut_type_intdef)
    };


    /**
     * Annotation for the different accessibility fragment type.
     *
     * {@code VOLUME_SHORTCUT_TOGGLE} for displaying appearance with switch bar and only one
     * shortcut option that is volume key shortcut.
     * {@code INVISIBLE_TOGGLE} for displaying appearance without switch bar.
     * {@code TOGGLE} for displaying appearance with switch bar.
     * {@code LAUNCH_ACTIVITY} for displaying appearance with pop-up action that is for launch
     * activity.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            AccessibilityFragmentType.VOLUME_SHORTCUT_TOGGLE,
            AccessibilityFragmentType.INVISIBLE_TOGGLE,
            AccessibilityFragmentType.TOGGLE,
            AccessibilityFragmentType.LAUNCH_ACTIVITY,
    })
    public @interface AccessibilityFragmentType {
        int VOLUME_SHORTCUT_TOGGLE = 0;
        int INVISIBLE_TOGGLE = 1;
        int TOGGLE = 2;
        int LAUNCH_ACTIVITY = 3;
    }

    /**
     * Annotation for different shortcut menu mode.
     *
     * {@code LAUNCH} for clicking list item to trigger the service callback.
     * {@code EDIT} for clicking list item and save button to disable the service.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            ShortcutMenuMode.LAUNCH,
            ShortcutMenuMode.EDIT,
    })
    public @interface ShortcutMenuMode {
        int LAUNCH = 0;
        int EDIT = 1;
    }

    /**
     * Annotation for different FAB shortcut type's menu size
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            FloatingMenuSize.UNKNOWN,
            FloatingMenuSize.SMALL,
            FloatingMenuSize.LARGE,
    })
    public @interface FloatingMenuSize {
        int UNKNOWN = -1;
        int SMALL = 0;
        int LARGE = 1;
    }

    /**
     * A map of a11y feature to its qs tile component
     */
    public static final Map<ComponentName, ComponentName> A11Y_FEATURE_TO_FRAMEWORK_TILE = Map.of(
            COLOR_INVERSION_COMPONENT_NAME, COLOR_INVERSION_TILE_COMPONENT_NAME,
            DALTONIZER_COMPONENT_NAME, DALTONIZER_TILE_COMPONENT_NAME,
            ONE_HANDED_COMPONENT_NAME, ONE_HANDED_TILE_COMPONENT_NAME,
            REDUCE_BRIGHT_COLORS_COMPONENT_NAME, REDUCE_BRIGHT_COLORS_TILE_SERVICE_COMPONENT_NAME,
            FONT_SIZE_COMPONENT_NAME, FONT_SIZE_TILE_COMPONENT_NAME
    );
}
