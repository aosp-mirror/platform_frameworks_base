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

import static com.android.systemui.pip.phone.PipMenuActivityController.MENU_STATE_CLOSE;
import static com.android.systemui.pip.phone.PipMenuActivityController.MENU_STATE_FULL;
import static com.android.systemui.pip.phone.PipMenuActivityController.MENU_STATE_NONE;

import android.app.IActivityManager;
import android.app.IActivityTaskManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;
import android.util.Pair;
import android.util.Size;
import android.view.IPinnedStackController;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.logging.MetricsLoggerWrapper;
import com.android.systemui.R;
import com.android.systemui.pip.PipBoundsHandler;
import com.android.systemui.pip.PipSnapAlgorithm;
import com.android.systemui.pip.PipTaskOrganizer;
import com.android.systemui.shared.system.InputConsumerController;
import com.android.systemui.statusbar.FlingAnimationUtils;
import com.android.systemui.util.DeviceConfigProxy;
import com.android.systemui.util.FloatingContentCoordinator;

import java.io.PrintWriter;

/**
 * Manages all the touch handling for PIP on the Phone, including moving, dismissing and expanding
 * the PIP.
 */
public class PipTouchHandler {
    private static final String TAG = "PipTouchHandler";

    // Allow the PIP to be flung from anywhere on the screen to the bottom to be dismissed.
    private static final boolean ENABLE_FLING_DISMISS = false;

    private static final int SHOW_DISMISS_AFFORDANCE_DELAY = 225;
    private static final int BOTTOM_OFFSET_BUFFER_DP = 1;

    // Allow dragging the PIP to a location to close it
    private final boolean mEnableDismissDragToEdge;
    // Allow PIP to resize to a slightly bigger state upon touch
    private final boolean mEnableResize;
    private final Context mContext;
    private final IActivityManager mActivityManager;
    private final PipBoundsHandler mPipBoundsHandler;
    private PipResizeGestureHandler mPipResizeGestureHandler;
    private IPinnedStackController mPinnedStackController;

    private final PipMenuActivityController mMenuController;
    private final PipDismissViewController mDismissViewController;
    private final PipSnapAlgorithm mSnapAlgorithm;
    private final AccessibilityManager mAccessibilityManager;
    private boolean mShowPipMenuOnAnimationEnd = false;

    // The current movement bounds
    private Rect mMovementBounds = new Rect();
    // The current resized bounds, changed by user resize.
    // This is used during expand/un-expand to save/restore the user's resized size.
    @VisibleForTesting Rect mResizedBounds = new Rect();

    // The reference inset bounds, used to determine the dismiss fraction
    private Rect mInsetBounds = new Rect();
    // The reference bounds used to calculate the normal/expanded target bounds
    private Rect mNormalBounds = new Rect();
    @VisibleForTesting Rect mNormalMovementBounds = new Rect();
    private Rect mExpandedBounds = new Rect();
    @VisibleForTesting Rect mExpandedMovementBounds = new Rect();
    private int mExpandedShortestEdgeSize;

    // Used to workaround an issue where the WM rotation happens before we are notified, allowing
    // us to send stale bounds
    private int mDeferResizeToNormalBoundsUntilRotation = -1;
    private int mDisplayRotation;

    private Handler mHandler = new Handler();
    private Runnable mShowDismissAffordance = new Runnable() {
        @Override
        public void run() {
            if (mEnableDismissDragToEdge) {
                mDismissViewController.showDismissTarget();
            }
        }
    };

    // Behaviour states
    private int mMenuState = MENU_STATE_NONE;
    private boolean mIsImeShowing;
    private int mImeHeight;
    private int mImeOffset;
    private boolean mIsShelfShowing;
    private int mShelfHeight;
    private int mMovementBoundsExtraOffsets;
    private float mSavedSnapFraction = -1f;
    private boolean mSendingHoverAccessibilityEvents;
    private boolean mMovementWithinDismiss;
    private PipAccessibilityInteractionConnection mConnection;

