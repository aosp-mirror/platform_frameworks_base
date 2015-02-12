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
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

/**
 * A propagation that varies with the distance to the epicenter of the Transition
 * or center of the scene if no epicenter exists. When a View is visible in the
 * start of the transition, Views farther from the epicenter will transition
 * sooner than Views closer to the epicenter. When a View is not in the start
 * of the transition or is not visible at the start of the transition, it will
 * transition sooner when closer to the epicenter and later when farther from
 * the epicenter. This is the default TransitionPropagation used with
 * {@link android.transition.Explode}.
 */
public class CircularPropagation extends VisibilityPropagation {
    private static final String TAG = "CircularPropagation";

    private float mPropagationSpeed = 3.0f;

    /**
     * Sets the speed at which transition propagation happens, relative to the duration of the
     * Transition. A <code>propagationSpeed</code> of 1 means that a View centered farthest from
     * the epicenter and View centered at the epicenter will have a difference
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
        TransitionValues positionValues;
        if (endValues == null || getViewVisibility(startValues) == View.VISIBLE) {
            positionValues = startValues;
            directionMultiplier = -1;
        } else {
            positionValues = endValues;
        }

        int viewCenterX = getViewX(positionValues);
        int viewCenterY = getViewY(positionValues);

        Rect epicenter = transition.getEpicenter();
        int epicenterX;
        int epicenterY;
        if (epicenter != null) {
            epicenterX = epicenter.centerX();
            epicenterY = epicenter.centerY();
        } else {
            int[] loc = new int[2];
            sceneRoot.getLocationOnScreen(loc);
            epicenterX = Math.round(loc[0] + (sceneRoot.getWidth() / 2)
                    + sceneRoot.getTranslationX());
            epicenterY = Math.round(loc[1] + (sceneRoot.getHeight() / 2)
                    + sceneRoot.getTranslationY());
        }
        double distance = distance(viewCenterX, viewCenterY, epicenterX, epicenterY);
        double maxDistance = distance(0, 0, sceneRoot.getWidth(), sceneRoot.getHeight());
        double distanceFraction = distance/maxDistance;

        long duration = transition.getDuration();
        if (duration < 0) {
            duration = 300;
        }

        return Math.round(duration * directionMultiplier / mPropagationSpeed * distanceFraction);
    }

    private static double distance(float x1, float y1, float x2, float y2) {
        double x = x2 - x1;
        double y = y2 - y1;
        return Math.hypot(x, y);
    }
}
