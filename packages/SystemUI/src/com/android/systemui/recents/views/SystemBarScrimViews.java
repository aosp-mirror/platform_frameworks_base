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

import android.app.Activity;
import android.content.Context;
import android.view.View;

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

    /**** EventBus events ****/

    /**
     * Starts animating the scrim views when entering Recents.
     */
    public final void onBusEvent(EnterRecentsWindowAnimationCompletedEvent event) {
        if (mHasNavBarScrim && mShouldAnimateNavBarScrim) {
            mNavBarScrimView.setTranslationY(mNavBarScrimView.getMeasuredHeight());
            mNavBarScrimView.animate()
                    .translationY(0)
                    .setDuration(mNavBarScrimEnterDuration)
                    .setInterpolator(Interpolators.DECELERATE_QUINT)
                    .withStartAction(new Runnable() {
                        @Override
                        public void run() {
                            mNavBarScrimView.setVisibility(View.VISIBLE);
                        }
                    })
                    .start();
        }
    }

    /**
     * Starts animating the scrim views when leaving Recents (either via launching a task, or
     * going home).
     */
    public final void onBusEvent(DismissRecentsToHomeAnimationStarted event) {
        int taskViewExitToAppDuration = mContext.getResources().getInteger(
                R.integer.recents_task_exit_to_app_duration);
        if (mHasNavBarScrim && mShouldAnimateNavBarScrim) {
            mNavBarScrimView.animate()
                    .translationY(mNavBarScrimView.getMeasuredHeight())
                    .setStartDelay(0)
                    .setDuration(taskViewExitToAppDuration)
                    .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                    .start();
        }
    }
}
