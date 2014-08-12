/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Slog;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.policy.IKeyguardShowCallback;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.ViewMediatorCallback;

import static com.android.keyguard.KeyguardHostView.OnDismissAction;

/**
 * Manages creating, showing, hiding and resetting the keyguard within the status bar. Calls back
 * via {@link ViewMediatorCallback} to poke the wake lock and report that the keyguard is done,
 * which is in turn, reported to this class by the current
 * {@link com.android.keyguard.KeyguardViewBase}.
 */
public class StatusBarKeyguardViewManager {

    // When hiding the Keyguard with timing supplied from WindowManager, better be early than late.
    private static final long HIDE_TIMING_CORRECTION_MS = -3 * 16;

    private static String TAG = "StatusBarKeyguardViewManager";

    private final Context mContext;

    private LockPatternUtils mLockPatternUtils;
    private ViewMediatorCallback mViewMediatorCallback;
    private PhoneStatusBar mPhoneStatusBar;
    private ScrimController mScrimController;

    private ViewGroup mContainer;
    private StatusBarWindowManager mStatusBarWindowManager;

    private boolean mScreenOn = false;
    private KeyguardBouncer mBouncer;
    private boolean mShowing;
    private boolean mOccluded;

    private boolean mFirstUpdate = true;
    private boolean mLastShowing;
    private boolean mLastOccluded;
    private boolean mLastBouncerShowing;
    private boolean mLastBouncerDismissible;

    public StatusBarKeyguardViewManager(Context context, ViewMediatorCallback callback,
            LockPatternUtils lockPatternUtils) {
        mContext = context;
        mViewMediatorCallback = callback;
        mLockPatternUtils = lockPatternUtils;
    }

    public void registerStatusBar(PhoneStatusBar phoneStatusBar,
            ViewGroup container, StatusBarWindowManager statusBarWindowManager,
            ScrimController scrimController) {
        mPhoneStatusBar = phoneStatusBar;
        mContainer = container;
        mStatusBarWindowManager = statusBarWindowManager;
        mScrimController = scrimController;
        mBouncer = new KeyguardBouncer(mContext, mViewMediatorCallback, mLockPatternUtils,
                mStatusBarWindowManager, container);
    }

    /**
     * Show the keyguard.  Will handle creating and attaching to the view manager
     * lazily.
     */
    public void show(Bundle options) {
        mShowing = true;
        mStatusBarWindowManager.setKeyguardShowing(true);
        reset();
    }

    /**
     * Shows the notification keyguard or the bouncer depending on
     * {@link KeyguardBouncer#needsFullscreenBouncer()}.
     */
    private void showBouncerOrKeyguard() {
        if (mBouncer.needsFullscreenBouncer()) {

            // The keyguard might be showing (already). So we need to hide it.
            mPhoneStatusBar.hideKeyguard();
            mBouncer.show();
        } else {
            mPhoneStatusBar.showKeyguard();
            mBouncer.hide(false /* destroyView */);
            mBouncer.prepare();
        }
    }

    private void showBouncer() {
        if (mShowing) {
            mBouncer.show();
        }
        updateStates();
    }

    public void dismissWithAction(OnDismissAction r) {
        if (mShowing) {
            mBouncer.showWithDismissAction(r);
        }
        updateStates();
    }

    /**
     * Reset the state of the view.
     */
    public void reset() {
        if (mShowing) {
            if (mOccluded) {
                mPhoneStatusBar.hideKeyguard();
                mBouncer.hide(false /* destroyView */);
            } else {
                showBouncerOrKeyguard();
            }
            updateStates();
        }
    }

    public void onScreenTurnedOff() {
        mScreenOn = false;
        mPhoneStatusBar.onScreenTurnedOff();
        mBouncer.onScreenTurnedOff();
    }

    public void onScreenTurnedOn(final IKeyguardShowCallback callback) {
        mScreenOn = true;
        mPhoneStatusBar.onScreenTurnedOn();
        if (callback != null) {
            callbackAfterDraw(callback);
        }
    }

    private void callbackAfterDraw(final IKeyguardShowCallback callback) {
        mContainer.post(new Runnable() {
            @Override
            public void run() {
                try {
                    callback.onShown(mContainer.getWindowToken());
                } catch (RemoteException e) {
                    Slog.w(TAG, "Exception calling onShown():", e);
                }
            }
        });
    }

    public void verifyUnlock() {
        dismiss();
    }

    public void setNeedsInput(boolean needsInput) {
        mStatusBarWindowManager.setKeyguardNeedsInput(needsInput);
    }

    public void updateUserActivityTimeout() {
        mStatusBarWindowManager.setKeyguardUserActivityTimeout(mBouncer.getUserActivityTimeout());
    }

    public void setOccluded(boolean occluded) {
        if (occluded && !mOccluded && mShowing) {
            if (mPhoneStatusBar.isInLaunchTransition()) {
                mOccluded = true;
                mPhoneStatusBar.fadeKeyguardAfterLaunchTransition(null /* beforeFading */,
                        new Runnable() {
                            @Override
                            public void run() {
                                mStatusBarWindowManager.setKeyguardOccluded(true);
                            }
                        });
                return;
            }
        }
        mOccluded = occluded;
        mStatusBarWindowManager.setKeyguardOccluded(occluded);
        reset();
    }

    public boolean isOccluded() {
        return mOccluded;
    }

