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
import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.INT_ARRAY;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation;
import com.android.internal.widget.remotecompose.core.operations.utilities.ArrayAccess;

import java.util.Arrays;
import java.util.List;

public class DataListIds implements VariableSupport, ArrayAccess, Operation {
    private static final int OP_CODE = Operations.ID_LIST;
    private static final String CLASS_NAME = "IdListData";
    int mId;
    int[] mIds;
    private static final int MAX_LIST = 2000;

    public DataListIds(int id, int[] ids) {
        mId = id;
        mIds = ids;
    }

    @Override
    public void updateVariables(RemoteContext context) {}

    @Override
    public void registerListening(RemoteContext context) {}

    @Override
    public void write(WireBuffer buffer) {
        apply(buffer, mId, mIds);
    }

    @Override
    public String toString() {
        return "map[" + Utils.idString(mId) + "]  \"" + Arrays.toString(mIds) + "\"";
    }

    public static void apply(WireBuffer buffer, int id, int[] ids) {
        buffer.start(OP_CODE);
        buffer.writeInt(id);
        buffer.writeInt(ids.length);
        for (int i = 0; i < ids.length; i++) {
            buffer.writeInt(ids[i]);
        }
    }

    public static void read(WireBuffer buffer, List<Operation> operations) {
        int id = buffer.readInt();
        int len = buffer.readInt();
        if (len > MAX_LIST) {
            throw new RuntimeException(len + " list entries more than max = " + MAX_LIST);
        }
        int[] ids = new int[len];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = buffer.readInt();
        }
        DataListIds data = new DataListIds(id, ids);
        operations.add(data);
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Data Operations", OP_CODE, CLASS_NAME)
                .description("a list of id's")
                .field(DocumentedOperation.INT, "id", "id the array")
                .field(INT, "length", "number of ids")
                .field(INT_ARRAY, "ids[n]", "length", "ids of other variables");
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
        return Float.NaN;
    }

    @Override
    public int getId(int index) {
        return mIds[index];
    }

    @Override
    public float[] getFloats() {
        return null;
    }

    @Override
    public int getLength() {
        return mIds.length;
    }

    @Override
    public int getIntValue(int index) {
        return 0;
    }
}
