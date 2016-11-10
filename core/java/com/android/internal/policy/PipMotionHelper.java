/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.internal.policy;

import android.animation.RectEvaluator;
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.app.IActivityManager;
import android.graphics.Rect;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * A helper to animate the PIP.
 */
public class PipMotionHelper {

    private static final String TAG = "PipMotionHelper";

    private static final RectEvaluator RECT_EVALUATOR = new RectEvaluator(new Rect());
    private static final Interpolator FAST_OUT_SLOW_IN = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    private static final int DEFAULT_DURATION = 225;

    private IActivityManager mActivityManager;
    private Handler mHandler;

    public PipMotionHelper(Handler handler) {
        mHandler = handler;
    }

    /**
     * Moves the PIP to give given {@param bounds}.
     */
    public void resizeToBounds(Rect toBounds) {
        mHandler.post(() -> {
            if (mActivityManager == null) {
                mActivityManager = ActivityManager.getService();
            }
            try {
                mActivityManager.resizePinnedStack(toBounds, null /* tempPinnedTaskBounds */);
            } catch (RemoteException e) {
                Log.e(TAG, "Could not move pinned stack to bounds: " + toBounds, e);
            }
        });
    }

    /**
     * Creates an animation to move the PIP to give given {@param toBounds} with the default
     * animation properties.
     */
    public ValueAnimator createAnimationToBounds(Rect fromBounds, Rect toBounds) {
        return createAnimationToBounds(fromBounds, toBounds, DEFAULT_DURATION, FAST_OUT_SLOW_IN,
                null);
    }

    /**
     * Creates an animation to move the PIP to give given {@param toBounds}.
     */
    public ValueAnimator createAnimationToBounds(Rect fromBounds, Rect toBounds, int duration,
            Interpolator interpolator, ValueAnimator.AnimatorUpdateListener updateListener) {
        ValueAnimator anim = ValueAnimator.ofObject(RECT_EVALUATOR, fromBounds, toBounds);
        anim.setDuration(duration);
        anim.setInterpolator(interpolator);
        anim.addUpdateListener((ValueAnimator animation) -> {
            resizeToBounds((Rect) animation.getAnimatedValue());
        });
        if (updateListener != null) {
            anim.addUpdateListener(updateListener);
        }
        return anim;
    }


}
