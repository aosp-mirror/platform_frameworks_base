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
import android.graphics.Rect;
import android.os.Bundle;

import com.android.internal.widget.remotecompose.core.CoreDocument;
import com.android.internal.widget.remotecompose.core.operations.layout.ClickModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.Component;
import com.android.internal.widget.remotecompose.core.operations.layout.LayoutComponent;
import com.android.internal.widget.remotecompose.core.operations.layout.RootLayoutComponent;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.ComponentModifiers;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.ModifierOperation;
import com.android.internal.widget.remotecompose.core.semantics.AccessibilitySemantics;
import com.android.internal.widget.remotecompose.core.semantics.AccessibleComponent;
import com.android.internal.widget.remotecompose.core.semantics.CoreSemantics;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Java Player implementation of the {@link RemoteComposeDocumentAccessibility} interface. Each item
 * in the semantic tree is a {@link Component} from the remote Compose UI. Each Component can have a
 * list of modifiers that must be tagged with {@link AccessibilitySemantics} either incidentally
 * (see {@link ClickModifierOperation}) or explicitly (see {@link CoreSemantics}).
 */
public class CoreDocumentAccessibility
        implements RemoteComposeDocumentAccessibility<Component, AccessibilitySemantics> {
    private final CoreDocument mDocument;

    private final Rect mMissingBounds = new Rect(0, 0, 1, 1);

    public CoreDocumentAccessibility(CoreDocument document) {
        this.mDocument = document;
    }

    @Nullable
    @Override
    public Integer getComponentIdAt(PointF point) {
        return RootId;
    }

    @Override
    public @Nullable Component findComponentById(int virtualViewId) {
        RootLayoutComponent root = mDocument.getRootLayoutComponent();

        if (root == null || virtualViewId == -1) {
            return root;
        }

        return componentStream(root)
                .filter(op -> op.getComponentId() == virtualViewId)
                .findFirst()
                .orElse(null);
    }

    @Override
    public List<CoreSemantics.Mode> mergeMode(Component component) {
        if (!(component instanceof LayoutComponent)) {
            return Collections.singletonList(CoreSemantics.Mode.SET);
        }

        return ((LayoutComponent) component)
                .getComponentModifiers().getList().stream()
                        .filter(i -> i instanceof AccessibleComponent)
                        .map(i -> ((AccessibleComponent) i).getMode())
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
    }

    @Override
    public boolean performAction(Component component, int action, Bundle arguments) {
        if (action == ACTION_CLICK) {
            mDocument.performClick(component.getComponentId());
            return true;
        } else {
            return false;
        }
    }

    @Nullable
    @Override
    public String stringValue(int id) {
        Object value = mDocument.getRemoteComposeState().getFromId(id);
        return value != null ? String.valueOf(value) : null;
    }

    @Override
    public List<AccessibilitySemantics> semanticModifiersForComponent(Component component) {
        if (!(component instanceof LayoutComponent)) {
            return Collections.emptyList();
        }

        List<ModifierOperation> modifiers =
                ((LayoutComponent) component).getComponentModifiers().getList();

        return modifiers.stream()
                .filter(
                        it ->
                                it instanceof AccessibilitySemantics
                                        && ((AccessibilitySemantics) it)
                                                .isInterestingForSemantics())
                .map(i -> (AccessibilitySemantics) i)
                .collect(Collectors.toList());
    }

    @Override
    public List<Integer> semanticallyRelevantChildComponents(Component component) {
        return componentStream(component)
                .filter(i -> i.getComponentId() != component.getComponentId())
                .filter(CoreDocumentAccessibility::isInteresting)
                .map(Component::getComponentId)
                .collect(Collectors.toList());
    }

    static Stream<Component> componentStream(Component root) {
        return Stream.concat(
                Stream.of(root),
                root.mList.stream()
                        .flatMap(
                                op -> {
                                    if (op instanceof Component) {
                                        return componentStream((Component) op);
                                    } else {
                                        return Stream.empty();
                                    }
                                }));
    }

    static Stream<ModifierOperation> modifiersStream(Component component) {
        return component.mList.stream()
                .filter(it -> it instanceof ComponentModifiers)
                .flatMap(it -> ((ComponentModifiers) it).getList().stream());
    }

    static boolean isInteresting(Component component) {
        boolean interesting =
                isContainerWithSemantics(component)
                        || modifiersStream(component)
                                .anyMatch(CoreDocumentAccessibility::isModifierWithSemantics);

        return interesting && component.isVisible();
    }

    static boolean isModifierWithSemantics(ModifierOperation modifier) {
        return modifier instanceof AccessibilitySemantics
                && ((AccessibilitySemantics) modifier).isInterestingForSemantics();
    }

    static boolean isContainerWithSemantics(Component component) {
        if (component instanceof AccessibilitySemantics) {
            return ((AccessibilitySemantics) component).isInterestingForSemantics();
        }

        if (!(component instanceof LayoutComponent)) {
            return false;
        }

        return ((LayoutComponent) component)
                .getComponentModifiers().getList().stream()
                        .anyMatch(CoreDocumentAccessibility::isModifierWithSemantics);
    }
}
