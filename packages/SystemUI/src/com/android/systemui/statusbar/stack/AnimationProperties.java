/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.statusbar.stack;

import android.animation.AnimatorListenerAdapter;
import android.util.Property;
import android.view.View;
import android.view.animation.Interpolator;

/**
 * Properties for a View animation
 */
public class AnimationProperties {
    public long duration;
    public long delay;

    /**
     * @return an animation filter for this animation.
     */
    public AnimationFilter getAnimationFilter() {
        return null;
    }

    /**
     * @return a listener that should be run whenever any property finished its animation
     */
    public AnimatorListenerAdapter getAnimationFinishListener() {
        return null;
    }

    public boolean wasAdded(View view) {
        return false;
    }

    /**
     * Get a custom interpolator for a property instead of the normal one.
     */
    public Interpolator getCustomInterpolator(View child, Property translationY) {
        return null;
    }
}
