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
package com.android.internal.widget.remotecompose.core.operations.layout.modifiers;

import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.FLOAT;

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.operations.layout.Component;
import com.android.internal.widget.remotecompose.core.operations.paint.PaintBundle;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;

import java.util.List;

/** Component size-aware background draw */
public class BackgroundModifierOperation extends DecoratorModifierOperation {
    private static final int OP_CODE = Operations.MODIFIER_BACKGROUND;
    private static final String CLASS_NAME = "BackgroundModifierOperation";
    float mX;
    float mY;
    float mWidth;
    float mHeight;
    float mR;
    float mG;
    float mB;
    float mA;
    int mShapeType = ShapeType.RECTANGLE;

    @NonNull public PaintBundle mPaint = new PaintBundle();

    public BackgroundModifierOperation(
            float x,
            float y,
            float width,
            float height,
            float r,
            float g,
            float b,
            float a,
            int shapeType) {
        this.mX = x;
        this.mY = y;
        this.mWidth = width;
        this.mHeight = height;
        this.mR = r;
        this.mG = g;
        this.mB = b;
        this.mA = a;
        this.mShapeType = shapeType;
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mX, mY, mWidth, mHeight, mR, mG, mB, mA, mShapeType);
    }

    @Override
    public void serializeToString(int indent, @NonNull StringSerializer serializer) {
        serializer.append(
                indent,
                "BACKGROUND = ["
                        + mX
                        + ", "
                        + mY
                        + ", "
                        + mWidth
                        + ", "
                        + mHeight
                        + "] color ["
                        + mR
                        + ", "
                        + mG
                        + ", "
                        + mB
                        + ", "
                        + mA
                        + "] shape ["
                        + mShapeType
                        + "]");
    }

    @Override
    public void layout(
            @NonNull RemoteContext context, Component component, float width, float height) {
        this.mWidth = width;
        this.mHeight = height;
    }

    @NonNull
    @Override
    public String toString() {
        return "BackgroundModifierOperation(" + mWidth + " x " + mHeight + ")";
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
            float x,
            float y,
            float width,
            float height,
            float r,
            float g,
            float b,
            float a,
            int shapeType) {
        buffer.start(OP_CODE);
        buffer.writeFloat(x);
        buffer.writeFloat(y);
        buffer.writeFloat(width);
        buffer.writeFloat(height);
        buffer.writeFloat(r);
        buffer.writeFloat(g);
        buffer.writeFloat(b);
        buffer.writeFloat(a);
        // shape type
        buffer.writeInt(shapeType);
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        float x = buffer.readFloat();
        float y = buffer.readFloat();
        float width = buffer.readFloat();
        float height = buffer.readFloat();
        float r = buffer.readFloat();
        float g = buffer.readFloat();
        float b = buffer.readFloat();
        float a = buffer.readFloat();
        // shape type
        int shapeType = buffer.readInt();
        operations.add(new BackgroundModifierOperation(x, y, width, height, r, g, b, a, shapeType));
    }

    @Override
    public void paint(@NonNull PaintContext context) {
        context.savePaint();
        mPaint.reset();
        mPaint.setStyle(PaintBundle.STYLE_FILL);
        mPaint.setColor(mR, mG, mB, mA);
        context.applyPaint(mPaint);
        if (mShapeType == ShapeType.RECTANGLE) {
            context.drawRect(0f, 0f, mWidth, mHeight);
        } else if (mShapeType == ShapeType.CIRCLE) {
            context.drawCircle(mWidth / 2f, mHeight / 2f, Math.min(mWidth, mHeight) / 2f);
        }
        context.restorePaint();
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Modifier Operations", OP_CODE, CLASS_NAME)
                .description("define the Background Modifier")
                .field(FLOAT, "x", "")
                .field(FLOAT, "y", "")
                .field(FLOAT, "width", "")
                .field(FLOAT, "height", "")
                .field(FLOAT, "r", "")
                .field(FLOAT, "g", "")
                .field(FLOAT, "b", "")
                .field(FLOAT, "a", "")
                .field(FLOAT, "shapeType", "");
    }
}
