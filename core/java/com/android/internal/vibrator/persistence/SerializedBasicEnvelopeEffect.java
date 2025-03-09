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

import static com.android.internal.vibrator.persistence.XmlConstants.ATTRIBUTE_DURATION_MS;
import static com.android.internal.vibrator.persistence.XmlConstants.ATTRIBUTE_INITIAL_SHARPNESS;
import static com.android.internal.vibrator.persistence.XmlConstants.ATTRIBUTE_INTENSITY;
import static com.android.internal.vibrator.persistence.XmlConstants.ATTRIBUTE_SHARPNESS;
import static com.android.internal.vibrator.persistence.XmlConstants.NAMESPACE;
import static com.android.internal.vibrator.persistence.XmlConstants.TAG_BASIC_ENVELOPE_EFFECT;
import static com.android.internal.vibrator.persistence.XmlConstants.TAG_CONTROL_POINT;

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
 * Serialized representation of a basic envelope effect created via
 * {@link VibrationEffect.BasicEnvelopeBuilder}.
 *
 * @hide
 */
final class SerializedBasicEnvelopeEffect implements SerializedComposedEffect.SerializedSegment {
    private final BasicControlPoint[] mControlPoints;
    private final float mInitialSharpness;

    SerializedBasicEnvelopeEffect(BasicControlPoint[] controlPoints, float initialSharpness) {
        mControlPoints = controlPoints;
        mInitialSharpness = initialSharpness;
    }

    @Override
    public void write(@NonNull TypedXmlSerializer serializer) throws IOException {
        serializer.startTag(NAMESPACE, TAG_BASIC_ENVELOPE_EFFECT);

        if (!Float.isNaN(mInitialSharpness)) {
            serializer.attributeFloat(NAMESPACE, ATTRIBUTE_INITIAL_SHARPNESS, mInitialSharpness);
        }

        for (BasicControlPoint point : mControlPoints) {
            serializer.startTag(NAMESPACE, TAG_CONTROL_POINT);
            serializer.attributeFloat(NAMESPACE, ATTRIBUTE_INTENSITY, point.mIntensity);
            serializer.attributeFloat(NAMESPACE, ATTRIBUTE_SHARPNESS, point.mSharpness);
            serializer.attributeLong(NAMESPACE, ATTRIBUTE_DURATION_MS, point.mDurationMs);
            serializer.endTag(NAMESPACE, TAG_CONTROL_POINT);
        }

        serializer.endTag(NAMESPACE, TAG_BASIC_ENVELOPE_EFFECT);
    }

    @Override
    public void deserializeIntoComposition(@NonNull VibrationEffect.Composition composition) {
        VibrationEffect.BasicEnvelopeBuilder builder = new VibrationEffect.BasicEnvelopeBuilder();

        if (!Float.isNaN(mInitialSharpness)) {
            builder.setInitialSharpness(mInitialSharpness);
        }

        for (BasicControlPoint point : mControlPoints) {
            builder.addControlPoint(point.mIntensity, point.mSharpness, point.mDurationMs);
        }
        composition.addEffect(builder.build());
    }

    @Override
    public String toString() {
        return "SerializedBasicEnvelopeEffect{"
                + "initialSharpness=" + (Float.isNaN(mInitialSharpness) ? "" : mInitialSharpness)
                + ", controlPoints=" + Arrays.toString(mControlPoints)
                + '}';
    }

    static final class Builder {
        private final List<BasicControlPoint> mControlPoints;
        private float mInitialSharpness = Float.NaN;

        Builder() {
            mControlPoints = new ArrayList<>();
        }

        void setInitialSharpness(float sharpness) {
            mInitialSharpness = sharpness;
        }

        void addControlPoint(float intensity, float sharpness, long durationMs) {
            mControlPoints.add(new BasicControlPoint(intensity, sharpness, durationMs));
        }

        SerializedBasicEnvelopeEffect build() {
            return new SerializedBasicEnvelopeEffect(
                    mControlPoints.toArray(new BasicControlPoint[0]), mInitialSharpness);
        }
    }

    /** Parser implementation for {@link SerializedBasicEnvelopeEffect}. */
    static final class Parser {

        @NonNull
        static SerializedBasicEnvelopeEffect parseNext(@NonNull TypedXmlPullParser parser,
                @XmlConstants.Flags int flags) throws XmlParserException, IOException {
            XmlValidator.checkStartTag(parser, TAG_BASIC_ENVELOPE_EFFECT);
            XmlValidator.checkTagHasNoUnexpectedAttributes(parser, ATTRIBUTE_INITIAL_SHARPNESS);

            Builder builder = new Builder();
            builder.setInitialSharpness(
                    XmlReader.readAttributeFloatInRange(parser, ATTRIBUTE_INITIAL_SHARPNESS, 0f, 1f,
                            Float.NaN));

            int outerDepth = parser.getDepth();

            // Read all nested tags
            while (XmlReader.readNextTagWithin(parser, outerDepth)) {
                parseControlPoint(parser, builder);
                // Consume tag
                XmlReader.readEndTag(parser);
            }

            // Check schema assertions about <basic-envelope-effect>
            XmlValidator.checkParserCondition(!builder.mControlPoints.isEmpty(),
                    "Expected tag %s to have at least one control point",
                    TAG_BASIC_ENVELOPE_EFFECT);
            XmlValidator.checkParserCondition(builder.mControlPoints.getLast().mIntensity == 0,
                    "Basic envelope effects must end at a zero intensity control point");

            return builder.build();
        }

        private static void parseControlPoint(TypedXmlPullParser parser, Builder builder)
                throws XmlParserException {
            XmlValidator.checkStartTag(parser, TAG_CONTROL_POINT);
            XmlValidator.checkTagHasNoUnexpectedAttributes(
                    parser, ATTRIBUTE_DURATION_MS, ATTRIBUTE_INTENSITY,
                    ATTRIBUTE_SHARPNESS);
            float intensity = XmlReader.readAttributeFloatInRange(parser, ATTRIBUTE_INTENSITY, 0,
                    1);
            float sharpness = XmlReader.readAttributeFloatInRange(parser, ATTRIBUTE_SHARPNESS, 0,
                    1);
            long durationMs = XmlReader.readAttributePositiveLong(parser, ATTRIBUTE_DURATION_MS);

            builder.addControlPoint(intensity, sharpness, durationMs);
        }
    }

    private static final class BasicControlPoint {
        private final float mIntensity;
        private final float mSharpness;
        private final long mDurationMs;

        BasicControlPoint(float intensity, float sharpness, long durationMs) {
            mIntensity = intensity;
            mSharpness = sharpness;
            mDurationMs = durationMs;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "(%.2f, %.2f, %dms)", mIntensity, mSharpness,
                    mDurationMs);
        }
    }
}

