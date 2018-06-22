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

package com.android.server.biometrics.common;

import android.content.Context;
import android.hardware.biometrics.BiometricConstants;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.fingerprint.FingerprintUtils;

import java.util.Arrays;

/**
 * A class to keep track of the enrollment state for a given client.
 */
public abstract class EnrollClient extends ClientMonitor {
    private static final long MS_PER_SEC = 1000;
    private static final int ENROLLMENT_TIMEOUT_MS = 60 * 1000; // 1 minute
    private byte[] mCryptoToken;

    public EnrollClient(Context context, Metrics metrics, BiometricService.DaemonWrapper daemon,
            long halDeviceId, IBinder token, BiometricService.ServiceListener listener, int userId,
            int groupId, byte[] cryptoToken, boolean restricted, String owner) {
        super(context, metrics, daemon, halDeviceId, token, listener, userId, groupId, restricted,
                owner);
        mCryptoToken = Arrays.copyOf(cryptoToken, cryptoToken.length);
    }

    @Override
    public boolean onEnrollResult(int fingerId, int groupId, int remaining) {
        if (groupId != getGroupId()) {
            Slog.w(getLogTag(), "groupId != getGroupId(), groupId: " + groupId +
                    " getGroupId():" + getGroupId());
        }
        if (remaining == 0) {
            // TODO: handle other biometrics
            FingerprintUtils.getInstance().addFingerprintForUser(getContext(), fingerId,
                    getTargetUserId());
        }
        return sendEnrollResult(fingerId, groupId, remaining);
    }

    /*
     * @return true if we're done.
     */
    private boolean sendEnrollResult(int fpId, int groupId, int remaining) {
        vibrateSuccess();
        mMetricsLogger.action(mMetrics.actionBiometricEnroll());
        try {
            getListener().onEnrollResult(getHalDeviceId(), fpId, groupId, remaining);
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
            final int result = getDaemonWrapper().enroll(mCryptoToken, getGroupId(), timeout);
            if (result != 0) {
                Slog.w(getLogTag(), "startEnroll failed, result=" + result);
                mMetricsLogger.histogram(mMetrics.tagEnrollStartError(), result);
                onError(BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE, 0 /* vendorCode */);
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
            onError(BiometricConstants.BIOMETRIC_ERROR_CANCELED, 0 /* vendorCode */);
        }
        mAlreadyCancelled = true;
        return 0;
    }

    @Override
    public boolean onRemoved(int fingerId, int groupId, int remaining) {
        if (DEBUG) Slog.w(getLogTag(), "onRemoved() called for enroll!");
        return true; // Invalid for EnrollClient
    }

    @Override
    public boolean onEnumerationResult(int fingerId, int groupId, int remaining) {
        if (DEBUG) Slog.w(getLogTag(), "onEnumerationResult() called for enroll!");
        return true; // Invalid for EnrollClient
    }

    @Override
    public boolean onAuthenticated(int fingerId, int groupId) {
        if (DEBUG) Slog.w(getLogTag(), "onAuthenticated() called for enroll!");
        return true; // Invalid for EnrollClient
    }

}
