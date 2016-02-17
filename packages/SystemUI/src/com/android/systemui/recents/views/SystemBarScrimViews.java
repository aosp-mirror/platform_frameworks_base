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

package com.android.systemui.recents.views;

import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewPropertyAnimator;

import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.recents.events.activity.DismissRecentsToHomeAnimationStarted;
import com.android.systemui.recents.events.activity.EnterRecentsWindowAnimationCompletedEvent;

/** Manages the scrims for the various system bars. */
public class SystemBarScrimViews {

    Context mContext;

    View mNavBarScrimView;

    boolean mHasNavBarScrim;
    boolean mShouldAnimateNavBarScrim;

    int mNavBarScrimEnterDuration;

    public SystemBarScrimViews(Activity activity) {
        mContext = activity;
        mNavBarScrimView = activity.findViewById(R.id.nav_bar_scrim);
        mNavBarScrimEnterDuration = activity.getResources().getInteger(
                R.integer.recents_nav_bar_scrim_enter_duration);
    }

    /**
     * Prepares the scrim views for animating when entering Recents. This will be called before
     * the first draw.
     */
    public void prepareEnterRecentsAnimation(boolean hasNavBarScrim, boolean animateNavBarScrim) {
        mHasNavBarScrim = hasNavBarScrim;
        mShouldAnimateNavBarScrim = animateNavBarScrim;

        mNavBarScrimView.setVisibility(mHasNavBarScrim && !mShouldAnimateNavBarScrim ?
                View.VISIBLE : View.INVISIBLE);
    }

    /**
     * Animates the nav bar scrim visibility.
     */
    public void animateNavBarScrimVisibility(boolean visible, AnimationProps animation) {
        int toY = 0;
        if (visible) {
            mNavBarScrimView.setVisibility(View.VISIBLE);
            mNavBarScrimView.setTranslationY(mNavBarScrimView.getMeasuredHeight());
        } else {
            toY = mNavBarScrimView.getMeasuredHeight();
        }
        if (animation != AnimationProps.IMMEDIATE) {
            mNavBarScrimView.animate()
                    .translationY(toY)
                    .setDuration(animation.getDuration(AnimationProps.BOUNDS))
                    .setInterpolator(animation.getInterpolator(AnimationProps.BOUNDS))
                    .start();
        } else {
            mNavBarScrimView.setTranslationY(toY);
        }
    }

    /**** EventBus events ****/

    /**
     * Starts animating the scrim views when entering Recents.
     */
    public final void onBusEvent(EnterRecentsWindowAnimationCompletedEvent event) {
        if (mHasNavBarScrim) {
            AnimationProps animation = mShouldAnimateNavBarScrim
                    ? new AnimationProps()
                            .setDuration(AnimationProps.BOUNDS, mNavBarScrimEnterDuration)
                            .setInterpolator(AnimationProps.BOUNDS, Interpolators.DECELERATE_QUINT)
                    : AnimationProps.IMMEDIATE;
            animateNavBarScrimVisibility(true, animation);
        }
    }

    /**
     * Starts animating the scrim views when leaving Recents (either via launching a task, or
     * going home).
     */
    public final void onBusEvent(DismissRecentsToHomeAnimationStarted event) {
        if (mHasNavBarScrim) {
            AnimationProps animation = new AnimationProps()
                    .setDuration(AnimationProps.BOUNDS,
                            TaskStackAnimationHelper.EXIT_TO_HOME_TRANSLATION_DURATION)
                    .setInterpolator(AnimationProps.BOUNDS, Interpolators.FAST_OUT_SLOW_IN);
            animateNavBarScrimVisibility(false, animation);
        }
    }
}
