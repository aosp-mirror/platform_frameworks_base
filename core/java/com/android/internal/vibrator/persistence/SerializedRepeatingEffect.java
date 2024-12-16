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

import static com.android.internal.vibrator.persistence.XmlConstants.NAMESPACE;
import static com.android.internal.vibrator.persistence.XmlConstants.TAG_BASIC_ENVELOPE_EFFECT;
import static com.android.internal.vibrator.persistence.XmlConstants.TAG_PREAMBLE;
import static com.android.internal.vibrator.persistence.XmlConstants.TAG_PREDEFINED_EFFECT;
import static com.android.internal.vibrator.persistence.XmlConstants.TAG_PRIMITIVE_EFFECT;
import static com.android.internal.vibrator.persistence.XmlConstants.TAG_REPEATING;
import static com.android.internal.vibrator.persistence.XmlConstants.TAG_REPEATING_EFFECT;
import static com.android.internal.vibrator.persistence.XmlConstants.TAG_WAVEFORM_ENTRY;
import static com.android.internal.vibrator.persistence.XmlConstants.TAG_WAVEFORM_ENVELOPE_EFFECT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.VibrationEffect;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Serialized representation of a repeating effect created via
 * {@link VibrationEffect#createRepeatingEffect}.
 *
 * @hide
 */
public class SerializedRepeatingEffect implements SerializedComposedEffect.SerializedSegment {

    @Nullable
    private final SerializedComposedEffect mSerializedPreamble;
    @NonNull
    private final SerializedComposedEffect mSerializedRepeating;

    SerializedRepeatingEffect(@Nullable SerializedComposedEffect serializedPreamble,
            @NonNull SerializedComposedEffect serializedRepeating) {
        mSerializedPreamble = serializedPreamble;
        mSerializedRepeating = serializedRepeating;
    }

    @Override
    public void write(@NonNull TypedXmlSerializer serializer) throws IOException {
        serializer.startTag(NAMESPACE, TAG_REPEATING_EFFECT);

        if (mSerializedPreamble != null) {
            serializer.startTag(NAMESPACE, TAG_PREAMBLE);
            mSerializedPreamble.writeContent(serializer);
            serializer.endTag(NAMESPACE, TAG_PREAMBLE);
        }

        serializer.startTag(NAMESPACE, TAG_REPEATING);
        mSerializedRepeating.writeContent(serializer);
        serializer.endTag(NAMESPACE, TAG_REPEATING);

        serializer.endTag(NAMESPACE, TAG_REPEATING_EFFECT);
    }

    @Override
    public void deserializeIntoComposition(@NonNull VibrationEffect.Composition composition) {
        if (mSerializedPreamble != null) {
            composition.addEffect(
                    VibrationEffect.createRepeatingEffect(mSerializedPreamble.deserialize(),
                            mSerializedRepeating.deserialize()));
            return;
        }

        composition.addEffect(
                VibrationEffect.createRepeatingEffect(mSerializedRepeating.deserialize()));
    }

    @Override
    public String toString() {
        return "SerializedRepeatingEffect{"
                + "preamble=" + mSerializedPreamble
                + ", repeating=" + mSerializedRepeating
                + '}';
    }

    static final class Builder {
        private SerializedComposedEffect mPreamble;
        private SerializedComposedEffect mRepeating;

        void setPreamble(SerializedComposedEffect effect) {
            mPreamble = effect;
        }

        void setRepeating(SerializedComposedEffect effect) {
            mRepeating = effect;
        }

        boolean hasRepeatingSegment() {
            return mRepeating != null;
        }

        SerializedRepeatingEffect build() {
            return new SerializedRepeatingEffect(mPreamble, mRepeating);
        }
    }

    /** Parser implementation for {@link SerializedRepeatingEffect}. */
    static final class Parser {

        @NonNull
        static SerializedRepeatingEffect parseNext(@NonNull TypedXmlPullParser parser,
                @XmlConstants.Flags int flags) throws XmlParserException, IOException {
            XmlValidator.checkStartTag(parser, TAG_REPEATING_EFFECT);
            XmlValidator.checkTagHasNoUnexpectedAttributes(parser);

            Builder builder = new Builder();
            int outerDepth = parser.getDepth();

            boolean hasNestedTag = XmlReader.readNextTagWithin(parser, outerDepth);
            if (hasNestedTag && TAG_PREAMBLE.equals(parser.getName())) {
                builder.setPreamble(parseEffect(parser, TAG_PREAMBLE, flags));
                hasNestedTag = XmlReader.readNextTagWithin(parser, outerDepth);
            }

            XmlValidator.checkParserCondition(hasNestedTag,
                    "Missing %s tag in %s", TAG_REPEATING, TAG_REPEATING_EFFECT);
            builder.setRepeating(parseEffect(parser, TAG_REPEATING, flags));

            XmlValidator.checkParserCondition(builder.hasRepeatingSegment(),
                    "Unexpected %s tag with no repeating segment", TAG_REPEATING_EFFECT);

            // Consume tag
            XmlReader.readEndTag(parser, TAG_REPEATING_EFFECT, outerDepth);

            return builder.build();
        }

        private static SerializedComposedEffect parseEffect(TypedXmlPullParser parser,
                String tagName, int flags) throws XmlParserException, IOException {
            XmlValidator.checkStartTag(parser, tagName);
            XmlValidator.checkTagHasNoUnexpectedAttributes(parser);
            int vibrationTagDepth = parser.getDepth();
            XmlValidator.checkParserCondition(
                    XmlReader.readNextTagWithin(parser, vibrationTagDepth),
                    "Unsupported empty %s tag", tagName);

            SerializedComposedEffect effect;
            switch (parser.getName()) {
                case TAG_PREDEFINED_EFFECT:
                    effect = new SerializedComposedEffect(
                            SerializedPredefinedEffect.Parser.parseNext(parser, flags));
                    break;
                case TAG_PRIMITIVE_EFFECT:
                    effect = parsePrimitiveEffects(parser, vibrationTagDepth);
                    break;
                case TAG_WAVEFORM_ENTRY:
                    effect = parseWaveformEntries(parser, vibrationTagDepth);
                    break;
                case TAG_WAVEFORM_ENVELOPE_EFFECT:
                    effect = new SerializedComposedEffect(
                            SerializedWaveformEnvelopeEffect.Parser.parseNext(parser, flags));
                    break;
                case TAG_BASIC_ENVELOPE_EFFECT:
                    effect = new SerializedComposedEffect(
                            SerializedBasicEnvelopeEffect.Parser.parseNext(parser, flags));
                    break;
                default:
                    throw new XmlParserException("Unexpected tag " + parser.getName()
                            + " in vibration tag " + tagName);
            }

            // Consume tag
            XmlReader.readEndTag(parser, tagName, vibrationTagDepth);

            return effect;
        }

        private static SerializedComposedEffect parsePrimitiveEffects(TypedXmlPullParser parser,
                int vibrationTagDepth)
                throws IOException, XmlParserException {
            List<SerializedComposedEffect.SerializedSegment> primitives = new ArrayList<>();
            do { // First primitive tag already open
                primitives.add(SerializedCompositionPrimitive.Parser.parseNext(parser));
            } while (XmlReader.readNextTagWithin(parser, vibrationTagDepth));
            return new SerializedComposedEffect(primitives.toArray(
                    new SerializedComposedEffect.SerializedSegment[
                            primitives.size()]));
        }

        private static SerializedComposedEffect parseWaveformEntries(TypedXmlPullParser parser,
                int vibrationTagDepth)
                throws IOException, XmlParserException {
            SerializedWaveformEffectEntries.Builder waveformBuilder =
                    new SerializedWaveformEffectEntries.Builder();
            do { // First waveform-entry tag already open
                SerializedWaveformEffectEntries
                        .Parser.parseWaveformEntry(parser, waveformBuilder);
            } while (XmlReader.readNextTagWithin(parser, vibrationTagDepth));
            XmlValidator.checkParserCondition(waveformBuilder.hasNonZeroDuration(),
                    "Unexpected %s tag with total duration zero", TAG_WAVEFORM_ENTRY);
            return new SerializedComposedEffect(waveformBuilder.build());
        }
    }
}
