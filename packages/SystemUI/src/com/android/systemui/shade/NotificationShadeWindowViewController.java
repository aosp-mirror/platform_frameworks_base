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

package com.android.systemui.shade;

import android.app.StatusBarManager;
import android.media.AudioManager;
import android.media.session.MediaSessionLegacyHelper;
import android.os.SystemClock;
import android.util.Log;
import android.view.GestureDetector;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.annotations.VisibleForTesting;
import com.android.keyguard.AuthKeyguardMessageArea;
import com.android.keyguard.LockIconViewController;
import com.android.keyguard.dagger.KeyguardBouncerComponent;
import com.android.systemui.R;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.dock.DockManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.keyguard.ui.binder.KeyguardBouncerViewBinder;
import com.android.systemui.keyguard.ui.viewmodel.KeyguardBouncerViewModel;
import com.android.systemui.statusbar.DragDownHelper;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.stack.AmbientState;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.PhoneStatusBarViewController;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.phone.dagger.CentralSurfacesComponent;
import com.android.systemui.statusbar.phone.panelstate.PanelExpansionStateManager;
import com.android.systemui.statusbar.window.StatusBarWindowStateController;

import java.io.PrintWriter;

import javax.inject.Inject;

/**
 * Controller for {@link NotificationShadeWindowView}.
 */
@CentralSurfacesComponent.CentralSurfacesScope
public class NotificationShadeWindowViewController {
    private static final String TAG = "NotifShadeWindowVC";
    private final FalsingCollector mFalsingCollector;
    private final SysuiStatusBarStateController mStatusBarStateController;
    private final NotificationShadeWindowView mView;
    private final NotificationShadeDepthController mDepthController;
    private final NotificationStackScrollLayoutController mNotificationStackScrollLayoutController;
    private final LockscreenShadeTransitionController mLockscreenShadeTransitionController;
    private final LockIconViewController mLockIconViewController;
    private final StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private final StatusBarWindowStateController mStatusBarWindowStateController;
    private final KeyguardUnlockAnimationController mKeyguardUnlockAnimationController;
    private final AmbientState mAmbientState;
    private final PulsingGestureListener mPulsingGestureListener;

    private GestureDetector mPulsingWakeupGestureHandler;
    private View mBrightnessMirror;
    private boolean mTouchActive;
    private boolean mTouchCancelled;
    private boolean mExpandAnimationRunning;
    private NotificationStackScrollLayout mStackScrollLayout;
    private PhoneStatusBarViewController mStatusBarViewController;
    private final CentralSurfaces mService;
    private final NotificationShadeWindowController mNotificationShadeWindowController;
    private DragDownHelper mDragDownHelper;
    private boolean mExpandingBelowNotch;
    private final DockManager mDockManager;
    private final NotificationPanelViewController mNotificationPanelViewController;
    private final PanelExpansionStateManager mPanelExpansionStateManager;

    private boolean mIsTrackingBarGesture = false;

