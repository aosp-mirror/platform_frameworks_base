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

package com.android.systemui.bubbles;

import android.content.Context;
import android.graphics.PointF;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

import com.android.systemui.Dependency;

/**
 * Handles interpreting touches on a {@link BubbleStackView}. This includes expanding, collapsing,
 * dismissing, and flings.
 */
class BubbleTouchHandler implements View.OnTouchListener {
    /** Velocity required to dismiss the stack without dragging it into the dismiss target. */
    private static final float STACK_DISMISS_MIN_VELOCITY = 4000f;

    /**
     * Velocity required to dismiss an individual bubble without dragging it into the dismiss
     * target.
     *
     * This is higher than the stack dismiss velocity since unlike the stack, a downward fling could
     * also be an attempted gesture to return the bubble to the row of expanded bubbles, which would
     * usually be below the dragged bubble. By increasing the required velocity, it's less likely
     * that the user is trying to drop it back into the row vs. fling it away.
     */
    private static final float INDIVIDUAL_BUBBLE_DISMISS_MIN_VELOCITY = 6000f;

    /**
     * When the stack is flung towards the bottom of the screen, it'll be dismissed if it's flung
     * towards the center of the screen (where the dismiss target is). This value is the width of
     * the target area to be considered 'towards the target'. For example 50% means that the stack
     * needs to be flung towards the middle 50%, and the 25% on the left and right sides won't
     * count.
     */
    private static final float DISMISS_FLING_TARGET_WIDTH_PERCENT = 0.5f;

    private final PointF mTouchDown = new PointF();
    private final PointF mViewPositionOnTouchDown = new PointF();
    private final BubbleStackView mStack;
    private final BubbleData mBubbleData;

    private BubbleController mController = Dependency.get(BubbleController.class);

    private boolean mMovedEnough;
    private int mTouchSlopSquared;
    private VelocityTracker mVelocityTracker;

    /** View that was initially touched, when we received the first ACTION_DOWN event. */
    private View mTouchedView;
    /** Whether the current touched view is in the dismiss target. */
    private boolean mInDismissTarget;

