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

package com.android.systemui.statusbar.phone;

import static android.app.StatusBarManager.WINDOW_STATE_SHOWING;

import android.app.StatusBarManager;
import android.graphics.RectF;
import android.hardware.display.AmbientDisplayConfiguration;
import android.media.AudioManager;
import android.media.session.MediaSessionLegacyHelper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.GestureDetector;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.dock.DockManager;
import com.android.systemui.doze.DozeLog;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.DragDownHelper;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.PulseExpansionHandler;
import com.android.systemui.statusbar.SuperStatusBarViewFactory;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.InjectionInflationController;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import javax.inject.Inject;

/**
 * Controller for {@link NotificationShadeWindowView}.
 */
public class NotificationShadeWindowViewController {
    private final InjectionInflationController mInjectionInflationController;
    private final NotificationWakeUpCoordinator mCoordinator;
    private final PulseExpansionHandler mPulseExpansionHandler;
    private final DynamicPrivacyController mDynamicPrivacyController;
    private final KeyguardBypassController mBypassController;
    private final PluginManager mPluginManager;
    private final FalsingCollector mFalsingCollector;
    private final TunerService mTunerService;
    private final NotificationLockscreenUserManager mNotificationLockscreenUserManager;
    private final NotificationEntryManager mNotificationEntryManager;
    private final KeyguardStateController mKeyguardStateController;
    private final SysuiStatusBarStateController mStatusBarStateController;
    private final DozeLog mDozeLog;
    private final DozeParameters mDozeParameters;
    private final CommandQueue mCommandQueue;
    private final NotificationShadeWindowView mView;
    private final ShadeController mShadeController;
    private final NotificationShadeDepthController mDepthController;
    private final NotificationStackScrollLayoutController mNotificationStackScrollLayoutController;
    private final LockscreenShadeTransitionController mLockscreenShadeTransitionController;
    private final StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;

    private GestureDetector mGestureDetector;
    private View mBrightnessMirror;
    private boolean mTouchActive;
    private boolean mTouchCancelled;
    private boolean mExpandAnimationRunning;
    private NotificationStackScrollLayout mStackScrollLayout;
    private PhoneStatusBarView mStatusBarView;
    private PhoneStatusBarTransitions mBarTransitions;
    private StatusBar mService;
    private NotificationShadeWindowController mNotificationShadeWindowController;
    private DragDownHelper mDragDownHelper;
    private boolean mDoubleTapEnabled;
    private boolean mSingleTapEnabled;
    private boolean mExpandingBelowNotch;
    private final DockManager mDockManager;
    private final NotificationPanelViewController mNotificationPanelViewController;
    private final SuperStatusBarViewFactory mStatusBarViewFactory;

    // Used for determining view / touch intersection
    private int[] mTempLocation = new int[2];
    private RectF mTempRect = new RectF();
    private boolean mIsTrackingBarGesture = false;

    @Inject
    public NotificationShadeWindowViewController(
            InjectionInflationController injectionInflationController,
            NotificationWakeUpCoordinator coordinator,
            PulseExpansionHandler pulseExpansionHandler,
            DynamicPrivacyController dynamicPrivacyController,
            KeyguardBypassController bypassController,
            LockscreenShadeTransitionController transitionController,
            FalsingCollector falsingCollector,
            PluginManager pluginManager,
            TunerService tunerService,
            NotificationLockscreenUserManager notificationLockscreenUserManager,
            NotificationEntryManager notificationEntryManager,
            KeyguardStateController keyguardStateController,
            SysuiStatusBarStateController statusBarStateController,
            DozeLog dozeLog,
            DozeParameters dozeParameters,
            CommandQueue commandQueue,
            ShadeController shadeController,
            DockManager dockManager,
            NotificationShadeDepthController depthController,
            NotificationShadeWindowView notificationShadeWindowView,
            NotificationPanelViewController notificationPanelViewController,
            SuperStatusBarViewFactory statusBarViewFactory,
            NotificationStackScrollLayoutController notificationStackScrollLayoutController,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager) {
        mInjectionInflationController = injectionInflationController;
        mCoordinator = coordinator;
        mPulseExpansionHandler = pulseExpansionHandler;
        mDynamicPrivacyController = dynamicPrivacyController;
        mBypassController = bypassController;
        mLockscreenShadeTransitionController = transitionController;
        mFalsingCollector = falsingCollector;
        mPluginManager = pluginManager;
        mTunerService = tunerService;
        mNotificationLockscreenUserManager = notificationLockscreenUserManager;
        mNotificationEntryManager = notificationEntryManager;
        mKeyguardStateController = keyguardStateController;
        mStatusBarStateController = statusBarStateController;
        mDozeLog = dozeLog;
        mDozeParameters = dozeParameters;
        mCommandQueue = commandQueue;
        mView = notificationShadeWindowView;
        mShadeController = shadeController;
        mDockManager = dockManager;
        mNotificationPanelViewController = notificationPanelViewController;
        mDepthController = depthController;
        mStatusBarViewFactory = statusBarViewFactory;
        mNotificationStackScrollLayoutController = notificationStackScrollLayoutController;
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;

        // This view is not part of the newly inflated expanded status bar.
        mBrightnessMirror = mView.findViewById(R.id.brightness_mirror_container);
    }

