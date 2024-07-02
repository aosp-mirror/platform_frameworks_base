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

import com.android.internal.widget.remotecompose.core.CompanionOperation;
import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;

import java.util.List;

/**
 * Operation to deal with Text data
 */
public class NamedVariable implements Operation {
    public int mVarId;
    public String mVarName;
    public int mVarType;
    public static final Companion COMPANION = new Companion();
    public static final int MAX_STRING_SIZE = 4000;

    public NamedVariable(int varId, int varType, String name) {
        this.mVarId = varId;
        this.mVarType = varType;
        this.mVarName = name;
    }

    @Override
    public void write(WireBuffer buffer) {
        COMPANION.apply(buffer, mVarId, mVarType, mVarName);
    }

    @Override
    public String toString() {
        return "VariableName[" + mVarId + "] = \""
                + Utils.trimString(mVarName, 10) + "\" type=" + mVarType;
    }

    public static class Companion implements CompanionOperation {
        private Companion() {
        }

        @Override
        public String name() {
            return "TextData";
        }

        @Override
        public int id() {
            return Operations.DATA_TEXT;
        }

        /**
         * Writes out the operation to the buffer
         * @param buffer
         * @param varId
         * @param varType
         * @param text
         */
        public void apply(WireBuffer buffer, int varId, int varType, String text) {
            buffer.start(Operations.DATA_TEXT);
            buffer.writeInt(varId);
            buffer.writeInt(varType);
            buffer.writeUTF8(text);
        }

        @Override
        public void read(WireBuffer buffer, List<Operation> operations) {
            int varId = buffer.readInt();
            int varType = buffer.readInt();
            String text = buffer.readUTF8(MAX_STRING_SIZE);
            operations.add(new NamedVariable(varId, varType, text));
        }
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
