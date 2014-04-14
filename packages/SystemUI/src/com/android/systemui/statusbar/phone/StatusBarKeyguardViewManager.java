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
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.Slog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.android.internal.policy.IKeyguardShowCallback;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardHostView;
import com.android.keyguard.KeyguardSimpleHostView;
import com.android.keyguard.R;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.keyguard.KeyguardViewMediator;

/**
 * Manages creating, showing, hiding and resetting the keyguard within the status bar. Calls back
 * via {@link ViewMediatorCallback} to poke the wake lock and report that the keyguard is done,
 * which is in turn, reported to this class by the current
 * {@link com.android.keyguard.KeyguardViewBase}.
 */
public class StatusBarKeyguardViewManager {
    private static String TAG = "StatusBarKeyguardViewManager";

    private final Context mContext;

    private LockPatternUtils mLockPatternUtils;
    private ViewMediatorCallback mViewMediatorCallback;
    private PhoneStatusBar mPhoneStatusBar;

    private ViewGroup mContainer;
    private StatusBarWindowManager mStatusBarWindowManager;

    private boolean mScreenOn = false;
    private KeyguardBouncer mBouncer;
    private boolean mShowing;

    public StatusBarKeyguardViewManager(Context context, ViewMediatorCallback callback,
            LockPatternUtils lockPatternUtils) {
        mContext = context;
        mViewMediatorCallback = callback;
        mLockPatternUtils = lockPatternUtils;
    }

    public void registerStatusBar(PhoneStatusBar phoneStatusBar,
            ViewGroup container, StatusBarWindowManager statusBarWindowManager) {
        mPhoneStatusBar = phoneStatusBar;
        mContainer = container;
        mStatusBarWindowManager = statusBarWindowManager;
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
        mPhoneStatusBar.showKeyguard();
        mBouncer.prepare();
        updateBackButtonState();
    }

    public void showBouncer() {
        mBouncer.show();
        updateBackButtonState();
    }

    /**
     * Reset the state of the view.
     */
    public void reset() {
        mBouncer.reset();
        mPhoneStatusBar.showKeyguard();
        updateBackButtonState();
    }

    public void onScreenTurnedOff() {
        mScreenOn = false;
        mBouncer.onScreenTurnedOff();
    }

    public void onScreenTurnedOn(final IKeyguardShowCallback callback) {
        mScreenOn = true;
        mBouncer.onScreenTurnedOn();
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
        mStatusBarWindowManager.setKeyguardOccluded(occluded);
    }

    /**
     * Hides the keyguard view
     */
    public void hide() {
        mShowing = false;
        mPhoneStatusBar.hideKeyguard();
        mStatusBarWindowManager.setKeyguardShowing(false);
        mBouncer.hide();
        mViewMediatorCallback.keyguardGone();
    }

    /**
     * Dismisses the keyguard by going to the next screen or making it gone.
     */
    public void dismiss() {
        if (mScreenOn) {
            showBouncer();
        }
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
            mBouncer.hide();
            mPhoneStatusBar.showKeyguard();
            updateBackButtonState();
            return true;
        }
        return false;
    }

    private void updateBackButtonState() {
        int vis = mContainer.getSystemUiVisibility();
        if (mBouncer.isShowing()) {
            mContainer.setSystemUiVisibility(vis & ~View.STATUS_BAR_DISABLE_BACK);
        } else {
            mContainer.setSystemUiVisibility(vis | View.STATUS_BAR_DISABLE_BACK);
        }
    }
}