    /** Inflates the {@link R.layout#status_bar_expanded} layout and sets it up. */
    public void setupExpandedStatusBar() {
        mStackScrollLayout = mView.findViewById(R.id.notification_stack_scroller);

        TunerService.Tunable tunable = (key, newValue) -> {
            AmbientDisplayConfiguration configuration =
                    new AmbientDisplayConfiguration(mView.getContext());
            switch (key) {
                case Settings.Secure.DOZE_DOUBLE_TAP_GESTURE:
                    mDoubleTapEnabled = configuration.doubleTapGestureEnabled(
                            UserHandle.USER_CURRENT);
                    break;
                case Settings.Secure.DOZE_TAP_SCREEN_GESTURE:
                    mSingleTapEnabled = configuration.tapGestureEnabled(UserHandle.USER_CURRENT);
            }
        };
        mTunerService.addTunable(tunable,
                Settings.Secure.DOZE_DOUBLE_TAP_GESTURE,
                Settings.Secure.DOZE_TAP_SCREEN_GESTURE);

        GestureDetector.SimpleOnGestureListener gestureListener =
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapConfirmed(MotionEvent e) {
                        if (mSingleTapEnabled && !mDockManager.isDocked()) {
                            mService.wakeUpIfDozing(
                                    SystemClock.uptimeMillis(), mView, "SINGLE_TAP");
                            return true;
                        }
                        return false;
                    }

                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        if (mDoubleTapEnabled || mSingleTapEnabled) {
                            mService.wakeUpIfDozing(
                                    SystemClock.uptimeMillis(), mView, "DOUBLE_TAP");
                            return true;
                        }
                        return false;
                    }
                };
        mGestureDetector = new GestureDetector(mView.getContext(), gestureListener);

        mView.setInteractionEventHandler(new NotificationShadeWindowView.InteractionEventHandler() {
            @Override
            public Boolean handleDispatchTouchEvent(MotionEvent ev) {
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
                    setTouchActive(true);
                    mTouchCancelled = false;
                } else if (ev.getActionMasked() == MotionEvent.ACTION_UP
                        || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    setTouchActive(false);
                }
                if (mTouchCancelled || mExpandAnimationRunning) {
                    return false;
                }
                mFalsingCollector.onTouchEvent(ev);
                mGestureDetector.onTouchEvent(ev);
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
                    mService.mDozeScrimController.extendPulse();
                }
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
                    return mStatusBarView.dispatchTouchEvent(ev);
                }

                if (!mIsTrackingBarGesture && isDown
                        && mNotificationPanelViewController.isFullyCollapsed()) {
                    float x = ev.getRawX();
                    float y = ev.getRawY();
                    if (isIntersecting(mStatusBarView, x, y)) {
                        if (mService.isSameStatusBarState(WINDOW_STATE_SHOWING)) {
                            mIsTrackingBarGesture = true;
                            return mStatusBarView.dispatchTouchEvent(ev);
                        } else { // it's hidden or hiding, don't send to notification shade.
                            return true;
                        }
                    }
                } else if (mIsTrackingBarGesture) {
                    final boolean sendToNotification = mStatusBarView.dispatchTouchEvent(ev);
                    if (isUp || isCancel) {
                        mIsTrackingBarGesture = false;
                    }
                    return sendToNotification;
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
        mNotificationPanelViewController.addExpansionListener(mDepthController);
    }

    public NotificationShadeWindowView getView() {
        return mView;
    }

    public void setTouchActive(boolean touchActive) {
        mTouchActive = touchActive;
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
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
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

    public PhoneStatusBarTransitions getBarTransitions() {
        return mBarTransitions;
    }

    public void setStatusBarView(PhoneStatusBarView statusBarView) {
        mStatusBarView = statusBarView;
        if (statusBarView != null && mStatusBarViewFactory != null) {
            mBarTransitions = new PhoneStatusBarTransitions(
                    statusBarView,
                    mStatusBarViewFactory.getStatusBarWindowView()
                            .findViewById(R.id.status_bar_container));
        }
    }

    public void setService(StatusBar statusBar, NotificationShadeWindowController controller) {
        mService = statusBar;
        mNotificationShadeWindowController = controller;
    }

    @VisibleForTesting
    void setDragDownHelper(DragDownHelper dragDownHelper) {
        mDragDownHelper = dragDownHelper;
    }

    private boolean isIntersecting(View view, float x, float y) {
        mTempLocation = view.getLocationOnScreen();
        mTempRect.set(mTempLocation[0], mTempLocation[1], mTempLocation[0] + view.getWidth(),
                mTempLocation[1] + view.getHeight());
        return mTempRect.contains(x, y);
    }
}
