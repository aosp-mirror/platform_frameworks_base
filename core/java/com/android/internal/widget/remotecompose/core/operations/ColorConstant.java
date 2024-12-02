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

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation;

import java.util.List;

/** Operation that defines a simple Color based on ID Mainly for colors in theming. */
public class ColorConstant extends Operation {
    private static final int OP_CODE = Operations.COLOR_CONSTANT;
    private static final String CLASS_NAME = "ColorConstant";

    /** the id of the color */
    public int mColorId;

    /** the color value (AARRGGBB) */
    public int mColor;

    /**
     * Creat a color constant
     *
     * @param colorId id of color
     * @param color AARRGGBB value
     */
    public ColorConstant(int colorId, int color) {
        this.mColorId = colorId;
        this.mColor = color;
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mColorId, mColor);
    }

    @NonNull
    @Override
    public String toString() {
        return "ColorConstant[" + mColorId + "] = " + Utils.colorInt(mColor) + "";
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

    /**
     * Writes out the operation to the buffer
     *
     * @param buffer
     * @param colorId
     * @param color
     */
    public static void apply(@NonNull WireBuffer buffer, int colorId, int color) {
        buffer.start(OP_CODE);
        buffer.writeInt(colorId);
        buffer.writeInt(color);
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        int colorId = buffer.readInt();
        int color = buffer.readInt();
        operations.add(new ColorConstant(colorId, color));
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Expressions Operations", OP_CODE, CLASS_NAME)
                .description("Define a Color")
                .field(DocumentedOperation.INT, "id", "Id of the color")
                .field(INT, "color", "32 bit ARGB color");
    }

    @Override
    public void apply(@NonNull RemoteContext context) {
        context.loadColor(mColorId, mColor);
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return indent + toString();
    }
}
