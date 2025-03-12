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
package com.android.internal.widget.remotecompose.accessibility;

import android.annotation.Nullable;
import android.graphics.PointF;
import android.os.Bundle;
import android.view.View;

import com.android.internal.widget.remotecompose.core.operations.layout.Component;
import com.android.internal.widget.remotecompose.core.semantics.AccessibilitySemantics;
import com.android.internal.widget.remotecompose.core.semantics.CoreSemantics;

import java.util.List;

/**
 * Interface for interacting with the accessibility features of a remote Compose UI. This interface
 * provides methods to perform actions, retrieve state, and query the accessibility tree of the
 * remote Compose UI.
 */
public interface RemoteComposeDocumentAccessibility {
    // Matches ExploreByTouchHelper.HOST_ID
    Integer RootId = View.NO_ID;

    // androidx.core.view.accessibility.AccessibilityNodeInfoCompat.ACTION_CLICK
    int ACTION_CLICK = 0x00000010;

    /**
     * Performs the specified action on the given component.
     *
     * @param component The component on which to perform the action.
     * @param action The action to perform.
     * @param arguments Optional arguments for the action.
     * @return {@code true} if the action was performed successfully, {@code false} otherwise.
     */
    boolean performAction(Component component, int action, Bundle arguments);

    /**
     * Retrieves the string value associated with the given ID.
     *
     * @param id The ID to retrieve the string value for.
     * @return The string value associated with the ID, or {@code null} if no such value exists.
     */
    @Nullable
    String stringValue(int id);

    /**
     * Retrieves a list of child view IDs semantically contained within the given component/virtual
     * view. These may later be hidden from accessibility services by properties, but should contain
     * only possibly semantically relevant virtual views.
     *
     * @param component The component to retrieve child view IDs from, or [RootId] for the top
     *     level.
     * @param useUnmergedTree Whether to include merged children
     * @return A list of integer IDs representing the child views of the component.
     */
    List<Integer> semanticallyRelevantChildComponents(Component component, boolean useUnmergedTree);

    /**
     * Retrieves the semantic modifiers associated with a given component.
     *
     * @param component The component for which to retrieve semantic modifiers.
     * @return A list of semantic modifiers applicable to the component.
     */
    List<AccessibilitySemantics> semanticModifiersForComponent(Component component);

    /**
     * Gets all applied merge modes of the given component. A Merge mode is one of Set, Merge or
     * Clear and describes how to apply and combine hierarchical semantics.
     *
     * @param component The component to merge the mode for.
     * @return The effective merge modes, potentially conflicting but resolved to a single value.
     */
    CoreSemantics.Mode mergeMode(Component component);

    /**
     * Finds a component by its ID.
     *
     * @param id the ID of the component to find
     * @return the component with the given ID, or {@code null} if no such component exists
     */
    @Nullable
    Component findComponentById(int id);

    @Nullable
    Integer getComponentIdAt(PointF point);
}