    @Inject
    public NotificationShadeWindowViewController(
            LockscreenShadeTransitionController transitionController,
            FalsingCollector falsingCollector,
            SysuiStatusBarStateController statusBarStateController,
            DockManager dockManager,
            NotificationShadeDepthController depthController,
            NotificationShadeWindowView notificationShadeWindowView,
            NotificationPanelViewController notificationPanelViewController,
            PanelExpansionStateManager panelExpansionStateManager,
            NotificationStackScrollLayoutController notificationStackScrollLayoutController,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            StatusBarWindowStateController statusBarWindowStateController,
            LockIconViewController lockIconViewController,
            CentralSurfaces centralSurfaces,
            NotificationShadeWindowController controller,
            KeyguardUnlockAnimationController keyguardUnlockAnimationController,
            AmbientState ambientState,
            PulsingGestureListener pulsingGestureListener,
            FeatureFlags featureFlags,
            KeyguardBouncerViewModel keyguardBouncerViewModel,
            KeyguardBouncerComponent.Factory keyguardBouncerComponentFactory
    ) {
        mLockscreenShadeTransitionController = transitionController;
        mFalsingCollector = falsingCollector;
        mStatusBarStateController = statusBarStateController;
        mView = notificationShadeWindowView;
        mDockManager = dockManager;
        mNotificationPanelViewController = notificationPanelViewController;
        mPanelExpansionStateManager = panelExpansionStateManager;
        mDepthController = depthController;
        mNotificationStackScrollLayoutController = notificationStackScrollLayoutController;
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        mStatusBarWindowStateController = statusBarWindowStateController;
        mLockIconViewController = lockIconViewController;
        mService = centralSurfaces;
        mNotificationShadeWindowController = controller;
        mKeyguardUnlockAnimationController = keyguardUnlockAnimationController;
        mAmbientState = ambientState;
        mPulsingGestureListener = pulsingGestureListener;

        // This view is not part of the newly inflated expanded status bar.
        mBrightnessMirror = mView.findViewById(R.id.brightness_mirror_container);
        if (featureFlags.isEnabled(Flags.MODERN_BOUNCER)) {
            KeyguardBouncerViewBinder.bind(
                    mView.findViewById(R.id.keyguard_bouncer_container),
                    keyguardBouncerViewModel,
                    keyguardBouncerComponentFactory);
        }
    }

    /**
     * @return Location where to place the KeyguardBouncer
     */
    public ViewGroup getBouncerContainer() {
        return mView.findViewById(R.id.keyguard_bouncer_container);
    }

    /**
     * @return Location where to place the KeyguardMessageArea
     */
    public AuthKeyguardMessageArea getKeyguardMessageArea() {
        return mView.findViewById(R.id.keyguard_message_area);
    }

