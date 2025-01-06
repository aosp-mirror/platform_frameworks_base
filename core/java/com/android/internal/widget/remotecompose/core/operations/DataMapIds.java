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
import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.UTF8;

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.operations.utilities.DataMap;

import java.util.List;

/** This is a map of strings to type & Id */
public class DataMapIds extends Operation {
    private static final int OP_CODE = Operations.ID_MAP;
    private static final String CLASS_NAME = "DataMapIds";
    int mId;
    final DataMap mDataMap;

    private static final int MAX_MAP = 2000;

    public static final byte TYPE_STRING = 0;
    public static final byte TYPE_INT = 1;
    public static final byte TYPE_FLOAT = 2;
    public static final byte TYPE_LONG = 3;
    public static final byte TYPE_BOOLEAN = 4;

    @NonNull
    private String typeString(byte type) {
        switch (type) {
            case TYPE_STRING:
                return "String";
            case TYPE_INT:
                return "Int";
            case TYPE_FLOAT:
                return "Float";
            case TYPE_LONG:
                return "Long";
            case TYPE_BOOLEAN:
                return "Boolean";
        }
        return "?";
    }

    public DataMapIds(int id, @NonNull String[] names, @NonNull byte[] types, @NonNull int[] ids) {
        mId = id;
        mDataMap = new DataMap(names, types, ids);
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mId, mDataMap.mNames, mDataMap.mTypes, mDataMap.mIds);
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("DataMapIds[" + Utils.idString(mId) + "] ");
        for (int i = 0; i < mDataMap.mNames.length; i++) {
            if (i != 0) {
                builder.append(" ");
            }
            builder.append(typeString(mDataMap.mTypes[i]));
            builder.append("[");
            builder.append(mDataMap.mNames[i]);
            builder.append("]=");
            builder.append(mDataMap.mIds[i]);
        }
        return builder.toString();
    }

    public static void apply(
            @NonNull WireBuffer buffer,
            int id,
            @NonNull String[] names,
            @Nullable byte[] type, // todo: can we make this not nullable?
            @NonNull int[] ids) {
        buffer.start(OP_CODE);
        buffer.writeInt(id);
        buffer.writeInt(names.length);
        for (int i = 0; i < names.length; i++) {
            buffer.writeUTF8(names[i]);
            buffer.writeByte(type == null ? 2 : type[i]);
            buffer.writeInt(ids[i]);
        }
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        int id = buffer.readInt();
        int len = buffer.readInt();
        if (len > MAX_MAP) {
            throw new RuntimeException(len + " map entries more than max = " + MAX_MAP);
        }
        String[] names = new String[len];
        int[] ids = new int[len];
        byte[] types = new byte[len];
        for (int i = 0; i < names.length; i++) {
            names[i] = buffer.readUTF8();
            types[i] = (byte) buffer.readByte();
            ids[i] = buffer.readInt();
        }
        DataMapIds data = new DataMapIds(id, names, types, ids);
        operations.add(data);
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Data Operations", OP_CODE, CLASS_NAME)
                .description("Encode a collection of name id pairs")
                .field(INT, "id", "id the array")
                .field(INT, "length", "number of entries")
                .field(INT, "names[0]", "length", "path encoded as floats")
                .field(UTF8, "id[0]", "length", "path encoded as floats");
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return indent + toString();
    }

    @Override
    public void apply(@NonNull RemoteContext context) {
        context.putDataMap(mId, mDataMap);
    }
}
