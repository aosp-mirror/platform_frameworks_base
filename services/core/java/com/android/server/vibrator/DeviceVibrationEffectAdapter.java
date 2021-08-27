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

package com.android.server.vibrator;

import android.os.VibrationEffect;
import android.os.VibratorInfo;

import java.util.Arrays;
import java.util.List;

/** Adapts a {@link VibrationEffect} to a specific device, taking into account its capabilities. */
final class DeviceVibrationEffectAdapter
        implements VibrationEffectAdapters.EffectAdapter<VibratorInfo> {

    private final List<VibrationEffectAdapters.SegmentsAdapter<VibratorInfo>> mSegmentAdapters;

    DeviceVibrationEffectAdapter(VibrationSettings settings) {
        mSegmentAdapters = Arrays.asList(
                // TODO(b/167947076): add filter that removes unsupported primitives
                // TODO(b/167947076): add filter that replaces unsupported prebaked with fallback
                new RampToStepAdapter(settings.getRampStepDuration()),
                new StepToRampAdapter(),
                new RampDownAdapter(settings.getRampDownDuration(), settings.getRampStepDuration()),
                new ClippingAmplitudeAndFrequencyAdapter()
        );
    }

    @Override
    public VibrationEffect apply(VibrationEffect effect, VibratorInfo info) {
        return VibrationEffectAdapters.apply(effect, mSegmentAdapters, info);
    }
}