    /**
     * Starts the animation before we dismiss Keyguard, i.e. an disappearing animation on the
     * security view of the bouncer.
     *
     * @param finishRunnable the runnable to be run after the animation finished
     */
    public void startPreHideAnimation(Runnable finishRunnable) {
        if (mBouncer.isShowing()) {
            mBouncer.startPreHideAnimation(finishRunnable);
        } else {
            finishRunnable.run();
        }
    }

    /**
     * Hides the keyguard view
     */
    public void hide(long startTime, final long fadeoutDuration) {
        mShowing = false;

        long uptimeMillis = SystemClock.uptimeMillis();
        long delay = Math.max(0, startTime + HIDE_TIMING_CORRECTION_MS - uptimeMillis);

        if (mPhoneStatusBar.isInLaunchTransition() ) {
            mPhoneStatusBar.fadeKeyguardAfterLaunchTransition(new Runnable() {
                @Override
                public void run() {
                    mStatusBarWindowManager.setKeyguardShowing(false);
                    mStatusBarWindowManager.setKeyguardFadingAway(true);
                    mBouncer.hide(true /* destroyView */);
                    updateStates();
                    mScrimController.animateKeyguardFadingOut(
                            PhoneStatusBar.FADE_KEYGUARD_START_DELAY,
                            PhoneStatusBar.FADE_KEYGUARD_DURATION, null);
                }
            }, new Runnable() {
                @Override
                public void run() {
                    mPhoneStatusBar.hideKeyguard();
                    mStatusBarWindowManager.setKeyguardFadingAway(false);
                    mViewMediatorCallback.keyguardGone();
                }
            });
        } else {
            mPhoneStatusBar.setKeyguardFadingAway(delay, fadeoutDuration);
            boolean staying = mPhoneStatusBar.hideKeyguard();
            if (!staying) {
                mStatusBarWindowManager.setKeyguardFadingAway(true);
                mScrimController.animateKeyguardFadingOut(delay, fadeoutDuration, new Runnable() {
                    @Override
                    public void run() {
                        mStatusBarWindowManager.setKeyguardFadingAway(false);
                        mPhoneStatusBar.finishKeyguardFadingAway();
                    }
                });
            } else {
                mScrimController.animateGoingToFullShade(delay, fadeoutDuration);
                mPhoneStatusBar.finishKeyguardFadingAway();
            }
            mStatusBarWindowManager.setKeyguardShowing(false);
            mBouncer.hide(true /* destroyView */);
            mViewMediatorCallback.keyguardGone();
            updateStates();
        }

    }

    /**
     * Dismisses the keyguard by going to the next screen or making it gone.
     */
    public void dismiss() {
        if (mScreenOn) {
            showBouncer();
        }
    }

    public boolean isSecure() {
        return mBouncer.isSecure();
    }

    /**
     * @return Whether the keyguard is showing
     */
    public boolean isShowing() {
        return mShowing;
    }

    /**
     * Notifies this manager that the back button has been pressed.
     *
     * @return whether the back press has been handled
     */
    public boolean onBackPressed() {
        if (mBouncer.isShowing()) {
            mBouncer.hide(false /* destroyView */);
            mPhoneStatusBar.showKeyguard();
            updateStates();
            return true;
        }
        return false;
    }

    public boolean isBouncerShowing() {
        return mBouncer.isShowing();
    }

    private void updateStates() {
        int vis = mContainer.getSystemUiVisibility();
        boolean showing = mShowing;
        boolean occluded = mOccluded;
        boolean bouncerShowing = mBouncer.isShowing();
        boolean bouncerDismissible = bouncerShowing && !mBouncer.needsFullscreenBouncer();

        if ((bouncerDismissible || !showing) != (mLastBouncerDismissible || !mLastShowing)
                || mFirstUpdate) {
            if (bouncerDismissible || !showing) {
                mContainer.setSystemUiVisibility(vis & ~View.STATUS_BAR_DISABLE_BACK);
            } else {
                mContainer.setSystemUiVisibility(vis | View.STATUS_BAR_DISABLE_BACK);
            }
        }
        if ((!(showing && !occluded) || bouncerShowing)
                != (!(mLastShowing && !mLastOccluded) || mLastBouncerShowing) || mFirstUpdate) {
            if (mPhoneStatusBar.getNavigationBarView() != null) {
                if (!(showing && !occluded) || bouncerShowing) {
                    mPhoneStatusBar.getNavigationBarView().setVisibility(View.VISIBLE);
                } else {
                    mPhoneStatusBar.getNavigationBarView().setVisibility(View.GONE);
                }
            }
        }

        if (bouncerShowing != mLastBouncerShowing || mFirstUpdate) {
            mStatusBarWindowManager.setBouncerShowing(bouncerShowing);
            mPhoneStatusBar.setBouncerShowing(bouncerShowing);
            mScrimController.setBouncerShowing(bouncerShowing);
        }

        KeyguardUpdateMonitor updateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
        if ((showing && !occluded) != (mLastShowing && !mLastOccluded) || mFirstUpdate) {
            updateMonitor.sendKeyguardVisibilityChanged(showing && !occluded);
        }
        if (bouncerShowing != mLastBouncerShowing || mFirstUpdate) {
            updateMonitor.sendKeyguardBouncerChanged(bouncerShowing);
        }

        mFirstUpdate = false;
        mLastShowing = showing;
        mLastOccluded = occluded;
        mLastBouncerShowing = bouncerShowing;
        mLastBouncerDismissible = bouncerDismissible;
    }

    public boolean onMenuPressed() {
        return mBouncer.onMenuPressed();
    }

    public boolean interceptMediaKey(KeyEvent event) {
        return mBouncer.interceptMediaKey(event);
    }
}
