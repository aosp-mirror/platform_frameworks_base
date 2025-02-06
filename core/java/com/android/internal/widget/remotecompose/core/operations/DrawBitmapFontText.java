/*
 * Copyright (C) 2025 The Android Open Source Project
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
public class DrawBitmapFontText extends PaintOperation implements VariableSupport {
    private static final int OP_CODE = Operations.DRAW_BITMAP_FONT_TEXT_RUN;
    private static final String CLASS_NAME = "DrawBitmapFontText";
    int mTextID;
    int mBitmapFontID;
    int mStart;
    int mEnd;
    float mX;
    float mY;
    float mOutX;
    float mOutY;

    public DrawBitmapFontText(int textID, int bitmapFontID, int start, int end, float x, float y) {
        mTextID = textID;
        mBitmapFontID = bitmapFontID;
        mStart = start;
        mEnd = end;
        mOutX = mX = x;
        mOutY = mY = y;
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
        apply(buffer, mTextID, mBitmapFontID, mStart, mEnd, mX, mY);
    }

    @NonNull
    @Override
    public String toString() {
        return "DrawBitmapFontText ["
                + mTextID
                + "] "
                + mBitmapFontID
                + ", "
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
        int bitmapFont = buffer.readInt();
        int start = buffer.readInt();
        int end = buffer.readInt();
        float x = buffer.readFloat();
        float y = buffer.readFloat();
        DrawBitmapFontText op = new DrawBitmapFontText(text, bitmapFont, start, end, x, y);

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
     * @param bitmapFontID id of the bitmap font
     * @param start Start position
     * @param end end position
     * @param x position of where to draw
     * @param y position of where to draw
     */
    public static void apply(
            @NonNull WireBuffer buffer,
            int textID,
            int bitmapFontID,
            int start,
            int end,
            float x,
            float y) {
        buffer.start(Operations.DRAW_BITMAP_FONT_TEXT_RUN);
        buffer.writeInt(textID);
        buffer.writeInt(bitmapFontID);
        buffer.writeInt(start);
        buffer.writeInt(end);
        buffer.writeFloat(x);
        buffer.writeFloat(y);
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Draw Operations", id(), CLASS_NAME)
                .description("Draw a run of bitmap font text, all in a single direction")
                .field(DocumentedOperation.INT, "textId", "id of bitmap")
                .field(DocumentedOperation.INT, "bitmapFontId", "id of the bitmap font")
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
        RemoteContext remoteContext = context.getContext();
        String textToPaint = remoteContext.getText(mTextID);
        if (textToPaint == null) {
            return;
        }
        if (mEnd == -1) {
            if (mStart != 0) {
                textToPaint = textToPaint.substring(mStart);
            }
        } else if (mEnd > textToPaint.length()) {
            textToPaint = textToPaint.substring(mStart);
        } else {
            textToPaint = textToPaint.substring(mStart, mEnd);
        }

        BitmapFontData bitmapFont = (BitmapFontData) remoteContext.getObject(mBitmapFontID);
        if (bitmapFont == null) {
            return;
        }

        float xPos = mX;
        int pos = 0;
        while (pos < textToPaint.length()) {
            BitmapFontData.Glyph glyph = bitmapFont.lookupGlyph(textToPaint, pos);
            if (glyph == null) {
                pos++;
                continue;
            }

            pos += glyph.mChars.length();
            if (glyph.mBitmapId == -1) {
                // Space is represented by a glyph of -1.
                xPos += glyph.mMarginLeft + glyph.mMarginRight;
                continue;
            }

            xPos += glyph.mMarginLeft;
            float xPos2 = xPos + glyph.mBitmapWidth;
            context.drawBitmap(
                    glyph.mBitmapId, xPos, mY + glyph.mMarginTop, xPos2, mY + glyph.mBitmapHeight);
            xPos = xPos2 + glyph.mMarginRight;
        }
    }
}
