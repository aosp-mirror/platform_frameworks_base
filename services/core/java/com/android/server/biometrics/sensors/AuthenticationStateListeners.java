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
import android.hardware.biometrics.events.AuthenticationAcquiredInfo;
import android.hardware.biometrics.events.AuthenticationErrorInfo;
import android.hardware.biometrics.events.AuthenticationFailedInfo;
import android.hardware.biometrics.events.AuthenticationHelpInfo;
import android.hardware.biometrics.events.AuthenticationStartedInfo;
import android.hardware.biometrics.events.AuthenticationStoppedInfo;
import android.hardware.biometrics.events.AuthenticationSucceededInfo;
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
     * Defines behavior in response to biometric authentication being acquired.
     * @param authInfo information related to the biometric authentication acquired.
     */
    public void onAuthenticationAcquired(AuthenticationAcquiredInfo authInfo) {
        for (AuthenticationStateListener listener: mAuthenticationStateListeners) {
            try {
                listener.onAuthenticationAcquired(authInfo);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception in notifying listener that authentication "
                        + "acquired", e);
            }
        }
    }

    /**
     * Defines behavior in response to an unrecoverable error encountered during authentication.
     * @param authInfo information related to the unrecoverable auth error encountered
     */
    public void onAuthenticationError(AuthenticationErrorInfo authInfo) {
        for (AuthenticationStateListener listener : mAuthenticationStateListeners) {
            try {
                listener.onAuthenticationError(authInfo);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception in notifying listener of unrecoverable"
                        + " authentication error", e);
            }
        }
    }

    /**
     * Defines behavior in response to a failed authentication
     * @param authInfo information related to the failed authentication
     */
    public void onAuthenticationFailed(AuthenticationFailedInfo authInfo) {
        for (AuthenticationStateListener listener : mAuthenticationStateListeners) {
            try {
                listener.onAuthenticationFailed(authInfo);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception in notifying listener that authentication "
                        + "failed", e);
            }
        }
    }

    /**
     * Defines behavior in response to a recoverable error encountered during authentication.
     * @param authInfo information related to the recoverable auth error encountered
     */
    public void onAuthenticationHelp(AuthenticationHelpInfo authInfo) {
        for (AuthenticationStateListener listener : mAuthenticationStateListeners) {
            try {
                listener.onAuthenticationHelp(authInfo);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception in notifying listener of recoverable"
                        + " authentication error", e);
            }
        }
    }

    /**
     * Defines behavior in response to authentication starting
     * @param authInfo information related to the authentication starting
     */
    public void onAuthenticationStarted(AuthenticationStartedInfo authInfo) {
        for (AuthenticationStateListener listener: mAuthenticationStateListeners) {
            try {
                listener.onAuthenticationStarted(authInfo);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception in notifying listener that authentication "
                        + "started", e);
            }
        }
    }

    /**
     * Defines behavior in response to authentication stopping
     * @param authInfo information related to the authentication stopping
     */
    public void onAuthenticationStopped(AuthenticationStoppedInfo authInfo) {
        for (AuthenticationStateListener listener: mAuthenticationStateListeners) {
            try {
                listener.onAuthenticationStopped(authInfo);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception in notifying listener that authentication "
                        + "stopped", e);
            }
        }
    }

    /**
     * Defines behavior in response to a successful authentication
     * @param authInfo information related to the successful authentication
     */
    public void onAuthenticationSucceeded(AuthenticationSucceededInfo authInfo) {
        for (AuthenticationStateListener listener: mAuthenticationStateListeners) {
            try {
                listener.onAuthenticationSucceeded(authInfo);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception in notifying listener that authentication "
                        + "succeeded", e);
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