    // Touch state
    private final PipTouchState mTouchState;
    private final FlingAnimationUtils mFlingAnimationUtils;
    private final FloatingContentCoordinator mFloatingContentCoordinator;
    private PipMotionHelper mMotionHelper;
    private PipTouchGesture mGesture;

    // Temp vars
    private final Rect mTmpBounds = new Rect();

    /**
     * A listener for the PIP menu activity.
     */
    private class PipMenuListener implements PipMenuActivityController.Listener {
        @Override
        public void onPipMenuStateChanged(int menuState, boolean resize) {
            setMenuState(menuState, resize);
        }

        @Override
        public void onPipExpand() {
            mMotionHelper.expandPip();
        }

        @Override
        public void onPipDismiss() {
            Pair<ComponentName, Integer> topPipActivity = PipUtils.getTopPipActivity(mContext,
                    mActivityManager);
            if (topPipActivity.first != null) {
                MetricsLoggerWrapper.logPictureInPictureDismissByTap(mContext, topPipActivity);
            }
            mMotionHelper.dismissPip();
        }

        @Override
        public void onPipShowMenu() {
            mMenuController.showMenu(MENU_STATE_FULL, mMotionHelper.getBounds(),
                    mMovementBounds, true /* allowMenuTimeout */, willResizeMenu());
        }
    }

    public PipTouchHandler(Context context, IActivityManager activityManager,
            IActivityTaskManager activityTaskManager, PipMenuActivityController menuController,
            InputConsumerController inputConsumerController,
            PipBoundsHandler pipBoundsHandler,
            PipTaskOrganizer pipTaskOrganizer,
            FloatingContentCoordinator floatingContentCoordinator,
            DeviceConfigProxy deviceConfig,
            PipSnapAlgorithm pipSnapAlgorithm) {
        // Initialize the Pip input consumer
        mContext = context;
        mActivityManager = activityManager;
        mAccessibilityManager = context.getSystemService(AccessibilityManager.class);
        mMenuController = menuController;
        mMenuController.addListener(new PipMenuListener());
        mDismissViewController = new PipDismissViewController(context);
        mSnapAlgorithm = pipSnapAlgorithm;
        mFlingAnimationUtils = new FlingAnimationUtils(context.getResources().getDisplayMetrics(),
                2.5f);
        mGesture = new DefaultPipTouchGesture();
        mMotionHelper = new PipMotionHelper(mContext, activityTaskManager, pipTaskOrganizer,
                mMenuController, mSnapAlgorithm, mFlingAnimationUtils, floatingContentCoordinator);
        mPipResizeGestureHandler =
                new PipResizeGestureHandler(context, pipBoundsHandler, this, mMotionHelper,
                        deviceConfig, pipTaskOrganizer);
        mTouchState = new PipTouchState(ViewConfiguration.get(context), mHandler,
                () -> mMenuController.showMenu(MENU_STATE_FULL, mMotionHelper.getBounds(),
                        mMovementBounds, true /* allowMenuTimeout */, willResizeMenu()));

        Resources res = context.getResources();
        mExpandedShortestEdgeSize = res.getDimensionPixelSize(
                R.dimen.pip_expanded_shortest_edge_size);
        mImeOffset = res.getDimensionPixelSize(R.dimen.pip_ime_offset);

        mEnableDismissDragToEdge = res.getBoolean(R.bool.config_pipEnableDismissDragToEdge);
        mEnableResize = res.getBoolean(R.bool.config_pipEnableResizeForMenu);

        // Register the listener for input consumer touch events
        inputConsumerController.setInputListener(this::handleTouchEvent);
        inputConsumerController.setRegistrationListener(this::onRegistrationChanged);

        mPipBoundsHandler = pipBoundsHandler;
        mFloatingContentCoordinator = floatingContentCoordinator;
        mConnection = new PipAccessibilityInteractionConnection(mMotionHelper,
                this::onAccessibilityShowMenu, mHandler);
    }

