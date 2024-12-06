/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.FLOAT;
import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.FLOAT_ARRAY;
import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.INT;
import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.SHORT;

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation;
import com.android.internal.widget.remotecompose.core.operations.utilities.AnimatedFloatExpression;
import com.android.internal.widget.remotecompose.core.operations.utilities.NanMap;
import com.android.internal.widget.remotecompose.core.operations.utilities.easing.FloatAnimation;
import com.android.internal.widget.remotecompose.core.operations.utilities.easing.SpringStopEngine;

import java.util.List;

/**
 * Operation to deal with AnimatedFloats This is designed to be an optimized calculation for things
 * like injecting the width of the component int draw rect As well as supporting generalized
 * animation floats. The floats represent a RPN style calculator
 */
public class FloatExpression extends Operation implements VariableSupport {
    private static final int OP_CODE = Operations.ANIMATED_FLOAT;
    private static final String CLASS_NAME = "FloatExpression";
    public int mId;
    @NonNull public float[] mSrcValue;
    @Nullable public float[] mSrcAnimation;
    @Nullable public FloatAnimation mFloatAnimation;
    @Nullable private SpringStopEngine mSpring;
    @Nullable public float[] mPreCalcValue;
    private float mLastChange = Float.NaN;
    private float mLastCalculatedValue = Float.NaN;
    @NonNull AnimatedFloatExpression mExp = new AnimatedFloatExpression();
    public static final int MAX_EXPRESSION_SIZE = 32;

    public FloatExpression(int id, @NonNull float[] value, @Nullable float[] animation) {
        this.mId = id;
        this.mSrcValue = value;
        this.mSrcAnimation = animation;
        if (mSrcAnimation != null) {
            if (mSrcAnimation.length > 4 && mSrcAnimation[0] == 0) {
                mSpring = new SpringStopEngine(mSrcAnimation);
            } else {
                mFloatAnimation = new FloatAnimation(mSrcAnimation);
            }
        }
    }

    @Override
    public void updateVariables(@NonNull RemoteContext context) {
        if (mPreCalcValue == null || mPreCalcValue.length != mSrcValue.length) {
            mPreCalcValue = new float[mSrcValue.length];
        }

        boolean value_changed = false;
        for (int i = 0; i < mSrcValue.length; i++) {
            float v = mSrcValue[i];
            if (Float.isNaN(v)
                    && !AnimatedFloatExpression.isMathOperator(v)
                    && !NanMap.isDataVariable(v)) {
                int id = Utils.idFromNan(v);
                float newValue = context.getFloat(Utils.idFromNan(v));

                // TODO: rethink the lifecycle for variable updates
                if (id == RemoteContext.ID_DENSITY && newValue == 0f) {
                    newValue = 1f;
                }
                if (mFloatAnimation != null) {
                    if (mPreCalcValue[i] != newValue) {
                        value_changed = true;
                        mPreCalcValue[i] = newValue;
                    }
                } else if (mSpring != null) {
                    if (mPreCalcValue[i] != newValue) {
                        value_changed = true;
                        mPreCalcValue[i] = newValue;
                    }
                } else {
                    mPreCalcValue[i] = newValue;
                }
            } else {
                mPreCalcValue[i] = mSrcValue[i];
            }
        }
        float v = mLastCalculatedValue;
        if (value_changed) { // inputs changed check if output changed
            v = mExp.eval(mPreCalcValue, mPreCalcValue.length);
            if (v != mLastCalculatedValue) {
                mLastChange = context.getAnimationTime();
                mLastCalculatedValue = v;
            } else {
                value_changed = false;
            }
        }

        if (value_changed && mFloatAnimation != null) {
            if (Float.isNaN(mFloatAnimation.getTargetValue())) {
                mFloatAnimation.setInitialValue(v);
            } else {
                mFloatAnimation.setInitialValue(mFloatAnimation.getTargetValue());
            }
            mFloatAnimation.setTargetValue(v);
        } else if (value_changed && mSpring != null) {
            mSpring.setTargetValue(v);
        }
    }

    @Override
    public void registerListening(@NonNull RemoteContext context) {
        for (float v : mSrcValue) {
            if (Float.isNaN(v)
                    && !AnimatedFloatExpression.isMathOperator(v)
                    && !NanMap.isDataVariable(v)) {
                context.listensTo(Utils.idFromNan(v), this);
            }
        }
    }

    // Keep track of the last computed value when we are animated,
    // e.g. if FloatAnimation or Spring is used, so that we can
    // ask for a repaint.
    float mLastAnimatedValue = Float.NaN;

