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

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.TouchListener;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.operations.layout.Component;
import com.android.internal.widget.remotecompose.core.operations.utilities.AnimatedFloatExpression;
import com.android.internal.widget.remotecompose.core.operations.utilities.NanMap;
import com.android.internal.widget.remotecompose.core.operations.utilities.touch.VelocityEasing;

import java.util.Arrays;
import java.util.List;

/**
 * Operation to deal with Touch handling (typically on canvas) This support handling of many typical
 * touch behaviours. Including animating to Notched, positions. and tweaking the dynamics of the
 * animation.
 */
public class TouchExpression implements Operation, VariableSupport, TouchListener {
    private static final int OP_CODE = Operations.TOUCH_EXPRESSION;
    private static final String CLASS_NAME = "TouchExpression";
    private float mDefValue;
    private float mOutDefValue;
    public int mId;
    public float[] mSrcExp;
    int mMode = 1; // 0 = delta, 1 = absolute
    float mMax = 1;
    float mMin = 1;
    float mOutMax = 1;
    float mOutMin = 1;
    float mValue = 0;
    boolean mUnmodified = true;
    public float[] mPreCalcValue;
    private float mLastChange = Float.NaN;
    private float mLastCalculatedValue = Float.NaN;
    AnimatedFloatExpression mExp = new AnimatedFloatExpression();
    public static final int MAX_EXPRESSION_SIZE = 32;
    private VelocityEasing mEasyTouch = new VelocityEasing();
    private boolean mEasingToStop = false;
    private float mTouchUpTime = 0;
    private float mCurrentValue = Float.NaN;
    private boolean mTouchDown = false;
    float mMaxTime = 1;
    float mMaxAcceleration = 5;
    float mMaxVelocity = 7;
    int mStopMode = 0;
    boolean mWrapMode = false;
    float[] mNotches;
    float[] mStopSpec;
    int mTouchEffects;
    float mVelocityId;

    public static final int STOP_GENTLY = 0;
    public static final int STOP_ENDS = 2;
    public static final int STOP_INSTANTLY = 1;
    public static final int STOP_NOTCHES_EVEN = 3;
    public static final int STOP_NOTCHES_PERCENTS = 4;
    public static final int STOP_NOTCHES_ABSOLUTE = 5;
    public static final int STOP_ABSOLUTE_POS = 6;

    public TouchExpression(
            int id,
            float[] exp,
            float defValue,
            float min,
            float max,
            int touchEffects,
            float velocityId,
            int stopMode,
            float[] stopSpec,
            float[] easingSpec) {
        this.mId = id;
        this.mSrcExp = exp;
        mOutDefValue = mDefValue = defValue;
        mMode = STOP_ABSOLUTE_POS == stopMode ? 1 : 0;
        mOutMax = mMax = max;
        mTouchEffects = touchEffects;
        mVelocityId = velocityId;
        if (Float.isNaN(min) && Utils.idFromNan(min) == 0) {
            mWrapMode = true;
        } else {
            mOutMin = mMin = min;
        }
        mStopMode = stopMode;
        mStopSpec = stopSpec;
        if (easingSpec != null) {
            Utils.log("easingSpec  " + Arrays.toString(easingSpec));
            if (easingSpec.length >= 4) {
                if (Float.floatToRawIntBits(easingSpec[0]) == 0) {
                    Utils.log("easingSpec[2]  " + easingSpec[2]);
                    mMaxTime = easingSpec[1];
                    mMaxAcceleration = easingSpec[2];
                    mMaxVelocity = easingSpec[3];
                }
            }
        }
    }

