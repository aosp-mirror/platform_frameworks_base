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
package com.android.internal.widget.remotecompose.core.operations.utilities.easing;

import android.annotation.NonNull;
import android.annotation.Nullable;

/** Support Animation of the FloatExpression */
public class FloatAnimation extends Easing {
    float[] mSpec;
    // mSpec[0] = duration
    // int(mSpec[1]) = num_of_param << 16 | type
    // mSpec[2..1+num_of_param] params
    // mSpec[2+num_of_param] starting Value
    Easing mEasingCurve;

    private float mDuration = 1;
    private float mWrap = Float.NaN;
    private float mInitialValue = Float.NaN;
    private float mTargetValue = Float.NaN;
    //    private float mScale = 1;
    float mOffset = 0;

    @NonNull
    @Override
    public String toString() {

        String str = "type " + mType;
        if (!Float.isNaN(mInitialValue)) {
            str += " " + mInitialValue;
        }
        if (!Float.isNaN(mTargetValue)) {
            str += " -> " + mTargetValue;
        }
        if (!Float.isNaN(mWrap)) {
            str += "  % " + mWrap;
        }

        return str;
    }

    public FloatAnimation() {
        mType = CUBIC_STANDARD;
        mEasingCurve = new CubicEasing(mType);
    }

    public FloatAnimation(@NonNull float... description) {
        mType = CUBIC_STANDARD;
        setAnimationDescription(description);
    }

    public FloatAnimation(
            int type,
            float duration,
            @Nullable float[] description,
            float initialValue,
            float wrap) {
        mType = CUBIC_STANDARD;
        setAnimationDescription(packToFloatArray(duration, type, description, initialValue, wrap));
    }

    /**
     * packs spec into a float array
     *
     * @param duration
     * @param type
     * @param spec
     * @param initialValue
     * @return
     */
    public static @NonNull float[] packToFloatArray(
            float duration, int type, @Nullable float[] spec, float initialValue, float wrap) {
        int count = 0;

        if (!Float.isNaN(initialValue)) {
            count++;
        }
        if (spec != null) {

            count++;
        }
        if (spec != null || type != CUBIC_STANDARD) {
            count++;
            count += (spec == null) ? 0 : spec.length;
        }

        if (!Float.isNaN(initialValue)) {
            count++;
        }
        if (!Float.isNaN(wrap)) {
            count++;
        }
        if (duration != 1 || count > 0) {
            count++;
        }
        if (!Float.isNaN(wrap) || !Float.isNaN(initialValue)) {
            count++;
        }
        float[] ret = new float[count];
        int pos = 0;
        int specLen = (spec == null) ? 0 : spec.length;

        if (ret.length > 0) {
            ret[pos++] = duration;
        }
        if (ret.length > 1) {
            int wrapBit = Float.isNaN(wrap) ? 0 : 1;
            int initBit = Float.isNaN(initialValue) ? 0 : 2;
            int bits = type | ((wrapBit | initBit) << 8);
            ret[pos++] = Float.intBitsToFloat(specLen << 16 | bits);
        }

        if (specLen > 0) {
            System.arraycopy(spec, 0, ret, pos, spec.length);
            pos += spec.length;
        }
        if (!Float.isNaN(initialValue)) {
            ret[pos++] = initialValue;
        }
        if (!Float.isNaN(wrap)) {
            ret[pos] = wrap;
        }
        return ret;
    }

    /**
     * Useful to debug the packed form of an animation string
     *
     * @param description
     * @return
     */
    public static String unpackAnimationToString(float[] description) {
        float[] mSpec = description;
        float mDuration = (mSpec.length == 0) ? 1 : mSpec[0];
        int len = 0;
        int type = 0;
        float wrapValue = Float.NaN;
        float initialValue = Float.NaN;
        if (mSpec.length > 1) {
            int num_type = Float.floatToRawIntBits(mSpec[1]);
            type = num_type & 0xFF;
            boolean wrap = ((num_type >> 8) & 0x1) > 0;
            boolean init = ((num_type >> 8) & 0x2) > 0;
            len = (num_type >> 16) & 0xFFFF;
            int off = 2 + len;
            if (init) {
                initialValue = mSpec[off++];
            }
            if (wrap) {
                wrapValue = mSpec[off];
            }
        }
        float[] params = description;
        int offset = 2;

        String typeStr = "";
        switch (type) {
            case CUBIC_STANDARD:
                typeStr = "CUBIC_STANDARD";
                break;
            case CUBIC_ACCELERATE:
                typeStr = "CUBIC_ACCELERATE";
                break;
            case CUBIC_DECELERATE:
                typeStr = "CUBIC_DECELERATE";
                break;
            case CUBIC_LINEAR:
                typeStr = "CUBIC_LINEAR";
                break;
            case CUBIC_ANTICIPATE:
                typeStr = "CUBIC_ANTICIPATE";
                break;
            case CUBIC_OVERSHOOT:
                typeStr = "CUBIC_OVERSHOOT";

                break;
            case CUBIC_CUSTOM:
                typeStr = "CUBIC_CUSTOM (";
                typeStr += params[offset + 0] + " ";
                typeStr += params[offset + 1] + " ";
                typeStr += params[offset + 2] + " ";
                typeStr += params[offset + 3] + " )";
                break;
            case EASE_OUT_BOUNCE:
                typeStr = "EASE_OUT_BOUNCE";

                break;
            case EASE_OUT_ELASTIC:
                typeStr = "EASE_OUT_ELASTIC";
                break;
            case SPLINE_CUSTOM:
                typeStr = "SPLINE_CUSTOM (";
                for (int i = offset; i < offset + len; i++) {
                    typeStr += params[i] + " ";
                }
                typeStr += ")";
                break;
        }

        String str = mDuration + " " + typeStr;
        if (!Float.isNaN(initialValue)) {
            str += " init =" + initialValue;
        }
        if (!Float.isNaN(wrapValue)) {
            str += " wrap =" + wrapValue;
        }
        return str;
    }

