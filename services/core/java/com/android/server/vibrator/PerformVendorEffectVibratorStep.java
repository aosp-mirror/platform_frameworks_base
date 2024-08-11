/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.vibrator;

import android.annotation.NonNull;
import android.os.Trace;
import android.os.VibrationEffect;

import java.util.List;

/**
 * Represents a step to turn the vibrator on with a vendor-specific vibration from a
 * {@link VibrationEffect.VendorEffect} effect.
 */
final class PerformVendorEffectVibratorStep extends AbstractVibratorStep {
    /**
     * Timeout to ensure vendor vibrations are not unbounded if vibrator callbacks are lost.
     */
    static final long VENDOR_EFFECT_MAX_DURATION_MS = 60_000; // 1 min

    public final VibrationEffect.VendorEffect effect;

    PerformVendorEffectVibratorStep(VibrationStepConductor conductor, long startTime,
            VibratorController controller, VibrationEffect.VendorEffect effect,
            long pendingVibratorOffDeadline) {
        // This step should wait for the last vibration to finish (with the timeout) and for the
        // intended step start time (to respect the effect delays).
        super(conductor, Math.max(startTime, pendingVibratorOffDeadline), controller,
                pendingVibratorOffDeadline);
        this.effect = effect;
    }

    @NonNull
    @Override
    public List<Step> play() {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "PerformVendorEffectVibratorStep");
        try {
            long vibratorOnResult = controller.on(effect, getVibration().id);
            vibratorOnResult = Math.min(vibratorOnResult, VENDOR_EFFECT_MAX_DURATION_MS);
            handleVibratorOnResult(vibratorOnResult);
            getVibration().stats.reportPerformVendorEffect(vibratorOnResult);
            return List.of(new CompleteEffectVibratorStep(conductor, startTime,
                    /* cancelled= */ false, controller, mPendingVibratorOffDeadline));
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }
}
