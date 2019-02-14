/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.bubbles.animation;

import android.content.res.Resources;
import android.graphics.PointF;
import android.view.View;
import android.view.WindowInsets;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.android.systemui.R;

import com.google.android.collect.Sets;

import java.util.Set;

/**
 * Animation controller for bubbles when they're in their expanded state, or animating to/from the
 * expanded state. This controls the expansion animation as well as bubbles 'dragging out' to be
 * dismissed.
 */
public class ExpandedAnimationController
        extends PhysicsAnimationLayout.PhysicsAnimationController {

    /**
     * How much to translate the bubbles when they're animating in/out. This value is multiplied by
     * the bubble size.
     */
    private static final int ANIMATE_TRANSLATION_FACTOR = 4;

    /**
     * The stack position from which the bubbles were expanded. Saved in {@link #expandFromStack}
     * and used to return to stack form in {@link #collapseBackToStack}.
     */
    private PointF mExpandedFrom;

    /** Horizontal offset between bubbles, which we need to know to re-stack them. */
    private float mStackOffsetPx;
    /** Spacing between bubbles in the expanded state. */
    private float mBubblePaddingPx;
    /** Size of each bubble. */
    private float mBubbleSizePx;
    /** Height of the status bar. */
    private float mStatusBarHeight;

    @Override
    protected void setLayout(PhysicsAnimationLayout layout) {
        super.setLayout(layout);

        final Resources res = layout.getResources();
        mStackOffsetPx = res.getDimensionPixelSize(R.dimen.bubble_stack_offset);
        mBubblePaddingPx = res.getDimensionPixelSize(R.dimen.bubble_padding);
        mBubbleSizePx = res.getDimensionPixelSize(R.dimen.individual_bubble_size);
        mStatusBarHeight =
                res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
    }

    /**
     * Animates expanding the bubbles into a row along the top of the screen.
     *
     * @return The y-value to which the bubbles were expanded, in case that's useful.
     */
    public float expandFromStack(PointF expandedFrom, Runnable after) {
        mExpandedFrom = expandedFrom;

        // How much to translate the next bubble, so that it is not overlapping the previous one.
        float translateNextBubbleXBy = mBubblePaddingPx;
        for (int i = 0; i < mLayout.getChildCount(); i++) {
            mLayout.animatePositionForChildAtIndex(i, translateNextBubbleXBy, getExpandedY());
            translateNextBubbleXBy += mBubbleSizePx + mBubblePaddingPx;
        }

        runAfterTranslationsEnd(after);
        return getExpandedY();
    }

    /** Animate collapsing the bubbles back to their stacked position. */
    public void collapseBackToStack(Runnable after) {
        // Stack to the left if we're going to the left, or right if not.
        final float sideMultiplier = mLayout.isFirstChildXLeftOfCenter(mExpandedFrom.x) ? -1 : 1;
        for (int i = 0; i < mLayout.getChildCount(); i++) {
            mLayout.animatePositionForChildAtIndex(
                    i, mExpandedFrom.x + (sideMultiplier * i * mStackOffsetPx), mExpandedFrom.y);
        }

        runAfterTranslationsEnd(after);
    }

    /** The Y value of the row of expanded bubbles. */
    private float getExpandedY() {
        final WindowInsets insets = mLayout.getRootWindowInsets();
        if (insets != null) {
            return mBubblePaddingPx + Math.max(
                    mStatusBarHeight,
                    insets.getDisplayCutout() != null
                            ? insets.getDisplayCutout().getSafeInsetTop()
                            : 0);
        }

        return mBubblePaddingPx;
    }

    /** Runs the given Runnable after all translation-related animations have ended. */
    private void runAfterTranslationsEnd(Runnable after) {
        DynamicAnimation.OnAnimationEndListener allEndedListener =
                (animation, canceled, value, velocity) -> {
                    if (!mLayout.arePropertiesAnimating(
                            DynamicAnimation.TRANSLATION_X,
                            DynamicAnimation.TRANSLATION_Y)) {
                        after.run();
                    }
                };

        mLayout.setEndListenerForProperty(allEndedListener, DynamicAnimation.TRANSLATION_X);
        mLayout.setEndListenerForProperty(allEndedListener, DynamicAnimation.TRANSLATION_Y);
    }

    @Override
    Set<DynamicAnimation.ViewProperty> getAnimatedProperties() {
        return Sets.newHashSet(
                DynamicAnimation.TRANSLATION_X,
                DynamicAnimation.TRANSLATION_Y,
                DynamicAnimation.SCALE_X,
                DynamicAnimation.SCALE_Y,
                DynamicAnimation.ALPHA);
    }

    @Override
    int getNextAnimationInChain(DynamicAnimation.ViewProperty property, int index) {
        return NONE;
    }

    @Override
    float getOffsetForChainedPropertyAnimation(DynamicAnimation.ViewProperty property) {
        return 0;
    }

    @Override
    SpringForce getSpringForce(DynamicAnimation.ViewProperty property, View view) {
        return new SpringForce()
                .setStiffness(SpringForce.STIFFNESS_LOW)
                .setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY);
    }

    @Override
    void onChildAdded(View child, int index) {
        // Pop in from the top.
        // TODO: Reverse this when bubbles are at the bottom.
        child.setTranslationX(getXForChildAtIndex(index));
        child.setTranslationY(getExpandedY() - mBubbleSizePx * ANIMATE_TRANSLATION_FACTOR);
        mLayout.animateValueForChild(DynamicAnimation.TRANSLATION_Y, child, getExpandedY());

        // Animate the remaining bubbles to the correct X position.
        for (int i = index + 1; i < mLayout.getChildCount(); i++) {
            mLayout.animateValueForChildAtIndex(
                    DynamicAnimation.TRANSLATION_X, i, getXForChildAtIndex(i));
        }
    }

    @Override
    void onChildRemoved(View child, int index, Runnable finishRemoval) {
        // Bubble pops out to the top.
        // TODO: Reverse this when bubbles are at the bottom.
        mLayout.animateValueForChild(
                DynamicAnimation.ALPHA, child, 0f, finishRemoval);
        mLayout.animateValueForChild(
                DynamicAnimation.TRANSLATION_Y,
                child,
                getExpandedY() - mBubbleSizePx * ANIMATE_TRANSLATION_FACTOR);

        // Animate the remaining bubbles to the correct X position.
        for (int i = index; i < mLayout.getChildCount(); i++) {
            mLayout.animateValueForChildAtIndex(
                    DynamicAnimation.TRANSLATION_X, i, getXForChildAtIndex(i));
        }
    }

    @Override
    protected void setChildVisibility(View child, int index, int visibility) {
        if (visibility == View.VISIBLE) {
            // Set alpha to 0 but then become visible immediately so the animation is visible.
            child.setAlpha(0f);
            child.setVisibility(View.VISIBLE);
        }

        // Fade in.
        mLayout.animateValueForChild(
                DynamicAnimation.ALPHA,
                child,
                /* value */ visibility == View.GONE ? 0f : 1f,
                () -> super.setChildVisibility(child, index, visibility));
    }

    /** Returns the appropriate X translation value for a bubble at the given index. */
    private float getXForChildAtIndex(int index) {
        return mBubblePaddingPx + (mBubbleSizePx + mBubblePaddingPx) * index;
    }
}
