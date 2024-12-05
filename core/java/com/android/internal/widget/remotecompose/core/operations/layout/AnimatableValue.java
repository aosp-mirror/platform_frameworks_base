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

import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.operations.Utils;
import com.android.internal.widget.remotecompose.core.operations.utilities.easing.FloatAnimation;
import com.android.internal.widget.remotecompose.core.operations.utilities.easing.GeneralEasing;

/** Value animation for layouts */
public class AnimatableValue {
    boolean mIsVariable = false;
    int mId = 0;
    float mValue = 0f;

    boolean mAnimate = false;
    long mAnimateTargetTime = 0;
    float mAnimateDuration = 300f;
    float mTargetRotationX;
    float mStartRotationX;

    int mMotionEasingType = GeneralEasing.CUBIC_STANDARD;
    FloatAnimation mMotionEasing;

    /**
     * Value to animate
     *
     * @param value value
     */
    public AnimatableValue(float value) {
        if (Utils.isVariable(value)) {
            mId = Utils.idFromNan(value);
            mIsVariable = true;
        } else {
            mValue = value;
        }
    }

    /**
     * Get the value
     *
     * @return the value
     */
    public float getValue() {
        return mValue;
    }

    /**
     * Evaluate going through FloatAnimation if needed
     *
     * @param context the paint context
     * @return the current value
     */
    public float evaluate(PaintContext context) {
        if (!mIsVariable) {
            return mValue;
        }
        float value = context.getContext().mRemoteComposeState.getFloat(mId);

        if (value != mValue && !mAnimate) {
            // animate
            mStartRotationX = mValue;
            mTargetRotationX = value;
            mAnimate = true;
            mAnimateTargetTime = System.currentTimeMillis();
            mMotionEasing =
                    new FloatAnimation(
                            mMotionEasingType, mAnimateDuration / 1000f, null, 0f, Float.NaN);
            mMotionEasing.setTargetValue(1f);
        }
        if (mAnimate) {
            float elapsed = System.currentTimeMillis() - mAnimateTargetTime;
            float p = mMotionEasing.get(elapsed / mAnimateDuration);
            mValue = (1 - p) * mStartRotationX + p * mTargetRotationX;
            if (p >= 1f) {
                mAnimate = false;
            }
        } else {
            mValue = mTargetRotationX;
        }

        return mValue;
    }

    @Override
    public String toString() {
        return "AnimatableValue{mId=" + mId + "}";
    }
}
