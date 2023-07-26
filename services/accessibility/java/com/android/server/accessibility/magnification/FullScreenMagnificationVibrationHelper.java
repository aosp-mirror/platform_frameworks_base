/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.accessibility.magnification;

import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Context;
import android.os.UserHandle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Class to encapsulate all the logic to fire a vibration when user reaches the screen's left or
 * right edge, when it's in magnification mode.
 */
public class FullScreenMagnificationVibrationHelper {
    private static final long VIBRATION_DURATION_MS = 10L;
    private static final int VIBRATION_AMPLITUDE = VibrationEffect.MAX_AMPLITUDE / 2;

    @Nullable
    private final Vibrator mVibrator;
    private final ContentResolver mContentResolver;
    private final VibrationEffect mVibrationEffect = VibrationEffect.get(
            VibrationEffect.EFFECT_CLICK);
    @VisibleForTesting
    VibrationEffectSupportedProvider mIsVibrationEffectSupportedProvider;

    public FullScreenMagnificationVibrationHelper(Context context) {
        mContentResolver = context.getContentResolver();
        mVibrator = context.getSystemService(Vibrator.class);
        mIsVibrationEffectSupportedProvider =
                () -> mVibrator != null && mVibrator.areAllEffectsSupported(
                        VibrationEffect.EFFECT_CLICK) == Vibrator.VIBRATION_EFFECT_SUPPORT_YES;
    }


    void vibrateIfSettingEnabled() {
        if (mVibrator != null && mVibrator.hasVibrator() && isEdgeHapticSettingEnabled()) {
            if (mIsVibrationEffectSupportedProvider.isVibrationEffectSupported()) {
                mVibrator.vibrate(mVibrationEffect);
            } else {
                mVibrator.vibrate(VibrationEffect.createOneShot(VIBRATION_DURATION_MS,
                        VIBRATION_AMPLITUDE));
            }
        }
    }

    private boolean isEdgeHapticSettingEnabled() {
        return Settings.Secure.getIntForUser(
                mContentResolver,
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_EDGE_HAPTIC_ENABLED,
                0, UserHandle.USER_CURRENT)
                == 1;
    }

    @VisibleForTesting
    interface VibrationEffectSupportedProvider {
        boolean isVibrationEffectSupported();
    }
}

