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
import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.INT;

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.PaintOperation;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;

import java.util.List;

public class DrawBitmap extends PaintOperation implements VariableSupport {
    private static final int OP_CODE = Operations.DRAW_BITMAP;
    private static final String CLASS_NAME = "DrawBitmap";
    float mLeft;
    float mTop;
    float mRight;
    float mBottom;
    float mOutputLeft;
    float mOutputTop;
    float mOutputRight;
    float mOutputBottom;
    int mId;
    int mDescriptionId = 0;

    public DrawBitmap(
            int imageId, float left, float top, float right, float bottom, int descriptionId) {
        mLeft = left;
        mTop = top;
        mRight = right;
        mBottom = bottom;
        mId = imageId;
        mDescriptionId = descriptionId;
    }

    @Override
    public void updateVariables(@NonNull RemoteContext context) {
        mOutputLeft = Float.isNaN(mLeft) ? context.getFloat(Utils.idFromNan(mLeft)) : mLeft;
        mOutputTop = Float.isNaN(mTop) ? context.getFloat(Utils.idFromNan(mTop)) : mTop;
        mOutputRight = Float.isNaN(mRight) ? context.getFloat(Utils.idFromNan(mRight)) : mRight;
        mOutputBottom = Float.isNaN(mBottom) ? context.getFloat(Utils.idFromNan(mBottom)) : mBottom;
    }

    @Override
    public void registerListening(@NonNull RemoteContext context) {
        if (Float.isNaN(mLeft)) {
            context.listensTo(Utils.idFromNan(mLeft), this);
        }
        if (Float.isNaN(mTop)) {
            context.listensTo(Utils.idFromNan(mTop), this);
        }
        if (Float.isNaN(mRight)) {
            context.listensTo(Utils.idFromNan(mRight), this);
        }
        if (Float.isNaN(mBottom)) {
            context.listensTo(Utils.idFromNan(mBottom), this);
        }
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mId, mLeft, mTop, mRight, mBottom, mDescriptionId);
    }

    @NonNull
    @Override
    public String toString() {
        return "DrawBitmap (desc="
                + mDescriptionId
                + ")"
                + mLeft
                + " "
                + mTop
                + " "
                + mRight
                + " "
                + mBottom
                + ";";
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        int id = buffer.readInt();
        float sLeft = buffer.readFloat();
        float srcTop = buffer.readFloat();
        float srcRight = buffer.readFloat();
        float srcBottom = buffer.readFloat();
        int descriptionId = buffer.readInt();

        DrawBitmap op = new DrawBitmap(id, sLeft, srcTop, srcRight, srcBottom, descriptionId);
        operations.add(op);
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

    public static void apply(
            @NonNull WireBuffer buffer,
            int id,
            float left,
            float top,
            float right,
            float bottom,
            int descriptionId) {
        buffer.start(Operations.DRAW_BITMAP);
        buffer.writeInt(id);
        buffer.writeFloat(left);
        buffer.writeFloat(top);
        buffer.writeFloat(right);
        buffer.writeFloat(bottom);
        buffer.writeInt(descriptionId);
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Draw Operations", OP_CODE, CLASS_NAME)
                .description("Draw a bitmap")
                .field(INT, "id", "id of float")
                .field(FLOAT, "left", "The left side of the image")
                .field(FLOAT, "top", "The top of the image")
                .field(FLOAT, "right", "The right side of the image")
                .field(FLOAT, "bottom", "The bottom of the image")
                .field(INT, "descriptionId", "id of string");
    }

    @Override
    public void paint(@NonNull PaintContext context) {
        context.drawBitmap(mId, mOutputLeft, mOutputTop, mOutputRight, mOutputBottom);
    }
}
