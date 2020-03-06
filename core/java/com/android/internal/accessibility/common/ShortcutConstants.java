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
     * Annotation for different accessibilityService fragment UI type.
     *
     * {@code LEGACY} for displaying appearance aligned with sdk version Q accessibility service
     * page, but only hardware shortcut allowed and under service in version Q or early.
     * {@code INVISIBLE} for displaying appearance without switch bar.
     * {@code INTUITIVE} for displaying appearance with version R accessibility design.
     * {@code BOUNCE} for displaying appearance with pop-up action.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            AccessibilityServiceFragmentType.LEGACY,
            AccessibilityServiceFragmentType.INVISIBLE,
            AccessibilityServiceFragmentType.INTUITIVE,
            AccessibilityServiceFragmentType.BOUNCE,
    })
    public @interface AccessibilityServiceFragmentType {
        int LEGACY = 0;
        int INVISIBLE = 1;
        int INTUITIVE = 2;
        int BOUNCE = 3;
    }

    /**
     * Annotation for different shortcut target.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            TargetType.ACCESSIBILITY_SERVICE,
            TargetType.ACCESSIBILITY_ACTIVITY,
            TargetType.WHITE_LISTING,
    })
    public @interface TargetType {
        int ACCESSIBILITY_SERVICE = 0;
        int ACCESSIBILITY_ACTIVITY = 1;
        int WHITE_LISTING = 2;
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
     * Annotation for align the element index of white listing feature
     * {@code WHITE_LISTING_FEATURES}.
     *
     * {@code COMPONENT_ID} is to get the service component name.
     * {@code LABEL_ID} is to get the service label text.
     * {@code ICON_ID} is to get the service icon.
     * {@code FRAGMENT_TYPE} is to get the service fragment type.
     * {@code SETTINGS_KEY} is to get the service settings key.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            WhiteListingFeatureElementIndex.COMPONENT_ID,
            WhiteListingFeatureElementIndex.LABEL_ID,
            WhiteListingFeatureElementIndex.ICON_ID,
            WhiteListingFeatureElementIndex.FRAGMENT_TYPE,
            WhiteListingFeatureElementIndex.SETTINGS_KEY,
    })
    public @interface WhiteListingFeatureElementIndex {
        int COMPONENT_ID = 0;
        int LABEL_ID = 1;
        int ICON_ID = 2;
        int FRAGMENT_TYPE = 3;
        int SETTINGS_KEY = 4;
    }
}
