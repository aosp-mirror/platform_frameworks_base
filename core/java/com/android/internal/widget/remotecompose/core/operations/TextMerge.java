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

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation;

import java.util.List;

/** Operation to deal with Text data */
public class TextMerge implements Operation {
    private static final int OP_CODE = Operations.TEXT_MERGE;
    private static final String CLASS_NAME = "TextMerge";
    public int mTextId;
    public int mSrcId1;
    public int mSrcId2;

    public TextMerge(int textId, int srcId1, int srcId2) {
        this.mTextId = textId;
        this.mSrcId1 = srcId1;
        this.mSrcId2 = srcId2;
    }

    @Override
    public void write(WireBuffer buffer) {
        apply(buffer, mTextId, mSrcId1, mSrcId2);
    }

    @Override
    public String toString() {
        return "TextMerge[" + mTextId + "] = [" + mSrcId1 + " ] + [ " + mSrcId2 + "]";
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
     * @param textId id of the text
     * @param srcId1 source text 1
     * @param srcId2 source text 2
     */
    public static void apply(WireBuffer buffer, int textId, int srcId1, int srcId2) {
        buffer.start(OP_CODE);
        buffer.writeInt(textId);
        buffer.writeInt(srcId1);
        buffer.writeInt(srcId2);
    }

    public static void read(WireBuffer buffer, List<Operation> operations) {
        int textId = buffer.readInt();
        int srcId1 = buffer.readInt();
        int srcId2 = buffer.readInt();

        operations.add(new TextMerge(textId, srcId1, srcId2));
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Data Operations", OP_CODE, CLASS_NAME)
                .description("Merge two string into one")
                .field(DocumentedOperation.INT, "textId", "id of the text")
                .field(INT, "srcTextId1", "id of the path")
                .field(INT, "srcTextId1", "x Shift of the text");
    }

    @Override
    public void apply(RemoteContext context) {
        String str1 = context.getText(mSrcId1);
        String str2 = context.getText(mSrcId2);
        context.loadText(mTextId, str1 + str2);
    }

    @Override
    public String deepToString(String indent) {
        return indent + toString();
    }
}
