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
public class TextLookup implements Operation, VariableSupport {
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
    public void write(WireBuffer buffer) {
        apply(buffer, mTextId, mDataSetId, mIndex);
    }

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
    public void updateVariables(RemoteContext context) {
        if (Float.isNaN(mIndex)) {
            mOutIndex = context.getFloat(Utils.idFromNan(mIndex));
        }
    }

    @Override
    public void registerListening(RemoteContext context) {
        if (Float.isNaN(mIndex)) {
            context.listensTo(Utils.idFromNan(mIndex), this);
        }
    }

    public static String name() {
        return CLASS_NAME;
    }

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
    public static void apply(WireBuffer buffer, int textId, int dataSet, float index) {
        buffer.start(OP_CODE);
        buffer.writeInt(textId);
        buffer.writeInt(dataSet);
        buffer.writeFloat(index);
    }

    public static void read(WireBuffer buffer, List<Operation> operations) {
        int textId = buffer.readInt();
        int dataSetId = buffer.readInt();
        float index = buffer.readFloat();
        operations.add(new TextLookup(textId, dataSetId, index));
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Expressions Operations", OP_CODE, CLASS_NAME)
                .description("Look an array and turn into a text object")
                .field(INT, "textId", "id of the text generated")
                .field(FLOAT, "dataSet", "float pointer to the array/list to turn int a string")
                .field(FLOAT, "index", "index of element to return");
    }

    @Override
    public void apply(RemoteContext context) {
        int id = context.getCollectionsAccess().getId(mDataSetId, (int) mOutIndex);
        context.loadText(mTextId, context.getText(id));
    }

    @Override
    public String deepToString(String indent) {
        return indent + toString();
    }
}
