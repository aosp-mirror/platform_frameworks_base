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
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;

import java.util.List;

/**
 * Operation convert floats to text This command is structured
 * [command][textID][before,after][flags] before and after define number of digits before and after
 * the decimal point
 */
public class TextLookup extends Operation implements VariableSupport {
    private static final int OP_CODE = Operations.TEXT_LOOKUP;
    private static final String CLASS_NAME = "TextFromFloat";
    public int mTextId;
    public int mDataSetId;
    public float mOutIndex, mIndex;

    public static final int MAX_STRING_SIZE = 4000;

    public TextLookup(int textId, int dataSetId, float index) {
        this.mTextId = textId;
        this.mDataSetId = dataSetId;
        this.mOutIndex = this.mIndex = index;
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mTextId, mDataSetId, mIndex);
    }

    @NonNull
    @Override
    public String toString() {
        return "TextLookup["
                + Utils.idString(mTextId)
                + "] = "
                + Utils.idString(mDataSetId)
                + " "
                + Utils.floatToString(mIndex);
    }

    @Override
    public void updateVariables(@NonNull RemoteContext context) {
        if (Float.isNaN(mIndex)) {
            mOutIndex = context.getFloat(Utils.idFromNan(mIndex));
        }
    }

    @Override
    public void registerListening(@NonNull RemoteContext context) {
        if (Float.isNaN(mIndex)) {
            context.listensTo(Utils.idFromNan(mIndex), this);
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
     * @param dataSet float pointer to the array/list to turn int a string
     * @param index index of element to return
     */
    public static void apply(@NonNull WireBuffer buffer, int textId, int dataSet, float index) {
        buffer.start(OP_CODE);
        buffer.writeInt(textId);
        buffer.writeInt(dataSet);
        buffer.writeFloat(index);
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        int textId = buffer.readInt();
        int dataSetId = buffer.readInt();
        float index = buffer.readFloat();
        operations.add(new TextLookup(textId, dataSetId, index));
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Expressions Operations", OP_CODE, CLASS_NAME)
                .description("Look an array and turn into a text object")
                .field(INT, "textId", "id of the text generated")
                .field(FLOAT, "dataSet", "float pointer to the array/list to turn int a string")
                .field(FLOAT, "index", "index of element to return");
    }

    @Override
    public void apply(@NonNull RemoteContext context) {
        int id = context.getCollectionsAccess().getId(mDataSetId, (int) mOutIndex);
        context.loadText(mTextId, context.getText(id));
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return indent + toString();
    }
}
