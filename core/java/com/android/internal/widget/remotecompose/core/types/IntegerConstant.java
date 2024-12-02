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

import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.INT;

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation;

import java.util.List;

/** Represents a single integer typically used for states or named for input into the system */
public class IntegerConstant extends Operation {
    private int mValue = 0;
    private int mId;

    IntegerConstant(int id, int value) {
        mId = id;
        mValue = value;
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mId, mValue);
    }

    @Override
    public void apply(@NonNull RemoteContext context) {
        context.loadInteger(mId, mValue);
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return toString();
    }

    @NonNull
    @Override
    public String toString() {
        return "IntegerConstant[" + mId + "] = " + mValue + "";
    }

    /**
     * The name of the class
     *
     * @return the name
     */
    @NonNull
    public static String name() {
        return "IntegerConstant";
    }

    /**
     * The OP_CODE for this command
     *
     * @return the opcode
     */
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
    public static void apply(@NonNull WireBuffer buffer, int textId, int value) {
        buffer.start(Operations.DATA_INT);
        buffer.writeInt(textId);
        buffer.writeInt(value);
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        int id = buffer.readInt();

        int value = buffer.readInt();
        operations.add(new IntegerConstant(id, value));
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Expressions Operations", id(), "IntegerConstant")
                .description("A integer and its associated id")
                .field(DocumentedOperation.INT, "id", "id of Int")
                .field(INT, "value", "32-bit int value");
    }
}
