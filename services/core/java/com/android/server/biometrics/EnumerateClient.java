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
 * A class to keep track of the enumeration state for a given client.
 */
public abstract class EnumerateClient extends ClientMonitor {
    public EnumerateClient(Context context, Constants constants,
            BiometricServiceBase.DaemonWrapper daemon, long halDeviceId, IBinder token,
            BiometricServiceBase.ServiceListener listener, int groupId, int userId,
            boolean restricted, String owner) {
        super(context, constants, daemon, halDeviceId, token, listener, userId, groupId, restricted,
                owner, 0 /* cookie */);
    }

    @Override
    public void notifyUserActivity() {
    }

    @Override
    protected int statsAction() {
        return BiometricsProtoEnums.ACTION_ENUMERATE;
    }

    @Override
    public int start() {
        // The biometric template ids will be removed when we get confirmation from the HAL
        try {
            final int result = getDaemonWrapper().enumerate();
            if (result != 0) {
                Slog.w(getLogTag(), "start enumerate for user " + getTargetUserId()
                    + " failed, result=" + result);
                mMetricsLogger.histogram(mConstants.tagEnumerateStartError(), result);
                onError(getHalDeviceId(), BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE,
                        0 /* vendorCode */);
                return result;
            }
        } catch (RemoteException e) {
            Slog.e(getLogTag(), "startEnumeration failed", e);
        }
        return 0;
    }

    @Override
    public int stop(boolean initiatedByClient) {
        if (mAlreadyCancelled) {
            Slog.w(getLogTag(), "stopEnumerate: already cancelled!");
            return 0;
        }

        try {
            final int result = getDaemonWrapper().cancel();
            if (result != 0) {
                Slog.w(getLogTag(), "stop enumeration failed, result=" + result);
                return result;
            }
        } catch (RemoteException e) {
            Slog.e(getLogTag(), "stopEnumeration failed", e);
            return ERROR_ESRCH;
        }

        // We don't actually stop enumerate, but inform the client that the cancel operation
        // succeeded so we can start the next operation.
        if (initiatedByClient) {
            onError(getHalDeviceId(), BiometricConstants.BIOMETRIC_ERROR_CANCELED,
                    0 /* vendorCode */);
        }
        mAlreadyCancelled = true;
        return 0; // success
    }

    @Override
    public boolean onEnumerationResult(BiometricAuthenticator.Identifier identifier,
            int remaining) {
        try {
            if (getListener() != null) {
                getListener().onEnumerated(identifier, remaining);
            }
        } catch (RemoteException e) {
            Slog.w(getLogTag(), "Failed to notify enumerated:", e);
        }
        return remaining == 0;
    }

    @Override
    public boolean onAuthenticated(BiometricAuthenticator.Identifier identifier,
            boolean authenticated, ArrayList<Byte> token) {
        if (DEBUG) Slog.w(getLogTag(), "onAuthenticated() called for enumerate!");
        return true; // Invalid for Enumerate.
    }

    @Override
    public boolean onEnrollResult(BiometricAuthenticator.Identifier identifier, int rem) {
        if (DEBUG) Slog.w(getLogTag(), "onEnrollResult() called for enumerate!");
        return true; // Invalid for Enumerate.
    }

    @Override
    public boolean onRemoved(BiometricAuthenticator.Identifier identifier, int remaining) {
        if (DEBUG) Slog.w(getLogTag(), "onRemoved() called for enumerate!");
        return true; // Invalid for Enumerate.
    }
}
