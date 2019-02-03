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

/**
 * A class to keep track of the remove state for a given client.
 */
public abstract class RemovalClient extends ClientMonitor {
    private final int mBiometricId;
    private final BiometricUtils mBiometricUtils;

    public RemovalClient(Context context, Metrics metrics,
            BiometricServiceBase.DaemonWrapper daemon, long halDeviceId, IBinder token,
            BiometricServiceBase.ServiceListener listener, int biometricId, int groupId, int userId,
            boolean restricted, String owner, BiometricUtils utils) {
        super(context, metrics, daemon, halDeviceId, token, listener, userId, groupId, restricted,
                owner, 0 /* cookie */);
        mBiometricId = biometricId;
        mBiometricUtils = utils;
    }

    @Override
    protected int statsAction() {
        return BiometricsProtoEnums.ACTION_REMOVE;
    }

    @Override
    public int start() {
        // The biometric template ids will be removed when we get confirmation from the HAL
        try {
            final int result = getDaemonWrapper().remove(getGroupId(), mBiometricId);
            if (result != 0) {
                Slog.w(getLogTag(), "startRemove with id = " + mBiometricId + " failed, result=" +
                        result);
                mMetricsLogger.histogram(mMetrics.tagRemoveStartError(), result);
                onError(getHalDeviceId(), BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE,
                        0 /* vendorCode */);
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
    private boolean sendRemoved(BiometricAuthenticator.Identifier identifier,
            int remaining) {
        try {
            getListener().onRemoved(identifier, remaining);
        } catch (RemoteException e) {
            Slog.w(getLogTag(), "Failed to notify Removed:", e);
        }
        return remaining == 0;
    }

    @Override
    public boolean onRemoved(BiometricAuthenticator.Identifier identifier, int remaining) {
        if (identifier.getBiometricId() != 0) {
            mBiometricUtils.removeBiometricForUser(getContext(), getTargetUserId(),
                    identifier.getBiometricId());
        }
        return sendRemoved(identifier, remaining);
    }

    @Override
    public boolean onEnrollResult(BiometricAuthenticator.Identifier identifier, int rem) {
        if (DEBUG) Slog.w(getLogTag(), "onEnrollResult() called for remove!");
        return true; // Invalid for Remove
    }

    @Override
    public boolean onAuthenticated(BiometricAuthenticator.Identifier identifier,
            boolean authenticated, ArrayList<Byte> token) {
        if (DEBUG) Slog.w(getLogTag(), "onAuthenticated() called for remove!");
        return true; // Invalid for Remove.
    }

    @Override
    public boolean onEnumerationResult(BiometricAuthenticator.Identifier identifier,
            int remaining) {
        if (DEBUG) Slog.w(getLogTag(), "onEnumerationResult() called for remove!");
        return true; // Invalid for Remove.
    }
}
