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

import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.UTF8;

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.SerializableToString;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;

import java.util.List;

/** Operation to deal with Text data */
public class TextData extends Operation implements SerializableToString {
    private static final int OP_CODE = Operations.DATA_TEXT;
    private static final String CLASS_NAME = "TextData";
    public final int mTextId;
    @NonNull public final String mText;
    public static final int MAX_STRING_SIZE = 4000;

    public TextData(int textId, @NonNull String text) {
        this.mTextId = textId;
        this.mText = text;
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mTextId, mText);
    }

    @NonNull
    @Override
    public String toString() {
        return "TextData[" + mTextId + "] = \"" + Utils.trimString(mText, 10) + "\"";
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

    public static void apply(@NonNull WireBuffer buffer, int textId, @NonNull String text) {
        buffer.start(OP_CODE);
        buffer.writeInt(textId);
        buffer.writeUTF8(text);
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        int textId = buffer.readInt();

        String text = buffer.readUTF8(MAX_STRING_SIZE);
        operations.add(new TextData(textId, text));
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Data Operations", OP_CODE, CLASS_NAME)
                .description("Encode a string ")
                .field(DocumentedOperation.INT, "id", "id string")
                .field(UTF8, "text", "encode text as a string");
    }

    @Override
    public void apply(@NonNull RemoteContext context) {
        context.loadText(mTextId, mText);
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return indent + toString();
    }

    @Override
    public void serializeToString(int indent, @NonNull StringSerializer serializer) {
        serializer.append(indent, getSerializedName() + "<" + mTextId + "> = \"" + mText + "\"");
    }

    @NonNull
    private String getSerializedName() {
        return "DATA_TEXT";
    }
}
