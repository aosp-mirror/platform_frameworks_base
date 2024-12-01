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
package com.android.internal.widget.remotecompose.core.operations;

import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.INT;
import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.SHORT;

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringUtils;

import java.util.List;

/**
 * Operation convert floats to text This command is structured
 * [command][textID][before,after][flags] before and after define number of digits before and after
 * the decimal point
 */
public class TextFromFloat extends Operation implements VariableSupport {
    private static final int OP_CODE = Operations.TEXT_FROM_FLOAT;
    private static final String CLASS_NAME = "TextFromFloat";
    public int mTextId;
    public float mValue;
    public float mOutValue;
    public short mDigitsBefore;
    public short mDigitsAfter;
    public int mFlags;
    public static final int MAX_STRING_SIZE = 4000;
    char mPre = ' ';
    char mAfter = ' ';
    // Theses flags define what how to/if  fill the space
    public static final int PAD_AFTER_SPACE = 0; // pad past point with space
    public static final int PAD_AFTER_NONE = 1; // do not pad past last digit
    public static final int PAD_AFTER_ZERO = 3; // pad with 0 past last digit
    public static final int PAD_PRE_SPACE = 0; // pad before number with spaces
    public static final int PAD_PRE_NONE = 4; // pad before number with 0s
    public static final int PAD_PRE_ZERO = 12; // do not pad before number

    public TextFromFloat(
            int textId, float value, short digitsBefore, short digitsAfter, int flags) {
        this.mTextId = textId;
        this.mValue = value;
        this.mDigitsAfter = digitsAfter;
        this.mDigitsBefore = digitsBefore;
        this.mFlags = flags;
        mOutValue = mValue;
        switch (mFlags & 3) {
            case PAD_AFTER_SPACE:
                mAfter = ' ';
                break;
            case PAD_AFTER_NONE:
                mAfter = 0;
                break;
            case PAD_AFTER_ZERO:
                mAfter = '0';
                break;
        }
        switch (mFlags & 12) {
            case PAD_PRE_SPACE:
                mPre = ' ';
                break;
            case PAD_PRE_NONE:
                mPre = 0;
                break;
            case PAD_PRE_ZERO:
                mPre = '0';
                break;
        }
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mTextId, mValue, mDigitsBefore, mDigitsAfter, mFlags);
    }

    @NonNull
    @Override
    public String toString() {
        return "TextFromFloat["
                + mTextId
                + "] = "
                + Utils.floatToString(mValue)
                + " "
                + mDigitsBefore
                + "."
                + mDigitsAfter
                + " "
                + mFlags;
    }

    @Override
    public void updateVariables(@NonNull RemoteContext context) {
        if (Float.isNaN(mValue)) {
            mOutValue = context.getFloat(Utils.idFromNan(mValue));
        }
    }

    @Override
    public void registerListening(@NonNull RemoteContext context) {
        if (Float.isNaN(mValue)) {
            context.listensTo(Utils.idFromNan(mValue), this);
        }
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
     * @param buffer buffer to write to
     * @param textId the id of the output text
     * @param value the float value to be turned into strings
     * @param digitsBefore the digits before the decimal point
     * @param digitsAfter the digits after the decimal point
     * @param flags flags that control if and how to fill the empty spots
     */
    public static void apply(
            @NonNull WireBuffer buffer,
            int textId,
            float value,
            short digitsBefore,
            short digitsAfter,
            int flags) {
        buffer.start(OP_CODE);
        buffer.writeInt(textId);
        buffer.writeFloat(value);
        buffer.writeInt((digitsBefore << 16) | digitsAfter);
        buffer.writeInt(flags);
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        int textId = buffer.readInt();
        float value = buffer.readFloat();
        int tmp = buffer.readInt();
        short post = (short) (tmp & 0xFFFF);
        short pre = (short) ((tmp >> 16) & 0xFFFF);

        int flags = buffer.readInt();
        operations.add(new TextFromFloat(textId, value, pre, post, flags));
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Expressions Operations", OP_CODE, CLASS_NAME)
                .description("Draw text along path object")
                .field(DocumentedOperation.INT, "textId", "id of the text generated")
                .field(INT, "value", "Value to add")
                .field(SHORT, "prePoint", "digits before the decimal point")
                .field(SHORT, "pstPoint", "digit after the decimal point")
                .field(INT, "flags", "options on padding");
    }

    @Override
    public void apply(@NonNull RemoteContext context) {
        float v = mOutValue;
        String s = StringUtils.floatToString(v, mDigitsBefore, mDigitsAfter, mPre, mAfter);
        context.loadText(mTextId, s);
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return indent + toString();
    }
}
