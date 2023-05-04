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

import static com.android.internal.vibrator.persistence.XmlConstants.ATTRIBUTE_NAME;
import static com.android.internal.vibrator.persistence.XmlConstants.NAMESPACE;
import static com.android.internal.vibrator.persistence.XmlConstants.TAG_PREDEFINED_EFFECT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.VibrationEffect;

import com.android.internal.vibrator.persistence.SerializedVibrationEffect.SerializedSegment;
import com.android.internal.vibrator.persistence.XmlConstants.PredefinedEffectName;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import java.io.IOException;

/**
 * Serialized representation of a predefined effect created via
 * {@link VibrationEffect#createPredefined(int)}.
 *
 * @hide
 */
final class SerializedPredefinedEffect implements SerializedSegment {

    private final PredefinedEffectName mEffectName;

    SerializedPredefinedEffect(PredefinedEffectName effectName) {
        mEffectName = effectName;
    }

    @Override
    public void deserializeIntoComposition(@NonNull VibrationEffect.Composition composition) {
        composition.addEffect(VibrationEffect.createPredefined(mEffectName.getEffectId()));
    }

    @Override
    public void write(@NonNull TypedXmlSerializer serializer) throws IOException {
        serializer.startTag(NAMESPACE, TAG_PREDEFINED_EFFECT);
        serializer.attribute(NAMESPACE, ATTRIBUTE_NAME, mEffectName.toString());
        serializer.endTag(NAMESPACE, TAG_PREDEFINED_EFFECT);
    }

    @Override
    public String toString() {
        return "SerializedPredefinedEffect{"
                + "effectName=" + mEffectName
                + '}';
    }

    /** Parser implementation for {@link SerializedPredefinedEffect}. */
    static final class Parser {

        @NonNull
        static SerializedPredefinedEffect parseNext(@NonNull TypedXmlPullParser parser)
                throws XmlParserException, IOException {
            XmlValidator.checkStartTag(parser, TAG_PREDEFINED_EFFECT);
            XmlValidator.checkTagHasNoUnexpectedAttributes(parser, ATTRIBUTE_NAME);

            PredefinedEffectName effectName = parseEffectName(
                    parser.getAttributeValue(NAMESPACE, ATTRIBUTE_NAME));

            // Consume tag
            XmlReader.readEndTag(parser);

            return new SerializedPredefinedEffect(effectName);
        }

        @NonNull
        private static PredefinedEffectName parseEffectName(@Nullable String name)
                throws XmlParserException {
            if (name == null) {
                throw new XmlParserException("Missing predefined effect name");
            }
            PredefinedEffectName effectName = PredefinedEffectName.findByName(name);
            if (effectName == null) {
                throw new XmlParserException("Unexpected predefined effect name " + name);
            }
            return effectName;
        }
    }
}