    public void setTouchGesture(PipTouchGesture gesture) {
        mGesture = gesture;
    }

    public void setTouchEnabled(boolean enabled) {
        mTouchState.setAllowTouches(enabled);
    }

    public void showPictureInPictureMenu() {
        // Only show the menu if the user isn't currently interacting with the PiP
        if (!mTouchState.isUserInteracting()) {
            mMenuController.showMenu(MENU_STATE_FULL, mMotionHelper.getBounds(),
                    mMovementBounds, false /* allowMenuTimeout */, willResizeMenu());
        }
    }

    public void onActivityPinned() {
        cleanUpDismissTarget();
        mShowPipMenuOnAnimationEnd = true;
        mPipResizeGestureHandler.onActivityPinned();
        mFloatingContentCoordinator.onContentAdded(mMotionHelper);
    }

    public void onActivityUnpinned(ComponentName topPipActivity) {
        if (topPipActivity == null) {
            // Clean up state after the last PiP activity is removed
            cleanUpDismissTarget();

            mFloatingContentCoordinator.onContentRemoved(mMotionHelper);
        }
        mResizedBounds.setEmpty();
        mPipResizeGestureHandler.onActivityUnpinned();
    }

    public void onPinnedStackAnimationEnded() {
        // Always synchronize the motion helper bounds once PiP animations finish
        mMotionHelper.synchronizePinnedStackBounds();
        updateMovementBounds();
        mResizedBounds.set(mMotionHelper.getBounds());

        if (mShowPipMenuOnAnimationEnd) {
            mMenuController.showMenu(MENU_STATE_CLOSE, mMotionHelper.getBounds(),
                    mMovementBounds, true /* allowMenuTimeout */, false /* willResizeMenu */);
            mShowPipMenuOnAnimationEnd = false;
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

    public void onShelfVisibilityChanged(boolean shelfVisible, int shelfHeight) {
        mIsShelfShowing = shelfVisible;
        mShelfHeight = shelfHeight;
    }

    public void onMovementBoundsChanged(Rect insetBounds, Rect normalBounds, Rect curBounds,
            boolean fromImeAdjustment, boolean fromShelfAdjustment, int displayRotation) {
        final int bottomOffset = mIsImeShowing ? mImeHeight : 0;
        final boolean fromDisplayRotationChanged = (mDisplayRotation != displayRotation);
        if (fromDisplayRotationChanged) {
            mTouchState.reset();
        }

        // Re-calculate the expanded bounds
        mNormalBounds = normalBounds;
        Rect normalMovementBounds = new Rect();
        mSnapAlgorithm.getMovementBounds(mNormalBounds, insetBounds, normalMovementBounds,
                bottomOffset);

        // Calculate the expanded size
        float aspectRatio = (float) normalBounds.width() / normalBounds.height();
        Point displaySize = new Point();
        mContext.getDisplay().getRealSize(displaySize);
        Size expandedSize = mSnapAlgorithm.getSizeForAspectRatio(aspectRatio,
                mExpandedShortestEdgeSize, displaySize.x, displaySize.y);
        mExpandedBounds.set(0, 0, expandedSize.getWidth(), expandedSize.getHeight());
        Rect expandedMovementBounds = new Rect();
        mSnapAlgorithm.getMovementBounds(mExpandedBounds, insetBounds, expandedMovementBounds,
                bottomOffset);

        mPipResizeGestureHandler.updateMinSize(mNormalBounds.width(), mNormalBounds.height());
        mPipResizeGestureHandler.updateMaxSize(mExpandedBounds.width(), mExpandedBounds.height());

        // The extra offset does not really affect the movement bounds, but are applied based on the
        // current state (ime showing, or shelf offset) when we need to actually shift
        int extraOffset = Math.max(
                mIsImeShowing ? mImeOffset : 0,
                !mIsImeShowing && mIsShelfShowing ? mShelfHeight : 0);

        // If this is from an IME or shelf adjustment, then we should move the PiP so that it is not
        // occluded by the IME or shelf.
        if (fromImeAdjustment || fromShelfAdjustment || fromDisplayRotationChanged) {
            if (mTouchState.isUserInteracting()) {
                // Defer the update of the current movement bounds until after the user finishes
                // touching the screen
            } else {
                final float offsetBufferPx = BOTTOM_OFFSET_BUFFER_DP
                        * mContext.getResources().getDisplayMetrics().density;
                final Rect toMovementBounds = mMenuState == MENU_STATE_FULL && willResizeMenu()
                        ? new Rect(expandedMovementBounds)
                        : new Rect(normalMovementBounds);
                final int prevBottom = mMovementBounds.bottom - mMovementBoundsExtraOffsets;
                final int toBottom = toMovementBounds.bottom < toMovementBounds.top
                        ? toMovementBounds.bottom
                        : toMovementBounds.bottom - extraOffset;
                if ((Math.min(prevBottom, toBottom) - offsetBufferPx) <= curBounds.top
                        && curBounds.top <= (Math.max(prevBottom, toBottom) + offsetBufferPx)) {
                    mMotionHelper.animateToOffset(curBounds, toBottom - curBounds.top);
                }
            }
        }

        // Update the movement bounds after doing the calculations based on the old movement bounds
        // above
        mNormalMovementBounds = normalMovementBounds;
        mExpandedMovementBounds = expandedMovementBounds;
        mDisplayRotation = displayRotation;
        mInsetBounds.set(insetBounds);
        updateMovementBounds();
        mMovementBoundsExtraOffsets = extraOffset;

        // If we have a deferred resize, apply it now
        if (mDeferResizeToNormalBoundsUntilRotation == displayRotation) {
            mMotionHelper.animateToUnexpandedState(normalBounds, mSavedSnapFraction,
                    mNormalMovementBounds, mMovementBounds, true /* immediate */);
            mSavedSnapFraction = -1f;
            mDeferResizeToNormalBoundsUntilRotation = -1;
        }
    }

    private void onRegistrationChanged(boolean isRegistered) {
        mAccessibilityManager.setPictureInPictureActionReplacingConnection(isRegistered
                ? mConnection : null);
        if (!isRegistered && mTouchState.isUserInteracting()) {
            // If the input consumer is unregistered while the user is interacting, then we may not
            // get the final TOUCH_UP event, so clean up the dismiss target as well
            cleanUpDismissTarget();
        }
    }

    private void onAccessibilityShowMenu() {
        mMenuController.showMenu(MENU_STATE_FULL, mMotionHelper.getBounds(),
                mMovementBounds, true /* allowMenuTimeout */, willResizeMenu());
    }

    private boolean handleTouchEvent(InputEvent inputEvent) {
        // Skip any non motion events
        if (!(inputEvent instanceof MotionEvent)) {
            return true;
        }
        // Skip touch handling until we are bound to the controller
        if (mPinnedStackController == null) {
            return true;
        }
        MotionEvent ev = (MotionEvent) inputEvent;

        // Update the touch state
        mTouchState.onTouchEvent(ev);

        boolean shouldDeliverToMenu = mMenuState != MENU_STATE_NONE;

        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN: {
                mMotionHelper.synchronizePinnedStackBoundsForTouchGesture();
                mGesture.onDown(mTouchState);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mGesture.onMove(mTouchState)) {
                    break;
                }

                shouldDeliverToMenu = !mTouchState.isDragging();
                break;
            }
            case MotionEvent.ACTION_UP: {
                // Update the movement bounds again if the state has changed since the user started
                // dragging (ie. when the IME shows)
                updateMovementBounds();

                if (mGesture.onUp(mTouchState)) {
                    break;
                }

                // Fall through to clean up
            }
            case MotionEvent.ACTION_CANCEL: {
                shouldDeliverToMenu = !mTouchState.startedDragging() && !mTouchState.isDragging();
                mTouchState.reset();
                break;
            }
            case MotionEvent.ACTION_HOVER_ENTER:
                mMenuController.showMenu(MENU_STATE_FULL, mMotionHelper.getBounds(),
                        mMovementBounds, false /* allowMenuTimeout */, false /* willResizeMenu */);
            case MotionEvent.ACTION_HOVER_MOVE: {
                if (!shouldDeliverToMenu && !mSendingHoverAccessibilityEvents) {
                    sendAccessibilityHoverEvent(AccessibilityEvent.TYPE_VIEW_HOVER_ENTER);
                    mSendingHoverAccessibilityEvents = true;
                }
                break;
            }
            case MotionEvent.ACTION_HOVER_EXIT: {
                mMenuController.hideMenu();
                if (!shouldDeliverToMenu && mSendingHoverAccessibilityEvents) {
                    sendAccessibilityHoverEvent(AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);
                    mSendingHoverAccessibilityEvents = false;
                }
                break;
            }
        }

        // Deliver the event to PipMenuActivity to handle button click if the menu has shown.
        if (shouldDeliverToMenu) {
            final MotionEvent cloneEvent = MotionEvent.obtain(ev);
            // Send the cancel event and cancel menu timeout if it starts to drag.
            if (mTouchState.startedDragging()) {
                cloneEvent.setAction(MotionEvent.ACTION_CANCEL);
                mMenuController.pokeMenu();
            }

            mMenuController.handlePointerEvent(cloneEvent);
        }

        return true;
    }

