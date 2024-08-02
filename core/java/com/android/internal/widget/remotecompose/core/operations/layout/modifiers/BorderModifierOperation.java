/*
 * Copyright (C) 2023 The Android Open Source Project
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

import com.android.internal.widget.remotecompose.core.CompanionOperation;
import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.operations.paint.PaintBundle;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;

import java.util.List;

/**
 * Component size-aware border draw
 */
public class BorderModifierOperation extends DecoratorModifierOperation {

    public static final BorderModifierOperation.Companion COMPANION =
            new BorderModifierOperation.Companion();

    float mX;
    float mY;
    float mWidth;
    float mHeight;
    float mBorderWidth;
    float mRoundedCorner;
    float mR;
    float mG;
    float mB;
    float mA;
    int mShapeType = ShapeType.RECTANGLE;

    public PaintBundle paint = new PaintBundle();

    public BorderModifierOperation(float x, float y, float width, float height,
                                   float borderWidth, float roundedCorner,
                                   float r, float g, float b, float a, int shapeType) {
        this.mX = x;
        this.mY = y;
        this.mWidth = width;
        this.mHeight = height;
        this.mBorderWidth = borderWidth;
        this.mRoundedCorner = roundedCorner;
        this.mR = r;
        this.mG = g;
        this.mB = b;
        this.mA = a;
        this.mShapeType = shapeType;
    }

    @Override
    public void serializeToString(int indent, StringSerializer serializer) {
        serializer.append(indent, "BORDER = [" + mX + ", " + mY + ", "
                + mWidth + ", " + mHeight + "] "
                + "color [" + mR + ", " + mG + ", " + mB + ", " + mA + "] "
                + "border [" + mBorderWidth + ", " + mRoundedCorner + "] "
                + "shape [" + mShapeType + "]");
    }

    @Override
    public void write(WireBuffer buffer) {
        COMPANION.apply(buffer, mX, mY, mWidth, mHeight, mBorderWidth, mRoundedCorner,
                mR, mG, mB, mA, mShapeType);
    }

    @Override
    public void layout(RemoteContext context, float width, float height) {
        this.mWidth = width;
        this.mHeight = height;
    }

    @Override
    public String toString() {
        return "BorderModifierOperation(" + mX + "," + mY + " - " + mWidth + " x " + mHeight + ") "
                + "borderWidth(" + mBorderWidth + ") "
                + "color(" + mR + "," + mG + "," + mB + "," + mA + ")";
    }

    public static class Companion implements CompanionOperation {

        @Override
        public String name() {
            return "BorderModifier";
        }

        @Override
        public int id() {
            return Operations.MODIFIER_BORDER;
        }

        public void apply(WireBuffer buffer, float x, float y, float width, float height,
                                 float borderWidth, float roundedCorner,
                                 float r, float g, float b, float a,
                                 int shapeType) {
            buffer.start(Operations.MODIFIER_BORDER);
            buffer.writeFloat(x);
            buffer.writeFloat(y);
            buffer.writeFloat(width);
            buffer.writeFloat(height);
            buffer.writeFloat(borderWidth);
            buffer.writeFloat(roundedCorner);
            buffer.writeFloat(r);
            buffer.writeFloat(g);
            buffer.writeFloat(b);
            buffer.writeFloat(a);
            // shape type
            buffer.writeInt(shapeType);
        }

        @Override
        public void read(WireBuffer buffer, List<Operation> operations) {
            float x = buffer.readFloat();
            float y = buffer.readFloat();
            float width = buffer.readFloat();
            float height = buffer.readFloat();
            float bw = buffer.readFloat();
            float rc = buffer.readFloat();
            float r = buffer.readFloat();
            float g = buffer.readFloat();
            float b = buffer.readFloat();
            float a = buffer.readFloat();
            // shape type
            int shapeType = buffer.readInt();
            operations.add(new BorderModifierOperation(x, y, width, height, bw,
                    rc, r, g, b, a, shapeType));
        }
    }

    @Override
    public void paint(PaintContext context) {
        context.savePaint();
        paint.reset();
        paint.setColor(mR, mG, mB, mA);
        paint.setStrokeWidth(mBorderWidth);
        paint.setStyle(PaintBundle.STYLE_STROKE);
        context.applyPaint(paint);
        if (mShapeType == ShapeType.RECTANGLE) {
            context.drawRect(0f, 0f, mWidth, mHeight);
        } else {
            float size = mRoundedCorner;
            if (mShapeType == ShapeType.CIRCLE) {
                size = Math.min(mWidth, mHeight) / 2f;
            }
            context.drawRoundRect(0f, 0f, mWidth, mHeight, size, size);
        }
        context.restorePaint();
    }
}
