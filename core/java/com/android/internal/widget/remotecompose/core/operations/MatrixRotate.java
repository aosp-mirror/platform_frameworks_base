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

public class MatrixRotate extends DrawBase3 {
    public static final int OP_CODE = Operations.MATRIX_ROTATE;
    private static final String CLASS_NAME = "MatrixRotate";

    public static void read(WireBuffer buffer, List<Operation> operations) {
        Maker m = new Maker() {
            @Override
            public DrawBase3 create(float v1, float v2, float v3) {
                return new MatrixRotate(v1, v2, v3);
            }
        };
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
                .description("apply rotation to matrix")
                .field(FLOAT, "rotate", "Angle to rotate")
                .field(FLOAT, "pivotX", "X Pivot point")
                .field(FLOAT, "pivotY", "Y Pivot point");
    }


    protected void write(WireBuffer buffer,
                         float v1,
                         float v2,
                         float v3) {
        apply(buffer, v1, v2, v3);
    }

    public MatrixRotate(float rotate, float pivotX, float pivotY) {
        super(rotate, pivotX, pivotY);
        mName = CLASS_NAME;
    }

    @Override
    public void paint(PaintContext context) {
        context.matrixRotate(mV1, mV2, mV3);
    }

    /**
     * Writes out the operation to the buffer
     *
     * @param buffer The buffer to write into
     * @param x1 Angle to rotate
     * @param y1 X Pivot point
     * @param x2 Y Pivot point
     */
    public static void apply(WireBuffer buffer,
                             float x1,
                             float y1,
                             float x2) {
        buffer.start(OP_CODE);
        buffer.writeFloat(x1);
        buffer.writeFloat(y1);
        buffer.writeFloat(x2);
    }
}
