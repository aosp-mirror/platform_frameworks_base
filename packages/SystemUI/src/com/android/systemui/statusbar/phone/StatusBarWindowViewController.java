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

import android.app.StatusBarManager;
import android.hardware.display.AmbientDisplayConfiguration;
import android.media.AudioManager;
import android.media.session.MediaSessionLegacyHelper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.GestureDetector;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.ExpandHelper;
import com.android.systemui.R;
import com.android.systemui.doze.DozeLog;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.statusbar.DragDownHelper;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.PulseExpansionHandler;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.InjectionInflationController;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import javax.inject.Inject;

/**
 * Controller for {@link StatusBarWindowView}.
 */
public class StatusBarWindowViewController {
    private final StatusBarWindowView mView;
    private final FalsingManager mFalsingManager;
    private final GestureDetector mGestureDetector;
    private View mBrightnessMirror;
    private boolean mTouchActive;
    private boolean mTouchCancelled;
    private boolean mExpandAnimationPending;
    private boolean mExpandAnimationRunning;
    private NotificationStackScrollLayout mStackScrollLayout;
    private LockIcon mLockIcon;
    private PhoneStatusBarView mStatusBarView;
    private StatusBar mService;
    private DragDownHelper mDragDownHelper;
    private boolean mSuppressingWakeUpGesture;
    private boolean mDoubleTapEnabled;
    private boolean mSingleTapEnabled;
    private boolean mExpandingBelowNotch;

