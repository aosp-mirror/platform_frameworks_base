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

import static com.android.internal.widget.remotecompose.core.operations.Utils.floatToString;

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

/** Draw Text */
public class DrawText extends PaintOperation implements VariableSupport {
    private static final int OP_CODE = Operations.DRAW_TEXT_RUN;
    private static final String CLASS_NAME = "DrawText";
    int mTextID;
    int mStart = 0;
    int mEnd = 0;
    int mContextStart = 0;
    int mContextEnd = 0;
    float mX = 0f;
    float mY = 0f;
    float mOutX = 0f;
    float mOutY = 0f;
    boolean mRtl = false;

    public DrawText(
            int textID,
            int start,
            int end,
            int contextStart,
            int contextEnd,
            float x,
            float y,
            boolean rtl) {
        mTextID = textID;
        mStart = start;
        mEnd = end;
        mContextStart = contextStart;
        mContextEnd = contextEnd;
        mOutX = mX = x;
        mOutY = mY = y;
        mRtl = rtl;
    }

    @Override
    public void updateVariables(@NonNull RemoteContext context) {
        mOutX = Float.isNaN(mX) ? context.getFloat(Utils.idFromNan(mX)) : mX;
        mOutY = Float.isNaN(mY) ? context.getFloat(Utils.idFromNan(mY)) : mY;
    }

    @Override
    public void registerListening(@NonNull RemoteContext context) {
        if (Float.isNaN(mX)) {
            context.listensTo(Utils.idFromNan(mX), this);
        }
        if (Float.isNaN(mY)) {
            context.listensTo(Utils.idFromNan(mY), this);
        }
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mTextID, mStart, mEnd, mContextStart, mContextEnd, mX, mY, mRtl);
    }

    @NonNull
    @Override
    public String toString() {
        return "DrawTextRun ["
                + mTextID
                + "] "
                + mStart
                + ", "
                + mEnd
                + ", "
                + floatToString(mX, mOutX)
                + ", "
                + floatToString(mY, mOutY);
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        int text = buffer.readInt();
        int start = buffer.readInt();
        int end = buffer.readInt();
        int contextStart = buffer.readInt();
        int contextEnd = buffer.readInt();
        float x = buffer.readFloat();
        float y = buffer.readFloat();
        boolean rtl = buffer.readBoolean();
        DrawText op = new DrawText(text, start, end, contextStart, contextEnd, x, y, rtl);

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
     * @param buffer write the command to the buffer
     * @param textID id of the text
     * @param start Start position
     * @param end end position
     * @param contextStart start of the context
     * @param contextEnd end of the context
     * @param x position of where to draw
     * @param y position of where to draw
     * @param rtl is it Right to Left text
     */
    public static void apply(
            @NonNull WireBuffer buffer,
            int textID,
            int start,
            int end,
            int contextStart,
            int contextEnd,
            float x,
            float y,
            boolean rtl) {
        buffer.start(Operations.DRAW_TEXT_RUN);
        buffer.writeInt(textID);
        buffer.writeInt(start);
        buffer.writeInt(end);
        buffer.writeInt(contextStart);
        buffer.writeInt(contextEnd);
        buffer.writeFloat(x);
        buffer.writeFloat(y);
        buffer.writeBoolean(rtl);
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Draw Operations", id(), CLASS_NAME)
                .description("Draw a run of text, all in a single direction")
                .field(DocumentedOperation.INT, "textId", "id of bitmap")
                .field(
                        DocumentedOperation.INT,
                        "start",
                        "The start of the text to render. -1=end of string")
                .field(DocumentedOperation.INT, "end", "The end of the text to render")
                .field(
                        DocumentedOperation.INT,
                        "contextStart",
                        "the index of the start of the shaping context")
                .field(
                        DocumentedOperation.INT,
                        "contextEnd",
                        "the index of the end of the shaping context")
                .field(DocumentedOperation.FLOAT, "x", "The x position at which to draw the text")
                .field(DocumentedOperation.FLOAT, "y", "The y position at which to draw the text")
                .field(DocumentedOperation.BOOLEAN, "RTL", "Whether the run is in RTL direction");
    }

    @Override
    public void paint(@NonNull PaintContext context) {
        context.drawTextRun(mTextID, mStart, mEnd, mContextStart, mContextEnd, mOutX, mOutY, mRtl);
    }
}