    private void sendAccessibilityHoverEvent(int type) {
        if (!mAccessibilityManager.isEnabled()) {
            return;
        }

        AccessibilityEvent event = AccessibilityEvent.obtain(type);
        event.setImportantForAccessibility(true);
        event.setSourceNodeId(AccessibilityNodeInfo.ROOT_NODE_ID);
        event.setWindowId(
                AccessibilityWindowInfo.PICTURE_IN_PICTURE_ACTION_REPLACER_WINDOW_ID);
        mAccessibilityManager.sendAccessibilityEvent(event);
    }

    /**
     * Updates the appearance of the menu and scrim on top of the PiP while dismissing.
     */
    private void updateDismissFraction() {
        // Skip updating the dismiss fraction when the IME is showing. This is to work around an
        // issue where starting the menu activity for the dismiss overlay will steal the window
        // focus, which closes the IME.
        if (mMenuController != null && !mIsImeShowing) {
            Rect bounds = mMotionHelper.getBounds();
            final float target = mInsetBounds.bottom;
            float fraction = 0f;
            if (bounds.bottom > target) {
                final float distance = bounds.bottom - target;
                fraction = Math.min(distance / bounds.height(), 1f);
            }
            if (Float.compare(fraction, 0f) != 0 || mMenuController.isMenuActivityVisible()) {
                // Update if the fraction > 0, or if fraction == 0 and the menu was already visible
                mMenuController.setDismissFraction(fraction);
            }
        }
    }

