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
import com.android.internal.widget.remotecompose.core.PaintOperation;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;

import java.util.List;

public class MatrixRestore extends PaintOperation {
    private static final int OP_CODE = Operations.MATRIX_RESTORE;
    private static final String CLASS_NAME = "MatrixRestore";

    public MatrixRestore() {}

    @Override
    public void write(WireBuffer buffer) {
        apply(buffer);
    }

    public static void read(WireBuffer buffer, List<Operation> operations) {
        MatrixRestore op = new MatrixRestore();
        operations.add(op);
    }

    @Override
    public String toString() {
        return "MatrixRestore";
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

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Canvas Operations", OP_CODE, CLASS_NAME)
                .description("Restore the matrix and clip");
    }

    @Override
    public void paint(PaintContext context) {
        context.matrixRestore();
    }
}
