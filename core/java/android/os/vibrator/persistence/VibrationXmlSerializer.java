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

package android.os.vibrator.persistence;

import android.annotation.NonNull;
import android.annotation.TestApi;
import android.os.CombinedVibration;
import android.os.VibrationEffect;
import android.util.Xml;

import com.android.internal.vibrator.persistence.VibrationEffectXmlSerializer;
import com.android.internal.vibrator.persistence.XmlSerializedVibration;
import com.android.internal.vibrator.persistence.XmlSerializerException;
import com.android.internal.vibrator.persistence.XmlValidator;
import com.android.modules.utils.TypedXmlSerializer;

import java.io.IOException;
import java.io.Writer;

/**
 * Serializes {@link CombinedVibration} and {@link VibrationEffect} instances to XML.
 *
 * <p>This uses the same schema expected by the {@link VibrationXmlParser}.
 *
 * @hide
 */
@TestApi
public final class VibrationXmlSerializer {
    private static final String TAG = "VibrationXmlSerializer";

    private static final String SERIALIZER_ENCODING = Xml.Encoding.UTF_8.name();
    private static final String SERIALIZER_FEATURE_INDENT_OUTPUT =
            "http://xmlpull.org/v1/doc/features.html#indent-output";

    /**
     * Serializes a {@link VibrationEffect} to XML and writes output to given {@link Writer}.
     *
     * <p>This method will only write into the {@link Writer} if the effect can successfully
     * be represented by the XML serialization. It will return {@code false} otherwise, and not
     * write any data.
     *
     * @throws SerializationFailedException serialization of input effect failed, no data was
     *                                      written into given {@link Writer}
     * @throws IOException error writing to given {@link Writer}
     *
     * @hide
     */
    @TestApi
    public static void serialize(@NonNull VibrationEffect effect, @NonNull Writer writer)
            throws SerializationFailedException, IOException {
        XmlSerializedVibration<VibrationEffect> serializableEffect;
        try {
            serializableEffect = VibrationEffectXmlSerializer.serialize(effect);
            XmlValidator.checkSerializedVibration(serializableEffect, effect);
        } catch (XmlSerializerException e) {
            // Serialization failed or created incomplete representation, fail before writing.
            throw new SerializationFailedException(effect, e);
        }

        TypedXmlSerializer xmlSerializer = Xml.newFastSerializer();
        xmlSerializer.setFeature(SERIALIZER_FEATURE_INDENT_OUTPUT, false);
        xmlSerializer.setOutput(writer);
        xmlSerializer.startDocument(SERIALIZER_ENCODING, /* standalone= */ false);
        serializableEffect.write(xmlSerializer);
        xmlSerializer.endDocument();
    }

    /**
     * Exception thrown when a {@link VibrationEffect} instance serialization fails.
     *
     * @hide
     */
    @TestApi
    public static final class SerializationFailedException extends IllegalStateException {
        SerializationFailedException(VibrationEffect effect, Throwable cause) {
            super("Serialization failed for vibration effect " + effect, cause);
        }
    }

    private VibrationXmlSerializer() {
    }
}
