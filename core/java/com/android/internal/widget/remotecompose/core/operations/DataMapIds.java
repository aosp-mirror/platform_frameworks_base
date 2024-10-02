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
import static com.android.internal.widget.remotecompose.core.documentation.Operation.UTF8;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.operations.utilities.ArrayAccess;

import java.util.List;

public class DataMapIds implements VariableSupport, ArrayAccess, Operation  {
    private static final int OP_CODE = Operations.ID_MAP;
    private static final String CLASS_NAME = "IdMapData";
    int mId;
    String[] mNames;
    int[] mIds;
    float[] mValues;
    private static final int MAX_MAP = 2000;

    public DataMapIds(int id, String[] names, int[] ids) {
        mId = id;
        mNames = names;
        mIds = ids;
        mValues = new float[ids.length];

    }

    @Override
    public void updateVariables(RemoteContext context) {
        for (int i = 0; i < mIds.length; i++) {
            int id = mIds[i];
            mValues[i] = context.getFloat(id);
        }
    }

    @Override
    public void registerListening(RemoteContext context) {
        for (int mId : mIds) {
            context.listensTo(mId, this);
        }
    }

    @Override
    public void write(WireBuffer buffer) {
        apply(buffer, mId, mNames, mIds);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("DataMapIds ");
        for (int i = 0; i < mNames.length; i++) {
            if (i != 0) {
                builder.append(" ");
            }
            builder.append(mNames[i]);
            builder.append("[");
            builder.append(mIds[i]);
            builder.append("]");

        }
        return builder.toString();
    }

    public static void apply(WireBuffer buffer, int id, String[] names, int[] ids) {
        buffer.start(OP_CODE);
        buffer.writeInt(id);
        buffer.writeInt(names.length);
        for (int i = 0; i < names.length; i++) {
            buffer.writeUTF8(names[i]);
            buffer.writeInt(ids[i]);
        }
    }

    public static void read(WireBuffer buffer, List<Operation> operations) {
        int id = buffer.readInt();
        int len = buffer.readInt();
        if (len > MAX_MAP) {
            throw new RuntimeException(len + " map entries more than max = " + MAX_MAP);
        }
        String[] names = new String[len];
        int[] ids = new int[len];
        for (int i = 0; i < names.length; i++) {
            names[i] = buffer.readUTF8();
            ids[i] = buffer.readInt();
        }
        DataMapIds data = new DataMapIds(id, names, ids);
        operations.add(data);
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Data Operations",
                        OP_CODE,
                        CLASS_NAME)
                .description("Encode a collection of name id pairs")
                .field(INT, "id", "id the array")
                .field(INT, "length", "number of entries")
                .field(INT, "names[0]", "length",
                        "path encoded as floats")
                .field(UTF8, "id[0]", "length",
                        "path encoded as floats");
    }

    @Override
    public String deepToString(String indent) {
        return indent + toString();
    }

    @Override
    public void apply(RemoteContext context) {
        context.addCollection(mId, this);
    }

    @Override
    public float getFloatValue(int index) {
        return mValues[index];
    }

    @Override
    public float[] getFloats() {
        return mValues;
    }

    @Override
    public int getFloatsLength() {
        return mValues.length;
    }
}
