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

import android.content.Context;
import android.hardware.biometrics.BiometricConstants;
import android.media.AudioAttributes;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Slog;

/**
 * Abstract {@link ClientMonitor} subclass that operations eligible/interested in acquisition
 * messages should extend.
 */
public abstract class AcquisitionClient extends ClientMonitor {

    private static final String TAG = "Biometrics/AcquisitionClient";

    private static final AudioAttributes VIBRATION_SONFICATION_ATTRIBUTES =
            new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .build();

    private final PowerManager mPowerManager;
    private final VibrationEffect mSuccessVibrationEffect;
    private final VibrationEffect mErrorVibrationEffect;

    AcquisitionClient(Context context, BiometricServiceBase.DaemonWrapper daemon, IBinder token,
            ClientMonitorCallbackConverter listener, int userId, int groupId, boolean restricted,
            String owner, int cookie, int sensorId, int statsModality, int statsAction,
            int statsClient) {
        super(context, daemon, token, listener, userId, groupId, restricted, owner, cookie,
                sensorId,
                statsModality, statsAction, statsClient);
        mPowerManager = context.getSystemService(PowerManager.class);
        mSuccessVibrationEffect = VibrationEffect.get(VibrationEffect.EFFECT_CLICK);
        mErrorVibrationEffect = VibrationEffect.get(VibrationEffect.EFFECT_DOUBLE_CLICK);
    }

    /**
     * Called when we get notification from the biometric's HAL that an image has been acquired.
     * Common to authenticate and enroll.
     * @param acquiredInfo info about the current image acquisition
     * @return true if client should be removed
     */
    public boolean onAcquired(int acquiredInfo, int vendorCode) {
        // Default is to always send acquire messages to clients.
        return onAcquiredInternal(acquiredInfo, vendorCode, true /* shouldSend */);
    }

    protected final boolean onAcquiredInternal(int acquiredInfo, int vendorCode,
            boolean shouldSend) {
        super.logOnAcquired(getContext(), acquiredInfo, vendorCode, getTargetUserId());
        if (DEBUG) {
            Slog.v(TAG, "Acquired: " + acquiredInfo + " " + vendorCode
                    + ", shouldSend: " + shouldSend);
        }

        try {
            if (getListener() != null && shouldSend) {
                getListener().onAcquired(getSensorId(), acquiredInfo, vendorCode);
            }
            return false; // acquisition continues...
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to invoke sendAcquired", e);
            return true;
        } finally {
            // Good scans will keep the device awake
            if (acquiredInfo == BiometricConstants.BIOMETRIC_ACQUIRED_GOOD) {
                notifyUserActivity();
            }
        }
    }

    final void notifyUserActivity() {
        long now = SystemClock.uptimeMillis();
        mPowerManager.userActivity(now, PowerManager.USER_ACTIVITY_EVENT_TOUCH, 0);
    }


    final void vibrateSuccess() {
        Vibrator vibrator = getContext().getSystemService(Vibrator.class);
        if (vibrator != null) {
            vibrator.vibrate(mSuccessVibrationEffect, VIBRATION_SONFICATION_ATTRIBUTES);
        }
    }

    protected final void vibrateError() {
        Vibrator vibrator = getContext().getSystemService(Vibrator.class);
        if (vibrator != null) {
            vibrator.vibrate(mErrorVibrationEffect, VIBRATION_SONFICATION_ATTRIBUTES);
        }
    }
}
