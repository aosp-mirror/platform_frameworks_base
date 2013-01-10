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

package com.android.internal.policy.impl;

import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.internal.policy.IKeyguardResult;
import com.android.internal.policy.IKeyguardService;

/**
 * A wrapper class for KeyguardService.  It implements IKeyguardService to ensure the interface
 * remains consistent.
 *
 */
public class KeyguardServiceWrapper implements IKeyguardService {
    private IKeyguardService mService;

    public KeyguardServiceWrapper(IKeyguardService service) {
        mService = service;
    }

    public boolean isShowing() {
        try {
            return mService.isShowing();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return false;
    }

    public boolean isSecure() {
        try {
            return mService.isSecure();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return false; // TODO cache state
    }

    public boolean isShowingAndNotHidden() {
        try {
            return mService.isShowingAndNotHidden();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return false; // TODO cache state
    }

    public boolean isInputRestricted() {
        try {
            return mService.isInputRestricted();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return false; // TODO cache state
    }

    public boolean isDismissable() {
        try {
            return mService.isDismissable();
        } catch (RemoteException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return true; // TODO cache state
    }

    public void userActivity() {
        try {
            mService.userActivity();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public void verifyUnlock(IKeyguardResult result) {
        try {
            mService.verifyUnlock(result);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public void keyguardDone(boolean authenticated, boolean wakeup) {
        try {
            mService.keyguardDone(authenticated, wakeup);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public void setHidden(boolean isHidden) {
        try {
            mService.setHidden(isHidden);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public void dismiss() {
        try {
            mService.dismiss();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public void onWakeKeyWhenKeyguardShowingTq(int keyCode) {
        try {
            mService.onWakeKeyWhenKeyguardShowingTq(keyCode);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public void onWakeMotionWhenKeyguardShowingTq() {
        try {
            mService.onWakeMotionWhenKeyguardShowingTq();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public void onDreamingStarted() {
        try {
            mService.onDreamingStarted();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public void onDreamingStopped() {
        try {
            mService.onDreamingStopped();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public void onScreenTurnedOff(int reason) {
        try {
            mService.onScreenTurnedOff(reason);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public void onScreenTurnedOn(IKeyguardResult result) {
        try {
            mService.onScreenTurnedOn(result);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public void setKeyguardEnabled(boolean enabled) {
        try {
            mService.setKeyguardEnabled(enabled);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public void onSystemReady() {
        try {
            mService.onSystemReady();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public void doKeyguardTimeout(Bundle options) {
        try {
            mService.doKeyguardTimeout(options);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public void setCurrentUser(int userId) {
        try {
            mService.setCurrentUser(userId);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public void showAssistant() {
        try {
            mService.showAssistant();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    @Override
    public IBinder asBinder() {
        return mService.asBinder();
    }

}