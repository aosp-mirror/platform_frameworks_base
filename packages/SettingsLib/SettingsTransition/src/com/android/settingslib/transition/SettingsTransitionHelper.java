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

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.view.Window;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;

import com.google.android.material.transition.platform.MaterialSharedAxis;
import com.google.android.material.transition.platform.SlideDistanceProvider;

/**
 * A helper class to apply Settings Transition
 */
public class SettingsTransitionHelper {

    private static final String TAG = "SettingsTransitionHelper";
    private static final long DURATION = 450L;

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

        final Interpolator interpolator =
                AnimationUtils.loadInterpolator(context,
                        android.R.interpolator.fast_out_extra_slow_in);
        transition.setInterpolator(interpolator);

        // TODO(b/177480673): Update fade through threshold once (cl/362065364) is released

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
