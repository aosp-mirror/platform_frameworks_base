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

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;

import android.content.Context;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.IFingerprintDaemon;
import android.hardware.fingerprint.IFingerprintServiceReceiver;
import android.os.IBinder;
import android.os.RemoteException;
import android.system.ErrnoException;
import android.util.Slog;

/**
 * A class to keep track of the authentication state for a given client.
 */
public abstract class AuthenticationClient extends ClientMonitor {
    private long mOpId;

    public abstract boolean handleFailedAttempt();
    public abstract void resetFailedAttempts();

    public AuthenticationClient(Context context, long halDeviceId, IBinder token,
            IFingerprintServiceReceiver receiver, int callingUserId, int groupId, long opId,
            boolean restricted, String owner) {
        super(context, halDeviceId, token, receiver, callingUserId, groupId, restricted, owner);
        mOpId = opId;
    }

    @Override
    public boolean onAuthenticated(int fingerId, int groupId) {
        boolean result = false;
        boolean authenticated = fingerId != 0;

        IFingerprintServiceReceiver receiver = getReceiver();
        if (receiver != null) {
            try {
                MetricsLogger.action(getContext(), MetricsEvent.ACTION_FINGERPRINT_AUTH,
                        authenticated);
                if (!authenticated) {
                    receiver.onAuthenticationFailed(getHalDeviceId());
                } else {
                    if (DEBUG) {
                        Slog.v(TAG, "onAuthenticated(owner=" + getOwnerString()
                                + ", id=" + fingerId + ", gp=" + groupId + ")");
                    }
                    Fingerprint fp = !getIsRestricted()
                            ? new Fingerprint("" /* TODO */, groupId, fingerId, getHalDeviceId())
                            : null;
                    receiver.onAuthenticationSucceeded(getHalDeviceId(), fp);
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to notify Authenticated:", e);
                result = true; // client failed
            }
        } else {
            result = true; // client not listening
        }
        if (!authenticated) {
            if (receiver != null) {
                FingerprintUtils.vibrateFingerprintError(getContext());
            }
            // allow system-defined limit of number of attempts before giving up
            boolean inLockoutMode =  handleFailedAttempt();
            // send lockout event in case driver doesn't enforce it.
            if (inLockoutMode) {
                try {
                    Slog.w(TAG, "Forcing lockout (fp driver code should do this!)");
                    receiver.onError(getHalDeviceId(),
                            FingerprintManager.FINGERPRINT_ERROR_LOCKOUT);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to notify lockout:", e);
                }
            }
            result |= inLockoutMode;
        } else {
            if (receiver != null) {
                FingerprintUtils.vibrateFingerprintSuccess(getContext());
            }
            result |= true; // we have a valid fingerprint, done
            resetFailedAttempts();
        }
        return result;
    }

    /**
     * Start authentication
     */
    @Override
    public int start() {
        IFingerprintDaemon daemon = getFingerprintDaemon();
        if (daemon == null) {
            Slog.w(TAG, "start authentication: no fingeprintd!");
            return ERROR_ESRCH;
        }
        try {
            final int result = daemon.authenticate(mOpId, getGroupId());
            if (result != 0) {
                Slog.w(TAG, "startAuthentication failed, result=" + result);
                onError(FingerprintManager.FINGERPRINT_ERROR_HW_UNAVAILABLE);
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
        IFingerprintDaemon daemon = getFingerprintDaemon();
        if (daemon == null) {
            Slog.w(TAG, "stopAuthentication: no fingeprintd!");
            return ERROR_ESRCH;
        }
        try {
            final int result = daemon.cancelAuthentication();
            if (result != 0) {
                Slog.w(TAG, "stopAuthentication failed, result=" + result);
                return result;
            }
            if (DEBUG) Slog.w(TAG, "client " + getOwnerString() + " is no longer authenticating");
        } catch (RemoteException e) {
            Slog.e(TAG, "stopAuthentication failed", e);
            return ERROR_ESRCH;
        }
        return 0; // success
    }

    @Override
    public boolean onEnrollResult(int fingerId, int groupId, int rem) {
        if (DEBUG) Slog.w(TAG, "onEnrollResult() called for authenticate!");
        return true; // Invalid for Authenticate
    }

    @Override
    public boolean onRemoved(int fingerId, int groupId) {
        if (DEBUG) Slog.w(TAG, "onRemoved() called for authenticate!");
        return true; // Invalid for Authenticate
    }

    @Override
    public boolean onEnumerationResult(int fingerId, int groupId) {
        if (DEBUG) Slog.w(TAG, "onEnumerationResult() called for authenticate!");
        return true; // Invalid for Authenticate
    }
}
