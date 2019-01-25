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

    @Override
    protected void setLayout(PhysicsAnimationLayout layout) {
        super.setLayout(layout);
        mStackOffsetPx = layout.getResources().getDimensionPixelSize(
                R.dimen.bubble_stack_offset);
        mBubblePaddingPx = layout.getResources().getDimensionPixelSize(
                R.dimen.bubble_padding);
        mBubbleSizePx = layout.getResources().getDimensionPixelSize(
                R.dimen.individual_bubble_size);
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
                    insets.getSystemWindowInsetTop(),
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
                DynamicAnimation.TRANSLATION_Y);
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
        // TODO: Animate the new bubble into the row, and push the other bubbles out of the way.
        child.setTranslationY(getExpandedY());
    }

    @Override
    void onChildToBeRemoved(View child, int index, Runnable actuallyRemove) {
        // TODO: Animate the bubble out, and pull the other bubbles into its position.
        actuallyRemove.run();
    }
}
