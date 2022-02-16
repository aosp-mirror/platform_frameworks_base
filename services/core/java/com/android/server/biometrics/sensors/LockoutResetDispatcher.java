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

package com.android.server.biometrics.sensors;

import android.content.Context;
import android.hardware.biometrics.IBiometricServiceLockoutResetCallback;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.PowerManager;
import android.os.RemoteException;
import android.util.Slog;

import java.util.ArrayList;

/**
 * Allows clients (such as keyguard) to register for notifications on when biometric lockout
 * ends. This class keeps track of all client callbacks. Individual sensors should notify this
 * when lockout for a specific sensor has been reset.
 */
public class LockoutResetDispatcher implements IBinder.DeathRecipient {

    private static final String TAG = "LockoutResetTracker";

    private final Context mContext;
    private final ArrayList<ClientCallback> mClientCallbacks;

    private static class ClientCallback {
        private static final long WAKELOCK_TIMEOUT_MS = 2000;

        private final String mOpPackageName;
        private final IBiometricServiceLockoutResetCallback mCallback;
        private final PowerManager.WakeLock mWakeLock;

        ClientCallback(Context context, IBiometricServiceLockoutResetCallback callback,
                String opPackageName) {
            final PowerManager pm = context.getSystemService(PowerManager.class);
            mOpPackageName = opPackageName;
            mCallback = callback;
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "LockoutResetMonitor:SendLockoutReset");
        }

        void sendLockoutReset(int sensorId) {
            if (mCallback != null) {
                try {
                    mWakeLock.acquire(WAKELOCK_TIMEOUT_MS);
                    mCallback.onLockoutReset(sensorId, new IRemoteCallback.Stub() {
                        @Override
                        public void sendResult(Bundle data) {
                            releaseWakelock();
                        }
                    });
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to invoke onLockoutReset: ", e);
                    releaseWakelock();
                }
            }
        }

        private void releaseWakelock() {
            if (mWakeLock.isHeld()) {
                mWakeLock.release();
            }
        }
    }

    public LockoutResetDispatcher(Context context) {
        mContext = context;
        mClientCallbacks = new ArrayList<>();
    }

    public void addCallback(IBiometricServiceLockoutResetCallback callback, String opPackageName) {
        if (callback == null) {
            Slog.w(TAG, "Callback from : " + opPackageName + " is null");
            return;
        }

        mClientCallbacks.add(new ClientCallback(mContext, callback, opPackageName));
        try {
            callback.asBinder().linkToDeath(this, 0 /* flags */);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to link to death", e);
        }
    }

    @Override
    public void binderDied() {
        // Do nothing, handled below
    }

    @Override
    public void binderDied(IBinder who) {
        Slog.e(TAG, "Callback binder died: " + who);
        for (ClientCallback callback : mClientCallbacks) {
            if (callback.mCallback.asBinder().equals(who)) {
                Slog.e(TAG, "Removing dead callback for: " + callback.mOpPackageName);
                callback.releaseWakelock();
                mClientCallbacks.remove(callback);
            }
        }
    }

    public void notifyLockoutResetCallbacks(int sensorId) {
        for (ClientCallback callback : mClientCallbacks) {
            callback.sendLockoutReset(sensorId);
        }
    }
}
