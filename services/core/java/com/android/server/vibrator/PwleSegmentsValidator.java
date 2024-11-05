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

import android.hardware.vibrator.IVibrator;
import android.os.VibratorInfo;
import android.os.vibrator.PwleSegment;
import android.os.vibrator.VibrationEffectSegment;

import java.util.List;

/**
 * Validates Pwle segments to ensure they are compatible with the device's capabilities
 * and adhere to frequency constraints.
 *
 * <p>The validator verifies that each segment's start and end frequencies fall within
 * the supported range.
 *
 * <p>The segments will be considered invalid of the device does not have
 * {@link IVibrator#CAP_COMPOSE_PWLE_EFFECTS_V2}.
 */
final class PwleSegmentsValidator implements VibrationSegmentsValidator {

    @Override
    public boolean hasValidSegments(VibratorInfo info, List<VibrationEffectSegment> segments) {

        boolean hasPwleCapability = info.hasCapability(IVibrator.CAP_COMPOSE_PWLE_EFFECTS_V2);
        float minFrequency = info.getFrequencyProfile().getMinFrequencyHz();
        float maxFrequency = info.getFrequencyProfile().getMaxFrequencyHz();

        for (VibrationEffectSegment segment : segments) {
            if (!(segment instanceof PwleSegment pwleSegment)) {
                continue;
            }

            if (!hasPwleCapability || pwleSegment.getStartFrequencyHz() < minFrequency
                    || pwleSegment.getStartFrequencyHz() > maxFrequency
                    || pwleSegment.getEndFrequencyHz() < minFrequency
                    || pwleSegment.getEndFrequencyHz() > maxFrequency) {
                return false;
            }
        }

        return true;
    }
}
