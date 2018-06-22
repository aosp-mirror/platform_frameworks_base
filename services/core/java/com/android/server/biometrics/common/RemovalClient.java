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

/**
 * A class to keep track of the remove state for a given client.
 */
public abstract class RemovalClient extends ClientMonitor {
    private int mFingerId;

    public RemovalClient(Context context, Metrics metrics, BiometricService.DaemonWrapper daemon,
            long halDeviceId, IBinder token, BiometricService.ServiceListener listener,
            int fingerId, int groupId, int userId, boolean restricted, String owner) {
        super(context, metrics, daemon, halDeviceId, token, listener, userId, groupId, restricted,
                owner);
        mFingerId = fingerId;
    }

    @Override
    public int start() {
        // The biometric template ids will be removed when we get confirmation from the HAL
        try {
            final int result = getDaemonWrapper().remove(getGroupId(), mFingerId);
            if (result != 0) {
                Slog.w(getLogTag(), "startRemove with id = " + mFingerId + " failed, result=" +
                        result);
                mMetricsLogger.histogram(mMetrics.tagRemoveStartError(), result);
                onError(BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE, 0 /* vendorCode */);
                return result;
            }
        } catch (RemoteException e) {
            Slog.e(getLogTag(), "startRemove failed", e);
        }
        return 0;
    }

    @Override
    public int stop(boolean initiatedByClient) {
        if (mAlreadyCancelled) {
            Slog.w(getLogTag(), "stopRemove: already cancelled!");
            return 0;
        }

        try {
            final int result = getDaemonWrapper().cancel();
            if (result != 0) {
                Slog.w(getLogTag(), "stopRemoval failed, result=" + result);
                return result;
            }
            if (DEBUG) Slog.w(getLogTag(), "client " + getOwnerString() + " is no longer removing");
        } catch (RemoteException e) {
            Slog.e(getLogTag(), "stopRemoval failed", e);
            return ERROR_ESRCH;
        }
        mAlreadyCancelled = true;
        return 0; // success
    }

    /*
     * @return true if we're done.
     */
    private boolean sendRemoved(int fingerId, int groupId, int remaining) {
        try {
            getListener().onRemoved(getHalDeviceId(), fingerId, groupId, remaining);
        } catch (RemoteException e) {
            Slog.w(getLogTag(), "Failed to notify Removed:", e);
        }
        return remaining == 0;
    }

    @Override
    public boolean onRemoved(int fingerId, int groupId, int remaining) {
        if (fingerId != 0) {
            // TODO: biometric
            FingerprintUtils.getInstance().removeFingerprintIdForUser(getContext(), fingerId,
                    getTargetUserId());
        }
        return sendRemoved(fingerId, getGroupId(), remaining);
    }

    @Override
    public boolean onEnrollResult(int fingerId, int groupId, int rem) {
        if (DEBUG) Slog.w(getLogTag(), "onEnrollResult() called for remove!");
        return true; // Invalid for Remove
    }

    @Override
    public boolean onAuthenticated(int fingerId, int groupId) {
        if (DEBUG) Slog.w(getLogTag(), "onAuthenticated() called for remove!");
        return true; // Invalid for Remove.
    }

    @Override
    public boolean onEnumerationResult(int fingerId, int groupId, int remaining) {
        if (DEBUG) Slog.w(getLogTag(), "onEnumerationResult() called for remove!");
        return true; // Invalid for Remove.
    }
}
