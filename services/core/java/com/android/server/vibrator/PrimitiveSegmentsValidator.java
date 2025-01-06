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

import android.annotation.SuppressLint;
import android.hardware.vibrator.IVibrator;
import android.os.VibratorInfo;
import android.os.vibrator.Flags;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.VibrationEffectSegment;

import java.util.List;

/**
 * Validates primitive segments to ensure they are compatible with the device's capabilities.
 *
 * <p>The segments will be considered invalid if the device does not have
 * {@link IVibrator#CAP_COMPOSE_EFFECTS} or if one of the primitives is not supported.
 */
final class PrimitiveSegmentsValidator implements VibrationSegmentsValidator {

    @SuppressLint("WrongConstant") // using primitive id from validated segment
    @Override
    public boolean hasValidSegments(VibratorInfo info, List<VibrationEffectSegment> segments) {
        int segmentCount = segments.size();
        for (int i = 0; i < segmentCount; i++) {
            if (!(segments.get(i) instanceof PrimitiveSegment primitive)) {
                continue;
            }
            if (Flags.primitiveCompositionAbsoluteDelay()) {
                // Primitive support checks introduced by this feature
                if (!info.isPrimitiveSupported(primitive.getPrimitiveId())) {
                    return false;
                }
            } else {
                // Delay type support not available without this feature
                if ((primitive.getDelayType() != PrimitiveSegment.DEFAULT_DELAY_TYPE)) {
                    return false;
                }
            }
        }

        return true;
    }
}
