/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.notification;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Resources;
import android.media.AudioAttributes;
import android.os.Process;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Slog;

import com.android.internal.R;
import com.android.server.pm.PackageManagerService;

import java.util.Arrays;

/**
 * NotificationManagerService helper for functionality related to the vibrator.
 */
public final class VibratorHelper {
    private static final String TAG = "NotificationVibratorHelper";

    private static final long[] DEFAULT_VIBRATE_PATTERN = {0, 250, 250, 250};
    private static final int VIBRATE_PATTERN_MAXLEN = 8 * 2 + 1; // up to eight bumps

    private final Vibrator mVibrator;
    private final long[] mDefaultPattern;
    private final long[] mFallbackPattern;

    public VibratorHelper(Context context) {
        mVibrator = context.getSystemService(Vibrator.class);
        mDefaultPattern = getLongArray(
                context.getResources(),
                com.android.internal.R.array.config_defaultNotificationVibePattern,
                VIBRATE_PATTERN_MAXLEN,
                DEFAULT_VIBRATE_PATTERN);
        mFallbackPattern = getLongArray(context.getResources(),
                R.array.config_notificationFallbackVibePattern,
                VIBRATE_PATTERN_MAXLEN,
                DEFAULT_VIBRATE_PATTERN);
    }

    /**
     * Safely create a {@link VibrationEffect} from given vibration {@code pattern}.
     *
     * <p>This method returns {@code null} if the pattern is also {@code null} or invalid.
     *
     * @param pattern The off/on vibration pattern, where each item is a duration in milliseconds.
     * @param insistent {@code true} if the vibration should loop until it is cancelled.
     */
    @Nullable
    public static VibrationEffect createWaveformVibration(@Nullable long[] pattern,
            boolean insistent) {
        try {
            if (pattern != null) {
                return VibrationEffect.createWaveform(pattern, /* repeat= */ insistent ? 0 : -1);
            }
        } catch (IllegalArgumentException e) {
            Slog.e(TAG, "Error creating vibration waveform with pattern: "
                    + Arrays.toString(pattern));
        }
        return null;
    }

    /**
     * Vibrate the device with given {@code effect}.
     *
     * <p>We need to vibrate as "android" so we can breakthrough DND.
     */
    public void vibrate(VibrationEffect effect, AudioAttributes attrs, String reason) {
        mVibrator.vibrate(Process.SYSTEM_UID, PackageManagerService.PLATFORM_PACKAGE_NAME,
                effect, reason, attrs);
    }

    /** Stop all notification vibrations (ringtone, alarm, notification usages). */
    public void cancelVibration() {
        int usageFilter =
                VibrationAttributes.USAGE_CLASS_ALARM | ~VibrationAttributes.USAGE_CLASS_MASK;
        mVibrator.cancel(usageFilter);
    }

    /**
     * Creates a vibration to be used as fallback when the device is in vibrate mode.
     *
     * @param insistent {@code true} if the vibration should loop until it is cancelled.
     */
    public VibrationEffect createFallbackVibration(boolean insistent) {
        if (mVibrator.hasFrequencyControl()) {
            return createChirpVibration(insistent);
        }
        return createWaveformVibration(mFallbackPattern, insistent);
    }

    /**
     * Creates a vibration to be used by notifications without a custom pattern.
     *
     * @param insistent {@code true} if the vibration should loop until it is cancelled.
     */
    public VibrationEffect createDefaultVibration(boolean insistent) {
        if (mVibrator.hasFrequencyControl()) {
            return createChirpVibration(insistent);
        }
        return createWaveformVibration(mDefaultPattern, insistent);
    }

    private static VibrationEffect createChirpVibration(boolean insistent) {
        VibrationEffect.WaveformBuilder waveformBuilder = VibrationEffect.startWaveform()
                .addStep(/* amplitude= */ 0, /* frequency= */ -0.85f, /* duration= */ 0)
                .addRamp(/* amplitude= */ 1, /* frequency= */ -0.25f, /* duration= */ 100)
                .addStep(/* amplitude= */ 1, /* duration= */ 150)
                .addRamp(/* amplitude= */ 0, /* frequency= */ -0.85f, /* duration= */ 250);

        if (insistent) {
            return waveformBuilder.build(/* repeat= */ 0);
        }

        VibrationEffect singleBeat = waveformBuilder.build();
        return VibrationEffect.startComposition()
                .addEffect(singleBeat)
                .addEffect(singleBeat)
                .compose();
    }

    private static long[] getLongArray(Resources resources, int resId, int maxLength, long[] def) {
        int[] ar = resources.getIntArray(resId);
        if (ar == null) {
            return def;
        }
        final int len = ar.length > maxLength ? maxLength : ar.length;
        long[] out = new long[len];
        for (int i = 0; i < len; i++) {
            out[i] = ar[i];
        }
        return out;
    }
}
