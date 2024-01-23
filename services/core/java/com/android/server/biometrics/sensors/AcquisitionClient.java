/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.biometrics.sensors;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.biometrics.BiometricConstants;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Slog;

import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;

import java.util.function.Supplier;

/**
 * Abstract {@link HalClientMonitor} subclass that operations eligible/interested in acquisition
 * messages should extend.
 */
public abstract class AcquisitionClient<T> extends HalClientMonitor<T> implements ErrorConsumer {

    private static final String TAG = "Biometrics/AcquisitionClient";

    private static final VibrationAttributes HARDWARE_FEEDBACK_VIBRATION_ATTRIBUTES =
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK);

    private static final VibrationEffect SUCCESS_VIBRATION_EFFECT =
            VibrationEffect.get(VibrationEffect.EFFECT_CLICK);
    private static final VibrationEffect ERROR_VIBRATION_EFFECT =
            VibrationEffect.get(VibrationEffect.EFFECT_DOUBLE_CLICK);

    private final PowerManager mPowerManager;
    // If haptics should occur when auth result (success/reject) is known
    protected final boolean mShouldVibrate;
    private boolean mShouldSendErrorToClient = true;
    private boolean mAlreadyCancelled;

    public AcquisitionClient(@NonNull Context context, @NonNull Supplier<T> lazyDaemon,
            @NonNull IBinder token, @NonNull ClientMonitorCallbackConverter listener, int userId,
            @NonNull String owner, int cookie, int sensorId, boolean shouldVibrate,
            @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext) {
        super(context, lazyDaemon, token, listener, userId, owner, cookie, sensorId,
                logger, biometricContext);
        mPowerManager = context.getSystemService(PowerManager.class);
        mShouldVibrate = shouldVibrate;
    }

    /**
     * Stops the HAL operation specific to the ClientMonitor subclass.
     */
    protected abstract void stopHalOperation();

    @Override
    public void unableToStart() {
        try {
            getListener().onError(getSensorId(), getCookie(),
                    BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE, 0 /* vendorCode */);
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to send error", e);
        }
    }

    @Override
    public void onError(int errorCode, int vendorCode) {
        // Errors from the HAL always finish the client
        onErrorInternal(errorCode, vendorCode, true /* finish */);
    }

    /**
     * Notifies the caller that the operation was canceled by the user. Note that the actual
     * operation still needs to wait for the HAL to send ERROR_CANCELED.
     */
    public void onUserCanceled() {
        Slog.d(TAG, "onUserCanceled");

        // Send USER_CANCELED, but do not finish. Wait for the HAL to respond with ERROR_CANCELED,
        // which then finishes the AcquisitionClient's lifecycle.
        onErrorInternal(BiometricConstants.BIOMETRIC_ERROR_USER_CANCELED, 0 /* vendorCode */,
                false /* finish */);
        stopHalOperation();
    }

    protected void onErrorInternal(int errorCode, int vendorCode, boolean finish) {
        Slog.d(TAG, "onErrorInternal code: " + errorCode + ", finish: " + finish);

        // In some cases, the framework will send an error to the caller before a true terminal
        // case (success, failure, or error) is received from the HAL (e.g. versions of fingerprint
        // that do not handle lockout under the HAL. In these cases, ensure that the framework only
        // sends errors once per ClientMonitor.
        if (mShouldSendErrorToClient) {
            getLogger().logOnError(getContext(), getOperationContext(),
                    errorCode, vendorCode, getTargetUserId());
            try {
                mShouldSendErrorToClient = false;
                getListener().onError(getSensorId(), getCookie(), errorCode, vendorCode);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to invoke sendError", e);
            }
        }

        if (finish) {
            if (mCallback == null) {
                Slog.e(TAG, "Callback is null, perhaps the client hasn't been started yet?");
            } else {
                mCallback.onClientFinished(this, false /* success */);
            }
        }
    }

    @Override
    public void cancel() {
        if (mAlreadyCancelled) {
            Slog.w(TAG, "Cancel was already requested");
            return;
        }

        stopHalOperation();
        mAlreadyCancelled = true;
    }

    @Override
    public void cancelWithoutStarting(@NonNull ClientMonitorCallback callback) {
        Slog.d(TAG, "cancelWithoutStarting: " + this);

        final int errorCode = BiometricConstants.BIOMETRIC_ERROR_CANCELED;
        try {
            getListener().onError(getSensorId(), getCookie(), errorCode, 0 /* vendorCode */);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to invoke sendError", e);
        }
        callback.onClientFinished(this, true /* success */);
    }

    /**
     * Called when we get notification from the biometric's HAL that an image has been acquired.
     * Common to authenticate and enroll.
     * @param acquiredInfo info about the current image acquisition
     */
    public void onAcquired(int acquiredInfo, int vendorCode) {
        // Default is to always send acquire messages to clients.
        onAcquiredInternal(acquiredInfo, vendorCode, true /* shouldSend */);
    }

    protected final void onAcquiredInternal(int acquiredInfo, int vendorCode,
            boolean shouldSend) {
        getLogger().logOnAcquired(getContext(), getOperationContext(),
                acquiredInfo, vendorCode, getTargetUserId());
        if (DEBUG) {
            Slog.v(TAG, "Acquired: " + acquiredInfo + " " + vendorCode
                    + ", shouldSend: " + shouldSend);
        }

        // Good scans will keep the device awake
        if (acquiredInfo == BiometricConstants.BIOMETRIC_ACQUIRED_GOOD) {
            notifyUserActivity();
        }

        try {
            if (shouldSend) {
                getListener().onAcquired(getSensorId(), acquiredInfo, vendorCode);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to invoke sendAcquired", e);
            mCallback.onClientFinished(this, false /* success */);
        }
    }

    final void notifyUserActivity() {
        long now = SystemClock.uptimeMillis();
        mPowerManager.userActivity(now, PowerManager.USER_ACTIVITY_EVENT_TOUCH, 0);
    }

    protected final void vibrateSuccess() {
        Vibrator vibrator = getContext().getSystemService(Vibrator.class);
        if (vibrator != null && mShouldVibrate) {
            vibrator.vibrate(Process.myUid(),
                    getContext().getOpPackageName(),
                    SUCCESS_VIBRATION_EFFECT,
                    getClass().getSimpleName() + "::success",
                    HARDWARE_FEEDBACK_VIBRATION_ATTRIBUTES);
        }
    }

    @Override
    public boolean isInterruptable() {
        return true;
    }

    public boolean isAlreadyCancelled() {
        return mAlreadyCancelled;
    }
}
