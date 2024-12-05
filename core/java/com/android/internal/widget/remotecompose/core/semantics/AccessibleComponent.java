/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.core.semantics;

import android.annotation.Nullable;

public interface AccessibleComponent extends AccessibilitySemantics {
    default @Nullable Integer getContentDescriptionId() {
        return null;
    }

    default @Nullable Integer getTextId() {
        return null;
    }

    default @Nullable Role getRole() {
        return null;
    }

    default boolean isClickable() {
        return false;
    }

    default CoreSemantics.Mode getMode() {
        return CoreSemantics.Mode.SET;
    }

    // Our master list
    // https://developer.android.com/reference/kotlin/androidx/compose/ui/semantics/Role
    enum Role {
        BUTTON("Button"),
        CHECKBOX("Checkbox"),
        SWITCH("Switch"),
        RADIO_BUTTON("RadioButton"),
        TAB("Tab"),
        IMAGE("Image"),
        DROPDOWN_LIST("DropdownList"),
        PICKER("Picker"),
        CAROUSEL("Carousel"),
        UNKNOWN(null);

        @Nullable private final String mDescription;

        Role(@Nullable String description) {
            this.mDescription = description;
        }

        @Nullable
        public String getDescription() {
            return mDescription;
        }

        public static Role fromInt(int i) {
            if (i < UNKNOWN.ordinal()) {
                return Role.values()[i];
            }
            return Role.UNKNOWN;
        }
    }
}
