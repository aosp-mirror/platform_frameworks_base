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
package com.android.internal.widget.remotecompose.core.operations;

import static com.android.internal.widget.remotecompose.core.documentation.Operation.FLOAT;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;

import java.util.List;

/**
 * Draw a Rectangle
 */
public class DrawRect extends DrawBase4 {
    private static final int OP_CODE = Operations.DRAW_RECT;
    private static final String CLASS_NAME = "DrawRect";

    public static void read(WireBuffer buffer, List<Operation> operations) {
        Maker m = DrawRect::new;
        read(m, buffer, operations);
    }


    public static int id() {
        return OP_CODE;
    }

    public static String name() {
        return CLASS_NAME;
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Canvas Operations",
                        OP_CODE,
                        CLASS_NAME)
                .description("Draw the specified rectangle")
                .field(FLOAT, "left",
                        "The left side of the rectangle")
                .field(FLOAT, "top",
                        "The top of the rectangle")
                .field(FLOAT, "right",
                        "The right side of the rectangle")
                .field(FLOAT, "bottom",
                        "The bottom of the rectangle");
    }


    protected void write(WireBuffer buffer,
                         float v1,
                         float v2,
                         float v3,
                         float v4) {
        apply(buffer, v1, v2, v3, v4);
    }

    public DrawRect(
            float left,
            float top,
            float right,
            float bottom) {
        super(left, top, right, bottom);
        mName = CLASS_NAME;
    }

    @Override
    public void paint(PaintContext context) {
        context.drawRect(mX1, mY1, mX2, mY2);
    }

    /**
     * Writes out the DrawRect to the buffer
     *
     * @param buffer buffer to write to
     * @param x1     left x of rect
     * @param y1     top y of the rect
     * @param x2     right x of the rect
     * @param y2     bottom y of the rect
     */
    public static void apply(WireBuffer buffer,
                             float x1,
                             float y1,
                             float x2,
                             float y2) {
        write(buffer, OP_CODE, x1, y1, x2, y2);
    }
}
