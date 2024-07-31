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

import static android.os.vibrator.Flags.FLAG_VIBRATION_XML_APIS;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.VibrationEffect;
import android.util.Xml;

import com.android.internal.vibrator.persistence.VibrationEffectXmlParser;
import com.android.internal.vibrator.persistence.XmlConstants;
import com.android.internal.vibrator.persistence.XmlParserException;
import com.android.internal.vibrator.persistence.XmlReader;
import com.android.internal.vibrator.persistence.XmlValidator;
import com.android.modules.utils.TypedXmlPullParser;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses XML into a {@link VibrationEffect}.
 *
 * <p>This parser supports a root element that represent a single vibration effect or a selection
 * list of vibration effects.
 *
 * <p>Use the schema at core/xsd/vibrator/vibration/vibration.xsd.
 *
 * <p>When the root element represents a single vibration effect, the format is as follows:
 *
 * * Predefined vibration effects
 *
 * <pre>
 *   {@code
 *     <vibration-effect>
 *       <predefined-effect name="click" />
 *     </vibration-effect>
 *   }
 * </pre>
 *
 * * Waveform vibration effects
 *
 * <pre>
 *   {@code
 *     <vibration-effect>
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
 *     </vibration-effect>
 *   }
 * </pre>
 *
 * * Primitive composition effects
 *
 * <pre>
 *   {@code
 *     <vibration-effect>
 *       <primitive-effect name="click" />
 *       <primitive-effect name="slow_rise" scale="0.8" />
 *       <primitive-effect name="quick_fall" delayMs="50" />
 *       <primitive-effect name="tick" scale="0.5" delayMs="100" />
 *     </vibration-effect>
 *   }
 * </pre>
 *
 * <p>When the root element represents a selection list of vibration effects, the root tag should be
 * a <vibration-select> tag. The root element should contain a list of vibration serializations.
 * Each vibration within the root-element should follow the format discussed for the
 * <vibration-effect> tag above. See example below:
 *
 * <pre>
 *   {@code
 *     <vibration-select>
 *       <vibration-effect>
 *         <predefined-effect name="click" />
 *       </vibration-effect>
 *       <vibration-effect>
 *         <waveform-effect>
 *           <waveform-entry amplitude="default" durationMs="10" />
 *         </waveform-effect>
 *       </vibration-effect>
 *     </vibration-select>
 *   }
 * </pre>
 *
 * @hide
 */
@SystemApi
@FlaggedApi(FLAG_VIBRATION_XML_APIS)
public final class VibrationXmlParser {

