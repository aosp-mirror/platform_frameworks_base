/*
 * Copyright (C) 2015 The Android Open Source Project
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
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.util.Log;
import android.util.Property;
import android.view.View;
import android.view.animation.Interpolator;

import com.android.app.animation.Interpolators;
import com.android.systemui.Dumpable;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.AnimatableProperty;
import com.android.systemui.statusbar.notification.NotificationFadeAware.FadeOptimizedNotification;
import com.android.systemui.statusbar.notification.PropertyAnimator;
import com.android.systemui.statusbar.notification.row.ExpandableView;
import com.android.systemui.statusbar.policy.HeadsUpUtil;

import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * A state of a view. This can be used to apply a set of view properties to a view with
 * {@link com.android.systemui.statusbar.notification.stack.StackScrollState} or start
 * animations with {@link com.android.systemui.statusbar.notification.stack.StackStateAnimator}.
 */
public class ViewState implements Dumpable {

    /**
     * Some animation properties that can be used to update running animations but not creating
     * any new ones.
     */
    protected static final AnimationProperties NO_NEW_ANIMATIONS = new AnimationProperties() {
        AnimationFilter mAnimationFilter = new AnimationFilter();

        @Override
        public AnimationFilter getAnimationFilter() {
            return mAnimationFilter;
        }
    };
    private static final int TAG_ANIMATOR_TRANSLATION_X = R.id.translation_x_animator_tag;
    private static final int TAG_ANIMATOR_TRANSLATION_Y = R.id.translation_y_animator_tag;
    private static final int TAG_ANIMATOR_TRANSLATION_Z = R.id.translation_z_animator_tag;
    private static final int TAG_ANIMATOR_ALPHA = R.id.alpha_animator_tag;
    private static final int TAG_END_TRANSLATION_X = R.id.translation_x_animator_end_value_tag;
    private static final int TAG_END_TRANSLATION_Y = R.id.translation_y_animator_end_value_tag;
    private static final int TAG_END_TRANSLATION_Z = R.id.translation_z_animator_end_value_tag;
    private static final int TAG_END_ALPHA = R.id.alpha_animator_end_value_tag;
    private static final int TAG_START_TRANSLATION_X = R.id.translation_x_animator_start_value_tag;
    private static final int TAG_START_TRANSLATION_Y = R.id.translation_y_animator_start_value_tag;
    private static final int TAG_START_TRANSLATION_Z = R.id.translation_z_animator_start_value_tag;
    private static final int TAG_START_ALPHA = R.id.alpha_animator_start_value_tag;
    private static final String LOG_TAG = "StackViewState";

    private static final AnimatableProperty SCALE_X_PROPERTY
            = new AnimatableProperty() {

        @Override
        public int getAnimationStartTag() {
            return R.id.scale_x_animator_start_value_tag;
        }

        @Override
        public int getAnimationEndTag() {
            return R.id.scale_x_animator_end_value_tag;
        }

        @Override
        public int getAnimatorTag() {
            return R.id.scale_x_animator_tag;
        }

        @Override
        public Property getProperty() {
            return View.SCALE_X;
        }
    };

    private static final AnimatableProperty SCALE_Y_PROPERTY
            = new AnimatableProperty() {

        @Override
        public int getAnimationStartTag() {
            return R.id.scale_y_animator_start_value_tag;
        }

        @Override
        public int getAnimationEndTag() {
            return R.id.scale_y_animator_end_value_tag;
        }

        @Override
        public int getAnimatorTag() {
            return R.id.scale_y_animator_tag;
        }

        @Override
        public Property getProperty() {
            return View.SCALE_Y;
        }
    };

    public boolean gone;
    public boolean hidden;

    private float mAlpha;
    private float mXTranslation;
    private float mYTranslation;
    private float mZTranslation;
    private float mScaleX = 1.0f;
    private float mScaleY = 1.0f;

    public float getAlpha() {
        return mAlpha;
    }

    /**
     * @param alpha View transparency.
     */
    public void setAlpha(float alpha) {
        if (isValidFloat(alpha, "alpha")) {
            this.mAlpha = alpha;
        }
    }

    public float getXTranslation() {
        return mXTranslation;
    }

    /**
     * @param xTranslation x-axis translation value for the animation.
     */
    public void setXTranslation(float xTranslation) {
        if (isValidFloat(xTranslation, "xTranslation")) {
            this.mXTranslation = xTranslation;
        }
    }

