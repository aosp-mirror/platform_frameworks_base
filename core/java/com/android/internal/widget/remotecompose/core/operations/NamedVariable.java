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

import static com.android.internal.widget.remotecompose.core.documentation.Operation.INT;
import static com.android.internal.widget.remotecompose.core.documentation.Operation.UTF8;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;

import java.util.List;

/**
 * Operation to deal with Text data
 */
public class NamedVariable implements Operation {
    private static final int OP_CODE = Operations.NAMED_VARIABLE;
    private static final String CLASS_NAME = "NamedVariable";
    public int mVarId;
    public String mVarName;
    public int mVarType;
    public static final int MAX_STRING_SIZE = 4000;
    public static final int COLOR_TYPE = 2;
    public static final int FLOAT_TYPE = 1;
    public static final int STRING_TYPE = 0;

    public NamedVariable(int varId, int varType, String name) {
        this.mVarId = varId;
        this.mVarType = varType;
        this.mVarName = name;
    }

    @Override
    public void write(WireBuffer buffer) {
        apply(buffer, mVarId, mVarType, mVarName);
    }

    @Override
    public String toString() {
        return "VariableName[" + mVarId + "] = \""
                + Utils.trimString(mVarName, 10) + "\" type=" + mVarType;
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
     * @param buffer The buffer to write into
     * @param varId id to label
     * @param varType The type of variable
     * @param text String
     */
    public static void apply(WireBuffer buffer, int varId, int varType, String text) {
        buffer.start(Operations.NAMED_VARIABLE);
        buffer.writeInt(varId);
        buffer.writeInt(varType);
        buffer.writeUTF8(text);
    }

    public static  void read(WireBuffer buffer, List<Operation> operations) {
        int varId = buffer.readInt();
        int varType = buffer.readInt();
        String text = buffer.readUTF8(MAX_STRING_SIZE);
        operations.add(new NamedVariable(varId, varType, text));
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Data Operations",
                        OP_CODE,
                        CLASS_NAME)
                .description("Add a string name for an ID")
                .field(INT, "varId", "id to label")
                .field(INT, "varType", "The type of variable")
                .field(UTF8, "name", "String");
    }

    @Override
    public void apply(RemoteContext context) {
        context.loadVariableName(mVarName, mVarId, mVarType);
    }

    @Override
    public String deepToString(String indent) {
        return indent + toString();
    }
}
