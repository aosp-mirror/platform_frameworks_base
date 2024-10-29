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

import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.INT;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.operations.utilities.DataMap;
import com.android.internal.widget.remotecompose.core.types.BooleanConstant;
import com.android.internal.widget.remotecompose.core.types.LongConstant;

import java.util.List;

/** This can lookup in a map given a string writing the results to an id. */
public class DataMapLookup implements Operation {
    private static final int OP_CODE = Operations.DATA_MAP_LOOKUP;
    private static final String CLASS_NAME = "DataMapLookup";
    public int mId;
    public int mDataMapId;
    public int mStringId;

    /**
     * Create an access to a data map
     *
     * @param id of the output value
     * @param dataMapId the id of the data map
     * @param keyStringId the string to be looked up
     */
    public DataMapLookup(int id, int dataMapId, int keyStringId) {
        this.mId = id;
        this.mDataMapId = dataMapId;
        this.mStringId = keyStringId;
    }

    @Override
    public void write(WireBuffer buffer) {
        apply(buffer, mId, mDataMapId, mStringId);
    }

    @Override
    public String toString() {
        return "DataMapLookup[" + mId + "] = " + Utils.idString(mDataMapId) + " " + mStringId;
    }

    /**
     * The class name
     *
     * @return the name of the class
     */
    public static String name() {
        return CLASS_NAME;
    }

    /**
     * The opcode
     *
     * @return the opcode
     */
    public static int id() {
        return OP_CODE;
    }

    /**
     * Writes out the operation to the buffer
     *
     * @param buffer write command to this buffer
     * @param id the id
     * @param dataMapId the map to extract from
     * @param keyStringId the map to extract from
     */
    public static void apply(WireBuffer buffer, int id, int dataMapId, int keyStringId) {
        buffer.start(OP_CODE);
        buffer.writeInt(id);
        buffer.writeInt(dataMapId);
        buffer.writeInt(keyStringId);
    }

    /**
     * The read the buffer and create the command
     *
     * @param buffer buffer
     * @param operations the created command is added to the list
     */
    public static void read(WireBuffer buffer, List<Operation> operations) {
        int id = buffer.readInt();
        int mapId = buffer.readInt();
        int stringId = buffer.readInt();
        operations.add(new DataMapLookup(id, mapId, stringId));
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Expressions Operations", OP_CODE, CLASS_NAME)
                .description("A float and its associated id")
                .field(INT, "id", "id of float")
                .field(INT, "dataMapId", "32-bit float value")
                .field(INT, "value", "32-bit float value");
    }

    @Override
    public void apply(RemoteContext context) {
        String str = context.getText(mStringId);
        DataMap data = context.getDataMap(mDataMapId);
        int pos = data.getPos(str);
        byte type = data.getType(pos);
        int dataId = data.getId(pos);
        switch (type) {
            case DataMapIds.TYPE_STRING:
                context.loadText(mId, context.getText(dataId));
                break;
            case DataMapIds.TYPE_INT:
                context.loadInteger(mId, context.getInteger(dataId));
                break;
            case DataMapIds.TYPE_FLOAT:
                context.loadFloat(mId, context.getFloat(dataId));
                break;
            case DataMapIds.TYPE_LONG:
                LongConstant lc = (LongConstant) context.getObject(dataId);
                context.loadInteger(mId, (int) lc.getValue());
                break;
            case DataMapIds.TYPE_BOOLEAN:
                BooleanConstant bc = (BooleanConstant) context.getObject(dataId);
                context.loadInteger(mId, bc.getValue() ? 1 : 0);
                break;
        }
    }

    @Override
    public String deepToString(String indent) {
        return indent + toString();
    }
}