    /**
     * The MIME type for a xml holding a vibration.
     *
     * <p>This should match the type registered at android.mime.types.
     *
     * @hide
     */
    public static final String APPLICATION_VIBRATION_XML_MIME_TYPE =
            "application/vnd.android.haptics.vibration+xml";

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
     * Returns whether this parser supports parsing files of the given MIME type.
     *
     * <p>Returns false for {@code null} value.
     *
     * <p><em>Note: MIME type matching in the Android framework is case-sensitive, unlike the formal
     * RFC definitions. As a result, you should always write these elements with lower case letters,
     * or use {@link android.content.Intent#normalizeMimeType} to ensure that they are converted to
     * lower case.</em>
     *
     * @hide
     */
    public static boolean isSupportedMimeType(@Nullable String mimeType) {
        // NOTE: prefer using MimeTypeFilter.matches() if MIME_TYPE_VIBRATION_XML becomes a filter
        // or if more than one MIME type is supported by this parser.
        return APPLICATION_VIBRATION_XML_MIME_TYPE.equals(mimeType);
    }

    /**
     * Parses XML content from given input stream into a {@link ParsedVibration}.
     *
     * <p>It supports both the "vibration-effect" and "vibration-select" root tags.
     * <ul>
     *     <li>If "vibration-effect" is the root tag, the serialization provided should contain a
     *         valid serialization for a single vibration.
     *     <li>If "vibration-select" is the root tag, the serialization may contain one or more
     *         valid vibration serializations.
     * </ul>
     *
     * <p>After parsing, it returns a {@link ParsedVibration} that opaquely represents the parsed
     * vibration(s), and the caller can get a concrete {@link VibrationEffect} by resolving this
     * result to a specific vibrator.
     *
     * <p>This parser fails with an exception if the content of the input stream does not follow the
     * schema or has unsupported values.
     *
     * @return a {@link ParsedVibration}
     * @throws IOException error reading from given {@link InputStream} or parsing the content.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_VIBRATION_XML_APIS)
    @NonNull
    public static ParsedVibration parse(@NonNull InputStream inputStream) throws IOException {
        return parseDocument(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
    }

    /**
     * Parses XML content from given input stream into a single {@link VibrationEffect}.
     *
     * <p>This method parses an XML content that contains a single, complete {@link VibrationEffect}
     * serialization. As such, the root tag must be a "vibration-effect" tag.
     *
     * <p>This parser fails with an exception if the content of the input stream does not follow the
     * schema or has unsupported values.
     *
     * @return the parsed {@link VibrationEffect}
     * @throws IOException error reading from given {@link InputStream} or parsing the content.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_VIBRATION_XML_APIS)
    @NonNull
    public static VibrationEffect parseVibrationEffect(@NonNull InputStream inputStream)
            throws IOException {
        return parseVibrationEffect(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
    }

    /**
     * Parses XML content from given {@link Reader} into a {@link VibrationEffect}.
     *
     * <p>Same as {@link #parseVibrationEffect(InputStream)}, but with a {@link Reader}.
     *
     * @hide
     */
    @NonNull
    public static VibrationEffect parseVibrationEffect(@NonNull Reader reader) throws IOException {
        return parseVibrationEffect(reader, /* flags= */ 0);
    }

    /**
     * Parses XML content from given {@link Reader} into a {@link VibrationEffect}.
     *
     * <p>Same as {@link #parseVibrationEffect(Reader)}, with extra flags to control the parsing
     * behavior.
     *
     * @hide
     */
    @NonNull
    public static VibrationEffect parseVibrationEffect(@NonNull Reader reader, @Flags int flags)
            throws IOException {
        return parseDocumentInternal(reader, flags,
                VibrationXmlParser::parseVibrationEffectInternal);
    }

    /**
     * Parses XML content from given {@link Reader} into a {@link ParsedVibration}.
     *
     * <p>Same as {@link #parse(InputStream)}, but with a {@link Reader}.
     *
     * @hide
     */
    @NonNull
    public static ParsedVibration parseDocument(@NonNull Reader reader) throws IOException {
        return parseDocument(reader, /* flags= */ 0);
    }

    /**
     * Parses XML content from given {@link Reader} into a {@link ParsedVibration}.
     *
     * <p>Same as {@link #parseDocument(Reader)}, with extra flags to control the parsing behavior.
     *
     * @hide
     */
    @NonNull
    public static ParsedVibration parseDocument(@NonNull Reader reader, @Flags int flags)
            throws IOException {
        return parseDocumentInternal(reader, flags, VibrationXmlParser::parseElementInternal);
    }

    /**
     * Parses XML content from a given open {@link TypedXmlPullParser} into a
     * {@link ParsedVibration}.
     *
     * <p>Same as {@link #parseDocument(Reader, int)}, but, instead of parsing the full XML content,
     * it takes a parser that points to either a "vibration-effect" or a "vibration-select" start
     * tag. No other parser position, including start of document, is considered valid.
     *
     * <p>This method parses until an end "vibration-effect" or "vibration-select" tag (depending
     * on the start tag found at the start of parsing). After a successful parsing, the parser
     * will point to the end tag.
     *
     * @throws IOException error parsing from given {@link TypedXmlPullParser}.
     *         The given {@code parser} might be pointing to a child XML tag that caused the parser
     *         failure.
     *
     * @hide
     */
    @NonNull
    public static ParsedVibration parseElement(@NonNull TypedXmlPullParser parser, @Flags int flags)
            throws IOException {
        try {
            return parseElementInternal(parser, flags);
        } catch (XmlParserException e) {
            throw new ParseFailedException(e);
        }
    }

    @NonNull
    private static ParsedVibration parseElementInternal(
                @NonNull TypedXmlPullParser parser, @Flags int flags)
                        throws IOException, XmlParserException {
        XmlValidator.checkStartTag(parser);

        String tagName = parser.getName();
        switch(tagName) {
            case XmlConstants.TAG_VIBRATION_EFFECT:
                return new ParsedVibration(parseVibrationEffectInternal(parser, flags));
            case XmlConstants.TAG_VIBRATION_SELECT:
                return parseVibrationSelectInternal(parser, flags);
            default:
                throw new ParseFailedException(
                        "Unexpected tag " + tagName + " when parsing a vibration");
        }
    }

    @NonNull
    private static ParsedVibration parseVibrationSelectInternal(
            @NonNull TypedXmlPullParser parser, @Flags int flags)
                    throws IOException, XmlParserException {
        XmlValidator.checkStartTag(parser, XmlConstants.TAG_VIBRATION_SELECT);
        XmlValidator.checkTagHasNoUnexpectedAttributes(parser);

        int rootDepth = parser.getDepth();
        List<VibrationEffect> effects = new ArrayList<>();
        while (XmlReader.readNextTagWithin(parser, rootDepth)) {
            effects.add(parseVibrationEffectInternal(parser, flags));
        }
        return new ParsedVibration(effects);
    }

    @NonNull
    private static VibrationEffect parseVibrationEffectInternal(
            @NonNull TypedXmlPullParser parser, @Flags int flags)
                    throws IOException, XmlParserException {
        int parserFlags = 0;
        if ((flags & VibrationXmlParser.FLAG_ALLOW_HIDDEN_APIS) != 0) {
            parserFlags |= XmlConstants.FLAG_ALLOW_HIDDEN_APIS;
        }
        return VibrationEffectXmlParser.parseTag(parser, parserFlags).deserialize();
    }

    /**
     * This method parses a whole XML document (provided through a {@link Reader}). The root tag is
     * parsed as per a provided {@link ElementParser}.
     */
    @NonNull
    private static <T> T parseDocumentInternal(
            @NonNull Reader reader, @Flags int flags, ElementParser<T> parseLogic)
            throws IOException {
        try {
            TypedXmlPullParser parser = Xml.newFastPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
            parser.setInput(reader);

            // Ensure XML starts with a document start tag.
            XmlReader.readDocumentStart(parser);

            // Parse root tag.
            T result = parseLogic.parse(parser, flags);

            // Ensure XML ends after root tag is consumed.
            XmlReader.readDocumentEndTag(parser);

            return result;
        } catch (XmlPullParserException e) {
            throw new ParseFailedException("Error initializing XMLPullParser", e);
        } catch (XmlParserException e) {
            throw new ParseFailedException(e);
        }
    }

    /** Encapsulate a logic to parse an XML element from an open parser. */
    private interface ElementParser<T> {
        /** Parses a single XML element starting from the current position of the {@code parser}. */
        @NonNull
        T parse(@NonNull TypedXmlPullParser parser, @Flags int flags)
                throws IOException, XmlParserException;
    }

    /**
     * Represents an error while parsing a vibration XML input.
     *
     * @hide
     */
    @TestApi
    public static final class ParseFailedException extends IOException {
        private ParseFailedException(String message) {
            super(message);
        }

        private ParseFailedException(XmlParserException parserException) {
            this(parserException.getMessage(), parserException);
        }

        private ParseFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private VibrationXmlParser() {
    }
}
