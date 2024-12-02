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

import static com.android.internal.widget.remotecompose.accessibility.RemoteComposeDocumentAccessibility.RootId;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.PointF;
import android.os.Bundle;
import android.util.IntArray;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.internal.widget.ExploreByTouchHelper;
import com.android.internal.widget.remotecompose.core.CoreDocument;
import com.android.internal.widget.remotecompose.core.operations.layout.Component;
import com.android.internal.widget.remotecompose.core.semantics.AccessibilitySemantics;
import com.android.internal.widget.remotecompose.core.semantics.CoreSemantics.Mode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Stack;

public class PlatformRemoteComposeTouchHelper<N, C, S> extends ExploreByTouchHelper {
    private final RemoteComposeDocumentAccessibility<C, S> mRemoteDocA11y;

    private final SemanticNodeApplier<AccessibilityNodeInfo, C, S> mApplier;

    public PlatformRemoteComposeTouchHelper(
            View host,
            RemoteComposeDocumentAccessibility<C, S> remoteDocA11y,
            SemanticNodeApplier<AccessibilityNodeInfo, C, S> applier) {
        super(host);
        this.mRemoteDocA11y = remoteDocA11y;
        this.mApplier = applier;
    }

    public static PlatformRemoteComposeTouchHelper<
                    AccessibilityNodeInfo, Component, AccessibilitySemantics>
            forRemoteComposePlayer(View player, @NonNull CoreDocument coreDocument) {
        return new PlatformRemoteComposeTouchHelper<>(
                player,
                new CoreDocumentAccessibility(coreDocument),
                new AndroidPlatformSemanticNodeApplier());
    }

    /**
     * Gets the virtual view ID at a given location on the screen.
     *
     * <p>This method is called by the Accessibility framework to determine which virtual view, if
     * any, is located at a specific point on the screen. It uses the {@link
     * RemoteComposeDocumentAccessibility#getComponentIdAt(PointF)} method to find the ID of the
     * component at the given coordinates.
     *
     * @param x The x-coordinate of the location in pixels.
     * @param y The y-coordinate of the location in pixels.
     * @return The ID of the virtual view at the given location, or {@link #INVALID_ID} if no
     *     virtual view is found at that location.
     */
    @Override
    protected int getVirtualViewAt(float x, float y) {
        Integer root = mRemoteDocA11y.getComponentIdAt(new PointF(x, y));

        if (root == null) {
            return INVALID_ID;
        }

        return root;
    }

    /**
     * Populates a list with the visible virtual view IDs.
     *
     * <p>This method is called by the accessibility framework to retrieve the IDs of all visible
     * virtual views in the accessibility hierarchy. It traverses the hierarchy starting from the
     * root node (RootId) and adds the ID of each visible view to the provided list.
     *
     * @param virtualViewIds The list to be populated with the visible virtual view IDs.
     */
    @Override
    protected void getVisibleVirtualViews(IntArray virtualViewIds) {
        Stack<Integer> toVisit = new Stack<>();
        Set<Integer> visited = new HashSet<>();

        toVisit.push(RootId);

        while (!toVisit.isEmpty()) {
            Integer componentId = toVisit.remove(0);

            if (visited.add(componentId)) {
                virtualViewIds.add(componentId);

                C component = mRemoteDocA11y.findComponentById(componentId);

                if (component != null) {
                    boolean allSet =
                            mRemoteDocA11y.mergeMode(component).stream()
                                    .allMatch(i -> i == Mode.SET);

                    if (allSet) {
                        List<Integer> childViews =
                                mRemoteDocA11y.semanticallyRelevantChildComponents(component);

                        toVisit.addAll(childViews);
                    }
                }
            }
        }
    }

    @Override
    public void onPopulateNodeForVirtualView(
            int virtualViewId, @NonNull AccessibilityNodeInfo node) {
        C component = mRemoteDocA11y.findComponentById(virtualViewId);

        List<Mode> mode = mRemoteDocA11y.mergeMode(component);

        if (mode.contains(Mode.MERGE)) {
            List<Integer> childViews =
                    mRemoteDocA11y.semanticallyRelevantChildComponents(component);

            for (Integer childView : childViews) {
                onPopulateNodeForVirtualView(childView, node);
            }
        }

        List<S> semantics = mRemoteDocA11y.semanticModifiersForComponent(component);
        mApplier.applyComponent(mRemoteDocA11y, node, component, semantics);
    }

    @Override
    protected void onPopulateEventForVirtualView(int virtualViewId, AccessibilityEvent event) {
        // TODO
    }

    @Override
    protected boolean onPerformActionForVirtualView(
            int virtualViewId, int action, @Nullable Bundle arguments) {
        C component = mRemoteDocA11y.findComponentById(virtualViewId);

        if (component != null) {
            return mRemoteDocA11y.performAction(component, action, arguments);
        } else {
            return false;
        }
    }
}