    public float getYTranslation() {
        return mYTranslation;
    }

    /**
     * @param yTranslation y-axis translation value for the animation.
     */
    public void setYTranslation(float yTranslation) {
        if (isValidFloat(yTranslation, "yTranslation")) {
            this.mYTranslation = yTranslation;
        }
    }

    public float getZTranslation() {
        return mZTranslation;
    }


    /**
     * @param zTranslation z-axis translation value for the animation.
     */
    public void setZTranslation(float zTranslation) {
        if (isValidFloat(zTranslation, "zTranslation")) {
            this.mZTranslation = zTranslation;
        }
    }

    public float getScaleX() {
        return mScaleX;
    }

    /**
     * @param scaleX x-axis scale property for the animation.
     */
    public void setScaleX(float scaleX) {
        if (isValidFloat(scaleX, "scaleX")) {
            this.mScaleX = scaleX;
        }
    }

    public float getScaleY() {
        return mScaleY;
    }

    /**
     * @param scaleY y-axis scale property for the animation.
     */
    public void setScaleY(float scaleY) {
        if (isValidFloat(scaleY, "scaleY")) {
            this.mScaleY = scaleY;
        }
    }

    /**
     * Checks if {@code value} is a valid float value. If it is not, logs it (using {@code name})
     * and returns false.
     */
    private boolean isValidFloat(float value, String name) {
        if (Float.isNaN(value)) {
            Log.wtf(LOG_TAG, "Cannot set property " + name + " to NaN");
            return false;
        }
        return true;
    }

    public void copyFrom(ViewState viewState) {
        mAlpha = viewState.mAlpha;
        mXTranslation = viewState.mXTranslation;
        mYTranslation = viewState.mYTranslation;
        mZTranslation = viewState.mZTranslation;
        gone = viewState.gone;
        hidden = viewState.hidden;
        mScaleX = viewState.mScaleX;
        mScaleY = viewState.mScaleY;
    }

    public void initFrom(View view) {
        mAlpha = view.getAlpha();
        mXTranslation = view.getTranslationX();
        mYTranslation = view.getTranslationY();
        mZTranslation = view.getTranslationZ();
        gone = view.getVisibility() == View.GONE;
        hidden = view.getVisibility() == View.INVISIBLE;
        mScaleX = view.getScaleX();
        mScaleY = view.getScaleY();
    }

