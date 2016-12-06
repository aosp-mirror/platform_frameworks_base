/**
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.fingerprint;

import android.content.Context;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.IFingerprintDaemon;
import android.hardware.fingerprint.IFingerprintServiceReceiver;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import com.android.internal.logging.MetricsLogger;

/**
 * A class to keep track of the enumeration state for a given client.
 */
public abstract class EnumerateClient extends ClientMonitor {
    public EnumerateClient(Context context, long halDeviceId, IBinder token,
            IFingerprintServiceReceiver receiver, int userId, int groupId,
            boolean restricted, String owner) {
        super(context, halDeviceId, token, receiver, userId, groupId, restricted, owner);
    }

    @Override
    public int start() {
        IFingerprintDaemon daemon = getFingerprintDaemon();
        // The fingerprint template ids will be removed when we get confirmation from the HAL
        try {
            final int result = daemon.enumerate();
            if (result != 0) {
                Slog.w(TAG, "start enumerate for user " + getTargetUserId()
                    + " failed, result=" + result);
                MetricsLogger.histogram(getContext(), "fingerprintd_enum_start_error", result);
                onError(FingerprintManager.FINGERPRINT_ERROR_HW_UNAVAILABLE);
                return result;
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "startRemove failed", e);
        }
        return 0;
    }

    @Override
    public int stop(boolean initiatedByClient) {
        IFingerprintDaemon daemon = getFingerprintDaemon();
        if (daemon == null) {
            Slog.w(TAG, "stopAuthentication: no fingeprintd!");
            return ERROR_ESRCH;
        }
        try {
            final int result = daemon.cancelEnumeration();
            if (result != 0) {
                Slog.w(TAG, "stop enumeration failed, result=" + result);
                return result;
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "stop enumeration failed", e);
            return ERROR_ESRCH;
        }
        // We don't actually stop enumerate, but inform the client that the cancel operation
        // succeeded so we can start the next operation.
        if (initiatedByClient) {
            onError(FingerprintManager.FINGERPRINT_ERROR_CANCELED);
        }
        return 0; // success
    }

    @Override
    public boolean onEnumerationResult(int fingerId, int groupId) {
        IFingerprintServiceReceiver receiver = getReceiver();
        if (receiver == null)
            return true; // client not listening
        try {
            receiver.onRemoved(getHalDeviceId(), fingerId, groupId);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to notify enumerated:", e);
        }
        return fingerId == 0; // done when id hits 0
    }
}
