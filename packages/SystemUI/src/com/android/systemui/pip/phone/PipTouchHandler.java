/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.pip.phone;

import static android.view.WindowManager.INPUT_CONSUMER_PIP;

import android.app.IActivityManager;
import android.content.Context;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.IPinnedStackController;
import android.view.IWindowManager;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.policy.PipSnapAlgorithm;
import com.android.systemui.statusbar.FlingAnimationUtils;

import java.io.PrintWriter;

/**
 * Manages all the touch handling for PIP on the Phone, including moving, dismissing and expanding
 * the PIP.
 */
public class PipTouchHandler {
    private static final String TAG = "PipTouchHandler";

    // These values are used for metrics and should never change
    private static final int METRIC_VALUE_DISMISSED_BY_TAP = 0;
    private static final int METRIC_VALUE_DISMISSED_BY_DRAG = 1;

    private static final int SHOW_DISMISS_AFFORDANCE_DELAY = 200;

    // Allow dragging the PIP to a location to close it
    private static final boolean ENABLE_DRAG_TO_DISMISS = false;

    private final Context mContext;
    private final IActivityManager mActivityManager;
    private final IWindowManager mWindowManager;
    private final ViewConfiguration mViewConfig;
    private final PipMenuListener mMenuListener = new PipMenuListener();
    private IPinnedStackController mPinnedStackController;

    private PipInputEventReceiver mInputEventReceiver;
    private final PipMenuActivityController mMenuController;
    private final PipDismissViewController mDismissViewController;
    private final PipSnapAlgorithm mSnapAlgorithm;

    // The current movement bounds
    private Rect mMovementBounds = new Rect();

    // The reference bounds used to calculate the normal/expanded target bounds
    private Rect mNormalBounds = new Rect();
    private Rect mNormalMovementBounds = new Rect();
    private Rect mExpandedBounds = new Rect();
    private Rect mExpandedMovementBounds = new Rect();

    private Handler mHandler = new Handler();
    private Runnable mShowDismissAffordance = new Runnable() {
        @Override
        public void run() {
            if (ENABLE_DRAG_TO_DISMISS) {
                mDismissViewController.showDismissTarget(mMotionHelper.getBounds());
            }
        }
    };

    // Behaviour states
    private boolean mIsTappingThrough;
    private boolean mIsMinimized;
    private boolean mIsMenuVisible;
    private boolean mIsImeShowing;
    private int mImeHeight;
    private float mSavedSnapFraction = -1f;

    // Touch state
    private final PipTouchState mTouchState;
    private final FlingAnimationUtils mFlingAnimationUtils;
    private final PipTouchGesture[] mGestures;
    private final PipMotionHelper mMotionHelper;

    // Temp vars
    private final Rect mTmpBounds = new Rect();

    /**
     * Input handler used for Pip windows.
     */
    private final class PipInputEventReceiver extends InputEventReceiver {

        public PipInputEventReceiver(InputChannel inputChannel, Looper looper) {
            super(inputChannel, looper);
        }

        @Override
        public void onInputEvent(InputEvent event) {
            boolean handled = true;
            try {
                // To be implemented for input handling over Pip windows
                if (event instanceof MotionEvent) {
                    MotionEvent ev = (MotionEvent) event;
                    handled = handleTouchEvent(ev);
                }
            } finally {
                finishInputEvent(event, handled);
            }
        }
    }

    /**
     * A listener for the PIP menu activity.
     */
    private class PipMenuListener implements PipMenuActivityController.Listener {
        @Override
        public void onPipMenuVisibilityChanged(boolean visible) {
            setMenuVisibilityState(visible);
        }

        @Override
        public void onPipExpand() {
            if (!mIsMinimized) {
                mMotionHelper.expandPip();
            }
        }

        @Override
        public void onPipMinimize() {
            setMinimizedStateInternal(true);
            mMotionHelper.animateToClosestMinimizedState(mMovementBounds, mMenuController);
        }

