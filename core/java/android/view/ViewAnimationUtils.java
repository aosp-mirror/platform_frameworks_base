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

package android.view;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.RevealAnimator;

/**
 * Defines common utilities for working with View's animations.
 *
 */
public final class ViewAnimationUtils {
    private ViewAnimationUtils() {}
    /**
     * Returns an Animator which can animate a clipping circle.
     * <p>
     * Any shadow cast by the View will respect the circular clip from this animator.
     * <p>
     * Only a single non-rectangular clip can be applied on a View at any time.
     * Views clipped by a circular reveal animation take priority over
     * {@link View#setClipToOutline(boolean) View Outline clipping}.
     * <p>
     * Note that the animation returned here is a one-shot animation. It cannot
     * be re-used, and once started it cannot be paused or resumed. It is also
     * an asynchronous animation that automatically runs off of the UI thread.
     * As a result {@link AnimatorListener#onAnimationEnd(Animator)}
     * will occur after the animation has ended, but it may be delayed depending
     * on thread responsiveness.
     *
     * @param view The View will be clipped to the animating circle.
     * @param centerX The x coordinate of the center of the animating circle.
     * @param centerY The y coordinate of the center of the animating circle.
     * @param startRadius The starting radius of the animating circle.
     * @param endRadius The ending radius of the animating circle.
     */
    public static Animator createCircularReveal(View view,
            int centerX,  int centerY, float startRadius, float endRadius) {
        return new RevealAnimator(view, centerX, centerY, startRadius, endRadius);
    }
}
