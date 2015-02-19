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
import android.view.View;
import com.android.systemui.R;
import com.android.systemui.recents.RecentsConfiguration;

/** Manages the scrims for the various system bars. */
public class SystemBarScrimViews {

    RecentsConfiguration mConfig;

    View mStatusBarScrimView;
    View mNavBarScrimView;

    boolean mHasNavBarScrim;
    boolean mShouldAnimateStatusBarScrim;
    boolean mHasStatusBarScrim;
    boolean mShouldAnimateNavBarScrim;

    public SystemBarScrimViews(Activity activity, RecentsConfiguration config) {
        mConfig = config;
        mStatusBarScrimView = activity.findViewById(R.id.status_bar_scrim);
        mNavBarScrimView = activity.findViewById(R.id.nav_bar_scrim);
    }

    /**
     * Prepares the scrim views for animating when entering Recents. This will be called before
     * the first draw.
     */
    public void prepareEnterRecentsAnimation() {
        mHasNavBarScrim = mConfig.hasNavBarScrim();
        mShouldAnimateNavBarScrim = mConfig.shouldAnimateNavBarScrim();
        mHasStatusBarScrim = mConfig.hasStatusBarScrim();
        mShouldAnimateStatusBarScrim = mConfig.shouldAnimateStatusBarScrim();

        mNavBarScrimView.setVisibility(mHasNavBarScrim && !mShouldAnimateNavBarScrim ?
                View.VISIBLE : View.INVISIBLE);
        mStatusBarScrimView.setVisibility(mHasStatusBarScrim && !mShouldAnimateStatusBarScrim ?
                View.VISIBLE : View.INVISIBLE);
    }

    /**
     * Starts animating the scrim views when entering Recents.
     */
    public void startEnterRecentsAnimation() {
        if (mHasStatusBarScrim && mShouldAnimateStatusBarScrim) {
            mStatusBarScrimView.setTranslationY(-mStatusBarScrimView.getMeasuredHeight());
            mStatusBarScrimView.animate()
                    .translationY(0)
                    .setStartDelay(mConfig.launchedFromHome ?
                            mConfig.transitionEnterFromHomeDelay :
                            mConfig.transitionEnterFromAppDelay)
                    .setDuration(mConfig.navBarScrimEnterDuration)
                    .setInterpolator(mConfig.quintOutInterpolator)
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
                    .setStartDelay(mConfig.launchedFromHome ?
                            mConfig.transitionEnterFromHomeDelay :
                            mConfig.transitionEnterFromAppDelay)
                    .setDuration(mConfig.navBarScrimEnterDuration)
                    .setInterpolator(mConfig.quintOutInterpolator)
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
        if (mHasStatusBarScrim && mShouldAnimateStatusBarScrim) {
            mStatusBarScrimView.animate()
                    .translationY(-mStatusBarScrimView.getMeasuredHeight())
                    .setStartDelay(0)
                    .setDuration(mConfig.taskViewExitToAppDuration)
                    .setInterpolator(mConfig.fastOutSlowInInterpolator)
                    .start();
        }
        if (mHasNavBarScrim && mShouldAnimateNavBarScrim) {
            mNavBarScrimView.animate()
                    .translationY(mNavBarScrimView.getMeasuredHeight())
                    .setStartDelay(0)
                    .setDuration(mConfig.taskViewExitToAppDuration)
                    .setInterpolator(mConfig.fastOutSlowInInterpolator)
                    .start();
        }
    }
}
