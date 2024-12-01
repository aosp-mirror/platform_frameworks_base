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
public class HostActionOperation extends Operation implements ActionOperation {
    private static final int OP_CODE = Operations.HOST_ACTION;

    int mActionId = -1;

    public HostActionOperation(int id) {
        mActionId = id;
    }

    @NonNull
    @Override
    public String toString() {
        return "HostActionOperation(" + mActionId + ")";
    }

    public int getActionId() {
        return mActionId;
    }

    @NonNull
    public String serializedName() {
        return "HOST_ACTION";
    }

    @Override
    public void serializeToString(int indent, @NonNull StringSerializer serializer) {
        serializer.append(indent, serializedName() + " = " + mActionId);
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
        context.runAction(mActionId, "");
    }

    public static void apply(@NonNull WireBuffer buffer, int actionId) {
        buffer.start(OP_CODE);
        buffer.writeInt(actionId);
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        int actionId = buffer.readInt();
        operations.add(new HostActionOperation(actionId));
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Layout Operations", OP_CODE, "HostAction")
                .description("Host action. This operation represents a host action")
                .field(INT, "ACTION_ID", "Host Action ID");
    }
}
