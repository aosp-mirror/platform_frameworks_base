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

import android.os.VibrationEffect;
import android.os.VibratorInfo;
import android.os.vibrator.VibrationEffectSegment;

import java.util.List;

/** Adapts a sequence of {@link VibrationEffectSegment} to a vibrator. */
interface VibrationSegmentsAdapter {

    /**
     * Add and/or remove segments to the given {@link VibrationEffectSegment} list based on the
     * given {@link VibratorInfo}.
     *
     * <p>This returns the new {@code repeatIndex} to be used together with the updated list to
     * specify an equivalent {@link VibrationEffect}.
     *
     * @param info        The vibrator info to be applied to the sequence of segments.
     * @param segments    List of {@link VibrationEffectSegment} to be modified.
     * @param repeatIndex Repeat index on the current segment list.
     * @return The new repeat index on the modifies list.
     */
    int adaptToVibrator(VibratorInfo info, List<VibrationEffectSegment> segments, int repeatIndex);
}
