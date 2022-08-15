/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.hardware.biometrics.BiometricStateListener.STATE_AUTH_OTHER;
import static android.hardware.biometrics.BiometricStateListener.STATE_BP_AUTH;
import static android.hardware.biometrics.BiometricStateListener.STATE_ENROLLING;
import static android.hardware.biometrics.BiometricStateListener.STATE_IDLE;
import static android.hardware.biometrics.BiometricStateListener.STATE_KEYGUARD_AUTH;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.UserInfo;
import android.hardware.biometrics.BiometricStateListener;
import android.hardware.biometrics.IBiometricStateListener;
import android.hardware.biometrics.SensorPropertiesInternal;
import android.os.RemoteException;
import android.os.UserManager;
import android.util.Slog;

import com.android.server.biometrics.Utils;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A callback for receiving notifications about biometric sensor state changes.
 *
 * @param <T> service provider type
 * @param <P> internal property type
 */
public class BiometricStateCallback<T extends BiometricServiceProvider<P>,
        P extends SensorPropertiesInternal> implements ClientMonitorCallback {

    private static final String TAG = "BiometricStateCallback";

    @NonNull
    private final CopyOnWriteArrayList<IBiometricStateListener> mBiometricStateListeners =
            new CopyOnWriteArrayList<>();
    @NonNull
    private final UserManager mUserManager;
    @BiometricStateListener.State
    private int mBiometricState;
    @NonNull
    private List<T> mProviders = List.of();

    /**
     * Create a new callback that must be {@link #start(List)}ed.
     *
     * @param userManager user manager
     */
    public BiometricStateCallback(@NonNull UserManager userManager) {
        mBiometricState = STATE_IDLE;
        mUserManager = userManager;
    }

    /**
     * This should be called when the service has been initialized and all providers are ready.
     *
     * @param allProviders all registered biometric service providers
     */
    public synchronized void start(@NonNull List<T> allProviders) {
        mProviders = Collections.unmodifiableList(allProviders);
        broadcastCurrentEnrollmentState(null /* listener */);
    }

    /** Get the current state. */
    @BiometricStateListener.State
    public int getBiometricState() {
        return mBiometricState;
    }

    @Override
    public void onClientStarted(@NonNull BaseClientMonitor client) {
        final int previousBiometricState = mBiometricState;

        if (client instanceof AuthenticationClient) {
            final AuthenticationClient<?> authClient = (AuthenticationClient<?>) client;
            if (authClient.isKeyguard()) {
                mBiometricState = STATE_KEYGUARD_AUTH;
            } else if (authClient.isBiometricPrompt()) {
                mBiometricState = STATE_BP_AUTH;
            } else {
                mBiometricState = STATE_AUTH_OTHER;
            }
        } else if (client instanceof EnrollClient) {
            mBiometricState = STATE_ENROLLING;
        } else {
            Slog.w(TAG, "Other authentication client: " + Utils.getClientName(client));
            mBiometricState = STATE_IDLE;
        }

        Slog.d(TAG, "State updated from " + previousBiometricState + " to " + mBiometricState
                + ", client " + client);
        notifyBiometricStateListeners(mBiometricState);
    }

    @Override
    public void onClientFinished(@NonNull BaseClientMonitor client, boolean success) {
        mBiometricState = STATE_IDLE;
        Slog.d(TAG, "Client finished, state updated to " + mBiometricState + ", client "
                + client);

        if (client instanceof EnrollmentModifier) {
            EnrollmentModifier enrollmentModifier = (EnrollmentModifier) client;
            final boolean enrollmentStateChanged = enrollmentModifier.hasEnrollmentStateChanged();
            Slog.d(TAG, "Enrollment state changed: " + enrollmentStateChanged);
            if (enrollmentStateChanged) {
                notifyAllEnrollmentStateChanged(client.getTargetUserId(),
                        client.getSensorId(),
                        enrollmentModifier.hasEnrollments());
            }
        }

        notifyBiometricStateListeners(mBiometricState);
    }

    private void notifyBiometricStateListeners(@BiometricStateListener.State int newState) {
        for (IBiometricStateListener listener : mBiometricStateListeners) {
            try {
                listener.onStateChanged(newState);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception in biometric state change", e);
            }
        }
    }

    @Override
    public void onBiometricAction(@BiometricStateListener.Action int action) {
        for (IBiometricStateListener listener : mBiometricStateListeners) {
            try {
                listener.onBiometricAction(action);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception in onBiometricAction", e);
            }
        }
    }

    /**
     * Enables clients to register a BiometricStateListener. For example, this is used to forward
     * fingerprint sensor state changes to SideFpsEventHandler.
     *
     * @param listener listener to register
     */
    public synchronized void registerBiometricStateListener(
            @NonNull IBiometricStateListener listener) {
        mBiometricStateListeners.add(listener);
        broadcastCurrentEnrollmentState(listener);
    }

    private synchronized void broadcastCurrentEnrollmentState(
            @Nullable IBiometricStateListener listener) {
        for (T provider : mProviders) {
            for (SensorPropertiesInternal prop : provider.getSensorProperties()) {
                for (UserInfo userInfo : mUserManager.getAliveUsers()) {
                    final boolean enrolled = provider.hasEnrollments(prop.sensorId, userInfo.id);
                    if (listener != null) {
                        notifyEnrollmentStateChanged(
                                listener, userInfo.id, prop.sensorId, enrolled);
                    } else {
                        notifyAllEnrollmentStateChanged(
                                userInfo.id, prop.sensorId, enrolled);
                    }
                }
            }
        }
    }

    private void notifyAllEnrollmentStateChanged(int userId, int sensorId,
            boolean hasEnrollments) {
        for (IBiometricStateListener listener : mBiometricStateListeners) {
            notifyEnrollmentStateChanged(listener, userId, sensorId, hasEnrollments);
        }
    }

    private void notifyEnrollmentStateChanged(@NonNull IBiometricStateListener listener,
            int userId, int sensorId, boolean hasEnrollments) {
        try {
            listener.onEnrollmentsChanged(userId, sensorId, hasEnrollments);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }
    }
}
