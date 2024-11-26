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

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;

import java.util.List;

/** Operation to measure the length of the text */
public class TextLength extends Operation {
    private static final int OP_CODE = Operations.TEXT_LENGTH;
    private static final String CLASS_NAME = "TextLength";
    public int mLengthId;
    public int mTextId;

    public TextLength(int lengthId, int textId) {
        this.mLengthId = lengthId;
        this.mTextId = textId;
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mLengthId, mTextId);
    }

    @NonNull
    @Override
    public String toString() {
        return CLASS_NAME + "[" + mLengthId + "] = " + mTextId;
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
     * @param lengthId the id to output
     * @param textId the id of the text to measure
     */
    public static void apply(@NonNull WireBuffer buffer, int lengthId, int textId) {
        buffer.start(OP_CODE);
        buffer.writeInt(lengthId);
        buffer.writeInt(textId);
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        int lengthId = buffer.readInt();
        int textId = buffer.readInt();
        operations.add(new TextLength(lengthId, textId));
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Expressions Operations", OP_CODE, CLASS_NAME)
                .description("get the length of the text and store in float table")
                .field(INT, "id", "id of float length")
                .field(INT, "value", "index of text");
    }

    @Override
    public void apply(@NonNull RemoteContext context) {
        context.loadFloat(mLengthId, context.getText(mTextId).length());
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return indent + toString();
    }
}
