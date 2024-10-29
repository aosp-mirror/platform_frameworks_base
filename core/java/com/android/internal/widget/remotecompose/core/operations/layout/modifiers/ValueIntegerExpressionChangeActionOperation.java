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

import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.INT;

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

/** Apply a value change on an integer variable. */
public class ValueIntegerExpressionChangeActionOperation implements ActionOperation {
    private static final int OP_CODE = Operations.VALUE_INTEGER_EXPRESSION_CHANGE_ACTION;

    long mTargetValueId = -1;
    long mValueExpressionId = -1;

    public ValueIntegerExpressionChangeActionOperation(long id, long value) {
        mTargetValueId = id;
        mValueExpressionId = value;
    }

    @Override
    public String toString() {
        return "ValueIntegerExpressionChangeActionOperation(" + mTargetValueId + ")";
    }

    public String serializedName() {
        return "VALUE_INTEGER_EXPRESSION_CHANGE";
    }

    @Override
    public void serializeToString(int indent, StringSerializer serializer) {
        serializer.append(
                indent, serializedName() + " = " + mTargetValueId + " -> " + mValueExpressionId);
    }

    @Override
    public void apply(RemoteContext context) {}

    @Override
    public String deepToString(String indent) {
        return (indent != null ? indent : "") + toString();
    }

    @Override
    public void write(WireBuffer buffer) {}

    @Override
    public void runAction(
            RemoteContext context, CoreDocument document, Component component, float x, float y) {
        document.evaluateIntExpression(mValueExpressionId, (int) mTargetValueId, context);
    }

    public static void apply(WireBuffer buffer, long valueId, long value) {
        buffer.start(OP_CODE);
        buffer.writeLong(valueId);
        buffer.writeLong(value);
    }

    public static void read(WireBuffer buffer, List<Operation> operations) {
        long valueId = buffer.readLong();
        long value = buffer.readLong();
        operations.add(new ValueIntegerExpressionChangeActionOperation(valueId, value));
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Layout Operations", OP_CODE, "ValueIntegerExpressionChangeActionOperation")
                .description(
                        "ValueIntegerExpressionChange action. "
                                + " This operation represents a value change for the given id")
                .field(INT, "TARGET_VALUE_ID", "Value ID")
                .field(INT, "VALUE_ID", "id of the value to be assigned to the target");
    }
}
