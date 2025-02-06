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
import android.annotation.Nullable;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.PaintOperation;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation;
import com.android.internal.widget.remotecompose.core.operations.utilities.AnimatedFloatExpression;
import com.android.internal.widget.remotecompose.core.operations.utilities.NanMap;

import java.util.ArrayList;
import java.util.List;

/** This provides the command to call a floatfunction defined in floatfunction */
public class FloatFunctionCall extends PaintOperation implements VariableSupport {
    private static final int OP_CODE = Operations.FUNCTION_CALL;
    private static final String CLASS_NAME = "FunctionCall";
    private final int mId;
    private final float[] mArgs;
    private final float[] mOutArgs;

    FloatFunctionDefine mFunction;

    @NonNull private ArrayList<Operation> mList = new ArrayList<>();

    @NonNull AnimatedFloatExpression mExp = new AnimatedFloatExpression();

    /**
     * Create a new FloatFunctionCall operation
     *
     * @param id The function to call
     * @param args the arguments to call it with
     */
    public FloatFunctionCall(int id, float[] args) {
        mId = id;
        mArgs = args;
        if (args != null) {
            mOutArgs = new float[args.length];
            System.arraycopy(args, 0, mOutArgs, 0, args.length);
        } else {
            mOutArgs = null;
        }
    }

    @Override
    public void updateVariables(@NonNull RemoteContext context) {
        if (mOutArgs != null) {
            for (int i = 0; i < mArgs.length; i++) {
                float v = mArgs[i];
                mOutArgs[i] =
                        (Float.isNaN(v)
                                        && !AnimatedFloatExpression.isMathOperator(v)
                                        && !NanMap.isDataVariable(v))
                                ? context.getFloat(Utils.idFromNan(v))
                                : v;
            }
        }
    }

    @Override
    public void registerListening(@NonNull RemoteContext context) {
        mFunction = (FloatFunctionDefine) context.getObject(mId);
        if (mArgs != null) {
            for (int i = 0; i < mArgs.length; i++) {
                float v = mArgs[i];
                if (Float.isNaN(v)
                        && !AnimatedFloatExpression.isMathOperator(v)
                        && !NanMap.isDataVariable(v)) {
                    context.listensTo(Utils.idFromNan(v), this);
                }
            }
        }
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mId, mArgs);
    }

    @NonNull
    @Override
    public String toString() {
        String str = "callFunction[" + Utils.idString(mId) + "] ";
        for (int i = 0; i < mArgs.length; i++) {
            str += ((i == 0) ? "" : " ,") + Utils.floatToString(mArgs[i], mOutArgs[i]);
        }
        return str;
    }

    /**
     * Write the operation on the buffer
     *
     * @param buffer the buffer to write to
     * @param id the id of the function to call
     * @param args the arguments to call the function with
     */
    public static void apply(@NonNull WireBuffer buffer, int id, @Nullable float[] args) {
        buffer.start(OP_CODE);
        buffer.writeInt(id);
        if (args != null) {
            buffer.writeInt(args.length);
            for (int i = 0; i < args.length; i++) {
                buffer.writeFloat(args[i]);
            }
        } else {
            buffer.writeInt(0);
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
        int argLen = buffer.readInt();
        float[] args = null;
        if (argLen > 0) {
            args = new float[argLen];
            for (int i = 0; i < argLen; i++) {
                args[i] = buffer.readFloat();
            }
        }

        FloatFunctionCall data = new FloatFunctionCall(id, args);
        operations.add(data);
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Data Operations", OP_CODE, CLASS_NAME)
                .description("Command to call the function")
                .field(DocumentedOperation.INT, "id", "id of function to call")
                .field(INT, "argLen", "the number of Arguments")
                .field(FLOAT_ARRAY, "values", "argLen", "array of float arguments");
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return indent + toString();
    }

    @Override
    public void paint(@NonNull PaintContext context) {
        RemoteContext remoteContext = context.getContext();
        int[] args = mFunction.getArgs();
        for (int j = 0; j < mOutArgs.length; j++) {
            remoteContext.loadFloat(args[j], mOutArgs[j]);
            updateVariables(remoteContext);
        }
        mFunction.execute(remoteContext);
    }
}
