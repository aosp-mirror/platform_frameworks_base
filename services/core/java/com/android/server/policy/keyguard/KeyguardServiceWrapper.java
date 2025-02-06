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

package com.android.server.policy.keyguard;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.internal.policy.IKeyguardDrawnCallback;
import com.android.internal.policy.IKeyguardExitCallback;
import com.android.internal.policy.IKeyguardService;
import com.android.internal.policy.IKeyguardStateCallback;

import java.io.PrintWriter;

/**
 * A wrapper class for KeyguardService.  It implements IKeyguardService to ensure the interface
 * remains consistent.
 *
 */
public class KeyguardServiceWrapper implements IKeyguardService {
    private KeyguardStateMonitor mKeyguardStateMonitor;
    private IKeyguardService mService;
    private String TAG = "KeyguardServiceWrapper";

    public KeyguardServiceWrapper(Context context, IKeyguardService service,
            KeyguardStateMonitor.StateCallback callback) {
        mService = service;
        mKeyguardStateMonitor = new KeyguardStateMonitor(context, service, callback);
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
    public void setOccluded(boolean isOccluded, boolean animate) {
        try {
            mService.setOccluded(isOccluded, animate);
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
    public void dismiss(IKeyguardDismissCallback callback, CharSequence message) {
        try {
            mService.dismiss(callback, message);
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

    @Override
    public void onStartedGoingToSleep(@PowerManager.GoToSleepReason int pmSleepReason) {
        try {
            mService.onStartedGoingToSleep(pmSleepReason);
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    @Override
    public void onFinishedGoingToSleep(
            @PowerManager.GoToSleepReason int pmSleepReason,
            boolean powerButtonLaunchGestureTriggered) {
        try {
            mService.onFinishedGoingToSleep(pmSleepReason, powerButtonLaunchGestureTriggered);
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    @Override
    public void onStartedWakingUp(
            @PowerManager.WakeReason int pmWakeReason, boolean powerButtonLaunchGestureTriggered) {
        try {
            mService.onStartedWakingUp(pmWakeReason, powerButtonLaunchGestureTriggered);
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    @Override
    public void onFinishedWakingUp() {
        try {
            mService.onFinishedWakingUp();
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    @Override
    public void onScreenTurningOn(IKeyguardDrawnCallback callback) {
        try {
            mService.onScreenTurningOn(callback);
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    @Override
    public void onScreenTurnedOn() {
        try {
            mService.onScreenTurnedOn();
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    @Override
    public void onScreenTurningOff() {
        try {
            mService.onScreenTurningOff();
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    @Override
    public void onScreenTurnedOff() {
        try {
            mService.onScreenTurnedOff();
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
        int userId = mKeyguardStateMonitor.getCurrentUser();
        if (mKeyguardStateMonitor.isSecure(userId)) {
            // Preemptively inform the cache that the keyguard will soon be showing, as calls to
            // doKeyguardTimeout are a signal to lock the device as soon as possible.
            mKeyguardStateMonitor.onShowingStateChanged(true, userId);
        }
        try {
            mService.doKeyguardTimeout(options);
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    // Binder interface
    @Override
    public void showDismissibleKeyguard() {
        try {
            mService.showDismissibleKeyguard();
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    @Override // Binder interface
    public void setSwitchingUser(boolean switching) {
        try {
            mService.setSwitchingUser(switching);
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

    @Override
    public void onShortPowerPressedGoHome() {
        try {
            mService.onShortPowerPressedGoHome();
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    @Override
    public void dismissKeyguardToLaunch(Intent intentToLaunch) {
        try {
            mService.dismissKeyguardToLaunch(intentToLaunch);
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    @Override
    public void onSystemKeyPressed(int keycode) {
        try {
            mService.onSystemKeyPressed(keycode);
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

    public boolean isTrusted() {
        return mKeyguardStateMonitor.isTrusted();
    }

    public boolean isSecure(int userId) {
        return mKeyguardStateMonitor.isSecure(userId);
    }

    public boolean isInputRestricted() {
        return mKeyguardStateMonitor.isInputRestricted();
    }

    public void dump(String prefix, PrintWriter pw) {
        mKeyguardStateMonitor.dump(prefix, pw);
    }
}
