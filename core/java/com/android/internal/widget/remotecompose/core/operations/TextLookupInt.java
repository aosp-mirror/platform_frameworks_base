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

import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.INT;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation;

import java.util.List;

/** Operation convert int index of a list to text */
public class TextLookupInt implements Operation, VariableSupport {
    private static final int OP_CODE = Operations.TEXT_LOOKUP_INT;
    private static final String CLASS_NAME = "TextFromINT";
    public int mTextId;
    public int mDataSetId;
    public int mOutIndex;
    public int mIndex;

    public static final int MAX_STRING_SIZE = 4000;

    public TextLookupInt(int textId, int dataSetId, int indexId) {
        this.mTextId = textId;
        this.mDataSetId = dataSetId;
        this.mOutIndex = this.mIndex = indexId;
    }

    @Override
    public void write(WireBuffer buffer) {
        apply(buffer, mTextId, mDataSetId, mIndex);
    }

    @Override
    public String toString() {
        return "TextLookupInt["
                + Utils.idString(mTextId)
                + "] = "
                + Utils.idString(mDataSetId)
                + " "
                + mIndex;
    }

    @Override
    public void updateVariables(RemoteContext context) {
        mOutIndex = context.getInteger(mIndex);
    }

    @Override
    public void registerListening(RemoteContext context) {
        context.listensTo(mIndex, this);
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
     * @param indexId index of element to return
     */
    public static void apply(WireBuffer buffer, int textId, int dataSet, int indexId) {
        buffer.start(OP_CODE);
        buffer.writeInt(textId);
        buffer.writeInt(dataSet);
        buffer.writeInt(indexId);
    }

    public static void read(WireBuffer buffer, List<Operation> operations) {
        int textId = buffer.readInt();
        int dataSetId = buffer.readInt();
        int indexId = buffer.readInt();
        operations.add(new TextLookupInt(textId, dataSetId, indexId));
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Expressions Operations", OP_CODE, CLASS_NAME)
                .description("Look up an array and turn into a text object")
                .field(DocumentedOperation.INT, "textId", "id of the text generated")
                .field(INT, "dataSetId", "id to the array/list to turn int a string")
                .field(INT, "index", "index of the element to return");
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
