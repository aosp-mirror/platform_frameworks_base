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

package com.android.internal.vibrator.persistence;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.PersistableBundle;
import android.os.VibrationEffect;
import android.os.vibrator.BasicPwleSegment;
import android.os.vibrator.Flags;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.PwleSegment;
import android.os.vibrator.StepSegment;
import android.os.vibrator.VibrationEffectSegment;

import java.util.List;
import java.util.function.BiConsumer;

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
 * <p>This serializer also supports repeating effects. For repeating waveform effects, it attempts
 * to serialize the effect as a single unit. If this fails, it falls back to serializing it as a
 * sequence of individual waveform entries.
 *
 * @hide
 */
public class VibrationEffectSerializer {
    private static final String TAG = "VibrationEffectSerializer";

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

        List<VibrationEffectSegment> segments = composed.getSegments();
        int repeatIndex = composed.getRepeatIndex();

        SerializedComposedEffect serializedEffect;
        if (repeatIndex >= 0) {
            serializedEffect = trySerializeRepeatingAmplitudeWaveformEffect(segments, repeatIndex);
            if (serializedEffect == null) {
                serializedEffect = serializeRepeatingEffect(segments, repeatIndex, flags);
            }
        } else {
            serializedEffect = serializeNonRepeatingEffect(segments, flags);
        }

