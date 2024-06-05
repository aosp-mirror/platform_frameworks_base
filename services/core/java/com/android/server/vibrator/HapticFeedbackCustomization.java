/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.server.vibrator;

import android.annotation.Nullable;
import android.content.res.Resources;
import android.os.VibrationEffect;
import android.os.VibratorInfo;
import android.os.vibrator.Flags;
import android.os.vibrator.persistence.ParsedVibration;
import android.os.vibrator.persistence.VibrationXmlParser;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.vibrator.persistence.XmlParserException;
import com.android.internal.vibrator.persistence.XmlReader;
import com.android.internal.vibrator.persistence.XmlValidator;
import com.android.modules.utils.TypedXmlPullParser;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

/**
 * Class that loads custom {@link VibrationEffect} to be performed for each
 * {@link HapticFeedbackConstants} key.
 *
 * <p>The system has its default logic to get the {@link VibrationEffect} that will be played for a
 * given haptic feedback constant. Devices may choose to override some or all of these supported
 * haptic feedback vibrations via a customization XML.
 *
 * <p>The XML simply provides a mapping of a constant from {@link HapticFeedbackConstants} to its
 * corresponding {@link VibrationEffect}. Its root tag should be `<haptic-feedback-constants>`. It
 * should have one or more entries for customizing a haptic feedback constant. A customization is
 * started by a `<constant id="X">` tag (where `X` is the haptic feedback constant being customized
 * in this entry) and closed by </constant>. Between these two tags, there should be a valid XML
 * serialization of a non-repeating {@link VibrationEffect}. Such a valid vibration serialization
 * should be parse-able by {@link VibrationXmlParser}.
 *
 * The example below represents a valid customization for effect IDs 10 and 11.
 *
 * <pre>
 *   {@code
 *     <haptic-feedback-constants>
 *          <constant id="10">
 *              // Valid Vibration Serialization
 *          </constant>
 *          <constant id="11">
 *              // Valid Vibration Serialization
 *          </constant>
 *     </haptic-feedback-constants>
 *   }
 * </pre>
 *
 * <p>After a successful parsing of the customization XML file, it returns a {@link SparseArray}
 * that maps each customized haptic feedback effect ID to its respective {@link VibrationEffect}.
 */
final class HapticFeedbackCustomization {
    private static final String TAG = "HapticFeedbackCustomization";

    /** The outer-most tag for haptic feedback customizations.  */
    private static final String TAG_CONSTANTS = "haptic-feedback-constants";
    /** The tag defining a customization for a single haptic feedback constant. */
    private static final String TAG_CONSTANT = "constant";

    /**
     * Attribute for {@link TAG_CONSTANT}, specifying the haptic feedback constant to
     * customize.
     */
    private static final String ATTRIBUTE_ID = "id";

    /**
     * Parses the haptic feedback vibration customization XML file for the device, and provides a
     * mapping of the customized effect IDs to their respective {@link VibrationEffect}s.
     *
     * <p>This is potentially expensive, so avoid calling repeatedly. One call is enough, and the
     * caller should process the returned mapping (if any) for further queries.
     *
     * @param res {@link Resources} object to be used for reading the device's resources.
     * @return a {@link SparseArray} that maps each customized haptic feedback effect ID to its
     *      respective {@link VibrationEffect}, or {@code null}, if the device has not configured
     *      a file for haptic feedback constants customization.
     * @throws {@link IOException} if an IO error occurs while parsing the customization XML.
     * @throws {@link CustomizationParserException} for any non-IO error that occurs when parsing
     *      the XML, like an invalid XML content or an invalid haptic feedback constant.
     */
    @Nullable
    static SparseArray<VibrationEffect> loadVibrations(Resources res, VibratorInfo vibratorInfo)
            throws CustomizationParserException, IOException {
        try {
            return loadVibrationsInternal(res, vibratorInfo);
        } catch (VibrationXmlParser.VibrationXmlParserException
                | XmlParserException
                | XmlPullParserException e) {
            throw new CustomizationParserException(
                    "Error parsing haptic feedback customization file.", e);
        }
    }

    @Nullable
    private static SparseArray<VibrationEffect> loadVibrationsInternal(
            Resources res, VibratorInfo vibratorInfo) throws
                    CustomizationParserException,
                    IOException,
                    VibrationXmlParser.VibrationXmlParserException,
                    XmlParserException,
                    XmlPullParserException {
        if (!Flags.hapticFeedbackVibrationOemCustomizationEnabled()) {
            Slog.d(TAG, "Haptic feedback customization feature is not enabled.");
            return null;
        }
        String customizationFile =
                res.getString(
                        com.android.internal.R.string.config_hapticFeedbackCustomizationFile);
        if (TextUtils.isEmpty(customizationFile)) {
            Slog.d(TAG, "Customization file not configured.");
            return null;
        }

        FileReader fileReader;
        try {
            fileReader = new FileReader(customizationFile);
        } catch (FileNotFoundException e) {
            Slog.d(TAG, "Specified customization file not found.");
            return  null;
        }

        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        parser.setInput(fileReader);

        XmlReader.readDocumentStartTag(parser, TAG_CONSTANTS);
        XmlValidator.checkTagHasNoUnexpectedAttributes(parser);
        int rootDepth = parser.getDepth();

        SparseArray<VibrationEffect> mapping = new SparseArray<>();
        while (XmlReader.readNextTagWithin(parser, rootDepth)) {
            XmlValidator.checkStartTag(parser, TAG_CONSTANT);
            int customizationDepth = parser.getDepth();

            // Only attribute in tag is the `id` attribute.
            XmlValidator.checkTagHasNoUnexpectedAttributes(parser, ATTRIBUTE_ID);
            int effectId = XmlReader.readAttributeIntNonNegative(parser, ATTRIBUTE_ID);
            if (mapping.contains(effectId)) {
                throw new CustomizationParserException(
                        "Multiple customizations found for effect " + effectId);
            }

            // Move the parser one step into the `<constant>` tag.
            XmlValidator.checkParserCondition(
                    XmlReader.readNextTagWithin(parser, customizationDepth),
                    "Unsupported empty customization tag for effect " + effectId);

            ParsedVibration parsedVibration = VibrationXmlParser.parseElement(
                    parser, VibrationXmlParser.FLAG_ALLOW_HIDDEN_APIS);
            if (parsedVibration == null) {
                throw new CustomizationParserException(
                        "Unable to parse vibration element for effect " + effectId);
            }
            VibrationEffect effect = parsedVibration.resolve(vibratorInfo);
            if (effect != null) {
                if (effect.getDuration() == Long.MAX_VALUE) {
                    throw new CustomizationParserException(String.format(
                            "Vibration for effect ID %d is repeating, which is not allowed as a"
                            + " haptic feedback: %s", effectId, effect));
                }
                mapping.put(effectId, effect);
            }

            XmlReader.readEndTag(parser, TAG_CONSTANT, customizationDepth);
        }

        // Make checks that the XML ends well.
        XmlReader.readEndTag(parser, TAG_CONSTANTS, rootDepth);
        XmlReader.readDocumentEndTag(parser);

        return mapping;
    }

    /**
     * Represents an error while parsing a haptic feedback customization XML.
     */
    static final class CustomizationParserException extends Exception {
        private CustomizationParserException(String message) {
            super(message);
        }

        private CustomizationParserException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
