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

package com.android.server.vibrator;

import android.annotation.NonNull;
import android.os.CombinedVibration;
import android.os.VibrationEffect;
import android.os.VibratorInfo;
import android.os.vibrator.VibrationEffectSegment;
import android.util.Slog;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Adapts a {@link CombinedVibration} to a device by transforming each {@link VibrationEffect} to
 * the available device vibrator capabilities defined by {@link VibratorInfo}.
 */
final class DeviceAdapter implements CombinedVibration.VibratorAdapter {
    private static final String TAG = "DeviceAdapter";

    /**
     * The VibratorController.getInfo might trigger HAL method calls, so just keep a reference to
     * the system controllers until the adaptor is triggered by the VibrationThread.
     */
    private final SparseArray<VibratorController> mAvailableVibrators;
    private final int[] mAvailableVibratorIds;

    /**
     * The actual adapters that can replace VibrationEffectSegment entries from a list based on the
     * VibratorInfo. They can be applied in a chain to a mutable list before a new VibrationEffect
     * instance is created with the final segment list.
     */
    private final List<VibrationSegmentsAdapter> mSegmentAdapters;

    DeviceAdapter(VibrationSettings settings, SparseArray<VibratorController> vibrators) {
        mSegmentAdapters = Arrays.asList(
                // TODO(b/167947076): add filter that removes unsupported primitives
                // TODO(b/167947076): add filter that replaces unsupported prebaked with fallback
                // Convert segments based on device capabilities
                new RampToStepAdapter(settings.getRampStepDuration()),
                new StepToRampAdapter(),
                // Add extra ramp down segments as needed
                new RampDownAdapter(settings.getRampDownDuration(), settings.getRampStepDuration()),
                // Split segments based on their duration and device supported limits
                new SplitSegmentsAdapter(),
                // Clip amplitudes and frequencies of final segments based on device bandwidth curve
                new ClippingAmplitudeAndFrequencyAdapter()
        );
        mAvailableVibrators = vibrators;
        mAvailableVibratorIds = new int[vibrators.size()];
        for (int i = 0; i < vibrators.size(); i++) {
            mAvailableVibratorIds[i] = vibrators.keyAt(i);
        }
    }

    SparseArray<VibratorController> getAvailableVibrators() {
        return mAvailableVibrators;
    }

    @Override
    public int[] getAvailableVibratorIds() {
        return mAvailableVibratorIds;
    }

    @NonNull
    @Override
    public VibrationEffect adaptToVibrator(int vibratorId, @NonNull VibrationEffect effect) {
        if (!(effect instanceof VibrationEffect.Composed)) {
            // Segments adapters can only apply to Composed effects.
            Slog.wtf(TAG, "Error adapting unsupported vibration effect: " + effect);
            return effect;
        }

        VibratorController controller = mAvailableVibrators.get(vibratorId);
        if (controller == null) {
            // Effect mapped to nonexistent vibrator, skip adapter.
            return effect;
        }

        VibratorInfo info = controller.getVibratorInfo();
        VibrationEffect.Composed composed = (VibrationEffect.Composed) effect;
        List<VibrationEffectSegment> newSegments = new ArrayList<>(composed.getSegments());
        int newRepeatIndex = composed.getRepeatIndex();

        int adapterCount = mSegmentAdapters.size();
        for (int i = 0; i < adapterCount; i++) {
            newRepeatIndex =
                    mSegmentAdapters.get(i).adaptToVibrator(info, newSegments, newRepeatIndex);
        }

        return new VibrationEffect.Composed(newSegments, newRepeatIndex);
    }
}