    /**
     * Sets the controller to update the system of changes from user interaction.
     */
    void setPinnedStackController(IPinnedStackController controller) {
        mPinnedStackController = controller;
    }

    /**
     * Sets the menu visibility.
     */
    private void setMenuState(int menuState, boolean resize) {
        if (mMenuState == menuState && !resize) {
            return;
        }

        if (menuState == MENU_STATE_FULL && mMenuState != MENU_STATE_FULL) {
            // Save the current snap fraction and if we do not drag or move the PiP, then
            // we store back to this snap fraction.  Otherwise, we'll reset the snap
            // fraction and snap to the closest edge.
            // Also save the current resized bounds so when the menu disappears, we can restore it.
            if (resize) {
                mResizedBounds.set(mMotionHelper.getBounds());
                Rect expandedBounds = new Rect(mExpandedBounds);
                mSavedSnapFraction = mMotionHelper.animateToExpandedState(expandedBounds,
                        mMovementBounds, mExpandedMovementBounds);
            }
        } else if (menuState == MENU_STATE_NONE && mMenuState == MENU_STATE_FULL) {
            // Try and restore the PiP to the closest edge, using the saved snap fraction
            // if possible
            if (resize) {
                if (mDeferResizeToNormalBoundsUntilRotation == -1) {
                    // This is a very special case: when the menu is expanded and visible,
                    // navigating to another activity can trigger auto-enter PiP, and if the
                    // revealed activity has a forced rotation set, then the controller will get
                    // updated with the new rotation of the display. However, at the same time,
                    // SystemUI will try to hide the menu by creating an animation to the normal
                    // bounds which are now stale.  In such a case we defer the animation to the
                    // normal bounds until after the next onMovementBoundsChanged() call to get the
                    // bounds in the new orientation
                    try {
                        int displayRotation = mPinnedStackController.getDisplayRotation();
                        if (mDisplayRotation != displayRotation) {
                            mDeferResizeToNormalBoundsUntilRotation = displayRotation;
                        }
                    } catch (RemoteException e) {
                        Log.e(TAG, "Could not get display rotation from controller");
                    }
                }

                if (mDeferResizeToNormalBoundsUntilRotation == -1) {
                    Rect restoreBounds = new Rect(mResizedBounds);
                    Rect restoredMovementBounds = new Rect();
                    mSnapAlgorithm.getMovementBounds(restoreBounds, mInsetBounds,
                            restoredMovementBounds, mIsImeShowing ? mImeHeight : 0);
                    mMotionHelper.animateToUnexpandedState(restoreBounds, mSavedSnapFraction,
                            restoredMovementBounds, mMovementBounds, false /* immediate */);
                    mSavedSnapFraction = -1f;
                }
            } else {
                mSavedSnapFraction = -1f;
            }
        }
        mMenuState = menuState;
        updateMovementBounds();
        // If pip menu has dismissed, we should register the A11y ActionReplacingConnection for pip
        // as well, or it can't handle a11y focus and pip menu can't perform any action.
        onRegistrationChanged(menuState == MENU_STATE_NONE);
        if (menuState != MENU_STATE_CLOSE) {
            MetricsLoggerWrapper.logPictureInPictureMenuVisible(mContext, menuState == MENU_STATE_FULL);
        }
    }

