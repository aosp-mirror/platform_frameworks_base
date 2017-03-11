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

import android.app.IActivityManager;
import android.content.Context;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;
import android.util.Size;
import android.view.IPinnedStackController;
import android.view.MagnificationSpec;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.accessibility.IAccessibilityInteractionConnection;
import android.view.accessibility.IAccessibilityInteractionConnectionCallback;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.policy.PipSnapAlgorithm;
import com.android.systemui.R;
import com.android.systemui.statusbar.FlingAnimationUtils;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

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
    private static final boolean ENABLE_DISMISS_DRAG_TO_TARGET = false;
    private static final boolean ENABLE_DISMISS_DRAG_TO_EDGE = false;

    private final Context mContext;
    private final IActivityManager mActivityManager;
    private final ViewConfiguration mViewConfig;
    private final PipMenuListener mMenuListener = new PipMenuListener();
    private IPinnedStackController mPinnedStackController;

    private final PipMenuActivityController mMenuController;
    private final PipDismissViewController mDismissViewController;
    private final PipSnapAlgorithm mSnapAlgorithm;
    private final AccessibilityManager mAccessibilityManager;

    // The current movement bounds
    private Rect mMovementBounds = new Rect();

    // The reference bounds used to calculate the normal/expanded target bounds
    private Rect mNormalBounds = new Rect();
    private Rect mNormalMovementBounds = new Rect();
    private Rect mExpandedBounds = new Rect();
    private Rect mExpandedMovementBounds = new Rect();
    private int mExpandedShortestEdgeSize;

    private Handler mHandler = new Handler();
    private Runnable mShowDismissAffordance = new Runnable() {
        @Override
        public void run() {
            if (ENABLE_DISMISS_DRAG_TO_TARGET) {
                mDismissViewController.showDismissTarget(mMotionHelper.getBounds());
            }
        }
    };

    private Runnable mShowMenuRunnable = new Runnable() {
        @Override
        public void run() {
            mMenuController.showMenu(mMotionHelper.getBounds(), mMovementBounds);
        }
    };

    // Behaviour states
    private boolean mIsMenuVisible;
    private boolean mIsMinimized;
    private boolean mIsImeShowing;
    private int mImeHeight;
    private float mSavedSnapFraction = -1f;
    private boolean mSendingHoverAccessibilityEvents;

    // Touch state
    private final PipTouchState mTouchState;
    private final FlingAnimationUtils mFlingAnimationUtils;
    private final PipTouchGesture[] mGestures;
    private final PipMotionHelper mMotionHelper;

    // Temp vars
    private final Rect mTmpBounds = new Rect();

    /**
     * A listener for the PIP menu activity.
     */
    private class PipMenuListener implements PipMenuActivityController.Listener {
        @Override
        public void onPipMenuVisibilityChanged(boolean menuVisible, boolean resize) {
            setMenuVisibilityState(menuVisible, resize);
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
            mMotionHelper.animateToClosestMinimizedState(mMovementBounds);
        }

        @Override
        public void onPipDismiss() {
            mMotionHelper.dismissPip();
            MetricsLogger.action(mContext, MetricsEvent.ACTION_PICTURE_IN_PICTURE_DISMISSED,
                    METRIC_VALUE_DISMISSED_BY_TAP);
        }
    }

    public PipTouchHandler(Context context, IActivityManager activityManager,
            PipMenuActivityController menuController,
            InputConsumerController inputConsumerController) {

        // Initialize the Pip input consumer
        mContext = context;
        mActivityManager = activityManager;
        mAccessibilityManager = context.getSystemService(AccessibilityManager.class);
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
        mExpandedShortestEdgeSize = context.getResources().getDimensionPixelSize(
                R.dimen.pip_expanded_shortest_edge_size);

        // Register the listener for input consumer touch events
        inputConsumerController.setTouchListener(this::handleTouchEvent);
        inputConsumerController.setRegistrationListener(this::onRegistrationChanged);
        onRegistrationChanged(inputConsumerController.isRegistered());
    }

    public void setTouchEnabled(boolean enabled) {
        mTouchState.setAllowTouches(enabled);
    }

    public void onActivityPinned() {
        // Reset some states once we are pinned
        if (mIsMenuVisible) {
            mIsMenuVisible = false;
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

    public void onMovementBoundsChanged(Rect insetBounds, Rect normalBounds, Rect animatingBounds,
            boolean fromImeAdjustement) {
        // Re-calculate the expanded bounds
        mNormalBounds = normalBounds;
        Rect normalMovementBounds = new Rect();
        mSnapAlgorithm.getMovementBounds(mNormalBounds, insetBounds, normalMovementBounds,
                mIsImeShowing ? mImeHeight : 0);

        // Calculate the expanded size
        float aspectRatio = (float) normalBounds.width() / normalBounds.height();
        Point displaySize = new Point();
        mContext.getDisplay().getRealSize(displaySize);
        Size expandedSize = mSnapAlgorithm.getSizeForAspectRatio(aspectRatio,
                mExpandedShortestEdgeSize, displaySize.x, displaySize.y);
        mExpandedBounds.set(0, 0, expandedSize.getWidth(), expandedSize.getHeight());
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
                final Rect bounds = new Rect(animatingBounds);
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
                mMotionHelper.animateToIMEOffset(bounds);
            }
        }

        // Update the movement bounds after doing the calculations based on the old movement bounds
        // above
        mNormalMovementBounds = normalMovementBounds;
        mExpandedMovementBounds = expandedMovementBounds;
        updateMovementBounds(mIsMenuVisible);
    }

    private void onRegistrationChanged(boolean isRegistered) {
        mAccessibilityManager.setPictureInPictureActionReplacingConnection(isRegistered
                ? new AccessibilityInteractionConnection() : null);
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
                updateMovementBounds(mIsMenuVisible);

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
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE: {
                if (!mSendingHoverAccessibilityEvents) {
                    AccessibilityEvent event = AccessibilityEvent.obtain(
                            AccessibilityEvent.TYPE_VIEW_HOVER_ENTER);
                    AccessibilityNodeInfo info = obtainRootAccessibilityNodeInfo();
                    event.setSource(info);
                    info.recycle();
                    mAccessibilityManager.sendAccessibilityEvent(event);
                    mSendingHoverAccessibilityEvents = true;
                }
                break;
            }
            case MotionEvent.ACTION_HOVER_EXIT: {
                if (mSendingHoverAccessibilityEvents) {
                    AccessibilityEvent event = AccessibilityEvent.obtain(
                            AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);
                    AccessibilityNodeInfo info = obtainRootAccessibilityNodeInfo();
                    event.setSource(info);
                    info.recycle();
                    mAccessibilityManager.sendAccessibilityEvent(event);
                    mSendingHoverAccessibilityEvents = false;
                }
                break;
            }
        }
        return !mIsMenuVisible;
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
    void setMenuVisibilityState(boolean menuVisible, boolean resize) {
        if (menuVisible) {
            // Save the current snap fraction and if we do not drag or move the PiP, then
            // we store back to this snap fraction.  Otherwise, we'll reset the snap
            // fraction and snap to the closest edge
            Rect expandedBounds = new Rect(mExpandedBounds);
            if (resize) {
                mSavedSnapFraction = mMotionHelper.animateToExpandedState(expandedBounds,
                        mMovementBounds, mExpandedMovementBounds);
            }
        } else {
            // Try and restore the PiP to the closest edge, using the saved snap fraction
            // if possible
            if (resize) {
                Rect normalBounds = new Rect(mNormalBounds);
                mMotionHelper.animateToUnexpandedState(normalBounds, mSavedSnapFraction,
                        mNormalMovementBounds, mMovementBounds, mIsMinimized);
            }
            mSavedSnapFraction = -1f;
        }
        mIsMenuVisible = menuVisible;
        updateMovementBounds(menuVisible);
        MetricsLogger.visibility(mContext, MetricsEvent.ACTION_PICTURE_IN_PICTURE_MENU,
                menuVisible);
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

            // If the menu is still visible, and we aren't minimized, then just poke the menu
            // so that it will timeout after the user stops touching it
            if (mMenuController.isMenuVisible() && !mIsMinimized) {
                mMenuController.pokeMenu();
            }

            if (ENABLE_DISMISS_DRAG_TO_TARGET) {
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

            if (touchState.startedDragging() && ENABLE_DISMISS_DRAG_TO_TARGET) {
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
                if (ENABLE_DISMISS_DRAG_TO_EDGE) {
                    // Allow pip to move past bottom bounds
                    top = Math.max(mMovementBounds.top, top);
                } else {
                    top = Math.max(mMovementBounds.top, Math.min(mMovementBounds.bottom, top));
                }
                mTmpBounds.offsetTo((int) left, (int) top);
                mMotionHelper.movePip(mTmpBounds);

                if (ENABLE_DISMISS_DRAG_TO_TARGET) {
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
                if (ENABLE_DISMISS_DRAG_TO_TARGET) {
                    mHandler.removeCallbacks(mShowDismissAffordance);
                    PointF vel = mTouchState.getVelocity();
                    final float velocity = PointF.length(vel.x, vel.y);
                    if (touchState.isDragging()
                            && velocity < mFlingAnimationUtils.getMinVelocityPxPerSecond()) {
                        if (mDismissViewController.shouldDismiss(mMotionHelper.getBounds())) {
                            Rect dismissBounds = mDismissViewController.getDismissBounds();
                            mMotionHelper.animateDragToTargetDismiss(dismissBounds);
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
                final boolean onLeft = mMotionHelper.getBounds().left < mMovementBounds.centerX();
                boolean isFlingToBot = isFlingTowardsEdge(touchState, 4 /* bottom */);
                if (ENABLE_DISMISS_DRAG_TO_EDGE
                        && (mMotionHelper.shouldDismissPip() || isFlingToBot)) {
                    mMotionHelper.animateDragToEdgeDismiss(mMotionHelper.getBounds());
                    MetricsLogger.action(mContext,
                            MetricsEvent.ACTION_PICTURE_IN_PICTURE_DISMISSED,
                            METRIC_VALUE_DISMISSED_BY_DRAG);
                    return true;
                } else if (!mIsMinimized && (mMotionHelper.shouldMinimizePip()
                        || isFlingTowardsEdge(touchState, onLeft ? 2 : 3))) {
                    // Pip should be minimized
                    setMinimizedStateInternal(true);
                    if (mMenuController.isMenuVisible()) {
                        // If the user dragged the expanded PiP to the edge, then hiding the menu
                        // will trigger the PiP to be scaled back to the normal size with the
                        // minimize offset adjusted
                        mMenuController.hideMenu();
                    } else {
                        mMotionHelper.animateToClosestMinimizedState(mMovementBounds);
                    }
                    return true;
                }
                if (mIsMinimized) {
                    // If we're dragging and it wasn't a minimize gesture then we shouldn't be
                    // minimized.
                    setMinimizedStateInternal(false);
                }

                // If the menu is still visible, and we aren't minimized, then just poke the menu
                // so that it will timeout after the user stops touching it
                if (mMenuController.isMenuVisible()) {
                    mMenuController.showMenu(mMotionHelper.getBounds(), mMovementBounds);
                }

                final PointF vel = mTouchState.getVelocity();
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
            } else if (!mIsMenuVisible) {
                mMenuController.showMenu(mMotionHelper.getBounds(), mMovementBounds);
            } else {
                mMotionHelper.expandPip();
            }
            return true;
        }
    };

    /**
     * @return whether the gesture ending in {@param vel} is fast enough to be a fling and towards
     *         the provided {@param edge} where:
     *
     *         1 = top
     *         2 = left
     *         3 = right
     *         4 = bottom
     */
    private boolean isFlingTowardsEdge(PipTouchState touchState, int edge) {
        final PointF vel = touchState.getVelocity();
        final PointF downPos = touchState.getDownTouchPosition();
        final Rect bounds = mMotionHelper.getBounds();
        final boolean isHorizontal = Math.abs(vel.x) > Math.abs(vel.y);
        final boolean isFling =
                PointF.length(vel.x, vel.y) > mFlingAnimationUtils.getMinVelocityPxPerSecond();
        if (!isFling) {
            return false;
        }
        switch (edge) {
            case 1: // top
                return !isHorizontal && vel.y < 0
                        && downPos.y <= mMovementBounds.top + bounds.height();
            case 2: // left
                return isHorizontal && vel.x < 0
                        && downPos.x <= mMovementBounds.left + bounds.width();
            case 3: // right
                return isHorizontal && vel.x > 0
                        && downPos.x >= mMovementBounds.right;
            case 4: // bottom
                return !isHorizontal && vel.y > 0
                        && downPos.y >= mMovementBounds.bottom;
        }
        return false;
    }

    /**
     * Updates the current movement bounds based on whether the menu is currently visible.
     */
    private void updateMovementBounds(boolean isExpanded) {
        mMovementBounds = isExpanded
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
        pw.println(innerPrefix + "mIsMenuVisible=" + mIsMenuVisible);
        pw.println(innerPrefix + "mIsMinimized=" + mIsMinimized);
        pw.println(innerPrefix + "mIsImeShowing=" + mIsImeShowing);
        pw.println(innerPrefix + "mImeHeight=" + mImeHeight);
        pw.println(innerPrefix + "mSavedSnapFraction=" + mSavedSnapFraction);
        pw.println(innerPrefix + "mEnableDragToDismiss=" + ENABLE_DISMISS_DRAG_TO_TARGET);
        mSnapAlgorithm.dump(pw, innerPrefix);
        mTouchState.dump(pw, innerPrefix);
        mMotionHelper.dump(pw, innerPrefix);
    }

    private static AccessibilityNodeInfo obtainRootAccessibilityNodeInfo() {
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
        info.setSourceNodeId(AccessibilityNodeInfo.ROOT_NODE_ID,
                AccessibilityWindowInfo.PICTURE_IN_PICTURE_ACTION_REPLACER_WINDOW_ID);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_MOVE_WINDOW);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND);
        info.setImportantForAccessibility(true);
        info.setClickable(true);
        info.setVisibleToUser(true);
        return info;
    }

    /**
     * Expose the touch actions to accessibility as if this object were a window with a single view.
     * That pseudo-view exposes all of the actions this object can perform.
     */
    class AccessibilityInteractionConnection extends IAccessibilityInteractionConnection.Stub {
        static final long ACCESSIBILITY_NODE_ID = 1;
        List<AccessibilityNodeInfo> mAccessibilityNodeInfoList;

        @Override
        public void findAccessibilityNodeInfoByAccessibilityId(long accessibilityNodeId,
                Region interactiveRegion, int interactionId,
                IAccessibilityInteractionConnectionCallback callback, int flags,
                int interrogatingPid, long interrogatingTid, MagnificationSpec spec, Bundle args) {
            try {
                callback.setFindAccessibilityNodeInfosResult(
                        (accessibilityNodeId == AccessibilityNodeInfo.ROOT_NODE_ID)
                                ? getNodeList() : null, interactionId);
            } catch (RemoteException re) {
                    /* best effort - ignore */
            }
        }

        @Override
        public void performAccessibilityAction(long accessibilityNodeId, int action,
                Bundle arguments, int interactionId,
                IAccessibilityInteractionConnectionCallback callback, int flags,
                int interrogatingPid, long interrogatingTid) {
            // We only support one view. A request for anything else is invalid
            boolean result = false;
            if (accessibilityNodeId == AccessibilityNodeInfo.ROOT_NODE_ID) {
                switch (action) {
                    case AccessibilityNodeInfo.ACTION_CLICK:
                        mHandler.post(mShowMenuRunnable);
                        result = true;
                        break;
                    case AccessibilityNodeInfo.ACTION_DISMISS:
                        mMotionHelper.dismissPip();
                        result = true;
                        break;
                    case com.android.internal.R.id.accessibilityActionMoveWindow:
                        int newX = arguments.getInt(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_MOVE_WINDOW_X);
                        int newY = arguments.getInt(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_MOVE_WINDOW_Y);
                        Rect pipBounds = new Rect();
                        pipBounds.set(mMotionHelper.getBounds());
                        mTmpBounds.offsetTo(newX, newY);
                        mMotionHelper.movePip(mTmpBounds);
                        result = true;
                        break;
                    case AccessibilityNodeInfo.ACTION_EXPAND:
                        mMotionHelper.expandPip();
                        result = true;
                        break;
                    default:
                        // Leave result as false
                }
            }
            try {
                callback.setPerformAccessibilityActionResult(result, interactionId);
            } catch (RemoteException re) {
                    /* best effort - ignore */
            }
        }

        @Override
        public void findAccessibilityNodeInfosByViewId(long accessibilityNodeId,
                String viewId, Region interactiveRegion, int interactionId,
                IAccessibilityInteractionConnectionCallback callback, int flags,
                int interrogatingPid, long interrogatingTid, MagnificationSpec spec) {
            // We have no view with a proper ID
            try {
                callback.setFindAccessibilityNodeInfoResult(null, interactionId);
            } catch (RemoteException re) {
                /* best effort - ignore */
            }
        }

        @Override
        public void findAccessibilityNodeInfosByText(long accessibilityNodeId, String text,
                Region interactiveRegion, int interactionId,
                IAccessibilityInteractionConnectionCallback callback, int flags,
                int interrogatingPid, long interrogatingTid, MagnificationSpec spec) {
            // We have no view with text
            try {
                callback.setFindAccessibilityNodeInfoResult(null, interactionId);
            } catch (RemoteException re) {
                /* best effort - ignore */
            }
        }

        @Override
        public void findFocus(long accessibilityNodeId, int focusType, Region interactiveRegion,
                int interactionId, IAccessibilityInteractionConnectionCallback callback, int flags,
                int interrogatingPid, long interrogatingTid, MagnificationSpec spec) {
            // We have no view that can take focus
            try {
                callback.setFindAccessibilityNodeInfoResult(null, interactionId);
            } catch (RemoteException re) {
                /* best effort - ignore */
            }
        }

        @Override
        public void focusSearch(long accessibilityNodeId, int direction, Region interactiveRegion,
                int interactionId, IAccessibilityInteractionConnectionCallback callback, int flags,
                int interrogatingPid, long interrogatingTid, MagnificationSpec spec) {
            // We have no view that can take focus
            try {
                callback.setFindAccessibilityNodeInfoResult(null, interactionId);
            } catch (RemoteException re) {
                /* best effort - ignore */
            }
        }

        private List<AccessibilityNodeInfo> getNodeList() {
            if (mAccessibilityNodeInfoList == null) {
                mAccessibilityNodeInfoList = new ArrayList<>(1);
            }
            AccessibilityNodeInfo info = obtainRootAccessibilityNodeInfo();
            mAccessibilityNodeInfoList.clear();
            mAccessibilityNodeInfoList.add(info);
            return mAccessibilityNodeInfoList;
        }
    }
}
