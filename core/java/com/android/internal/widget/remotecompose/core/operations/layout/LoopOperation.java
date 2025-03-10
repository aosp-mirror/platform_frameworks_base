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
package com.android.internal.widget.remotecompose.core.operations.layout;

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.PaintOperation;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation;
import com.android.internal.widget.remotecompose.core.operations.Utils;

import java.util.ArrayList;
import java.util.List;

/** Represents a loop of operations */
public class LoopOperation extends PaintOperation implements VariableSupport {
    private static final int OP_CODE = Operations.LOOP_START;

    @NonNull public ArrayList<Operation> mList = new ArrayList<>();

    int mIndexVariableId;
    float mUntil;
    float mFrom;
    float mStep;
    float mUntilOut;
    float mFromOut;
    float mStepOut;

    public LoopOperation(int count, int indexId) {
        mUntil = count;
        mIndexVariableId = indexId;
    }

    @Override
    public void registerListening(RemoteContext context) {
        if (Float.isNaN(mUntil)) {
            context.listensTo(Utils.idFromNan(mUntil), this);
        }
        if (Float.isNaN(mFrom)) {
            context.listensTo(Utils.idFromNan(mFrom), this);
        }
        if (Float.isNaN(mStep)) {
            context.listensTo(Utils.idFromNan(mStep), this);
        }
    }

    @Override
    public void updateVariables(RemoteContext context) {
        mUntilOut = Float.isNaN(mUntil) ? context.getFloat(Utils.idFromNan(mUntil)) : mUntil;
        mFromOut = Float.isNaN(mFrom) ? context.getFloat(Utils.idFromNan(mFrom)) : mFrom;
        mStepOut = Float.isNaN(mStep) ? context.getFloat(Utils.idFromNan(mStep)) : mStep;
    }

    public LoopOperation(int indexId, float from, float step, float until) {
        mUntil = until;
        mFrom = from;
        mStep = step;
        mIndexVariableId = indexId;
    }

    @NonNull
    public ArrayList<Operation> getList() {
        return mList;
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mIndexVariableId, mFrom, mStep, mUntil);
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("LoopOperation\n");
        for (Operation operation : mList) {
            builder.append("  ");
            builder.append(operation);
            builder.append("\n");
        }
        return builder.toString();
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return (indent != null ? indent : "") + toString();
    }

    @Override
    public void paint(@NonNull PaintContext context) {
        RemoteContext remoteContext = context.getContext();
        if (mIndexVariableId == 0) {
            for (float i = mFromOut; i < mUntilOut; i += mStepOut) {
                for (Operation op : mList) {
                    remoteContext.incrementOpCount();
                    op.apply(context.getContext());
                }
            }
        } else {
            for (float i = mFromOut; i < mUntilOut; i += mStepOut) {
                context.getContext().loadFloat(mIndexVariableId, i);
                for (Operation op : mList) {
                    if (op instanceof VariableSupport && op.isDirty()) {
                        ((VariableSupport) op).updateVariables(context.getContext());
                    }
                    remoteContext.incrementOpCount();
                    op.apply(context.getContext());
                }
            }
        }
    }

    /**
     * The name of the class
     *
     * @return the name
     */
    @NonNull
    public static String name() {
        return "Loop";
    }

    public static void apply(
            @NonNull WireBuffer buffer, int indexId, float from, float step, float until) {
        buffer.start(OP_CODE);
        buffer.writeInt(indexId);
        buffer.writeFloat(from);
        buffer.writeFloat(step);
        buffer.writeFloat(until);
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        int indexId = buffer.readInt();
        float from = buffer.readFloat();
        float step = buffer.readFloat();
        float until = buffer.readFloat();
        operations.add(new LoopOperation(indexId, from, step, until));
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Operations", OP_CODE, name())
                .description("Loop. This operation execute" + " a list of action in a loop")
                .field(DocumentedOperation.INT, "id", "if not 0 write value")
                .field(DocumentedOperation.FLOAT, "from", "values starts at")
                .field(DocumentedOperation.FLOAT, "step", "value step")
                .field(DocumentedOperation.FLOAT, "until", "stops less than or equal");
    }

    /**
     * Calculate and estimate of the number of iterations
     *
     * @return number of loops or 10 if based on variables
     */
    public int estimateIterations() {
        if (!(Float.isNaN(mUntil) || Float.isNaN(mFrom) || Float.isNaN(mStep))) {
            return (int) (0.5f + (mUntil - mFrom) / mStep);
        }
        return 10; // this is a generic estmate if the values are variables;
    }
}
