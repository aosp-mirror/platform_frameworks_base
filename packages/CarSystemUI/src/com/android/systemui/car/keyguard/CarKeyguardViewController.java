/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.car.keyguard;

import android.car.Car;
import android.car.user.CarUserManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRootImpl;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import androidx.annotation.VisibleForTesting;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardViewController;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.R;
import com.android.systemui.SystemUIFactory;
import com.android.systemui.car.CarServiceProvider;
import com.android.systemui.car.navigationbar.CarNavigationBarController;
import com.android.systemui.car.window.OverlayViewController;
import com.android.systemui.car.window.OverlayViewGlobalStateController;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.keyguard.DismissCallbackRegistry;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.statusbar.phone.BiometricUnlockController;
import com.android.systemui.statusbar.phone.KeyguardBouncer;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.NotificationPanelViewController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Lazy;

/**
 * Automotive implementation of the {@link KeyguardViewController}. It controls the Keyguard View
 * that is mounted to the SystemUIOverlayWindow.
 */
@Singleton
public class CarKeyguardViewController extends OverlayViewController implements
        KeyguardViewController {
    private static final String TAG = "CarKeyguardViewController";
    private static final boolean DEBUG = false;

    private final Context mContext;
    private final Handler mHandler;
    private final CarServiceProvider mCarServiceProvider;
    private final KeyguardStateController mKeyguardStateController;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final Lazy<BiometricUnlockController> mBiometricUnlockControllerLazy;
    private final LockPatternUtils mLockPatternUtils;
    private final FalsingManager mFalsingManager;
    private final Lazy<KeyguardBypassController> mKeyguardBypassControllerLazy;
    private final DismissCallbackRegistry mDismissCallbackRegistry;
    private final ViewMediatorCallback mViewMediatorCallback;
    private final CarNavigationBarController mCarNavigationBarController;
    private final InputMethodManager mInputMethodManager;
    // Needed to instantiate mBouncer.
    private final KeyguardBouncer.BouncerExpansionCallback mExpansionCallback =
            new KeyguardBouncer.BouncerExpansionCallback() {
                @Override
                public void onFullyShown() {
                }

                @Override
                public void onStartingToHide() {
                }

                @Override
                public void onStartingToShow() {
                }

                @Override
                public void onFullyHidden() {
                }
            };
    private final CarUserManager.UserLifecycleListener mUserLifecycleListener = (e) -> {
        if (e.getEventType() == CarUserManager.USER_LIFECYCLE_EVENT_TYPE_SWITCHING) {
            UserHandle currentUser = e.getUserHandle();
            revealKeyguardIfBouncerPrepared(currentUser);
        }
    };

    private KeyguardBouncer mBouncer;
    private OnKeyguardCancelClickedListener mKeyguardCancelClickedListener;
    private boolean mShowing;
    private boolean mIsOccluded;

    @Inject
    public CarKeyguardViewController(
            Context context,
            @Main Handler mainHandler,
            CarServiceProvider carServiceProvider,
            OverlayViewGlobalStateController overlayViewGlobalStateController,
            KeyguardStateController keyguardStateController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            Lazy<BiometricUnlockController> biometricUnlockControllerLazy,
            ViewMediatorCallback viewMediatorCallback,
            CarNavigationBarController carNavigationBarController,
            /* The params below are only used to reuse KeyguardBouncer */
            LockPatternUtils lockPatternUtils,
            DismissCallbackRegistry dismissCallbackRegistry,
            FalsingManager falsingManager,
            Lazy<KeyguardBypassController> keyguardBypassControllerLazy) {

        super(R.id.keyguard_stub, overlayViewGlobalStateController);

        mContext = context;
        mHandler = mainHandler;
        mCarServiceProvider = carServiceProvider;
        mKeyguardStateController = keyguardStateController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mBiometricUnlockControllerLazy = biometricUnlockControllerLazy;
        mLockPatternUtils = lockPatternUtils;
        mFalsingManager = falsingManager;
        mKeyguardBypassControllerLazy = keyguardBypassControllerLazy;
        mDismissCallbackRegistry = dismissCallbackRegistry;
        mViewMediatorCallback = viewMediatorCallback;
        mCarNavigationBarController = carNavigationBarController;
        // TODO(b/169280588): Inject InputMethodManager instead.
        mInputMethodManager = mContext.getSystemService(InputMethodManager.class);

        registerUserSwitchedListener();
    }

    @Override
    protected int getFocusAreaViewId() {
        return R.id.keyguard_container;
    }

    @Override
    protected boolean shouldShowNavigationBarInsets() {
        return true;
    }

    @Override
    public void onFinishInflate() {
        mBouncer = SystemUIFactory.getInstance().createKeyguardBouncer(mContext,
                mViewMediatorCallback, mLockPatternUtils,
                getLayout().findViewById(R.id.keyguard_container), mDismissCallbackRegistry,
                mExpansionCallback, mKeyguardStateController, mFalsingManager,
                mKeyguardBypassControllerLazy.get());
        mBiometricUnlockControllerLazy.get().setKeyguardViewController(this);
    }

    @Override
    public void notifyKeyguardAuthenticated(boolean strongAuth) {
        if (mBouncer != null) {
            mBouncer.notifyKeyguardAuthenticated(strongAuth);
        }
    }

    @Override
    public void showBouncer(boolean scrimmed) {
        if (mShowing && !mBouncer.isShowing()) {
            mBouncer.show(/* resetSecuritySelection= */ false);
        }
    }

    @Override
    public void show(Bundle options) {
        if (mShowing) return;

        mShowing = true;
        mKeyguardStateController.notifyKeyguardState(mShowing, /* occluded= */ false);
        mCarNavigationBarController.showAllKeyguardButtons(/* isSetUp= */ true);
        start();
        reset(/* hideBouncerWhenShowing= */ false);
        notifyKeyguardUpdateMonitor();
    }

    @Override
    public void hide(long startTime, long fadeoutDuration) {
        if (!mShowing) return;

        mViewMediatorCallback.readyForKeyguardDone();
        mShowing = false;
        mKeyguardStateController.notifyKeyguardState(mShowing, /* occluded= */ false);
        mBouncer.hide(/* destroyView= */ true);
        mCarNavigationBarController.hideAllKeyguardButtons(/* isSetUp= */ true);
        stop();
        mKeyguardStateController.notifyKeyguardDoneFading();
        mHandler.post(mViewMediatorCallback::keyguardGone);
        notifyKeyguardUpdateMonitor();
    }

    @Override
    public void reset(boolean hideBouncerWhenShowing) {
        if (mShowing) {
            if (mBouncer != null) {
                if (!mBouncer.isSecure()) {
                    dismissAndCollapse();
                }
                mBouncer.show(/* resetSecuritySelection= */ true);
            }
            mKeyguardUpdateMonitor.sendKeyguardReset();
            notifyKeyguardUpdateMonitor();
        } else {
            // This is necessary in order to address an inconsistency between the keyguard service
            // and the keyguard views.
            // TODO: Investigate the source of the inconsistency.
            show(/* options= */ null);
        }
    }

    @Override
    public void onFinishedGoingToSleep() {
        if (mBouncer != null) {
            mBouncer.onScreenTurnedOff();
        }
    }

    @Override
    public void setOccluded(boolean occluded, boolean animate) {
        mIsOccluded = occluded;
        getOverlayViewGlobalStateController().setOccluded(occluded);
        if (!occluded) {
            reset(/* hideBouncerWhenShowing= */ false);
        }
    }

    @Override
    public void onCancelClicked() {
        if (mBouncer == null) return;

        getOverlayViewGlobalStateController().setWindowNeedsInput(/* needsInput= */ false);

        mBouncer.hide(/* destroyView= */ true);
        mKeyguardCancelClickedListener.onCancelClicked();
    }

    @Override
    public boolean isShowing() {
        return mShowing;
    }

    @Override
    public void dismissAndCollapse() {
        // If dismissing and collapsing Keyguard is requested (e.g. by a Keyguard-dismissing
        // Activity) while Keyguard is occluded, unocclude Keyguard so the user can authenticate to
        // dismiss Keyguard.
        if (mIsOccluded) {
            setOccluded(/* occluded= */ false, /* animate= */ false);
        }
        if (!mBouncer.isSecure()) {
            hide(/* startTime= */ 0, /* fadeoutDuration= */ 0);
        }
    }

    @Override
    public void startPreHideAnimation(Runnable finishRunnable) {
        if (mBouncer == null) return;

        mBouncer.startPreHideAnimation(finishRunnable);
    }

    @Override
    public void setNeedsInput(boolean needsInput) {
        getOverlayViewGlobalStateController().setWindowNeedsInput(needsInput);
    }

    /**
     * Add listener for keyguard cancel clicked.
     */
    public void registerOnKeyguardCancelClickedListener(
            OnKeyguardCancelClickedListener keyguardCancelClickedListener) {
        mKeyguardCancelClickedListener = keyguardCancelClickedListener;
    }

    /**
     * Remove listener for keyguard cancel clicked.
     */
    public void unregisterOnKeyguardCancelClickedListener(
            OnKeyguardCancelClickedListener keyguardCancelClickedListener) {
        mKeyguardCancelClickedListener = null;
    }

    @Override
    public ViewRootImpl getViewRootImpl() {
        return ((View) getLayout().getParent()).getViewRootImpl();
    }

    @Override
    public boolean isBouncerShowing() {
        return mBouncer != null && mBouncer.isShowing();
    }

    @Override
    public boolean bouncerIsOrWillBeShowing() {
        return mBouncer != null && (mBouncer.isShowing() || mBouncer.inTransit());
    }

    @Override
    public void keyguardGoingAway() {
        // no-op
    }

    @Override
    public void setKeyguardGoingAwayState(boolean isKeyguardGoingAway) {
        // no-op
    }

    @Override
    public void onStartedGoingToSleep() {
        // no-op
    }

    @Override
    public void onStartedWakingUp() {
        // no-op
    }

    @Override
    public void onScreenTurningOn() {
        // no-op
    }

    @Override
    public void onScreenTurnedOn() {
        // no-op
    }

    @Override
    public boolean shouldDisableWindowAnimationsForUnlock() {
        return false;
    }

    @Override
    public boolean isGoingToNotificationShade() {
        return false;
    }

    @Override
    public boolean isUnlockWithWallpaper() {
        return false;
    }

    @Override
    public boolean shouldSubtleWindowAnimationsForUnlock() {
        return false;
    }

    @Override
    public void registerStatusBar(StatusBar statusBar, ViewGroup container,
            NotificationPanelViewController notificationPanelViewController,
            BiometricUnlockController biometricUnlockController,
            DismissCallbackRegistry dismissCallbackRegistry, ViewGroup lockIconContainer,
            View notificationContainer, KeyguardBypassController bypassController,
            FalsingManager falsingManager) {
        // no-op
    }

    /**
     * Hides Keyguard so that the transitioning Bouncer can be hidden until it is prepared. To be
     * called by {@link com.android.systemui.car.userswitcher.FullscreenUserSwitcherViewMediator}
     * when a new user is selected.
     */
    public void hideKeyguardToPrepareBouncer() {
        getLayout().setVisibility(View.INVISIBLE);
    }

    @VisibleForTesting
    void setKeyguardBouncer(KeyguardBouncer keyguardBouncer) {
        mBouncer = keyguardBouncer;
    }

    private void revealKeyguardIfBouncerPrepared(UserHandle currentUser) {
        int reattemptDelayMillis = 50;
        Runnable revealKeyguard = () -> {
            if (mBouncer == null) {
                if (DEBUG) {
                    Log.d(TAG, "revealKeyguardIfBouncerPrepared: revealKeyguard request is ignored "
                            + "since the Bouncer has not been initialized yet.");
                }
                return;
            }
            if (!mBouncer.inTransit() || !mBouncer.isSecure()) {
                getLayout().setVisibility(View.VISIBLE);
                updateCurrentUserForPasswordEntry(currentUser);
            } else {
                if (DEBUG) {
                    Log.d(TAG, "revealKeyguardIfBouncerPrepared: Bouncer is not prepared "
                            + "yet so reattempting after " + reattemptDelayMillis + "ms.");
                }
                mHandler.postDelayed(() -> revealKeyguardIfBouncerPrepared(currentUser),
                        reattemptDelayMillis);
            }
        };
        mHandler.post(revealKeyguard);
    }

    private void updateCurrentUserForPasswordEntry(UserHandle currentUser) {
        EditText passwordEntry = getLayout().findViewById(R.id.passwordEntry);
        if (passwordEntry != null) {
            mHandler.post(() -> {
                mInputMethodManager.restartInput(passwordEntry);
                passwordEntry.setTextOperationUser(currentUser);
            });
        }
    }

    private void notifyKeyguardUpdateMonitor() {
        mKeyguardUpdateMonitor.onKeyguardVisibilityChanged(mShowing);
        if (mBouncer != null) {
            mKeyguardUpdateMonitor.sendKeyguardBouncerChanged(isBouncerShowing());
        }
    }

    private void registerUserSwitchedListener() {
        mCarServiceProvider.addListener(car -> {
            CarUserManager userManager = (CarUserManager) car.getCarManager(Car.CAR_USER_SERVICE);
            userManager.addListener(Runnable::run, mUserLifecycleListener);
        });
    }

    /**
     * Defines a callback for keyguard cancel button clicked listeners.
     */
    public interface OnKeyguardCancelClickedListener {
        /**
         * Called when keyguard cancel button is clicked.
         */
        void onCancelClicked();
    }
}
