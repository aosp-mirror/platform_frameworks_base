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

package com.android.server.biometrics.sensors.fingerprint;

import static android.hardware.fingerprint.FingerprintStateListener.STATE_AUTH_OTHER;
import static android.hardware.fingerprint.FingerprintStateListener.STATE_BP_AUTH;
import static android.hardware.fingerprint.FingerprintStateListener.STATE_ENROLLING;
import static android.hardware.fingerprint.FingerprintStateListener.STATE_IDLE;
import static android.hardware.fingerprint.FingerprintStateListener.STATE_KEYGUARD_AUTH;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.fingerprint.FingerprintStateListener;
import android.hardware.fingerprint.IFingerprintStateListener;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.Utils;
import com.android.server.biometrics.sensors.AuthenticationClient;
import com.android.server.biometrics.sensors.BaseClientMonitor;
import com.android.server.biometrics.sensors.EnrollClient;
import com.android.server.biometrics.sensors.EnrollmentModifier;
import com.android.server.biometrics.sensors.RemovalConsumer;
import com.android.server.biometrics.sensors.fingerprint.hidl.FingerprintEnrollClient;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A callback for receiving notifications about changes in fingerprint state.
 */
public class FingerprintStateCallback implements BaseClientMonitor.Callback {

    @NonNull private final CopyOnWriteArrayList<IFingerprintStateListener>
            mFingerprintStateListeners = new CopyOnWriteArrayList<>();

    private @FingerprintStateListener.State int mFingerprintState;

    public FingerprintStateCallback() {
        mFingerprintState = STATE_IDLE;
    }

    public int getFingerprintState() {
        return mFingerprintState;
    }

    @Override
    public void onClientStarted(@NonNull BaseClientMonitor client) {
        final int previousFingerprintState = mFingerprintState;

        if (client instanceof AuthenticationClient) {
            final AuthenticationClient<?> authClient = (AuthenticationClient<?>) client;
            if (authClient.isKeyguard()) {
                mFingerprintState = STATE_KEYGUARD_AUTH;
            } else if (authClient.isBiometricPrompt()) {
                mFingerprintState = STATE_BP_AUTH;
            } else {
                mFingerprintState = STATE_AUTH_OTHER;
            }
        } else if (client instanceof FingerprintEnrollClient) {
            mFingerprintState = STATE_ENROLLING;
        } else {
            Slog.w(FingerprintService.TAG,
                    "Other authentication client: " + Utils.getClientName(client));
            mFingerprintState = STATE_IDLE;
        }

        Slog.d(FingerprintService.TAG, "Fps state updated from " + previousFingerprintState
                + " to " + mFingerprintState + ", client " + client);
        notifyFingerprintStateListeners(mFingerprintState);
    }

    @Override
    public void onClientFinished(@NonNull BaseClientMonitor client, boolean success) {
        mFingerprintState = STATE_IDLE;
        Slog.d(FingerprintService.TAG,
                "Client finished, fps state updated to " + mFingerprintState + ", client "
                        + client);

        if (client instanceof EnrollmentModifier) {
            EnrollmentModifier enrollmentModifier = (EnrollmentModifier) client;
            final boolean enrollmentStateChanged = enrollmentModifier.hasEnrollmentStateChanged();
            Slog.d(FingerprintService.TAG, "Enrollment state changed: " + enrollmentStateChanged);
            if (enrollmentStateChanged) {
                notifyAllFingerprintEnrollmentStateChanged(client.getTargetUserId(),
                        client.getSensorId(),
                        enrollmentModifier.hasEnrollments());
            }
        }

        notifyFingerprintStateListeners(mFingerprintState);
    }

    private void notifyFingerprintStateListeners(@FingerprintStateListener.State int newState) {
        for (IFingerprintStateListener listener : mFingerprintStateListeners) {
            try {
                listener.onStateChanged(newState);
            } catch (RemoteException e) {
                Slog.e(FingerprintService.TAG, "Remote exception in fingerprint state change", e);
            }
        }
    }

    /**
     * This should be invoked when:
     *  1) Enrolled --> None-enrolled
     *  2) None-enrolled --> enrolled
     *  3) HAL becomes ready
     *  4) Listener is registered
     */
    void notifyAllFingerprintEnrollmentStateChanged(int userId, int sensorId,
            boolean hasEnrollments) {
        for (IFingerprintStateListener listener : mFingerprintStateListeners) {
            notifyFingerprintEnrollmentStateChanged(listener, userId, sensorId, hasEnrollments);
        }
    }

    /**
     * Notifies the listener of enrollment state changes.
     */
    void notifyFingerprintEnrollmentStateChanged(@NonNull IFingerprintStateListener listener,
            int userId, int sensorId, boolean hasEnrollments) {
        try {
            listener.onEnrollmentsChanged(userId, sensorId, hasEnrollments);
        } catch (RemoteException e) {
            Slog.e(FingerprintService.TAG, "Remote exception", e);
        }
    }

    /**
     * Enables clients to register a FingerprintStateListener. Used by FingerprintService to forward
     * updates in fingerprint sensor state to the SideFpNsEventHandler
     * @param listener
     */
    public void registerFingerprintStateListener(@NonNull IFingerprintStateListener listener) {
        mFingerprintStateListeners.add(listener);
    }
}
