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

    /** The stack position to collapse back to in {@link #collapseBackToStack}. */
    private PointF mCollapseToPoint;

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
    public float expandFromStack(PointF collapseTo, Runnable after) {
        animationsForChildrenFromIndex(
                0, /* startIndex */
                new ChildAnimationConfigurator() {
                    // How much to translate the next bubble, so that it is not overlapping the
                    // previous one.
                    float mTranslateNextBubbleXBy = mBubblePaddingPx;

                    @Override
                    public void configureAnimationForChildAtIndex(
                            int index, PhysicsAnimationLayout.PhysicsPropertyAnimator animation) {
                        animation.position(mTranslateNextBubbleXBy, getExpandedY());
                        mTranslateNextBubbleXBy += mBubbleSizePx + mBubblePaddingPx;
                    }
            })
            .startAll(after);

        mCollapseToPoint = collapseTo;
        return getExpandedY();
    }

    /** Animate collapsing the bubbles back to their stacked position. */
    public void collapseBackToStack(Runnable after) {
        // Stack to the left if we're going to the left, or right if not.
        final float sideMultiplier = mLayout.isFirstChildXLeftOfCenter(mCollapseToPoint.x) ? -1 : 1;

        animationsForChildrenFromIndex(
                0, /* startIndex */
                (index, animation) ->
                    animation.position(
                            mCollapseToPoint.x + (sideMultiplier * index * mStackOffsetPx),
                            mCollapseToPoint.y))
            .startAll(after /* endAction */);
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

        animationForChildAtIndex(index)
                .position(getXForChildAtIndex(index), getExpandedY())
                .withPositionStartVelocities(velX, velY)
                .start(() -> bubbleView.setTranslationZ(0f) /* after */);

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
     * Animates the bubbles to {@link #getExpandedY()} position. Used in response to IME showing.
     */
    public void updateYPosition(Runnable after) {
        if (mLayout == null) return;
        animationsForChildrenFromIndex(
                0, (i, anim) -> anim.translationY(getExpandedY())).startAll(after);
    }

    /**
     * Animates the bubbles, starting at the given index, to the left or right by the given number
     * of bubble widths. Passing zero for numBubbleWidths will animate the bubbles to their normal
     * positions.
     */
    private void animateStackByBubbleWidthsStartingFrom(int numBubbleWidths, int startIndex) {
        animationsForChildrenFromIndex(
                startIndex,
                (index, animation) ->
                        animation.translationX(getXForChildAtIndex(index + numBubbleWidths)))
            .startAll();
    }

    /** The Y value of the row of expanded bubbles. */
    public float getExpandedY() {
        if (mLayout == null || mLayout.getRootWindowInsets() == null) {
            return 0;
        }
        final boolean showOnTop = BubbleController.showBubblesAtTop(mLayout.getContext());
        final WindowInsets insets = mLayout.getRootWindowInsets();
        if (showOnTop) {
            return mBubblePaddingPx + Math.max(
                    mStatusBarHeight,
                    insets.getDisplayCutout() != null
                            ? insets.getDisplayCutout().getSafeInsetTop()
                            : 0);
        } else {
            int keyboardHeight = insets.getSystemWindowInsetBottom()
                    - insets.getStableInsetBottom();
            float bottomInset = keyboardHeight > 0
                    ? keyboardHeight
                    : (mPipDismissHeight - insets.getStableInsetBottom());
            // Stable insets are excluded from display size, so we must subtract it
            return mDisplaySize.y - mBubbleSizePx - mBubblePaddingPx - bottomInset;
        }
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
                .setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY)
                .setStiffness(SpringForce.STIFFNESS_LOW);
    }

    @Override
    void onChildAdded(View child, int index) {
        // Pop in from the top.
        // TODO: Reverse this when bubbles are at the bottom.
        child.setTranslationX(getXForChildAtIndex(index));

        animationForChild(child)
                .translationY(
                        getExpandedY() - mBubbleSizePx * ANIMATE_TRANSLATION_FACTOR, /* from */
                        getExpandedY() /* to */)
                .start();
        animateBubblesAfterIndexToCorrectX(index);
    }

    @Override
    void onChildRemoved(View child, int index, Runnable finishRemoval) {
        // Bubble pops out to the top.
        // TODO: Reverse this when bubbles are at the bottom.

        final PhysicsAnimationLayout.PhysicsPropertyAnimator animator = animationForChild(child);
        animator.alpha(0f, finishRemoval /* endAction */);

        // If we're removing the dragged-out bubble, that means it got dismissed.
        if (child.equals(mBubbleDraggingOut)) {
            animator.position(
                            mLayout.getWidth() / 2f - mBubbleSizePx / 2f,
                            mLayout.getHeight() + mBubbleSizePx)
                    .withPositionStartVelocities(mBubbleDraggingOutVelX, mBubbleDraggingOutVelY)
                    .scaleX(ANIMATE_SCALE_PERCENT)
                    .scaleY(ANIMATE_SCALE_PERCENT);

            mBubbleDraggingOut = null;
        } else {
            animator.translationY(getExpandedY() - mBubbleSizePx * ANIMATE_TRANSLATION_FACTOR);
        }

        animator.start();

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

        animationForChild(child)
                .alpha(visibility == View.GONE ? 0f : 1f)
                .start(() -> super.setChildVisibility(child, index, visibility) /* after */);
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
                animationForChild(bubble)
                        .translationX(getXForChildAtIndex(i))
                        .start();
            }
        }
    }

    /** Returns the appropriate X translation value for a bubble at the given index. */
    private float getXForChildAtIndex(int index) {
        return mBubblePaddingPx + (mBubbleSizePx + mBubblePaddingPx) * index;
    }
}
