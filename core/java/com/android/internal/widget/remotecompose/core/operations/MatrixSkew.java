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

import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.FLOAT;

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;

import java.util.List;

/** Skew the matrix command */
public class MatrixSkew extends DrawBase2 {
    private static final int OP_CODE = Operations.MATRIX_SKEW;
    private static final String CLASS_NAME = "MatrixSkew";

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        Maker m = MatrixSkew::new;
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
    protected void write(@NonNull WireBuffer buffer, float v1, float v2) {
        apply(buffer, v1, v2);
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Canvas Operations", OP_CODE, CLASS_NAME)
                .description("Current matrix with the specified skew.")
                .field(FLOAT, "skewX", "The amount to skew in X")
                .field(FLOAT, "skewY", "The amount to skew in Y");
    }

    public MatrixSkew(float skewX, float skewY) {
        super(skewX, skewY);
        mName = CLASS_NAME;
    }

    @Override
    public void paint(@NonNull PaintContext context) {
        context.matrixSkew(mV1, mV2);
    }

    /**
     * Writes out the DrawOval to the buffer
     *
     * @param buffer buffer to write to
     * @param x1 start x of DrawOval
     * @param y1 start y of the DrawOval
     */
    public static void apply(@NonNull WireBuffer buffer, float x1, float y1) {
        write(buffer, OP_CODE, x1, y1);
    }
}
