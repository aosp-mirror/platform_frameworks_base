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

public class DrawArc extends DrawBase6 {
    public static final int OP_CODE = Operations.DRAW_ARC;
    private static final String CLASS_NAME = "DrawArc";

    public static void read(WireBuffer buffer, List<Operation> operations) {
        Maker m = DrawArc::new;
        read(m, buffer, operations);
    }

    public static int id() {
        return OP_CODE;
    }

    /**
     * Writes out the operation to the buffer
     * @param buffer the buffer to write to
     * @param v1 The left side of the Oval
     * @param v2 The top of the Oval
     * @param v3 The right side of the Oval
     * @param v4 The bottom of the Oval
     * @param v5 Starting angle (in degrees) where the arc begins
     * @param v6 Sweep angle (in degrees) measured clockwise
     */
    public static void apply(WireBuffer buffer,
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

    protected void write(WireBuffer buffer,
                             float v1,
                             float v2,
                             float v3,
                             float v4,
                             float v5,
                             float v6) {
        apply(buffer, v1, v2, v3, v4, v5, v6);
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Canvas Operations",
                        OP_CODE,
                        CLASS_NAME)
                .description("Draw the specified arc"
                        + "which will be scaled to fit inside the specified oval")
                .field(FLOAT, "left",
                        "The left side of the Oval")
                .field(FLOAT, "top",
                        "The top of the Oval")
                .field(FLOAT, "right",
                        "The right side of the Oval")
                .field(FLOAT, "bottom",
                        "The bottom of the Oval")
                .field(FLOAT, "startAngle",
                        "Starting angle (in degrees) where the arc begins")
                .field(FLOAT, "sweepAngle",
                        "Sweep angle (in degrees) measured clockwise");
    }


    public DrawArc(float v1,
                   float v2,
                   float v3,
                   float v4,
                   float v5,
                   float v6) {
        super(v1, v2, v3, v4, v5, v6);
        mName = "DrawArc";
    }

    public void paint(PaintContext context) {
        context.drawArc(mV1, mV2, mV3, mV4, mV5, mV6);
    }
}