        @Override
        public void onPipDismiss() {
            mMotionHelper.dismissPip();
            MetricsLogger.action(mContext, MetricsEvent.ACTION_PICTURE_IN_PICTURE_DISMISSED,
                    METRIC_VALUE_DISMISSED_BY_TAP);
        }
    }

    public PipTouchHandler(Context context, PipMenuActivityController menuController,
            IActivityManager activityManager, IWindowManager windowManager) {

        // Initialize the Pip input consumer
        mContext = context;
        mActivityManager = activityManager;
        mWindowManager = windowManager;
        mViewConfig = ViewConfiguration.get(context);
        mMenuController = menuController;
        mMenuController.addListener(mMenuListener);
        mDismissViewController = new PipDismissViewController(context);
        mSnapAlgorithm = new PipSnapAlgorithm(mContext);
        mTouchState = new PipTouchState(mViewConfig);
        mFlingAnimationUtils = new FlingAnimationUtils(context, 2f);
        mGestures = new PipTouchGesture[] {
                mDefaultMovementGesture
        };
        mMotionHelper = new PipMotionHelper(mContext, mActivityManager, mSnapAlgorithm,
                mFlingAnimationUtils);
        registerInputConsumer();
    }

    public void setTouchEnabled(boolean enabled) {
        mTouchState.setAllowTouches(enabled);
    }

    public void onActivityPinned() {
        // Reset some states once we are pinned
        if (mIsTappingThrough) {
            mIsTappingThrough = false;
            registerInputConsumer();
        }
        if (mIsMinimized) {
            setMinimizedStateInternal(false);
        }
    }

    public void onConfigurationChanged() {
        mMotionHelper.onConfigurationChanged();
        mMotionHelper.synchronizePinnedStackBounds();
    }

    public void onImeVisibilityChanged(boolean imeVisible, int imeHeight) {
        mIsImeShowing = imeVisible;
        mImeHeight = imeHeight;
    }

    public void onMovementBoundsChanged(Rect insetBounds, Rect normalBounds,
            boolean fromImeAdjustement) {
        // Re-calculate the expanded bounds
        mNormalBounds = normalBounds;
        Rect normalMovementBounds = new Rect();
        mSnapAlgorithm.getMovementBounds(mNormalBounds, insetBounds, normalMovementBounds,
                mIsImeShowing ? mImeHeight : 0);
        // TODO: Figure out the expanded size policy
        mExpandedBounds = new Rect(normalBounds);
        Rect expandedMovementBounds = new Rect();
        mSnapAlgorithm.getMovementBounds(mExpandedBounds, insetBounds, expandedMovementBounds,
                mIsImeShowing ? mImeHeight : 0);


        // If this is from an IME adjustment, then we should move the PiP so that it is not occluded
        // by the IME
        if (fromImeAdjustement) {
            if (mTouchState.isUserInteracting()) {
                // Defer the update of the current movement bounds until after the user finishes
                // touching the screen
            } else {
                final Rect bounds = new Rect(mMotionHelper.getBounds());
                final Rect toMovementBounds = mIsMenuVisible
                        ? expandedMovementBounds
                        : normalMovementBounds;
                if (mIsImeShowing) {
                    // IME visible
                    if (bounds.top == mMovementBounds.bottom) {
                        // If the PIP is currently resting on top of the IME, then adjust it with
                        // the hiding IME
                        bounds.offsetTo(bounds.left, toMovementBounds.bottom);
                    } else {
                        bounds.offset(0, Math.min(0, toMovementBounds.bottom - bounds.top));
                    }
                } else {
                    // IME hidden
                    if (bounds.top == mMovementBounds.bottom) {
                        // If the PIP is resting on top of the IME, then adjust it with the hiding IME
                        bounds.offsetTo(bounds.left, toMovementBounds.bottom);
                    }
                }
                mMotionHelper.animateToBounds(bounds);
            }
        }

        // Update the movement bounds after doing the calculations based on the old movement bounds
        // above
        mNormalMovementBounds = normalMovementBounds;
        mExpandedMovementBounds = expandedMovementBounds;
        updateMovementBounds();
    }

