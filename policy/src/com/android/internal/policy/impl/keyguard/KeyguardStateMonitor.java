/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.internal.policy.impl.keyguard;

import android.app.ActivityManager;
import android.content.Context;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.policy.IKeyguardService;
import com.android.internal.policy.IKeyguardStateCallback;
import com.android.internal.widget.LockPatternUtils;

/**
 * Maintains a cached copy of Keyguard's state.
 * @hide
 */
public class KeyguardStateMonitor extends IKeyguardStateCallback.Stub {
    private static final String TAG = "KeyguardStateMonitor";

    // These cache the current state of Keyguard to improve performance and avoid deadlock. After
    // Keyguard changes its state, it always triggers a layout in window manager. Because
    // IKeyguardStateCallback is synchronous and because these states are declared volatile, it's
    // guaranteed that window manager picks up the new state all the time in the layout caused by
    // the state change of Keyguard.
    private volatile boolean mIsShowing;
    private volatile boolean mSimSecure;
    private volatile boolean mInputRestricted;

    private final LockPatternUtils mLockPatternUtils;

    public KeyguardStateMonitor(Context context, IKeyguardService service) {
        mLockPatternUtils = new LockPatternUtils(context);
        mLockPatternUtils.setCurrentUser(ActivityManager.getCurrentUser());
        try {
            service.addStateMonitorCallback(this);
        } catch (RemoteException e) {
            Slog.w(TAG, "Remote Exception", e);
        }
    }

    public boolean isShowing() {
        return mIsShowing;
    }

    public boolean isSecure() {
        return mLockPatternUtils.isSecure() || mSimSecure;
    }

    public boolean isInputRestricted() {
        return mInputRestricted;
    }

    @Override // Binder interface
    public void onShowingStateChanged(boolean showing) {
        mIsShowing = showing;
    }

    @Override // Binder interface
    public void onSimSecureStateChanged(boolean simSecure) {
        mSimSecure = simSecure;
    }

    public void setCurrentUser(int userId) {
        mLockPatternUtils.setCurrentUser(userId);
    }

    @Override // Binder interface
    public void onInputRestrictedStateChanged(boolean inputRestricted) {
        mInputRestricted = inputRestricted;
    }
}