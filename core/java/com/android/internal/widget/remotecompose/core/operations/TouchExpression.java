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
import com.android.internal.widget.remotecompose.core.TouchListener;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.operations.layout.Component;
import com.android.internal.widget.remotecompose.core.operations.layout.RootLayoutComponent;
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
public class TouchExpression extends Operation implements VariableSupport, TouchListener {
    private static final int OP_CODE = Operations.TOUCH_EXPRESSION;
    private static final String CLASS_NAME = "TouchExpression";
    private float mDefValue;
    private float mOutDefValue;
    private int mId;
    public float[] mSrcExp;
    int mMode = 1; // 0 = delta, 1 = absolute
    float mMax = 1;
    float mMin = 1;
    float mOutMax = 1;
    float mOutMin = 1;
    float mValue = 0;
    boolean mUnmodified = true;
    private float[] mPreCalcValue;
    private float mLastChange = Float.NaN;
    private float mLastCalculatedValue = Float.NaN;
    AnimatedFloatExpression mExp = new AnimatedFloatExpression();

    /** The maximum number of floats in the expression */
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
    float[] mOutStopSpec;
    int mTouchEffects;
    float mVelocityId;

    /** Stop with some deceleration */
    public static final int STOP_GENTLY = 0;

    /** Stop only at the start or end */
    public static final int STOP_ENDS = 2;

    /** Stop on touch up */
    public static final int STOP_INSTANTLY = 1;

    /** Stop at evenly spaced notches */
    public static final int STOP_NOTCHES_EVEN = 3;

    /** Stop at a collection points described in percents of the range */
    public static final int STOP_NOTCHES_PERCENTS = 4;

    /** Stop at a collectiond of point described in abslute cordnates */
    public static final int STOP_NOTCHES_ABSOLUTE = 5;

    /** Jump to the absloute poition of the point */
    public static final int STOP_ABSOLUTE_POS = 6;

    /**
     * create a touch expression
     *
     * @param id The float id the value is output to
     * @param exp the expression (containing TOUCH_* )
     * @param defValue the default value
     * @param min the minimum value
     * @param max the maximum value
     * @param touchEffects the type of touch mode
     * @param velocityId the valocity (not used)
     * @param stopMode the behavour on touch oup
     * @param stopSpec the paraameters that affect the touch up behavour
     * @param easingSpec the easing parameters for coming to a stop
     */
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
        if (stopSpec != null) {
            mOutStopSpec = Arrays.copyOf(stopSpec, stopSpec.length);
        }
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
            if (easingSpec.length >= 4) {
                if (Float.floatToRawIntBits(easingSpec[0]) == 0) {
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
        if (mOutStopSpec == null || mOutStopSpec.length != mStopSpec.length) {
            mOutStopSpec = new float[mStopSpec.length];
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
        for (int i = 0; i < mStopSpec.length; i++) {
            float v = mStopSpec[i];
            if (Float.isNaN(v)) {
                float newValue = context.getFloat(Utils.idFromNan(v));
                mOutStopSpec[i] = newValue;
            } else {
                mOutStopSpec[i] = v;
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
        if (mComponent == null) {
            context.addTouchListener(this);
        }
        for (float v : mSrcExp) {
            if (Float.isNaN(v)
                    && !AnimatedFloatExpression.isMathOperator(v)
                    && !NanMap.isDataVariable(v)) {
                context.listensTo(Utils.idFromNan(v), this);
            }
        }
        for (float v : mStopSpec) {
            if (Float.isNaN(v)) {
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
                int evenSpacing = (int) mOutStopSpec[0];
                float notchMax = (mOutStopSpec.length > 1) ? mOutStopSpec[1] : mOutMax;
                float step = (notchMax - min) / evenSpacing;

                float notch = min + step * (int) (0.5f + (target - mOutMin) / step);
                if (!mWrapMode) {
                    notch = Math.max(Math.min(notch, mOutMax), min);
                }
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

    @Nullable Component mComponent;

    /**
     * Set the component the touch expression is in (if any)
     *
     * @param component the component, or null if outside
     */
    public void setComponent(@Nullable Component component) {
        mComponent = component;
        if (mComponent != null) {
            try {
                RootLayoutComponent root = mComponent.getRoot();
                root.setHasTouchListeners(true);
            } catch (Exception e) {
            }
        }
    }

    private void updateBounds() {
        Component comp = mComponent;
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
    }

    @Override
    public void apply(RemoteContext context) {
        updateBounds();
        if (mUnmodified) {
            mCurrentValue = mOutDefValue;
            context.loadFloat(mId, wrap(mCurrentValue));
            return;
        }
        if (mEasingToStop) {
            float time = context.getAnimationTime() - mTouchUpTime;
            float value = mEasyTouch.getPos(time);
            mCurrentValue = value;
            if (mWrapMode) {
                value = wrap(value);
            } else {
                value = Math.min(Math.max(value, mOutMin), mOutMax);
            }
            context.loadFloat(mId, value);
            if (mEasyTouch.getDuration() < time) {
                mEasingToStop = false;
            }
            crossNotchCheck(context);
            context.needsRepaint();
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
        mEasingToStop = false;
        mTouchDown = true;
        mUnmodified = false;
        if (mMode == 0) {
            mValueAtDown = context.getFloat(mId);
            mDownTouchValue =
                    mExp.eval(context.getCollectionsAccess(), mPreCalcValue, mPreCalcValue.length);
        }
        context.needsRepaint();
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
        float time = mMaxTime * Math.abs(dest - value) / (2 * mMaxVelocity);
        mEasyTouch.config(value, dest, slope, time, mMaxAcceleration, mMaxVelocity, null);
        mEasingToStop = true;
        context.needsRepaint();
    }

    @Override
    public void touchDrag(RemoteContext context, float x, float y) {
        if (!mTouchDown) {
            return;
        }
        apply(context);
        context.needsRepaint();
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
     * @param min the minimum allowed value
     * @param max the maximum allowed value
     * @param velocityId the velocity id
     * @param touchEffects the type touch effect
     * @param exp the expression the maps touch drags to movement
     * @param touchMode the touch mode e.g. notch modes
     * @param touchSpec the spec of the touch modes
     * @param easingSpec the spec of when the object comes to an easing
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

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
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

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
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

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return indent + toString();
    }
}
