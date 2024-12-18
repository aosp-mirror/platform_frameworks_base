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
import com.android.internal.widget.remotecompose.core.SerializableToString;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;

import java.util.List;

public class DrawLine extends DrawBase4 implements SerializableToString {
    private static final int OP_CODE = Operations.DRAW_LINE;
    private static final String CLASS_NAME = "DrawLine";

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        Maker m = DrawLine::new;
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

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Canvas Operations", OP_CODE, CLASS_NAME)
                .description("Draw a line segment")
                .field(
                        DocumentedOperation.FLOAT,
                        "startX",
                        "The x-coordinate of the start point of the line")
                .field(
                        DocumentedOperation.FLOAT,
                        "startY",
                        "The y-coordinate of the start point of the line")
                .field(
                        DocumentedOperation.FLOAT,
                        "endX",
                        "The x-coordinate of the end point of the line")
                .field(
                        DocumentedOperation.FLOAT,
                        "endY",
                        "The y-coordinate of the end point of the line");
    }

    @Override
    protected void write(@NonNull WireBuffer buffer, float v1, float v2, float v3, float v4) {
        apply(buffer, v1, v2, v3, v4);
    }

    public DrawLine(float left, float top, float right, float bottom) {
        super(left, top, right, bottom);
        mName = "DrawLine";
    }

    @Override
    public void paint(@NonNull PaintContext context) {
        context.drawLine(mX1, mY1, mX2, mY2);
    }

    /**
     * Writes out the DrawLine to the buffer
     *
     * @param buffer buffer to write to
     * @param x1 start x of line
     * @param y1 start y of the line
     * @param x2 end x of the line
     * @param y2 end y of the line
     */
    public static void apply(@NonNull WireBuffer buffer, float x1, float y1, float x2, float y2) {
        write(buffer, OP_CODE, x1, y1, x2, y2);
    }

    @Override
    public void serializeToString(int indent, @NonNull StringSerializer serializer) {
        String x1 = "" + mX1;
        if (Float.isNaN(mX1Value)) {
            x1 = "[" + Utils.idFromNan(mX1Value) + " = " + mX1 + "]";
        }
        String y1 = "" + mY1;
        if (Float.isNaN(mY1Value)) {
            y1 = "[" + Utils.idFromNan(mY1Value) + " = " + mY1 + "]";
        }
        String x2 = "" + mX2;
        if (Float.isNaN(mX2Value)) {
            x2 = "[" + Utils.idFromNan(mX2Value) + " = " + mX2 + "]";
        }
        String y2 = "" + mY2;
        if (Float.isNaN(mY2Value)) {
            y2 = "[" + Utils.idFromNan(mY2Value) + " = " + mY2 + "]";
        }
        serializer.append(indent, CLASS_NAME + "(" + x1 + ", " + y1 + ", " + x2 + ", " + y2 + ")");
    }
}
