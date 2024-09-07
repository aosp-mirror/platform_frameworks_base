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
package com.android.internal.widget.remotecompose.core.operations.utilities.easing;

/**
 * Provides and interface to create easing functions
 */
public class GeneralEasing extends  Easing{
    float[] mEasingData = new float[0];
    Easing mEasingCurve = new CubicEasing(CUBIC_STANDARD);

    /**
     * Set the curve based on the float encoding of it
     * @param data
     */
    public void setCurveSpecification(float[] data) {
        mEasingData = data;
        createEngine();
    }

    public float[] getCurveSpecification() {
        return mEasingData;
    }

    void createEngine() {
        int type = Float.floatToRawIntBits(mEasingData[0]);
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
                mEasingCurve = new CubicEasing(mEasingData[1],
                        mEasingData[2],
                        mEasingData[3],
                        mEasingData[5]
                );
                break;
            case EASE_OUT_BOUNCE:
                mEasingCurve = new BounceCurve(type);
                break;
        }
    }

    /**
     * get the value at point x
     */
    public float get(float x) {
        return mEasingCurve.get(x);
    }

    /**
     * get the slope of the easing function at at x
     */
    public float getDiff(float x) {
        return mEasingCurve.getDiff(x);
    }

    public int getType() {
        return mEasingCurve.getType();
    }


}
