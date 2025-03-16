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
import android.os.PersistableBundle;
import android.os.VibrationEffect;
import android.os.vibrator.BasicPwleSegment;
import android.os.vibrator.Flags;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.PwleSegment;
import android.os.vibrator.StepSegment;
import android.os.vibrator.VibrationEffectSegment;

import com.android.internal.vibrator.persistence.SerializedComposedEffect.SerializedSegment;
import com.android.internal.vibrator.persistence.XmlConstants.PredefinedEffectName;
import com.android.internal.vibrator.persistence.XmlConstants.PrimitiveDelayType;
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
 *     <li>{@link VibrationEffect#createVendorEffect(PersistableBundle)}
 *     <li>{@link VibrationEffect.WaveformEnvelopeBuilder}
 *     <li>{@link VibrationEffect.BasicEnvelopeBuilder}
 * </ul>
 *
 * @hide
 */
public final class VibrationEffectXmlSerializer {

    /**
     * Creates a serialized representation of the input {@code vibration}.
     */
    @NonNull
    public static XmlSerializedVibration<? extends VibrationEffect> serialize(
            @NonNull VibrationEffect vibration, @XmlConstants.Flags int flags)
            throws XmlSerializerException {
        if (Flags.vendorVibrationEffects()
                && (vibration instanceof VibrationEffect.VendorEffect vendorEffect)) {
            return serializeVendorEffect(vendorEffect);
        }

        XmlValidator.checkSerializerCondition(vibration instanceof VibrationEffect.Composed,
                "Unsupported VibrationEffect type %s", vibration);

        VibrationEffect.Composed composed = (VibrationEffect.Composed) vibration;
        XmlValidator.checkSerializerCondition(!composed.getSegments().isEmpty(),
                "Unsupported empty VibrationEffect %s", vibration);

        VibrationEffectSegment firstSegment = composed.getSegments().get(0);
        if (firstSegment instanceof PrebakedSegment) {
            return serializePredefinedEffect(composed, flags);
        }
        if (firstSegment instanceof PrimitiveSegment) {
            return serializePrimitiveEffect(composed);
        }
        if (Flags.normalizedPwleEffects() && firstSegment instanceof PwleSegment) {
            return serializeWaveformEnvelopeEffect(composed);
        }
        if (Flags.normalizedPwleEffects() && firstSegment instanceof BasicPwleSegment) {
            return serializeBasicEnvelopeEffect(composed);
        }
        return serializeWaveformEffect(composed);
    }

    private static SerializedComposedEffect serializePredefinedEffect(
            VibrationEffect.Composed effect, @XmlConstants.Flags int flags)
            throws XmlSerializerException {
        List<VibrationEffectSegment> segments = effect.getSegments();
        XmlValidator.checkSerializerCondition(effect.getRepeatIndex() == -1,
                "Unsupported repeating predefined effect %s", effect);
        XmlValidator.checkSerializerCondition(segments.size() == 1,
                "Unsupported multiple segments in predefined effect %s", effect);
        return new SerializedComposedEffect(serializePrebakedSegment(segments.get(0), flags));
    }

    private static SerializedVendorEffect serializeVendorEffect(
            VibrationEffect.VendorEffect effect) {
        return new SerializedVendorEffect(effect.getVendorData());
    }

    private static SerializedComposedEffect serializePrimitiveEffect(
            VibrationEffect.Composed effect) throws XmlSerializerException {
        List<VibrationEffectSegment> segments = effect.getSegments();
        XmlValidator.checkSerializerCondition(effect.getRepeatIndex() == -1,
                "Unsupported repeating primitive composition %s", effect);

        SerializedSegment[] primitives = new SerializedSegment[segments.size()];
        for (int i = 0; i < segments.size(); i++) {
            primitives[i] = serializePrimitiveSegment(segments.get(i));
        }

        return new SerializedComposedEffect(primitives);
    }

    private static SerializedComposedEffect serializeWaveformEnvelopeEffect(
            VibrationEffect.Composed effect) throws XmlSerializerException {
        SerializedWaveformEnvelopeEffect.Builder builder =
                new SerializedWaveformEnvelopeEffect.Builder();
        List<VibrationEffectSegment> segments = effect.getSegments();
        XmlValidator.checkSerializerCondition(effect.getRepeatIndex() == -1,
                "Unsupported repeating waveform envelope effect %s", effect);
        for (int i = 0; i < segments.size(); i++) {
            XmlValidator.checkSerializerCondition(segments.get(i) instanceof PwleSegment,
                    "Unsupported segment for waveform envelope effect %s", segments.get(i));
            PwleSegment segment = (PwleSegment) segments.get(i);

            if (i == 0 && segment.getStartFrequencyHz() != segment.getEndFrequencyHz()) {
                // Initial frequency explicitly defined.
                builder.setInitialFrequencyHz(segment.getStartFrequencyHz());
            }

            builder.addControlPoint(segment.getEndAmplitude(), segment.getEndFrequencyHz(),
                    segment.getDuration());
        }

        return new SerializedComposedEffect(builder.build());
    }

    private static SerializedComposedEffect serializeBasicEnvelopeEffect(
            VibrationEffect.Composed effect) throws XmlSerializerException {
        SerializedBasicEnvelopeEffect.Builder builder = new SerializedBasicEnvelopeEffect.Builder();
        List<VibrationEffectSegment> segments = effect.getSegments();
        XmlValidator.checkSerializerCondition(effect.getRepeatIndex() == -1,
                "Unsupported repeating basic envelope effect %s", effect);
        for (int i = 0; i < segments.size(); i++) {
            XmlValidator.checkSerializerCondition(segments.get(i) instanceof BasicPwleSegment,
                    "Unsupported segment for basic envelope effect %s", segments.get(i));
            BasicPwleSegment segment = (BasicPwleSegment) segments.get(i);

            if (i == 0 && segment.getStartSharpness() != segment.getEndSharpness()) {
                // Initial sharpness explicitly defined.
                builder.setInitialSharpness(segment.getStartSharpness());
            }

            builder.addControlPoint(segment.getEndIntensity(), segment.getEndSharpness(),
                    segment.getDuration());
        }

        return new SerializedComposedEffect(builder.build());
    }

    private static SerializedComposedEffect serializeWaveformEffect(
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

            XmlValidator.checkSerializerCondition(Float.compare(segment.getFrequencyHz(), 0) == 0,
                    "Unsupported segment with non-default frequency %f", segment.getFrequencyHz());

            serializedWaveformBuilder.addDurationAndAmplitude(
                    segment.getDuration(), toAmplitudeInt(segment.getAmplitude()));
        }

        return new SerializedComposedEffect(serializedWaveformBuilder.build());
    }

    private static SerializedPredefinedEffect serializePrebakedSegment(
            VibrationEffectSegment segment, @XmlConstants.Flags int flags)
            throws XmlSerializerException {
        XmlValidator.checkSerializerCondition(segment instanceof PrebakedSegment,
                "Unsupported segment for predefined effect %s", segment);

        PrebakedSegment prebaked = (PrebakedSegment) segment;
        PredefinedEffectName effectName = PredefinedEffectName.findById(
                prebaked.getEffectId(), flags);

        XmlValidator.checkSerializerCondition(effectName != null,
                "Unsupported predefined effect id %s", prebaked.getEffectId());

        if ((flags & XmlConstants.FLAG_ALLOW_HIDDEN_APIS) == 0) {
            // Only allow effects with default fallback flag if using the public APIs schema.
            XmlValidator.checkSerializerCondition(
                    prebaked.shouldFallback() == PrebakedSegment.DEFAULT_SHOULD_FALLBACK,
                    "Unsupported predefined effect with should fallback %s",
                    prebaked.shouldFallback());
        }

        return new SerializedPredefinedEffect(effectName, prebaked.shouldFallback());
    }

    private static SerializedCompositionPrimitive serializePrimitiveSegment(
            VibrationEffectSegment segment) throws XmlSerializerException {
        XmlValidator.checkSerializerCondition(segment instanceof PrimitiveSegment,
                "Unsupported segment for primitive composition %s", segment);

        PrimitiveSegment primitive = (PrimitiveSegment) segment;
        PrimitiveEffectName primitiveName =
                PrimitiveEffectName.findById(primitive.getPrimitiveId());

        XmlValidator.checkSerializerCondition(primitiveName != null,
                "Unsupported primitive effect id %s", primitive.getPrimitiveId());

        PrimitiveDelayType delayType = null;

        if (Flags.primitiveCompositionAbsoluteDelay()) {
            delayType = PrimitiveDelayType.findByType(primitive.getDelayType());
            XmlValidator.checkSerializerCondition(delayType != null,
                    "Unsupported primitive delay type %s", primitive.getDelayType());
        } else {
            XmlValidator.checkSerializerCondition(
                    primitive.getDelayType() == PrimitiveSegment.DEFAULT_DELAY_TYPE,
                    "Unsupported primitive delay type %s", primitive.getDelayType());
        }

        return new SerializedCompositionPrimitive(
                primitiveName, primitive.getScale(), primitive.getDelay(), delayType);
    }

    private static int toAmplitudeInt(float amplitude) {
        return Float.compare(amplitude, VibrationEffect.DEFAULT_AMPLITUDE) == 0
                ? VibrationEffect.DEFAULT_AMPLITUDE
                : Math.round(amplitude * VibrationEffect.MAX_AMPLITUDE);
    }
}
