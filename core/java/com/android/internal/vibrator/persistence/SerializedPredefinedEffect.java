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

import static com.android.internal.vibrator.persistence.XmlConstants.ATTRIBUTE_FALLBACK;
import static com.android.internal.vibrator.persistence.XmlConstants.ATTRIBUTE_NAME;
import static com.android.internal.vibrator.persistence.XmlConstants.NAMESPACE;
import static com.android.internal.vibrator.persistence.XmlConstants.TAG_PREDEFINED_EFFECT;

import android.annotation.NonNull;
import android.os.VibrationEffect;
import android.os.vibrator.PrebakedSegment;

import com.android.internal.vibrator.persistence.SerializedComposedEffect.SerializedSegment;
import com.android.internal.vibrator.persistence.XmlConstants.PredefinedEffectName;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import java.io.IOException;

/**
 * Serialized representation of a predefined effect created via
 * {@link VibrationEffect#get(int, boolean)}.
 *
 * @hide
 */
final class SerializedPredefinedEffect implements SerializedSegment {

    @NonNull
    private final PredefinedEffectName mEffectName;
    private final boolean mShouldFallback;

    SerializedPredefinedEffect(PredefinedEffectName effectName, boolean shouldFallback) {
        mEffectName = effectName;
        mShouldFallback = shouldFallback;
    }

    @Override
    public void deserializeIntoComposition(@NonNull VibrationEffect.Composition composition) {
        composition.addEffect(VibrationEffect.get(mEffectName.getEffectId(), mShouldFallback));
    }

    @Override
    public void write(@NonNull TypedXmlSerializer serializer) throws IOException {
        serializer.startTag(NAMESPACE, TAG_PREDEFINED_EFFECT);
        serializer.attribute(NAMESPACE, ATTRIBUTE_NAME, mEffectName.toString());
        if (mShouldFallback != PrebakedSegment.DEFAULT_SHOULD_FALLBACK) {
            serializer.attributeBoolean(NAMESPACE, ATTRIBUTE_FALLBACK, mShouldFallback);
        }
        serializer.endTag(NAMESPACE, TAG_PREDEFINED_EFFECT);
    }

    @Override
    public String toString() {
        return "SerializedPredefinedEffect{"
                + "name=" + mEffectName
                + ", fallback=" + mShouldFallback
                + '}';
    }

    /** Parser implementation for {@link SerializedPredefinedEffect}. */
    static final class Parser {

        @NonNull
        static SerializedPredefinedEffect parseNext(@NonNull TypedXmlPullParser parser,
                @XmlConstants.Flags int flags) throws XmlParserException, IOException {
            XmlValidator.checkStartTag(parser, TAG_PREDEFINED_EFFECT);

            boolean allowHidden = (flags & XmlConstants.FLAG_ALLOW_HIDDEN_APIS) != 0;
            if (allowHidden) {
                XmlValidator.checkTagHasNoUnexpectedAttributes(parser, ATTRIBUTE_NAME,
                        ATTRIBUTE_FALLBACK);
            } else {
                XmlValidator.checkTagHasNoUnexpectedAttributes(parser, ATTRIBUTE_NAME);
            }

            String nameAttr = parser.getAttributeValue(NAMESPACE, ATTRIBUTE_NAME);
            if (nameAttr == null) {
                throw new XmlParserException("Missing predefined effect name");
            }
            PredefinedEffectName effectName = PredefinedEffectName.findByName(nameAttr, flags);
            if (effectName == null) {
                throw new XmlParserException("Unexpected predefined effect name " + nameAttr);
            }

            boolean defaultFallback = PrebakedSegment.DEFAULT_SHOULD_FALLBACK;
            boolean fallback = allowHidden
                    ? parser.getAttributeBoolean(NAMESPACE, ATTRIBUTE_FALLBACK, defaultFallback)
                    : defaultFallback;

            // Consume tag
            XmlReader.readEndTag(parser);

            return new SerializedPredefinedEffect(effectName, fallback);
        }
    }
}
