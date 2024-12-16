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

import static com.android.internal.vibrator.persistence.XmlConstants.ATTRIBUTE_AMPLITUDE;
import static com.android.internal.vibrator.persistence.XmlConstants.ATTRIBUTE_DURATION_MS;
import static com.android.internal.vibrator.persistence.XmlConstants.ATTRIBUTE_FREQUENCY_HZ;
import static com.android.internal.vibrator.persistence.XmlConstants.ATTRIBUTE_INITIAL_FREQUENCY_HZ;
import static com.android.internal.vibrator.persistence.XmlConstants.NAMESPACE;
import static com.android.internal.vibrator.persistence.XmlConstants.TAG_CONTROL_POINT;
import static com.android.internal.vibrator.persistence.XmlConstants.TAG_WAVEFORM_ENVELOPE_EFFECT;

import android.annotation.NonNull;
import android.os.VibrationEffect;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Serialized representation of a waveform envelope effect created via
 * {@link VibrationEffect.WaveformEnvelopeBuilder}.
 *
 * @hide
 */
final class SerializedWaveformEnvelopeEffect implements SerializedComposedEffect.SerializedSegment {

    private final WaveformControlPoint[] mControlPoints;
    private final float mInitialFrequency;

    SerializedWaveformEnvelopeEffect(WaveformControlPoint[] controlPoints, float initialFrequency) {
        mControlPoints = controlPoints;
        mInitialFrequency = initialFrequency;
    }

    @Override
    public void write(@NonNull TypedXmlSerializer serializer) throws IOException {
        serializer.startTag(NAMESPACE, TAG_WAVEFORM_ENVELOPE_EFFECT);

        if (!Float.isNaN(mInitialFrequency)) {
            serializer.attributeFloat(NAMESPACE, ATTRIBUTE_INITIAL_FREQUENCY_HZ, mInitialFrequency);
        }

        for (WaveformControlPoint point : mControlPoints) {
            serializer.startTag(NAMESPACE, TAG_CONTROL_POINT);
            serializer.attributeFloat(NAMESPACE, ATTRIBUTE_AMPLITUDE, point.mAmplitude);
            serializer.attributeFloat(NAMESPACE, ATTRIBUTE_FREQUENCY_HZ, point.mFrequency);
            serializer.attributeLong(NAMESPACE, ATTRIBUTE_DURATION_MS, point.mDurationMs);
            serializer.endTag(NAMESPACE, TAG_CONTROL_POINT);
        }

        serializer.endTag(NAMESPACE, TAG_WAVEFORM_ENVELOPE_EFFECT);
    }

    @Override
    public void deserializeIntoComposition(@NonNull VibrationEffect.Composition composition) {
        VibrationEffect.WaveformEnvelopeBuilder builder =
                new VibrationEffect.WaveformEnvelopeBuilder();

        if (!Float.isNaN(mInitialFrequency)) {
            builder.setInitialFrequencyHz(mInitialFrequency);
        }

        for (WaveformControlPoint point : mControlPoints) {
            builder.addControlPoint(point.mAmplitude, point.mFrequency, point.mDurationMs);
        }
        composition.addEffect(builder.build());
    }

    @Override
    public String toString() {
        return "SerializedWaveformEnvelopeEffect{"
                + "InitialFrequency=" + (Float.isNaN(mInitialFrequency) ? "" : mInitialFrequency)
                + ", controlPoints=" + Arrays.toString(mControlPoints)
                + '}';
    }

    static final class Builder {
        private final List<WaveformControlPoint> mControlPoints;
        private float mInitialFrequencyHz = Float.NaN;

        Builder() {
            mControlPoints = new ArrayList<>();
        }

        void setInitialFrequencyHz(float frequencyHz) {
            mInitialFrequencyHz = frequencyHz;
        }

        void addControlPoint(float amplitude, float frequencyHz, long durationMs) {
            mControlPoints.add(new WaveformControlPoint(amplitude, frequencyHz, durationMs));
        }

        SerializedWaveformEnvelopeEffect build() {
            return new SerializedWaveformEnvelopeEffect(
                    mControlPoints.toArray(new WaveformControlPoint[0]), mInitialFrequencyHz);
        }
    }

    /** Parser implementation for {@link SerializedWaveformEnvelopeEffect}. */
    static final class Parser {

        @NonNull
        static SerializedWaveformEnvelopeEffect parseNext(@NonNull TypedXmlPullParser parser,
                @XmlConstants.Flags int flags) throws XmlParserException, IOException {
            XmlValidator.checkStartTag(parser, TAG_WAVEFORM_ENVELOPE_EFFECT);
            XmlValidator.checkTagHasNoUnexpectedAttributes(parser, ATTRIBUTE_INITIAL_FREQUENCY_HZ);

            Builder builder = new Builder();
            builder.setInitialFrequencyHz(
                    XmlReader.readAttributePositiveFloat(parser, ATTRIBUTE_INITIAL_FREQUENCY_HZ,
                            Float.NaN));

            int outerDepth = parser.getDepth();

            while (XmlReader.readNextTagWithin(parser, outerDepth)) {
                parseControlPoint(parser, builder);
                // Consume tag
                XmlReader.readEndTag(parser);
            }

            // Check schema assertions about <waveform-envelope-effect>
            XmlValidator.checkParserCondition(!builder.mControlPoints.isEmpty(),
                    "Expected tag %s to have at least one control point",
                    TAG_WAVEFORM_ENVELOPE_EFFECT);

            return builder.build();
        }

        private static void parseControlPoint(TypedXmlPullParser parser, Builder builder)
                throws XmlParserException {
            XmlValidator.checkStartTag(parser, TAG_CONTROL_POINT);
            XmlValidator.checkTagHasNoUnexpectedAttributes(
                    parser, ATTRIBUTE_DURATION_MS, ATTRIBUTE_AMPLITUDE,
                    ATTRIBUTE_FREQUENCY_HZ);
            float amplitude = XmlReader.readAttributeFloatInRange(parser, ATTRIBUTE_AMPLITUDE, 0,
                    1);
            float frequencyHz = XmlReader.readAttributePositiveFloat(parser,
                    ATTRIBUTE_FREQUENCY_HZ);
            long durationMs = XmlReader.readAttributePositiveLong(parser, ATTRIBUTE_DURATION_MS);

            builder.addControlPoint(amplitude, frequencyHz, durationMs);
        }
    }

    private static final class WaveformControlPoint {
        private final float mAmplitude;
        private final float mFrequency;
        private final long mDurationMs;

        WaveformControlPoint(float amplitude, float frequency, long durationMs) {
            mAmplitude = amplitude;
            mFrequency = frequency;
            mDurationMs = durationMs;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "(%.2f, %.2f, %dms)", mAmplitude, mFrequency,
                    mDurationMs);
        }
    }
}
