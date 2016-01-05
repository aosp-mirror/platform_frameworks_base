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
 * limitations under the License.
 */

package com.android.systemui.recents.views;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;

import java.util.List;

/**
 * The animation properties to animate a {@link TaskView} to a given {@TaskViewTransform}.
 */
public class TaskViewAnimation {

    public static final TaskViewAnimation IMMEDIATE = new TaskViewAnimation(0,
            new LinearInterpolator());

    public final int startDelay;
    public final int duration;
    public final Interpolator interpolator;
    public final Animator.AnimatorListener listener;

    public TaskViewAnimation(int duration, Interpolator interpolator) {
        this(0 /* startDelay */, duration, interpolator, null);
    }

    public TaskViewAnimation(int duration, Interpolator interpolator,
            Animator.AnimatorListener listener) {
        this(0 /* startDelay */, duration, interpolator, listener);
    }

    public TaskViewAnimation(int startDelay, int duration, Interpolator interpolator,
            Animator.AnimatorListener listener) {
        this.startDelay = startDelay;
        this.duration = duration;
        this.interpolator = interpolator;
        this.listener = listener;
    }

    /**
     * Creates a new {@link AnimatorSet} that will animate the given animators with the current
     * animation properties.
     */
    public AnimatorSet createAnimator(List<Animator> animators) {
        AnimatorSet anim = new AnimatorSet();
        anim.setStartDelay(startDelay);
        anim.setDuration(duration);
        anim.setInterpolator(interpolator);
        if (listener != null) {
            anim.addListener(listener);
        }
        anim.playTogether(animators);
        return anim;
    }

    /**
     * Returns whether this animation has any duration.
     */
    public boolean isImmediate() {
        return duration <= 0;
    }
}
