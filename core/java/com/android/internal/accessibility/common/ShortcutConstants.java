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

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Collection of common constants for accessibility shortcut.
 */
public final class ShortcutConstants {
    private ShortcutConstants() {}

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
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            UserShortcutType.DEFAULT,
            UserShortcutType.SOFTWARE,
            UserShortcutType.HARDWARE,
            UserShortcutType.TRIPLETAP,
    })
    public @interface UserShortcutType {
        int DEFAULT = 0;
        int SOFTWARE = 1; // 1 << 0
        int HARDWARE = 2; // 1 << 1
        int TRIPLETAP = 4; // 1 << 2
    }

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
}
