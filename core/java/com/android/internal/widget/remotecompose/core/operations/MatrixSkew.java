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

public class MatrixSkew extends DrawBase2 {
    public static final int OP_CODE = Operations.MATRIX_SKEW;
    public static final String CLASS_NAME = "MatrixSkew";


    public static void read(WireBuffer buffer, List<Operation> operations) {
        Maker m = MatrixSkew::new;
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
                         float v2) {
        apply(buffer, v1, v2);
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Canvas Operations",
                        OP_CODE,
                        CLASS_NAME)
                .description("Current matrix with the specified skew.")
                .field(FLOAT, "skewX",
                        "The amount to skew in X")
                .field(FLOAT, "skewY",
                        "The amount to skew in Y");
    }


    public MatrixSkew(float skewX, float skewY) {
        super(skewX, skewY);
        mName = CLASS_NAME;
    }

    @Override
    public void paint(PaintContext context) {
        context.matrixSkew(mV1, mV2);
    }

    /**
     * Writes out the DrawOval to the buffer
     *
     * @param buffer buffer to write to
     * @param x1     start x of DrawOval
     * @param y1     start y of the DrawOval
     */
    public static void apply(WireBuffer buffer,
                             float x1,
                             float y1
    ) {
        write(buffer, OP_CODE, x1, y1);
    }
}