    @Override
    public void updateVariables(RemoteContext context) {

        if (mPreCalcValue == null || mPreCalcValue.length != mSrcExp.length) {
            mPreCalcValue = new float[mSrcExp.length];
        }
        if (Float.isNaN(mMax)) {
            mOutMax = context.getFloat(Utils.idFromNan(mMax));
        }
        if (Float.isNaN(mMin)) {
            mOutMin = context.getFloat(Utils.idFromNan(mMin));
        }
        if (Float.isNaN(mDefValue)) {
            mOutDefValue = context.getFloat(Utils.idFromNan(mDefValue));
        }

        boolean value_changed = false;
        for (int i = 0; i < mSrcExp.length; i++) {
            float v = mSrcExp[i];
            if (Float.isNaN(v)
                    && !AnimatedFloatExpression.isMathOperator(v)
                    && !NanMap.isDataVariable(v)) {
                float newValue = context.getFloat(Utils.idFromNan(v));

                mPreCalcValue[i] = newValue;

            } else {
                mPreCalcValue[i] = mSrcExp[i];
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
    }

    @Override
    public void registerListening(RemoteContext context) {
        if (Float.isNaN(mMax)) {
            context.listensTo(Utils.idFromNan(mMax), this);
        }
        if (Float.isNaN(mMin)) {
            context.listensTo(Utils.idFromNan(mMin), this);
        }
        if (Float.isNaN(mDefValue)) {
            context.listensTo(Utils.idFromNan(mDefValue), this);
        }
        context.addTouchListener(this);
        for (float v : mSrcExp) {
            if (Float.isNaN(v)
                    && !AnimatedFloatExpression.isMathOperator(v)
                    && !NanMap.isDataVariable(v)) {
                context.listensTo(Utils.idFromNan(v), this);
            }
        }
    }

    private float wrap(float pos) {
        if (!mWrapMode) {
            return pos;
        }
        pos = pos % mOutMax;
        if (pos < 0) {
            pos += mOutMax;
        }
        return pos;
    }

    private float getStopPosition(float pos, float slope) {
        float target = pos + slope / mMaxAcceleration;
        if (mWrapMode) {
            pos = wrap(pos);
            target = pos += +slope / mMaxAcceleration;
        } else {
            target = Math.max(Math.min(target, mOutMax), mOutMin);
        }
        float[] positions = new float[mStopSpec.length];
        float min = (mWrapMode) ? 0 : mOutMin;

        switch (mStopMode) {
            case STOP_ENDS:
                return ((pos + slope) > (mOutMax + min) / 2) ? mOutMax : min;
            case STOP_INSTANTLY:
                return pos;
            case STOP_NOTCHES_EVEN:
                int evenSpacing = (int) mStopSpec[0];
                float step = (mOutMax - min) / evenSpacing;

                float notch = min + step * (int) (0.5f + (target - mOutMin) / step);

                notch = Math.max(Math.min(notch, mOutMax), min);
                return notch;
            case STOP_NOTCHES_PERCENTS:
                positions = new float[mStopSpec.length];
                float minPos = min;
                float minPosDist = Math.abs(mOutMin - target);
                for (int i = 0; i < positions.length; i++) {
                    float p = mOutMin + mStopSpec[i] * (mOutMax - mOutMin);
                    float dist = Math.abs(p - target);
                    if (minPosDist > dist) {
                        minPosDist = dist;
                        minPos = p;
                    }
                }
                return minPos;
            case STOP_NOTCHES_ABSOLUTE:
                positions = mStopSpec;
                minPos = mOutMin;
                minPosDist = Math.abs(mOutMin - target);
                for (int i = 0; i < positions.length; i++) {
                    float dist = Math.abs(positions[i] - target);
                    if (minPosDist > dist) {
                        minPosDist = dist;
                        minPos = positions[i];
                    }
                }
                return minPos;
            case STOP_GENTLY:
            default:
                return target;
        }
    }

    void haptic(RemoteContext context) {
        int touch = ((mTouchEffects) & 0xFF);
        if ((mTouchEffects & (1 << 15)) != 0) {
            touch = context.getInteger(mTouchEffects & 0x7FFF);
        }

        context.hapticEffect(touch);
    }

    float mLastValue = 0;

    void crossNotchCheck(RemoteContext context) {
        float prev = mLastValue;
        float next = mCurrentValue;
        mLastValue = next;

        //        System.out.println(mStopMode + "    " + prev + "  -> " + next);
        float min = (mWrapMode) ? 0 : mOutMin;
        float max = mOutMax;

        switch (mStopMode) {
            case STOP_ENDS:
                if (((min - prev) * (max - prev) < 0) ^ ((min - next) * (max - next)) < 0) {
                    haptic(context);
                }
                break;
            case STOP_INSTANTLY:
                haptic(context);
                break;
            case STOP_NOTCHES_EVEN:
                int evenSpacing = (int) mStopSpec[0];
                float step = (max - min) / evenSpacing;
                if ((int) ((prev - min) / step) != (int) ((next - min) / step)) {
                    haptic(context);
                }
                break;
            case STOP_NOTCHES_PERCENTS:
                for (int i = 0; i < mStopSpec.length; i++) {
                    float p = mOutMin + mStopSpec[i] * (mOutMax - mOutMin);
                    if ((prev - p) * (next - p) < 0) {
                        haptic(context);
                    }
                }
                break;
            case STOP_NOTCHES_ABSOLUTE:
                for (int i = 0; i < mStopSpec.length; i++) {
                    float p = mStopSpec[i];
                    if ((prev - p) * (next - p) < 0) {
                        haptic(context);
                    }
                }
                break;
            case STOP_GENTLY:
        }
    }

    float mScrLeft, mScrRight, mScrTop, mScrBottom;

    @Override
    public void apply(RemoteContext context) {
        Component comp = context.lastComponent;
        if (comp != null) {
            float x = comp.getX();
            float y = comp.getY();
            float w = comp.getWidth();
            float h = comp.getHeight();
            comp = comp.getParent();
            while (comp != null) {
                x += comp.getX();
                y += comp.getY();
                comp = comp.getParent();
            }
            mScrLeft = x;
            mScrTop = y;
            mScrRight = w + x;
            mScrBottom = h + y;
        }
        updateVariables(context);
        if (mUnmodified) {
            mCurrentValue = mOutDefValue;

            context.loadFloat(mId, wrap(mCurrentValue));
            return;
        }
        if (mEasingToStop) {
            float time = context.getAnimationTime() - mTouchUpTime;
            float value = mEasyTouch.getPos(time);
            mCurrentValue = value;
            value = wrap(value);
            context.loadFloat(mId, value);
            if (mEasyTouch.getDuration() < time) {
                mEasingToStop = false;
            }
            crossNotchCheck(context);
            return;
        }
        if (mTouchDown) {
            float value =
                    mExp.eval(context.getCollectionsAccess(), mPreCalcValue, mPreCalcValue.length);
            if (mMode == 0) {
                value = mValueAtDown + (value - mDownTouchValue);
            }
            if (mWrapMode) {
                value = wrap(value);
            } else {
                value = Math.min(Math.max(value, mOutMin), mOutMax);
            }
            mCurrentValue = value;
        }
        crossNotchCheck(context);
        context.loadFloat(mId, wrap(mCurrentValue));
    }

    float mValueAtDown; // The currently "displayed" value at down
    float mDownTouchValue; // The calculated value at down

    @Override
    public void touchDown(RemoteContext context, float x, float y) {

        if (!(x >= mScrLeft && x <= mScrRight && y >= mScrTop && y <= mScrBottom)) {
            Utils.log("NOT IN WINDOW " + x + ", " + y + " " + mScrLeft + ", " + mScrTop);
            return;
        }
        mTouchDown = true;
        mUnmodified = false;
        if (mMode == 0) {
            mValueAtDown = context.getFloat(mId);
            mDownTouchValue =
                    mExp.eval(context.getCollectionsAccess(), mPreCalcValue, mPreCalcValue.length);
        }
    }

    @Override
    public void touchUp(RemoteContext context, float x, float y, float dx, float dy) {
        // calculate the slope (using small changes)
        if (!mTouchDown) {
            return;
        }
        mTouchDown = false;
        float dt = 0.0001f;
        if (mStopMode == STOP_INSTANTLY) {
            return;
        }
        float v = mExp.eval(context.getCollectionsAccess(), mPreCalcValue, mPreCalcValue.length);
        for (int i = 0; i < mSrcExp.length; i++) {
            if (Float.isNaN(mSrcExp[i])) {
                int id = Utils.idFromNan(mSrcExp[i]);
                if (id == RemoteContext.ID_TOUCH_POS_X) {
                    mPreCalcValue[i] = x + dx * dt;
                } else if (id == RemoteContext.ID_TOUCH_POS_Y) {
                    mPreCalcValue[i] = y + dy * dt;
                }
            }
        }
        float vdt = mExp.eval(context.getCollectionsAccess(), mPreCalcValue, mPreCalcValue.length);
        float slope = (vdt - v) / dt; // the rate of change with respect to the dx,dy movement
        float value = context.getFloat(mId);

        mTouchUpTime = context.getAnimationTime();

        float dest = getStopPosition(value, slope);
        mEasyTouch.config(value, dest, slope, mMaxTime, mMaxAcceleration, mMaxVelocity, null);
        mEasingToStop = true;
    }

    @Override
    public void touchDrag(RemoteContext context, float x, float y) {
        if (!mTouchDown) {
            return;
        }
        apply(context);
        context.getDocument().getRootLayoutComponent().needsRepaint();
    }

    @Override
    public void write(WireBuffer buffer) {
        apply(
                buffer,
                mId,
                mValue,
                mMin,
                mMax,
                mVelocityId,
                mTouchEffects,
                mSrcExp,
                mStopMode,
                mNotches,
                null);
    }

    @Override
    public String toString() {
        String[] labels = new String[mSrcExp.length];
        for (int i = 0; i < mSrcExp.length; i++) {
            if (Float.isNaN(mSrcExp[i])) {
                labels[i] = "[" + Utils.idStringFromNan(mSrcExp[i]) + "]";
            }
        }
        if (mPreCalcValue == null) {
            return CLASS_NAME
                    + "["
                    + mId
                    + "] = ("
                    + AnimatedFloatExpression.toString(mSrcExp, labels)
                    + ")";
        }
        return CLASS_NAME
                + "["
                + mId
                + "] = ("
                + AnimatedFloatExpression.toString(mPreCalcValue, labels)
                + ")";
    }

    // ===================== static ======================

    public static String name() {
        return CLASS_NAME;
    }

    public static int id() {
        return OP_CODE;
    }

    /**
     * Writes out the operation to the buffer
     *
     * @param buffer The buffer to write to
     * @param id the id of the resulting float
     * @param value the float expression array
     */
    public static void apply(
            WireBuffer buffer,
            int id,
            float value,
            float min,
            float max,
            float velocityId,
            int touchEffects,
            float[] exp,
            int touchMode,
            float[] touchSpec,
            float[] easingSpec) {
        buffer.start(OP_CODE);
        buffer.writeInt(id);
        buffer.writeFloat(value);
        buffer.writeFloat(min);
        buffer.writeFloat(max);
        buffer.writeFloat(velocityId);
        buffer.writeInt(touchEffects);
        buffer.writeInt(exp.length);
        for (float v : exp) {
            buffer.writeFloat(v);
        }
        int len = 0;
        if (touchSpec != null) {
            len = touchSpec.length;
        }
        buffer.writeInt((touchMode << 16) | len);
        for (int i = 0; i < len; i++) {
            buffer.writeFloat(touchSpec[i]);
        }

        if (easingSpec != null) {
            len = easingSpec.length;
        } else {
            len = 0;
        }
        buffer.writeInt(len);
        for (int i = 0; i < len; i++) {
            buffer.writeFloat(easingSpec[i]);
        }
    }

    public static void read(WireBuffer buffer, List<Operation> operations) {
        int id = buffer.readInt();
        float startValue = buffer.readFloat();
        float min = buffer.readFloat();
        float max = buffer.readFloat();
        float velocityId = buffer.readFloat(); // TODO future support
        int touchEffects = buffer.readInt();
        int len = buffer.readInt();
        int valueLen = len & 0xFFFF;
        if (valueLen > MAX_EXPRESSION_SIZE) {
            throw new RuntimeException("Float expression to long");
        }
        float[] exp = new float[valueLen];
        for (int i = 0; i < exp.length; i++) {
            exp[i] = buffer.readFloat();
        }
        int stopLogic = buffer.readInt();
        int stopLen = stopLogic & 0xFFFF;
        int stopMode = stopLogic >> 16;

        Utils.log("stopMode " + stopMode + " stopLen " + stopLen);
        float[] stopsData = new float[stopLen];
        for (int i = 0; i < stopsData.length; i++) {
            stopsData[i] = buffer.readFloat();
        }
        int easingLen = buffer.readInt();

        float[] easingData = new float[easingLen];
        for (int i = 0; i < easingData.length; i++) {
            easingData[i] = buffer.readFloat();
        }

        operations.add(
                new TouchExpression(
                        id,
                        exp,
                        startValue,
                        min,
                        max,
                        touchEffects,
                        velocityId,
                        stopMode,
                        stopsData,
                        easingData));
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Expressions Operations", OP_CODE, CLASS_NAME)
                .description("A Float expression")
                .field(INT, "id", "The id of the Color")
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

    @Override
    public String deepToString(String indent) {
        return indent + toString();
    }
}