    /** Inflates the {@link R.layout#status_bar_expanded} layout and sets it up. */
    public void setupExpandedStatusBar() {
        mStackScrollLayout = mView.findViewById(R.id.notification_stack_scroller);
        mPulsingWakeupGestureHandler = new GestureDetector(mView.getContext(),
                mPulsingGestureListener);

        mView.setInteractionEventHandler(new NotificationShadeWindowView.InteractionEventHandler() {
            @Override
            public Boolean handleDispatchTouchEvent(MotionEvent ev) {
                if (mStatusBarViewController == null) { // Fix for b/192490822
                    Log.w(TAG, "Ignoring touch while statusBarView not yet set.");
                    return false;
                }
                boolean isDown = ev.getActionMasked() == MotionEvent.ACTION_DOWN;
                boolean isUp = ev.getActionMasked() == MotionEvent.ACTION_UP;
                boolean isCancel = ev.getActionMasked() == MotionEvent.ACTION_CANCEL;

                boolean expandingBelowNotch = mExpandingBelowNotch;
                if (isUp || isCancel) {
                    mExpandingBelowNotch = false;
                }

                // Reset manual touch dispatch state here but make sure the UP/CANCEL event still
                // gets
                // delivered.
                if (!isCancel && mService.shouldIgnoreTouch()) {
                    return false;
                }

                if (isDown) {
                    mTouchActive = true;
                    mTouchCancelled = false;
                } else if (ev.getActionMasked() == MotionEvent.ACTION_UP
                        || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    mTouchActive = false;
                }
                if (mTouchCancelled || mExpandAnimationRunning) {
                    return false;
                }

                if (mKeyguardUnlockAnimationController.isPlayingCannedUnlockAnimation()) {
                    // If the user was sliding their finger across the lock screen,
                    // we may have been intercepting the touch and forwarding it to the
                    // UDFPS affordance via mStatusBarKeyguardViewManager.onTouch (see below).
                    // If this touch ended up unlocking the device, we want to cancel the touch
                    // immediately, so we don't cause swipe or expand animations afterwards.
                    cancelCurrentTouch();
                    return true;
                }

                mFalsingCollector.onTouchEvent(ev);
                mPulsingWakeupGestureHandler.onTouchEvent(ev);
                mStatusBarKeyguardViewManager.onTouch(ev);
                if (mBrightnessMirror != null
                        && mBrightnessMirror.getVisibility() == View.VISIBLE) {
                    // Disallow new pointers while the brightness mirror is visible. This is so that
                    // you can't touch anything other than the brightness slider while the mirror is
                    // showing and the rest of the panel is transparent.
                    if (ev.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
                        return false;
                    }
                }
                if (isDown) {
                    mNotificationStackScrollLayoutController.closeControlsIfOutsideTouch(ev);
                }

                if (mStatusBarStateController.isDozing()) {
                    mService.extendDozePulse();
                }
                mLockIconViewController.onTouchEvent(
                        ev,
                        () -> mService.wakeUpIfDozing(
                                SystemClock.uptimeMillis(),
                                mView,
                                "LOCK_ICON_TOUCH"));

                // In case we start outside of the view bounds (below the status bar), we need to
                // dispatch
                // the touch manually as the view system can't accommodate for touches outside of
                // the
                // regular view bounds.
                if (isDown && ev.getY() >= mView.getBottom()) {
                    mExpandingBelowNotch = true;
                    expandingBelowNotch = true;
                }
                if (expandingBelowNotch) {
                    return mStatusBarViewController.sendTouchToView(ev);
                }

                if (!mIsTrackingBarGesture && isDown
                        && mNotificationPanelViewController.isFullyCollapsed()) {
                    float x = ev.getRawX();
                    float y = ev.getRawY();
                    if (mStatusBarViewController.touchIsWithinView(x, y)) {
                        if (mStatusBarWindowStateController.windowIsShowing()) {
                            mIsTrackingBarGesture = true;
                            return mStatusBarViewController.sendTouchToView(ev);
                        } else { // it's hidden or hiding, don't send to notification shade.
                            return true;
                        }
                    }
                } else if (mIsTrackingBarGesture) {
                    final boolean sendToStatusBar = mStatusBarViewController.sendTouchToView(ev);
                    if (isUp || isCancel) {
                        mIsTrackingBarGesture = false;
                    }
                    return sendToStatusBar;
                }

                return null;
            }

            @Override
            public void dispatchTouchEventComplete() {
                mFalsingCollector.onMotionEventComplete();
            }

            @Override
            public boolean shouldInterceptTouchEvent(MotionEvent ev) {
                if (mStatusBarStateController.isDozing() && !mService.isPulsing()
                        && !mDockManager.isDocked()) {
                    // Capture all touch events in always-on.
                    return true;
                }

                if (mStatusBarKeyguardViewManager.isShowingAlternateAuthOrAnimating()) {
                    // capture all touches if the alt auth bouncer is showing
                    return true;
                }

                if (mLockIconViewController.onInterceptTouchEvent(ev)) {
                    // immediately return true; don't send the touch to the drag down helper
                    return true;
                }

                boolean intercept = false;
                if (mNotificationPanelViewController.isFullyExpanded()
                        && mDragDownHelper.isDragDownEnabled()
                        && !mService.isBouncerShowing()
                        && !mStatusBarStateController.isDozing()) {
                    intercept = mDragDownHelper.onInterceptTouchEvent(ev);
                }

                return intercept;

            }

            @Override
            public void didIntercept(MotionEvent ev) {
                MotionEvent cancellation = MotionEvent.obtain(ev);
                cancellation.setAction(MotionEvent.ACTION_CANCEL);
                mStackScrollLayout.onInterceptTouchEvent(cancellation);
                mNotificationPanelViewController.getView().onInterceptTouchEvent(cancellation);
                cancellation.recycle();
            }

            @Override
            public boolean handleTouchEvent(MotionEvent ev) {
                boolean handled = false;
                if (mStatusBarStateController.isDozing()) {
                    handled = !mService.isPulsing();
                }

                if (mStatusBarKeyguardViewManager.isShowingAlternateAuthOrAnimating()) {
                    // eat the touch
                    handled = true;
                }

                if ((mDragDownHelper.isDragDownEnabled() && !handled)
                        || mDragDownHelper.isDraggingDown()) {
                    // we still want to finish our drag down gesture when locking the screen
                    handled = mDragDownHelper.onTouchEvent(ev);
                }

                return handled;
            }

            @Override
            public void didNotHandleTouchEvent(MotionEvent ev) {
                final int action = ev.getActionMasked();
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    mService.setInteracting(StatusBarManager.WINDOW_STATUS_BAR, false);
                }
            }

            @Override
            public boolean interceptMediaKey(KeyEvent event) {
                return mService.interceptMediaKey(event);
            }

            @Override
            public boolean dispatchKeyEventPreIme(KeyEvent event) {
                return mService.dispatchKeyEventPreIme(event);
            }

            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
                switch (event.getKeyCode()) {
                    case KeyEvent.KEYCODE_BACK:
                        if (!down) {
                            mService.onBackPressed();
                        }
                        return true;
                    case KeyEvent.KEYCODE_MENU:
                        if (!down) {
                            return mService.onMenuPressed();
                        }
                        break;
                    case KeyEvent.KEYCODE_SPACE:
                        if (!down) {
                            return mService.onSpacePressed();
                        }
                        break;
                    case KeyEvent.KEYCODE_VOLUME_DOWN:
                    case KeyEvent.KEYCODE_VOLUME_UP:
                        if (mStatusBarStateController.isDozing()) {
                            MediaSessionLegacyHelper.getHelper(mView.getContext())
                                    .sendVolumeKeyEvent(
                                            event, AudioManager.USE_DEFAULT_STREAM_TYPE, true);
                            return true;
                        }
                        break;
                }
                return false;
            }
        });

        mView.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) {
                if (child.getId() == R.id.brightness_mirror_container) {
                    mBrightnessMirror = child;
                }
            }

            @Override
            public void onChildViewRemoved(View parent, View child) {
            }
        });

        setDragDownHelper(mLockscreenShadeTransitionController.getTouchHelper());

        mDepthController.setRoot(mView);
        mPanelExpansionStateManager.addExpansionListener(mDepthController);
    }

    public NotificationShadeWindowView getView() {
        return mView;
    }

    public void cancelCurrentTouch() {
        if (mTouchActive) {
            final long now = SystemClock.uptimeMillis();
            MotionEvent event = MotionEvent.obtain(now, now,
                    MotionEvent.ACTION_CANCEL, 0.0f, 0.0f, 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            mView.dispatchTouchEvent(event);
            event.recycle();
            mTouchCancelled = true;
        }
        mAmbientState.setSwipingUp(false);
    }

    public void dump(PrintWriter pw, String[] args) {
        pw.print("  mExpandAnimationRunning=");
        pw.println(mExpandAnimationRunning);
        pw.print("  mTouchCancelled=");
        pw.println(mTouchCancelled);
        pw.print("  mTouchActive=");
        pw.println(mTouchActive);
    }

    public void setExpandAnimationRunning(boolean running) {
        if (mExpandAnimationRunning != running) {
            mExpandAnimationRunning = running;
            mNotificationShadeWindowController.setLaunchingActivity(mExpandAnimationRunning);
        }
    }

    public void cancelExpandHelper() {
        if (mStackScrollLayout != null) {
            mStackScrollLayout.cancelExpandHelper();
        }
    }

    public void setStatusBarViewController(PhoneStatusBarViewController statusBarViewController) {
        mStatusBarViewController = statusBarViewController;
    }

    @VisibleForTesting
    void setDragDownHelper(DragDownHelper dragDownHelper) {
        mDragDownHelper = dragDownHelper;
    }
}
