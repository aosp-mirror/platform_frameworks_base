/*
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

package com.android.systemui.statusbar;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.media.AudioAttributes;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 */
@SysUISingleton
public class VibratorHelper {

    private final Vibrator mVibrator;
    public static final VibrationAttributes TOUCH_VIBRATION_ATTRIBUTES =
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH);
    private final Executor mExecutor;

    /**
     */
    @Inject
    public VibratorHelper(@Nullable Vibrator vibrator, @Background Executor executor) {
        mExecutor = executor;
        mVibrator = vibrator;
    }

    /**
     * @see Vibrator#vibrate(long)
     */
    public void vibrate(final int effectId) {
        if (!hasVibrator()) {
            return;
        }
        mExecutor.execute(() ->
                mVibrator.vibrate(VibrationEffect.get(effectId, false /* fallback */),
                        TOUCH_VIBRATION_ATTRIBUTES));
    }

    /**
     * @see Vibrator#vibrate(int, String, VibrationEffect, String, VibrationAttributes)
     */
    public void vibrate(int uid, String opPkg, @NonNull VibrationEffect vibe,
            String reason, @NonNull VibrationAttributes attributes) {
        if (!hasVibrator()) {
            return;
        }
        mExecutor.execute(() -> mVibrator.vibrate(uid, opPkg, vibe, reason, attributes));
    }

    /**
     * @see Vibrator#vibrate(VibrationEffect, AudioAttributes)
     */
    public void vibrate(@NonNull VibrationEffect effect, @NonNull AudioAttributes attributes) {
        if (!hasVibrator()) {
            return;
        }
        mExecutor.execute(() -> mVibrator.vibrate(effect, attributes));
    }

    /**
     * @see Vibrator#vibrate(VibrationEffect)
     */
    public void vibrate(@NotNull VibrationEffect effect) {
        if (!hasVibrator()) {
            return;
        }
        mExecutor.execute(() -> mVibrator.vibrate(effect));
    }

    /**
     * @see Vibrator#hasVibrator()
     */
    public boolean hasVibrator() {
        return mVibrator != null && mVibrator.hasVibrator();
    }

    /**
     * @see Vibrator#cancel()
     */
    public void cancel() {
        if (!hasVibrator()) {
            return;
        }
        mExecutor.execute(mVibrator::cancel);
    }
}
