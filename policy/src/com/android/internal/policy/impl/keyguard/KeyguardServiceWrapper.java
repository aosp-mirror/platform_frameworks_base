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

import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.policy.IKeyguardExitCallback;
import com.android.internal.policy.IKeyguardService;
import com.android.internal.policy.IKeyguardShowCallback;
import com.android.internal.policy.IKeyguardStateCallback;

/**
 * A wrapper class for KeyguardService.  It implements IKeyguardService to ensure the interface
 * remains consistent.
 *
 */
public class KeyguardServiceWrapper implements IKeyguardService {
    private KeyguardStateMonitor mKeyguardStateMonitor;
    private IKeyguardService mService;
    private String TAG = "KeyguardServiceWrapper";

    public KeyguardServiceWrapper(Context context, IKeyguardService service) {
        mService = service;
        mKeyguardStateMonitor = new KeyguardStateMonitor(context, service);
    }

    @Override // Binder interface
    public void verifyUnlock(IKeyguardExitCallback callback) {
        try {
            mService.verifyUnlock(callback);
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    @Override // Binder interface
    public void keyguardDone(boolean authenticated, boolean wakeup) {
        try {
            mService.keyguardDone(authenticated, wakeup);
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    @Override // Binder interface
    public void setOccluded(boolean isOccluded) {
        try {
            mService.setOccluded(isOccluded);
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    @Override
    public void addStateMonitorCallback(IKeyguardStateCallback callback) {
        try {
            mService.addStateMonitorCallback(callback);
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    @Override // Binder interface
    public void dismiss() {
        try {
            mService.dismiss();
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    @Override // Binder interface
    public void onDreamingStarted() {
        try {
            mService.onDreamingStarted();
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    @Override // Binder interface
    public void onDreamingStopped() {
        try {
            mService.onDreamingStopped();
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    @Override // Binder interface
    public void onScreenTurnedOff(int reason) {
        try {
            mService.onScreenTurnedOff(reason);
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    @Override // Binder interface
    public void onScreenTurnedOn(IKeyguardShowCallback result) {
        try {
            mService.onScreenTurnedOn(result);
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    @Override // Binder interface
    public void setKeyguardEnabled(boolean enabled) {
        try {
            mService.setKeyguardEnabled(enabled);
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    @Override // Binder interface
    public void onSystemReady() {
        try {
            mService.onSystemReady();
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    @Override // Binder interface
    public void doKeyguardTimeout(Bundle options) {
        try {
            mService.doKeyguardTimeout(options);
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    @Override // Binder interface
    public void setCurrentUser(int userId) {
        mKeyguardStateMonitor.setCurrentUser(userId);
        try {
            mService.setCurrentUser(userId);
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    @Override // Binder interface
    public void onBootCompleted() {
        try {
            mService.onBootCompleted();
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    @Override // Binder interface
    public void startKeyguardExitAnimation(long startTime, long fadeoutDuration) {
        try {
            mService.startKeyguardExitAnimation(startTime, fadeoutDuration);
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    @Override // Binder interface
    public void onActivityDrawn() {
        try {
            mService.onActivityDrawn();
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    @Override // Binder interface
    public IBinder asBinder() {
        return mService.asBinder();
    }

    public boolean isShowing() {
        return mKeyguardStateMonitor.isShowing();
    }

    public boolean isSecure() {
        return mKeyguardStateMonitor.isSecure();
    }

    public boolean isInputRestricted() {
        return mKeyguardStateMonitor.isInputRestricted();
    }
}