    /**
     * @return the motion helper.
     */
    public PipMotionHelper getMotionHelper() {
        return mMotionHelper;
    }

    @VisibleForTesting
    PipResizeGestureHandler getPipResizeGestureHandler() {
        return mPipResizeGestureHandler;
    }

    @VisibleForTesting
    void setPipResizeGestureHandler(PipResizeGestureHandler pipResizeGestureHandler) {
        mPipResizeGestureHandler = pipResizeGestureHandler;
    }

    @VisibleForTesting
    void setPipMotionHelper(PipMotionHelper pipMotionHelper) {
        mMotionHelper = pipMotionHelper;
    }

    /**
     * @return the unexpanded bounds.
     */
    public Rect getNormalBounds() {
        return mNormalBounds;
    }

    /**
     * Gesture controlling normal movement of the PIP.
     */
    private class DefaultPipTouchGesture extends PipTouchGesture {
        private final Point mStartPosition = new Point();
        private final PointF mDelta = new PointF();

        @Override
        public void onDown(PipTouchState touchState) {
            if (!touchState.isUserInteracting()) {
                return;
            }

            Rect bounds = mMotionHelper.getBounds();
            mDelta.set(0f, 0f);
            mStartPosition.set(bounds.left, bounds.top);
            mMovementWithinDismiss = touchState.getDownTouchPosition().y >= mMovementBounds.bottom;

            // If the menu is still visible then just poke the menu
            // so that it will timeout after the user stops touching it
            if (mMenuState != MENU_STATE_NONE) {
                mMenuController.pokeMenu();
            }

            if (mEnableDismissDragToEdge) {
                mDismissViewController.createDismissTarget();
                mHandler.postDelayed(mShowDismissAffordance, SHOW_DISMISS_AFFORDANCE_DELAY);
            }
        }

