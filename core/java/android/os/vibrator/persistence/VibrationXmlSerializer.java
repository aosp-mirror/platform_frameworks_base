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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.TestApi;
import android.os.CombinedVibration;
import android.os.VibrationEffect;
import android.util.Xml;

import com.android.internal.vibrator.persistence.VibrationEffectXmlSerializer;
import com.android.internal.vibrator.persistence.XmlConstants;
import com.android.internal.vibrator.persistence.XmlSerializedVibration;
import com.android.internal.vibrator.persistence.XmlSerializerException;
import com.android.internal.vibrator.persistence.XmlValidator;
import com.android.modules.utils.TypedXmlSerializer;

import java.io.IOException;
import java.io.Writer;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Serializes {@link CombinedVibration} and {@link VibrationEffect} instances to XML.
 *
 * <p>This uses the same schema expected by the {@link VibrationXmlParser}.
 *
 * @hide
 */
@TestApi
public final class VibrationXmlSerializer {

    /**
     * Allows {@link VibrationEffect} instances created via non-public APIs to be parsed/serialized.
     *
     * <p>Note that the XML schema for non-public APIs is not backwards compatible. This is intended
     * for loading custom {@link VibrationEffect} configured per device and platform version, not
     * to be restored from old platform versions or from different devices.
     *
     * @hide
     */
    public static final int FLAG_ALLOW_HIDDEN_APIS = 1 << 0;

    /**
     * Writes a more human-readable output XML.
     *
     * <p>This will be less compact as it includes extra whitespace for things like indentation.
     *
     * @hide
     */
    public static final int FLAG_PRETTY_PRINT = 1 << 1;

    /** @hide */
    @IntDef(prefix = { "FLAG_" }, flag = true, value = {
            FLAG_PRETTY_PRINT,
            FLAG_ALLOW_HIDDEN_APIS
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Flags {}

    private static final String XML_ENCODING = Xml.Encoding.UTF_8.name();
    private static final String XML_FEATURE_INDENT_OUTPUT =
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
        serialize(effect, writer, /* flags= */ 0);
    }

    /**
     * Serializes a {@link VibrationEffect} to XML and writes output to given {@link Writer}.
     *
     * <p>Same as {@link #serialize(VibrationEffect, Writer)}, with extra flags to control the
     * serialization behavior.
     *
     * @hide
     */
    public static void serialize(@NonNull VibrationEffect effect, @NonNull Writer writer,
            @Flags int flags) throws SerializationFailedException, IOException {
        // Serialize effect first to fail early.
        XmlSerializedVibration<VibrationEffect> serializedVibration =
                toSerializedVibration(effect, flags);
        TypedXmlSerializer xmlSerializer = Xml.newFastSerializer();
        xmlSerializer.setFeature(XML_FEATURE_INDENT_OUTPUT, (flags & FLAG_PRETTY_PRINT) != 0);
        xmlSerializer.setOutput(writer);
        xmlSerializer.startDocument(XML_ENCODING, /* standalone= */ false);
        serializedVibration.write(xmlSerializer);
        xmlSerializer.endDocument();
    }

    private static XmlSerializedVibration<VibrationEffect> toSerializedVibration(
            VibrationEffect effect, @Flags int flags) throws SerializationFailedException {
        XmlSerializedVibration<VibrationEffect> serializedVibration;
        int serializerFlags = 0;
        if ((flags & FLAG_ALLOW_HIDDEN_APIS) != 0) {
            serializerFlags |= XmlConstants.FLAG_ALLOW_HIDDEN_APIS;
        }

        try {
            serializedVibration = VibrationEffectXmlSerializer.serialize(effect, serializerFlags);
            XmlValidator.checkSerializedVibration(serializedVibration, effect);
        } catch (XmlSerializerException e) {
            // Serialization failed or created incomplete representation, fail before writing.
            throw new SerializationFailedException(effect, e);
        }

        return serializedVibration;
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
