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

import com.android.internal.widget.remotecompose.core.CompanionOperation;
import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.operations.utilities.AnimatedFloatExpression;
import com.android.internal.widget.remotecompose.core.operations.utilities.easing.FloatAnimation;

import java.util.Arrays;
import java.util.List;

/**
 * Operation to deal with AnimatedFloats
 * This is designed to be an optimized calculation for things like
 * injecting the width of the component int draw rect
 * As well as supporting generalized animation floats.
 * The floats represent a RPN style calculator
 */
public class FloatExpression implements Operation, VariableSupport {
    public int mId;
    public float[] mSrcValue;
    public float[] mSrcAnimation;
    public FloatAnimation mFloatAnimation;
    public float[] mPreCalcValue;
    private float mLastChange = Float.NaN;
    AnimatedFloatExpression mExp = new AnimatedFloatExpression();
    public static final Companion COMPANION = new Companion();
    public static final int MAX_STRING_SIZE = 4000;

    public FloatExpression(int id, float[] value, float[] animation) {
        this.mId = id;
        this.mSrcValue = value;
        this.mSrcAnimation = animation;
        if (mSrcAnimation != null) {
            mFloatAnimation = new FloatAnimation(mSrcAnimation);
        }
    }

    @Override
    public void updateVariables(RemoteContext context) {
        if (mPreCalcValue == null || mPreCalcValue.length != mSrcValue.length) {
            mPreCalcValue = new float[mSrcValue.length];
        }
        //Utils.log("updateVariables ");
        boolean value_changed = false;
        for (int i = 0; i < mSrcValue.length; i++) {
            float v = mSrcValue[i];
            if (Float.isNaN(v) && !AnimatedFloatExpression.isMathOperator(v)) {
                float newValue = context.getFloat(Utils.idFromNan(v));
                if (mFloatAnimation != null) {
                    if (mPreCalcValue[i] != newValue) {
                        mLastChange = context.getAnimationTime();
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
        if (value_changed && mFloatAnimation != null) {
            float v = mExp.eval(Arrays.copyOf(mPreCalcValue, mPreCalcValue.length));
            if (Float.isNaN(mFloatAnimation.getTargetValue())) {
                mFloatAnimation.setInitialValue(v);
            } else {
                mFloatAnimation.setInitialValue(mFloatAnimation.getTargetValue());
            }
            mFloatAnimation.setTargetValue(v);
        }
    }

    @Override
    public void registerListening(RemoteContext context) {
        for (int i = 0; i < mSrcValue.length; i++) {
            float v = mSrcValue[i];
            if (Float.isNaN(v) && !AnimatedFloatExpression.isMathOperator(v)) {
                context.listensTo(Utils.idFromNan(v), this);
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
        if (mFloatAnimation != null) {
            float f = mFloatAnimation.get(t - mLastChange);
            context.loadFloat(mId, f);
        } else {
            context.loadFloat(mId, mExp.eval(Arrays.copyOf(mPreCalcValue, mPreCalcValue.length)));
        }
    }

    @Override
    public void write(WireBuffer buffer) {
        COMPANION.apply(buffer, mId, mSrcValue, mSrcAnimation);
    }

    @Override
    public String toString() {
        String[] labels = new String[mSrcValue.length];
        for (int i = 0; i < mSrcValue.length; i++) {
            if (Float.isNaN(mSrcValue[i])) {
                labels[i] = "[" + Utils.idFromNan(mSrcValue[i]) + "]";
            }

        }
        return "FloatExpression[" + mId + "] = ("
                + AnimatedFloatExpression.toString(mPreCalcValue, labels) + ")";
    }

    public static class Companion implements CompanionOperation {
        private Companion() {
        }

        @Override
        public String name() {
            return "FloatExpression";
        }

        @Override
        public int id() {
            return Operations.ANIMATED_FLOAT;
        }

        /**
         * Writes out the operation to the buffer
         * @param buffer
         * @param id
         * @param value
         * @param animation
         */
        public void apply(WireBuffer buffer, int id, float[] value, float[] animation) {
            buffer.start(Operations.ANIMATED_FLOAT);
            buffer.writeInt(id);

            int len = value.length;
            if (animation != null) {
                len |= (animation.length << 16);
            }
            buffer.writeInt(len);

            for (int i = 0; i < value.length; i++) {
                buffer.writeFloat(value[i]);
            }
            if (animation != null) {
                for (int i = 0; i < animation.length; i++) {
                    buffer.writeFloat(animation[i]);
                }
            }

        }

        @Override
        public void read(WireBuffer buffer, List<Operation> operations) {
            int id = buffer.readInt();
            int len = buffer.readInt();
            int valueLen = len & 0xFFFF;
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
    }

    @Override
    public String deepToString(String indent) {
        return indent + toString();
    }

}
