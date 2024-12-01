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

import com.android.internal.widget.remotecompose.core.semantics.CoreSemantics;

import java.util.List;

/**
 * Interface for interacting with the accessibility features of a remote Compose UI. This interface
 * provides methods to perform actions, retrieve state, and query the accessibility tree of the
 * remote Compose UI.
 *
 * @param <C> The type of component in the remote Compose UI.
 * @param <S> The type representing semantic modifiers applied to components.
 */
public interface RemoteComposeDocumentAccessibility<C, S> {
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
    boolean performAction(C component, int action, Bundle arguments);

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
     * @return A list of integer IDs representing the child views of the component.
     */
    List<Integer> semanticallyRelevantChildComponents(C component);

    /**
     * Retrieves the semantic modifiers associated with a given component.
     *
     * @param component The component for which to retrieve semantic modifiers.
     * @return A list of semantic modifiers applicable to the component.
     */
    List<S> semanticModifiersForComponent(C component);

    /**
     * Gets all applied merge modes of the given component. A Merge mode is one of Set, Merge or
     * Clear and describes how to apply and combine hierarchical semantics.
     *
     * @param component The component to merge the mode for.
     * @return A list of merged modes, potentially conflicting but to be resolved by the caller.
     */
    List<CoreSemantics.Mode> mergeMode(C component);

    /**
     * Finds a component by its ID.
     *
     * @param id the ID of the component to find
     * @return the component with the given ID, or {@code null} if no such component exists
     */
    @Nullable
    C findComponentById(int id);

    @Nullable
    Integer getComponentIdAt(PointF point);
}
