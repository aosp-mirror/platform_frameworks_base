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

package com.android.systemui.statusbar.notification;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.util.Log;
import android.util.Property;
import android.view.View;
import android.view.animation.Interpolator;

import com.android.app.animation.Interpolators;
import com.android.systemui.statusbar.notification.stack.AnimationFilter;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.notification.stack.ViewState;

/**
 * An animator to animate properties
 */
public class PropertyAnimator {
    private static final String TAG = "PropertyAnimator";

    /**
     * Set a property on a view, updating its value, even if it's already animating.
     * The @param animated can be used to request an animation.
     * If the view isn't animated, this utility will update the current animation if existent,
     * such that the end value will point to @param newEndValue or apply it directly if there's
     * no animation.
     */
    public static <T extends View> void setProperty(final T view,
            AnimatableProperty animatableProperty, float newEndValue,
            AnimationProperties properties, boolean animated) {
        int animatorTag = animatableProperty.getAnimatorTag();
        ValueAnimator previousAnimator = ViewState.getChildTag(view, animatorTag);
        if (previousAnimator != null || animated) {
            startAnimation(view, animatableProperty, newEndValue, animated ? properties : null);
        } else {
            // no new animation needed, let's just apply the value
            animatableProperty.getProperty().set(view, newEndValue);
        }
    }

    public static <T extends View> void startAnimation(final T view,
            AnimatableProperty animatableProperty, float newEndValue,
            AnimationProperties properties) {
        Property<T, Float> property = animatableProperty.getProperty();
        int animationStartTag = animatableProperty.getAnimationStartTag();
        int animationEndTag = animatableProperty.getAnimationEndTag();
        Float previousStartValue = ViewState.getChildTag(view, animationStartTag);
        Float previousEndValue = ViewState.getChildTag(view, animationEndTag);
        if (previousEndValue != null && previousEndValue == newEndValue) {
            return;
        }
        int animatorTag = animatableProperty.getAnimatorTag();
        ValueAnimator previousAnimator = ViewState.getChildTag(view, animatorTag);
        AnimationFilter filter = properties != null ? properties.getAnimationFilter() : null;
        if (filter == null || !filter.shouldAnimateProperty(property)) {
            // just a local update was performed
            if (previousAnimator != null) {
                // we need to increase all animation keyframes of the previous animator by the
                // relative change to the end value
                PropertyValuesHolder[] values = previousAnimator.getValues();
                float relativeDiff = newEndValue - previousEndValue;
                float newStartValue = previousStartValue + relativeDiff;
                values[0].setFloatValues(newStartValue, newEndValue);
                view.setTag(animationStartTag, newStartValue);
                view.setTag(animationEndTag, newEndValue);
                previousAnimator.setCurrentPlayTime(previousAnimator.getCurrentPlayTime());
                return;
            } else {
                // no new animation needed, let's just apply the value
                property.set(view, newEndValue);
                return;
            }
        }

        Float currentValue = property.get(view);
        AnimatorListenerAdapter listener = properties.getAnimationFinishListener(property);
        if (currentValue.equals(newEndValue)) {
            // Skip the animation!
            if (previousAnimator != null) {
                previousAnimator.cancel();
            }
            if (listener != null) {
                listener.onAnimationEnd(null);
            }
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(currentValue, newEndValue);
        animator.addUpdateListener(
                animation -> property.set(view, (Float) animation.getAnimatedValue()));
        Interpolator customInterpolator = properties.getCustomInterpolator(view, property);
        Interpolator interpolator =  customInterpolator != null ? customInterpolator
                : Interpolators.FAST_OUT_SLOW_IN;
        animator.setInterpolator(interpolator);
        long newDuration = ViewState.cancelAnimatorAndGetNewDuration(properties.duration,
                previousAnimator);
        animator.setDuration(newDuration);
        if (properties.delay > 0 && (previousAnimator == null
                || previousAnimator.getAnimatedFraction() == 0)) {
            animator.setStartDelay(properties.delay);
        }
        // remove the tag when the animation is finished
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                Animator existing = (Animator) view.getTag(animatorTag);
                if (existing == animation) {
                    view.setTag(animatorTag, null);
                    view.setTag(animationStartTag, null);
                    view.setTag(animationEndTag, null);
                } else {
                    Log.e(TAG, "Unexpected Animator set during onAnimationEnd. Not cleaning up.");
                }
            }
        });
        if (listener != null) {
            animator.addListener(listener);
        }
        ViewState.startAnimator(animator, listener);
        view.setTag(animatorTag, animator);
        view.setTag(animationStartTag, currentValue);
        view.setTag(animationEndTag, newEndValue);
    }

    public static <T extends View> void applyImmediately(T view, AnimatableProperty property,
            float newValue) {
        cancelAnimation(view, property);
        property.getProperty().set(view, newValue);
    }

    public static void cancelAnimation(View view, AnimatableProperty property) {
        ValueAnimator animator = (ValueAnimator) view.getTag(property.getAnimatorTag());
        if (animator != null) {
            animator.cancel();
        }
    }

    public static boolean isAnimating(View view, AnimatableProperty property) {
        return view.getTag(property.getAnimatorTag()) != null;
    }
}
