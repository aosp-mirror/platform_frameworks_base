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

import static com.android.internal.vibrator.persistence.XmlConstants.ATTRIBUTE_DELAY_MS;
import static com.android.internal.vibrator.persistence.XmlConstants.ATTRIBUTE_NAME;
import static com.android.internal.vibrator.persistence.XmlConstants.ATTRIBUTE_SCALE;
import static com.android.internal.vibrator.persistence.XmlConstants.NAMESPACE;
import static com.android.internal.vibrator.persistence.XmlConstants.TAG_PRIMITIVE_EFFECT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.VibrationEffect;
import android.os.vibrator.PrimitiveSegment;

import com.android.internal.vibrator.persistence.SerializedComposedEffect.SerializedSegment;
import com.android.internal.vibrator.persistence.XmlConstants.PrimitiveEffectName;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import java.io.IOException;

/**
 * Serialized representation of a single {@link PrimitiveSegment} created via
 * {@link VibrationEffect.Composition#addPrimitive(int, float, int)}.
 *
 * @hide
 */
final class SerializedCompositionPrimitive implements SerializedSegment {

    @NonNull
    private final PrimitiveEffectName mPrimitiveName;
    private final float mPrimitiveScale;
    private final int mPrimitiveDelayMs;

    SerializedCompositionPrimitive(PrimitiveEffectName primitiveName, float scale, int delayMs) {
        mPrimitiveName = primitiveName;
        mPrimitiveScale = scale;
        mPrimitiveDelayMs = delayMs;
    }

    @Override
    public void deserializeIntoComposition(@NonNull VibrationEffect.Composition composition) {
        composition.addPrimitive(mPrimitiveName.getPrimitiveId(), mPrimitiveScale,
                mPrimitiveDelayMs);
    }

    @Override
    public void write(@NonNull TypedXmlSerializer serializer) throws IOException {
        serializer.startTag(NAMESPACE, TAG_PRIMITIVE_EFFECT);
        serializer.attribute(NAMESPACE, ATTRIBUTE_NAME, mPrimitiveName.toString());

        if (Float.compare(mPrimitiveScale, PrimitiveSegment.DEFAULT_SCALE) != 0) {
            serializer.attributeFloat(NAMESPACE, ATTRIBUTE_SCALE, mPrimitiveScale);
        }

        if (mPrimitiveDelayMs != PrimitiveSegment.DEFAULT_DELAY_MILLIS) {
            serializer.attributeInt(NAMESPACE, ATTRIBUTE_DELAY_MS, mPrimitiveDelayMs);
        }

        serializer.endTag(NAMESPACE, TAG_PRIMITIVE_EFFECT);
    }

    @Override
    public String toString() {
        return "SerializedCompositionPrimitive{"
                + "name=" + mPrimitiveName
                + ", scale=" + mPrimitiveScale
                + ", delayMs=" + mPrimitiveDelayMs
                + '}';
    }

    /** Parser implementation for {@link SerializedCompositionPrimitive}. */
    static final class Parser {

        @NonNull
        static SerializedCompositionPrimitive parseNext(@NonNull TypedXmlPullParser parser)
                throws XmlParserException, IOException {
            XmlValidator.checkStartTag(parser, TAG_PRIMITIVE_EFFECT);
            XmlValidator.checkTagHasNoUnexpectedAttributes(parser,
                    ATTRIBUTE_NAME, ATTRIBUTE_DELAY_MS, ATTRIBUTE_SCALE);

            PrimitiveEffectName primitiveName = parsePrimitiveName(
                    parser.getAttributeValue(NAMESPACE, ATTRIBUTE_NAME));
            float scale = XmlReader.readAttributeFloatInRange(
                    parser, ATTRIBUTE_SCALE, 0, 1, PrimitiveSegment.DEFAULT_SCALE);
            int delayMs = XmlReader.readAttributeIntNonNegative(
                    parser, ATTRIBUTE_DELAY_MS, PrimitiveSegment.DEFAULT_DELAY_MILLIS);

            // Consume tag
            XmlReader.readEndTag(parser);

            return new SerializedCompositionPrimitive(primitiveName, scale, delayMs);
        }

        @NonNull
        private static PrimitiveEffectName parsePrimitiveName(@Nullable String name)
                throws XmlParserException {
            if (name == null) {
                throw new XmlParserException("Missing primitive effect name");
            }
            PrimitiveEffectName effectName = PrimitiveEffectName.findByName(name);
            if (effectName == null) {
                throw new XmlParserException("Unexpected primitive effect name " + name);
            }
            return effectName;
        }
    }
}
