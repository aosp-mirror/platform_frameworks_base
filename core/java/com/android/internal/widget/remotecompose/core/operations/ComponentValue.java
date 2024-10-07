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
package com.android.internal.widget.remotecompose.core.operations;

import static com.android.internal.widget.remotecompose.core.documentation.Operation.INT;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.SerializableToString;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;

import java.util.List;

public class ComponentValue implements Operation, SerializableToString {
    public static final int OP_CODE = Operations.COMPONENT_VALUE;
    public static final String CLASS_NAME = "ComponentValue";

    public static final int WIDTH = 0;
    public static final int HEIGHT = 1;

    private int mType = WIDTH;
    private int mComponentID = -1;
    private int mValueId = -1;

    public static int id() {
        return OP_CODE;
    }

    public static String name() {
        return CLASS_NAME;
    }

    @Override
    public String toString() {
        return CLASS_NAME + "(" + mType + ", " + mComponentID + ", " + mValueId + ")";
    }

    public int getType() {
        return mType;
    }

    public int getComponentId() {
        return mComponentID;
    }

    public int getValueId() {
        return mValueId;
    }

    @Override
    public void write(WireBuffer buffer) {
        apply(buffer, mType, mComponentID, mValueId);
    }

    @Override
    public void apply(RemoteContext context) {
        // Nothing
    }

    public static void read(WireBuffer buffer, List<Operation> operations) {
        int type = buffer.readInt();
        int componentId = buffer.readInt();
        int valueId = buffer.readInt();
        ComponentValue op = new ComponentValue(type, componentId, valueId);
        operations.add(op);
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Expressions Operations",
                        OP_CODE,
                        CLASS_NAME)
                .description("Encode a component-related value (eg its width, height etc.)")
                .field(INT, "TYPE",
                        "The type of value, either WIDTH(0) or HEIGHT(1)")
                .field(INT, "COMPONENT_ID",
                        "The component id to reference")
                .field(INT, "VALUE_ID",
                        "The id of the RemoteFloat representing the described"
                                + " component value, which can be used in expressions");
    }

    public ComponentValue(int type, int componentId, int valueId) {
        mType = type;
        mComponentID = componentId;
        mValueId = valueId;
    }

    /**
     * Writes out the ComponentValue to the buffer
     *
     * @param buffer buffer to write to
     * @param type   type of value (WIDTH or HEIGHT)
     * @param componentId     component id to reference
     * @param valueId  remote float used to represent the component value
     */
    public static void apply(WireBuffer buffer,
                             int type,
                             int componentId,
                             int valueId) {
        buffer.start(OP_CODE);
        buffer.writeInt(type);
        buffer.writeInt(componentId);
        buffer.writeInt(valueId);
    }

    @Override
    public String deepToString(String indent) {
        return null;
    }

    public void serializeToString(int indent, StringSerializer serializer) {
        String type = "WIDTH";
        if (mType == HEIGHT) {
            type = "HEIGHT";
        }
        serializer.append(indent, CLASS_NAME
                + " value " + mValueId + " set to "
                + type + " of Component " + mComponentID);
    }
}