    private StatusBarWindowViewController(
            StatusBarWindowView view,
            InjectionInflationController injectionInflationController,
            NotificationWakeUpCoordinator coordinator,
            PulseExpansionHandler pulseExpansionHandler,
            DynamicPrivacyController dynamicPrivacyController,
            KeyguardBypassController bypassController,
            FalsingManager falsingManager,
            PluginManager pluginManager,
            TunerService tunerService,
            ShadeController shadeController,
            NotificationLockscreenUserManager notificationLockscreenUserManager,
            NotificationEntryManager notificationEntryManager,
            DozeLog dozeLog) {
        mView = view;
        mFalsingManager = falsingManager;

        // TODO: create controller for NotificationPanelView
        NotificationPanelView notificationPanelView = new NotificationPanelView(
                view.getContext(),
                null,
                injectionInflationController,
                coordinator,
                pulseExpansionHandler,
                dynamicPrivacyController,
                bypassController,
                falsingManager,
                pluginManager,
                shadeController,
                notificationLockscreenUserManager,
                notificationEntryManager,
                dozeLog);
        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        notificationPanelView.setVisibility(View.INVISIBLE);
        notificationPanelView.setId(R.id.notification_panel);
        LayoutInflater li = injectionInflationController.injectable(
                LayoutInflater.from(mView.getContext()));

        li.inflate(R.layout.status_bar_expanded, notificationPanelView);
        notificationPanelView.onChildrenAttached();

        ViewStub statusBarExpanded = view.findViewById(R.id.status_bar_expanded);
        mView.addView(notificationPanelView, mView.indexOfChild(statusBarExpanded), lp);
        mView.removeView(statusBarExpanded);

        mStackScrollLayout = mView.findViewById(R.id.notification_stack_scroller);
        mLockIcon = mView.findViewById(R.id.lock_icon);
        mBrightnessMirror = mView.findViewById(R.id.brightness_mirror);

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
        tunerService.addTunable(tunable,
                Settings.Secure.DOZE_DOUBLE_TAP_GESTURE,
                Settings.Secure.DOZE_TAP_SCREEN_GESTURE);

        GestureDetector.SimpleOnGestureListener gestureListener =
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapConfirmed(MotionEvent e) {
                        if (mSingleTapEnabled && !mSuppressingWakeUpGesture) {
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

        mView.setInteractionEventHandler(new StatusBarWindowView.InteractionEventHandler() {
            @Override
            public Boolean handleDispatchTouchEvent(MotionEvent ev) {
                boolean isDown = ev.getActionMasked() == MotionEvent.ACTION_DOWN;
                boolean isUp = ev.getActionMasked() == MotionEvent.ACTION_UP;
                boolean isCancel = ev.getActionMasked() == MotionEvent.ACTION_CANCEL;

                if (isUp || isCancel) {
                    mExpandingBelowNotch = false;
                }

                // Reset manual touch dispatch state here but make sure the UP/CANCEL event still
                // gets
                // delivered.

                if (!isCancel && mService.shouldIgnoreTouch()) {
                    return false;
                }
                if (isDown && notificationPanelView.isFullyCollapsed()) {
                    notificationPanelView.startExpandLatencyTracking();
                }
                if (isDown) {
                    setTouchActive(true);
                    mTouchCancelled = false;
                } else if (ev.getActionMasked() == MotionEvent.ACTION_UP
                        || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    setTouchActive(false);
                }
                if (mTouchCancelled || mExpandAnimationRunning || mExpandAnimationPending) {
                    return false;
                }
                mFalsingManager.onTouchEvent(ev, mView.getWidth(), mView.getHeight());
                mGestureDetector.onTouchEvent(ev);
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
                    getStackScrollLayout().closeControlsIfOutsideTouch(ev);
                }
                if (mService.isDozing()) {
                    mService.mDozeScrimController.extendPulse();
                }
                // In case we start outside of the view bounds (below the status bar), we need to
                // dispatch
                // the touch manually as the view system can't accommodate for touches outside of
                // the
                // regular view bounds.
                if (isDown && ev.getY() >= mView.getBottom()) {
                    mExpandingBelowNotch = true;
                }
                if (mExpandingBelowNotch) {
                    return mStatusBarView.dispatchTouchEvent(ev);
                }

                return null;
            }

            @Override
            public boolean shouldInterceptTouchEvent(MotionEvent ev) {
                if (mService.isDozing() && !mService.isPulsing()) {
                    // Capture all touch events in always-on.
                    return true;
                }
                boolean intercept = false;
                if (notificationPanelView.isFullyExpanded()
                        && mDragDownHelper.isDragDownEnabled()
                        && !mService.isBouncerShowing()
                        && !mService.isDozing()) {
                    intercept = mDragDownHelper.onInterceptTouchEvent(ev);
                }

                return intercept;

            }

            @Override
            public void didIntercept(MotionEvent ev) {
                NotificationStackScrollLayout stackScrollLayout = getStackScrollLayout();
                MotionEvent cancellation = MotionEvent.obtain(ev);
                cancellation.setAction(MotionEvent.ACTION_CANCEL);
                stackScrollLayout.onInterceptTouchEvent(cancellation);
                notificationPanelView.onInterceptTouchEvent(cancellation);
                cancellation.recycle();
            }

            @Override
            public boolean handleTouchEvent(MotionEvent ev) {
                boolean handled = false;
                if (mService.isDozing()) {
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
                        if (mService.isDozing()) {
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
                if (child.getId() == R.id.brightness_mirror) {
                    mBrightnessMirror = child;
                }
            }

            @Override
            public void onChildViewRemoved(View parent, View child) {
            }
        });
    }

    public StatusBarWindowView getView() {
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
        pw.print("  mExpandAnimationPending=");
        pw.println(mExpandAnimationPending);
        pw.print("  mExpandAnimationRunning=");
        pw.println(mExpandAnimationRunning);
        pw.print("  mTouchCancelled=");
        pw.println(mTouchCancelled);
        pw.print("  mTouchActive=");
        pw.println(mTouchActive);
    }

    public void setExpandAnimationPending(boolean pending) {
        mExpandAnimationPending = pending;
    }

    public void setExpandAnimationRunning(boolean running) {
        mExpandAnimationRunning = running;
    }

    public void cancelExpandHelper() {
        NotificationStackScrollLayout stackScrollLayout = getStackScrollLayout();
        if (stackScrollLayout != null) {
            stackScrollLayout.cancelExpandHelper();
        }
    }

    @VisibleForTesting
    protected NotificationStackScrollLayout getStackScrollLayout() {
        return mStackScrollLayout;
    }

    /**
     * Called whenever the scrims become opaque, transparent or semi-transparent.
     */
    public void onScrimVisibilityChanged(Integer scrimsVisible) {
        if (mLockIcon != null) {
            mLockIcon.onScrimVisibilityChanged(scrimsVisible);
        }
    }

    /**
     * Propagate {@link StatusBar} pulsing state.
     */
    public void setPulsing(boolean pulsing) {
        if (mLockIcon != null) {
            mLockIcon.setPulsing(pulsing);
        }
    }

    /**
     * Called when the biometric authentication mode changes.
     *
     * @param wakeAndUnlock If the type is {@link BiometricUnlockController#isWakeAndUnlock()}
     * @param isUnlock      If the type is {@link BiometricUnlockController#isBiometricUnlock()} ()
     */
    public void onBiometricAuthModeChanged(boolean wakeAndUnlock, boolean isUnlock) {
        if (mLockIcon != null) {
            mLockIcon.onBiometricAuthModeChanged(wakeAndUnlock, isUnlock);
        }
    }

    public void setStatusBarView(PhoneStatusBarView statusBarView) {
        mStatusBarView = statusBarView;
    }

    public void setService(StatusBar statusBar) {
        mService = statusBar;
        NotificationStackScrollLayout stackScrollLayout = getStackScrollLayout();
        ExpandHelper.Callback expandHelperCallback = stackScrollLayout.getExpandHelperCallback();
        DragDownHelper.DragDownCallback dragDownCallback = stackScrollLayout.getDragDownCallback();
        setDragDownHelper(
                new DragDownHelper(
                        mView.getContext(), mView, expandHelperCallback,
                        dragDownCallback, mFalsingManager));
    }

    @VisibleForTesting
    void setDragDownHelper(DragDownHelper dragDownHelper) {
        mDragDownHelper = dragDownHelper;
    }

    public void suppressWakeUpGesture(boolean suppress) {
        mSuppressingWakeUpGesture = suppress;
    }

    /**
     * When we're launching an affordance, like double pressing power to open camera.
     */
    public void onShowingLaunchAffordanceChanged(Boolean showing) {
        if (mLockIcon != null) {
            mLockIcon.onShowingLaunchAffordanceChanged(showing);
        }
    }

    public void setBouncerShowingScrimmed(boolean bouncerShowing) {
        if (mLockIcon != null) {
            mLockIcon.setBouncerShowingScrimmed(bouncerShowing);
        }
    }

    /**
     * When {@link KeyguardBouncer} starts to be dismissed and starts to play its animation.
     */
    public void onBouncerPreHideAnimation() {
        if (mLockIcon != null) {
            mLockIcon.onBouncerPreHideAnimation();
        }
    }

    /**
     * Builder for {@link StatusBarWindowViewController}.
     */
    public static class Builder {
        private final InjectionInflationController mInjectionInflationController;
        private final NotificationWakeUpCoordinator mCoordinator;
        private final PulseExpansionHandler mPulseExpansionHandler;
        private final DynamicPrivacyController mDynamicPrivacyController;
        private final KeyguardBypassController mBypassController;
        private final FalsingManager mFalsingManager;
        private final PluginManager mPluginManager;
        private final TunerService mTunerService;
        private ShadeController mShadeController;
        private final NotificationLockscreenUserManager mNotificationLockScreenUserManager;
        private final NotificationEntryManager mNotificationEntryManager;
        private final DozeLog mDozeLog;
        private StatusBarWindowView mView;

        @Inject
        public Builder(
                InjectionInflationController injectionInflationController,
                NotificationWakeUpCoordinator coordinator,
                PulseExpansionHandler pulseExpansionHandler,
                DynamicPrivacyController dynamicPrivacyController,
                KeyguardBypassController bypassController,
                FalsingManager falsingManager,
                PluginManager pluginManager,
                TunerService tunerService,
                NotificationLockscreenUserManager notificationLockscreenUserManager,
                NotificationEntryManager notificationEntryManager,
                DozeLog dozeLog) {
            mInjectionInflationController = injectionInflationController;
            mCoordinator = coordinator;
            mPulseExpansionHandler = pulseExpansionHandler;
            mDynamicPrivacyController = dynamicPrivacyController;
            mBypassController = bypassController;
            mFalsingManager = falsingManager;
            mPluginManager = pluginManager;
            mTunerService = tunerService;
            mNotificationLockScreenUserManager = notificationLockscreenUserManager;
            mNotificationEntryManager = notificationEntryManager;
            mDozeLog = dozeLog;
        }

        /**
         * Provide {@link StatusBarWindowView} to attach this controller to.
         */
        public Builder setStatusBarWindowView(StatusBarWindowView view) {
            mView = view;
            return this;
        }

        /**
         * Provide {@link ShadeController} that this view needs.
         */
        public Builder setShadeController(ShadeController shadeController) {
            mShadeController = shadeController;
            return this;
        }

        /**
         * Build a {@link StatusBarWindowView}.
         */
        public StatusBarWindowViewController build() {
            return new StatusBarWindowViewController(
                    mView,
                    mInjectionInflationController,
                    mCoordinator,
                    mPulseExpansionHandler,
                    mDynamicPrivacyController,
                    mBypassController,
                    mFalsingManager,
                    mPluginManager,
                    mTunerService,
                    mShadeController,
                    mNotificationLockScreenUserManager,
                    mNotificationEntryManager,
                    mDozeLog);
        }
    }
}
