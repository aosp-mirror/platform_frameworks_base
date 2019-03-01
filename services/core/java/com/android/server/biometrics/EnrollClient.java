/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.biometrics;

import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * A class to keep track of the enrollment state for a given client.
 */
public abstract class EnrollClient extends ClientMonitor {
    private static final long MS_PER_SEC = 1000;
    private static final int ENROLLMENT_TIMEOUT_MS = 60 * 1000; // 1 minute
    private final byte[] mCryptoToken;
    private final BiometricUtils mBiometricUtils;
    private final int[] mDisabledFeatures;

    public abstract boolean shouldVibrate();

    public EnrollClient(Context context, Metrics metrics,
            BiometricServiceBase.DaemonWrapper daemon, long halDeviceId, IBinder token,
            BiometricServiceBase.ServiceListener listener, int userId, int groupId,
            byte[] cryptoToken, boolean restricted, String owner, BiometricUtils utils,
            final int[] disabledFeatures) {
        super(context, metrics, daemon, halDeviceId, token, listener, userId, groupId, restricted,
                owner, 0 /* cookie */);
        mBiometricUtils = utils;
        mCryptoToken = Arrays.copyOf(cryptoToken, cryptoToken.length);
        mDisabledFeatures = Arrays.copyOf(disabledFeatures, disabledFeatures.length);
    }

    @Override
    protected int statsAction() {
        return BiometricsProtoEnums.ACTION_ENROLL;
    }

    @Override
    public boolean onEnrollResult(BiometricAuthenticator.Identifier identifier,
            int remaining) {
        if (remaining == 0) {
            mBiometricUtils.addBiometricForUser(getContext(), getTargetUserId(), identifier);
        }
        notifyUserActivity();
        return sendEnrollResult(identifier, remaining);
    }

    /*
     * @return true if we're done.
     */
    private boolean sendEnrollResult(BiometricAuthenticator.Identifier identifier,
            int remaining) {
        if (shouldVibrate()) {
            vibrateSuccess();
        }
        mMetricsLogger.action(mMetrics.actionBiometricEnroll());
        try {
            final BiometricServiceBase.ServiceListener listener = getListener();
            if (listener != null) {
                listener.onEnrollResult(identifier, remaining);
            }
            return remaining == 0;
        } catch (RemoteException e) {
            Slog.w(getLogTag(), "Failed to notify EnrollResult:", e);
            return true;
        }
    }

    @Override
    public int start() {
        final int timeout = (int) (ENROLLMENT_TIMEOUT_MS / MS_PER_SEC);
        try {
            final ArrayList<Integer> disabledFeatures = new ArrayList<>();
            for (int i = 0; i < mDisabledFeatures.length; i++) {
                disabledFeatures.add(mDisabledFeatures[i]);
            }

            final int result = getDaemonWrapper().enroll(mCryptoToken, getGroupId(), timeout,
                    disabledFeatures);
            if (result != 0) {
                Slog.w(getLogTag(), "startEnroll failed, result=" + result);
                mMetricsLogger.histogram(mMetrics.tagEnrollStartError(), result);
                onError(getHalDeviceId(), BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE,
                        0 /* vendorCode */);
                return result;
            }
        } catch (RemoteException e) {
            Slog.e(getLogTag(), "startEnroll failed", e);
        }
        return 0; // success
    }

    @Override
    public int stop(boolean initiatedByClient) {
        if (mAlreadyCancelled) {
            Slog.w(getLogTag(), "stopEnroll: already cancelled!");
            return 0;
        }

        try {
            final int result = getDaemonWrapper().cancel();
            if (result != 0) {
                Slog.w(getLogTag(), "startEnrollCancel failed, result = " + result);
                return result;
            }
        } catch (RemoteException e) {
            Slog.e(getLogTag(), "stopEnrollment failed", e);
        }
        if (initiatedByClient) {
            onError(getHalDeviceId(), BiometricConstants.BIOMETRIC_ERROR_CANCELED,
                    0 /* vendorCode */);
        }
        mAlreadyCancelled = true;
        return 0;
    }

    @Override
    public boolean onRemoved(BiometricAuthenticator.Identifier identifier, int remaining) {
        if (DEBUG) Slog.w(getLogTag(), "onRemoved() called for enroll!");
        return true; // Invalid for EnrollClient
    }

    @Override
    public boolean onEnumerationResult(BiometricAuthenticator.Identifier identifier,
            int remaining) {
        if (DEBUG) Slog.w(getLogTag(), "onEnumerationResult() called for enroll!");
        return true; // Invalid for EnrollClient
    }

    @Override
    public boolean onAuthenticated(BiometricAuthenticator.Identifier identifier,
            boolean authenticated, ArrayList<Byte> token) {
        if (DEBUG) Slog.w(getLogTag(), "onAuthenticated() called for enroll!");
        return true; // Invalid for EnrollClient
    }

}
