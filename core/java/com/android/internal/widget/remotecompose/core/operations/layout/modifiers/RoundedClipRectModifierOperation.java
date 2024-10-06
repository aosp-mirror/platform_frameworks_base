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

import static com.android.internal.widget.remotecompose.core.documentation.Operation.FLOAT;

import com.android.internal.widget.remotecompose.core.CoreDocument;
import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.operations.DrawBase4;
import com.android.internal.widget.remotecompose.core.operations.layout.Component;
import com.android.internal.widget.remotecompose.core.operations.layout.DecoratorComponent;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;

import java.util.List;

/**
 * Support clip with a rounded rectangle
 */
public class RoundedClipRectModifierOperation extends DrawBase4
        implements ModifierOperation, DecoratorComponent {
    public static final int OP_CODE = Operations.MODIFIER_ROUNDED_CLIP_RECT;
    public static final String CLASS_NAME = "RoundedClipRectModifierOperation";

    public static void read(WireBuffer buffer, List<Operation> operations) {
        Maker m = RoundedClipRectModifierOperation::new;
        read(m, buffer, operations);
    }

    public static int id() {
        return OP_CODE;
    }

    public static String name() {
        return CLASS_NAME;
    }


    protected void write(WireBuffer buffer,
                         float v1,
                         float v2,
                         float v3,
                         float v4) {
        apply(buffer, v1, v2, v3, v4);
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Modifier Operations",
                        id(),
                        "RoundedClipRectModifierOperation")
                .description("clip with rectangle")
                .field(FLOAT, "topStart",
                        "The topStart radius of the rectangle to "
                                + "intersect with the current clip")
                .field(FLOAT, "topEnd",
                        "The topEnd radius of the rectangle to "
                                + "intersect with the current clip")
                .field(FLOAT, "bottomStart",
                        "The bottomStart radius of the rectangle to "
                                + "intersect with the current clip")
                .field(FLOAT, "bottomEnd",
                        "The bottomEnd radius of the rectangle to "
                                + "intersect with the current clip");
    }


    float mWidth;
    float mHeight;


    public RoundedClipRectModifierOperation(
            float topStart,
            float topEnd,
            float bottomStart,
            float bottomEnd) {
        super(topStart, topEnd, bottomStart, bottomEnd);
        mName = CLASS_NAME;
    }

    @Override
    public void paint(PaintContext context) {
        context.roundedClipRect(mWidth, mHeight, mX1, mY1, mX2, mY2);
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
                indent, "ROUNDED_CLIP_RECT = [" + mWidth + ", " + mHeight
                        + ", " + mX1 + ", " + mY1
                        + ", " + mX2 + ", " + mY2 + "]");
    }

    /**
     * Writes out the rounded rect clip to the buffer
     *
     * @param buffer        buffer to write to
     * @param topStart      topStart radius
     * @param topEnd        topEnd radius
     * @param bottomStart   bottomStart radius
     * @param bottomEnd     bottomEnd radius
     */
    public static void apply(WireBuffer buffer,
                             float topStart,
                             float topEnd,
                             float bottomStart,
                             float bottomEnd) {
        write(buffer, OP_CODE, topStart, topEnd, bottomStart, bottomEnd);
    }
}
