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
import android.os.UserHandle;
import android.util.Slog;

/**
 * A class to keep track of the remove state for a given client.
 */
public abstract class RemovalClient extends ClientMonitor {
    private int mFingerId;
    private int mUserIdForRemove;

    public RemovalClient(Context context, long halDeviceId, IBinder token,
            IFingerprintServiceReceiver receiver, int userId, int groupId, int fingerId,
            boolean restricted, String owner) {
        super(context, halDeviceId, token, receiver, userId, groupId, restricted, owner);
        mFingerId = fingerId;
        mUserIdForRemove = userId;
    }

    @Override
    public int start() {
        IFingerprintDaemon daemon = getFingerprintDaemon();
        // The fingerprint template ids will be removed when we get confirmation from the HAL
        try {
            final int result = daemon.remove(mFingerId, getUserId());
            if (result != 0) {
                Slog.w(TAG, "startRemove with id = " + mFingerId + " failed, result=" + result);
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
        // We don't actually stop remove, but inform the client that the cancel operation succeeded
        // so we can start the next operation.
        if (initiatedByClient) {
            onError(FingerprintManager.FINGERPRINT_ERROR_CANCELED);
        }
        return 0;
    }

    /*
     * @return true if we're done.
     */
    private boolean sendRemoved(int fingerId, int groupId) {
        IFingerprintServiceReceiver receiver = getReceiver();
        if (receiver == null)
            return true; // client not listening
        try {
            receiver.onRemoved(getHalDeviceId(), fingerId, groupId);
            return fingerId == 0;
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to notify Removed:", e);
        }
        return false;
    }

    @Override
    public boolean onRemoved(int fingerId, int groupId) {
        if (fingerId != 0) {
            if (fingerId != mFingerId)
            FingerprintUtils.getInstance().removeFingerprintIdForUser(getContext(), fingerId,
                    mUserIdForRemove);
        } else {
            mUserIdForRemove = UserHandle.USER_NULL;
        }
        return sendRemoved(fingerId, getGroupId());
    }

    @Override
    public boolean onEnrollResult(int fingerId, int groupId, int rem) {
        if (DEBUG) Slog.w(TAG, "onEnrollResult() called for remove!");
        return true; // Invalid for Remove
    }

    @Override
    public boolean onAuthenticated(int fingerId, int groupId) {
        if (DEBUG) Slog.w(TAG, "onAuthenticated() called for remove!");
        return true; // Invalid for Remove.
    }

    @Override
    public boolean onEnumerationResult(int fingerId, int groupId) {
        if (DEBUG) Slog.w(TAG, "onEnumerationResult() called for remove!");
        return false; // Invalid for Remove.
    }


}
