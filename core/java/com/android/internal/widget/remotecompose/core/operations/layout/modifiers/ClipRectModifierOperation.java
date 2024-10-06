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
package com.android.internal.widget.remotecompose.core.operations.layout.modifiers;

import com.android.internal.widget.remotecompose.core.CoreDocument;
import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.operations.layout.Component;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;

import java.util.List;

/**
 * Support modifier clip with a rectangle
 */
public class ClipRectModifierOperation extends DecoratorModifierOperation {
    public static final String CLASS_NAME = "ClipRectModifierOperation";
    private static final int OP_CODE = Operations.MODIFIER_CLIP_RECT;
    float mWidth;
    float mHeight;

    @Override
    public void paint(PaintContext context) {
        context.clipRect(0f, 0f, mWidth, mHeight);
    }

    @Override
    public void layout(RemoteContext context, float width, float height) {
        this.mWidth = width;
        this.mHeight = height;
    }

    @Override
    public void onClick(RemoteContext context, CoreDocument document,
                        Component component, float x, float y) {
        // nothing
    }

    @Override
    public void serializeToString(int indent, StringSerializer serializer) {
        serializer.append(
                indent, "CLIP_RECT = [" + mWidth + ", " + mHeight + "]");
    }

    @Override
    public void write(WireBuffer buffer) {
        apply(buffer);
    }

    public static String name() {
        return CLASS_NAME;
    }


    public static int id() {
        return OP_CODE;
    }

    public static void apply(WireBuffer buffer) {
        buffer.start(OP_CODE);
    }


    public static void read(WireBuffer buffer, List<Operation> operations) {
        operations.add(new ClipRectModifierOperation());
    }


    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Canvas Operations",
                        OP_CODE,
                        CLASS_NAME)
                .description("Draw the specified round-rect");
    }
}