        return serializedEffect;
    }

    private static SerializedComposedEffect serializeRepeatingEffect(
            List<VibrationEffectSegment> segments, int repeatIndex, @XmlConstants.Flags int flags)
            throws XmlSerializerException {

        SerializedRepeatingEffect.Builder builder = new SerializedRepeatingEffect.Builder();
        if (repeatIndex > 0) {
            List<VibrationEffectSegment> preambleSegments = segments.subList(0, repeatIndex);
            builder.setPreamble(serializeEffectEntries(preambleSegments, flags));

            // Update segments to match the repeating block only, after preamble was consumed.
            segments = segments.subList(repeatIndex, segments.size());
        }

        builder.setRepeating(serializeEffectEntries(segments, flags));

        return new SerializedComposedEffect(builder.build());
    }

    @NonNull
    private static SerializedComposedEffect serializeNonRepeatingEffect(
            List<VibrationEffectSegment> segments, @XmlConstants.Flags int flags)
            throws XmlSerializerException {
        SerializedComposedEffect effect = trySerializeNonWaveformEffect(segments, flags);
        if (effect == null) {
            effect = serializeWaveformEffect(segments);
        }

        return effect;
    }

    @NonNull
    private static SerializedComposedEffect serializeEffectEntries(
            List<VibrationEffectSegment> segments, @XmlConstants.Flags int flags)
            throws XmlSerializerException {
        SerializedComposedEffect effect = trySerializeNonWaveformEffect(segments, flags);
        if (effect == null) {
            effect = serializeWaveformEffectEntries(segments);
        }

        return effect;
    }

    @Nullable
    private static SerializedComposedEffect trySerializeNonWaveformEffect(
            List<VibrationEffectSegment> segments, int flags) throws XmlSerializerException {
        VibrationEffectSegment firstSegment = segments.getFirst();

        if (firstSegment instanceof PrebakedSegment) {
            return serializePredefinedEffect(segments, flags);
        }
        if (firstSegment instanceof PrimitiveSegment) {
            return serializePrimitiveEffect(segments);
        }
        if (firstSegment instanceof PwleSegment) {
            return serializeWaveformEnvelopeEffect(segments);
        }
        if (firstSegment instanceof BasicPwleSegment) {
            return serializeBasicEnvelopeEffect(segments);
        }

        return null;
    }

    private static SerializedComposedEffect serializePredefinedEffect(
            List<VibrationEffectSegment> segments, @XmlConstants.Flags int flags)
            throws XmlSerializerException {
        XmlValidator.checkSerializerCondition(segments.size() == 1,
                "Unsupported multiple segments in predefined effect: %s", segments);
        return new SerializedComposedEffect(serializePrebakedSegment(segments.getFirst(), flags));
    }

    private static SerializedVendorEffect serializeVendorEffect(
            VibrationEffect.VendorEffect effect) {
        return new SerializedVendorEffect(effect.getVendorData());
    }

    private static SerializedComposedEffect serializePrimitiveEffect(
            List<VibrationEffectSegment> segments) throws XmlSerializerException {
        SerializedComposedEffect.SerializedSegment[] primitives =
                new SerializedComposedEffect.SerializedSegment[segments.size()];
        for (int i = 0; i < segments.size(); i++) {
            primitives[i] = serializePrimitiveSegment(segments.get(i));
        }

        return new SerializedComposedEffect(primitives);
    }

    private static SerializedComposedEffect serializeWaveformEnvelopeEffect(
            List<VibrationEffectSegment> segments) throws XmlSerializerException {
        SerializedWaveformEnvelopeEffect.Builder builder =
                new SerializedWaveformEnvelopeEffect.Builder();
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
            List<VibrationEffectSegment> segments) throws XmlSerializerException {
        SerializedBasicEnvelopeEffect.Builder builder = new SerializedBasicEnvelopeEffect.Builder();
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

    private static SerializedComposedEffect trySerializeRepeatingAmplitudeWaveformEffect(
            List<VibrationEffectSegment> segments, int repeatingIndex) {
        SerializedAmplitudeStepWaveform.Builder builder =
                new SerializedAmplitudeStepWaveform.Builder();

        for (int i = 0; i < segments.size(); i++) {
            if (repeatingIndex == i) {
                builder.setRepeatIndexToCurrentEntry();
            }
            try {
                serializeStepSegment(segments.get(i), builder::addDurationAndAmplitude);
            } catch (XmlSerializerException e) {
                return null;
            }
        }

        return new SerializedComposedEffect(builder.build());
    }

    private static SerializedComposedEffect serializeWaveformEffect(
            List<VibrationEffectSegment> segments) throws XmlSerializerException {
        SerializedAmplitudeStepWaveform.Builder builder =
                new SerializedAmplitudeStepWaveform.Builder();
        for (int i = 0; i < segments.size(); i++) {
            serializeStepSegment(segments.get(i), builder::addDurationAndAmplitude);
        }

        return new SerializedComposedEffect(builder.build());
    }

    private static SerializedComposedEffect serializeWaveformEffectEntries(
            List<VibrationEffectSegment> segments) throws XmlSerializerException {
        SerializedWaveformEffectEntries.Builder builder =
                new SerializedWaveformEffectEntries.Builder();
        for (int i = 0; i < segments.size(); i++) {
            serializeStepSegment(segments.get(i), builder::addDurationAndAmplitude);
        }

        return new SerializedComposedEffect(builder.build());
    }

    private static void serializeStepSegment(VibrationEffectSegment segment,
            BiConsumer<Long, Integer> builder) throws XmlSerializerException {
        XmlValidator.checkSerializerCondition(segment instanceof StepSegment,
                "Unsupported segment for waveform effect %s", segment);

        XmlValidator.checkSerializerCondition(
                Float.compare(((StepSegment) segment).getFrequencyHz(), 0) == 0,
                "Unsupported segment with non-default frequency %f",
                ((StepSegment) segment).getFrequencyHz());

        builder.accept(segment.getDuration(),
                toAmplitudeInt(((StepSegment) segment).getAmplitude()));
    }

    private static SerializedPredefinedEffect serializePrebakedSegment(
            VibrationEffectSegment segment, @XmlConstants.Flags int flags)
            throws XmlSerializerException {
        XmlValidator.checkSerializerCondition(segment instanceof PrebakedSegment,
                "Unsupported segment for predefined effect %s", segment);

        PrebakedSegment prebaked = (PrebakedSegment) segment;
        XmlConstants.PredefinedEffectName effectName = XmlConstants.PredefinedEffectName.findById(
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
        XmlConstants.PrimitiveEffectName primitiveName =
                XmlConstants.PrimitiveEffectName.findById(primitive.getPrimitiveId());

        XmlValidator.checkSerializerCondition(primitiveName != null,
                "Unsupported primitive effect id %s", primitive.getPrimitiveId());

        XmlConstants.PrimitiveDelayType delayType = null;

        if (Flags.primitiveCompositionAbsoluteDelay()) {
            delayType = XmlConstants.PrimitiveDelayType.findByType(primitive.getDelayType());
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
