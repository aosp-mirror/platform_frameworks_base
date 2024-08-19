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

import com.android.internal.widget.remotecompose.core.CompanionOperation;
import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.PaintOperation;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;

import java.util.List;

/**
 * Draw Text in Anchored to a point
 */
public class DrawTextAnchored extends PaintOperation implements VariableSupport {
    public static final Companion COMPANION = new Companion();
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

    public DrawTextAnchored(int textID,
                            float x,
                            float y,
                            float panX,
                            float panY,
                            int flags) {
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
    public void updateVariables(RemoteContext context) {
        mOutX = (Float.isNaN(mX))
                ? context.getFloat(Utils.idFromNan(mX)) : mX;
        mOutY = (Float.isNaN(mY))
                ? context.getFloat(Utils.idFromNan(mY)) : mY;
        mOutPanX = (Float.isNaN(mPanX))
                ? context.getFloat(Utils.idFromNan(mPanX)) : mPanX;
        mOutPanY = (Float.isNaN(mPanY))
                ? context.getFloat(Utils.idFromNan(mPanY)) : mPanY;
    }

    @Override
    public void registerListening(RemoteContext context) {
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
    public void write(WireBuffer buffer) {
        COMPANION.apply(buffer, mTextID, mX,
                mY,
                mPanX,
                mPanY,
                mFlags);
    }

    @Override
    public String toString() {
        return "DrawTextAnchored [" + mTextID + "] " + floatToStr(mX) + ", "
                + floatToStr(mY) + ", "
                + floatToStr(mPanX) + ", " + floatToStr(mPanY) + ", "
                + Integer.toBinaryString(mFlags);
    }

    private static String floatToStr(float v) {
        if (Float.isNaN(v)) {
            return "[" + Utils.idFromNan(v) + "]";
        }
        return Float.toString(v);
    }

    public static class Companion implements CompanionOperation {
        private Companion() {
        }

        @Override
        public void read(WireBuffer buffer, List<Operation> operations) {
            int textID = buffer.readInt();
            float x = buffer.readFloat();
            float y = buffer.readFloat();
            float panX = buffer.readFloat();
            float panY = buffer.readFloat();
            int flags = buffer.readInt();

            DrawTextAnchored op = new DrawTextAnchored(textID,
                    x, y,
                    panX, panY,
                    flags);

            operations.add(op);
        }

        @Override
        public String name() {
            return "";
        }

        @Override
        public int id() {
            return 0;
        }

        /**
         * Writes out the operation to the buffer
         * @param buffer
         * @param textID
         * @param x
         * @param y
         * @param panX
         * @param panY
         * @param flags
         */
        public void apply(WireBuffer buffer,
                          int textID,
                          float x,
                          float y,
                          float panX,
                          float panY,
                          int flags) {
            buffer.start(Operations.DRAW_TEXT_ANCHOR);
            buffer.writeInt(textID);
            buffer.writeFloat(x);
            buffer.writeFloat(y);
            buffer.writeFloat(panX);
            buffer.writeFloat(panY);
            buffer.writeInt(flags);
        }
    }

    float[] mBounds = new float[4];

    private float getHorizontalOffset() {
        // TODO scale  TextSize / BaseTextSize;
        float scale = 1.0f;

        float textWidth = scale * (mBounds[2] - mBounds[0]);
        float boxWidth = 0;
        return (boxWidth - textWidth) * (1 + mOutPanX) / 2.f
                - (scale * mBounds[0]);
    }

    private float getVerticalOffset() {
        // TODO scale TextSize / BaseTextSize;
        float scale = 1.0f;
        float boxHeight = 0;
        float textHeight = scale * (mBounds[3] - mBounds[1]);
        return (boxHeight - textHeight) * (1 - mOutPanY) / 2
                - (scale * mBounds[1]);
    }

    @Override
    public void paint(PaintContext context) {
        context.getTextBounds(mTextID, 0, -1,
                (mFlags & ANCHOR_MONOSPACE_MEASURE) != 0, mBounds);
        float x = mOutX + getHorizontalOffset();
        float y = (Float.isNaN(mOutPanY)) ? mOutY : mOutY + getVerticalOffset();
        context.drawTextRun(mTextID, 0, -1, 0, 1, x, y,
                (mFlags & ANCHOR_TEXT_RTL) == 1);
    }
}
