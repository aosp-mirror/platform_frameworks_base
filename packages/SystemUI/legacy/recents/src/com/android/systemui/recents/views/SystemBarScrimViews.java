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

import android.content.Context;
import android.view.View;

import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.recents.LegacyRecentsImpl;
import com.android.systemui.recents.RecentsActivity;
import com.android.systemui.recents.events.activity.ConfigurationChangedEvent;
import com.android.systemui.recents.events.activity.DismissRecentsToHomeAnimationStarted;
import com.android.systemui.recents.events.activity.EnterRecentsWindowAnimationCompletedEvent;
import com.android.systemui.recents.events.ui.DismissAllTaskViewsEvent;
import com.android.systemui.recents.events.activity.MultiWindowStateChangedEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragEndCancelledEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragEndEvent;
import com.android.systemui.recents.utilities.AnimationProps;

/** Manages the scrims for the various system bars. */
public class SystemBarScrimViews {

    private static final int DEFAULT_ANIMATION_DURATION = 150;

    private Context mContext;

    private View mNavBarScrimView;

    private boolean mHasNavBarScrim;
    private boolean mShouldAnimateNavBarScrim;
    private boolean mHasTransposedNavBar;
    private boolean mHasDockedTasks;
    private int mNavBarScrimEnterDuration;

    public SystemBarScrimViews(RecentsActivity activity) {
        mContext = activity;
        mNavBarScrimView = activity.findViewById(R.id.nav_bar_scrim);
        mNavBarScrimView.forceHasOverlappingRendering(false);
        mNavBarScrimEnterDuration = activity.getResources().getInteger(
                R.integer.recents_nav_bar_scrim_enter_duration);
        mHasNavBarScrim = LegacyRecentsImpl.getSystemServices().hasTransposedNavigationBar();
        mHasDockedTasks = LegacyRecentsImpl.getSystemServices().hasDockedTask();
    }

    /**
     * Updates the nav bar scrim.
     */
    public void updateNavBarScrim(boolean animateNavBarScrim, boolean hasStackTasks,
            AnimationProps animation) {
        prepareEnterRecentsAnimation(isNavBarScrimRequired(hasStackTasks), animateNavBarScrim);
        if (animateNavBarScrim && animation != null) {
            animateNavBarScrimVisibility(true, animation);
        }
    }

    /**
     * Prepares the scrim views for animating when entering Recents. This will be called before
     * the first draw, unless we are updating the scrim on configuration change.
     */
    private void prepareEnterRecentsAnimation(boolean hasNavBarScrim, boolean animateNavBarScrim) {
        mHasNavBarScrim = hasNavBarScrim;
        mShouldAnimateNavBarScrim = animateNavBarScrim;

        mNavBarScrimView.setVisibility(mHasNavBarScrim && !mShouldAnimateNavBarScrim ?
                View.VISIBLE : View.INVISIBLE);
    }

    /**
     * Animates the nav bar scrim visibility.
     */
    private void animateNavBarScrimVisibility(boolean visible, AnimationProps animation) {
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

    /**
     * @return Whether to show the nav bar scrim.
     */
    private boolean isNavBarScrimRequired(boolean hasStackTasks) {
        return hasStackTasks && !mHasTransposedNavBar && !mHasDockedTasks;
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
            AnimationProps animation = createBoundsAnimation(
                    TaskStackAnimationHelper.EXIT_TO_HOME_TRANSLATION_DURATION);
            animateNavBarScrimVisibility(false, animation);
        }
    }

    public final void onBusEvent(DismissAllTaskViewsEvent event) {
        if (mHasNavBarScrim) {
            AnimationProps animation = createBoundsAnimation(
                    TaskStackAnimationHelper.EXIT_TO_HOME_TRANSLATION_DURATION);
            animateNavBarScrimVisibility(false, animation);
        }
    }

    public final void onBusEvent(ConfigurationChangedEvent event) {
        if (event.fromDeviceOrientationChange) {
            mHasNavBarScrim = LegacyRecentsImpl.getSystemServices().hasTransposedNavigationBar();
        }
        animateScrimToCurrentNavBarState(event.hasStackTasks);
    }

    public final void onBusEvent(MultiWindowStateChangedEvent event) {
        mHasDockedTasks = event.inMultiWindow;
        animateScrimToCurrentNavBarState(event.stack.getTaskCount() > 0);
    }

    public final void onBusEvent(final DragEndEvent event) {
        // Hide the nav bar scrims once we drop to a dock region
        if (event.dropTarget instanceof DockState) {
            animateScrimToCurrentNavBarState(false /* hasStackTasks */);
        }
    }

    public final void onBusEvent(final DragEndCancelledEvent event) {
        // Restore the scrims to the normal state
        animateScrimToCurrentNavBarState(event.stack.getTaskCount() > 0);
    }

    /**
     * Animates the scrim to match the state of the current nav bar.
     */
    private void animateScrimToCurrentNavBarState(boolean hasStackTasks) {
        boolean hasNavBarScrim = isNavBarScrimRequired(hasStackTasks);
        if (mHasNavBarScrim != hasNavBarScrim) {
            AnimationProps animation = hasNavBarScrim
                    ? createBoundsAnimation(DEFAULT_ANIMATION_DURATION)
                    : AnimationProps.IMMEDIATE;
            animateNavBarScrimVisibility(hasNavBarScrim, animation);
        }
        mHasNavBarScrim = hasNavBarScrim;
    }

    /**
     * @return a default animation to aniamte the bounds of the scrim.
     */
    private AnimationProps createBoundsAnimation(int duration) {
        return new AnimationProps()
                .setDuration(AnimationProps.BOUNDS, duration)
                .setInterpolator(AnimationProps.BOUNDS, Interpolators.FAST_OUT_SLOW_IN);
    }
}
