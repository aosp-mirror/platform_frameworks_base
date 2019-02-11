/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.shared.system;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * TODO: Remove this class
 */
public class NavigationBarCompat extends QuickStepContract {


    @Retention(RetentionPolicy.SOURCE)
    @IntDef({HIT_TARGET_NONE, HIT_TARGET_BACK, HIT_TARGET_HOME, HIT_TARGET_OVERVIEW})
    public @interface HitTarget{}

    public static final int HIT_TARGET_NONE = 0;
    public static final int HIT_TARGET_BACK = 1;
    public static final int HIT_TARGET_HOME = 2;
    public static final int HIT_TARGET_OVERVIEW = 3;
    public static final int HIT_TARGET_ROTATION = 4;
    public static final int HIT_TARGET_DEAD_ZONE = 5;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({FLAG_DISABLE_SWIPE_UP,
            FLAG_DISABLE_QUICK_SCRUB,
            FLAG_SHOW_OVERVIEW_BUTTON
    })
    public @interface InteractionType {}

    /**
     * Interaction type: whether the gesture to swipe up from the navigation bar will trigger
     * launcher to show overview
     */
    public static final int FLAG_DISABLE_SWIPE_UP = 0x1;
    /**
     * Interaction type: enable quick scrub interaction on the home button
     */
    public static final int FLAG_DISABLE_QUICK_SCRUB = 0x2;

    /**
     * Interaction type: show/hide the overview button while this service is connected to launcher
     */
    public static final int FLAG_SHOW_OVERVIEW_BUTTON = 0x4;
}
