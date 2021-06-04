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
import android.os.vibrator.VibrationEffectSegment;

import java.util.ArrayList;
import java.util.List;

/**
 * Helpers to adapt a {@link VibrationEffect} to generic modifiers (e.g. device capabilities,
 * user settings, etc).
 */
public final class VibrationEffectAdapters {

    /**
     * Function that applies a generic modifier to a sequence of {@link VibrationEffectSegment}.
     *
     * @param <T> The type of modifiers this adapter accepts.
     */
    public interface SegmentsAdapter<T> {

        /**
         * Add and/or remove segments to the given {@link VibrationEffectSegment} list based on the
         * given modifier.
         *
         * <p>This returns the new {@code repeatIndex} to be used together with the updated list to
         * specify an equivalent {@link VibrationEffect}.
         *
         * @param segments    List of {@link VibrationEffectSegment} to be modified.
         * @param repeatIndex Repeat index on the current segment list.
         * @param modifier    The modifier to be applied to the sequence of segments.
         * @return The new repeat index on the modifies list.
         */
        int apply(List<VibrationEffectSegment> segments, int repeatIndex, T modifier);
    }

    /**
     * Function that applies a generic modifier to a {@link VibrationEffect}.
     *
     * @param <T> The type of modifiers this adapter accepts.
     */
    public interface EffectAdapter<T> {

        /** Applies the modifier to given {@link VibrationEffect}, returning the new effect. */
        VibrationEffect apply(VibrationEffect effect, T modifier);
    }

    /**
     * Applies a sequence of {@link SegmentsAdapter} to the segments of a given
     * {@link VibrationEffect}, in order.
     *
     * @param effect   The effect to be adapted to given modifier.
     * @param adapters The sequence of adapters to be applied to given {@link VibrationEffect}.
     * @param modifier The modifier to be passed to each adapter that describes the conditions the
     *                 {@link VibrationEffect} needs to be adapted to (e.g. device capabilities,
     *                 user settings, etc).
     */
    public static <T> VibrationEffect apply(VibrationEffect effect,
            List<SegmentsAdapter<T>> adapters, T modifier) {
        if (!(effect instanceof VibrationEffect.Composed)) {
            // Segments adapters can only be applied to Composed effects.
            return effect;
        }

        VibrationEffect.Composed composed = (VibrationEffect.Composed) effect;
        List<VibrationEffectSegment> newSegments = new ArrayList<>(composed.getSegments());
        int newRepeatIndex = composed.getRepeatIndex();

        int adapterCount = adapters.size();
        for (int i = 0; i < adapterCount; i++) {
            newRepeatIndex = adapters.get(i).apply(newSegments, newRepeatIndex, modifier);
        }

        return new VibrationEffect.Composed(newSegments, newRepeatIndex);
    }
}
