/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.settingslib.transition;

import androidx.annotation.IntDef;
import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.view.Window;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;

import androidx.core.os.BuildCompat;

import com.google.android.material.transition.platform.FadeThroughProvider;
import com.google.android.material.transition.platform.MaterialSharedAxis;
import com.google.android.material.transition.platform.SlideDistanceProvider;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A helper class to apply Settings Transition
 */
public class SettingsTransitionHelper {

    /**
     * Flags indicating the type of the transition.
     */
    @IntDef({
            TransitionType.TRANSITION_NONE,
            TransitionType.TRANSITION_SHARED_AXIS,
            TransitionType.TRANSITION_SLIDE,
            TransitionType.TRANSITION_FADE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TransitionType {
        int TRANSITION_NONE = -1;
        int TRANSITION_SHARED_AXIS = 0;
        int TRANSITION_SLIDE = 1;
        int TRANSITION_FADE = 2;
    }

    private static final String TAG = "SettingsTransitionHelper";
    private static final long DURATION = 450L;
    private static final float FADE_THROUGH_THRESHOLD = 0.22F;

    private static MaterialSharedAxis createSettingsSharedAxis(Context context, boolean forward) {
        final MaterialSharedAxis transition = new MaterialSharedAxis(MaterialSharedAxis.X, forward);
        transition.excludeTarget(android.R.id.statusBarBackground, true);
        transition.excludeTarget(android.R.id.navigationBarBackground, true);

        final SlideDistanceProvider forwardDistanceProvider =
                (SlideDistanceProvider) transition.getPrimaryAnimatorProvider();
        final int distance = context.getResources().getDimensionPixelSize(
                R.dimen.settings_shared_axis_x_slide_distance);
        forwardDistanceProvider.setSlideDistance(distance);
        transition.setDuration(DURATION);

        final FadeThroughProvider fadeThroughProvider =
                (FadeThroughProvider) transition.getSecondaryAnimatorProvider();
        fadeThroughProvider.setProgressThreshold(FADE_THROUGH_THRESHOLD);

        final Interpolator interpolator =
                AnimationUtils.loadInterpolator(context, R.interpolator.fast_out_extra_slow_in);
        transition.setInterpolator(interpolator);

        return transition;
    }

    /**
     * Apply the forward transition to the {@link Activity}, including Exit Transition and Enter
     * Transition.
     *
     * The Exit Transition takes effect when leaving the page, while the Enter Transition is
     * triggered when the page is launched/entering.
     */
    public static void applyForwardTransition(Activity activity) {
        if (!BuildCompat.isAtLeastS()) {
            return;
        }
        if (activity == null) {
            Log.w(TAG, "applyForwardTransition: Invalid activity!");
            return;
        }
        final Window window = activity.getWindow();
        if (window == null) {
            Log.w(TAG, "applyForwardTransition: Invalid window!");
            return;
        }
        final MaterialSharedAxis forward = createSettingsSharedAxis(activity, true);
        window.setExitTransition(forward);
        window.setEnterTransition(forward);
    }

    /**
     * Apply the backward transition to the {@link Activity}, including Return Transition and
     * Reenter Transition.
     *
     * Return Transition will be used to move Views out of the scene when the Window is preparing
     * to close. Reenter Transition will be used to move Views in to the scene when returning from a
     * previously-started Activity.
     */
    public static void applyBackwardTransition(Activity activity) {
        if (!BuildCompat.isAtLeastS()) {
            return;
        }
        if (activity == null) {
            Log.w(TAG, "applyBackwardTransition: Invalid activity!");
            return;
        }
        final Window window = activity.getWindow();
        if (window == null) {
            Log.w(TAG, "applyBackwardTransition: Invalid window!");
            return;
        }
        final MaterialSharedAxis backward = createSettingsSharedAxis(activity, false);
        window.setReturnTransition(backward);
        window.setReenterTransition(backward);
    }
}
