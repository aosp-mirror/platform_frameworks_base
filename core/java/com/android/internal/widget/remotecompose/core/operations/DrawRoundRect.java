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

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation;

import java.util.List;

/** Draw a rounded rectangle */
public class DrawRoundRect extends DrawBase6 {
    private static final int OP_CODE = Operations.DRAW_ROUND_RECT;
    private static final String CLASS_NAME = "DrawRoundRect";

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        Maker m = DrawRoundRect::new;
        read(m, buffer, operations);
    }

    /**
     * The OP_CODE for this command
     *
     * @return the opcode
     */
    public static int id() {
        return OP_CODE;
    }

    /**
     * Writes out the operation to the buffer
     *
     * @param buffer The buffer to write to
     * @param v1 The left side of the rect
     * @param v2 The top of the rect
     * @param v3 The right side of the rect
     * @param v4 The bottom of the rect
     * @param v5 The x-radius of the oval used to round the corners
     * @param v6 The y-radius of the oval used to round the corners
     */
    public static void apply(
            @NonNull WireBuffer buffer,
            float v1,
            float v2,
            float v3,
            float v4,
            float v5,
            float v6) {
        buffer.start(OP_CODE);
        buffer.writeFloat(v1);
        buffer.writeFloat(v2);
        buffer.writeFloat(v3);
        buffer.writeFloat(v4);
        buffer.writeFloat(v5);
        buffer.writeFloat(v6);
    }

    @Override
    protected void write(
            @NonNull WireBuffer buffer,
            float v1,
            float v2,
            float v3,
            float v4,
            float v5,
            float v6) {
        apply(buffer, v1, v2, v3, v4, v5, v6);
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Canvas Operations", OP_CODE, CLASS_NAME)
                .description("Draw the specified round-rect")
                .field(DocumentedOperation.FLOAT, "left", "The left side of the rect")
                .field(DocumentedOperation.FLOAT, "top", "The top of the rect")
                .field(DocumentedOperation.FLOAT, "right", "The right side of the rect")
                .field(DocumentedOperation.FLOAT, "bottom", "The bottom of the rect")
                .field(
                        DocumentedOperation.FLOAT,
                        "rx",
                        "The x-radius of the oval used to round the corners")
                .field(
                        DocumentedOperation.FLOAT,
                        "sweepAngle",
                        "The y-radius of the oval used to round the corners");
    }

    public DrawRoundRect(float v1, float v2, float v3, float v4, float v5, float v6) {
        super(v1, v2, v3, v4, v5, v6);
        mName = CLASS_NAME;
    }

    @Override
    public void paint(@NonNull PaintContext context) {
        context.drawRoundRect(mV1, mV2, mV3, mV4, mV5, mV6);
    }
}
