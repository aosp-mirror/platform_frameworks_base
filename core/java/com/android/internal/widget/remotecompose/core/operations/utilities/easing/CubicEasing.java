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

class CubicEasing extends Easing {
    float mType = 0;
    float mX1 = 0f;
    float mY1 = 0f;
    float mX2 = 0f;
    float mY2 = 0f;

    private static final float[] STANDARD = {0.4f, 0.0f, 0.2f, 1f};
    private static final float[] ACCELERATE = {0.4f, 0.05f, 0.8f, 0.7f};
    private static final float[] DECELERATE = {0.0f, 0.0f, 0.2f, 0.95f};
    private static final float[] LINEAR = {1f, 1f, 0f, 0f};
    private static final float[] ANTICIPATE = {0.36f, 0f, 0.66f, -0.56f};
    private static final float[] OVERSHOOT = {0.34f, 1.56f, 0.64f, 1f};

    CubicEasing(int type) {
        mType = type;
        config(type);
    }

    CubicEasing(float x1, float y1, float x2, float y2) {
        setup(x1, y1, x2, y2);
    }

    public void config(int type) {

        switch (type) {
            case CUBIC_STANDARD:
                setup(STANDARD);
                break;
            case CUBIC_ACCELERATE:
                setup(ACCELERATE);
                break;
            case CUBIC_DECELERATE:
                setup(DECELERATE);
                break;
            case CUBIC_LINEAR:
                setup(LINEAR);
                break;
            case CUBIC_ANTICIPATE:
                setup(ANTICIPATE);
                break;
            case CUBIC_OVERSHOOT:
                setup(OVERSHOOT);
                break;
        }
        mType = type;
    }

    void setup(float[] values) {
        setup(values[0], values[1], values[2], values[3]);
    }

    void setup(float x1, float y1, float x2, float y2) {
        mX1 = x1;
        mY1 = y1;
        mX2 = x2;
        mY2 = y2;
    }

    private float getX(float t) {
        float t1 = 1 - t;
        // no need for because start at 0,0 float f0 = (1 - t) * (1 - t) * (1 - t)
        float f1 = 3 * t1 * t1 * t;
        float f2 = 3 * t1 * t * t;
        float f3 = t * t * t;
        return mX1 * f1 + mX2 * f2 + f3;
    }

    private float getY(float t) {
        float t1 = 1 - t;
        // no need for testing because start at 0,0 float f0 = (1 - t) * (1 - t) * (1 - t)
        float f1 = 3 * t1 * t1 * t;
        float f2 = 3 * t1 * t * t;
        float f3 = t * t * t;
        return mY1 * f1 + mY2 * f2 + f3;
    }

    private float getDiffX(float t) {
        float t1 = 1 - t;
        return 3 * t1 * t1 * mX1 + 6 * t1 * t * (mX2 - mX1) + 3 * t * t * (1 - mX2);
    }

    private float getDiffY(float t) {
        float t1 = 1 - t;
        return 3 * t1 * t1 * mY1 + 6 * t1 * t * (mY2 - mY1) + 3 * t * t * (1 - mY2);
    }

    /**
     * binary search for the region and linear interpolate the answer
     */
    public float getDiff(float x) {
        float t = 0.5f;
        float range = 0.5f;
        while (range > D_ERROR) {
            float tx = getX(t);
            range *= 0.5;
            if (tx < x) {
                t += range;
            } else {
                t -= range;
            }
        }
        float x1 = getX(t - range);
        float x2 = getX(t + range);
        float y1 = getY(t - range);
        float y2 = getY(t + range);
        return (y2 - y1) / (x2 - x1);
    }

    /**
     * binary search for the region and linear interpolate the answer
     */
    public float get(float x) {
        if (x <= 0.0f) {
            return 0f;
        }
        if (x >= 1.0f) {
            return 1.0f;
        }
        float t = 0.5f;
        float range = 0.5f;
        while (range > ERROR) {
            float tx = getX(t);
            range *= 0.5f;
            if (tx < x) {
                t += range;
            } else {
                t -= range;
            }
        }
        float x1 = getX(t - range);
        float x2 = getX(t + range);
        float y1 = getY(t - range);
        float y2 = getY(t + range);
        return (y2 - y1) * (x - x1) / (x2 - x1) + y1;
    }

    private static final float ERROR = 0.01f;
    private static final float D_ERROR = 0.0001f;
}
