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

package com.android.internal.vibrator.persistence;

import android.annotation.NonNull;
import android.os.VibrationEffect;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.StepSegment;
import android.os.vibrator.VibrationEffectSegment;

import com.android.internal.vibrator.persistence.SerializedVibrationEffect.SerializedSegment;
import com.android.internal.vibrator.persistence.XmlConstants.PredefinedEffectName;
import com.android.internal.vibrator.persistence.XmlConstants.PrimitiveEffectName;

import java.util.List;

/**
 * Serializer implementation for {@link VibrationEffect}.
 *
 * <p>This serializer does not support effects created with {@link VibrationEffect.WaveformBuilder}
 * nor {@link VibrationEffect.Composition#addEffect(VibrationEffect)}. It only supports vibration
 * effects defined as:
 *
 * <ul>
 *     <li>{@link VibrationEffect#createPredefined(int)}
 *     <li>{@link VibrationEffect#createWaveform(long[], int[], int)}
 *     <li>A composition created exclusively via
 *         {@link VibrationEffect.Composition#addPrimitive(int, float, int)}
 * </ul>
 *
 * @hide
 */
public final class VibrationEffectXmlSerializer {

    /**
     * Creates a serialized representation of the input {@code vibration}.
     *
     * @see XmlSerializer#serialize
     */
    @NonNull
    public static XmlSerializedVibration<VibrationEffect> serialize(
            @NonNull VibrationEffect vibration) throws XmlSerializerException {
        XmlValidator.checkSerializerCondition(vibration instanceof VibrationEffect.Composed,
                "Unsupported VibrationEffect type %s", vibration);

        VibrationEffect.Composed composedEffect = (VibrationEffect.Composed) vibration;
        XmlValidator.checkSerializerCondition(!composedEffect.getSegments().isEmpty(),
                "Unsupported empty VibrationEffect %s", vibration);

        VibrationEffectSegment firstSegment = composedEffect.getSegments().get(0);
        if (firstSegment instanceof PrebakedSegment) {
            return serializePredefinedEffect(composedEffect);
        }
        if (firstSegment instanceof PrimitiveSegment) {
            return serializePrimitiveEffect(composedEffect);
        }
        return serializeWaveformEffect(composedEffect);
    }

    private static SerializedVibrationEffect serializePredefinedEffect(
            VibrationEffect.Composed effect) throws XmlSerializerException {
        List<VibrationEffectSegment> segments = effect.getSegments();
        XmlValidator.checkSerializerCondition(effect.getRepeatIndex() == -1,
                "Unsupported repeating predefined effect %s", effect);
        XmlValidator.checkSerializerCondition(segments.size() == 1,
                "Unsupported multiple segments in predefined effect %s", effect);
        XmlValidator.checkSerializerCondition(segments.get(0) instanceof PrebakedSegment,
                "Unsupported segment for predefined effect %s", segments.get(0));

        PrebakedSegment segment = (PrebakedSegment) segments.get(0);
        PredefinedEffectName effectName = PredefinedEffectName.findById(segment.getEffectId());

        XmlValidator.checkSerializerCondition(effectName != null,
                "Unsupported predefined effect id %s", segment.getEffectId());

        return new SerializedVibrationEffect(new SerializedPredefinedEffect(effectName));
    }

    private static SerializedVibrationEffect serializePrimitiveEffect(
            VibrationEffect.Composed effect) throws XmlSerializerException {
        List<VibrationEffectSegment> segments = effect.getSegments();
        XmlValidator.checkSerializerCondition(effect.getRepeatIndex() == -1,
                "Unsupported repeating primitive composition %s", effect);

        SerializedSegment[] primitives = new SerializedSegment[segments.size()];
        for (int i = 0; i < segments.size(); i++) {
            XmlValidator.checkSerializerCondition(segments.get(i) instanceof PrimitiveSegment,
                    "Unsupported segment for primitive composition %s", segments.get(i));

            PrimitiveSegment segment = (PrimitiveSegment) segments.get(i);
            PrimitiveEffectName primitiveName =
                    PrimitiveEffectName.findById(segment.getPrimitiveId());
            primitives[i] = new SerializedCompositionPrimitive(
                    primitiveName, segment.getScale(), segment.getDelay());
        }

        return new SerializedVibrationEffect(primitives);
    }

    private static SerializedVibrationEffect serializeWaveformEffect(
            VibrationEffect.Composed effect) throws XmlSerializerException {
        SerializedAmplitudeStepWaveform.Builder serializedWaveformBuilder =
                new SerializedAmplitudeStepWaveform.Builder();

        List<VibrationEffectSegment> segments = effect.getSegments();
        for (int i = 0; i < segments.size(); i++) {
            XmlValidator.checkSerializerCondition(segments.get(i) instanceof StepSegment,
                    "Unsupported segment for waveform effect %s", segments.get(i));

            StepSegment segment = (StepSegment) segments.get(i);
            if (effect.getRepeatIndex() == i) {
                serializedWaveformBuilder.setRepeatIndexToCurrentEntry();
            }
            serializedWaveformBuilder.addDurationAndAmplitude(
                    segment.getDuration(), toAmplitudeInt(segment.getAmplitude()));
        }

        return new SerializedVibrationEffect(serializedWaveformBuilder.build());
    }

    private static int toAmplitudeInt(float amplitude) {
        return Float.compare(amplitude, VibrationEffect.DEFAULT_AMPLITUDE) == 0
                ? VibrationEffect.DEFAULT_AMPLITUDE
                : Math.round(amplitude * VibrationEffect.MAX_AMPLITUDE);
    }
}
