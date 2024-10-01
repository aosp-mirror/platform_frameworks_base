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
 * Capture a host action information. This can be triggered on eg. a click.
 */
public class HostActionOperation implements ActionOperation {
    private static final int OP_CODE = Operations.HOST_ACTION;

    int mActionId = -1;

    public HostActionOperation(int id) {
        mActionId = id;
    }

    @Override
    public String toString() {
        return "HostActionOperation(" + mActionId + ")";
    }

    public int getActionId() {
        return mActionId;
    }

    public String serializedName() {
        return "HOST_ACTION";
    }

    @Override
    public void serializeToString(int indent, StringSerializer serializer) {
        serializer.append(indent, serializedName() + " = " + mActionId);
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
        context.runAction(mActionId, "");
    }

    public static void apply(WireBuffer buffer, int actionId) {
        buffer.start(OP_CODE);
        buffer.writeInt(actionId);
    }

    public static void read(WireBuffer buffer, List<Operation> operations) {
        int actionId = buffer.readInt();
        operations.add(new HostActionOperation(actionId));
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Layout Operations", OP_CODE, "HostAction")
                .description("Host action. This operation represents a host action")
                .field(INT, "ACTION_ID", "Host Action ID");
    }

}