    /**
     * Create an animation based on a float encoding of the animation
     *
     * @param description
     */
    public void setAnimationDescription(@NonNull float[] description) {
        mSpec = description;
        mDuration = (mSpec.length == 0) ? 1 : mSpec[0];
        int len = 0;
        if (mSpec.length > 1) {
            int num_type = Float.floatToRawIntBits(mSpec[1]);
            mType = num_type & 0xFF;
            boolean wrap = ((num_type >> 8) & 0x1) > 0;
            boolean init = ((num_type >> 8) & 0x2) > 0;
            len = (num_type >> 16) & 0xFFFF;
            int off = 2 + len;
            if (init) {
                mInitialValue = mSpec[off++];
            }
            if (wrap) {
                mWrap = mSpec[off];
            }
        }
        create(mType, description, 2, len);
    }

    private void create(int type, @Nullable float[] params, int offset, int len) {
        switch (type) {
            case CUBIC_STANDARD:
            case CUBIC_ACCELERATE:
            case CUBIC_DECELERATE:
            case CUBIC_LINEAR:
            case CUBIC_ANTICIPATE:
            case CUBIC_OVERSHOOT:
                mEasingCurve = new CubicEasing(type);
                break;
            case CUBIC_CUSTOM:
                mEasingCurve =
                        new CubicEasing(
                                params[offset + 0],
                                params[offset + 1],
                                params[offset + 2],
                                params[offset + 3]);
                break;
            case EASE_OUT_BOUNCE:
                mEasingCurve = new BounceCurve(type);
                break;
            case EASE_OUT_ELASTIC:
                mEasingCurve = new ElasticOutCurve();
                break;
            case SPLINE_CUSTOM:
                mEasingCurve = new StepCurve(params, offset, len);
                break;
        }
    }

    /**
     * Get the duration the interpolate is to take
     *
     * @return duration in seconds
     */
    public float getDuration() {
        return mDuration;
    }

    /**
     * Set the initial Value
     *
     * @param value
     */
    public void setInitialValue(float value) {

        if (Float.isNaN(mWrap)) {
            mInitialValue = value;
        } else {
            mInitialValue = value % mWrap;
        }
        setScaleOffset();
    }

    private static float wrap(float wrap, float value) {
        value = value % wrap;
        if (value < 0) {
            value += wrap;
        }
        return value;
    }

    float wrapDistance(float wrap, float from, float to) {
        float delta = (to - from) % 360;
        if (delta < -wrap / 2) {
            delta += wrap;
        } else if (delta > wrap / 2) {
            delta -= wrap;
        }
        return delta;
    }

    /**
     * Set the target value to interpolate to
     *
     * @param value
     */
    public void setTargetValue(float value) {
        mTargetValue = value;
        if (!Float.isNaN(mWrap)) {
            mInitialValue = wrap(mWrap, mInitialValue);
            mTargetValue = wrap(mWrap, mTargetValue);
            if (Float.isNaN(mInitialValue)) {
                mInitialValue = mTargetValue;
            }

            float dist = wrapDistance(mWrap, mInitialValue, mTargetValue);
            if ((dist > 0) && (mTargetValue < mInitialValue)) {
                mTargetValue += mWrap;
            } else if ((dist < 0) && (mTargetValue > mInitialValue)) {
                mTargetValue -= mWrap;
            }
        }
        setScaleOffset();
    }

    public float getTargetValue() {
        return mTargetValue;
    }

    private void setScaleOffset() {
        if (!Float.isNaN(mInitialValue) && !Float.isNaN(mTargetValue)) {
            //            mScale = (mTargetValue - mInitialValue); // TODO: commented out because
            // unused.
            mOffset = mInitialValue;
        } else {
            //            mScale = 1; // TODO: commented out because its unused
            mOffset = 0;
        }
    }

    /** get the value at time t in seconds since start */
    @Override
    public float get(float t) {
        return mEasingCurve.get(t / mDuration) * (mTargetValue - mInitialValue) + mInitialValue;
    }

    /** get the slope of the easing function at at x */
    @Override
    public float getDiff(float t) {
        return mEasingCurve.getDiff(t / mDuration) * (mTargetValue - mInitialValue);
    }

    public float getInitialValue() {
        return mInitialValue;
    }
}
