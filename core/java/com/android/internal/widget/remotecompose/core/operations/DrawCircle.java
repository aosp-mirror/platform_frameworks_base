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

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation;

import java.util.List;

public class DrawCircle extends DrawBase3 {
    private static final int OP_CODE = Operations.DRAW_CIRCLE;
    private static final String CLASS_NAME = "DrawCircle";

    public static void read(WireBuffer buffer, List<Operation> operations) {
        Maker m = DrawCircle::new;
        read(m, buffer, operations);
    }

    public static int id() {
        return OP_CODE;
    }

    public static String name() {
        return CLASS_NAME;
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Canvas Operations", OP_CODE, CLASS_NAME)
                .description("Draw a Circle")
                .field(
                        DocumentedOperation.FLOAT,
                        "centerX",
                        "The x-coordinate of the center of the circle to be drawn")
                .field(
                        DocumentedOperation.FLOAT,
                        "centerY",
                        "The y-coordinate of the center of the circle to be drawn")
                .field(DocumentedOperation.FLOAT, "radius", "The radius of the circle to be drawn");
    }

    @Override
    protected void write(WireBuffer buffer, float v1, float v2, float v3) {
        apply(buffer, v1, v2, v3);
    }

    public DrawCircle(float left, float top, float right) {
        super(left, top, right);
        mName = CLASS_NAME;
    }

    @Override
    public void paint(PaintContext context) {
        context.drawCircle(mV1, mV2, mV3);
    }

    /**
     * Writes out the operation to the buffer
     *
     * @param buffer
     * @param x1
     * @param y1
     * @param x2
     */
    public static void apply(WireBuffer buffer, float x1, float y1, float x2) {
        buffer.start(OP_CODE);
        buffer.writeFloat(x1);
        buffer.writeFloat(y1);
        buffer.writeFloat(x2);
    }
}
