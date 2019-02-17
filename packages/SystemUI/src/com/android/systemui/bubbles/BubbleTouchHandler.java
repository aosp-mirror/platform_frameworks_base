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
import android.graphics.PointF;
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
    /** Velocity required to dismiss a bubble without dragging it into the dismiss target. */
    private static final float DISMISS_MIN_VELOCITY = 4000f;

    private final PointF mTouchDown = new PointF();
    private final PointF mViewPositionOnTouchDown = new PointF();
    private final BubbleStackView mStack;

    private BubbleController mController = Dependency.get(BubbleController.class);
    private PipDismissViewController mDismissViewController;

    private boolean mMovedEnough;
    private int mTouchSlopSquared;
    private VelocityTracker mVelocityTracker;

    private boolean mInDismissTarget;
    private Handler mHandler = new Handler();
    private Runnable mShowDismissAffordance = new Runnable() {
        @Override
        public void run() {
            mDismissViewController.showDismissTarget();
        }
    };

    /** View that was initially touched, when we received the first ACTION_DOWN event. */
    private View mTouchedView;

    BubbleTouchHandler(Context context, BubbleStackView stackView) {
        final int touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mTouchSlopSquared = touchSlop * touchSlop;
        mDismissViewController = new PipDismissViewController(context);
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
            mStack.collapseStack();
            cleanUpDismissTarget();
            mTouchedView = null;
            return false;
        }

        final boolean isStack = mStack.equals(mTouchedView);
        final float rawX = event.getRawX();
        final float rawY = event.getRawY();

        // The coordinates of the touch event, in terms of the touched view's position.
        final float viewX = mViewPositionOnTouchDown.x + rawX - mTouchDown.x;
        final float viewY = mViewPositionOnTouchDown.y + rawY - mTouchDown.y;
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                trackMovement(event);

                mDismissViewController.createDismissTarget();
                mHandler.postDelayed(mShowDismissAffordance, SHOW_TARGET_DELAY);

                mTouchDown.set(rawX, rawY);

                if (isStack) {
                    mViewPositionOnTouchDown.set(mStack.getStackPosition());
                    mStack.onDragStart();
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
                    } else {
                        mStack.onBubbleDragged(mTouchedView, viewX, viewY);
                    }
                }

                // TODO - when we're in the target stick to it / animate in some way?
                mInDismissTarget = mDismissViewController.updateTarget(
                        isStack ? mStack.getBubbleAt(0) : mTouchedView);
                break;

            case MotionEvent.ACTION_CANCEL:
                mTouchedView = null;
                cleanUpDismissTarget();
                break;

            case MotionEvent.ACTION_UP:
                trackMovement(event);
                if (mInDismissTarget && isStack) {
                    mController.dismissStack();
                } else if (mMovedEnough) {
                    mVelocityTracker.computeCurrentVelocity(1000);
                    final float velX = mVelocityTracker.getXVelocity();
                    final float velY = mVelocityTracker.getYVelocity();
                    if (isStack) {
                        mStack.onDragFinish(viewX, viewY, velX, velY);
                    } else {
                        final boolean dismissed = mInDismissTarget || velY > DISMISS_MIN_VELOCITY;
                        mStack.onBubbleDragFinish(
                                mTouchedView, viewX, viewY, velX, velY, /* dismissed */ dismissed);
                        if (dismissed) {
                            mController.removeBubble(((BubbleView) mTouchedView).getKey());
                        }
                    }
                } else if (mTouchedView.equals(mStack.getExpandedBubbleView())) {
                    mStack.collapseStack();
                } else if (isStack) {
                    if (mStack.isExpanded()) {
                        mStack.collapseStack();
                    } else {
                        mStack.expandStack();
                    }
                } else {
                    mStack.setExpandedBubble(((BubbleView) mTouchedView).getKey());
                }

                cleanUpDismissTarget();
                mVelocityTracker.recycle();
                mVelocityTracker = null;
                mTouchedView = null;
                mMovedEnough = false;
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


    private void trackMovement(MotionEvent event) {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(event);
    }
}
