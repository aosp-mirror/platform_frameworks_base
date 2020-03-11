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
            mStack.hideStackUserEducation(false /* fromExpansion */);
            resetForNextGesture();
            return false;
        }

        if (!(mTouchedView instanceof BadgedImageView)
                && !(mTouchedView instanceof BubbleStackView)
                && !(mTouchedView instanceof BubbleFlyoutView)) {

            // Not touching anything touchable, but we shouldn't collapse (e.g. touching edge
            // of expanded view).
            mStack.maybeShowManageEducation(false);
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

                    // Dismiss the entire stack if it's released in the dismiss target.
                    mStack.setReleasedInDismissTargetAction(
                            () -> mController.dismissStack(BubbleController.DISMISS_USER_GESTURE));
                    mStack.onDragStart();
                    mStack.passEventToMagnetizedObject(event);
                } else if (isFlyout) {
                    mStack.onFlyoutDragStart();
                } else {
                    mViewPositionOnTouchDown.set(
                            mTouchedView.getTranslationX(), mTouchedView.getTranslationY());

                    // Dismiss only the dragged-out bubble if it's released in the target.
                    final String individualBubbleKey = ((BadgedImageView) mTouchedView).getKey();
                    mStack.setReleasedInDismissTargetAction(() -> {
                        final Bubble bubble =
                                mBubbleData.getBubbleWithKey(individualBubbleKey);
                        // bubble can be null if the user is in the middle of
                        // dismissing the bubble, but the app also sent a cancel
                        if (bubble != null) {
                            mController.removeBubble(bubble.getEntry(),
                                    BubbleController.DISMISS_USER_GESTURE);
                        }
                    });

                    mStack.onBubbleDragStart(mTouchedView);
                    mStack.passEventToMagnetizedObject(event);
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
                    if (isFlyout) {
                        mStack.onFlyoutDragged(deltaX);
                    } else if (!mStack.passEventToMagnetizedObject(event)) {
                        // If the magnetic target doesn't consume the event, drag the stack or
                        // bubble.
                        if (isStack) {
                            mStack.onDragged(viewX, viewY);
                        } else {
                            mStack.onBubbleDragged(mTouchedView, viewX, viewY);
                        }
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

                if (isFlyout && mMovedEnough) {
                    mStack.onFlyoutDragFinished(rawX - mTouchDown.x /* deltaX */, velX);
                } else if (isFlyout) {
                    if (!mBubbleData.isExpanded() && !mMovedEnough) {
                        mStack.onFlyoutTapped();
                    }
                } else if (mMovedEnough) {
                    if (!mStack.passEventToMagnetizedObject(event)) {
                        // If the magnetic target didn't consume the event, tell the stack to finish
                        // the drag.
                        if (isStack) {
                            mStack.onDragFinish(viewX, viewY, velX, velY);
                        } else {
                            mStack.onBubbleDragFinish(mTouchedView, viewX, viewY, velX, velY);
                        }
                    }
                } else if (mTouchedView == mStack.getExpandedBubbleView()) {
                    mBubbleData.setExpanded(false);
                } else if (isStack) {
                    mStack.onStackTapped();
                } else {
                    final String key = ((BadgedImageView) mTouchedView).getKey();
                    if (key == BubbleOverflow.KEY) {
                        mStack.showOverflow();
                    } else {
                        mStack.expandBubble(mBubbleData.getBubbleWithKey(key));
                    }
                }
                resetForNextGesture();
                break;
        }

        return true;
    }

    /** Clears all touch-related state. */
    private void resetForNextGesture() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }

        mTouchedView = null;
        mMovedEnough = false;

        mStack.onGestureFinished();
    }

    private void trackMovement(MotionEvent event) {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(event);
    }
}
