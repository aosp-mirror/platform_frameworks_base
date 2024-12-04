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

import android.graphics.Rect;

import com.android.internal.widget.remotecompose.core.operations.layout.Component;
import com.android.internal.widget.remotecompose.core.semantics.AccessibilitySemantics;
import com.android.internal.widget.remotecompose.core.semantics.AccessibleComponent;
import com.android.internal.widget.remotecompose.core.semantics.CoreSemantics;

import java.util.List;

/**
 * Base class for applying semantic information to a node.
 *
 * <p>This class provides common functionality for applying semantic information extracted from
 * Compose UI components to a node representation used for accessibility purposes. It handles
 * applying properties like content description, text, role, clickability, and bounds.
 *
 * <p>Subclasses are responsible for implementing methods to actually set these properties on the
 * specific node type they handle.
 *
 * @param <N> The type of node this applier works with.
 */
public abstract class BaseSemanticNodeApplier<N> implements SemanticNodeApplier<N> {
    @Override
    public void applyComponent(
            RemoteComposeDocumentAccessibility remoteComposeAccessibility,
            N nodeInfo,
            Component component,
            List<AccessibilitySemantics> semantics) {
        float[] locationInWindow = new float[2];
        component.getLocationInWindow(locationInWindow);
        Rect bounds =
                new Rect(
                        (int) locationInWindow[0],
                        (int) locationInWindow[1],
                        (int) (locationInWindow[0] + component.getWidth()),
                        (int) (locationInWindow[1] + component.getHeight()));
        setBoundsInScreen(nodeInfo, bounds);

        setUniqueId(nodeInfo, String.valueOf(component.getComponentId()));

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

        if (getText(nodeInfo) == null && getContentDescription(nodeInfo) == null) {
            setContentDescription(nodeInfo, "");
        }
    }

    protected void applySemantics(
            RemoteComposeDocumentAccessibility remoteComposeAccessibility,
            N nodeInfo,
            List<AccessibilitySemantics> semantics) {
        for (AccessibilitySemantics semantic : semantics) {
            if (semantic.isInterestingForSemantics()) {
                if (semantic instanceof CoreSemantics) {
                    CoreSemantics coreSemantics = (CoreSemantics) semantic;
                    applyCoreSemantics(remoteComposeAccessibility, nodeInfo, coreSemantics);
                } else if (semantic instanceof AccessibleComponent) {
                    AccessibleComponent accessibleComponent = (AccessibleComponent) semantic;
                    if (accessibleComponent.isClickable()) {
                        setClickable(nodeInfo, true);
                    }

                    if (accessibleComponent.getContentDescriptionId() != null) {
                        applyContentDescription(
                                accessibleComponent.getContentDescriptionId(),
                                nodeInfo,
                                remoteComposeAccessibility);
                    }

                    if (accessibleComponent.getTextId() != null) {
                        applyText(
                                accessibleComponent.getTextId(),
                                nodeInfo,
                                remoteComposeAccessibility);
                    }

                    applyRole(accessibleComponent.getRole(), nodeInfo);
                }
            }
        }
    }

    protected void applyCoreSemantics(
            RemoteComposeDocumentAccessibility remoteComposeAccessibility,
            N nodeInfo,
            CoreSemantics coreSemantics) {
        applyContentDescription(
                coreSemantics.getContentDescriptionId(), nodeInfo, remoteComposeAccessibility);

        applyRole(coreSemantics.getRole(), nodeInfo);

        applyText(coreSemantics.getTextId(), nodeInfo, remoteComposeAccessibility);

        applyStateDescription(
                coreSemantics.getStateDescriptionId(), nodeInfo, remoteComposeAccessibility);

        if (!coreSemantics.mEnabled) {
            setEnabled(nodeInfo, false);
        }
    }

    protected void applyStateDescription(
            Integer stateDescriptionId,
            N nodeInfo,
            RemoteComposeDocumentAccessibility remoteComposeAccessibility) {
        if (stateDescriptionId != null) {
            setStateDescription(
                    nodeInfo,
                    appendNullable(
                            getStateDescription(nodeInfo),
                            remoteComposeAccessibility.stringValue(stateDescriptionId)));
        }
    }

    protected void applyRole(AccessibleComponent.Role role, N nodeInfo) {
        if (role != null) {
            setRoleDescription(nodeInfo, role.getDescription());
        }
    }

    protected void applyText(
            Integer textId,
            N nodeInfo,
            RemoteComposeDocumentAccessibility remoteComposeAccessibility) {
        if (textId != null) {
            setText(
                    nodeInfo,
                    appendNullable(
                            getText(nodeInfo), remoteComposeAccessibility.stringValue(textId)));
        }
    }

    protected void applyContentDescription(
            Integer contentDescriptionId,
            N nodeInfo,
            RemoteComposeDocumentAccessibility remoteComposeAccessibility) {
        if (contentDescriptionId != null) {
            setContentDescription(
                    nodeInfo,
                    appendNullable(
                            getContentDescription(nodeInfo),
                            remoteComposeAccessibility.stringValue(contentDescriptionId)));
        }
    }

    private CharSequence appendNullable(CharSequence contentDescription, String value) {
        if (contentDescription == null) {
            return value;
        } else if (value == null) {
            return contentDescription;
        } else {
            return contentDescription + " " + value;
        }
    }

    protected abstract void setClickable(N nodeInfo, boolean b);

    protected abstract void setEnabled(N nodeInfo, boolean b);

    protected abstract CharSequence getStateDescription(N nodeInfo);

    protected abstract void setStateDescription(N nodeInfo, CharSequence charSequence);

    protected abstract void setRoleDescription(N nodeInfo, String description);

    protected abstract CharSequence getText(N nodeInfo);

    protected abstract void setText(N nodeInfo, CharSequence charSequence);

    protected abstract CharSequence getContentDescription(N nodeInfo);

    protected abstract void setContentDescription(N nodeInfo, CharSequence charSequence);

    protected abstract void setBoundsInScreen(N nodeInfo, Rect bounds);

    protected abstract void setUniqueId(N nodeInfo, String s);
}
