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

import android.hardware.biometrics.face.V1_0.IBiometricsFace;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import android.content.Context;
import android.hardware.face.Face;
import android.hardware.face.FaceManager;
import android.hardware.face.IFaceServiceReceiver;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

/**
 * A class to keep track of the authentication state for a given client.
 */
public abstract class AuthenticationClient extends ClientMonitor {
    private long mOpId;

    public abstract int handleFailedAttempt();
    public abstract void resetFailedAttempts();

    public static final int LOCKOUT_NONE = 0;
    public static final int LOCKOUT_TIMED = 1;
    public static final int LOCKOUT_PERMANENT = 2;

    public AuthenticationClient(Context context, long halDeviceId, IBinder token,
            IFaceServiceReceiver receiver, int targetUserId, long opId,
            boolean restricted, String owner) {
        super(context, halDeviceId, token, receiver, targetUserId, restricted, owner);
        mOpId = opId;
    }

    @Override
    public boolean onAuthenticated(int faceId) {
        boolean result = false;
        boolean authenticated = faceId != 0;

        IFaceServiceReceiver receiver = getReceiver();
        if (receiver != null) {
            try {
                MetricsLogger.action(getContext(), MetricsEvent.ACTION_FINGERPRINT_AUTH,
                        authenticated); // TODO: Define ACTION_FACE_AUTH constant and use here
                if (!authenticated) {
                    receiver.onAuthenticationFailed(getHalDeviceId());
                } else {
                    if (DEBUG) {
                        Slog.v(TAG, "onAuthenticated(owner=" + getOwnerString());
                    }
                    Face face = !getIsRestricted()
                            ? new Face("" /* TODO */, faceId, getHalDeviceId())
                            : null;
                    receiver.onAuthenticationSucceeded(getHalDeviceId(), face);
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to notify Authenticated:", e);
                result = true; // client failed
            }
        } else {
            result = true; // client not listening
        }
        if (!authenticated) {
            // allow system-defined limit of number of attempts before giving up
            int lockoutMode =  handleFailedAttempt();
            if (lockoutMode != LOCKOUT_NONE) {
                try {
                    Slog.w(TAG, "Forcing lockout (face authentication driver code should do " +
                            "this!), mode(" + lockoutMode + ")");
                    stop(false);
                    int errorCode = lockoutMode == LOCKOUT_TIMED ?
                            FaceManager.FACE_ERROR_LOCKOUT :
                            FaceManager.FACE_ERROR_LOCKOUT_PERMANENT;
                    receiver.onError(getHalDeviceId(), errorCode, 0 /* vendorCode */);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to notify lockout:", e);
                }
            }
            result |= lockoutMode != LOCKOUT_NONE; // in a lockout mode
        } else {
            if (receiver != null) {
                vibrateSuccess();
            }
            result |= true; // we have a valid face, done
            resetFailedAttempts();
        }
        return result;
    }

    /**
     * Start authentication
     */
    @Override
    public int start() {
        IBiometricsFace daemon = getFaceDaemon();
        if (daemon == null) {
            Slog.w(TAG, "start authentication: no face HAL!");
            return ERROR_ESRCH;
        }
        try {
            final int result = daemon.authenticate(mOpId);
            if (result != 0) {
                Slog.w(TAG, "startAuthentication failed, result=" + result);
                MetricsLogger.histogram(getContext(), "faced_auth_start_error", result);
                onError(FaceManager.FACE_ERROR_HW_UNAVAILABLE, 0 /* vendorCode */);
                return result;
            }
            if (DEBUG) Slog.w(TAG, "client " + getOwnerString() + " is authenticating...");
        } catch (RemoteException e) {
            Slog.e(TAG, "startAuthentication failed", e);
            return ERROR_ESRCH;
        }
        return 0; // success
    }

    @Override
    public int stop(boolean initiatedByClient) {
        if (mAlreadyCancelled) {
            Slog.w(TAG, "stopAuthentication: already cancelled!");
            return 0;
        }
        IBiometricsFace daemon = getFaceDaemon();
        if (daemon == null) {
            Slog.w(TAG, "stopAuthentication: no face HAL!");
            return ERROR_ESRCH;
        }
        try {
            final int result = daemon.cancel();
            if (result != 0) {
                Slog.w(TAG, "stopAuthentication failed, result=" + result);
                return result;
            }
            if (DEBUG) Slog.w(TAG, "client " + getOwnerString() + " is no longer authenticating");
        } catch (RemoteException e) {
            Slog.e(TAG, "stopAuthentication failed", e);
            return ERROR_ESRCH;
        }
        mAlreadyCancelled = true;
        return 0; // success
    }

    @Override
    public boolean onEnrollResult(int faceId, int remaining) {
        if (DEBUG) Slog.w(TAG, "onEnrollResult() called for authenticate!");
        return true; // Invalid for Authenticate
    }

    @Override
    public boolean onRemoved(int faceId, int remaining) {
        if (DEBUG) Slog.w(TAG, "onRemoved() called for authenticate!");
        return true; // Invalid for Authenticate
    }
}
