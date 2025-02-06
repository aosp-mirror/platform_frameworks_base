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

import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.FLOAT_ARRAY;
import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.INT;

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.Container;
import com.android.internal.widget.remotecompose.core.operations.utilities.AnimatedFloatExpression;

import java.util.ArrayList;
import java.util.List;

/**
 * This defines a function Operator. It contains a collection of commands which are then executed by
 * the FloatFunctionCall command
 */
public class FloatFunctionDefine extends Operation implements VariableSupport, Container {
    private static final int OP_CODE = Operations.FUNCTION_DEFINE;
    private static final String CLASS_NAME = "FunctionDefine";
    private final int mId;
    private final int[] mFloatVarId;
    @NonNull private ArrayList<Operation> mList = new ArrayList<>();

    @NonNull AnimatedFloatExpression mExp = new AnimatedFloatExpression();

    /**
     * @param id The id of the function
     * @param floatVarId the ids of the variables
     */
    public FloatFunctionDefine(int id, int[] floatVarId) {
        mId = id;
        mFloatVarId = floatVarId;
    }

    @NonNull
    @Override
    public ArrayList<Operation> getList() {
        return mList;
    }

    @Override
    public void updateVariables(@NonNull RemoteContext context) {}

    @Override
    public void registerListening(@NonNull RemoteContext context) {
        context.putObject(mId, this);
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mId, mFloatVarId);
    }

    @NonNull
    @Override
    public String toString() {
        String str = "FloatFunctionDefine[" + Utils.idString(mId) + "] (";
        for (int j = 0; j < mFloatVarId.length; j++) {
            str += "[" + mFloatVarId[j] + "] ";
        }
        str += ")";
        for (Operation operation : mList) {
            str += " \n  " + operation.toString();
        }
        return str;
    }

    /**
     * Write the operation on the buffer
     *
     * @param buffer the buffer to write to
     * @param id the id of the function
     * @param varId the ids of the variables
     */
    public static void apply(@NonNull WireBuffer buffer, int id, @NonNull int[] varId) {
        buffer.start(OP_CODE);
        buffer.writeInt(id);
        buffer.writeInt(varId.length);
        for (int i = 0; i < varId.length; i++) {
            buffer.writeInt(varId[i]);
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
        int varLen = buffer.readInt();
        int[] varId = new int[varLen];
        for (int i = 0; i < varId.length; i++) {
            varId[i] = buffer.readInt();
        }
        FloatFunctionDefine data = new FloatFunctionDefine(id, varId);
        operations.add(data);
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Data Operations", OP_CODE, CLASS_NAME)
                .description("Define a function")
                .field(DocumentedOperation.INT, "id", "The reference of the function")
                .field(INT, "varLen", "number of arguments to the function")
                .field(FLOAT_ARRAY, "id", "varLen", "id equations");
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return indent + toString();
    }

    /**
     * @return the array of id's
     */
    public int[] getArgs() {
        return mFloatVarId;
    }

    @Override
    public void apply(@NonNull RemoteContext context) {}

    /**
     * Execute the function by applying the list of operations
     *
     * @param context the current RemoteContext
     */
    public void execute(@NonNull RemoteContext context) {
        for (Operation op : mList) {
            if (op instanceof VariableSupport) {
                ((VariableSupport) op).updateVariables(context);
            }

            context.incrementOpCount();
            op.apply(context);
        }
    }
}