    /**
     * Applies a {@link ViewState} to a normal view.
     */
    public void applyToView(View view) {
        if (this.gone) {
            // don't do anything with it
            return;
        }

        // apply xTranslation
        boolean animatingX = isAnimating(view, TAG_ANIMATOR_TRANSLATION_X);
        if (animatingX) {
            updateAnimationX(view);
        } else if (view.getTranslationX() != this.mXTranslation) {
            view.setTranslationX(this.mXTranslation);
        }

        // apply yTranslation
        boolean animatingY = isAnimating(view, TAG_ANIMATOR_TRANSLATION_Y);
        if (animatingY) {
            updateAnimationY(view);
        } else if (view.getTranslationY() != this.mYTranslation) {
            view.setTranslationY(this.mYTranslation);
        }

        // apply zTranslation
        boolean animatingZ = isAnimating(view, TAG_ANIMATOR_TRANSLATION_Z);
        if (animatingZ) {
            updateAnimationZ(view);
        } else if (view.getTranslationZ() != this.mZTranslation) {
            view.setTranslationZ(this.mZTranslation);
        }

        // apply scaleX
        boolean animatingScaleX = isAnimating(view, SCALE_X_PROPERTY);
        if (animatingScaleX) {
            updateAnimation(view, SCALE_X_PROPERTY, mScaleX);
        } else if (view.getScaleX() != mScaleX) {
            view.setScaleX(mScaleX);
        }

        // apply scaleY
        boolean animatingScaleY = isAnimating(view, SCALE_Y_PROPERTY);
        if (animatingScaleY) {
            updateAnimation(view, SCALE_Y_PROPERTY, mScaleY);
        } else if (view.getScaleY() != mScaleY) {
            view.setScaleY(mScaleY);
        }

        int oldVisibility = view.getVisibility();
        boolean becomesInvisible = this.mAlpha == 0.0f
                || (this.hidden && (!isAnimating(view) || oldVisibility != View.VISIBLE));
        boolean animatingAlpha = isAnimating(view, TAG_ANIMATOR_ALPHA);
        if (animatingAlpha) {
            updateAlphaAnimation(view);
        } else if (view.getAlpha() != this.mAlpha) {
            // apply layer type
            boolean becomesFullyVisible = this.mAlpha == 1.0f;
            boolean becomesFaded = !becomesInvisible && !becomesFullyVisible;
            if (FadeOptimizedNotification.FADE_LAYER_OPTIMIZATION_ENABLED
                    && view instanceof FadeOptimizedNotification) {
                // NOTE: A view that's going to utilize this interface to avoid having a hardware
                //  layer will have to return false from hasOverlappingRendering(), so we
                //  intentionally do not check that value in this if, even though we do in the else.
                FadeOptimizedNotification fadeOptimizedView = (FadeOptimizedNotification) view;
                boolean isFaded = fadeOptimizedView.isNotificationFaded();
                if (isFaded != becomesFaded) {
                    fadeOptimizedView.setNotificationFaded(becomesFaded);
                }
            } else {
                boolean newLayerTypeIsHardware = becomesFaded && view.hasOverlappingRendering();
                int layerType = view.getLayerType();
                int newLayerType = newLayerTypeIsHardware
                        ? View.LAYER_TYPE_HARDWARE
                        : View.LAYER_TYPE_NONE;
                if (layerType != newLayerType) {
                    view.setLayerType(newLayerType, null);
                }
            }

            // apply alpha
            view.setAlpha(this.mAlpha);
        }

        // apply visibility
        int newVisibility = becomesInvisible ? View.INVISIBLE : View.VISIBLE;
        if (newVisibility != oldVisibility) {
            if (!(view instanceof ExpandableView) || !((ExpandableView) view).willBeGone()) {
                // We don't want views to change visibility when they are animating to GONE
                view.setVisibility(newVisibility);
            }
        }
    }

    public boolean isAnimating(View view) {
        if (isAnimating(view, TAG_ANIMATOR_TRANSLATION_X)) {
            return true;
        }
        if (isAnimating(view, TAG_ANIMATOR_TRANSLATION_Y)) {
            return true;
        }
        if (isAnimating(view, TAG_ANIMATOR_TRANSLATION_Z)) {
            return true;
        }
        if (isAnimating(view, TAG_ANIMATOR_ALPHA)) {
            return true;
        }
        if (isAnimating(view, SCALE_X_PROPERTY)) {
            return true;
        }
        if (isAnimating(view, SCALE_Y_PROPERTY)) {
            return true;
        }
        return false;
    }

    private static boolean isAnimating(View view, int tag) {
        return getChildTag(view, tag) != null;
    }

    public static boolean isAnimating(View view, AnimatableProperty property) {
        return getChildTag(view, property.getAnimatorTag()) != null;
    }

    /**
     * Start an animation to this viewstate
     *
     * @param child               the view to animate
     * @param animationProperties the properties of the animation
     */
    public void animateTo(View child, AnimationProperties animationProperties) {
        boolean wasVisible = child.getVisibility() == View.VISIBLE;
        final float alpha = this.mAlpha;
        if (!wasVisible && (alpha != 0 || child.getAlpha() != 0)
                && !this.gone && !this.hidden) {
            child.setVisibility(View.VISIBLE);
        }
        float childAlpha = child.getAlpha();
        boolean alphaChanging = this.mAlpha != childAlpha;
        if (child instanceof ExpandableView) {
            // We don't want views to change visibility when they are animating to GONE
            alphaChanging &= !((ExpandableView) child).willBeGone();
        }

        // start translationX animation
        if (child.getTranslationX() != this.mXTranslation) {
            startXTranslationAnimation(child, animationProperties);
        } else {
            abortAnimation(child, TAG_ANIMATOR_TRANSLATION_X);
        }

        // start translationY animation
        if (child.getTranslationY() != this.mYTranslation) {
            startYTranslationAnimation(child, animationProperties);
        } else {
            abortAnimation(child, TAG_ANIMATOR_TRANSLATION_Y);
        }

        // start translationZ animation
        if (child.getTranslationZ() != this.mZTranslation) {
            startZTranslationAnimation(child, animationProperties);
        } else {
            abortAnimation(child, TAG_ANIMATOR_TRANSLATION_Z);
        }

        // start scaleX animation
        if (child.getScaleX() != mScaleX) {
            PropertyAnimator.startAnimation(child, SCALE_X_PROPERTY, mScaleX, animationProperties);
        } else {
            abortAnimation(child, SCALE_X_PROPERTY.getAnimatorTag());
        }

        // start scaleX animation
        if (child.getScaleY() != mScaleY) {
            PropertyAnimator.startAnimation(child, SCALE_Y_PROPERTY, mScaleY, animationProperties);
        } else {
            abortAnimation(child, SCALE_Y_PROPERTY.getAnimatorTag());
        }

        // start alpha animation
        if (alphaChanging) {
            startAlphaAnimation(child, animationProperties);
        } else {
            abortAnimation(child, TAG_ANIMATOR_ALPHA);
        }
    }