    @Override
    public void apply(@NonNull RemoteContext context) {
        updateVariables(context);
        float t = context.getAnimationTime();
        if (Float.isNaN(mLastChange)) {
            mLastChange = t;
        }
        float lastComputedValue;
        if (mFloatAnimation != null && !Float.isNaN(mLastCalculatedValue)) {
            float f = mFloatAnimation.get(t - mLastChange);
            context.loadFloat(mId, f);
            lastComputedValue = f;
            if (lastComputedValue != mLastAnimatedValue) {
                mLastAnimatedValue = lastComputedValue;
                context.needsRepaint();
            }
        } else if (mSpring != null) {
            float f = mSpring.get(t - mLastChange);
            context.loadFloat(mId, f);
            lastComputedValue = f;
            if (lastComputedValue != mLastAnimatedValue) {
                mLastAnimatedValue = lastComputedValue;
                context.needsRepaint();
            }
        } else {
            float v =
                    mExp.eval(context.getCollectionsAccess(), mPreCalcValue, mPreCalcValue.length);
            if (mFloatAnimation != null) {
                mFloatAnimation.setTargetValue(v);
            }
            context.loadFloat(mId, v);
        }
    }

    /**
     * Evaluate the expression
     *
     * @param context current context
     * @return the resulting value
     */
    public float evaluate(@NonNull RemoteContext context) {
        updateVariables(context);
        float t = context.getAnimationTime();
        if (Float.isNaN(mLastChange)) {
            mLastChange = t;
        }
        return mExp.eval(context.getCollectionsAccess(), mPreCalcValue, mPreCalcValue.length);
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mId, mSrcValue, mSrcAnimation);
    }

    @NonNull
    @Override
    public String toString() {
        String[] labels = new String[mSrcValue.length];
        for (int i = 0; i < mSrcValue.length; i++) {
            if (Float.isNaN(mSrcValue[i])) {
                labels[i] = "[" + Utils.idStringFromNan(mSrcValue[i]) + "]";
            }
        }
        if (mPreCalcValue == null) {
            return "FloatExpression["
                    + mId
                    + "] = ("
                    + AnimatedFloatExpression.toString(mSrcValue, labels)
                    + ")";
        }
        return "FloatExpression["
                + mId
                + "] = ("
                + AnimatedFloatExpression.toString(mPreCalcValue, labels)
                + ")";
    }

    /**
     * The name of the class
     *
     * @return the name
     */
    @NonNull
    public static String name() {
        return CLASS_NAME;
    }

    /**
     * The OP_CODE for this command
     *
     * @return the opcode
     */
    public static int id() {
        return OP_CODE;
    }

    /**
     * Writes out the operation to the buffer
     *
     * @param buffer The buffer to write to
     * @param id the id of the resulting float
     * @param value the float expression array
     * @param animation the animation expression array
     */
    public static void apply(
            @NonNull WireBuffer buffer,
            int id,
            @NonNull float[] value,
            @Nullable float[] animation) {
        buffer.start(OP_CODE);
        buffer.writeInt(id);

        int len = value.length;
        if (len > MAX_EXPRESSION_SIZE) {
            throw new RuntimeException(AnimatedFloatExpression.toString(value, null) + " to long");
        }
        if (animation != null) {
            len |= (animation.length << 16);
        }
        buffer.writeInt(len);

        for (float v : value) {
            buffer.writeFloat(v);
        }
        if (animation != null) {
            for (float v : animation) {
                buffer.writeFloat(v);
            }
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
        int valueLen = len & 0xFFFF;
        if (valueLen > MAX_EXPRESSION_SIZE) {
            throw new RuntimeException("Float expression too long");
        }
        int animLen = (len >> 16) & 0xFFFF;
        float[] values = new float[valueLen];
        for (int i = 0; i < values.length; i++) {
            values[i] = buffer.readFloat();
        }

        float[] animation;
        if (animLen != 0) {
            animation = new float[animLen];
            for (int i = 0; i < animation.length; i++) {
                animation[i] = buffer.readFloat();
            }
        } else {
            animation = null;
        }
        operations.add(new FloatExpression(id, values, animation));
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Expressions Operations", OP_CODE, CLASS_NAME)
                .description("A Float expression")
                .field(DocumentedOperation.INT, "id", "The id of the Color")
                .field(SHORT, "expression_length", "expression length")
                .field(SHORT, "animation_length", "animation description length")
                .field(
                        FLOAT_ARRAY,
                        "expression",
                        "expression_length",
                        "Sequence of Floats representing and expression")
                .field(
                        FLOAT_ARRAY,
                        "AnimationSpec",
                        "animation_length",
                        "Sequence of Floats representing animation curve")
                .field(FLOAT, "duration", "> time in sec")
                .field(INT, "bits", "> WRAP|INITALVALUE | TYPE ")
                .field(FLOAT_ARRAY, "spec", "> [SPEC PARAMETERS] ")
                .field(FLOAT, "initialValue", "> [Initial value] ")
                .field(FLOAT, "wrapValue", "> [Wrap value] ");
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return indent + toString();
    }
}
