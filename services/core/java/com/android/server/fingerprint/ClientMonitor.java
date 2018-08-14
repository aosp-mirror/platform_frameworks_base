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

import android.Manifest;
import android.content.Context;
import android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprint;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.IFingerprintServiceReceiver;
import android.media.AudioAttributes;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Slog;

import java.util.NoSuchElementException;

/**
 * Abstract base class for keeping track and dispatching events from fingerprint HAL to the
 * the current client.  Subclasses are responsible for coordinating the interaction with
 * fingerprint HAL for the specific action (e.g. authenticate, enroll, enumerate, etc.).
 */
public abstract class ClientMonitor implements IBinder.DeathRecipient {
    protected static final String TAG = FingerprintService.TAG; // TODO: get specific name
    protected static final int ERROR_ESRCH = 3; // Likely fingerprint HAL is dead. See errno.h.
    protected static final boolean DEBUG = FingerprintService.DEBUG;
    private static final long[] DEFAULT_SUCCESS_VIBRATION_PATTERN = new long[] {0, 30};
    private static final AudioAttributes FINGERPRINT_SONFICATION_ATTRIBUTES =
            new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .build();
    private final Context mContext;
    private final long mHalDeviceId;
    private final int mTargetUserId;
    private final int mGroupId;
    // True if client does not have MANAGE_FINGERPRINT permission
    private final boolean mIsRestricted;
    private final String mOwner;
    private final VibrationEffect mSuccessVibrationEffect;
    private final VibrationEffect mErrorVibrationEffect;
    private IBinder mToken;
    private IFingerprintServiceReceiver mReceiver;
    protected boolean mAlreadyCancelled;

    /**
     * @param context context of FingerprintService
     * @param halDeviceId the HAL device ID of the associated fingerprint hardware
     * @param token a unique token for the client
     * @param receiver recipient of related events (e.g. authentication)
     * @param userId target user id for operation
     * @param groupId groupId for the fingerprint set
     * @param restricted whether or not client has the {@link Manifest#MANAGE_FINGERPRINT}
     * permission
     * @param owner name of the client that owns this
     */
    public ClientMonitor(Context context, long halDeviceId, IBinder token,
            IFingerprintServiceReceiver receiver, int userId, int groupId,boolean restricted,
            String owner) {
        mContext = context;
        mHalDeviceId = halDeviceId;
        mToken = token;
        mReceiver = receiver;
        mTargetUserId = userId;
        mGroupId = groupId;
        mIsRestricted = restricted;
        mOwner = owner;
        mSuccessVibrationEffect = VibrationEffect.get(VibrationEffect.EFFECT_CLICK);
        mErrorVibrationEffect = VibrationEffect.get(VibrationEffect.EFFECT_DOUBLE_CLICK);
        try {
            if (token != null) {
                token.linkToDeath(this, 0);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "caught remote exception in linkToDeath: ", e);
        }
    }

    /**
     * Contacts fingerprint HAL to start the client.
     * @return 0 on success, errno from driver on failure
     */
    public abstract int start();

    /**
     * Contacts fingerprint HAL to stop the client.
     * @param initiatedByClient whether the operation is at the request of a client
     */
    public abstract int stop(boolean initiatedByClient);

    /**
     * Method to explicitly poke powermanager on events
     */
    public abstract void notifyUserActivity();

    /**
     * Gets the fingerprint daemon from the cached state in the container class.
     */
    public abstract IBiometricsFingerprint getFingerprintDaemon();

    // Event callbacks from driver. Inappropriate calls is flagged/logged by the
    // respective client (e.g. enrolling shouldn't get authenticate events).
    // All of these return 'true' if the operation is completed and it's ok to move
    // to the next client (e.g. authentication accepts or rejects a fingerprint).
    public abstract boolean onEnrollResult(int fingerId, int groupId, int rem);
    public abstract boolean onAuthenticated(int fingerId, int groupId);
    public abstract boolean onRemoved(int fingerId, int groupId, int remaining);
    public abstract boolean onEnumerationResult(int fingerId, int groupId, int remaining);

    /**
     * Called when we get notification from fingerprint HAL that an image has been acquired.
     * Common to authenticate and enroll.
     * @param acquiredInfo info about the current image acquisition
     * @return true if client should be removed
     */
    public boolean onAcquired(int acquiredInfo, int vendorCode) {
        if (mReceiver == null)
            return true; // client not connected
        try {
            mReceiver.onAcquired(getHalDeviceId(), acquiredInfo, vendorCode);
            return false; // acquisition continues...
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to invoke sendAcquired:", e);
            return true; // client failed
        } finally {
            // Good scans will keep the device awake
            if (acquiredInfo == FingerprintManager.FINGERPRINT_ACQUIRED_GOOD) {
                notifyUserActivity();
            }
        }
    }

    /**
     * Called when we get notification from fingerprint HAL that an error has occurred with the
     * current operation. Common to authenticate, enroll, enumerate and remove.
     * @param error
     * @return true if client should be removed
     */
    public boolean onError(int error, int vendorCode) {
        if (mReceiver != null) {
            try {
                mReceiver.onError(getHalDeviceId(), error, vendorCode);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to invoke sendError:", e);
            }
        }
        return true; // errors always remove current client
    }

    public void destroy() {
        if (mToken != null) {
            try {
                mToken.unlinkToDeath(this, 0);
            } catch (NoSuchElementException e) {
                // TODO: remove when duplicate call bug is found
                Slog.e(TAG, "destroy(): " + this + ":", new Exception("here"));
            }
            mToken = null;
        }
        mReceiver = null;
    }

    @Override
    public void binderDied() {
        mToken = null;
        mReceiver = null;
        onError(FingerprintManager.FINGERPRINT_ERROR_HW_UNAVAILABLE, 0 /* vendorCode */);
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mToken != null) {
                if (DEBUG) Slog.w(TAG, "removing leaked reference: " + mToken);
                onError(FingerprintManager.FINGERPRINT_ERROR_HW_UNAVAILABLE, 0 /* vendorCode */);
            }
        } finally {
            super.finalize();
        }
    }

    public final Context getContext() {
        return mContext;
    }

    public final long getHalDeviceId() {
        return mHalDeviceId;
    }

    public final String getOwnerString() {
        return mOwner;
    }

    public final IFingerprintServiceReceiver getReceiver() {
        return mReceiver;
    }

    public final boolean getIsRestricted() {
        return mIsRestricted;
    }

    public final int getTargetUserId() {
        return mTargetUserId;
    }

    public final int getGroupId() {
        return mGroupId;
    }

    public final IBinder getToken() {
        return mToken;
    }

    public final void vibrateSuccess() {
        Vibrator vibrator = mContext.getSystemService(Vibrator.class);
        if (vibrator != null) {
            vibrator.vibrate(mSuccessVibrationEffect, FINGERPRINT_SONFICATION_ATTRIBUTES);
        }
    }

    public final void vibrateError() {
        Vibrator vibrator = mContext.getSystemService(Vibrator.class);
        if (vibrator != null) {
            vibrator.vibrate(mErrorVibrationEffect, FINGERPRINT_SONFICATION_ATTRIBUTES);
        }
    }
}