        @Override
        public boolean onMove(PipTouchState touchState) {
            if (!touchState.isUserInteracting()) {
                return false;
            }

            if (touchState.startedDragging()) {
                mSavedSnapFraction = -1f;

                if (mEnableDismissDragToEdge) {
                    mHandler.removeCallbacks(mShowDismissAffordance);
                    mDismissViewController.showDismissTarget();
                }
            }

            if (touchState.isDragging()) {
                // Move the pinned stack freely
                final PointF lastDelta = touchState.getLastTouchDelta();
                float lastX = mStartPosition.x + mDelta.x;
                float lastY = mStartPosition.y + mDelta.y;
                float left = lastX + lastDelta.x;
                float top = lastY + lastDelta.y;

                // Add to the cumulative delta after bounding the position
                mDelta.x += left - lastX;
                mDelta.y += top - lastY;

                mTmpBounds.set(mMotionHelper.getBounds());
                mTmpBounds.offsetTo((int) left, (int) top);
                mMotionHelper.movePip(mTmpBounds, true /* isDragging */);

                if (mEnableDismissDragToEdge) {
                    updateDismissFraction();
                }

                final PointF curPos = touchState.getLastTouchPosition();
                if (mMovementWithinDismiss) {
                    // Track if movement remains near the bottom edge to identify swipe to dismiss
                    mMovementWithinDismiss = curPos.y >= mMovementBounds.bottom;
                }
                return true;
            }
            return false;
        }

        @Override
        public boolean onUp(PipTouchState touchState) {
            if (mEnableDismissDragToEdge) {
                // Clean up the dismiss target regardless of the touch state in case the touch
                // enabled state changes while the user is interacting
                cleanUpDismissTarget();
            }

            if (!touchState.isUserInteracting()) {
                return false;
            }

            final PointF vel = touchState.getVelocity();
            final boolean isHorizontal = Math.abs(vel.x) > Math.abs(vel.y);
            final float velocity = PointF.length(vel.x, vel.y);
            final boolean isFling = velocity > mFlingAnimationUtils.getMinVelocityPxPerSecond();
            final boolean isUpWithinDimiss = ENABLE_FLING_DISMISS
                    && touchState.getLastTouchPosition().y >= mMovementBounds.bottom
                    && mMotionHelper.isGestureToDismissArea(mMotionHelper.getBounds(), vel.x,
                            vel.y, isFling);
            final boolean isFlingToBot = isFling && vel.y > 0 && !isHorizontal
                    && (mMovementWithinDismiss || isUpWithinDimiss);
            if (mEnableDismissDragToEdge) {
                // Check if the user dragged or flung the PiP offscreen to dismiss it
                if (mMotionHelper.shouldDismissPip() || isFlingToBot) {
                    MetricsLoggerWrapper.logPictureInPictureDismissByDrag(mContext,
                            PipUtils.getTopPipActivity(mContext, mActivityManager));
                    mMotionHelper.animateDismiss(
                            vel.x, vel.y,
                            PipTouchHandler.this::updateDismissFraction /* updateAction */);
                    return true;
                }
            }

            if (touchState.isDragging()) {
                Runnable endAction = null;
                if (mMenuState != MENU_STATE_NONE) {
                    // If the menu is still visible, then just poke the menu so that
                    // it will timeout after the user stops touching it
                    mMenuController.showMenu(mMenuState, mMotionHelper.getBounds(),
                            mMovementBounds, true /* allowMenuTimeout */, willResizeMenu());
                } else {
                    // If the menu is not visible, then we can still be showing the activity for the
                    // dismiss overlay, so just finish it after the animation completes
                    endAction = mMenuController::hideMenu;
                }

                if (isFling) {
                    mMotionHelper.flingToSnapTarget(vel.x, vel.y,
                            PipTouchHandler.this::updateDismissFraction /* updateAction */,
                            endAction /* endAction */);
                } else {
                    mMotionHelper.animateToClosestSnapTarget();
                }
            } else if (mTouchState.isDoubleTap()) {
                // Expand to fullscreen if this is a double tap
                // the PiP should be frozen until the transition ends
                setTouchEnabled(false);
                mMotionHelper.expandPip();
            } else if (mMenuState != MENU_STATE_FULL) {
                if (!mTouchState.isWaitingForDoubleTap()) {
                    // User has stalled long enough for this not to be a drag or a double tap, just
                    // expand the menu
                    mMenuController.showMenu(MENU_STATE_FULL, mMotionHelper.getBounds(),
                            mMovementBounds, true /* allowMenuTimeout */, willResizeMenu());
                } else {
                    // Next touch event _may_ be the second tap for the double-tap, schedule a
                    // fallback runnable to trigger the menu if no touch event occurs before the
                    // next tap
                    mTouchState.scheduleDoubleTapTimeoutCallback();
                }
            }
            return true;
        }
    };

