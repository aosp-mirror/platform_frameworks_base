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

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.PaintOperation;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;

import java.util.ArrayList;
import java.util.List;

/** Represents a loop of operations */
public class LoopOperation extends PaintOperation {
    private static final int OP_CODE = Operations.LOOP_START;

    public ArrayList<Operation> mList = new ArrayList<>();

    int mIndexVariableId;
    float mUntil = 12;
    float mFrom = 0;
    float mStep = 1;

    public LoopOperation(int count, int indexId) {
        mUntil = count;
        mIndexVariableId = indexId;
    }

    public LoopOperation(float count, float from, float step, int indexId) {
        mUntil = count;
        mFrom = from;
        mStep = step;
        mIndexVariableId = indexId;
    }

    public ArrayList<Operation> getList() {
        return mList;
    }

    @Override
    public void write(WireBuffer buffer) {
        apply(buffer, mUntil, mFrom, mStep, mIndexVariableId);
    }

    @Override
    public String toString() {
        return "LoopOperation";
    }

    @Override
    public String deepToString(String indent) {
        return (indent != null ? indent : "") + toString();
    }

    @Override
    public void paint(PaintContext context) {
        if (mIndexVariableId == 0) {
            for (float i = mFrom; i < mUntil; i += mStep) {
                for (Operation op : mList) {
                    op.apply(context.getContext());
                }
            }
        } else {
            for (float i = mFrom; i < mUntil; i += mStep) {
                context.getContext().loadFloat(mIndexVariableId, i);
                for (Operation op : mList) {
                    if (op instanceof VariableSupport) {
                        ((VariableSupport) op).updateVariables(context.getContext());
                    }
                    op.apply(context.getContext());
                }
            }
        }
    }

    public static String name() {
        return "Loop";
    }

    public static void apply(WireBuffer buffer, float count, float from, float step, int indexId) {
        buffer.start(OP_CODE);
        buffer.writeFloat(count);
        buffer.writeFloat(from);
        buffer.writeFloat(step);
        buffer.writeInt(indexId);
    }

    public static void read(WireBuffer buffer, List<Operation> operations) {
        float count = buffer.readFloat();
        float from = buffer.readFloat();
        float step = buffer.readFloat();
        int indexId = buffer.readInt();
        operations.add(new LoopOperation(count, from, step, indexId));
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Operations", OP_CODE, name())
                .description("Loop. This operation execute" + " a list of action in a loop");
    }
}