    private void updateAlphaAnimation(View view) {
        startAlphaAnimation(view, NO_NEW_ANIMATIONS);
    }

    private void startAlphaAnimation(final View child, AnimationProperties properties) {
        Float previousStartValue = getChildTag(child, TAG_START_ALPHA);
        Float previousEndValue = getChildTag(child, TAG_END_ALPHA);
        final float newEndValue = this.mAlpha;
        if (previousEndValue != null && previousEndValue == newEndValue) {
            return;
        }
        ObjectAnimator previousAnimator = getChildTag(child, TAG_ANIMATOR_ALPHA);
        AnimationFilter filter = properties.getAnimationFilter();
        if (!filter.animateAlpha) {
            // just a local update was performed
            if (previousAnimator != null) {
                // we need to increase all animation keyframes of the previous animator by the
                // relative change to the end value
                PropertyValuesHolder[] values = previousAnimator.getValues();
                float relativeDiff = newEndValue - previousEndValue;
                float newStartValue = previousStartValue + relativeDiff;
                values[0].setFloatValues(newStartValue, newEndValue);
                child.setTag(TAG_START_ALPHA, newStartValue);
                child.setTag(TAG_END_ALPHA, newEndValue);
                previousAnimator.setCurrentPlayTime(previousAnimator.getCurrentPlayTime());
                return;
            } else {
                // no new animation needed, let's just apply the value
                child.setAlpha(newEndValue);
                if (newEndValue == 0) {
                    child.setVisibility(View.INVISIBLE);
                }
            }
        }

        ObjectAnimator animator = ObjectAnimator.ofFloat(child, View.ALPHA,
                child.getAlpha(), newEndValue);
        animator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        // Handle layer type
        child.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        animator.addListener(new AnimatorListenerAdapter() {
            public boolean mWasCancelled;

            @Override
            public void onAnimationEnd(Animator animation) {
                child.setLayerType(View.LAYER_TYPE_NONE, null);
                if (newEndValue == 0 && !mWasCancelled) {
                    child.setVisibility(View.INVISIBLE);
                }
                // remove the tag when the animation is finished
                child.setTag(TAG_ANIMATOR_ALPHA, null);
                child.setTag(TAG_START_ALPHA, null);
                child.setTag(TAG_END_ALPHA, null);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                mWasCancelled = true;
            }

            @Override
            public void onAnimationStart(Animator animation) {
                mWasCancelled = false;
            }
        });
        long newDuration = cancelAnimatorAndGetNewDuration(properties.duration, previousAnimator);
        animator.setDuration(newDuration);
        if (properties.delay > 0 && (previousAnimator == null
                || previousAnimator.getAnimatedFraction() == 0)) {
            animator.setStartDelay(properties.delay);
        }
        AnimatorListenerAdapter listener = properties.getAnimationFinishListener(View.ALPHA);
        if (listener != null) {
            animator.addListener(listener);
        }

        startAnimator(animator, listener);
        child.setTag(TAG_ANIMATOR_ALPHA, animator);
        child.setTag(TAG_START_ALPHA, child.getAlpha());
        child.setTag(TAG_END_ALPHA, newEndValue);
    }

    private void updateAnimationZ(View view) {
        startZTranslationAnimation(view, NO_NEW_ANIMATIONS);
    }

    private void updateAnimation(View view, AnimatableProperty property,
            float endValue) {
        PropertyAnimator.startAnimation(view, property, endValue, NO_NEW_ANIMATIONS);
    }

