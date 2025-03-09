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
package com.android.internal.widget.remotecompose.core.operations.layout;

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.CoreDocument;
import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;

import java.util.List;

/** Represents a touch cancel modifier + actions */
public class TouchCancelModifierOperation extends ListActionsOperation implements TouchHandler {

    private static final int OP_CODE = Operations.MODIFIER_TOUCH_CANCEL;

    public TouchCancelModifierOperation() {
        super("TOUCH_CANCEL_MODIFIER");
    }

    @Override
    public void write(WireBuffer buffer) {
        apply(buffer);
    }

    @Override
    public String toString() {
        return "TouchCancelModifier";
    }

    @Override
    public void apply(RemoteContext context) {
        RootLayoutComponent root = context.getDocument().getRootLayoutComponent();
        if (root != null) {
            root.setHasTouchListeners(true);
        }
        super.apply(context);
    }

    @Override
    public void onTouchDown(
            RemoteContext context, CoreDocument document, Component component, float x, float y) {
        // nothing
    }

    @Override
    public void onTouchUp(
            RemoteContext context,
            CoreDocument document,
            Component component,
            float x,
            float y,
            float dx,
            float dy) {
        // nothing
    }

    @Override
    public void onTouchCancel(
            RemoteContext context, CoreDocument document, Component component, float x, float y) {
        applyActions(context, document, component, x, y, true);
    }

    @Override
    public void onTouchDrag(
            RemoteContext context, CoreDocument document, Component component, float x, float y) {
        // nothing
    }

    /**
     * The name of the class
     *
     * @return the name
     */
    @NonNull
    public static String name() {
        return "TouchCancelModifier";
    }

    public static void apply(WireBuffer buffer) {
        buffer.start(OP_CODE);
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(WireBuffer buffer, List<Operation> operations) {
        operations.add(new TouchCancelModifierOperation());
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Modifier Operations", OP_CODE, name())
                .description(
                        "Touch cancel modifier. This operation contains"
                                + " a list of action executed on Touch cancel");
    }
}
