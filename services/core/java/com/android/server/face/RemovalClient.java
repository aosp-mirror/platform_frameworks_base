/**
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
 * limitations under the License.
 */

package com.android.server.face;

import android.content.Context;
import android.hardware.biometrics.face.V1_0.IBiometricsFace;
import android.hardware.face.FaceManager;
import android.hardware.face.IFaceServiceReceiver;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import com.android.internal.logging.MetricsLogger;

/**
 * A class to keep track of the remove state for a given client.
 */
public abstract class RemovalClient extends ClientMonitor {

    public RemovalClient(Context context, long halDeviceId, IBinder token,
            IFaceServiceReceiver receiver, int userId,
            boolean restricted, String owner) {
        super(context, halDeviceId, token, receiver, userId, restricted, owner);
    }

    @Override
    public int start() {
        IBiometricsFace daemon = getFaceDaemon();
        // The face template ids will be removed when we get confirmation from the HAL
        try {
            final int result = daemon.remove(getTargetUserId());
            if (result != 0) {
                Slog.w(TAG, "startRemove failed, result=" + result);
                MetricsLogger.histogram(getContext(), "faced_remove_start_error", result);
                onError(FaceManager.FACE_ERROR_HW_UNAVAILABLE, 0 /* vendorCode */);
                return result;
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "startRemove failed", e);
        }
        return 0;
    }

    @Override
    public int stop(boolean initiatedByClient) {
        if (mAlreadyCancelled) {
            Slog.w(TAG, "stopRemove: already cancelled!");
            return 0;
        }
        IBiometricsFace daemon = getFaceDaemon();
        if (daemon == null) {
            Slog.w(TAG, "stopRemoval: no face HAL!");
            return ERROR_ESRCH;
        }
        try {
            final int result = daemon.cancel();
            if (result != 0) {
                Slog.w(TAG, "stopRemoval failed, result=" + result);
                return result;
            }
            if (DEBUG) Slog.w(TAG, "client " + getOwnerString() + " is no longer removing");
        } catch (RemoteException e) {
            Slog.e(TAG, "stopRemoval failed", e);
            return ERROR_ESRCH;
        }
        mAlreadyCancelled = true;
        return 0; // success
    }

    /*
     * @return true if we're done.
     */
    private boolean sendRemoved(int faceId, int remaining) {
        IFaceServiceReceiver receiver = getReceiver();
        try {
            if (receiver != null) {
                receiver.onRemoved(getHalDeviceId(), faceId, remaining);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to notify Removed:", e);
        }
        return true;
    }

    @Override
    public boolean onRemoved(int faceId, int remaining) {
        FaceUtils.getInstance().removeFaceForUser(getContext(), getTargetUserId());
        return sendRemoved(faceId, remaining);
    }

    @Override
    public boolean onEnrollResult(int faceId, int remaining) {
        if (DEBUG) Slog.w(TAG, "onEnrollResult() called for remove!");
        return true; // Invalid for Remove
    }

    @Override
    public boolean onAuthenticated(int faceId) {
        if (DEBUG) Slog.w(TAG, "onAuthenticated() called for remove!");
        return true; // Invalid for Remove.
    }
}
