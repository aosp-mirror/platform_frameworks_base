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
import com.android.internal.widget.remotecompose.core.PaintOperation;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation;

import java.util.List;

/** Draw Text in Anchored to a point */
public class DrawTextAnchored extends PaintOperation implements VariableSupport {
    private static final int OP_CODE = Operations.DRAW_TEXT_ANCHOR;
    private static final String CLASS_NAME = "DrawTextAnchored";
    int mTextID;
    float mX;
    float mY;
    float mPanX;
    float mPanY;
    int mFlags;
    float mOutX;
    float mOutY;
    float mOutPanX;
    float mOutPanY;

    public static final int ANCHOR_TEXT_RTL = 1;
    public static final int ANCHOR_MONOSPACE_MEASURE = 2;

    public DrawTextAnchored(int textID, float x, float y, float panX, float panY, int flags) {
        mTextID = textID;
        mX = x;
        mY = y;
        mOutX = mX;
        mOutY = mY;
        mFlags = flags;
        mOutPanX = mPanX = panX;
        mOutPanY = mPanY = panY;
    }

    @Override
    public void updateVariables(@NonNull RemoteContext context) {
        mOutX = Float.isNaN(mX) ? context.getFloat(Utils.idFromNan(mX)) : mX;
        mOutY = Float.isNaN(mY) ? context.getFloat(Utils.idFromNan(mY)) : mY;
        mOutPanX = Float.isNaN(mPanX) ? context.getFloat(Utils.idFromNan(mPanX)) : mPanX;
        mOutPanY = Float.isNaN(mPanY) ? context.getFloat(Utils.idFromNan(mPanY)) : mPanY;
    }

    @Override
    public void registerListening(@NonNull RemoteContext context) {
        if (Float.isNaN(mX)) {
            context.listensTo(Utils.idFromNan(mX), this);
        }
        if (Float.isNaN(mY)) {
            context.listensTo(Utils.idFromNan(mY), this);
        }
        if (Float.isNaN(mPanX)) {
            context.listensTo(Utils.idFromNan(mPanX), this);
        }
        if (Float.isNaN(mPanY) && Utils.idFromNan(mPanY) > 0) {
            context.listensTo(Utils.idFromNan(mPanY), this);
        }
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mTextID, mX, mY, mPanX, mPanY, mFlags);
    }

    @NonNull
    @Override
    public String toString() {
        return "DrawTextAnchored ["
                + mTextID
                + "] "
                + floatToStr(mX)
                + ", "
                + floatToStr(mY)
                + ", "
                + floatToStr(mPanX)
                + ", "
                + floatToStr(mPanY)
                + ", "
                + Integer.toBinaryString(mFlags);
    }

    private static String floatToStr(float v) {
        if (Float.isNaN(v)) {
            return "[" + Utils.idFromNan(v) + "]";
        }
        return Float.toString(v);
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        int textID = buffer.readInt();
        float x = buffer.readFloat();
        float y = buffer.readFloat();
        float panX = buffer.readFloat();
        float panY = buffer.readFloat();
        int flags = buffer.readInt();

        DrawTextAnchored op = new DrawTextAnchored(textID, x, y, panX, panY, flags);

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

    /**
     * Writes out the operation to the buffer
     *
     * @param buffer The buffer to write to
     * @param textID The id of the text data
     * @param x The x-position of the anchor point
     * @param y The y-position of the anchor point
     * @param panX The pan from left(-1) to right(1) 0 being centered
     * @param panY The pan from top(-1) to bottom(1) 0 being centered
     * @param flags Change the behaviour
     */
    public static void apply(
            @NonNull WireBuffer buffer,
            int textID,
            float x,
            float y,
            float panX,
            float panY,
            int flags) {
        buffer.start(OP_CODE);
        buffer.writeInt(textID);
        buffer.writeFloat(x);
        buffer.writeFloat(y);
        buffer.writeFloat(panX);
        buffer.writeFloat(panY);
        buffer.writeInt(flags);
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Draw Operations", OP_CODE, CLASS_NAME)
                .description("Draw text centered about an anchor point")
                .field(DocumentedOperation.INT, "textId", "id of bitmap")
                .field(DocumentedOperation.FLOAT, "x", "The x-position of the anchor point")
                .field(DocumentedOperation.FLOAT, "y", "The y-position of the anchor point")
                .field(
                        DocumentedOperation.FLOAT,
                        "panX",
                        "The pan from left(-1) to right(1) 0 being centered")
                .field(
                        DocumentedOperation.FLOAT,
                        "panY",
                        "The pan from top(-1) to bottom(1) 0 being centered")
                .field(DocumentedOperation.INT, "flags", "Change the behaviour");
    }

    @NonNull float[] mBounds = new float[4];

    private float getHorizontalOffset() {
        // TODO scale  TextSize / BaseTextSize;
        float scale = 1.0f;

        float textWidth = scale * (mBounds[2] - mBounds[0]);
        float boxWidth = 0;
        return (boxWidth - textWidth) * (1 + mOutPanX) / 2.f - (scale * mBounds[0]);
    }

    private float getVerticalOffset() {
        // TODO scale TextSize / BaseTextSize;
        float scale = 1.0f;
        float boxHeight = 0;
        float textHeight = scale * (mBounds[3] - mBounds[1]);
        return (boxHeight - textHeight) * (1 - mOutPanY) / 2 - (scale * mBounds[1]);
    }

    @Override
    public void paint(@NonNull PaintContext context) {
        int flags =
                ((mFlags & ANCHOR_MONOSPACE_MEASURE) != 0)
                        ? PaintContext.TEXT_MEASURE_MONOSPACE_WIDTH
                        : 0;
        context.getTextBounds(mTextID, 0, -1, flags, mBounds);
        float x = mOutX + getHorizontalOffset();
        float y = Float.isNaN(mOutPanY) ? mOutY : mOutY + getVerticalOffset();
        context.drawTextRun(mTextID, 0, -1, 0, 1, x, y, (mFlags & ANCHOR_TEXT_RTL) == 1);
    }
}