    private void startZTranslationAnimation(final View child, AnimationProperties properties) {
        Float previousStartValue = getChildTag(child, TAG_START_TRANSLATION_Z);
        Float previousEndValue = getChildTag(child, TAG_END_TRANSLATION_Z);
        float newEndValue = this.mZTranslation;
        if (previousEndValue != null && previousEndValue == newEndValue) {
            return;
        }
        ObjectAnimator previousAnimator = getChildTag(child, TAG_ANIMATOR_TRANSLATION_Z);
        AnimationFilter filter = properties.getAnimationFilter();
        if (!filter.animateZ) {
            // just a local update was performed
            if (previousAnimator != null) {
                // we need to increase all animation keyframes of the previous animator by the
                // relative change to the end value
                PropertyValuesHolder[] values = previousAnimator.getValues();
                float relativeDiff = newEndValue - previousEndValue;
                float newStartValue = previousStartValue + relativeDiff;
                values[0].setFloatValues(newStartValue, newEndValue);
                child.setTag(TAG_START_TRANSLATION_Z, newStartValue);
                child.setTag(TAG_END_TRANSLATION_Z, newEndValue);
                previousAnimator.setCurrentPlayTime(previousAnimator.getCurrentPlayTime());
                return;
            } else {
                // no new animation needed, let's just apply the value
                child.setTranslationZ(newEndValue);
            }
        }

        ObjectAnimator animator = ObjectAnimator.ofFloat(child, View.TRANSLATION_Z,
                child.getTranslationZ(), newEndValue);
        animator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        long newDuration = cancelAnimatorAndGetNewDuration(properties.duration, previousAnimator);
        animator.setDuration(newDuration);
        if (properties.delay > 0 && (previousAnimator == null
                || previousAnimator.getAnimatedFraction() == 0)) {
            animator.setStartDelay(properties.delay);
        }
        AnimatorListenerAdapter listener = properties.getAnimationFinishListener(
                View.TRANSLATION_Z);
        if (listener != null) {
            animator.addListener(listener);
        }
        // remove the tag when the animation is finished
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                child.setTag(TAG_ANIMATOR_TRANSLATION_Z, null);
                child.setTag(TAG_START_TRANSLATION_Z, null);
                child.setTag(TAG_END_TRANSLATION_Z, null);
            }
        });
        startAnimator(animator, listener);
        child.setTag(TAG_ANIMATOR_TRANSLATION_Z, animator);
        child.setTag(TAG_START_TRANSLATION_Z, child.getTranslationZ());
        child.setTag(TAG_END_TRANSLATION_Z, newEndValue);
    }

    private void updateAnimationX(View view) {
        startXTranslationAnimation(view, NO_NEW_ANIMATIONS);
    }

    private void startXTranslationAnimation(final View child, AnimationProperties properties) {
        Float previousStartValue = getChildTag(child, TAG_START_TRANSLATION_X);
        Float previousEndValue = getChildTag(child, TAG_END_TRANSLATION_X);
        float newEndValue = this.mXTranslation;
        if (previousEndValue != null && previousEndValue == newEndValue) {
            return;
        }
        ObjectAnimator previousAnimator = getChildTag(child, TAG_ANIMATOR_TRANSLATION_X);
        AnimationFilter filter = properties.getAnimationFilter();
        if (!filter.animateX) {
            // just a local update was performed
            if (previousAnimator != null) {
                // we need to increase all animation keyframes of the previous animator by the
                // relative change to the end value
                PropertyValuesHolder[] values = previousAnimator.getValues();
                float relativeDiff = newEndValue - previousEndValue;
                float newStartValue = previousStartValue + relativeDiff;
                values[0].setFloatValues(newStartValue, newEndValue);
                child.setTag(TAG_START_TRANSLATION_X, newStartValue);
                child.setTag(TAG_END_TRANSLATION_X, newEndValue);
                previousAnimator.setCurrentPlayTime(previousAnimator.getCurrentPlayTime());
                return;
            } else {
                // no new animation needed, let's just apply the value
                child.setTranslationX(newEndValue);
                return;
            }
        }

        ObjectAnimator animator = ObjectAnimator.ofFloat(child, View.TRANSLATION_X,
                child.getTranslationX(), newEndValue);
        Interpolator customInterpolator = properties.getCustomInterpolator(child,
                View.TRANSLATION_X);
        Interpolator interpolator = customInterpolator != null ? customInterpolator
                : Interpolators.FAST_OUT_SLOW_IN;
        animator.setInterpolator(interpolator);
        long newDuration = cancelAnimatorAndGetNewDuration(properties.duration, previousAnimator);
        animator.setDuration(newDuration);
        if (properties.delay > 0 && (previousAnimator == null
                || previousAnimator.getAnimatedFraction() == 0)) {
            animator.setStartDelay(properties.delay);
        }
        AnimatorListenerAdapter listener = properties.getAnimationFinishListener(
                View.TRANSLATION_X);
        if (listener != null) {
            animator.addListener(listener);
        }
        // remove the tag when the animation is finished
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                child.setTag(TAG_ANIMATOR_TRANSLATION_X, null);
                child.setTag(TAG_START_TRANSLATION_X, null);
                child.setTag(TAG_END_TRANSLATION_X, null);
            }
        });
        startAnimator(animator, listener);
        child.setTag(TAG_ANIMATOR_TRANSLATION_X, animator);
        child.setTag(TAG_START_TRANSLATION_X, child.getTranslationX());
        child.setTag(TAG_END_TRANSLATION_X, newEndValue);
    }

    private void updateAnimationY(View view) {
        startYTranslationAnimation(view, NO_NEW_ANIMATIONS);
    }

    private void startYTranslationAnimation(final View child, AnimationProperties properties) {
        Float previousStartValue = getChildTag(child, TAG_START_TRANSLATION_Y);
        Float previousEndValue = getChildTag(child, TAG_END_TRANSLATION_Y);
        float newEndValue = this.mYTranslation;
        if (previousEndValue != null && previousEndValue == newEndValue) {
            return;
        }
        ObjectAnimator previousAnimator = getChildTag(child, TAG_ANIMATOR_TRANSLATION_Y);
        AnimationFilter filter = properties.getAnimationFilter();
        if (!filter.animateY) {
            // just a local update was performed
            if (previousAnimator != null) {
                // we need to increase all animation keyframes of the previous animator by the
                // relative change to the end value
                PropertyValuesHolder[] values = previousAnimator.getValues();
                float relativeDiff = newEndValue - previousEndValue;
                float newStartValue = previousStartValue + relativeDiff;
                values[0].setFloatValues(newStartValue, newEndValue);
                child.setTag(TAG_START_TRANSLATION_Y, newStartValue);
                child.setTag(TAG_END_TRANSLATION_Y, newEndValue);
                previousAnimator.setCurrentPlayTime(previousAnimator.getCurrentPlayTime());
                return;
            } else {
                // no new animation needed, let's just apply the value
                child.setTranslationY(newEndValue);
                return;
            }
        }

        ObjectAnimator animator = ObjectAnimator.ofFloat(child, View.TRANSLATION_Y,
                child.getTranslationY(), newEndValue);
        Interpolator customInterpolator = properties.getCustomInterpolator(child,
                View.TRANSLATION_Y);
        Interpolator interpolator = customInterpolator != null ? customInterpolator
                : Interpolators.FAST_OUT_SLOW_IN;
        animator.setInterpolator(interpolator);
        long newDuration = cancelAnimatorAndGetNewDuration(properties.duration, previousAnimator);
        animator.setDuration(newDuration);
        if (properties.delay > 0 && (previousAnimator == null
                || previousAnimator.getAnimatedFraction() == 0)) {
            animator.setStartDelay(properties.delay);
        }
        AnimatorListenerAdapter listener = properties.getAnimationFinishListener(
                View.TRANSLATION_Y);
        if (listener != null) {
            animator.addListener(listener);
        }
        // remove the tag when the animation is finished
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                HeadsUpUtil.setNeedsHeadsUpDisappearAnimationAfterClick(child, false);
                child.setTag(TAG_ANIMATOR_TRANSLATION_Y, null);
                child.setTag(TAG_START_TRANSLATION_Y, null);
                child.setTag(TAG_END_TRANSLATION_Y, null);
                onYTranslationAnimationFinished(child);
            }
        });
        startAnimator(animator, listener);
        child.setTag(TAG_ANIMATOR_TRANSLATION_Y, animator);
        child.setTag(TAG_START_TRANSLATION_Y, child.getTranslationY());
        child.setTag(TAG_END_TRANSLATION_Y, newEndValue);
    }

    protected void onYTranslationAnimationFinished(View view) {
        if (hidden && !gone) {
            view.setVisibility(View.INVISIBLE);
        }
    }

    public static void startAnimator(Animator animator, AnimatorListenerAdapter listener) {
        if (listener != null) {
            // Even if there's a delay we'd want to notify it of the start immediately.
            listener.onAnimationStart(animator);
        }
        animator.start();
    }

    public static <T> T getChildTag(View child, int tag) {
        return (T) child.getTag(tag);
    }

    protected void abortAnimation(View child, int animatorTag) {
        Animator previousAnimator = getChildTag(child, animatorTag);
        if (previousAnimator != null) {
            previousAnimator.cancel();
        }
    }

    /**
     * Cancel the previous animator and get the duration of the new animation.
     *
     * @param duration         the new duration
     * @param previousAnimator the animator which was running before
     * @return the new duration
     */
    public static long cancelAnimatorAndGetNewDuration(long duration,
            ValueAnimator previousAnimator) {
        long newDuration = duration;
        if (previousAnimator != null) {
            // We take either the desired length of the new animation or the remaining time of
            // the previous animator, whichever is longer.
            newDuration = Math.max(previousAnimator.getDuration()
                    - previousAnimator.getCurrentPlayTime(), newDuration);
            previousAnimator.cancel();
        }
        return newDuration;
    }

    /**
     * Get the end value of the xTranslation animation running on a view or the xTranslation
     * if no animation is running.
     */
    public static float getFinalTranslationX(View view) {
        if (view == null) {
            return 0;
        }
        ValueAnimator xAnimator = getChildTag(view, TAG_ANIMATOR_TRANSLATION_X);
        if (xAnimator == null) {
            return view.getTranslationX();
        } else {
            return getChildTag(view, TAG_END_TRANSLATION_X);
        }
    }

    /**
     * Get the end value of the yTranslation animation running on a view or the yTranslation
     * if no animation is running.
     */
    public static float getFinalTranslationY(View view) {
        if (view == null) {
            return 0;
        }
        ValueAnimator yAnimator = getChildTag(view, TAG_ANIMATOR_TRANSLATION_Y);
        if (yAnimator == null) {
            return view.getTranslationY();
        } else {
            return getChildTag(view, TAG_END_TRANSLATION_Y);
        }
    }

    /**
     * Get the end value of the zTranslation animation running on a view or the zTranslation
     * if no animation is running.
     */
    public static float getFinalTranslationZ(View view) {
        if (view == null) {
            return 0;
        }
        ValueAnimator zAnimator = getChildTag(view, TAG_ANIMATOR_TRANSLATION_Z);
        if (zAnimator == null) {
            return view.getTranslationZ();
        } else {
            return getChildTag(view, TAG_END_TRANSLATION_Z);
        }
    }

    public static boolean isAnimatingY(View child) {
        return getChildTag(child, TAG_ANIMATOR_TRANSLATION_Y) != null;
    }

    public void cancelAnimations(View view) {
        Animator animator = getChildTag(view, TAG_ANIMATOR_TRANSLATION_X);
        if (animator != null) {
            animator.cancel();
        }
        animator = getChildTag(view, TAG_ANIMATOR_TRANSLATION_Y);
        if (animator != null) {
            animator.cancel();
        }
        animator = getChildTag(view, TAG_ANIMATOR_TRANSLATION_Z);
        if (animator != null) {
            animator.cancel();
        }
        animator = getChildTag(view, TAG_ANIMATOR_ALPHA);
        if (animator != null) {
            animator.cancel();
        }
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        StringBuilder result = new StringBuilder();
        result.append("ViewState { ");

        boolean first = true;
        Class currentClass = this.getClass();
        while (currentClass != null) {
            Field[] fields = currentClass.getDeclaredFields();
            // Print field names paired with their values
            for (Field field : fields) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || field.isSynthetic()
                        || Modifier.isTransient(modifiers)) {
                    continue;
                }
                if (!first) {
                    result.append(", ");
                }
                try {
                    result.append(field.getName());
                    result.append(": ");
                    //requires access to private field:
                    field.setAccessible(true);
                    result.append(field.get(this));
                } catch (IllegalAccessException ex) {
                }
                first = false;
            }
            currentClass = currentClass.getSuperclass();
        }
        result.append(" }");
        pw.print(result);
    }
}