    private boolean handleTouchEvent(MotionEvent ev) {
        // Skip touch handling until we are bound to the controller
        if (mPinnedStackController == null) {
            return true;
        }

        // Update the touch state
        mTouchState.onTouchEvent(ev);

        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN: {
                mMotionHelper.synchronizePinnedStackBounds();

                for (PipTouchGesture gesture : mGestures) {
                    gesture.onDown(mTouchState);
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                for (PipTouchGesture gesture : mGestures) {
                    if (gesture.onMove(mTouchState)) {
                        break;
                    }
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                // Update the movement bounds again if the state has changed since the user started
                // dragging (ie. when the IME shows)
                updateMovementBounds();

                for (PipTouchGesture gesture : mGestures) {
                    if (gesture.onUp(mTouchState)) {
                        break;
                    }
                }

                // Fall through to clean up
            }
            case MotionEvent.ACTION_CANCEL: {
                mTouchState.reset();
                break;
            }
        }
        return !mIsTappingThrough;
    }

    /**
     * Registers the input consumer.
     */
    private void registerInputConsumer() {
        if (mInputEventReceiver == null) {
            final InputChannel inputChannel = new InputChannel();
            try {
                mWindowManager.destroyInputConsumer(INPUT_CONSUMER_PIP);
                mWindowManager.createInputConsumer(INPUT_CONSUMER_PIP, inputChannel);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to create PIP input consumer", e);
            }
            mInputEventReceiver = new PipInputEventReceiver(inputChannel, Looper.myLooper());
        }
    }

    /**
     * Unregisters the input consumer.
     */
    private void unregisterInputConsumer() {
        if (mInputEventReceiver != null) {
            try {
                mWindowManager.destroyInputConsumer(INPUT_CONSUMER_PIP);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to destroy PIP input consumer", e);
            }
            mInputEventReceiver.dispose();
            mInputEventReceiver = null;
        }
    }

    /**
     * Sets the controller to update the system of changes from user interaction.
     */
    void setPinnedStackController(IPinnedStackController controller) {
        mPinnedStackController = controller;
    }

    /**
     * Sets the minimized state.
     */
    void setMinimizedStateInternal(boolean isMinimized) {
        setMinimizedState(isMinimized, false /* fromController */);
    }

    /**
     * Sets the minimized state.
     */
    void setMinimizedState(boolean isMinimized, boolean fromController) {
        if (mIsMinimized != isMinimized) {
            MetricsLogger.action(mContext, MetricsEvent.ACTION_PICTURE_IN_PICTURE_MINIMIZED,
                    isMinimized);
        }
        mIsMinimized = isMinimized;
        mSnapAlgorithm.setMinimized(isMinimized);

        if (fromController) {
            if (isMinimized) {
                // Move the PiP to the new bounds immediately if minimized
                mMotionHelper.movePip(mMotionHelper.getClosestMinimizedBounds(mNormalBounds,
                        mMovementBounds));
            }
        } else if (mPinnedStackController != null) {
            try {
                mPinnedStackController.setIsMinimized(isMinimized);
            } catch (RemoteException e) {
                Log.e(TAG, "Could not set minimized state", e);
            }
        }
    }

    /**
     * Sets the menu visibility.
     */
    void setMenuVisibilityState(boolean isMenuVisible) {
        if (!isMenuVisible) {
            mIsTappingThrough = false;
            registerInputConsumer();
        } else {
            unregisterInputConsumer();
        }
        MetricsLogger.visibility(mContext, MetricsEvent.ACTION_PICTURE_IN_PICTURE_MENU,
                isMenuVisible);

        if (isMenuVisible != mIsMenuVisible) {
            if (isMenuVisible) {
                // Save the current snap fraction and if we do not drag or move the PiP, then
                // we store back to this snap fraction.  Otherwise, we'll reset the snap
                // fraction and snap to the closest edge
                Rect expandedBounds = new Rect(mExpandedBounds);
                mSavedSnapFraction = mMotionHelper.animateToExpandedState(expandedBounds,
                        mMovementBounds, mExpandedMovementBounds);
            } else {
                // Try and restore the PiP to the closest edge, using the saved snap fraction
                // if possible
                Rect normalBounds = new Rect(mNormalBounds);
                mMotionHelper.animateToUnexpandedState(normalBounds, mSavedSnapFraction,
                        mNormalMovementBounds);
            }
            mIsMenuVisible = isMenuVisible;
            updateMovementBounds();
        }
    }

    /**
     * @return the motion helper.
     */
    public PipMotionHelper getMotionHelper() {
        return mMotionHelper;
    }

    /**
     * Gesture controlling normal movement of the PIP.
     */
    private PipTouchGesture mDefaultMovementGesture = new PipTouchGesture() {

        @Override
        public void onDown(PipTouchState touchState) {
            if (!touchState.isUserInteracting()) {
                return;
            }

            if (ENABLE_DRAG_TO_DISMISS) {
                mDismissViewController.createDismissTarget();
                mHandler.postDelayed(mShowDismissAffordance, SHOW_DISMISS_AFFORDANCE_DELAY);
            }
        }

        @Override
        boolean onMove(PipTouchState touchState) {
            if (!touchState.isUserInteracting()) {
                return false;
            }

            if (touchState.startedDragging()) {
                mSavedSnapFraction = -1f;
            }

            if (touchState.startedDragging() && ENABLE_DRAG_TO_DISMISS) {
                mHandler.removeCallbacks(mShowDismissAffordance);
                mDismissViewController.showDismissTarget(mMotionHelper.getBounds());
            }

            if (touchState.isDragging()) {
                // Move the pinned stack freely
                mTmpBounds.set(mMotionHelper.getBounds());
                final PointF lastDelta = touchState.getLastTouchDelta();
                float left = mTmpBounds.left + lastDelta.x;
                float top = mTmpBounds.top + lastDelta.y;
                if (!touchState.allowDraggingOffscreen()) {
                    left = Math.max(mMovementBounds.left, Math.min(mMovementBounds.right, left));
                }
                top = Math.max(mMovementBounds.top, Math.min(mMovementBounds.bottom, top));
                mTmpBounds.offsetTo((int) left, (int) top);
                mMotionHelper.movePip(mTmpBounds);

                if (ENABLE_DRAG_TO_DISMISS) {
                    mDismissViewController.updateDismissTarget(mTmpBounds);
                }
                return true;
            }
            return false;
        }

        @Override
        public boolean onUp(PipTouchState touchState) {
            if (!touchState.isUserInteracting()) {
                return false;
            }

            try {
                if (ENABLE_DRAG_TO_DISMISS) {
                    mHandler.removeCallbacks(mShowDismissAffordance);
                    PointF vel = mTouchState.getVelocity();
                    final float velocity = PointF.length(vel.x, vel.y);
                    if (touchState.isDragging()
                            && velocity < mFlingAnimationUtils.getMinVelocityPxPerSecond()) {
                        if (mDismissViewController.shouldDismiss(mMotionHelper.getBounds())) {
                            Rect dismissBounds = mDismissViewController.getDismissBounds();
                            mMotionHelper.animateDismissFromDrag(dismissBounds);
                            MetricsLogger.action(mContext,
                                    MetricsEvent.ACTION_PICTURE_IN_PICTURE_DISMISSED,
                                    METRIC_VALUE_DISMISSED_BY_DRAG);
                            return true;
                        }
                    }
                }
            } finally {
                mDismissViewController.destroyDismissTarget();
            }
            if (touchState.isDragging()) {
                PointF vel = mTouchState.getVelocity();
                if (!mIsMinimized && (mMotionHelper.shouldMinimizePip()
                        || isHorizontalFlingTowardsCurrentEdge(vel))) {
                    // Pip should be minimized
                    setMinimizedStateInternal(true);
                    mMotionHelper.animateToClosestMinimizedState(mMovementBounds, mMenuController);
                    return true;
                }
                if (mIsMinimized) {
                    // If we're dragging and it wasn't a minimize gesture
                    // then we shouldn't be minimized.
                    setMinimizedStateInternal(false);
                }

                final float velocity = PointF.length(vel.x, vel.y);
                if (velocity > mFlingAnimationUtils.getMinVelocityPxPerSecond()) {
                    mMotionHelper.flingToSnapTarget(velocity, vel.x, vel.y, mMovementBounds);
                } else {
                    mMotionHelper.animateToClosestSnapTarget(mMovementBounds);
                }
            } else if (mIsMinimized) {
                // This was a tap, so no longer minimized
                mMotionHelper.animateToClosestSnapTarget(mMovementBounds);
                setMinimizedStateInternal(false);
            } else if (!mIsTappingThrough) {
                mMenuController.showMenu();
                mIsTappingThrough = true;
            } else {
                mMotionHelper.expandPip();
            }
            return true;
        }
    };

    /**
     * @return whether the gesture ending in the {@param vel} is fast enough to be a fling towards
     *         the same edge the PIP is on. Used to identify a minimize gesture.
     */
    private boolean isHorizontalFlingTowardsCurrentEdge(PointF vel) {
        final boolean isHorizontal = Math.abs(vel.x) > Math.abs(vel.y);
        final boolean isFling = PointF.length(vel.x, vel.y) > mFlingAnimationUtils
                .getMinVelocityPxPerSecond();
        final boolean towardsCurrentEdge = isOverEdge(true /* left */) && vel.x < 0
                || isOverEdge(false /* right */) && vel.x > 0;
        return towardsCurrentEdge && isHorizontal && isFling;
    }

    /**
     * @return whether the given bounds are on the left or right edge (depending on
     *         {@param checkLeft})
     */
    private boolean isOverEdge(boolean checkLeft) {
        final Rect bounds = mMotionHelper.getBounds();
        if (checkLeft) {
            return bounds.left <= mMovementBounds.left;
        } else {
            return bounds.right >= mMovementBounds.right + bounds.width();
        }
    }

    /**
     * Updates the current movement bounds based on whether the menu is currently visible.
     */
    private void updateMovementBounds() {
        mMovementBounds = mIsMenuVisible
                ? mExpandedMovementBounds
                : mNormalMovementBounds;
    }

    public void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + TAG);
        pw.println(innerPrefix + "mMovementBounds=" + mMovementBounds);
        pw.println(innerPrefix + "mNormalBounds=" + mNormalBounds);
        pw.println(innerPrefix + "mNormalMovementBounds=" + mNormalMovementBounds);
        pw.println(innerPrefix + "mExpandedBounds=" + mExpandedBounds);
        pw.println(innerPrefix + "mExpandedMovementBounds=" + mExpandedMovementBounds);
        pw.println(innerPrefix + "mIsTappingThrough=" + mIsTappingThrough);
        pw.println(innerPrefix + "mIsMinimized=" + mIsMinimized);
        pw.println(innerPrefix + "mIsMenuVisible=" + mIsMenuVisible);
        pw.println(innerPrefix + "mIsImeShowing=" + mIsImeShowing);
        pw.println(innerPrefix + "mImeHeight=" + mImeHeight);
        pw.println(innerPrefix + "mSavedSnapFraction=" + mSavedSnapFraction);
        pw.println(innerPrefix + "mEnableDragToDismiss=" + ENABLE_DRAG_TO_DISMISS);
        mSnapAlgorithm.dump(pw, innerPrefix);
        mTouchState.dump(pw, innerPrefix);
        mMotionHelper.dump(pw, innerPrefix);
    }
}
