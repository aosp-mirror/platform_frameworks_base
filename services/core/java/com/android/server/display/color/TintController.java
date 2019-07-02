/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.display.color;

import android.animation.ValueAnimator;
import android.content.Context;

import java.io.PrintWriter;

abstract class TintController {

    private ValueAnimator mAnimator;
    private Boolean mIsActivated;

    public ValueAnimator getAnimator() {
        return mAnimator;
    }

    public void setAnimator(ValueAnimator animator) {
        mAnimator = animator;
    }

    /**
     * Cancel the animator if it's still running.
     */
    public void cancelAnimator() {
        if (mAnimator != null) {
            mAnimator.cancel();
        }
    }

    /**
     * End the animator if it's still running, jumping to the end state.
     */
    public void endAnimator() {
        if (mAnimator != null) {
            mAnimator.end();
            mAnimator = null;
        }
    }

    public void setActivated(Boolean isActivated) {
        mIsActivated = isActivated;
    }

    public boolean isActivated() {
        return mIsActivated != null && mIsActivated;
    }

    public boolean isActivatedStateNotSet() {
        return mIsActivated == null;
    }

    /**
     * Dump debug information.
     */
    public void dump(PrintWriter pw) {
    }

    /**
     * Set up any constants needed for computing the matrix.
     */
    public abstract void setUp(Context context, boolean needsLinear);

    /**
     * Sets the 4x4 matrix to apply.
     */
    public abstract void setMatrix(int value);

    /**
     * Get the 4x4 matrix to apply.
     */
    public abstract float[] getMatrix();

    /**
     * Get the color transform level to apply the matrix.
     */
    public abstract int getLevel();

    /**
     * Returns whether or not this transform type is available on this device.
     */
    public abstract boolean isAvailable(Context context);
}
