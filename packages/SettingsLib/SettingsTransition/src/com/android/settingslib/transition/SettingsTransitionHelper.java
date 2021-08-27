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

import androidx.annotation.IntDef;
import androidx.fragment.app.Fragment;

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

    public static final String EXTRA_PAGE_TRANSITION_TYPE = "page_transition_type";

    private static final String TAG = "SettingsTransitionHelper";

    /**
     * Apply the forward transition to the {@link Activity}, including Exit Transition and Enter
     * Transition.
     *
     * The Exit Transition takes effect when leaving the page, while the Enter Transition is
     * triggered when the page is launched/entering.
     */
    public static void applyForwardTransition(Activity activity) {}

    /**
     * Apply the forward transition to the {@link Fragment}, including Exit Transition and Enter
     * Transition.
     *
     * The Exit Transition takes effect when leaving the page, while the Enter Transition is
     * triggered when the page is launched/entering.
     */
    public static void applyForwardTransition(Fragment fragment) {}

    /**
     * Apply the backward transition to the {@link Activity}, including Return Transition and
     * Reenter Transition.
     *
     * Return Transition will be used to move Views out of the scene when the Window is preparing
     * to close. Reenter Transition will be used to move Views in to the scene when returning from a
     * previously-started Activity.
     */
    public static void applyBackwardTransition(Activity activity) {}

    /**
     * Apply the backward transition to the {@link Fragment}, including Return Transition and
     * Reenter Transition.
     *
     * Return Transition will be used to move Views out of the scene when the Window is preparing
     * to close. Reenter Transition will be used to move Views in to the scene when returning from a
     * previously-started Fragment.
     */
    public static void applyBackwardTransition(Fragment fragment) {}
}
