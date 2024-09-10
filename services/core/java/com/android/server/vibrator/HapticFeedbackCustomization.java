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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.VibrationEffect;
import android.os.VibratorInfo;
import android.os.vibrator.Flags;
import android.os.vibrator.persistence.ParsedVibration;
import android.os.vibrator.persistence.VibrationXmlParser;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;
import android.view.InputDevice;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.XmlUtils;
import com.android.internal.vibrator.persistence.XmlParserException;
import com.android.internal.vibrator.persistence.XmlReader;
import com.android.internal.vibrator.persistence.XmlValidator;
import com.android.modules.utils.TypedXmlPullParser;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Locale;

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
     * A {@link SparseArray} that maps each customized haptic feedback effect ID to its
     * respective {@link VibrationEffect}. If this is empty, system's default vibration will be
     * used.
     */
    @NonNull
    private final SparseArray<VibrationEffect> mHapticCustomizations;

    /**
     * A {@link SparseArray} similar to {@link mHapticCustomizations} but for rotary input source
     * specific customization.
     */
    @NonNull
    private final SparseArray<VibrationEffect> mHapticCustomizationsForSourceRotary;

    /**
     * A {@link SparseArray} similar to {@link mHapticCustomizations} but for touch screen input
     * source specific customization.
     */
    @NonNull
    private final SparseArray<VibrationEffect> mHapticCustomizationsForSourceTouchScreen;

    HapticFeedbackCustomization(Resources res, VibratorInfo vibratorInfo) {
        if (!Flags.hapticFeedbackVibrationOemCustomizationEnabled()) {
            Slog.d(TAG, "Haptic feedback customization feature is not enabled.");
            mHapticCustomizations = new SparseArray<>();
            mHapticCustomizationsForSourceRotary = new SparseArray<>();
            mHapticCustomizationsForSourceTouchScreen = new SparseArray<>();
            return;
        }

        // Load base customizations.
        SparseArray<VibrationEffect> hapticCustomizations;
        hapticCustomizations = loadCustomizedFeedbackVibrationFromFile(res, vibratorInfo);
        if (hapticCustomizations.size() == 0) {
            // Input source customized haptic feedback was directly added in res. So, no need to old
            // loading path.
            hapticCustomizations = loadCustomizedFeedbackVibrationFromRes(res, vibratorInfo,
                    R.xml.haptic_feedback_customization);
        }
        mHapticCustomizations = hapticCustomizations;

        // Load customizations specified by input sources.
        if (android.os.vibrator.Flags.hapticFeedbackInputSourceCustomizationEnabled()) {
            mHapticCustomizationsForSourceRotary =
                    loadCustomizedFeedbackVibrationFromRes(res, vibratorInfo,
                            R.xml.haptic_feedback_customization_source_rotary_encoder);
            mHapticCustomizationsForSourceTouchScreen =
                    loadCustomizedFeedbackVibrationFromRes(res, vibratorInfo,
                            R.xml.haptic_feedback_customization_source_touchscreen);
        } else {
            mHapticCustomizationsForSourceRotary = new SparseArray<>();
            mHapticCustomizationsForSourceTouchScreen = new SparseArray<>();
        }
    }

    @VisibleForTesting
    HapticFeedbackCustomization(@NonNull SparseArray<VibrationEffect> hapticCustomizations,
            @NonNull SparseArray<VibrationEffect> hapticCustomizationsForSourceRotary,
            @NonNull SparseArray<VibrationEffect> hapticCustomizationsForSourceTouchScreen) {
        mHapticCustomizations = hapticCustomizations;
        mHapticCustomizationsForSourceRotary = hapticCustomizationsForSourceRotary;
        mHapticCustomizationsForSourceTouchScreen = hapticCustomizationsForSourceTouchScreen;
    }

    @Nullable
    VibrationEffect getEffect(int effectId) {
        return mHapticCustomizations.get(effectId);
    }

    @Nullable
    VibrationEffect getEffect(int effectId, int inputSource) {
        VibrationEffect resultVibration = null;
        if ((InputDevice.SOURCE_ROTARY_ENCODER & inputSource) != 0) {
            resultVibration = mHapticCustomizationsForSourceRotary.get(effectId);
        } else if ((InputDevice.SOURCE_TOUCHSCREEN & inputSource) != 0) {
            resultVibration = mHapticCustomizationsForSourceTouchScreen.get(effectId);
        }
        if (resultVibration == null) {
            resultVibration = mHapticCustomizations.get(effectId);
        }
        return resultVibration;
    }

    /**
     * Parses the haptic feedback vibration customization XML file for the device whose directory is
     * specified by config. See {@link R.string.config_hapticFeedbackCustomizationFile}.
     *
     * @return Return a mapping of the customized effect IDs to their respective
     * {@link VibrationEffect}s.
     */
    @NonNull
    private static SparseArray<VibrationEffect> loadCustomizedFeedbackVibrationFromFile(
            Resources res, VibratorInfo vibratorInfo) {
        try {
            TypedXmlPullParser parser = readCustomizationFile(res);
            if (parser == null) {
                Slog.d(TAG, "No loadable haptic feedback customization from file.");
                return new SparseArray<>();
            }
            return parseVibrations(parser, vibratorInfo);
        } catch (XmlPullParserException | XmlParserException | IOException e) {
            Slog.e(TAG, "Error parsing haptic feedback customizations from file", e);
            return new SparseArray<>();
        }
    }

    /**
     * Parses the haptic feedback vibration customization XML resource for the device.
     *
     * @return Return a mapping of the customized effect IDs to their respective
     * {@link VibrationEffect}s.
     */
    @NonNull
    private static SparseArray<VibrationEffect> loadCustomizedFeedbackVibrationFromRes(
            Resources res, VibratorInfo vibratorInfo, int xmlResId) {
        try {
            TypedXmlPullParser parser = readCustomizationResources(res, xmlResId);
            if (parser == null) {
                Slog.d(TAG, "No loadable haptic feedback customization from res.");
                return new SparseArray<>();
            }
            return parseVibrations(parser, vibratorInfo);
        } catch (XmlPullParserException | XmlParserException | IOException e) {
            Slog.e(TAG, "Error parsing haptic feedback customizations from res", e);
            return new SparseArray<>();
        }
    }

    // TODO(b/356412421): deprecate old path related files.
    private static TypedXmlPullParser readCustomizationFile(Resources res)
            throws XmlPullParserException {
        String customizationFile;
        try {
            customizationFile = res.getString(
                    R.string.config_hapticFeedbackCustomizationFile);
        } catch (Resources.NotFoundException e) {
            Slog.e(TAG, "Customization file directory config not found.", e);
            return null;
        }

        if (TextUtils.isEmpty(customizationFile)) {
            return null;
        }

        final Reader customizationReader;
        try {
            customizationReader = new FileReader(customizationFile);
        } catch (FileNotFoundException e) {
            Slog.e(TAG, "Specified customization file not found.", e);
            return null;
        }

        final TypedXmlPullParser parser;
        parser = Xml.newFastPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        parser.setInput(customizationReader);
        Slog.d(TAG, "Successfully opened customization file.");
        return parser;
    }

    @Nullable
    private static TypedXmlPullParser readCustomizationResources(Resources res, int xmlResId) {
        if (!Flags.loadHapticFeedbackVibrationCustomizationFromResources()) {
            return null;
        }
        final XmlResourceParser resParser;
        try {
            resParser = res.getXml(xmlResId);
        } catch (Resources.NotFoundException e) {
            Slog.e(TAG, "Haptic customization resource not found.", e);
            return null;
        }
        Slog.d(TAG, "Successfully opened customization resource.");
        return XmlUtils.makeTyped(resParser);
    }

    @NonNull
    private static SparseArray<VibrationEffect> parseVibrations(TypedXmlPullParser parser,
            VibratorInfo vibratorInfo)
            throws XmlPullParserException, IOException, XmlParserException {
        XmlUtils.beginDocument(parser, TAG_CONSTANTS);
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
                Slog.e(TAG, "Multiple customizations found for effect " + effectId);
                return new SparseArray<>();
            }

            // Move the parser one step into the `<constant>` tag.
            XmlValidator.checkParserCondition(
                    XmlReader.readNextTagWithin(parser, customizationDepth),
                    "Unsupported empty customization tag for effect " + effectId);

            ParsedVibration parsedVibration = VibrationXmlParser.parseElement(
                    parser, VibrationXmlParser.FLAG_ALLOW_HIDDEN_APIS);
            VibrationEffect effect = parsedVibration.resolve(vibratorInfo);
            if (effect != null) {
                if (effect.getDuration() == Long.MAX_VALUE) {
                    Slog.e(TAG, String.format(Locale.getDefault(),
                            "Vibration for effect ID %d is repeating, which is not allowed as a"
                                    + " haptic feedback: %s", effectId, effect));
                    return new SparseArray<>();
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
}
