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
import android.hardware.biometrics.BiometricStateListener;
import android.hardware.biometrics.IBiometricStateListener;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.Utils;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A callback for receiving notifications about biometric sensor state changes.
 */
public class BiometricStateCallback implements ClientMonitorCallback {

    private static final String TAG = "BiometricStateCallback";

    @NonNull
    private final CopyOnWriteArrayList<IBiometricStateListener>
            mBiometricStateListeners = new CopyOnWriteArrayList<>();

    private @BiometricStateListener.State int mBiometricState;

    public BiometricStateCallback() {
        mBiometricState = STATE_IDLE;
    }

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

    /**
     * This should be invoked when:
     * 1) Enrolled --> None-enrolled
     * 2) None-enrolled --> enrolled
     * 3) HAL becomes ready
     * 4) Listener is registered
     */
    public void notifyAllEnrollmentStateChanged(int userId, int sensorId,
            boolean hasEnrollments) {
        for (IBiometricStateListener listener : mBiometricStateListeners) {
            notifyEnrollmentStateChanged(listener, userId, sensorId, hasEnrollments);
        }
    }

    /**
     * Notifies the listener of enrollment state changes.
     */
    public void notifyEnrollmentStateChanged(@NonNull IBiometricStateListener listener,
            int userId, int sensorId, boolean hasEnrollments) {
        try {
            listener.onEnrollmentsChanged(userId, sensorId, hasEnrollments);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }
    }

    /**
     * Enables clients to register a BiometricStateListener. For example, this is used to forward
     * fingerprint sensor state changes to SideFpsEventHandler.
     *
     * @param listener
     */
    public void registerBiometricStateListener(@NonNull IBiometricStateListener listener) {
        mBiometricStateListeners.add(listener);
    }
}
