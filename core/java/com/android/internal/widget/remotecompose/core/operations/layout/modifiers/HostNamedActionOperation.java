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

import android.annotation.NonNull;

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

/** Capture a host action information. This can be triggered on eg. a click. */
public class HostNamedActionOperation extends Operation implements ActionOperation {
    private static final int OP_CODE = Operations.HOST_NAMED_ACTION;

    public static final int FLOAT_TYPE = 0;
    public static final int INT_TYPE = 1;
    public static final int STRING_TYPE = 2;
    public static final int FLOAT_ARRAY_TYPE = 3;
    public static final int NONE_TYPE = -1;

    int mTextId = -1;
    int mType = NONE_TYPE;
    int mValueId = -1;

    public HostNamedActionOperation(int id, int type, int valueId) {
        mTextId = id;
        mType = type;
        mValueId = valueId;
    }

    @NonNull
    @Override
    public String toString() {
        return "HostNamedActionOperation(" + mTextId + " : " + mValueId + ")";
    }

    @NonNull
    public String serializedName() {
        return "HOST_NAMED_ACTION";
    }

    @Override
    public void serializeToString(int indent, @NonNull StringSerializer serializer) {
        if (mValueId != -1) {
            serializer.append(indent, serializedName() + " = " + mTextId + " : " + mValueId);
        } else {
            serializer.append(indent, serializedName() + " = " + mTextId);
        }
    }

    @Override
    public void apply(@NonNull RemoteContext context) {}

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return (indent != null ? indent : "") + toString();
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {}

    @Override
    public void runAction(
            @NonNull RemoteContext context,
            @NonNull CoreDocument document,
            @NonNull Component component,
            float x,
            float y) {
        Object value = null;
        if (mValueId != -1) {
            if (mType == INT_TYPE) {
                value = context.mRemoteComposeState.getInteger(mValueId);
            } else if (mType == STRING_TYPE) {
                value = context.mRemoteComposeState.getFromId(mValueId);
            } else if (mType == FLOAT_TYPE) {
                value = context.mRemoteComposeState.getFloat(mValueId);
            } else if (mType == FLOAT_ARRAY_TYPE) {
                value = context.mRemoteComposeState.getFloats(mValueId);
            }
        }
        context.runNamedAction(mTextId, value);
    }

    public static void apply(@NonNull WireBuffer buffer, int textId, int type, int valueId) {
        buffer.start(OP_CODE);
        buffer.writeInt(textId);
        buffer.writeInt(type);
        buffer.writeInt(valueId);
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        int textId = buffer.readInt();
        int type = buffer.readInt();
        int valueId = buffer.readInt();
        operations.add(new HostNamedActionOperation(textId, type, valueId));
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Layout Operations", OP_CODE, "HostNamedAction")
                .description("Host Named action. This operation represents a host action")
                .field(INT, "TEXT_ID", "Named Host Action Text ID")
                .field(INT, "VALUE_ID", "Named Host Action Value ID");
    }
}