    BubbleTouchHandler(BubbleStackView stackView,
            BubbleData bubbleData, Context context) {
        final int touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mTouchSlopSquared = touchSlop * touchSlop;
        mBubbleData = bubbleData;
        mStack = stackView;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        final int action = event.getActionMasked();

        // If we aren't currently in the process of touching a view, figure out what we're touching.
        // It'll be the stack, an individual bubble, or nothing.
        if (mTouchedView == null) {
            mTouchedView = mStack.getTargetView(event);
        }

        // If this is an ACTION_OUTSIDE event, or the stack reported that we aren't touching
        // anything, collapse the stack.
        if (action == MotionEvent.ACTION_OUTSIDE || mTouchedView == null) {
            mBubbleData.setExpanded(false);
            resetForNextGesture();
            return false;
        }

        if (!(mTouchedView instanceof BubbleView)
                && !(mTouchedView instanceof BubbleStackView)
                && !(mTouchedView instanceof BubbleFlyoutView)) {
            // Not touching anything touchable, but we shouldn't collapse (e.g. touching edge
            // of expanded view).
            resetForNextGesture();
            return false;
        }

        final boolean isStack = mStack.equals(mTouchedView);
        final boolean isFlyout = mStack.getFlyoutView().equals(mTouchedView);
        final float rawX = event.getRawX();
        final float rawY = event.getRawY();

        // The coordinates of the touch event, in terms of the touched view's position.
        final float viewX = mViewPositionOnTouchDown.x + rawX - mTouchDown.x;
        final float viewY = mViewPositionOnTouchDown.y + rawY - mTouchDown.y;
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                trackMovement(event);

                mTouchDown.set(rawX, rawY);
                mStack.onGestureStart();

                if (isStack) {
                    mViewPositionOnTouchDown.set(mStack.getStackPosition());
                    mStack.onDragStart();
                } else if (isFlyout) {
                    mStack.onFlyoutDragStart();
                } else {
                    mViewPositionOnTouchDown.set(
                            mTouchedView.getTranslationX(), mTouchedView.getTranslationY());
                    mStack.onBubbleDragStart(mTouchedView);
                }

                break;
            case MotionEvent.ACTION_MOVE:
                trackMovement(event);
                final float deltaX = rawX - mTouchDown.x;
                final float deltaY = rawY - mTouchDown.y;

                if ((deltaX * deltaX) + (deltaY * deltaY) > mTouchSlopSquared && !mMovedEnough) {
                    mMovedEnough = true;
                }

                if (mMovedEnough) {
                    if (isStack) {
                        mStack.onDragged(viewX, viewY);
                    } else if (isFlyout) {
                        mStack.onFlyoutDragged(deltaX);
                    } else {
                        mStack.onBubbleDragged(mTouchedView, viewX, viewY);
                    }
                }

                final boolean currentlyInDismissTarget = mStack.isInDismissTarget(event);
                if (currentlyInDismissTarget != mInDismissTarget) {
                    mInDismissTarget = currentlyInDismissTarget;

                    mVelocityTracker.computeCurrentVelocity(/* maxVelocity */ 1000);
                    final float velX = mVelocityTracker.getXVelocity();
                    final float velY = mVelocityTracker.getYVelocity();

                    // If the touch event is within the dismiss target, magnet the stack to it.
                    if (!isFlyout) {
                        mStack.animateMagnetToDismissTarget(
                                mTouchedView, mInDismissTarget, viewX, viewY, velX, velY);
                    }
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                resetForNextGesture();
                break;

            case MotionEvent.ACTION_UP:
                trackMovement(event);
                mVelocityTracker.computeCurrentVelocity(/* maxVelocity */ 1000);
                final float velX = mVelocityTracker.getXVelocity();
                final float velY = mVelocityTracker.getYVelocity();

                final boolean shouldDismiss =
                        isStack
                                ? mInDismissTarget
                                    || isFastFlingTowardsDismissTarget(rawX, rawY, velX, velY)
                                : mInDismissTarget
                                        || velY > INDIVIDUAL_BUBBLE_DISMISS_MIN_VELOCITY;

                if (isFlyout && mMovedEnough) {
                    mStack.onFlyoutDragFinished(rawX - mTouchDown.x /* deltaX */, velX);
                } else if (shouldDismiss) {
                    final String individualBubbleKey =
                            isStack ? null : ((BubbleView) mTouchedView).getKey();
                    mStack.magnetToStackIfNeededThenAnimateDismissal(mTouchedView, velX, velY,
                            () -> {
                                if (isStack) {
                                    mController.dismissStack(BubbleController.DISMISS_USER_GESTURE);
                                } else {
                                    mController.removeBubble(
                                            individualBubbleKey,
                                            BubbleController.DISMISS_USER_GESTURE);
                                }
                            });
                } else if (isFlyout) {
                    if (!mBubbleData.isExpanded() && !mMovedEnough) {
                        mStack.onFlyoutTapped();
                    }
                } else if (mMovedEnough) {
                    if (isStack) {
                        mStack.onDragFinish(viewX, viewY, velX, velY);
                    } else {
                        mStack.onBubbleDragFinish(mTouchedView, viewX, viewY, velX, velY);
                    }
                } else if (mTouchedView == mStack.getExpandedBubbleView()) {
                    mBubbleData.setExpanded(false);
                } else if (isStack || isFlyout) {
                    // Toggle expansion
                    mBubbleData.setExpanded(!mBubbleData.isExpanded());
                } else {
                    final String key = ((BubbleView) mTouchedView).getKey();
                    mBubbleData.setSelectedBubble(mBubbleData.getBubbleWithKey(key));
                }

                resetForNextGesture();
                break;
        }

        return true;
    }

    /**
     * Whether the given touch data represents a powerful fling towards the bottom-center of the
     * screen (the dismiss target).
     */
    private boolean isFastFlingTowardsDismissTarget(
            float rawX, float rawY, float velX, float velY) {
        // Not a fling downward towards the target if velocity is zero or negative.
        if (velY <= 0) {
            return false;
        }

        float bottomOfScreenInterceptX = rawX;

        // Only do math if the X velocity is non-zero, otherwise X won't change.
        if (velX != 0) {
            // Rise over run...
            final float slope = velY / velX;
            // ...y = mx + b, b = y / mx...
            final float yIntercept = rawY - slope * rawX;
            // ...calculate the x value when y = bottom of the screen.
            bottomOfScreenInterceptX = (mStack.getHeight() - yIntercept) / slope;
        }

        final float dismissTargetWidth =
                mStack.getWidth() * DISMISS_FLING_TARGET_WIDTH_PERCENT;
        return velY > STACK_DISMISS_MIN_VELOCITY
                && bottomOfScreenInterceptX > dismissTargetWidth / 2f
                && bottomOfScreenInterceptX < mStack.getWidth() - dismissTargetWidth / 2f;
    }

    /** Clears all touch-related state. */
    private void resetForNextGesture() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }

        mTouchedView = null;
        mMovedEnough = false;
        mInDismissTarget = false;

        mStack.onGestureFinished();
    }

    private void trackMovement(MotionEvent event) {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(event);
    }
}
