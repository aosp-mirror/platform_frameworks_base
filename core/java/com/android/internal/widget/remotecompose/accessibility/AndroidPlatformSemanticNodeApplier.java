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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.internal.widget.remotecompose.core.operations.layout.Component;
import com.android.internal.widget.remotecompose.core.semantics.AccessibilitySemantics;
import com.android.internal.widget.remotecompose.core.semantics.AccessibleComponent;
import com.android.internal.widget.remotecompose.core.semantics.CoreSemantics;

import java.util.List;

public class AndroidPlatformSemanticNodeApplier
        implements SemanticNodeApplier<AccessibilityNodeInfo, Component, AccessibilitySemantics> {

    private static final String ROLE_DESCRIPTION_KEY = "AccessibilityNodeInfo.roleDescription";

    @Override
    public void applyComponent(
            @NonNull
                    RemoteComposeDocumentAccessibility<Component, AccessibilitySemantics>
                            remoteComposeAccessibility,
            AccessibilityNodeInfo nodeInfo,
            Component component,
            List<AccessibilitySemantics> semantics) {
        if (component instanceof AccessibleComponent) {
            applyContentDescription(
                    ((AccessibleComponent) component).getContentDescriptionId(),
                    nodeInfo,
                    remoteComposeAccessibility);

            applyRole(((AccessibleComponent) component).getRole(), nodeInfo);
        }

        applySemantics(remoteComposeAccessibility, nodeInfo, semantics);

        float[] locationInWindow = new float[2];
        component.getLocationInWindow(locationInWindow);
        Rect bounds =
                new Rect(
                        (int) locationInWindow[0],
                        (int) locationInWindow[1],
                        (int) (locationInWindow[0] + component.getWidth()),
                        (int) (locationInWindow[1] + component.getHeight()));
        //noinspection deprecation
        nodeInfo.setBoundsInParent(bounds);
        nodeInfo.setBoundsInScreen(bounds);

        if (component instanceof AccessibleComponent) {
            applyContentDescription(
                    ((AccessibleComponent) component).getContentDescriptionId(),
                    nodeInfo,
                    remoteComposeAccessibility);

            applyText(
                    ((AccessibleComponent) component).getTextId(),
                    nodeInfo,
                    remoteComposeAccessibility);

            applyRole(((AccessibleComponent) component).getRole(), nodeInfo);
        }

        applySemantics(remoteComposeAccessibility, nodeInfo, semantics);

        if (nodeInfo.getText() == null && nodeInfo.getContentDescription() == null) {
            nodeInfo.setContentDescription("");
        }
    }

    public void applySemantics(
            RemoteComposeDocumentAccessibility<Component, AccessibilitySemantics>
                    remoteComposeAccessibility,
            AccessibilityNodeInfo nodeInfo,
            List<AccessibilitySemantics> semantics) {
        for (AccessibilitySemantics semantic : semantics) {
            if (semantic.isInterestingForSemantics()) {
                if (semantic instanceof CoreSemantics) {
                    applyCoreSemantics(
                            remoteComposeAccessibility, nodeInfo, (CoreSemantics) semantic);
                } else if (semantic instanceof AccessibleComponent) {
                    AccessibleComponent s = (AccessibleComponent) semantic;

                    applyContentDescription(
                            s.getContentDescriptionId(), nodeInfo, remoteComposeAccessibility);

                    applyRole(s.getRole(), nodeInfo);

                    applyText(s.getTextId(), nodeInfo, remoteComposeAccessibility);

                    if (s.isClickable()) {
                        nodeInfo.setClickable(true);
                        nodeInfo.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
                    }
                }
            }
        }
    }

    private void applyCoreSemantics(
            RemoteComposeDocumentAccessibility<Component, AccessibilitySemantics>
                    remoteComposeAccessibility,
            AccessibilityNodeInfo nodeInfo,
            CoreSemantics semantics) {
        applyContentDescription(
                semantics.getContentDescriptionId(), nodeInfo, remoteComposeAccessibility);

        applyRole(semantics.getRole(), nodeInfo);

        applyText(semantics.getTextId(), nodeInfo, remoteComposeAccessibility);

        applyStateDescription(
                semantics.getStateDescriptionId(), nodeInfo, remoteComposeAccessibility);

        nodeInfo.setEnabled(semantics.mEnabled);
    }

    void applyRole(@Nullable AccessibleComponent.Role role, AccessibilityNodeInfo nodeInfo) {
        if (role != null) {
            nodeInfo.getExtras().putCharSequence(ROLE_DESCRIPTION_KEY, role.getDescription());
        }
    }

    void applyContentDescription(
            @Nullable Integer contentDescriptionId,
            AccessibilityNodeInfo nodeInfo,
            RemoteComposeDocumentAccessibility<Component, AccessibilitySemantics>
                    remoteComposeAccessibility) {
        if (contentDescriptionId != null) {
            nodeInfo.setContentDescription(
                    remoteComposeAccessibility.stringValue(contentDescriptionId));
        }
    }

    void applyText(
            @Nullable Integer textId,
            AccessibilityNodeInfo nodeInfo,
            RemoteComposeDocumentAccessibility<Component, AccessibilitySemantics>
                    remoteComposeAccessibility) {
        if (textId != null) {
            nodeInfo.setText(remoteComposeAccessibility.stringValue(textId));
        }
    }

    void applyStateDescription(
            @Nullable Integer stateDescriptionId,
            AccessibilityNodeInfo nodeInfo,
            RemoteComposeDocumentAccessibility<Component, AccessibilitySemantics>
                    remoteComposeAccessibility) {
        if (stateDescriptionId != null) {
            nodeInfo.setStateDescription(
                    remoteComposeAccessibility.stringValue(stateDescriptionId));
        }
    }
}
