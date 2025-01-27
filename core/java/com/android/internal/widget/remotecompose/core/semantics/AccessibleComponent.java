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

/**
 * Interface representing an accessible component in the UI. This interface defines properties and
 * methods related to accessibility semantics for a component. It extends the {@link
 * AccessibilitySemantics} interface to inherit semantic properties.
 *
 * <p>This is similar to {@link CoreSemantics} but handles built in operations that also expose
 * those core semantics.
 */
public interface AccessibleComponent extends AccessibilitySemantics {
    /**
     * Returns the ID of the content description for this item.
     *
     * <p>The content description is used to provide textual information about the item to
     * accessibility services, such as screen readers. This allows users with visual impairments to
     * understand the purpose and content of the item.
     *
     * <p>This is similar to AccessibilityNodeInfo.getContentDescription().
     *
     * @return The ID of a RemoteString for the content description, or null if no content
     *     description is provided.
     */
    default @Nullable Integer getContentDescriptionId() {
        return null;
    }

    /**
     * Gets the text ID associated with this object.
     *
     * <p>This method is intended to be overridden by subclasses that need to associate a specific
     * text ID with themselves. The default implementation returns null, indicating that no text ID
     * is associated with the object.
     *
     * <p>This is similar to AccessibilityNodeInfo.getText().
     *
     * @return The text ID, or null if no text ID is associated with this object.
     */
    default @Nullable Integer getTextId() {
        return null;
    }

    /**
     * Retrieves the role associated with this object. The enum type deliberately matches the
     * Compose Role. In the platform it will be applied as ROLE_DESCRIPTION_KEY.
     *
     * <p>The default implementation returns {@code null}, indicating that no role is assigned.
     *
     * @return The role associated with this object, or {@code null} if no role is assigned.
     */
    default @Nullable Role getRole() {
        return null;
    }

    /**
     * Checks if the element is clickable.
     *
     * <p>By default, elements are not considered clickable. Subclasses should override this method
     * to indicate clickability based on their specific properties and behavior.
     *
     * <p>This is similar to AccessibilityNodeInfo.isClickable().
     *
     * @return {@code true} if the element is clickable, {@code false} otherwise.
     */
    default boolean isClickable() {
        return false;
    }

    /**
     * Gets the merge mode of the operation.
     *
     * <p>The mode indicates the type of operation being performed. By default it returns {@link
     * CoreSemantics.Mode#SET}, indicating a "set" operation.
     *
     * <p>{@link CoreSemantics.Mode#CLEAR_AND_SET} matches a Compose modifier of
     * `Modifier.clearAndSetSemantics {}`
     *
     * <p>{@link CoreSemantics.Mode#MERGE} matches a Compose modifier of
     * `Modifier.semantics(mergeDescendants = true) {}`
     *
     * @return The mode of the operation, which defaults to {@link CoreSemantics.Mode#SET}.
     */
    default CoreSemantics.Mode getMode() {
        return CoreSemantics.Mode.SET;
    }

    /**
     * Represents the role of an accessible component.
     *
     * <p>The enum type deliberately matches the Compose Role. In the platform it will be applied as
     * ROLE_DESCRIPTION_KEY.
     *
     * @link <a
     *     href="https://developer.android.com/reference/androidx/compose/ui/semantics/Role">Compose
     *     Semantics Role</a>
     */
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

        /**
         * Map int value to Role enum value
         *
         * @param i int value
         * @return corresponding enum value
         */
        public static Role fromInt(int i) {
            if (i < UNKNOWN.ordinal()) {
                return Role.values()[i];
            }
            return Role.UNKNOWN;
        }
    }

    /**
     * Defines the merge mode of an element in the semantic tree.
     *
     * <p>{@link CoreSemantics.Mode#CLEAR_AND_SET} matches a Compose modifier of
     * `Modifier.clearAndSetSemantics {}`
     *
     * <p>{@link CoreSemantics.Mode#MERGE} matches a Compose modifier of
     * `Modifier.semantics(mergeDescendants = true) {}`
     *
     * <p>{@link CoreSemantics.Mode#SET} adds or overrides semantics on an element.
     */
    enum Mode {
        SET,
        CLEAR_AND_SET,
        MERGE
    }
}
