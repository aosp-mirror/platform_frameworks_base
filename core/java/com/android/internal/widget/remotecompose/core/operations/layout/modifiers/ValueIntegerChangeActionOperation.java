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
package com.android.internal.widget.remotecompose.core.operations.layout.modifiers;

import static com.android.internal.widget.remotecompose.core.documentation.Operation.INT;

import com.android.internal.widget.remotecompose.core.CoreDocument;
import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.operations.layout.ActionOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.Component;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;

import java.util.List;

/**
 * Apply a value change on an integer variable.
 */
public class ValueIntegerChangeActionOperation implements ActionOperation {
    private static final int OP_CODE = Operations.VALUE_INTEGER_CHANGE_ACTION;

    int mTargetValueId = -1;
    int mValue = -1;

    public ValueIntegerChangeActionOperation(int id, int value) {
        mTargetValueId = id;
        mValue = value;
    }

    @Override
    public String toString() {
        return "ValueChangeActionOperation(" + mTargetValueId + ")";
    }

    public String serializedName() {
        return "VALUE_INTEGER_CHANGE";
    }

    @Override
    public void serializeToString(int indent, StringSerializer serializer) {
        serializer.append(indent, serializedName()
                + " = " + mTargetValueId + " -> " + mValue);
    }

    @Override
    public void apply(RemoteContext context) {
    }

    @Override
    public String deepToString(String indent) {
        return (indent != null ? indent : "") + toString();
    }


    @Override
    public void write(WireBuffer buffer) {

    }

    @Override
    public void runAction(RemoteContext context, CoreDocument document,
                          Component component, float x, float y) {
        context.overrideInteger(mTargetValueId, mValue);
    }

    public static void apply(WireBuffer buffer, int valueId, int value) {
        buffer.start(OP_CODE);
        buffer.writeInt(valueId);
        buffer.writeInt(value);
    }

    public static void read(WireBuffer buffer, List<Operation> operations) {
        int valueId = buffer.readInt();
        int value = buffer.readInt();
        operations.add(new ValueIntegerChangeActionOperation(valueId, value));
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Layout Operations", OP_CODE, "ValueIntegerChangeActionOperation")
                .description("ValueIntegerChange action. "
                        + " This operation represents a value change for the given id")
                .field(INT, "TARGET_VALUE_ID", "Value ID")
                .field(INT, "VALUE", "integer value to be assigned to the target")
        ;
    }

}
