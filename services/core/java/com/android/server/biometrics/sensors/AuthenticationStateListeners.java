/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.annotation.NonNull;
import android.hardware.biometrics.AuthenticationStateListener;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Low-level callback interface between BiometricManager and AuthService. Allows core system
 * services (e.g. SystemUI) to register and unregister listeners for updates about the current
 * state of biometric authentication.
 * @hide */
public class AuthenticationStateListeners implements IBinder.DeathRecipient {

    private static final String TAG = "AuthenticationStateListeners";

    @NonNull
    private final CopyOnWriteArrayList<AuthenticationStateListener> mAuthenticationStateListeners =
            new CopyOnWriteArrayList<>();

    /**
     * Enables clients to register an AuthenticationStateListener for updates about the current
     * state of biometric authentication.
     * @param listener listener to register
     */
    public void registerAuthenticationStateListener(
            @NonNull AuthenticationStateListener listener) {
        mAuthenticationStateListeners.add(listener);
        try {
            listener.asBinder().linkToDeath(this, 0 /* flags */);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to link to death", e);
        }
    }

    /**
     * Enables clients to unregister an AuthenticationStateListener.
     * @param listener listener to register
     */
    public void unregisterAuthenticationStateListener(
            @NonNull AuthenticationStateListener listener) {
        mAuthenticationStateListeners.remove(listener);
    }

    /**
     * Defines behavior in response to authentication starting
     * @param requestReason reason from [BiometricRequestConstants.RequestReason] for requesting
     * authentication starting
     */
    public void onAuthenticationStarted(int requestReason) {
        for (AuthenticationStateListener listener: mAuthenticationStateListeners) {
            try {
                listener.onAuthenticationStarted(requestReason);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception in notifying listener that authentication "
                        + "started", e);
            }
        }
    }

    /**
     * Defines behavior in response to authentication stopping
     */
    public void onAuthenticationStopped() {
        for (AuthenticationStateListener listener: mAuthenticationStateListeners) {
            try {
                listener.onAuthenticationStopped();
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception in notifying listener that authentication "
                        + "stopped", e);
            }
        }
    }

    /**
     * Defines behavior in response to a successful authentication
     * @param requestReason Reason from [BiometricRequestConstants.RequestReason] for the requested
     *                      authentication
     * @param userId The user Id for the requested authentication
     */
    public void onAuthenticationSucceeded(int requestReason, int userId) {
        for (AuthenticationStateListener listener: mAuthenticationStateListeners) {
            try {
                listener.onAuthenticationSucceeded(requestReason, userId);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception in notifying listener that authentication "
                        + "succeeded", e);
            }
        }
    }

    /**
     * Defines behavior in response to a failed authentication
     * @param requestReason Reason from [BiometricRequestConstants.RequestReason] for the requested
     *                      authentication
     * @param userId The user Id for the requested authentication
     */
    public void onAuthenticationFailed(int requestReason, int userId) {
        for (AuthenticationStateListener listener: mAuthenticationStateListeners) {
            try {
                listener.onAuthenticationFailed(requestReason, userId);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception in notifying listener that authentication "
                        + "failed", e);
            }
        }
    }

    @Override
    public void binderDied() {
        // Do nothing, handled below
    }

    @Override
    public void binderDied(IBinder who) {
        Slog.w(TAG, "Callback binder died: " + who);
        if (mAuthenticationStateListeners.removeIf(listener -> listener.asBinder().equals(who))) {
            Slog.w(TAG, "Removed dead listener for " + who);
        } else {
            Slog.w(TAG, "No dead listeners found");
        }
    }
}
