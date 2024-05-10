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

package com.android.systemui.statusbar.notification.stack;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.util.ArrayMap;
import android.util.Property;
import android.view.View;
import android.view.animation.Interpolator;

import java.util.function.Consumer;

/**
 * Properties for a View animation
 */
public class AnimationProperties {
    public long duration;
    public long delay;
    private ArrayMap<Property, Interpolator> mInterpolatorMap;
    private Consumer<Property> mAnimationCancelAction;
    private Consumer<Property> mAnimationEndAction;

    /**
     * @return an animation filter for this animation.
     */
    public AnimationFilter getAnimationFilter() {
        return new AnimationFilter() {
            @Override
            public boolean shouldAnimateProperty(Property property) {
                return true;
            }
        };
    }

    /**
     * @return a listener that will be added for a given property during its animation.
     */
    public AnimatorListenerAdapter getAnimationFinishListener(Property property) {
        if (mAnimationEndAction == null && mAnimationCancelAction == null) {
            return null;
        }
        Consumer<Property> cancelAction = mAnimationCancelAction;
        Consumer<Property> endAction = mAnimationEndAction;
        return new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
                if (cancelAction != null) {
                    cancelAction.accept(property);
                }
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (!mCancelled && endAction != null) {
                    endAction.accept(property);
                }
            }
        };
    }

    /**
     * Add a callback for animation cancellation.
     */
    public AnimationProperties setAnimationCancelAction(Consumer<Property> listener) {
        mAnimationCancelAction = listener;
        return this;
    }

    /**
     * Add a callback for animation ending successfully. The callback will not be called when the
     * animations is cancelled.
     */
    public AnimationProperties setAnimationEndAction(Consumer<Property> listener) {
        mAnimationEndAction = listener;
        return this;
    }

    public boolean wasAdded(View view) {
        return false;
    }

    /**
     * Get a custom interpolator for a property instead of the normal one.
     */
    public Interpolator getCustomInterpolator(View child, Property property) {
        return mInterpolatorMap != null ? mInterpolatorMap.get(property) : null;
    }


    public void combineCustomInterpolators(AnimationProperties iconAnimationProperties) {
        ArrayMap<Property, Interpolator> map = iconAnimationProperties.mInterpolatorMap;
        if (map != null) {
            if (mInterpolatorMap == null) {
                mInterpolatorMap = new ArrayMap<>();
            }
            mInterpolatorMap.putAll(map);
        }
    }

    /**
     * Set a custom interpolator to use for all views for a property.
     */
    public AnimationProperties setCustomInterpolator(Property property, Interpolator interpolator) {
        if (mInterpolatorMap == null) {
            mInterpolatorMap = new ArrayMap<>();
        }
        mInterpolatorMap.put(property, interpolator);
        return this;
    }

    public AnimationProperties setDuration(long duration) {
        this.duration = duration;
        return this;
    }

    public AnimationProperties setDelay(long delay) {
        this.delay = delay;
        return this;
    }

    public AnimationProperties resetCustomInterpolators() {
        mInterpolatorMap = null;
        return this;
    }
}
