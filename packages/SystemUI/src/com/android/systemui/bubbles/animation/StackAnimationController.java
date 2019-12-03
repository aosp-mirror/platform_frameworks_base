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

import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FlingAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.android.systemui.R;

import com.google.android.collect.Sets;

import java.io.FileDescriptor;
import java.io.PrintWriter;
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

    /** Values to use for animating bubbles in. */
    private static final float ANIMATE_IN_STIFFNESS = 1000f;
    private static final int ANIMATE_IN_START_DELAY = 25;

    /**
     * Values to use for the default {@link SpringForce} provided to the physics animation layout.
     */
    private static final int DEFAULT_STIFFNESS = 12000;
    private static final int FLING_FOLLOW_STIFFNESS = 20000;
    private static final float DEFAULT_BOUNCINESS = 0.9f;

    /**
     * Friction applied to fling animations. Since the stack must land on one of the sides of the
     * screen, we want less friction horizontally so that the stack has a better chance of making it
     * to the side without needing a spring.
     */
    private static final float FLING_FRICTION_X = 2.2f;
    private static final float FLING_FRICTION_Y = 2.2f;

    /**
     * Values to use for the stack spring animation used to spring the stack to its final position
     * after a fling.
     */
    private static final int SPRING_AFTER_FLING_STIFFNESS = 750;
    private static final float SPRING_AFTER_FLING_DAMPING_RATIO = 0.85f;

    /**
     * Minimum fling velocity required to trigger moving the stack from one side of the screen to
     * the other.
     */
    private static final float ESCAPE_VELOCITY = 750f;

    /**
     * The canonical position of the stack. This is typically the position of the first bubble, but
     * we need to keep track of it separately from the first bubble's translation in case there are
     * no bubbles, or the first bubble was just added and being animated to its new position.
     */
    private PointF mStackPosition = new PointF(-1, -1);

    /** Whether or not the stack's start position has been set. */
    private boolean mStackMovedToStartPosition = false;

    /** The most recent position in which the stack was resting on the edge of the screen. */
    @Nullable private PointF mRestingStackPosition;

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

    /**
     * Whether the current motion of the stack is due to a fling animation (vs. being dragged
     * manually).
     */
    private boolean mIsMovingFromFlinging = false;

    /**
     * Whether the stack is within the dismiss target (either by being dragged, magnet'd, or flung).
     */
    private boolean mWithinDismissTarget = false;

    /**
     * Whether the first bubble is springing towards the touch point, rather than using the default
     * behavior of moving directly to the touch point with the rest of the stack following it.
     *
     * This happens when the user's finger exits the dismiss area while the stack is magnetized to
     * the center. Since the touch point differs from the stack location, we need to animate the
     * stack back to the touch point to avoid a jarring instant location change from the center of
     * the target to the touch point just outside the target bounds.
     *
     * This is reset once the spring animations end, since that means the first bubble has
     * successfully 'caught up' to the touch.
     */
    private boolean mFirstBubbleSpringingToTouch = false;

    /** Horizontal offset of bubbles in the stack. */
    private float mStackOffset;
    /** Diameter of the bubble icon. */
    private int mBubbleIconBitmapSize;
    /** Width of the bubble (icon and padding). */
    private int mBubbleSize;
    /**
     * The amount of space to add between the bubbles and certain UI elements, such as the top of
     * the screen or the IME. This does not apply to the left/right sides of the screen since the
     * stack goes offscreen intentionally.
     */
    private int mBubblePaddingTop;
    /** How far offscreen the stack rests. */
    private int mBubbleOffscreen;
    /** How far down the screen the stack starts, when there is no pre-existing location. */
    private int mStackStartingVerticalOffset;
    /** Height of the status bar. */
    private float mStatusBarHeight;

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

        // This method is called when the stack is being dragged manually, so we're clearly no
        // longer flinging.
        mIsMovingFromFlinging = false;
    }

    /**
     * The position of the stack - typically the position of the first bubble; if no bubbles have
     * been added yet, it will be where the first bubble will go when added.
     */
    public PointF getStackPosition() {
        return mStackPosition;
    }

    /** Whether the stack is on the left side of the screen. */
    public boolean isStackOnLeftSide() {
        if (mLayout == null || !isStackPositionSet()) {
            return false;
        }

        float stackCenter = mStackPosition.x + mBubbleIconBitmapSize / 2;
        float screenCenter = mLayout.getWidth() / 2;
        return stackCenter < screenCenter;
    }

    /**
     * Fling stack to given corner, within allowable screen bounds.
     * Note that we need new SpringForce instances per animation despite identical configs because
     * SpringAnimation uses SpringForce's internal (changing) velocity while the animation runs.
     */
    public void springStack(float destinationX, float destinationY) {
        springFirstBubbleWithStackFollowing(DynamicAnimation.TRANSLATION_X,
                new SpringForce()
                        .setStiffness(SPRING_AFTER_FLING_STIFFNESS)
                        .setDampingRatio(SPRING_AFTER_FLING_DAMPING_RATIO),
                0 /* startXVelocity */,
                destinationX);

        springFirstBubbleWithStackFollowing(DynamicAnimation.TRANSLATION_Y,
                new SpringForce()
                        .setStiffness(SPRING_AFTER_FLING_STIFFNESS)
                        .setDampingRatio(SPRING_AFTER_FLING_DAMPING_RATIO),
                0 /* startYVelocity */,
                destinationY);
    }

    /**
     * Flings the stack starting with the given velocities, springing it to the nearest edge
     * afterward.
     *
     * @return The X value that the stack will end up at after the fling/spring.
     */
    public float flingStackThenSpringToEdge(float x, float velX, float velY) {
        final boolean stackOnLeftSide = x - mBubbleIconBitmapSize / 2 < mLayout.getWidth() / 2;

        final boolean stackShouldFlingLeft = stackOnLeftSide
                ? velX < ESCAPE_VELOCITY
                : velX < -ESCAPE_VELOCITY;

        final RectF stackBounds = getAllowableStackPositionRegion();

        // Target X translation (either the left or right side of the screen).
        final float destinationRelativeX = stackShouldFlingLeft
                ? stackBounds.left : stackBounds.right;

        // If all bubbles were removed during a drag event, just return the X we would have animated
        // to if there were still bubbles.
        if (mLayout == null || mLayout.getChildCount() == 0) {
            return destinationRelativeX;
        }

        // Minimum velocity required for the stack to make it to the targeted side of the screen,
        // taking friction into account (4.2f is the number that friction scalars are multiplied by
        // in DynamicAnimation.DragForce). This is an estimate - it could possibly be slightly off,
        // but the SpringAnimation at the end will ensure that it reaches the destination X
        // regardless.
        final float minimumVelocityToReachEdge =
                (destinationRelativeX - x) * (FLING_FRICTION_X * 4.2f);

        // Use the touch event's velocity if it's sufficient, otherwise use the minimum velocity so
        // that it'll make it all the way to the side of the screen.
        final float startXVelocity = stackShouldFlingLeft
                ? Math.min(minimumVelocityToReachEdge, velX)
                : Math.max(minimumVelocityToReachEdge, velX);

        flingThenSpringFirstBubbleWithStackFollowing(
                DynamicAnimation.TRANSLATION_X,
                startXVelocity,
                FLING_FRICTION_X,
                new SpringForce()
                        .setStiffness(SPRING_AFTER_FLING_STIFFNESS)
                        .setDampingRatio(SPRING_AFTER_FLING_DAMPING_RATIO),
                destinationRelativeX);

        flingThenSpringFirstBubbleWithStackFollowing(
                DynamicAnimation.TRANSLATION_Y,
                velY,
                FLING_FRICTION_Y,
                new SpringForce()
                        .setStiffness(SPRING_AFTER_FLING_STIFFNESS)
                        .setDampingRatio(SPRING_AFTER_FLING_DAMPING_RATIO),
                /* destination */ null);

        // If we're flinging now, there's no more touch event to catch up to.
        mFirstBubbleSpringingToTouch = false;
        mIsMovingFromFlinging = true;
        return destinationRelativeX;
    }

    /**
     * Where the stack would be if it were snapped to the nearest horizontal edge (left or right).
     */
    public PointF getStackPositionAlongNearestHorizontalEdge() {
        final PointF stackPos = getStackPosition();
        final boolean onLeft = mLayout.isFirstChildXLeftOfCenter(stackPos.x);
        final RectF bounds = getAllowableStackPositionRegion();

        stackPos.x = onLeft ? bounds.left : bounds.right;
        return stackPos;
    }

    /**
     * Moves the stack in response to rotation. We keep it in the most similar position by keeping
     * it on the same side, and positioning it the same percentage of the way down the screen
     * (taking status bar/nav bar into account by using the allowable region's height).
     */
    public void moveStackToSimilarPositionAfterRotation(boolean wasOnLeft, float verticalPercent) {
        final RectF allowablePos = getAllowableStackPositionRegion();
        final float allowableRegionHeight = allowablePos.bottom - allowablePos.top;

        final float x = wasOnLeft ? allowablePos.left : allowablePos.right;
        final float y = (allowableRegionHeight * verticalPercent) + allowablePos.top;

        setStackPosition(new PointF(x, y));
    }

    /** Description of current animation controller state. */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("StackAnimationController state:");
        pw.print("  isActive:             "); pw.println(isActiveController());
        pw.print("  restingStackPos:      ");
        pw.println(mRestingStackPosition != null ? mRestingStackPosition.toString() : "null");
        pw.print("  currentStackPos:      "); pw.println(mStackPosition.toString());
        pw.print("  isMovingFromFlinging: "); pw.println(mIsMovingFromFlinging);
        pw.print("  withinDismiss:        "); pw.println(mWithinDismissTarget);
        pw.print("  firstBubbleSpringing: "); pw.println(mFirstBubbleSpringingToTouch);
    }

    /**
     * Flings the first bubble along the given property's axis, using the provided configuration
     * values. When the animation ends - either by hitting the min/max, or by friction sufficiently
     * reducing momentum - a SpringAnimation takes over to snap the bubble to the given final
     * position.
     */
    protected void flingThenSpringFirstBubbleWithStackFollowing(
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
                        mRestingStackPosition = new PointF();
                        mRestingStackPosition.set(mStackPosition);

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

        removeEndActionForProperty(DynamicAnimation.TRANSLATION_X);
        removeEndActionForProperty(DynamicAnimation.TRANSLATION_Y);
    }

    /** Save the current IME height so that we know where the stack bounds should be. */
    public void setImeHeight(int imeHeight) {
        mImeHeight = imeHeight;
    }

    /**
     * Animates the stack either away from the newly visible IME, or back to its original position
     * due to the IME going away.
     */
    public void animateForImeVisibility(boolean imeVisible) {
        final float maxBubbleY = getAllowableStackPositionRegion().bottom;
        float destinationY = Float.MIN_VALUE;

        if (imeVisible) {
            // Stack is lower than it should be and overlaps the now-visible IME.
            if (mStackPosition.y > maxBubbleY && mPreImeY == Float.MIN_VALUE) {
                mPreImeY = mStackPosition.y;
                destinationY = maxBubbleY;
            }
        } else {
            if (mPreImeY > Float.MIN_VALUE) {
                destinationY = mPreImeY;
                mPreImeY = Float.MIN_VALUE;
            }
        }

        if (destinationY > Float.MIN_VALUE) {
            springFirstBubbleWithStackFollowing(
                    DynamicAnimation.TRANSLATION_Y,
                    getSpringForce(DynamicAnimation.TRANSLATION_Y, /* view */ null)
                            .setStiffness(SpringForce.STIFFNESS_LOW),
                    /* startVel */ 0f,
                    destinationY);
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
                            + Math.max(
                            insets.getSystemWindowInsetLeft(),
                            insets.getDisplayCutout() != null
                                    ? insets.getDisplayCutout().getSafeInsetLeft()
                                    : 0);
            allowableRegion.right =
                    mLayout.getWidth()
                            - mBubbleSize
                            + mBubbleOffscreen
                            - Math.max(
                            insets.getSystemWindowInsetRight(),
                            insets.getDisplayCutout() != null
                                    ? insets.getDisplayCutout().getSafeInsetRight()
                                    : 0);

            allowableRegion.top =
                    mBubblePaddingTop
                            + Math.max(
                            mStatusBarHeight,
                            insets.getDisplayCutout() != null
                                    ? insets.getDisplayCutout().getSafeInsetTop()
                                    : 0);
            allowableRegion.bottom =
                    mLayout.getHeight()
                            - mBubbleSize
                            - mBubblePaddingTop
                            - (mImeHeight > Float.MIN_VALUE ? mImeHeight + mBubblePaddingTop : 0f)
                            - Math.max(
                            insets.getSystemWindowInsetBottom(),
                            insets.getDisplayCutout() != null
                                    ? insets.getDisplayCutout().getSafeInsetBottom()
                                    : 0);
        }

        return allowableRegion;
    }

    /** Moves the stack in response to a touch event. */
    public void moveStackFromTouch(float x, float y) {

        // If we're springing to the touch point to 'catch up' after dragging out of the dismiss
        // target, then update the stack position animations instead of moving the bubble directly.
        if (mFirstBubbleSpringingToTouch) {
            final SpringAnimation springToTouchX =
                    (SpringAnimation) mStackPositionAnimations.get(DynamicAnimation.TRANSLATION_X);
            final SpringAnimation springToTouchY =
                    (SpringAnimation) mStackPositionAnimations.get(DynamicAnimation.TRANSLATION_Y);

            // If either animation is still running, we haven't caught up. Update the animations.
            if (springToTouchX.isRunning() || springToTouchY.isRunning()) {
                springToTouchX.animateToFinalPosition(x);
                springToTouchY.animateToFinalPosition(y);
            } else {
                // If the animations have finished, the stack is now at the touch point. We can
                // resume moving the bubble directly.
                mFirstBubbleSpringingToTouch = false;
            }
        }

        if (!mFirstBubbleSpringingToTouch && !mWithinDismissTarget) {
            moveFirstBubbleWithStackFollowing(x, y);
        }
    }

    /**
     * Demagnetizes the stack, springing it towards the given point. This also sets flags so that
     * subsequent touch events will update the final position of the demagnetization spring instead
     * of directly moving the bubbles, until demagnetization is complete.
     */
    public void demagnetizeFromDismissToPoint(float x, float y, float velX, float velY) {
        mWithinDismissTarget = false;
        mFirstBubbleSpringingToTouch = true;

        springFirstBubbleWithStackFollowing(
                DynamicAnimation.TRANSLATION_X,
                new SpringForce()
                        .setDampingRatio(DEFAULT_BOUNCINESS)
                        .setStiffness(DEFAULT_STIFFNESS),
                velX, x);

        springFirstBubbleWithStackFollowing(
                DynamicAnimation.TRANSLATION_Y,
                new SpringForce()
                        .setDampingRatio(DEFAULT_BOUNCINESS)
                        .setStiffness(DEFAULT_STIFFNESS),
                velY, y);
    }

    /**
     * Spring the stack towards the dismiss target, respecting existing velocity. This also sets
     * flags so that subsequent touch events will not move the stack until it's demagnetized.
     */
    public void magnetToDismiss(float velX, float velY, float destY, Runnable after) {
        mWithinDismissTarget = true;
        mFirstBubbleSpringingToTouch = false;

        springFirstBubbleWithStackFollowing(
                DynamicAnimation.TRANSLATION_X,
                new SpringForce()
                        .setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY)
                        .setStiffness(SpringForce.STIFFNESS_MEDIUM),
                velX, mLayout.getWidth() / 2f - mBubbleIconBitmapSize / 2f);

        springFirstBubbleWithStackFollowing(
                DynamicAnimation.TRANSLATION_Y,
                new SpringForce()
                        .setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY)
                        .setStiffness(SpringForce.STIFFNESS_MEDIUM),
                velY, destY, after);
    }

    /**
     * 'Implode' the stack by shrinking the bubbles via chained animations and fading them out.
     */
    public void implodeStack(Runnable after) {
        // Pop and fade the bubbles sequentially.
        animationForChildAtIndex(0)
                .scaleX(0.5f)
                .scaleY(0.5f)
                .alpha(0f)
                .withDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY)
                .withStiffness(SpringForce.STIFFNESS_HIGH)
                .start(() -> {
                    // Run the callback and reset flags. The child translation animations might
                    // still be running, but that's fine. Once the alpha is at 0f they're no longer
                    // visible anyway.
                    after.run();
                    mWithinDismissTarget = false;
                });
    }

    /**
     * Springs the first bubble to the given final position, with the rest of the stack 'following'.
     */
    protected void springFirstBubbleWithStackFollowing(
            DynamicAnimation.ViewProperty property, SpringForce spring,
            float vel, float finalPosition, @Nullable Runnable... after) {

        if (mLayout.getChildCount() == 0) {
            return;
        }

        Log.d(TAG, String.format("Springing %s to final position %f.",
                PhysicsAnimationLayout.getReadablePropertyName(property),
                finalPosition));

        StackPositionProperty firstBubbleProperty = new StackPositionProperty(property);
        SpringAnimation springAnimation =
                new SpringAnimation(this, firstBubbleProperty)
                        .setSpring(spring)
                        .addEndListener((dynamicAnimation, b, v, v1) -> {
                            if (after != null) {
                                for (Runnable callback : after) {
                                    callback.run();
                                }
                            }
                        })
                        .setStartVelocity(vel);

        cancelStackPositionAnimation(property);
        mStackPositionAnimations.put(property, springAnimation);
        springAnimation.animateToFinalPosition(finalPosition);
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
            return index + 1;
        } else if (mWithinDismissTarget) {
            return index + 1; // Chain all animations in dismiss (scale, alpha, etc. are used).
        } else {
            return NONE;
        }
    }


    @Override
    float getOffsetForChainedPropertyAnimation(DynamicAnimation.ViewProperty property) {
        if (property.equals(DynamicAnimation.TRANSLATION_X)) {
            // If we're in the dismiss target, have the bubbles pile on top of each other with no
            // offset.
            if (mWithinDismissTarget) {
                return 0f;
            } else {
                // Offset to the left if we're on the left, or the right otherwise.
                return mLayout.isFirstChildXLeftOfCenter(mStackPosition.x)
                        ? -mStackOffset : mStackOffset;
            }
        } else {
            return 0f;
        }
    }

    @Override
    SpringForce getSpringForce(DynamicAnimation.ViewProperty property, View view) {
        return new SpringForce()
                .setDampingRatio(DEFAULT_BOUNCINESS)
                .setStiffness(mIsMovingFromFlinging ? FLING_FOLLOW_STIFFNESS : DEFAULT_STIFFNESS);
    }

    @Override
    void onChildAdded(View child, int index) {
        // Don't animate additions within the dismiss target.
        if (mWithinDismissTarget) {
            return;
        }

        if (mLayout.getChildCount() == 1) {
            // If this is the first child added, position the stack in its starting position.
            moveStackToStartPosition();
        } else if (isStackPositionSet() && mLayout.indexOfChild(child) == 0) {
            // Otherwise, animate the bubble in if it's the newest bubble. If we're adding a bubble
            // to the back of the stack, it'll be largely invisible so don't bother animating it in.
            animateInBubble(child, index);
        }
    }

    @Override
    void onChildRemoved(View child, int index, Runnable finishRemoval) {
        // Animate the removing view in the opposite direction of the stack.
        final float xOffset = getOffsetForChainedPropertyAnimation(DynamicAnimation.TRANSLATION_X);
        animationForChild(child)
                .alpha(0f, finishRemoval /* after */)
                .scaleX(ANIMATE_IN_STARTING_SCALE)
                .scaleY(ANIMATE_IN_STARTING_SCALE)
                .translationX(mStackPosition.x - (-xOffset * ANIMATE_TRANSLATION_FACTOR))
                .start();

        // If there are other bubbles, pull them into the correct position.
        if (mLayout.getChildCount() > 0) {
            animationForChildAtIndex(0).translationX(mStackPosition.x).start();
        } else {
            // If there's no other bubbles, and we were in the dismiss target, reset the flag.
            mWithinDismissTarget = false;
        }
    }

    @Override
    void onChildReordered(View child, int oldIndex, int newIndex) {
        if (isStackPositionSet()) {
            setStackPosition(mStackPosition);
        }
    }

    @Override
    void onActiveControllerForLayout(PhysicsAnimationLayout layout) {
        Resources res = layout.getResources();
        mStackOffset = res.getDimensionPixelSize(R.dimen.bubble_stack_offset);
        mBubbleSize = res.getDimensionPixelSize(R.dimen.individual_bubble_size);
        mBubbleIconBitmapSize = res.getDimensionPixelSize(R.dimen.bubble_icon_bitmap_size);
        mBubblePaddingTop = res.getDimensionPixelSize(R.dimen.bubble_padding_top);
        mBubbleOffscreen = res.getDimensionPixelSize(R.dimen.bubble_stack_offscreen);
        mStackStartingVerticalOffset =
                res.getDimensionPixelSize(R.dimen.bubble_stack_starting_offset_y);
        mStatusBarHeight =
                res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
    }

    /**
     * Update effective screen width based on current orientation.
     * @param orientation Landscape or portrait.
     */
    public void updateOrientation(int orientation) {
        if (mLayout != null) {
            Resources res = mLayout.getContext().getResources();
            mBubblePaddingTop = res.getDimensionPixelSize(R.dimen.bubble_padding_top);
            mStatusBarHeight = res.getDimensionPixelSize(
                    com.android.internal.R.dimen.status_bar_height);
        }
    }


    /** Moves the stack, without any animation, to the starting position. */
    private void moveStackToStartPosition() {
        // Post to ensure that the layout's width and height have been calculated.
        mLayout.setVisibility(View.INVISIBLE);
        mLayout.post(() -> {
            setStackPosition(mRestingStackPosition == null
                    ? getDefaultStartPosition()
                    : mRestingStackPosition);
            mStackMovedToStartPosition = true;
            mLayout.setVisibility(View.VISIBLE);

            // Animate in the top bubble now that we're visible.
            if (mLayout.getChildCount() > 0) {
                animateInBubble(mLayout.getChildAt(0), 0 /* index */);
            }
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
            if (mLayout.getChildCount() > 1) {
                animationForChildAtIndex(1)
                        .property(property, value + getOffsetForChainedPropertyAnimation(property))
                        .start();
            }
        }
    }

    /** Moves the stack to a position instantly, with no animation. */
    private void setStackPosition(PointF pos) {
        Log.d(TAG, String.format("Setting position to (%f, %f).", pos.x, pos.y));
        mStackPosition.set(pos.x, pos.y);

        // If we're not the active controller, we don't want to physically move the bubble views.
        if (isActiveController()) {
            // Cancel animations that could be moving the views.
            mLayout.cancelAllAnimationsOfProperties(
                    DynamicAnimation.TRANSLATION_X, DynamicAnimation.TRANSLATION_Y);
            cancelStackPositionAnimations();

            // Since we're not using the chained animations, apply the offsets manually.
            final float xOffset = getOffsetForChainedPropertyAnimation(
                    DynamicAnimation.TRANSLATION_X);
            final float yOffset = getOffsetForChainedPropertyAnimation(
                    DynamicAnimation.TRANSLATION_Y);
            for (int i = 0; i < mLayout.getChildCount(); i++) {
                mLayout.getChildAt(i).setTranslationX(pos.x + (i * xOffset));
                mLayout.getChildAt(i).setTranslationY(pos.y + (i * yOffset));
            }
        }
    }

    /** Returns the default stack position, which is on the top right. */
    private PointF getDefaultStartPosition() {
        return new PointF(
                getAllowableStackPositionRegion().right,
                getAllowableStackPositionRegion().top + mStackStartingVerticalOffset);
    }

    private boolean isStackPositionSet() {
        return mStackMovedToStartPosition;
    }

    /** Animates in the given bubble. */
    private void animateInBubble(View child, int index) {
        if (!isActiveController()) {
            return;
        }

        final float xOffset =
                getOffsetForChainedPropertyAnimation(DynamicAnimation.TRANSLATION_X);

        // Position the new bubble in the correct position, scaled down completely.
        child.setTranslationX(mStackPosition.x + xOffset * index);
        child.setTranslationY(mStackPosition.y);
        child.setScaleX(0f);
        child.setScaleY(0f);

        // Push the subsequent views out of the way, if there are subsequent views.
        if (index + 1 < mLayout.getChildCount()) {
            animationForChildAtIndex(index + 1)
                    .translationX(mStackPosition.x + xOffset * (index + 1))
                    .withStiffness(SpringForce.STIFFNESS_LOW)
                    .start();
        }

        // Scale in the new bubble, slightly delayed.
        animationForChild(child)
                .scaleX(1f)
                .scaleY(1f)
                .withStiffness(ANIMATE_IN_STIFFNESS)
                .withStartDelay(mLayout.getChildCount() > 1 ? ANIMATE_IN_START_DELAY : 0)
                .start();
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
            return mLayout.getChildCount() > 0 ? mProperty.getValue(mLayout.getChildAt(0)) : 0;
        }

        @Override
        public void setValue(StackAnimationController controller, float value) {
            moveFirstBubbleWithStackFollowing(mProperty, value);
        }
    }
}

