/*
 * Copyright (C) 2022 The Nameless-AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.oplus.os;

import android.annotation.SystemService;
import android.content.Context;
import android.os.RemoteException;
import android.util.Slog;

/**
 * @hide
 */
@SystemService(Context.LINEARMOTOR_VIBRATOR_SERVICE)
public class LinearmotorVibrator {

    private static final String TAG = "LinearmotorVibrator";

    private final ILinearmotorVibratorService mService;

    public LinearmotorVibrator(Context context, ILinearmotorVibratorService service) {
        mService = service;
        if (service == null) {
            Slog.v(TAG, "ILinearmotorVibratorService was null");
        }
    }

    /**
     * @hide
     */
    public void vibrate(WaveformEffect effect) {
        if (mService == null || effect == null) {
            Slog.d(TAG, "ignore vibrate in favor of invalid params.");
            return;
        }
        try {
            mService.vibrate(effect);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
