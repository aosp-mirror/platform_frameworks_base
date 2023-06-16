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
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.os.VibrationEffect;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.vibrator.persistence.VibrationEffectXmlParser;
import com.android.internal.vibrator.persistence.XmlConstants;
import com.android.internal.vibrator.persistence.XmlParserException;
import com.android.internal.vibrator.persistence.XmlReader;
import com.android.internal.vibrator.persistence.XmlSerializedVibration;
import com.android.modules.utils.TypedXmlPullParser;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.Reader;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Parses XML into a {@link VibrationEffect}.
 *
 * <p>This parser supports a root element that represent a single vibration effect as follows:
 *
 * * Predefined vibration effects
 *
 * <pre>
 *   {@code
 *     <vibration>
 *       <predefined-effect name="click" />
 *     </vibration>
 *   }
 * </pre>
 *
 * * Waveform vibration effects
 *
 * <pre>
 *   {@code
 *     <vibration>
 *       <waveform-effect>
 *         <waveform-entry amplitude="default" durationMs="10" />
 *         <waveform-entry amplitude="0" durationMs="10" />
 *         <waveform-entry amplitude="255" durationMs="100" />
 *         <repeating>
 *           <waveform-entry amplitude="128" durationMs="30" />
 *           <waveform-entry amplitude="192" durationMs="60" />
 *           <waveform-entry amplitude="255" durationMs="20" />
 *         </repeating>
 *       </waveform-effect>
 *     </vibration>
 *   }
 * </pre>
 *
 * * Primitive composition effects
 *
 * <pre>
 *   {@code
 *     <vibration>
 *       <primitive-effect name="click" />
 *       <primitive-effect name="slow_rise" scale="0.8" />
 *       <primitive-effect name="quick_fall" delayMs="50" />
 *       <primitive-effect name="tick" scale="0.5" delayMs="100" />
 *     </vibration>
 *   }
 * </pre>
 *
 * @hide
 */
@TestApi
public final class VibrationXmlParser {
    private static final String TAG = "VibrationXmlParser";

    /**
     * Allows {@link VibrationEffect} instances created via non-public APIs to be parsed/serialized.
     *
     * <p>Note that the XML schema for non-public APIs is not backwards compatible. This is intended
     * for loading custom {@link VibrationEffect} configured per device and platform version, not
     * to be restored from old platform versions.
     *
     * @hide
     */
    public static final int FLAG_ALLOW_HIDDEN_APIS = 1 << 0; // Same as VibrationXmlSerializer

    /** @hide */
    @IntDef(prefix = { "FLAG_" }, flag = true, value = {
            FLAG_ALLOW_HIDDEN_APIS
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Flags {}

    /**
     * Parses XML content from given input stream into a {@link VibrationEffect}.
     *
     * <p>This parser fails silently and returns {@code null} if the content of the input stream
     * does not follow the schema or has unsupported values.
     *
     * @return the {@link VibrationEffect} if parsed successfully, {@code null} otherwise.
     * @throws IOException error reading from given {@link Reader}
     *
     * @hide
     */
    @TestApi
    @Nullable
    public static VibrationEffect parse(@NonNull Reader reader) throws IOException {
        return parse(reader, /* flags= */ 0);
    }

    /**
     * Parses XML content from given input stream into a {@link VibrationEffect}.
     *
     * <p>Same as {@link #parse(Reader)}, with extra flags to control the parsing behavior.
     *
     * @hide
     */
    @Nullable
    public static VibrationEffect parse(@NonNull Reader reader, @Flags int flags)
            throws IOException {
        TypedXmlPullParser parser = Xml.newFastPullParser();

        try {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
            parser.setInput(reader);
        } catch (XmlPullParserException e) {
            throw new RuntimeException("An error occurred while setting up the XML parser", e);
        }

        try {
            // Ensure XML starts with expected root tag.
            XmlReader.readDocumentStartTag(parser, XmlConstants.TAG_VIBRATION);

            int parserFlags = 0;
            if ((flags & FLAG_ALLOW_HIDDEN_APIS) != 0) {
                parserFlags |= XmlConstants.FLAG_ALLOW_HIDDEN_APIS;
            }

            // Parse root tag as a vibration effect.
            XmlSerializedVibration<VibrationEffect> serializedVibration =
                    VibrationEffectXmlParser.parseTag(parser, parserFlags);

            // Ensure XML ends after root tag is consumed.
            XmlReader.readDocumentEndTag(parser);

            return serializedVibration.deserialize();
        } catch (XmlParserException e) {
            Slog.w(TAG, "Error parsing vibration XML", e);
            return null;
        }
    }

    private VibrationXmlParser() {
    }
}
