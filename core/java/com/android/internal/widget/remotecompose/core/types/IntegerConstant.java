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
package com.android.internal.widget.remotecompose.core.types;

import static com.android.internal.widget.remotecompose.core.documentation.Operation.INT;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;

import java.util.List;

/**
 * Represents a single integer typically used for states
 * or named for input into the system
 */
public class IntegerConstant implements Operation {
    private int mValue = 0;
    private int mId;

    IntegerConstant(int id, int value) {
        mId = id;
        mValue = value;
    }

    @Override
    public void write(WireBuffer buffer) {
        apply(buffer, mId, mValue);
    }

    @Override
    public void apply(RemoteContext context) {
        context.loadInteger(mId, mValue);
    }

    @Override
    public String deepToString(String indent) {
        return toString();
    }

    @Override
    public String toString() {
        return "IntegerConstant[" + mId + "] = " + mValue + "";
    }

    public static String name() {
        return "IntegerConstant";
    }

    public static int id() {
        return Operations.DATA_INT;
    }

    /**
     * Writes out the operation to the buffer
     *
     * @param buffer
     * @param textId
     * @param value
     */
    public static void apply(WireBuffer buffer, int textId, int value) {
        buffer.start(Operations.DATA_INT);
        buffer.writeInt(textId);
        buffer.writeInt(value);
    }

    public static void read(WireBuffer buffer, List<Operation> operations) {
        int id = buffer.readInt();

        int value = buffer.readInt();
        operations.add(new IntegerConstant(id, value));
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Expressions Operations",
                        id(),
                        "IntegerConstant")
                .description("A integer and its associated id")
                .field(INT, "id", "id of Int")
                .field(INT, "value",
                        "32-bit int value");

    }
}
