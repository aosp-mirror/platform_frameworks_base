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
import android.graphics.Point;
import android.graphics.PointF;
import android.view.View;
import android.view.WindowInsets;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.android.systemui.R;
import com.android.systemui.bubbles.BubbleController;

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

    /** How much to scale down bubbles when they're animating in/out. */
    private static final float ANIMATE_SCALE_PERCENT = 0.5f;

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
    /** Size of display. */
    private Point mDisplaySize;
    /** Size of dismiss target at bottom of screen. */
    private float mPipDismissHeight;

    public ExpandedAnimationController(Point displaySize) {
        mDisplaySize = displaySize;
    }

    /**
     * Whether the individual bubble has been dragged out of the row of bubbles far enough to cause
     * the rest of the bubbles to animate to fill the gap.
     */
    private boolean mBubbleDraggedOutEnough = false;

    /** The bubble currently being dragged out of the row (to potentially be dismissed). */
    private View mBubbleDraggingOut;

    /**
     * Drag velocities for the dragging-out bubble when the drag finished. These are used by
     * {@link #onChildRemoved} to animate out the bubble while respecting touch velocity.
     */
    private float mBubbleDraggingOutVelX;
    private float mBubbleDraggingOutVelY;

    @Override
    protected void setLayout(PhysicsAnimationLayout layout) {
        super.setLayout(layout);

        final Resources res = layout.getResources();
        mStackOffsetPx = res.getDimensionPixelSize(R.dimen.bubble_stack_offset);
        mBubblePaddingPx = res.getDimensionPixelSize(R.dimen.bubble_padding);
        mBubbleSizePx = res.getDimensionPixelSize(R.dimen.individual_bubble_size);
        mStatusBarHeight =
                res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
        mPipDismissHeight = res.getDimensionPixelSize(R.dimen.pip_dismiss_gradient_height);
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

    /** Prepares the given bubble to be dragged out. */
    public void prepareForBubbleDrag(View bubble) {
        mLayout.cancelAnimationsOnView(bubble);

        mBubbleDraggingOut = bubble;
        mBubbleDraggingOut.setTranslationZ(Short.MAX_VALUE);
    }

    /**
     * Drags an individual bubble to the given coordinates. Bubbles to the right will animate to
     * take its place once it's dragged out of the row of bubbles, and animate out of the way if the
     * bubble is dragged back into the row.
     */
    public void dragBubbleOut(View bubbleView, float x, float y) {
        bubbleView.setTranslationX(x);
        bubbleView.setTranslationY(y);

        final boolean draggedOutEnough =
                y > getExpandedY() + mBubbleSizePx || y < getExpandedY() - mBubbleSizePx;
        if (draggedOutEnough != mBubbleDraggedOutEnough) {
            animateStackByBubbleWidthsStartingFrom(
                    /* numBubbleWidths */ draggedOutEnough ? -1 : 0,
                    /* startIndex */ mLayout.indexOfChild(bubbleView) + 1);
            mBubbleDraggedOutEnough = draggedOutEnough;
        }
    }

    /**
     * Snaps a bubble back to its position within the bubble row, and animates the rest of the
     * bubbles to accommodate it if it was previously dragged out past the threshold.
     */
    public void snapBubbleBack(View bubbleView, float velX, float velY) {
        final int index = mLayout.indexOfChild(bubbleView);

        // Snap the bubble back, respecting its current velocity.
        mLayout.animateValueForChildAtIndex(
                DynamicAnimation.TRANSLATION_X, index, getXForChildAtIndex(index), velX);
        mLayout.animateValueForChildAtIndex(
                DynamicAnimation.TRANSLATION_Y, index, getExpandedY(), velY);
        mLayout.setEndListenerForProperties(
                mLayout.new OneTimeMultiplePropertyEndListener() {
                    @Override
                    void onAllAnimationsForPropertiesEnd() {
                        // Reset Z translation once the bubble is done snapping back.
                        bubbleView.setTranslationZ(0f);
                    }
                },
                DynamicAnimation.TRANSLATION_X, DynamicAnimation.TRANSLATION_Y);

        animateStackByBubbleWidthsStartingFrom(
                /* numBubbleWidths */ 0, /* startIndex */ index + 1);

        mBubbleDraggingOut = null;
        mBubbleDraggedOutEnough = false;
    }

    /**
     * Sets configuration variables so that when the given bubble is removed, the animations are
     * started with the given velocities.
     */
    public void prepareForDismissalWithVelocity(View bubbleView, float velX, float velY) {
        mBubbleDraggingOut = bubbleView;
        mBubbleDraggingOutVelX = velX;
        mBubbleDraggingOutVelY = velY;
        mBubbleDraggedOutEnough = false;
    }

    /**
     * Animates the bubbles, starting at the given index, to the left or right by the given number
     * of bubble widths. Passing zero for numBubbleWidths will animate the bubbles to their normal
     * positions.
     */
    private void animateStackByBubbleWidthsStartingFrom(int numBubbleWidths, int startIndex) {
        for (int i = startIndex; i < mLayout.getChildCount(); i++) {
            mLayout.animateValueForChildAtIndex(
                    DynamicAnimation.TRANSLATION_X,
                    i,
                    getXForChildAtIndex(i + numBubbleWidths));
        }
    }

    /** The Y value of the row of expanded bubbles. */
    public float getExpandedY() {
        boolean showOnTop = mLayout != null
                && BubbleController.showBubblesAtTop(mLayout.getContext());
        final WindowInsets insets = mLayout != null ? mLayout.getRootWindowInsets() : null;
        if (showOnTop && insets != null) {
            return mBubblePaddingPx + Math.max(
                    mStatusBarHeight,
                    insets.getDisplayCutout() != null
                            ? insets.getDisplayCutout().getSafeInsetTop()
                            : 0);
        } else {
            int bottomInset = insets != null ? insets.getSystemWindowInsetBottom() : 0;
            return mDisplaySize.y - mBubbleSizePx - (mPipDismissHeight - bottomInset);
        }
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
        animateBubblesAfterIndexToCorrectX(index);
    }

    @Override
    void onChildRemoved(View child, int index, Runnable finishRemoval) {
        // Bubble pops out to the top.
        // TODO: Reverse this when bubbles are at the bottom.
        mLayout.animateValueForChild(
                DynamicAnimation.ALPHA, child, 0f, finishRemoval);

        // If we're removing the dragged-out bubble, that means it got dismissed.
        if (child.equals(mBubbleDraggingOut)) {
            // Throw it to the bottom of the screen, towards the center horizontally.
            mLayout.animateValueForChild(
                    DynamicAnimation.TRANSLATION_X,
                    child,
                    mLayout.getWidth() / 2f - mBubbleSizePx / 2f,
                    mBubbleDraggingOutVelX);
            mLayout.animateValueForChild(
                    DynamicAnimation.TRANSLATION_Y,
                    child,
                    mLayout.getHeight() + mBubbleSizePx,
                    mBubbleDraggingOutVelY);

            // Scale it down a bit so it looks like it's disappearing.
            mLayout.animateValueForChild(DynamicAnimation.SCALE_X, child, ANIMATE_SCALE_PERCENT);
            mLayout.animateValueForChild(DynamicAnimation.SCALE_Y, child, ANIMATE_SCALE_PERCENT);

            mBubbleDraggingOut = null;
        } else {
            // If we're removing some random bubble just throw it off the top.
            mLayout.animateValueForChild(
                    DynamicAnimation.TRANSLATION_Y,
                    child,
                    getExpandedY() - mBubbleSizePx * ANIMATE_TRANSLATION_FACTOR);
        }

        // Animate all the other bubbles to their new positions sans this bubble.
        animateBubblesAfterIndexToCorrectX(index);
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

    /**
     * Animates the bubbles after the given index to the X position they should be in according to
     * {@link #getXForChildAtIndex}.
     */
    private void animateBubblesAfterIndexToCorrectX(int start) {
        for (int i = start; i < mLayout.getChildCount(); i++) {
            final View bubble = mLayout.getChildAt(i);

            // Don't animate the dragging out bubble, or it'll jump around while being dragged. It
            // will be snapped to the correct X value after the drag (if it's not dismissed).
            if (!bubble.equals(mBubbleDraggingOut)) {
                mLayout.animateValueForChild(
                        DynamicAnimation.TRANSLATION_X, bubble, getXForChildAtIndex(i));
            }
        }
    }

    /** Returns the appropriate X translation value for a bubble at the given index. */
    private float getXForChildAtIndex(int index) {
        return mBubblePaddingPx + (mBubbleSizePx + mBubblePaddingPx) * index;
    }
}
