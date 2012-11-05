/*
 * Copyright (C) 2012 The Android Open Source Project
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
package com.android.internal.policy.impl.keyguard;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

import java.util.ArrayList;

import com.android.internal.R;

public class KeyguardWidgetCarousel extends KeyguardWidgetPager {

    private float mAdjacentPagesAngle;
    private static float MAX_SCROLL_PROGRESS = 1.3f;
    private static float CAMERA_DISTANCE = 10000;
    protected AnimatorSet mChildrenTransformsAnimator;
    float[] mTmpTransform = new float[3];

    public KeyguardWidgetCarousel(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardWidgetCarousel(Context context) {
        this(context, null, 0);
    }

    public KeyguardWidgetCarousel(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mAdjacentPagesAngle = context.getResources().getInteger(R.integer.kg_carousel_angle);
    }

    protected float getMaxScrollProgress() {
        return MAX_SCROLL_PROGRESS;
    }

    public float getAlphaForPage(int screenCenter, int index) {
        View child = getChildAt(index);
        if (child == null) return 0f;

        float scrollProgress = getScrollProgress(screenCenter, child, index);
        if (!isOverScrollChild(index, scrollProgress)) {
            scrollProgress = getBoundedScrollProgress(screenCenter, child, index);
            float alpha = 1.0f - 1.0f * Math.abs(scrollProgress / MAX_SCROLL_PROGRESS);
            return alpha;
        } else {
            return 1.0f;
        }
    }

    private void updatePageAlphaValues(int screenCenter) {
        if (mChildrenOutlineFadeAnimation != null) {
            mChildrenOutlineFadeAnimation.cancel();
            mChildrenOutlineFadeAnimation = null;
        }
        if (!isReordering(false)) {
            for (int i = 0; i < getChildCount(); i++) {
                KeyguardWidgetFrame child = getWidgetPageAt(i);
                if (child != null) {
                    child.setBackgroundAlpha(getOutlineAlphaForPage(screenCenter, i));
                    child.setContentAlpha(getAlphaForPage(screenCenter, i));
                }
            }
        }
    }

    public void showInitialPageHints() {
        int count = getChildCount();
        for (int i = 0; i < count; i++) {
            KeyguardWidgetFrame child = getWidgetPageAt(i);
            if (i >= mCurrentPage - 1 && i <= mCurrentPage + 1) {
                child.fadeFrame(this, true, KeyguardWidgetFrame.OUTLINE_ALPHA_MULTIPLIER,
                        CHILDREN_OUTLINE_FADE_IN_DURATION);
            }
        }
    }

    @Override
    protected void screenScrolled(int screenCenter) {
        mScreenCenter = screenCenter;
        updatePageAlphaValues(screenCenter);
        if (isReordering(false)) return;
        for (int i = 0; i < getChildCount(); i++) {
            KeyguardWidgetFrame v = getWidgetPageAt(i);
            float scrollProgress = getScrollProgress(screenCenter, v, i);
            float boundedProgress = getBoundedScrollProgress(screenCenter, v, i);
            if (v == mDragView || v == null) continue;
            v.setCameraDistance(CAMERA_DISTANCE);

            if (isOverScrollChild(i, scrollProgress)) {
                v.setRotationY(- OVERSCROLL_MAX_ROTATION * scrollProgress);
                v.setOverScrollAmount(Math.abs(scrollProgress), scrollProgress < 0);
            } else {
                int width = v.getMeasuredWidth();
                float pivotX = (width / 2f) + boundedProgress * (width / 2f);
                float pivotY = v.getMeasuredHeight() / 2;
                float rotationY = - mAdjacentPagesAngle * boundedProgress;
                v.setPivotX(pivotX);
                v.setPivotY(pivotY);
                v.setRotationY(rotationY);
                v.setOverScrollAmount(0f, false);
            }
            float alpha = v.getAlpha();
            // If the view has 0 alpha, we set it to be invisible so as to prevent
            // it from accepting touches
            if (alpha == 0) {
                v.setVisibility(INVISIBLE);
            } else if (v.getVisibility() != VISIBLE) {
                v.setVisibility(VISIBLE);
            }
        }
    }

    void animatePagesToNeutral() {
        if (mChildrenTransformsAnimator != null) {
            mChildrenTransformsAnimator.cancel();
            mChildrenTransformsAnimator = null;
        }

        int count = getChildCount();
        PropertyValuesHolder alpha;
        PropertyValuesHolder outlineAlpha;
        PropertyValuesHolder rotationY;
        ArrayList<Animator> anims = new ArrayList<Animator>();

        for (int i = 0; i < count; i++) {
            KeyguardWidgetFrame child = getWidgetPageAt(i);
            boolean inVisibleRange = (i >= mCurrentPage - 1 && i <= mCurrentPage + 1);
            if (!inVisibleRange) {
                child.setRotationY(0f);
            }
            alpha = PropertyValuesHolder.ofFloat("contentAlpha", 1.0f);
            outlineAlpha = PropertyValuesHolder.ofFloat("backgroundAlpha",
                    KeyguardWidgetFrame.OUTLINE_ALPHA_MULTIPLIER);
            rotationY = PropertyValuesHolder.ofFloat("rotationY", 0f);
            ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(child, alpha, outlineAlpha, rotationY);
            child.setVisibility(VISIBLE);
            if (!inVisibleRange) {
                a.setInterpolator(mSlowFadeInterpolator);
            }
            anims.add(a);
        }

        int duration = REORDERING_ZOOM_IN_OUT_DURATION;
        mChildrenTransformsAnimator = new AnimatorSet();
        mChildrenTransformsAnimator.playTogether(anims);

        mChildrenTransformsAnimator.setDuration(duration);
        mChildrenTransformsAnimator.start();
    }

    private void getTransformForPage(int screenCenter, int index, float[] transform) {
        View child = getChildAt(index);
        float boundedProgress = getBoundedScrollProgress(screenCenter, child, index);
        float rotationY = - mAdjacentPagesAngle * boundedProgress;
        int width = child.getMeasuredWidth();
        float pivotX = (width / 2f) + boundedProgress * (width / 2f);
        float pivotY = child.getMeasuredHeight() / 2;

        transform[0] = pivotX;
        transform[1] = pivotY;
        transform[2] = rotationY;
    }

    Interpolator mFastFadeInterpolator = new Interpolator() {
        Interpolator mInternal = new DecelerateInterpolator(1.5f);
        float mFactor = 2.5f;
        @Override
        public float getInterpolation(float input) {
            return mInternal.getInterpolation(Math.min(mFactor * input, 1f));
        }
    };

    Interpolator mSlowFadeInterpolator = new Interpolator() {
        Interpolator mInternal = new AccelerateInterpolator(1.5f);
        float mFactor = 1.3f;
        @Override
        public float getInterpolation(float input) {
            input -= (1 - 1 / mFactor);
            input = mFactor * Math.max(input, 0f);
            return mInternal.getInterpolation(input);
        }
    };

    void animatePagesToCarousel() {
        if (mChildrenTransformsAnimator != null) {
            mChildrenTransformsAnimator.cancel();
            mChildrenTransformsAnimator = null;
        }

        int count = getChildCount();
        PropertyValuesHolder alpha;
        PropertyValuesHolder outlineAlpha;
        PropertyValuesHolder rotationY;
        PropertyValuesHolder pivotX;
        PropertyValuesHolder pivotY;
        ArrayList<Animator> anims = new ArrayList<Animator>();

        for (int i = 0; i < count; i++) {
            KeyguardWidgetFrame child = getWidgetPageAt(i);
            float finalAlpha = getAlphaForPage(mScreenCenter, i);
            float finalOutlineAlpha = getOutlineAlphaForPage(mScreenCenter, i);
            getTransformForPage(mScreenCenter, i, mTmpTransform);

            boolean inVisibleRange = (i >= mCurrentPage - 1 && i <= mCurrentPage + 1);

            ObjectAnimator a;
            alpha = PropertyValuesHolder.ofFloat("contentAlpha", finalAlpha);
            outlineAlpha = PropertyValuesHolder.ofFloat("backgroundAlpha", finalOutlineAlpha);
            pivotX = PropertyValuesHolder.ofFloat("pivotX", mTmpTransform[0]);
            pivotY = PropertyValuesHolder.ofFloat("pivotY", mTmpTransform[1]);
            rotationY = PropertyValuesHolder.ofFloat("rotationY", mTmpTransform[2]);

            if (inVisibleRange) {
                // for the central pages we animate into a rotated state
                a = ObjectAnimator.ofPropertyValuesHolder(child, alpha, outlineAlpha,
                        pivotX, pivotY, rotationY);
            } else {
                a = ObjectAnimator.ofPropertyValuesHolder(child, alpha, outlineAlpha);
                a.setInterpolator(mFastFadeInterpolator);
            }
            anims.add(a);
        }

        int duration = REORDERING_ZOOM_IN_OUT_DURATION;
        mChildrenTransformsAnimator = new AnimatorSet();
        mChildrenTransformsAnimator.playTogether(anims);

        mChildrenTransformsAnimator.setDuration(duration);
        mChildrenTransformsAnimator.start();
    }

    protected void reorderStarting() {
        mViewStateManager.fadeOutSecurity(REORDERING_ZOOM_IN_OUT_DURATION);
        animatePagesToNeutral();
    }

    protected boolean zoomIn(final Runnable onCompleteRunnable) {
        animatePagesToCarousel();
        return super.zoomIn(onCompleteRunnable);
    }

    @Override
    protected void onEndReordering() {
        super.onEndReordering();
        mViewStateManager.fadeInSecurity(REORDERING_ZOOM_IN_OUT_DURATION);
    }
}
