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
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import com.android.systemui.R;
import com.android.systemui.recents.RecentsActivity;
import com.android.systemui.recents.RecentsActivityLaunchState;
import com.android.systemui.recents.RecentsConfiguration;

/** Manages the scrims for the various system bars. */
public class SystemBarScrimViews {

    Context mContext;
    RecentsConfiguration mConfig;

    View mStatusBarScrimView;
    View mNavBarScrimView;

    boolean mHasNavBarScrim;
    boolean mShouldAnimateStatusBarScrim;
    boolean mHasStatusBarScrim;
    boolean mShouldAnimateNavBarScrim;

    int mNavBarScrimEnterDuration;

    Interpolator mFastOutSlowInInterpolator;
    Interpolator mQuintOutInterpolator;

    public SystemBarScrimViews(Activity activity) {
        mContext = activity;
        mConfig = RecentsConfiguration.getInstance();
        mStatusBarScrimView = activity.findViewById(R.id.status_bar_scrim);
        mNavBarScrimView = activity.findViewById(R.id.nav_bar_scrim);
        mNavBarScrimEnterDuration = activity.getResources().getInteger(
                R.integer.recents_nav_bar_scrim_enter_duration);
        mFastOutSlowInInterpolator = AnimationUtils.loadInterpolator(activity,
                        com.android.internal.R.interpolator.fast_out_slow_in);
        mQuintOutInterpolator = AnimationUtils.loadInterpolator(activity,
                com.android.internal.R.interpolator.decelerate_quint);
    }

    /**
     * Prepares the scrim views for animating when entering Recents. This will be called before
     * the first draw.
     */
    public void prepareEnterRecentsAnimation() {
        RecentsActivityLaunchState launchState = mConfig.getLaunchState();
        mHasNavBarScrim = launchState.hasNavBarScrim();
        mShouldAnimateNavBarScrim = launchState.shouldAnimateNavBarScrim();
        mHasStatusBarScrim = launchState.hasStatusBarScrim();
        mShouldAnimateStatusBarScrim = launchState.shouldAnimateStatusBarScrim();

        mNavBarScrimView.setVisibility(mHasNavBarScrim && !mShouldAnimateNavBarScrim ?
                View.VISIBLE : View.INVISIBLE);
        mStatusBarScrimView.setVisibility(mHasStatusBarScrim && !mShouldAnimateStatusBarScrim ?
                View.VISIBLE : View.INVISIBLE);
    }

    /**
     * Starts animating the scrim views when entering Recents.
     */
    public void startEnterRecentsAnimation() {
        RecentsActivityLaunchState launchState = mConfig.getLaunchState();
        int transitionEnterFromAppDelay = mContext.getResources().getInteger(
                R.integer.recents_enter_from_app_transition_duration);
        int transitionEnterFromHomeDelay = mContext.getResources().getInteger(
                R.integer.recents_enter_from_home_transition_duration);

        if (mHasStatusBarScrim && mShouldAnimateStatusBarScrim) {
            mStatusBarScrimView.setTranslationY(-mStatusBarScrimView.getMeasuredHeight());
            mStatusBarScrimView.animate()
                    .translationY(0)
                    .setStartDelay(launchState.launchedFromHome ?
                            transitionEnterFromHomeDelay :
                            transitionEnterFromAppDelay)
                    .setDuration(mNavBarScrimEnterDuration)
                    .setInterpolator(mQuintOutInterpolator)
                    .withStartAction(new Runnable() {
                        @Override
                        public void run() {
                            mStatusBarScrimView.setVisibility(View.VISIBLE);
                        }
                    })
                    .start();
        }
        if (mHasNavBarScrim && mShouldAnimateNavBarScrim) {
            mNavBarScrimView.setTranslationY(mNavBarScrimView.getMeasuredHeight());
            mNavBarScrimView.animate()
                    .translationY(0)
                    .setStartDelay(launchState.launchedFromHome ?
                            transitionEnterFromHomeDelay :
                            transitionEnterFromAppDelay)
                    .setDuration(mNavBarScrimEnterDuration)
                    .setInterpolator(mQuintOutInterpolator)
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
    public void startExitRecentsAnimation() {
        int taskViewExitToAppDuration = mContext.getResources().getInteger(
                R.integer.recents_task_exit_to_app_duration);
        if (mHasStatusBarScrim && mShouldAnimateStatusBarScrim) {
            mStatusBarScrimView.animate()
                    .translationY(-mStatusBarScrimView.getMeasuredHeight())
                    .setStartDelay(0)
                    .setDuration(taskViewExitToAppDuration)
                    .setInterpolator(mFastOutSlowInInterpolator)
                    .start();
        }
        if (mHasNavBarScrim && mShouldAnimateNavBarScrim) {
            mNavBarScrimView.animate()
                    .translationY(mNavBarScrimView.getMeasuredHeight())
                    .setStartDelay(0)
                    .setDuration(taskViewExitToAppDuration)
                    .setInterpolator(mFastOutSlowInInterpolator)
                    .start();
        }
    }
}
