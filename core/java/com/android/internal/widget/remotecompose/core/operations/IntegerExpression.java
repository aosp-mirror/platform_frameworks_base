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
import static com.android.internal.widget.remotecompose.core.documentation.Operation.INT_ARRAY;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.operations.utilities.IntegerExpressionEvaluator;

import java.util.Arrays;
import java.util.List;

/**
 * Operation to deal with AnimatedFloats
 * This is designed to be an optimized calculation for things like
 * injecting the width of the component int draw rect
 * As well as supporting generalized animation floats.
 * The floats represent a RPN style calculator
 */
public class IntegerExpression implements Operation, VariableSupport {
    private static final int OP_CODE = Operations.INTEGER_EXPRESSION;
    private static final String CLASS_NAME = "IntegerExpression";
    public int mId;
    private int mMask;
    private int mPreMask;
    public int[] mSrcValue;
    public int[] mPreCalcValue;
    private float mLastChange = Float.NaN;
    public static final int MAX_STRING_SIZE = 4000;
    IntegerExpressionEvaluator mExp = new IntegerExpressionEvaluator();

    public IntegerExpression(int id, int mask, int[] value) {
        this.mId = id;
        this.mMask = mask;
        this.mSrcValue = value;
    }

    @Override
    public void updateVariables(RemoteContext context) {
        if (mPreCalcValue == null || mPreCalcValue.length != mSrcValue.length) {
            mPreCalcValue = new int[mSrcValue.length];
        }
        mPreMask = mMask;
        for (int i = 0; i < mSrcValue.length; i++) {
            if (isId(mMask, i, mSrcValue[i])) {
                mPreMask &= ~(0x1 << i);
                mPreCalcValue[i] = context.getInteger(mSrcValue[i]);
            } else {
                mPreCalcValue[i] = mSrcValue[i];
            }
        }
    }


    @Override
    public void registerListening(RemoteContext context) {
        for (int i = 0; i < mSrcValue.length; i++) {
            if (isId(mMask, i, mSrcValue[i])) {
                context.listensTo(mSrcValue[i], this);
            }
        }
    }

    @Override
    public void apply(RemoteContext context) {
        updateVariables(context);
        float t = context.getAnimationTime();
        if (Float.isNaN(mLastChange)) {
            mLastChange = t;
        }
        int v = mExp.eval(mPreMask, Arrays.copyOf(mPreCalcValue, mPreCalcValue.length));
        context.loadInteger(mId, v);
    }

    @Override
    public void write(WireBuffer buffer) {
        apply(buffer, mId, mMask, mSrcValue);
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < mPreCalcValue.length; i++) {
            if (i != 0) {
                s.append(" ");
            }
            if (IntegerExpressionEvaluator.isOperation(mMask, i)) {
                if (isId(mMask, i, mSrcValue[i])) {
                    s.append("[" + mSrcValue[i] + "]");
                } else {
                    s.append(IntegerExpressionEvaluator.toMathName(mPreCalcValue[i]));
                }
            } else {
                s.append(mSrcValue[i]);
            }
        }
        return "IntegerExpression[" + mId + "] = (" + s + ")";
    }

    public static String name() {
        return CLASS_NAME;
    }

    public static int id() {
        return OP_CODE;
    }

    /**
     * Writes out the operation to the buffer
     *
     * @param buffer buffer to write to
     * @param id     the id of the integer
     * @param mask   the mask bits of ints & operators or variables
     * @param value  array of integers to be evaluated
     */
    public static void apply(WireBuffer buffer, int id, int mask, int[] value) {
        buffer.start(OP_CODE);
        buffer.writeInt(id);
        buffer.writeInt(mask);
        buffer.writeInt(value.length);
        for (int opMask : value) {
            buffer.writeInt(opMask);
        }
    }

    public static void read(WireBuffer buffer, List<Operation> operations) {
        int id = buffer.readInt();
        int mask = buffer.readInt();
        int len = buffer.readInt();

        int[] values = new int[len];
        for (int i = 0; i < values.length; i++) {
            values[i] = buffer.readInt();
        }

        operations.add(new IntegerExpression(id, mask, values));
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Data Operations",
                        OP_CODE,
                        CLASS_NAME)
                .description("Expression that computes an integer")
                .field(INT, "id", "id of integer")
                .field(INT, "mask",
                        "bits representing operators or other id's")
                .field(INT, "length",
                        "length of array")
                .field(INT_ARRAY, "values", "length",
                        "Array of ints");
    }

    @Override
    public String deepToString(String indent) {
        return indent + toString();
    }

    /**
     * given the "i" position in the mask is this an ID
     *
     * @param mask  32 bit mask used for defining numbers vs other
     * @param i     the bit in question
     * @param value the value
     * @return true if this is an ID
     */
    public static boolean isId(int mask, int i, int value) {
        return ((1 << i) & mask) != 0 && value < IntegerExpressionEvaluator.OFFSET;
    }
}
