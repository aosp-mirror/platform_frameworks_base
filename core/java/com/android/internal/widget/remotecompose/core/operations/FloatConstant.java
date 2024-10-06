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

import static com.android.internal.widget.remotecompose.core.documentation.Operation.FLOAT;
import static com.android.internal.widget.remotecompose.core.documentation.Operation.INT;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;

import java.util.List;

/**
 * Operation to deal with Text data
 */
public class FloatConstant implements Operation {
    private static final int OP_CODE = Operations.DATA_FLOAT;
    private static final String CLASS_NAME = "FloatConstant";
    public int mTextId;
    public float mValue;

    public FloatConstant(int textId, float value) {
        this.mTextId = textId;
        this.mValue = value;
    }

    @Override
    public void write(WireBuffer buffer) {
        apply(buffer, mTextId, mValue);
    }

    @Override
    public String toString() {
        return "FloatConstant[" + mTextId + "] = " + mValue;
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
     * @param buffer write command to this buffer
     * @param id     the id
     * @param value  the value of the float
     */
    public static void apply(WireBuffer buffer, int id, float value) {
        buffer.start(OP_CODE);
        buffer.writeInt(id);
        buffer.writeFloat(value);
    }

    public static void read(WireBuffer buffer, List<Operation> operations) {
        int textId = buffer.readInt();

        float value = buffer.readFloat();
        operations.add(new FloatConstant(textId, value));
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Expressions Operations",
                        OP_CODE,
                        CLASS_NAME)
                .description("A float and its associated id")
                .field(INT, "id", "id of float")
                .field(FLOAT, "value",
                        "32-bit float value");

    }

    @Override
    public void apply(RemoteContext context) {
        context.loadFloat(mTextId, mValue);
    }

    @Override
    public String deepToString(String indent) {
        return indent + toString();
    }
}
