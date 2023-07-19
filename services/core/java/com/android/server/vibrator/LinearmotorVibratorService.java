/*
 * Copyright (C) 2022 The Nameless-AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.vibrator;

import android.content.Context;
import android.os.Binder;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Slog;

import com.android.server.SystemService;

import com.oplus.os.ILinearmotorVibratorService;
import com.oplus.os.WaveformEffect;

public class LinearmotorVibratorService extends SystemService {

    private static final String TAG = "LinearmotorVibratorService";

    private static final VibrationEffect EFFECT_CLICK =
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK);

    private final Vibrator mVibrator;

    private final Object mLock = new Object();

    private final ILinearmotorVibratorService.Stub mService = new ILinearmotorVibratorService.Stub() {
        @Override
        public void vibrate(WaveformEffect effect) {
            synchronized (mLock) {
                final long ident = Binder.clearCallingIdentity();
                try {
                    Slog.d(TAG, "WaveformEffect: " + effect);
                    mVibrator.vibrate(EFFECT_CLICK);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }
    };

    public LinearmotorVibratorService(Context context) {
        super(context);
        mVibrator = context.getSystemService(Vibrator.class);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.LINEARMOTOR_VIBRATOR_SERVICE, mService);
    }
}
