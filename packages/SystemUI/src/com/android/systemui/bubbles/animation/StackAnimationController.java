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
import android.graphics.RectF;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FlingAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.android.systemui.R;

import com.google.android.collect.Sets;

import java.util.HashMap;
import java.util.Set;

/**
 * Animation controller for bubbles when they're in their stacked state. Stacked bubbles sit atop
 * each other with a slight offset to the left or right (depending on which side of the screen they
 * are on). Bubbles 'follow' each other when dragged, and can be flung to the left or right sides of
 * the screen.
 */
public class StackAnimationController extends
        PhysicsAnimationLayout.PhysicsAnimationController {

    private static final String TAG = "Bubbs.StackCtrl";

    /** Scale factor to use initially for new bubbles being animated in. */
    private static final float ANIMATE_IN_STARTING_SCALE = 1.15f;

    /** Translation factor (multiplied by stack offset) to use for bubbles being animated in/out. */
    private static final int ANIMATE_TRANSLATION_FACTOR = 4;

    /**
     * Values to use for the default {@link SpringForce} provided to the physics animation layout.
     */
    private static final float DEFAULT_STIFFNESS = 2500f;
    private static final float DEFAULT_BOUNCINESS = 0.85f;

    /**
     * The canonical position of the stack. This is typically the position of the first bubble, but
     * we need to keep track of it separately from the first bubble's translation in case there are
     * no bubbles, or the first bubble was just added and being animated to its new position.
     */
    private PointF mStackPosition = new PointF();

    /** The height of the most recently visible IME. */
    private float mImeHeight = 0f;

    /**
     * The Y position of the stack before the IME became visible, or {@link Float#MIN_VALUE} if the
     * IME is not visible or the user moved the stack since the IME became visible.
     */
    private float mPreImeY = Float.MIN_VALUE;

    /**
     * Animations on the stack position itself, which would have been started in
     * {@link #flingThenSpringFirstBubbleWithStackFollowing}. These animations dispatch to
     * {@link #moveFirstBubbleWithStackFollowing} to move the entire stack (with 'following' effect)
     * to a legal position on the side of the screen.
     */
    private HashMap<DynamicAnimation.ViewProperty, DynamicAnimation> mStackPositionAnimations =
            new HashMap<>();

    /** Horizontal offset of bubbles in the stack. */
    private float mStackOffset;
    /** Diameter of the bubbles themselves. */
    private int mIndividualBubbleSize;
    /** Size of spacing around the bubbles, separating it from the edge of the screen. */
    private int mBubblePadding;
    /** How far offscreen the stack rests. */
    private int mBubbleOffscreen;
    /** How far down the screen the stack starts, when there is no pre-existing location. */
    private int mStackStartingVerticalOffset;
    /** Height of the status bar. */
    private float mStatusBarHeight;

    @Override
    protected void setLayout(PhysicsAnimationLayout layout) {
        super.setLayout(layout);

        Resources res = layout.getResources();
        mStackOffset = res.getDimensionPixelSize(R.dimen.bubble_stack_offset);
        mIndividualBubbleSize = res.getDimensionPixelSize(R.dimen.individual_bubble_size);
        mBubblePadding = res.getDimensionPixelSize(R.dimen.bubble_padding);
        mBubbleOffscreen = res.getDimensionPixelSize(R.dimen.bubble_stack_offscreen);
        mStackStartingVerticalOffset =
                res.getDimensionPixelSize(R.dimen.bubble_stack_starting_offset_y);
        mStatusBarHeight =
                res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
    }

    /**
     * Instantly move the first bubble to the given point, and animate the rest of the stack behind
     * it with the 'following' effect.
     */
    public void moveFirstBubbleWithStackFollowing(float x, float y) {
        // If we manually move the bubbles with the IME open, clear the return point since we don't
        // want the stack to snap away from the new position.
        mPreImeY = Float.MIN_VALUE;

        moveFirstBubbleWithStackFollowing(DynamicAnimation.TRANSLATION_X, x);
        moveFirstBubbleWithStackFollowing(DynamicAnimation.TRANSLATION_Y, y);
    }

    /**
     * The position of the stack - typically the position of the first bubble; if no bubbles have
     * been added yet, it will be where the first bubble will go when added.
     */
    public PointF getStackPosition() {
        return mStackPosition;
    }

    /**
     * Flings the first bubble along the given property's axis, using the provided configuration
     * values. When the animation ends - either by hitting the min/max, or by friction sufficiently
     * reducing momentum - a SpringAnimation takes over to snap the bubble to the given final
     * position.
     */
    public void flingThenSpringFirstBubbleWithStackFollowing(
            DynamicAnimation.ViewProperty property,
            float vel,
            float friction,
            SpringForce spring,
            Float finalPosition) {
        Log.d(TAG, String.format("Flinging %s.",
                        PhysicsAnimationLayout.getReadablePropertyName(property)));

        StackPositionProperty firstBubbleProperty = new StackPositionProperty(property);
        final float currentValue = firstBubbleProperty.getValue(this);
        final RectF bounds = getAllowableStackPositionRegion();
        final float min =
                property.equals(DynamicAnimation.TRANSLATION_X)
                        ? bounds.left
                        : bounds.top;
        final float max =
                property.equals(DynamicAnimation.TRANSLATION_X)
                        ? bounds.right
                        : bounds.bottom;

        FlingAnimation flingAnimation = new FlingAnimation(this, firstBubbleProperty);
        flingAnimation.setFriction(friction)
                .setStartVelocity(vel)

                // If the bubble's property value starts beyond the desired min/max, use that value
                // instead so that the animation won't immediately end. If, for example, the user
                // drags the bubbles into the navigation bar, but then flings them upward, we want
                // the fling to occur despite temporarily having a value outside of the min/max. If
                // the bubbles are out of bounds and flung even farther out of bounds, the fling
                // animation will halt immediately and the SpringAnimation will take over, springing
                // it in reverse to the (legal) final position.
                .setMinValue(Math.min(currentValue, min))
                .setMaxValue(Math.max(currentValue, max))

                .addEndListener((animation, canceled, endValue, endVelocity) -> {
                    if (!canceled) {
                        springFirstBubbleWithStackFollowing(property, spring, endVelocity,
                                finalPosition != null
                                        ? finalPosition
                                        : Math.max(min, Math.min(max, endValue)));
                    }
                });

        cancelStackPositionAnimation(property);
        mStackPositionAnimations.put(property, flingAnimation);
        flingAnimation.start();
    }

    /**
     * Cancel any stack position animations that were started by calling
     * @link #flingThenSpringFirstBubbleWithStackFollowing}, and remove any corresponding end
     * listeners.
     */
    public void cancelStackPositionAnimations() {
        cancelStackPositionAnimation(DynamicAnimation.TRANSLATION_X);
        cancelStackPositionAnimation(DynamicAnimation.TRANSLATION_Y);

        mLayout.removeEndListenerForProperty(DynamicAnimation.TRANSLATION_X);
        mLayout.removeEndListenerForProperty(DynamicAnimation.TRANSLATION_Y);
    }

    /**
     * Save the IME height so that the allowable stack bounds reflect the now-visible IME, and
     * animate the stack out of the way if necessary.
     */
    public void updateBoundsForVisibleImeAndAnimate(int imeHeight) {
        mImeHeight = imeHeight;

        final float maxBubbleY = getAllowableStackPositionRegion().bottom;
        if (mStackPosition.y > maxBubbleY && mPreImeY == Float.MIN_VALUE) {
            mPreImeY = mStackPosition.y;

            springFirstBubbleWithStackFollowing(
                    DynamicAnimation.TRANSLATION_Y,
                    getSpringForce(DynamicAnimation.TRANSLATION_Y, /* view */ null)
                            .setStiffness(SpringForce.STIFFNESS_LOW),
                    /* startVel */ 0f,
                    maxBubbleY);
        }
    }

    /**
     * Clear the IME height from the bounds and animate the stack back to its original position,
     * assuming it wasn't moved in the meantime.
     */
    public void updateBoundsForInvisibleImeAndAnimate() {
        mImeHeight = 0;

        if (mPreImeY > Float.MIN_VALUE) {
            springFirstBubbleWithStackFollowing(
                    DynamicAnimation.TRANSLATION_Y,
                    getSpringForce(DynamicAnimation.TRANSLATION_Y, /* view */ null)
                        .setStiffness(SpringForce.STIFFNESS_LOW),
                    /* startVel */ 0f,
                    mPreImeY);
            mPreImeY = Float.MIN_VALUE;
        }
    }

    /**
     * Returns the region within which the stack is allowed to rest. This goes slightly off the left
     * and right sides of the screen, below the status bar/cutout and above the navigation bar.
     * While the stack is not allowed to rest outside of these bounds, it can temporarily be
     * animated or dragged beyond them.
     */
    public RectF getAllowableStackPositionRegion() {
        final WindowInsets insets = mLayout.getRootWindowInsets();
        final RectF allowableRegion = new RectF();
        if (insets != null) {
            allowableRegion.left =
                    -mBubbleOffscreen
                            - mBubblePadding
                            + Math.max(
                            insets.getSystemWindowInsetLeft(),
                            insets.getDisplayCutout() != null
                                    ? insets.getDisplayCutout().getSafeInsetLeft()
                                    : 0);
            allowableRegion.right =
                    mLayout.getWidth()
                            - mIndividualBubbleSize
                            + mBubbleOffscreen
                            - mBubblePadding
                            - Math.max(
                            insets.getSystemWindowInsetRight(),
                            insets.getDisplayCutout() != null
                                    ? insets.getDisplayCutout().getSafeInsetRight()
                                    : 0);

            allowableRegion.top =
                    mBubblePadding
                            + Math.max(
                            mStatusBarHeight,
                            insets.getDisplayCutout() != null
                                    ? insets.getDisplayCutout().getSafeInsetTop()
                                    : 0);
            allowableRegion.bottom =
                    mLayout.getHeight()
                            - mIndividualBubbleSize
                            - mBubblePadding
                            - (mImeHeight > Float.MIN_VALUE ? mImeHeight + mBubblePadding : 0f)
                            - Math.max(
                            insets.getSystemWindowInsetBottom(),
                            insets.getDisplayCutout() != null
                                    ? insets.getDisplayCutout().getSafeInsetBottom()
                                    : 0);
        }

        return allowableRegion;
    }

    @Override
    Set<DynamicAnimation.ViewProperty> getAnimatedProperties() {
        return Sets.newHashSet(
                DynamicAnimation.TRANSLATION_X, // For positioning.
                DynamicAnimation.TRANSLATION_Y,
                DynamicAnimation.ALPHA,         // For fading in new bubbles.
                DynamicAnimation.SCALE_X,       // For 'popping in' new bubbles.
                DynamicAnimation.SCALE_Y);
    }

    @Override
    int getNextAnimationInChain(DynamicAnimation.ViewProperty property, int index) {
        if (property.equals(DynamicAnimation.TRANSLATION_X)
                || property.equals(DynamicAnimation.TRANSLATION_Y)) {
            return index + 1; // Just chain them linearly.
        } else {
            return NONE;
        }
    }


    @Override
    float getOffsetForChainedPropertyAnimation(DynamicAnimation.ViewProperty property) {
        if (property.equals(DynamicAnimation.TRANSLATION_X)) {
            // Offset to the left if we're on the left, or the right otherwise.
            return mLayout.isFirstChildXLeftOfCenter(mStackPosition.x)
                    ? -mStackOffset : mStackOffset;
        } else {
            return 0f;
        }
    }

    @Override
    SpringForce getSpringForce(DynamicAnimation.ViewProperty property, View view) {
        return new SpringForce()
                .setDampingRatio(DEFAULT_BOUNCINESS)
                .setStiffness(DEFAULT_STIFFNESS);
    }

    @Override
    void onChildAdded(View child, int index) {
        if (mLayout.getChildCount() == 1) {
            // If this is the first child added, position the stack in its starting position before
            // animating in.
            moveStackToStartPosition(() -> animateInBubble(child));
        } else if (mLayout.indexOfChild(child) == 0) {
            // Otherwise, animate the bubble in if it's the newest bubble. If we're adding a bubble
            // to the back of the stack, it'll be largely invisible so don't bother animating it in.
            animateInBubble(child);
        }
    }

    @Override
    void onChildRemoved(View child, int index, Runnable finishRemoval) {
        // Animate the child out, actually removing it once its alpha is zero.
        mLayout.animateValueForChild(
                DynamicAnimation.ALPHA, child, 0f, finishRemoval);
        mLayout.animateValueForChild(DynamicAnimation.SCALE_X, child, ANIMATE_IN_STARTING_SCALE);
        mLayout.animateValueForChild(DynamicAnimation.SCALE_Y, child, ANIMATE_IN_STARTING_SCALE);

        // Animate the removing view in the opposite direction of the stack.
        final float xOffset = getOffsetForChainedPropertyAnimation(DynamicAnimation.TRANSLATION_X);
        mLayout.animateValueForChild(DynamicAnimation.TRANSLATION_X, child,
                mStackPosition.x - (-xOffset * ANIMATE_TRANSLATION_FACTOR));

        // Pull the top of the stack to the correct position, the chained animations will instruct
        // any children that are out of place to animate to the correct position.
        mLayout.animateValueForChildAtIndex(DynamicAnimation.TRANSLATION_X, 0, mStackPosition.x);
    }

    /** Moves the stack, without any animation, to the starting position. */
    private void moveStackToStartPosition(Runnable after) {
        // Post to ensure that the layout's width and height have been calculated.
        mLayout.post(() -> {
            setStackPosition(
                    getAllowableStackPositionRegion().right,
                    getAllowableStackPositionRegion().top + mStackStartingVerticalOffset);
            after.run();
        });
    }

    /**
     * Moves the first bubble instantly to the given X or Y translation, and instructs subsequent
     * bubbles to animate 'following' to the new location.
     */
    private void moveFirstBubbleWithStackFollowing(
            DynamicAnimation.ViewProperty property, float value) {

        // Update the canonical stack position.
        if (property.equals(DynamicAnimation.TRANSLATION_X)) {
            mStackPosition.x = value;
        } else if (property.equals(DynamicAnimation.TRANSLATION_Y)) {
            mStackPosition.y = value;
        }

        if (mLayout.getChildCount() > 0) {
            property.setValue(mLayout.getChildAt(0), value);
            mLayout.animateValueForChildAtIndex(
                    property,
                    /* index */ 1,
                    value + getOffsetForChainedPropertyAnimation(property));
        }
    }

    /** Moves the stack to a position instantly, with no animation. */
    private void setStackPosition(float x, float y) {
        Log.d(TAG, String.format("Setting position to (%f, %f).", x, y));
        mStackPosition.set(x, y);

        cancelStackPositionAnimations();

        // Since we're not using the chained animations, apply the offsets manually.
        final float xOffset = getOffsetForChainedPropertyAnimation(DynamicAnimation.TRANSLATION_X);
        final float yOffset = getOffsetForChainedPropertyAnimation(DynamicAnimation.TRANSLATION_Y);
        for (int i = 0; i < mLayout.getChildCount(); i++) {
            mLayout.getChildAt(i).setTranslationX(x + (i * xOffset));
            mLayout.getChildAt(i).setTranslationY(y + (i * yOffset));
        }
    }

    /** Animates in the given bubble. */
    private void animateInBubble(View child) {
        child.setTranslationY(mStackPosition.y);

        // Pop in the new bubble.
        child.setScaleX(ANIMATE_IN_STARTING_SCALE);
        child.setScaleY(ANIMATE_IN_STARTING_SCALE);
        mLayout.animateValueForChildAtIndex(DynamicAnimation.SCALE_X, 0, 1f);
        mLayout.animateValueForChildAtIndex(DynamicAnimation.SCALE_Y, 0, 1f);

        // Fade in the new bubble.
        child.setAlpha(0);
        mLayout.animateValueForChildAtIndex(DynamicAnimation.ALPHA, 0, 1f);

        // Start the new bubble 4x the normal offset distance in the opposite direction. We'll
        // animate in from this position. Since the animations are chained, when the new bubble
        // flies in from the side, it will push the other ones out of the way.
        float xOffset = getOffsetForChainedPropertyAnimation(DynamicAnimation.TRANSLATION_X);
        child.setTranslationX(mStackPosition.x - ANIMATE_TRANSLATION_FACTOR * xOffset);
        mLayout.animateValueForChildAtIndex(
                DynamicAnimation.TRANSLATION_X, 0, mStackPosition.x);
    }

    /**
     * Springs the first bubble to the given final position, with the rest of the stack 'following'.
     */
    private void springFirstBubbleWithStackFollowing(
            DynamicAnimation.ViewProperty property, SpringForce spring,
            float vel, float finalPosition) {

        Log.d(TAG, String.format("Springing %s to final position %f.",
                PhysicsAnimationLayout.getReadablePropertyName(property),
                finalPosition));

        StackPositionProperty firstBubbleProperty = new StackPositionProperty(property);
        SpringAnimation springAnimation =
                new SpringAnimation(this, firstBubbleProperty)
                        .setSpring(spring)
                        .setStartVelocity(vel);

        cancelStackPositionAnimation(property);
        mStackPositionAnimations.put(property, springAnimation);
        springAnimation.animateToFinalPosition(finalPosition);
    }

    /**
     * Cancels any outstanding first bubble property animations that are running. This does not
     * affect the SpringAnimations controlling the individual bubbles' 'following' effect - it only
     * cancels animations started from {@link #springFirstBubbleWithStackFollowing} and
     * {@link #flingThenSpringFirstBubbleWithStackFollowing}.
     */
    private void cancelStackPositionAnimation(DynamicAnimation.ViewProperty property) {
        if (mStackPositionAnimations.containsKey(property)) {
            mStackPositionAnimations.get(property).cancel();
        }
    }

    /**
     * FloatProperty that uses {@link #moveFirstBubbleWithStackFollowing} to set the first bubble's
     * translation and animate the rest of the stack with it. A DynamicAnimation can animate this
     * property directly to move the first bubble and cause the stack to 'follow' to the new
     * location.
     *
     * This could also be achieved by simply animating the first bubble view and adding an update
     * listener to dispatch movement to the rest of the stack. However, this would require
     * duplication of logic in that update handler - it's simpler to keep all logic contained in the
     * {@link #moveFirstBubbleWithStackFollowing} method.
     */
    private class StackPositionProperty
            extends FloatPropertyCompat<StackAnimationController> {
        private final DynamicAnimation.ViewProperty mProperty;

        private StackPositionProperty(DynamicAnimation.ViewProperty property) {
            super(property.toString());
            mProperty = property;
        }

        @Override
        public float getValue(StackAnimationController controller) {
            return mProperty.getValue(mLayout.getChildAt(0));
        }

        @Override
        public void setValue(StackAnimationController controller, float value) {
            moveFirstBubbleWithStackFollowing(mProperty, value);
        }
    }
}

