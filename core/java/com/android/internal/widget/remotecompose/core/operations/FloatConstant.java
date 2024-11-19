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

import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.FLOAT;

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation;

import java.util.List;

/** Operation to deal with Text data */
public class FloatConstant implements com.android.internal.widget.remotecompose.core.Operation {
    private static final int OP_CODE = Operations.DATA_FLOAT;
    private static final String CLASS_NAME = "FloatConstant";
    public int mTextId;
    public float mValue;

    public FloatConstant(int textId, float value) {
        this.mTextId = textId;
        this.mValue = value;
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mTextId, mValue);
    }

    @NonNull
    @Override
    public String toString() {
        return "FloatConstant[" + mTextId + "] = " + mValue;
    }

    @NonNull
    public static String name() {
        return CLASS_NAME;
    }

    public static int id() {
        return OP_CODE;
    }

    /**
     * Writes out the operation to the buffer
     *
     * @param buffer write command to this buffer
     * @param id the id
     * @param value the value of the float
     */
    public static void apply(@NonNull WireBuffer buffer, int id, float value) {
        buffer.start(OP_CODE);
        buffer.writeInt(id);
        buffer.writeFloat(value);
    }

    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        int textId = buffer.readInt();

        float value = buffer.readFloat();
        operations.add(new FloatConstant(textId, value));
    }

    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Expressions Operations", OP_CODE, CLASS_NAME)
                .description("A float and its associated id")
                .field(DocumentedOperation.INT, "id", "id of float")
                .field(FLOAT, "value", "32-bit float value");
    }

    @Override
    public void apply(@NonNull RemoteContext context) {
        context.loadFloat(mTextId, mValue);
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return indent + toString();
    }
}