    /**
     * Updates the current movement bounds based on whether the menu is currently visible and
     * resized.
     */
    private void updateMovementBounds() {
        mSnapAlgorithm.getMovementBounds(mMotionHelper.getBounds(), mInsetBounds,
                mMovementBounds, mIsImeShowing ? mImeHeight : 0);
        mMotionHelper.setCurrentMovementBounds(mMovementBounds);

        boolean isMenuExpanded = mMenuState == MENU_STATE_FULL;
        mPipBoundsHandler.setMinEdgeSize(
                isMenuExpanded  && willResizeMenu() ? mExpandedShortestEdgeSize : 0);
    }

    /**
     * Removes the dismiss target and cancels any pending callbacks to show it.
     */
    private void cleanUpDismissTarget() {
        mHandler.removeCallbacks(mShowDismissAffordance);
        mDismissViewController.destroyDismissTarget();
    }

    /**
     * @return whether the menu will resize as a part of showing the full menu.
     */
    private boolean willResizeMenu() {
        if (!mEnableResize) {
            return false;
        }
        return mExpandedBounds.width() != mNormalBounds.width()
                || mExpandedBounds.height() != mNormalBounds.height();
    }

    public void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + TAG);
        pw.println(innerPrefix + "mMovementBounds=" + mMovementBounds);
        pw.println(innerPrefix + "mNormalBounds=" + mNormalBounds);
        pw.println(innerPrefix + "mNormalMovementBounds=" + mNormalMovementBounds);
        pw.println(innerPrefix + "mExpandedBounds=" + mExpandedBounds);
        pw.println(innerPrefix + "mExpandedMovementBounds=" + mExpandedMovementBounds);
        pw.println(innerPrefix + "mMenuState=" + mMenuState);
        pw.println(innerPrefix + "mIsImeShowing=" + mIsImeShowing);
        pw.println(innerPrefix + "mImeHeight=" + mImeHeight);
        pw.println(innerPrefix + "mIsShelfShowing=" + mIsShelfShowing);
        pw.println(innerPrefix + "mShelfHeight=" + mShelfHeight);
        pw.println(innerPrefix + "mSavedSnapFraction=" + mSavedSnapFraction);
        pw.println(innerPrefix + "mEnableDragToEdgeDismiss=" + mEnableDismissDragToEdge);
        mSnapAlgorithm.dump(pw, innerPrefix);
        mTouchState.dump(pw, innerPrefix);
        mMotionHelper.dump(pw, innerPrefix);
    }

}
