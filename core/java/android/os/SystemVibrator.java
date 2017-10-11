/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.os;

import android.content.Context;
import android.media.AudioAttributes;
import android.util.Log;

/**
 * Vibrator implementation that controls the main system vibrator.
 *
 * @hide
 */
public class SystemVibrator extends Vibrator {
    private static final String TAG = "Vibrator";

    private final IVibratorService mService;
    private final Binder mToken = new Binder();

    public SystemVibrator() {
        mService = IVibratorService.Stub.asInterface(ServiceManager.getService("vibrator"));
    }

    public SystemVibrator(Context context) {
        super(context);
        mService = IVibratorService.Stub.asInterface(ServiceManager.getService("vibrator"));
    }

    @Override
    public boolean hasVibrator() {
        if (mService == null) {
            Log.w(TAG, "Failed to vibrate; no vibrator service.");
            return false;
        }
        try {
            return mService.hasVibrator();
        } catch (RemoteException e) {
        }
        return false;
    }

    @Override
    public boolean hasAmplitudeControl() {
        if (mService == null) {
            Log.w(TAG, "Failed to check amplitude control; no vibrator service.");
            return false;
        }
        try {
            return mService.hasAmplitudeControl();
        } catch (RemoteException e) {
        }
        return false;
    }

    @Override
    public void vibrate(int uid, String opPkg,
            VibrationEffect effect, AudioAttributes attributes) {
        if (mService == null) {
            Log.w(TAG, "Failed to vibrate; no vibrator service.");
            return;
        }
        try {
            mService.vibrate(uid, opPkg, effect, usageForAttributes(attributes), mToken);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to vibrate.", e);
        }
    }

    private static int usageForAttributes(AudioAttributes attributes) {
        return attributes != null ? attributes.getUsage() : AudioAttributes.USAGE_UNKNOWN;
    }

    @Override
    public void cancel() {
        if (mService == null) {
            return;
        }
        try {
            mService.cancelVibrate(mToken);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to cancel vibration.", e);
        }
    }
}
