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
import android.util.Slog;

import java.io.PrintWriter;

abstract class TintController {

    /**
     * The default transition time, in milliseconds, for color transforms to turn on/off.
     */
    private static final long TRANSITION_DURATION = 3000L;

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

    public long getTransitionDurationMilliseconds() {
        return TRANSITION_DURATION;
    }

    public long getTransitionDurationMilliseconds(boolean direction) {
        return TRANSITION_DURATION;
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

    /**
     * Format a given matrix into a string.
     *
     * @param matrix the matrix to format
     * @param columns number of columns in the matrix
     */
    static String matrixToString(float[] matrix, int columns) {
        if (matrix == null || columns <= 0) {
            Slog.e(ColorDisplayService.TAG, "Invalid arguments when formatting matrix to string,"
                    + " matrix is null: " + (matrix == null)
                    + " columns: " + columns);
            return "";
        }

        final StringBuilder sb = new StringBuilder("");
        for (int i = 0; i < matrix.length; i++) {
            if (i % columns == 0) {
                sb.append("\n      ");
            }
            sb.append(String.format("%9.6f", matrix[i]));
        }
        return sb.toString();
    }

}
