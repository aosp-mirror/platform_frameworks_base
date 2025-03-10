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

import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.LONG;

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation;

import java.util.List;

/** Used to represent a long */
public class LongConstant extends Operation {
    private static final int OP_CODE = Operations.DATA_LONG;
    private long mValue;
    private int mId;

    public LongConstant(int id, long value) {
        mId = id;
        mValue = value;
    }

    /**
     * Get the value of the long constant
     *
     * @return the value of the long
     */
    public long getValue() {
        return mValue;
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mId, mValue);
    }

    @Override
    public void apply(@NonNull RemoteContext context) {
        context.putObject(mId, this);
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return toString();
    }

    @NonNull
    @Override
    public String toString() {
        return "LongConstant[" + mId + "] = " + mValue + "";
    }

    /**
     * Writes out the operation to the buffer
     *
     * @param buffer
     * @param id
     * @param value
     */
    public static void apply(@NonNull WireBuffer buffer, int id, long value) {
        buffer.start(OP_CODE);
        buffer.writeInt(id);
        buffer.writeLong(value);
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        int id = buffer.readInt();

        long value = buffer.readLong();
        operations.add(new LongConstant(id, value));
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Expressions Operations", OP_CODE, "LongConstant")
                .description("A boolean and its associated id")
                .field(DocumentedOperation.INT, "id", "id of Int")
                .field(LONG, "value", "The long Value");
    }
}
