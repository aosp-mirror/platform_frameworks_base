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

/** Scale the rendering matrix command */
public class MatrixScale extends DrawBase4 {
    private static final int OP_CODE = Operations.MATRIX_SCALE;
    private static final String CLASS_NAME = "MatrixScale";

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        Maker m = MatrixScale::new;
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
     * The name of the class
     *
     * @return the name
     */
    @NonNull
    public static String name() {
        return CLASS_NAME;
    }

    @Override
    protected void write(@NonNull WireBuffer buffer, float v1, float v2, float v3, float v4) {
        apply(buffer, v1, v2, v3, v4);
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Canvas Operations", OP_CODE, CLASS_NAME)
                .description("Scale the following draw commands")
                .field(DocumentedOperation.FLOAT, "scaleX", "The amount to scale in X")
                .field(DocumentedOperation.FLOAT, "scaleY", "The amount to scale in Y")
                .field(DocumentedOperation.FLOAT, "pivotX", "The x-coordinate for the pivot point")
                .field(DocumentedOperation.FLOAT, "pivotY", "The y-coordinate for the pivot point");
    }

    public MatrixScale(float scaleX, float scaleY, float centerX, float centerY) {
        super(scaleX, scaleY, centerX, centerY);
        mName = CLASS_NAME;
    }

    @Override
    public void paint(@NonNull PaintContext context) {
        context.matrixScale(mX1, mY1, mX2, mY2);
    }

    /**
     * Writes out the DrawOval to the buffer
     *
     * @param buffer buffer to write to
     * @param x1 start x of DrawOval
     * @param y1 start y of the DrawOval
     * @param x2 end x of the DrawOval
     * @param y2 end y of the DrawOval
     */
    public static void apply(@NonNull WireBuffer buffer, float x1, float y1, float x2, float y2) {
        write(buffer, OP_CODE, x1, y1, x2, y2);
    }
}
