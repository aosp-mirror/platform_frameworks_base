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

import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PointF;
import android.view.DisplayCutout;
import android.view.View;
import android.view.WindowInsets;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.util.magnetictarget.MagnetizedObject;

import com.google.android.collect.Sets;

import java.io.FileDescriptor;
import java.io.PrintWriter;
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

    /** Duration of the expand/collapse target path animation. */
    private static final int EXPAND_COLLAPSE_TARGET_ANIM_DURATION = 175;

    /** Stiffness for the expand/collapse path-following animation. */
    private static final int EXPAND_COLLAPSE_ANIM_STIFFNESS = 1000;

    /** What percentage of the screen to use when centering the bubbles in landscape. */
    private static final float CENTER_BUBBLES_LANDSCAPE_PERCENT = 0.66f;

    /**
     * Velocity required to dismiss an individual bubble without dragging it into the dismiss
     * target.
     */
    private static final float FLING_TO_DISMISS_MIN_VELOCITY = 6000f;

    /** Horizontal offset between bubbles, which we need to know to re-stack them. */
    private float mStackOffsetPx;
    /** Space between status bar and bubbles in the expanded state. */
    private float mBubblePaddingTop;
    /** Size of each bubble. */
    private float mBubbleSizePx;
    /** Space between bubbles in row above expanded view. */
    private float mSpaceBetweenBubbles;
    /** Height of the status bar. */
    private float mStatusBarHeight;
    /** Size of display. */
    private Point mDisplaySize;
    /** Max number of bubbles shown in row above expanded view. */
    private int mBubblesMaxRendered;
    /** What the current screen orientation is. */
    private int mScreenOrientation;

    private boolean mAnimatingExpand = false;
    private boolean mAnimatingCollapse = false;
    private @Nullable Runnable mAfterExpand;
    private Runnable mAfterCollapse;
    private PointF mCollapsePoint;

    /**
     * Whether the dragged out bubble is springing towards the touch point, rather than using the
     * default behavior of moving directly to the touch point.
     *
     * This happens when the user's finger exits the dismiss area while the bubble is magnetized to
     * the center. Since the touch point differs from the bubble location, we need to animate the
     * bubble back to the touch point to avoid a jarring instant location change from the center of
     * the target to the touch point just outside the target bounds.
     */
    private boolean mSpringingBubbleToTouch = false;

    /**
     * Whether to spring the bubble to the next touch event coordinates. This is used to animate the
     * bubble out of the magnetic dismiss target to the touch location.
     *
     * Once it 'catches up' and the animation ends, we'll revert to moving it directly.
     */
    private boolean mSpringToTouchOnNextMotionEvent = false;

    /** The bubble currently being dragged out of the row (to potentially be dismissed). */
    private MagnetizedObject<View> mMagnetizedBubbleDraggingOut;

    private int mExpandedViewPadding;

    public ExpandedAnimationController(Point displaySize, int expandedViewPadding,
            int orientation) {
        updateOrientation(orientation, displaySize);
        mExpandedViewPadding = expandedViewPadding;
    }

    /**
     * Whether the individual bubble has been dragged out of the row of bubbles far enough to cause
     * the rest of the bubbles to animate to fill the gap.
     */
    private boolean mBubbleDraggedOutEnough = false;

    /**
     * Animates expanding the bubbles into a row along the top of the screen.
     */
    public void expandFromStack(@Nullable Runnable after) {
        mAnimatingCollapse = false;
        mAnimatingExpand = true;
        mAfterExpand = after;

        startOrUpdatePathAnimation(true /* expanding */);
    }

    /** Animate collapsing the bubbles back to their stacked position. */
    public void collapseBackToStack(PointF collapsePoint, Runnable after) {
        mAnimatingExpand = false;
        mAnimatingCollapse = true;
        mAfterCollapse = after;
        mCollapsePoint = collapsePoint;

        startOrUpdatePathAnimation(false /* expanding */);
    }

    /**
     * Update effective screen width based on current orientation.
     * @param orientation Landscape or portrait.
     * @param displaySize Updated display size.
     */
    public void updateOrientation(int orientation, Point displaySize) {
        mScreenOrientation = orientation;
        mDisplaySize = displaySize;
        if (mLayout != null) {
            Resources res = mLayout.getContext().getResources();
            mBubblePaddingTop = res.getDimensionPixelSize(R.dimen.bubble_padding_top);
            mStatusBarHeight = res.getDimensionPixelSize(
                    com.android.internal.R.dimen.status_bar_height);
        }
    }

    /**
     * Animates the bubbles along a curved path, either to expand them along the top or collapse
     * them back into a stack.
     */
    private void startOrUpdatePathAnimation(boolean expanding) {
        Runnable after;

        if (expanding) {
            after = () -> {
                mAnimatingExpand = false;

                if (mAfterExpand != null) {
                    mAfterExpand.run();
                }

                mAfterExpand = null;
            };
        } else {
            after = () -> {
                mAnimatingCollapse = false;

                if (mAfterCollapse != null) {
                    mAfterCollapse.run();
                }

                mAfterCollapse = null;
            };
        }

        // Animate each bubble individually, since each path will end in a different spot.
        animationsForChildrenFromIndex(0, (index, animation) -> {
            final View bubble = mLayout.getChildAt(index);

            // Start a path at the bubble's current position.
            final Path path = new Path();
            path.moveTo(bubble.getTranslationX(), bubble.getTranslationY());

            final float expandedY = getExpandedY();
            if (expanding) {
                // If we're expanding, first draw a line from the bubble's current position to the
                // top of the screen.
                path.lineTo(bubble.getTranslationX(), expandedY);

                // Then, draw a line across the screen to the bubble's resting position.
                path.lineTo(getBubbleLeft(index), expandedY);
            } else {
                final float sideMultiplier =
                        mLayout.isFirstChildXLeftOfCenter(mCollapsePoint.x) ? -1 : 1;
                final float stackedX = mCollapsePoint.x + (sideMultiplier * index * mStackOffsetPx);

                // If we're collapsing, draw a line from the bubble's current position to the side
                // of the screen where the bubble will be stacked.
                path.lineTo(stackedX, expandedY);

                // Then, draw a line down to the stack position.
                path.lineTo(stackedX, mCollapsePoint.y);
            }

            // The lead bubble should be the bubble with the longest distance to travel when we're
            // expanding, and the bubble with the shortest distance to travel when we're collapsing.
            // During expansion from the left side, the last bubble has to travel to the far right
            // side, so we have it lead and 'pull' the rest of the bubbles into place. From the
            // right side, the first bubble is traveling to the top left, so it leads. During
            // collapse to the left, the first bubble has the shortest travel time back to the stack
            // position, so it leads (and vice versa).
            final boolean firstBubbleLeads =
                    (expanding && !mLayout.isFirstChildXLeftOfCenter(bubble.getTranslationX()))
                            || (!expanding && mLayout.isFirstChildXLeftOfCenter(mCollapsePoint.x));
            final int startDelay = firstBubbleLeads
                    ? (index * 10)
                    : ((mLayout.getChildCount() - index) * 10);

            animation
                    .followAnimatedTargetAlongPath(
                            path,
                            EXPAND_COLLAPSE_TARGET_ANIM_DURATION /* targetAnimDuration */,
                            Interpolators.LINEAR /* targetAnimInterpolator */)
                    .withStartDelay(startDelay)
                    .withStiffness(EXPAND_COLLAPSE_ANIM_STIFFNESS);
        }).startAll(after);
    }

    /** Notifies the controller that the dragged-out bubble was unstuck from the magnetic target. */
    public void onUnstuckFromTarget() {
        mSpringToTouchOnNextMotionEvent = true;
    }

    /** Prepares the given bubble to be dragged out. */
    public void prepareForBubbleDrag(View bubble, MagnetizedObject.MagneticTarget target) {
        mLayout.cancelAnimationsOnView(bubble);

        bubble.setTranslationZ(Short.MAX_VALUE);
        mMagnetizedBubbleDraggingOut = new MagnetizedObject<View>(
                mLayout.getContext(), bubble,
                DynamicAnimation.TRANSLATION_X, DynamicAnimation.TRANSLATION_Y) {
            @Override
            public float getWidth(@NonNull View underlyingObject) {
                return mBubbleSizePx;
            }

            @Override
            public float getHeight(@NonNull View underlyingObject) {
                return mBubbleSizePx;
            }

            @Override
            public void getLocationOnScreen(@NonNull View underlyingObject, @NonNull int[] loc) {
                loc[0] = (int) bubble.getTranslationX();
                loc[1] = (int) bubble.getTranslationY();
            }
        };
        mMagnetizedBubbleDraggingOut.addTarget(target);
        mMagnetizedBubbleDraggingOut.setHapticsEnabled(true);
        mMagnetizedBubbleDraggingOut.setFlingToTargetMinVelocity(FLING_TO_DISMISS_MIN_VELOCITY);
    }

    private void springBubbleTo(View bubble, float x, float y) {
        animationForChild(bubble)
                .translationX(x)
                .translationY(y)
                .withStiffness(SpringForce.STIFFNESS_HIGH)
                .start();
    }

    /**
     * Drags an individual bubble to the given coordinates. Bubbles to the right will animate to
     * take its place once it's dragged out of the row of bubbles, and animate out of the way if the
     * bubble is dragged back into the row.
     */
    public void dragBubbleOut(View bubbleView, float x, float y) {
        if (mSpringToTouchOnNextMotionEvent) {
            springBubbleTo(mMagnetizedBubbleDraggingOut.getUnderlyingObject(), x, y);
            mSpringToTouchOnNextMotionEvent = false;
            mSpringingBubbleToTouch = true;
        } else if (mSpringingBubbleToTouch) {
            if (mLayout.arePropertiesAnimatingOnView(
                    bubbleView, DynamicAnimation.TRANSLATION_X, DynamicAnimation.TRANSLATION_Y)) {
                springBubbleTo(mMagnetizedBubbleDraggingOut.getUnderlyingObject(), x, y);
            } else {
                mSpringingBubbleToTouch = false;
            }
        }

        if (!mSpringingBubbleToTouch && !mMagnetizedBubbleDraggingOut.getObjectStuckToTarget()) {
            bubbleView.setTranslationX(x);
            bubbleView.setTranslationY(y);
        }

        final boolean draggedOutEnough =
                y > getExpandedY() + mBubbleSizePx || y < getExpandedY() - mBubbleSizePx;
        if (draggedOutEnough != mBubbleDraggedOutEnough) {
            updateBubblePositions();
            mBubbleDraggedOutEnough = draggedOutEnough;
        }
    }

    /** Plays a dismiss animation on the dragged out bubble. */
    public void dismissDraggedOutBubble(View bubble, Runnable after) {
        animationForChild(bubble)
                .withStiffness(SpringForce.STIFFNESS_HIGH)
                .scaleX(1.1f)
                .scaleY(1.1f)
                .alpha(0f, after)
                .start();

        updateBubblePositions();
    }

    @Nullable public View getDraggedOutBubble() {
        return mMagnetizedBubbleDraggingOut == null
                ? null
                : mMagnetizedBubbleDraggingOut.getUnderlyingObject();
    }

    /** Returns the MagnetizedObject instance for the dragging-out bubble. */
    public MagnetizedObject<View> getMagnetizedBubbleDraggingOut() {
        return mMagnetizedBubbleDraggingOut;
    }

    /**
     * Snaps a bubble back to its position within the bubble row, and animates the rest of the
     * bubbles to accommodate it if it was previously dragged out past the threshold.
     */
    public void snapBubbleBack(View bubbleView, float velX, float velY) {
        final int index = mLayout.indexOfChild(bubbleView);

        animationForChildAtIndex(index)
                .position(getBubbleLeft(index), getExpandedY())
                .withPositionStartVelocities(velX, velY)
                .start(() -> bubbleView.setTranslationZ(0f) /* after */);

        mMagnetizedBubbleDraggingOut = null;

        updateBubblePositions();
    }

    /** Resets bubble drag out gesture flags. */
    public void onGestureFinished() {
        mBubbleDraggedOutEnough = false;
        mMagnetizedBubbleDraggingOut = null;
        updateBubblePositions();
    }

    /**
     * Animates the bubbles to {@link #getExpandedY()} position. Used in response to IME showing.
     */
    public void updateYPosition(Runnable after) {
        if (mLayout == null) return;
        animationsForChildrenFromIndex(
                0, (i, anim) -> anim.translationY(getExpandedY())).startAll(after);
    }

    /** The Y value of the row of expanded bubbles. */
    public float getExpandedY() {
        if (mLayout == null || mLayout.getRootWindowInsets() == null) {
            return 0;
        }
        final WindowInsets insets = mLayout.getRootWindowInsets();
        return mBubblePaddingTop + Math.max(
                mStatusBarHeight,
                insets.getDisplayCutout() != null
                        ? insets.getDisplayCutout().getSafeInsetTop()
                        : 0);
    }

    /** Description of current animation controller state. */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("ExpandedAnimationController state:");
        pw.print("  isActive:          "); pw.println(isActiveController());
        pw.print("  animatingExpand:   "); pw.println(mAnimatingExpand);
        pw.print("  animatingCollapse: "); pw.println(mAnimatingCollapse);
        pw.print("  springingBubble:   "); pw.println(mSpringingBubbleToTouch);
    }

    @Override
    void onActiveControllerForLayout(PhysicsAnimationLayout layout) {
        final Resources res = layout.getResources();
        mStackOffsetPx = res.getDimensionPixelSize(R.dimen.bubble_stack_offset);
        mBubblePaddingTop = res.getDimensionPixelSize(R.dimen.bubble_padding_top);
        mBubbleSizePx = res.getDimensionPixelSize(R.dimen.individual_bubble_size);
        mStatusBarHeight =
                res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
        mBubblesMaxRendered = res.getInteger(R.integer.bubbles_max_rendered);

        // Includes overflow button.
        float totalGapWidth = getWidthForDisplayingBubbles() - (mExpandedViewPadding * 2)
                - (mBubblesMaxRendered + 1) * mBubbleSizePx;
        mSpaceBetweenBubbles = totalGapWidth / mBubblesMaxRendered;

        // Ensure that all child views are at 1x scale, and visible, in case they were animating
        // in.
        mLayout.setVisibility(View.VISIBLE);
        animationsForChildrenFromIndex(0 /* startIndex */, (index, animation) ->
                animation.scaleX(1f).scaleY(1f).alpha(1f)).startAll();
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
        // If a bubble is added while the expand/collapse animations are playing, update the
        // animation to include the new bubble.
        if (mAnimatingExpand) {
            startOrUpdatePathAnimation(true /* expanding */);
        } else if (mAnimatingCollapse) {
            startOrUpdatePathAnimation(false /* expanding */);
        } else {
            child.setTranslationX(getBubbleLeft(index));
            animationForChild(child)
                    .translationY(
                            getExpandedY() - mBubbleSizePx * ANIMATE_TRANSLATION_FACTOR, /* from */
                            getExpandedY() /* to */)
                    .start();
            updateBubblePositions();
        }
    }

    @Override
    void onChildRemoved(View child, int index, Runnable finishRemoval) {
        final PhysicsAnimationLayout.PhysicsPropertyAnimator animator = animationForChild(child);

        // If we're removing the dragged-out bubble, that means it got dismissed.
        if (child.equals(getDraggedOutBubble())) {
            mMagnetizedBubbleDraggingOut = null;
            finishRemoval.run();
        } else {
            animator.alpha(0f, finishRemoval /* endAction */)
                    .withStiffness(SpringForce.STIFFNESS_HIGH)
                    .withDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY)
                    .scaleX(1.1f)
                    .scaleY(1.1f)
                    .start();
        }

        // Animate all the other bubbles to their new positions sans this bubble.
        updateBubblePositions();
    }

    @Override
    void onChildReordered(View child, int oldIndex, int newIndex) {
        updateBubblePositions();

        // We expect reordering during collapse, since we'll put the last selected bubble on top.
        // Update the collapse animation so they end up in the right stacked positions.
        if (mAnimatingCollapse) {
            startOrUpdatePathAnimation(false /* expanding */);
        }
    }

    private void updateBubblePositions() {
        if (mAnimatingExpand || mAnimatingCollapse) {
            return;
        }

        for (int i = 0; i < mLayout.getChildCount(); i++) {
            final View bubble = mLayout.getChildAt(i);

            // Don't animate the dragging out bubble, or it'll jump around while being dragged. It
            // will be snapped to the correct X value after the drag (if it's not dismissed).
            if (bubble.equals(getDraggedOutBubble())) {
                return;
            }

            animationForChild(bubble)
                    .translationX(getBubbleLeft(i))
                    .start();
        }
    }

    /**
     * @param index Bubble index in row.
     * @return Bubble left x from left edge of screen.
     */
    public float getBubbleLeft(int index) {
        final float bubbleFromRowLeft = index * (mBubbleSizePx + mSpaceBetweenBubbles);
        return getRowLeft() + bubbleFromRowLeft;
    }

    /**
     * When expanded, the bubbles are centered in the screen. In portrait, all available space is
     * used. In landscape we have too much space so the value is restricted. This method accounts
     * for window decorations (nav bar, cutouts).
     *
     * @return the desired width to display the expanded bubbles in.
     */
    public float getWidthForDisplayingBubbles() {
        final float availableWidth = getAvailableScreenWidth(true /* includeStableInsets */);
        if (mScreenOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            // display size y in landscape will be the smaller dimension of the screen
            return Math.max(mDisplaySize.y, availableWidth * CENTER_BUBBLES_LANDSCAPE_PERCENT);
        } else {
            return availableWidth;
        }
    }

    /**
     * Determines the available screen width without the cutout.
     *
     * @param subtractStableInsets Whether or not stable insets should also be removed from the
     *                             returned width.
     * @return the total screen width available accounting for cutouts and insets,
     * iff {@param includeStableInsets} is true.
     */
    private float getAvailableScreenWidth(boolean subtractStableInsets) {
        float availableSize = mDisplaySize.x;
        WindowInsets insets = mLayout != null ? mLayout.getRootWindowInsets() : null;
        if (insets != null) {
            int cutoutLeft = 0;
            int cutoutRight = 0;
            DisplayCutout cutout = insets.getDisplayCutout();
            if (cutout != null) {
                cutoutLeft = cutout.getSafeInsetLeft();
                cutoutRight = cutout.getSafeInsetRight();
            }
            final int stableLeft = subtractStableInsets ? insets.getStableInsetLeft() : 0;
            final int stableRight = subtractStableInsets ? insets.getStableInsetRight() : 0;
            availableSize -= Math.max(stableLeft, cutoutLeft);
            availableSize -= Math.max(stableRight, cutoutRight);
        }
        return availableSize;
    }

    private float getRowLeft() {
        if (mLayout == null) {
            return 0;
        }
        float rowWidth = (mLayout.getChildCount() * mBubbleSizePx)
                + ((mLayout.getChildCount() - 1) * mSpaceBetweenBubbles);

        // This display size we're using includes the size of the insets, we want the true
        // center of the display minus the notch here, which means we should include the
        // stable insets (e.g. status bar, nav bar) in this calculation.
        final float trueCenter = getAvailableScreenWidth(false /* subtractStableInsets */) / 2f;
        return trueCenter - (rowWidth / 2f);
    }
}
