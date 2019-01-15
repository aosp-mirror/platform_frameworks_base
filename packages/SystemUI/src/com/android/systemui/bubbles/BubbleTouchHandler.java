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

import static com.android.systemui.pip.phone.PipDismissViewController.SHOW_TARGET_DELAY;

import android.content.Context;
import android.graphics.Point;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

import com.android.systemui.Dependency;
import com.android.systemui.pip.phone.PipDismissViewController;

/**
 * Handles interpreting touches on a {@link BubbleStackView}. This includes expanding, collapsing,
 * dismissing, and flings.
 */
class BubbleTouchHandler implements View.OnTouchListener {

    private BubbleController mController = Dependency.get(BubbleController.class);
    private PipDismissViewController mDismissViewController;
    private BubbleMovementHelper mMovementHelper;

    // The position of the bubble on down event
    private int mBubbleDownPosX;
    private int mBubbleDownPosY;
    // The touch position on down event
    private int mDownX = -1;
    private int mDownY = -1;

    private boolean mMovedEnough;
    private int mTouchSlopSquared;
    private float mMinFlingVelocity;
    private VelocityTracker mVelocityTracker;

    private boolean mInDismissTarget;
    private Handler mHandler = new Handler();
    private Runnable mShowDismissAffordance = new Runnable() {
        @Override
        public void run() {
            mDismissViewController.showDismissTarget();
        }
    };

    // Bubble being dragged from the row of bubbles when the stack is expanded
    private BubbleView mBubbleDraggingOut;

    /**
     * Views movable by this touch handler should implement this interface.
     */
    public interface FloatingView {

        /**
         * Sets the position of the view.
         */
        void setPosition(int x, int y);

        /**
         * Sets the x position of the view.
         */
        void setPositionX(int x);

        /**
         * Sets the y position of the view.
         */
        void setPositionY(int y);

        /**
         * @return the position of the view.
         */
        Point getPosition();
    }

    public BubbleTouchHandler(Context context) {
        final int touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mTouchSlopSquared = touchSlop * touchSlop;

        // Multiply by 3 for better fling
        mMinFlingVelocity = ViewConfiguration.get(context).getScaledMinimumFlingVelocity() * 3;

        mMovementHelper = new BubbleMovementHelper(context);
        mDismissViewController = new PipDismissViewController(context);
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        int action = event.getActionMasked();

        BubbleStackView stack = (BubbleStackView) v;
        View targetView = mBubbleDraggingOut != null
                ? mBubbleDraggingOut
                : stack.getTargetView(event);
        boolean isFloating = targetView instanceof FloatingView;
        if (!isFloating || targetView == null || action == MotionEvent.ACTION_OUTSIDE) {
            stack.collapseStack();
            cleanUpDismissTarget();
            resetTouches();
            return false;
        }

        FloatingView floatingView = (FloatingView) targetView;
        boolean isBubbleStack = floatingView instanceof BubbleStackView;

        Point startPos = floatingView.getPosition();
        int rawX = (int) event.getRawX();
        int rawY = (int) event.getRawY();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                trackMovement(event);

                mDismissViewController.createDismissTarget();
                mHandler.postDelayed(mShowDismissAffordance, SHOW_TARGET_DELAY);

                mBubbleDownPosX = startPos.x;
                mBubbleDownPosY = startPos.y;
                mDownX = rawX;
                mDownY = rawY;
                mMovedEnough = false;
                break;

            case MotionEvent.ACTION_MOVE:
                trackMovement(event);

                if (mBubbleDownPosX == -1 || mDownX == -1) {
                    mBubbleDownPosX = startPos.x;
                    mBubbleDownPosY = startPos.y;
                    mDownX = rawX;
                    mDownY = rawY;
                }
                final int deltaX = rawX - mDownX;
                final int deltaY = rawY - mDownY;
                if ((deltaX * deltaX) + (deltaY * deltaY) > mTouchSlopSquared && !mMovedEnough) {
                    mMovedEnough = true;
                }
                int x = mBubbleDownPosX + rawX - mDownX;
                int y = mBubbleDownPosY + rawY - mDownY;

                if (mMovedEnough) {
                    if (floatingView instanceof BubbleView && mBubbleDraggingOut == null) {
                        mBubbleDraggingOut = ((BubbleView) floatingView);
                    }
                    floatingView.setPosition(x, y);
                }
                // TODO - when we're in the target stick to it / animate in some way?
                mInDismissTarget = mDismissViewController.updateTarget((View) floatingView);
                break;

            case MotionEvent.ACTION_CANCEL:
                resetTouches();
                cleanUpDismissTarget();
                break;

            case MotionEvent.ACTION_UP:
                trackMovement(event);
                if (mInDismissTarget) {
                    if (isBubbleStack) {
                        mController.dismissStack();
                    } else {
                        mController.removeBubble(((BubbleView) floatingView).getKey());
                    }
                } else if (mMovedEnough) {
                    mVelocityTracker.computeCurrentVelocity(1000);
                    final float velX = mVelocityTracker.getXVelocity();
                    final float velY = mVelocityTracker.getYVelocity();
                    if (isBubbleStack) {
                        if ((Math.abs(velY) > mMinFlingVelocity)
                                || (Math.abs(velX) > mMinFlingVelocity)) {
                            // It's being flung somewhere
                            mMovementHelper.animateFlingTo(stack, velX, velY).start();
                        } else {
                            // Magnet back to nearest edge
                            mMovementHelper.animateMagnetTo(stack).start();
                        }
                    } else {
                        // Individual bubble got dragged but not dismissed.. lets animate it back
                        // into position
                        Point toGoTo = (Point) ((View) floatingView).getTag();
                        mMovementHelper.getTranslateAnim(floatingView, toGoTo, 100, 0).start();
                    }
                } else if (floatingView.equals(stack.getExpandedBubble())) {
                    stack.collapseStack();
                } else if (isBubbleStack) {
                    if (stack.isExpanded()) {
                        stack.collapseStack();
                    } else {
                        stack.expandStack();
                    }
                } else {
                    stack.setExpandedBubble((BubbleView) floatingView);
                }
                cleanUpDismissTarget();
                mVelocityTracker.recycle();
                mVelocityTracker = null;
                resetTouches();
                break;
        }
        return true;
    }

    /**
     * Removes the dismiss target and cancels any pending callbacks to show it.
     */
    private void cleanUpDismissTarget() {
        mHandler.removeCallbacks(mShowDismissAffordance);
        mDismissViewController.destroyDismissTarget();
    }

    /**
     * Resets anything we care about after a gesture is complete.
     */
    private void resetTouches() {
        mDownX = -1;
        mDownY = -1;
        mBubbleDownPosX = -1;
        mBubbleDownPosY = -1;
        mBubbleDraggingOut = null;
    }

    private void trackMovement(MotionEvent event) {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(event);
    }
}
