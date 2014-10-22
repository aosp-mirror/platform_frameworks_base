/*
 * Copyright (C) 2014 The Android Open Source Project
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
package android.transition;

import android.graphics.Rect;
import android.util.FloatMath;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;

/**
 * A <code>TransitionPropagation</code> that propagates based on the distance to the side
 * and, orthogonally, the distance to epicenter. If the transitioning View is visible in
 * the start of the transition, then it will transition sooner when closer to the side and
 * later when farther. If the view is not visible in the start of the transition, then
 * it will transition later when closer to the side and sooner when farther from the edge.
 * This is the default TransitionPropagation used with {@link android.transition.Slide}.
 */
public class SidePropagation extends VisibilityPropagation {
    private static final String TAG = "SlidePropagation";

    private float mPropagationSpeed = 3.0f;
    private int mSide = Gravity.BOTTOM;

    /**
     * Sets the side that is used to calculate the transition propagation. If the transitioning
     * View is visible in the start of the transition, then it will transition sooner when
     * closer to the side and later when farther. If the view is not visible in the start of
     * the transition, then it will transition later when closer to the side and sooner when
     * farther from the edge. The default is {@link Gravity#BOTTOM}.
     *
     * @param side The side that is used to calculate the transition propagation. Must be one of
     *             {@link Gravity#LEFT}, {@link Gravity#TOP}, {@link Gravity#RIGHT}, or
     *             {@link Gravity#BOTTOM}.
     */
    public void setSide(int side) {
        mSide = side;
    }

    /**
     * Sets the speed at which transition propagation happens, relative to the duration of the
     * Transition. A <code>propagationSpeed</code> of 1 means that a View centered at the side
     * set in {@link #setSide(int)} and View centered at the opposite edge will have a difference
     * in start delay of approximately the duration of the Transition. A speed of 2 means the
     * start delay difference will be approximately half of the duration of the transition. A
     * value of 0 is illegal, but negative values will invert the propagation.
     *
     * @param propagationSpeed The speed at which propagation occurs, relative to the duration
     *                         of the transition. A speed of 4 means it works 4 times as fast
     *                         as the duration of the transition. May not be 0.
     */
    public void setPropagationSpeed(float propagationSpeed) {
        if (propagationSpeed == 0) {
            throw new IllegalArgumentException("propagationSpeed may not be 0");
        }
        mPropagationSpeed = propagationSpeed;
    }

    @Override
    public long getStartDelay(ViewGroup sceneRoot, Transition transition,
            TransitionValues startValues, TransitionValues endValues) {
        if (startValues == null && endValues == null) {
            return 0;
        }
        int directionMultiplier = 1;
        Rect epicenter = transition.getEpicenter();
        TransitionValues positionValues;
        if (endValues == null || getViewVisibility(startValues) == View.VISIBLE) {
            positionValues = startValues;
            directionMultiplier = -1;
        } else {
            positionValues = endValues;
        }

        int viewCenterX = getViewX(positionValues);
        int viewCenterY = getViewY(positionValues);

        int[] loc = new int[2];
        sceneRoot.getLocationOnScreen(loc);
        int left = loc[0] + Math.round(sceneRoot.getTranslationX());
        int top = loc[1] + Math.round(sceneRoot.getTranslationY());
        int right = left + sceneRoot.getWidth();
        int bottom = top + sceneRoot.getHeight();

        int epicenterX;
        int epicenterY;
        if (epicenter != null) {
            epicenterX = epicenter.centerX();
            epicenterY = epicenter.centerY();
        } else {
            epicenterX = (left + right) / 2;
            epicenterY = (top + bottom) / 2;
        }

        float distance = distance(viewCenterX, viewCenterY, epicenterX, epicenterY,
                left, top, right, bottom);
        float maxDistance = getMaxDistance(sceneRoot);
        float distanceFraction = distance/maxDistance;

        long duration = transition.getDuration();
        if (duration < 0) {
            duration = 300;
        }

        return Math.round(duration * directionMultiplier / mPropagationSpeed * distanceFraction);
    }

    private int distance(int viewX, int viewY, int epicenterX, int epicenterY,
            int left, int top, int right, int bottom) {
        int distance = 0;
        switch (mSide) {
            case Gravity.LEFT:
                distance = right - viewX + Math.abs(epicenterY - viewY);
                break;
            case Gravity.TOP:
                distance = bottom - viewY + Math.abs(epicenterX - viewX);
                break;
            case Gravity.RIGHT:
                distance = viewX - left + Math.abs(epicenterY - viewY);
                break;
            case Gravity.BOTTOM:
                distance = viewY - top + Math.abs(epicenterX - viewX);
                break;
        }
        return distance;
    }

    private int getMaxDistance(ViewGroup sceneRoot) {
        switch (mSide) {
            case Gravity.LEFT:
            case Gravity.RIGHT:
                return sceneRoot.getWidth();
            default:
                return sceneRoot.getHeight();
        }
    }
}
