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

/**
 * Parses XML into a {@link VibrationEffect}.
 *
 * <p>This parser supports a root element that represent a single vibration effect as follows:
 *
 * * Predefined vibration effects
 *
 * <pre>VibrationEffect
 *   {@code
 *     <vibration>
 *       <predefined-effect id="0" />
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
 *       <primitive-effect id="1" />
 *       <primitive-effect id="2" scale="0.8" />
 *       <primitive-effect id="3" delayMs="50" />
 *       <primitive-effect id="2" scale="0.5" delayMs="100" />
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

            // Parse root tag as a vibration effect.
            XmlSerializedVibration<VibrationEffect> serializable =
                    VibrationEffectXmlParser.parseTag(parser);

            // Ensure XML ends after root tag is consumed.
            XmlReader.readDocumentEndTag(parser);

            return serializable.deserialize();
        } catch (XmlParserException e) {
            Slog.w(TAG, "Error parsing vibration XML", e);
            return null;
        }
    }

    private VibrationXmlParser() {
    }
}
