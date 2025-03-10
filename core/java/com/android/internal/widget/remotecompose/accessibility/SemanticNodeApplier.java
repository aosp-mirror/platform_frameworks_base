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

import com.android.internal.widget.remotecompose.core.operations.layout.Component;
import com.android.internal.widget.remotecompose.core.semantics.AccessibilitySemantics;

import java.util.List;

/**
 * An interface for applying semantic information to a semantics node.
 *
 * <p>Implementations of this interface are responsible for taking a node represented by [nodeInfo]
 * and applying a list of [semantics] (representing accessible actions and properties) to it. This
 * process might involve: - Modifying the node's properties (e.g., content description, clickable
 * state). - Adding a child node to represent a specific semantic element. - Performing any other
 * action necessary to make the node semantically meaningful and accessible to assistive
 * technologies.
 *
 * @param <N> The type representing information about the node. This could be an Androidx
 *     `AccessibilityNodeInfoCompat`, or potentially a platform `AccessibilityNodeInfo`.
 */
public interface SemanticNodeApplier<N> {
    void applyComponent(
            RemoteComposeDocumentAccessibility remoteComposeAccessibility,
            N nodeInfo,
            Component component,
            List<AccessibilitySemantics> semantics);

    String VIRTUAL_VIEW_ID_KEY = "VirtualViewId";
}
