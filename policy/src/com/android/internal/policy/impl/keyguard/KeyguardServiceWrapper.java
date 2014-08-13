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

import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.view.MotionEvent;

import com.android.internal.policy.IKeyguardServiceConstants;
import com.android.internal.policy.IKeyguardShowCallback;
import com.android.internal.policy.IKeyguardExitCallback;
import com.android.internal.policy.IKeyguardService;

/**
 * A wrapper class for KeyguardService.  It implements IKeyguardService to ensure the interface
 * remains consistent.
 *
 */
public class KeyguardServiceWrapper implements IKeyguardService {
    private IKeyguardService mService;
    private String TAG = "KeyguardServiceWrapper";

    public KeyguardServiceWrapper(IKeyguardService service) {
        mService = service;
    }

    public boolean isShowing() {
        try {
            return mService.isShowing();
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
        return false;
    }

    public boolean isSecure() {
        try {
            return mService.isSecure();
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
        return false; // TODO cache state
    }

    public boolean isShowingAndNotOccluded() {
        try {
            return mService.isShowingAndNotOccluded();
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
        return false; // TODO cache state
    }

    public boolean isInputRestricted() {
        try {
            return mService.isInputRestricted();
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
        return false; // TODO cache state
    }

    public boolean isDismissable() {
        try {
            return mService.isDismissable();
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
        return true; // TODO cache state
    }

    public void verifyUnlock(IKeyguardExitCallback callback) {
        try {
            mService.verifyUnlock(callback);
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    public void keyguardDone(boolean authenticated, boolean wakeup) {
        try {
            mService.keyguardDone(authenticated, wakeup);
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    public int setOccluded(boolean isOccluded) {
        try {
            return mService.setOccluded(isOccluded);
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
            return IKeyguardServiceConstants.KEYGUARD_SERVICE_SET_OCCLUDED_RESULT_NONE;
        }
    }

    public void dismiss() {
        try {
            mService.dismiss();
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    public void onDreamingStarted() {
        try {
            mService.onDreamingStarted();
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    public void onDreamingStopped() {
        try {
            mService.onDreamingStopped();
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    public void onScreenTurnedOff(int reason) {
        try {
            mService.onScreenTurnedOff(reason);
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    public void onScreenTurnedOn(IKeyguardShowCallback result) {
        try {
            mService.onScreenTurnedOn(result);
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    public void setKeyguardEnabled(boolean enabled) {
        try {
            mService.setKeyguardEnabled(enabled);
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    public void onSystemReady() {
        try {
            mService.onSystemReady();
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    public void doKeyguardTimeout(Bundle options) {
        try {
            mService.doKeyguardTimeout(options);
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    public void setCurrentUser(int userId) {
        try {
            mService.setCurrentUser(userId);
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    public void onBootCompleted() {
        try {
            mService.onBootCompleted();
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    public void startKeyguardExitAnimation(long startTime, long fadeoutDuration) {
        try {
            mService.startKeyguardExitAnimation(startTime, fadeoutDuration);
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    public void onActivityDrawn() {
        try {
            mService.onActivityDrawn();
        } catch (RemoteException e) {
            Slog.w(TAG , "Remote Exception", e);
        }
    }

    public void showAssistant() {
        // Not used by PhoneWindowManager
    }

    public void dispatch(MotionEvent event) {
        // Not used by PhoneWindowManager.  See code in {@link NavigationBarView}
    }

    public void launchCamera() {
        // Not used by PhoneWindowManager.  See code in {@link NavigationBarView}
    }

    @Override
    public IBinder asBinder() {
        return mService.asBinder();
    }

}