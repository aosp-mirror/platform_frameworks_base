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
import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.UTF8;

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation;

import java.util.List;

/** Operation to deal with Text data */
public class NamedVariable extends Operation {
    private static final int OP_CODE = Operations.NAMED_VARIABLE;
    private static final String CLASS_NAME = "NamedVariable";
    public final int mVarId;
    public final @NonNull String mVarName;
    public final int mVarType;
    public static final int MAX_STRING_SIZE = 4000;
    public static final int COLOR_TYPE = 2;
    public static final int FLOAT_TYPE = 1;
    public static final int STRING_TYPE = 0;
    public static final int IMAGE_TYPE = 3;

    public NamedVariable(int varId, int varType, @NonNull String name) {
        this.mVarId = varId;
        this.mVarType = varType;
        this.mVarName = name;
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mVarId, mVarType, mVarName);
    }

    @NonNull
    @Override
    public String toString() {
        return "VariableName["
                + mVarId
                + "] = \""
                + Utils.trimString(mVarName, 10)
                + "\" type="
                + mVarType;
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
     * @param buffer The buffer to write into
     * @param varId id to label
     * @param varType The type of variable
     * @param text String
     */
    public static void apply(
            @NonNull WireBuffer buffer, int varId, int varType, @NonNull String text) {
        buffer.start(Operations.NAMED_VARIABLE);
        buffer.writeInt(varId);
        buffer.writeInt(varType);
        buffer.writeUTF8(text);
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        int varId = buffer.readInt();
        int varType = buffer.readInt();
        String text = buffer.readUTF8(MAX_STRING_SIZE);
        operations.add(new NamedVariable(varId, varType, text));
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Data Operations", OP_CODE, CLASS_NAME)
                .description("Add a string name for an ID")
                .field(DocumentedOperation.INT, "varId", "id to label")
                .field(INT, "varType", "The type of variable")
                .field(UTF8, "name", "String");
    }

    @Override
    public void apply(@NonNull RemoteContext context) {
        context.loadVariableName(mVarName, mVarId, mVarType);
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return indent + toString();
    }
}
