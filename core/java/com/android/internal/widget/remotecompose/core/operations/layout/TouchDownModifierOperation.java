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
import com.android.internal.widget.remotecompose.core.serialize.MapSerializer;

import java.util.List;

/** Represents a touch down modifier + actions */
public class TouchDownModifierOperation extends ListActionsOperation implements TouchHandler {

    private static final int OP_CODE = Operations.MODIFIER_TOUCH_DOWN;

    public TouchDownModifierOperation() {
        super("TOUCH_DOWN_MODIFIER");
    }

    @Override
    public void write(WireBuffer buffer) {
        apply(buffer);
    }

    @Override
    public String toString() {
        return "TouchDownModifier";
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
        if (applyActions(context, document, component, x, y, false)) {
            document.appliedTouchOperation(component);
        }
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
        // nothing
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
        return "TouchModifier";
    }

    /**
     * Write the operation to the buffer
     *
     * @param buffer a WireBuffer
     */
    public static void apply(WireBuffer buffer) {
        buffer.start(OP_CODE);
    }

    /**
     * Read the operation from the buffer
     *
     * @param buffer a WireBuffer
     * @param operations the list of operations we read so far
     */
    public static void read(WireBuffer buffer, List<Operation> operations) {
        operations.add(new TouchDownModifierOperation());
    }

    /**
     * Add documentation for this operation
     *
     * @param doc a DocumentationBuilder
     */
    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Modifier Operations", OP_CODE, name())
                .description(
                        "Touch down modifier. This operation contains"
                                + " a list of action executed on Touch down");
    }

    @Override
    public void serialize(MapSerializer serializer) {
        super.serialize(serializer);
        serializer.add("type", "TouchDownModifierOperation");
    }
}
