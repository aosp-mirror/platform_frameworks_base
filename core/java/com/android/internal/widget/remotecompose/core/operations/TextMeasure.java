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

import static com.android.internal.widget.remotecompose.core.PaintContext.TEXT_MEASURE_FONT_HEIGHT;
import static com.android.internal.widget.remotecompose.core.PaintContext.TEXT_MEASURE_MONOSPACE_WIDTH;
import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.INT;

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.PaintOperation;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;

import java.util.List;

/** Operation to Measure Text data */
public class TextMeasure extends PaintOperation {
    private static final int OP_CODE = Operations.TEXT_MEASURE;
    private static final String CLASS_NAME = "TextMeasure";
    public int mId;
    public int mTextId;
    public int mType;

    public static final int MEASURE_WIDTH = 0;
    public static final int MEASURE_HEIGHT = 1;
    public static final int MEASURE_LEFT = 2;
    public static final int MEASURE_RIGHT = 3;
    public static final int MEASURE_TOP = 4;
    public static final int MEASURE_BOTTOM = 5;

    /** a << 8 shifted {@link PaintContext#getTextBounds} */
    public static final int MEASURE_MONOSPACE_FLAG = TEXT_MEASURE_MONOSPACE_WIDTH << 8;

    public static final int MEASURE_MAX_HEIGHT_FLAG = TEXT_MEASURE_FONT_HEIGHT << 8;

    public TextMeasure(int id, int textId, int type) {
        this.mId = id;
        this.mTextId = textId;
        this.mType = type;
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mId, mTextId, mType);
    }

    @Override
    public @NonNull String toString() {
        return "FloatConstant[" + mId + "] = " + mTextId + " " + mType;
    }

    public static @NonNull String name() {
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
     * @param buffer write command to this buffer
     * @param id the id
     * @param textId the id
     * @param type the value of the float
     */
    public static void apply(@NonNull WireBuffer buffer, int id, int textId, int type) {
        buffer.start(OP_CODE);
        buffer.writeInt(id);
        buffer.writeInt(textId);
        buffer.writeInt(type);
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        int id = buffer.readInt();
        int textId = buffer.readInt();
        int type = buffer.readInt();
        operations.add(new TextMeasure(id, textId, type));
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Expressions Operations", OP_CODE, CLASS_NAME)
                .description("Measure text")
                .field(INT, "id", "id of float result of the measure")
                .field(INT, "textId", "id of text")
                .field(INT, "type", "type: measure 0=width,1=height");
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return indent + toString();
    }

    @NonNull float[] mBounds = new float[4];

    @Override
    public void paint(@NonNull PaintContext context) {
        int val = mType & 255;
        int flags = mType >> 8;
        context.getTextBounds(mTextId, 0, -1, flags, mBounds);
        switch (val) {
            case MEASURE_WIDTH:
                context.getContext().loadFloat(mId, mBounds[2] - mBounds[0]);
                break;
            case MEASURE_HEIGHT:
                context.getContext().loadFloat(mId, mBounds[3] - mBounds[1]);
                break;
            case MEASURE_LEFT:
                context.getContext().loadFloat(mId, mBounds[0]);
                break;
            case MEASURE_TOP:
                context.getContext().loadFloat(mId, mBounds[1]);
                break;
            case MEASURE_RIGHT:
                context.getContext().loadFloat(mId, mBounds[2]);

                break;
            case MEASURE_BOTTOM:
                context.getContext().loadFloat(mId, mBounds[3]);

                break;
        }
    }
}
