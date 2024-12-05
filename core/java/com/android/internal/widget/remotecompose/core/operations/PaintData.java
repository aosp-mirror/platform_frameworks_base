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

import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.INT;
import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.INT_ARRAY;

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.PaintOperation;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.operations.paint.PaintBundle;

import java.util.List;

public class PaintData extends PaintOperation implements VariableSupport {
    private static final int OP_CODE = Operations.PAINT_VALUES;
    private static final String CLASS_NAME = "PaintData";
    @NonNull public PaintBundle mPaintData = new PaintBundle();
    public static final int MAX_STRING_SIZE = 4000;

    public PaintData() {}

    @Override
    public void updateVariables(@NonNull RemoteContext context) {
        mPaintData.updateVariables(context);
    }

    @Override
    public void registerListening(@NonNull RemoteContext context) {
        mPaintData.registerVars(context, this);
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mPaintData);
    }

    @NonNull
    @Override
    public String toString() {
        return "PaintData " + "\"" + mPaintData + "\"";
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

    /**
     * The OP_CODE for this command
     *
     * @return the opcode
     */
    public static int id() {
        return OP_CODE;
    }

    public static void apply(@NonNull WireBuffer buffer, @NonNull PaintBundle paintBundle) {
        buffer.start(Operations.PAINT_VALUES);
        paintBundle.writeBundle(buffer);
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        PaintData data = new PaintData();
        data.mPaintData.readBundle(buffer);
        operations.add(data);
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Data Operations", OP_CODE, CLASS_NAME)
                .description("Encode a Paint ")
                .field(INT, "length", "id string")
                .field(INT_ARRAY, "paint", "length", "path encoded as floats");
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return indent + toString();
    }

    @Override
    public void paint(@NonNull PaintContext context) {
        context.applyPaint(mPaintData);
    }
}
