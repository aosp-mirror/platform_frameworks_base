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

import static android.os.VibrationEffect.Composition.PRIMITIVE_TICK;
import static android.os.VibrationEffect.EFFECT_CLICK;
import static android.os.VibrationEffect.EFFECT_TICK;
import static android.os.vibrator.Flags.FLAG_HAPTIC_FEEDBACK_INPUT_SOURCE_CUSTOMIZATION_ENABLED;
import static android.os.vibrator.Flags.FLAG_HAPTIC_FEEDBACK_VIBRATION_OEM_CUSTOMIZATION_ENABLED;
import static android.os.vibrator.Flags.FLAG_LOAD_HAPTIC_FEEDBACK_VIBRATION_CUSTOMIZATION_FROM_RESOURCES;

import static com.android.internal.R.xml.haptic_feedback_customization;
import static com.android.internal.R.xml.haptic_feedback_customization_source_rotary_encoder;
import static com.android.internal.R.xml.haptic_feedback_customization_source_touchscreen;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.content.res.Resources;
import android.os.VibrationEffect;
import android.os.VibratorInfo;
import android.platform.test.flag.junit.SetFlagsRule;
import android.util.AtomicFile;
import android.view.InputDevice;

import androidx.test.InstrumentationRegistry;

import com.android.internal.R;

import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.io.File;
import java.io.FileOutputStream;

@RunWith(TestParameterInjector.class)
public class HapticFeedbackCustomizationTest {
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Rule public MockitoRule rule = MockitoJUnit.rule();

    // Pairs of valid vibration XML along with their equivalent VibrationEffect.
    private static final String COMPOSITION_VIBRATION_XML = "<vibration-effect>"
            + "<primitive-effect name=\"tick\" scale=\"0.2497\"/>"
            + "</vibration-effect>";
    private static final VibrationEffect COMPOSITION_VIBRATION =
            VibrationEffect.startComposition().addPrimitive(PRIMITIVE_TICK, 0.2497f).compose();

    private static final String PREDEFINED_VIBRATION_CLICK_XML =
            "<vibration-effect><predefined-effect name=\"click\"/></vibration-effect>";
    private static final VibrationEffect PREDEFINED_VIBRATION_CLICK =
            VibrationEffect.createPredefined(EFFECT_CLICK);

    private static final String PREDEFINED_VIBRATION_TICK_XML =
            "<vibration-effect><predefined-effect name=\"tick\"/></vibration-effect>";
    private static final VibrationEffect PREDEFINED_VIBRATION_TICK =
            VibrationEffect.createPredefined(EFFECT_TICK);

    private static final String WAVEFORM_VIBRATION_XML = "<vibration-effect>"
            + "<waveform-effect>"
            + "<waveform-entry durationMs=\"123\" amplitude=\"254\"/>"
            + "</waveform-effect>"
            + "</vibration-effect>";
    private static final VibrationEffect WAVEFORM_VIBRATION =
            VibrationEffect.createWaveform(new long[] {123}, new int[] {254}, -1);

    @Mock private Resources mResourcesMock;
    @Mock private VibratorInfo mVibratorInfoMock;

    private enum CustomizationSource {
        DEVICE_CONFIG_FILE,
        DEVICE_RESOURCE,
        DEVICE_RESOURCE_INPUT_ROTARY,
        DEVICE_RESOURCE_INPUT_TOUCHSCREEN
    }

    @Before
    public void setUp() {
        clearFileAndResourceSetup();
        when(mVibratorInfoMock.areVibrationFeaturesSupported(any())).thenReturn(true);
        mSetFlagsRule.enableFlags(FLAG_HAPTIC_FEEDBACK_VIBRATION_OEM_CUSTOMIZATION_ENABLED);
    }

    @Test
    public void testParseCustomizations_featureFlagDisabled_customizationNotLoaded(
            @TestParameter CustomizationSource customizationSource) throws Exception {
        mSetFlagsRule.disableFlags(FLAG_HAPTIC_FEEDBACK_VIBRATION_OEM_CUSTOMIZATION_ENABLED);
        // Valid customization XML.
        String xml = "<haptic-feedback-constants>"
                + "<constant id=\"10\">"
                + COMPOSITION_VIBRATION_XML
                + "</constant>"
                + "</haptic-feedback-constants>";
        HapticFeedbackCustomization customization = createCustomizationForSource(xml,
                customizationSource);

        assertThat(getEffectForSource(/* effectId= */ 10, customizationSource, customization))
                .isNull();
    }

    @Test
    public void testParseCustomizations_oneVibrationCustomization_success(
            @TestParameter CustomizationSource customizationSource)
            throws Exception {
        String xml = "<haptic-feedback-constants>"
                + "<constant id=\"10\">"
                + COMPOSITION_VIBRATION_XML
                + "</constant>"
                + "</haptic-feedback-constants>";
        HapticFeedbackCustomization customization = createCustomizationForSource(xml,
                customizationSource);

        assertThat(getEffectForSource(/* effectId= */ 10, customizationSource, customization))
                .isEqualTo(COMPOSITION_VIBRATION);
    }

    @Test
    public void testParseCustomizations_oneVibrationSelectCustomization_success(
            @TestParameter CustomizationSource customizationSource)
            throws Exception {
        String xml = "<haptic-feedback-constants>"
                + "<constant id=\"10\">"
                + "<vibration-select>"
                + COMPOSITION_VIBRATION_XML
                + "</vibration-select>"
                + "</constant>"
                + "</haptic-feedback-constants>";
        HapticFeedbackCustomization customization = createCustomizationForSource(xml,
                customizationSource);

        assertThat(getEffectForSource(/* effectId= */ 10, customizationSource, customization))
                .isEqualTo(COMPOSITION_VIBRATION);
    }

    @Test
    public void testParseCustomizations_multipleCustomizations_success(
            @TestParameter CustomizationSource customizationSource) throws Exception {
        String xml = "<haptic-feedback-constants>"
                + "<constant id=\"1\">"
                + COMPOSITION_VIBRATION_XML
                + "</constant>"
                + "<constant id=\"12\">"
                + "<vibration-select>"
                + PREDEFINED_VIBRATION_CLICK_XML
                + WAVEFORM_VIBRATION_XML
                + "</vibration-select>"
                + "</constant>"
                + "<constant id=\"150\">"
                + PREDEFINED_VIBRATION_CLICK_XML
                + "</constant>"
                + "<constant id=\"10\">"
                + "<vibration-select>"
                + WAVEFORM_VIBRATION_XML
                + COMPOSITION_VIBRATION_XML
                + "</vibration-select>"
                + "</constant>"
                + "</haptic-feedback-constants>";
        HapticFeedbackCustomization customization = createCustomizationForSource(xml,
                customizationSource);

        assertThat(getEffectForSource(/* effectId= */ 1, customizationSource,
                customization))
                .isEqualTo(COMPOSITION_VIBRATION);
        assertThat(getEffectForSource(/* effectId= */ 12, customizationSource,
                customization))
                .isEqualTo(PREDEFINED_VIBRATION_CLICK);
        assertThat(getEffectForSource(/* effectId= */ 150, customizationSource,
                customization))
                .isEqualTo(PREDEFINED_VIBRATION_CLICK);
        assertThat(getEffectForSource(/* effectId= */ 10, customizationSource,
                customization))
                .isEqualTo(WAVEFORM_VIBRATION);
    }

    @Test
    public void testParseCustomizations_multipleCustomizations_noSupportedVibration_success(
            @TestParameter CustomizationSource customizationSource) throws Exception {
        makeUnsupported(COMPOSITION_VIBRATION, PREDEFINED_VIBRATION_CLICK, WAVEFORM_VIBRATION);
        String xml = "<haptic-feedback-constants>"
                + "<constant id=\"1\">"
                + COMPOSITION_VIBRATION_XML
                + "</constant>"
                + "<constant id=\"12\">"
                + "<vibration-select>"
                + PREDEFINED_VIBRATION_CLICK_XML
                + WAVEFORM_VIBRATION_XML
                + "</vibration-select>"
                + "</constant>"
                + "<constant id=\"150\">"
                + PREDEFINED_VIBRATION_CLICK_XML
                + "</constant>"
                + "<constant id=\"10\">"
                + "<vibration-select>"
                + WAVEFORM_VIBRATION_XML
                + COMPOSITION_VIBRATION_XML
                + "</vibration-select>"
                + "</constant>"
                + "</haptic-feedback-constants>";
        HapticFeedbackCustomization customization = createCustomizationForSource(xml,
                customizationSource);

        assertThat(getEffectForSource(/* effectId= */ 1, customizationSource,
                customization)).isNull();
        assertThat(getEffectForSource(/* effectId= */ 12, customizationSource,
                customization)).isNull();
        assertThat(getEffectForSource(/* effectId= */ 150, customizationSource,
                customization)).isNull();
        assertThat(getEffectForSource(/* effectId= */ 10, customizationSource,
                customization)).isNull();
    }

    @Test
    public void testParseCustomizations_multipleCustomizations_someUnsupportedVibration_success(
            @TestParameter CustomizationSource customizationSource) throws Exception {
        makeSupported(PREDEFINED_VIBRATION_CLICK, WAVEFORM_VIBRATION);
        makeUnsupported(COMPOSITION_VIBRATION);
        String xml = "<haptic-feedback-constants>"
                + "<constant id=\"1\">" // No supported customization.
                + COMPOSITION_VIBRATION_XML
                + "</constant>"
                + "<constant id=\"12\">" // PREDEFINED_VIBRATION is the first/only supported.
                + "<vibration-select>"
                + PREDEFINED_VIBRATION_CLICK_XML
                + COMPOSITION_VIBRATION_XML
                + "</vibration-select>"
                + "</constant>"
                + "<constant id=\"14\">" // WAVEFORM_VIBRATION is the first/only supported.
                + "<vibration-select>"
                + COMPOSITION_VIBRATION_XML
                + WAVEFORM_VIBRATION_XML
                + "</vibration-select>"
                + "</constant>"
                + "<constant id=\"150\">" // PREDEFINED_VIBRATION is the first/only supported.
                + PREDEFINED_VIBRATION_CLICK_XML
                + "</constant>"
                + "<constant id=\"10\">" // PREDEFINED_VIBRATION is the first supported.
                + "<vibration-select>"
                + PREDEFINED_VIBRATION_CLICK_XML
                + WAVEFORM_VIBRATION_XML
                + "</vibration-select>"
                + "</constant>"
                + "</haptic-feedback-constants>";
        HapticFeedbackCustomization customization = createCustomizationForSource(xml,
                customizationSource);

        assertThat(getEffectForSource(/* effectId= */ 1, customizationSource,
                customization)).isNull();
        assertThat(getEffectForSource(/* effectId= */ 12, customizationSource,
                customization)).isEqualTo(PREDEFINED_VIBRATION_CLICK);
        assertThat(getEffectForSource(/* effectId= */ 14, customizationSource,
                customization)).isEqualTo(WAVEFORM_VIBRATION);
        assertThat(getEffectForSource(/* effectId= */ 150, customizationSource,
                customization)).isEqualTo(PREDEFINED_VIBRATION_CLICK);
        assertThat(getEffectForSource(/* effectId= */ 10, customizationSource,
                customization)).isEqualTo(PREDEFINED_VIBRATION_CLICK);
    }

    @Test
    public void testParseCustomizations_malformedXml_notLoaded(
            @TestParameter CustomizationSource customizationSource) throws Exception {
        // No end "<constant>" tag
        String xmlNoEndConstantTag = "<haptic-feedback-constants>"
                + "<constant id=\"10\">"
                + COMPOSITION_VIBRATION_XML
                + "</haptic-feedback-constants>";
        HapticFeedbackCustomization customizationNoEndConstantTag = createCustomizationForSource(
                xmlNoEndConstantTag, customizationSource);
        // No start "<haptic-feedback-constants>" tag
        String xmlNoStartCustomizationTag = "<constant id=\"10\">"
                + COMPOSITION_VIBRATION_XML
                + "</constant>"
                + "</haptic-feedback-constants>";
        clearFileAndResourceSetup();
        HapticFeedbackCustomization customizationNoStartCustomizationTag =
                createCustomizationForSource(xmlNoStartCustomizationTag, customizationSource);
        // No end "<haptic-feedback-constants>" tag
        String xmlNoEndCustomizationTag = "<haptic-feedback-constants>"
                + "<constant id=\"10\">"
                + COMPOSITION_VIBRATION_XML
                + "</constant>";
        clearFileAndResourceSetup();
        HapticFeedbackCustomization customizationNoEndCustomizationTag =
                createCustomizationForSource(xmlNoEndCustomizationTag, customizationSource);
        // No start "<constant>" tag
        String xmlNoStartConstantTag = "<haptic-feedback-constants>"
                + COMPOSITION_VIBRATION_XML
                + "</constant>"
                + "</haptic-feedback-constants>";
        clearFileAndResourceSetup();
        HapticFeedbackCustomization customizationNoStartConstantTag = createCustomizationForSource(
                xmlNoStartConstantTag, customizationSource);

        assertThat(getEffectForSource(/* effectId= */ 10, customizationSource,
                customizationNoEndConstantTag)).isNull();
        assertThat(getEffectForSource(/* effectId= */ 10, customizationSource,
                customizationNoStartCustomizationTag)).isNull();
        assertThat(getEffectForSource(/* effectId= */ 10, customizationSource,
                customizationNoEndCustomizationTag)).isNull();
        assertThat(getEffectForSource(/* effectId= */ 10, customizationSource,
                customizationNoStartConstantTag)).isNull();
    }

    @Test
    public void testParseCustomizations_disallowedVibrationForHapticFeedback_notLoaded(
                @TestParameter CustomizationSource customizationSource) throws Exception {
        // The XML content is good, but the serialized vibration is not supported for haptic
        // feedback usage (i.e. repeating vibration).
        String xml = "<haptic-feedback-constants>"
                + "<constant id=\"10\">"
                + "<vibration-effect>"
                + "<waveform-effect>"
                + "<repeating>"
                + "<waveform-entry durationMs=\"10\" amplitude=\"100\"/>"
                + "</repeating>"
                + "</waveform-effect>"
                + "</vibration-effect>"
                + "</constant>"
                + "</haptic-feedback-constants>";
        HapticFeedbackCustomization customization = createCustomizationForSource(xml,
                customizationSource);

        assertThat(getEffectForSource(/* effectId= */ 10, customizationSource, customization))
                .isNull();
    }

    @Test
    public void testParseCustomizations_xmlNoVibration_notLoaded(
                @TestParameter CustomizationSource customizationSource) throws Exception {
        String xml = "<haptic-feedback-constants>"
                + "<constant id=\"1\">"
                + "</constant>"
                + "</haptic-feedback-constants>";
        HapticFeedbackCustomization customization = createCustomizationForSource(xml,
                customizationSource);

        assertThat(getEffectForSource(/* effectId= */ 1, customizationSource, customization))
                .isNull();
    }


    @Test
    public void testParseCustomizations_badEffectId_notLoaded(
            @TestParameter CustomizationSource customizationSource) throws Exception {
        String xmlNegativeId = "<haptic-feedback-constants>"
                + "<constant id=\"-10\">"
                + COMPOSITION_VIBRATION_XML
                + "</constant>"
                + "</haptic-feedback-constants>";
        HapticFeedbackCustomization customization = createCustomizationForSource(
                xmlNegativeId, customizationSource);

        assertThat(getEffectForSource(/* effectId= */ -10, customizationSource, customization))
                .isNull();
    }

    @Test
    public void testParseCustomizations_badVibrationXml_notLoaded(
            @TestParameter CustomizationSource customizationSource) throws Exception {
        // Case#1 - bad opening tag <bad-vibration-effect>
        String xmlBadTag = "<haptic-feedback-constants>"
                + "<constant id=\"10\">"
                + "<bad-vibration-effect></bad-vibration-effect>"
                + "</constant>"
                + "</haptic-feedback-constants>";
        HapticFeedbackCustomization customizationBadTag = createCustomizationForSource(
                xmlBadTag, customizationSource);
        // Case#2 - bad attribute "name" for tag <predefined-effect>
        String xmlBadEffectName = "<haptic-feedback-constants>"
                + "<constant id=\"10\">"
                + "<vibration-effect><predefined-effect name=\"bad-effect\"/></vibration-effect>"
                + "</constant>"
                + "</haptic-feedback-constants>";
        clearFileAndResourceSetup();
        HapticFeedbackCustomization customizationBadEffectName = createCustomizationForSource(
                xmlBadEffectName, customizationSource);
        // Case#3 - miss "</vibration-select>"
        String xmlBadEffectNameAndMissingCloseTag = "<haptic-feedback-constants>"
                + "<constant id=\"10\">"
                + "<vibration-select>"
                + "<vibration-effect><predefined-effect name=\"bad-effect\"/></vibration-effect>"
                + "</constant>"
                + "</haptic-feedback-constants>";
        clearFileAndResourceSetup();
        HapticFeedbackCustomization customizationBadEffectNameAndMissingCloseTag =
                createCustomizationForSource(xmlBadEffectNameAndMissingCloseTag,
                        customizationSource);
        // Case#4 - miss "<vibration-select>"
        String xmlBadEffectNameAndMissingOpenTag = "<haptic-feedback-constants>"
                + "<constant id=\"10\">"
                + "<vibration-effect><predefined-effect name=\"bad-effect\"/></vibration-effect>"
                + "</vibration-select>"
                + "</constant>"
                + "</haptic-feedback-constants>";
        clearFileAndResourceSetup();
        HapticFeedbackCustomization customizationBadEffectNameAndMissingOpenTag =
                createCustomizationForSource(xmlBadEffectNameAndMissingOpenTag,
                        customizationSource);

        assertThat(getEffectForSource(/* effectId= */ 10, customizationSource,
                customizationBadTag)).isNull();
        assertThat(getEffectForSource(/* effectId= */ 10, customizationSource,
                customizationBadEffectName)).isNull();
        assertThat(getEffectForSource(/* effectId= */ 10, customizationSource,
                customizationBadEffectNameAndMissingCloseTag)).isNull();
        assertThat(getEffectForSource(/* effectId= */ 10, customizationSource,
                customizationBadEffectNameAndMissingOpenTag)).isNull();
    }

    @Test
    public void testParseCustomizations_badConstantAttribute_notLoaded(
            @TestParameter CustomizationSource customizationSource) throws Exception {
        // Case#1 - bad attribute id for tag <constant>
        String xmlBadConstantIdAttribute = "<haptic-feedback-constants>"
                + "<constant iddddd=\"10\">"
                + COMPOSITION_VIBRATION_XML
                + "</constant>"
                + "</haptic-feedback-constants>";
        HapticFeedbackCustomization customizationBadConstantIdAttribute =
                createCustomizationForSource(xmlBadConstantIdAttribute, customizationSource);
        // Case#2 - unexpected attribute "unwanted" for tag <constant>
        String xmlUnwantedConstantAttribute = "<haptic-feedback-constants>"
                + "<constant id=\"10\" unwanted-attr=\"1\">"
                + COMPOSITION_VIBRATION_XML
                + "</constant>"
                + "</haptic-feedback-constants>";
        clearFileAndResourceSetup();
        HapticFeedbackCustomization customizationUnwantedConstantAttribute =
                createCustomizationForSource(xmlUnwantedConstantAttribute, customizationSource);

        assertThat(getEffectForSource(/* effectId= */ 10, customizationSource,
                customizationBadConstantIdAttribute)).isNull();
        assertThat(getEffectForSource(/* effectId= */ 10, customizationSource,
                customizationUnwantedConstantAttribute)).isNull();
    }

    @Test
    public void testParseCustomizations_duplicateEffects_notLoaded(
            @TestParameter CustomizationSource customizationSource) throws Exception {
        String xmlDuplicateEffect = "<haptic-feedback-constants>"
                + "<constant id=\"10\">"
                + COMPOSITION_VIBRATION_XML
                + "</constant>"
                + "<constant id=\"10\">"
                + PREDEFINED_VIBRATION_CLICK_XML
                + "</constant>"
                + "<constant id=\"11\">"
                + PREDEFINED_VIBRATION_CLICK_XML
                + "</constant>"
                + "</haptic-feedback-constants>";
        HapticFeedbackCustomization customization = createCustomizationForSource(xmlDuplicateEffect,
                customizationSource);

        assertThat(getEffectForSource(/* effectId= */ 10, customizationSource, customization))
                .isNull();
        assertThat(getEffectForSource(/* effectId= */ 11, customizationSource, customization))
                .isNull();
    }

    @Test
    public void testParseCustomizations_withDifferentCustomizations_loadsCorrectOne()
            throws Exception {
        String xmlBaseCustomization = "<haptic-feedback-constants>"
                + "<constant id=\"10\">"
                + COMPOSITION_VIBRATION_XML
                + "</constant>"
                + "<constant id=\"14\">"
                + "<vibration-select>"
                + WAVEFORM_VIBRATION_XML
                + "</vibration-select>"
                + "</constant>"
                + "</haptic-feedback-constants>";
        String xmlRotaryInputCustomization = "<haptic-feedback-constants>"
                + "<constant id=\"10\">"
                + "<vibration-select>"
                + PREDEFINED_VIBRATION_CLICK_XML
                + COMPOSITION_VIBRATION_XML
                + "</vibration-select>"
                + "</constant>"
                + "</haptic-feedback-constants>";
        String xmlTouchScreenInputCustomization = "<haptic-feedback-constants>"
                + "<constant id=\"10\">"
                + PREDEFINED_VIBRATION_TICK_XML
                + "</constant>"
                + "</haptic-feedback-constants>";
        setupCustomizations(xmlBaseCustomization, CustomizationSource.DEVICE_RESOURCE);
        setupCustomizations(xmlRotaryInputCustomization,
                CustomizationSource.DEVICE_RESOURCE_INPUT_ROTARY);
        HapticFeedbackCustomization customization = createCustomizationForSource(
                xmlTouchScreenInputCustomization,
                CustomizationSource.DEVICE_RESOURCE_INPUT_TOUCHSCREEN);

        // Matching customizations.
        assertThat(customization.getEffect(/* effectId= */ 10)).isEqualTo(COMPOSITION_VIBRATION);
        assertThat(customization.getEffect(/* effectId= */ 14)).isEqualTo(WAVEFORM_VIBRATION);
        assertThat(customization.getEffect(/* effectId= */ 10,
                InputDevice.SOURCE_ROTARY_ENCODER)).isEqualTo(PREDEFINED_VIBRATION_CLICK);
        assertThat(customization.getEffect(/* effectId= */ 10,
                InputDevice.SOURCE_TOUCHSCREEN)).isEqualTo(PREDEFINED_VIBRATION_TICK);
        // Missing from input source customization xml. Fallback to base.
        assertThat(customization.getEffect(/* effectId= */ 14,
                InputDevice.SOURCE_ROTARY_ENCODER)).isEqualTo(WAVEFORM_VIBRATION);
        assertThat(customization.getEffect(/* effectId= */ 14,
                InputDevice.SOURCE_TOUCHSCREEN)).isEqualTo(WAVEFORM_VIBRATION);
    }

    @Test
    public void testParseCustomizations_customizationsFromConfigFileAndRes_preferConfigFile()
            throws Exception {
        String xmlConfigFileCustomization = "<haptic-feedback-constants>"
                + "<constant id=\"10\">"
                + COMPOSITION_VIBRATION_XML
                + "</constant>"
                + "</haptic-feedback-constants>";
        String xmlResourceCustomization = "<haptic-feedback-constants>"
                + "<constant id=\"10\">"
                + "<vibration-select>"
                + PREDEFINED_VIBRATION_CLICK_XML
                + "</vibration-select>"
                + "</constant>"
                + "<constant id=\"14\">"
                + "<vibration-select>"
                + WAVEFORM_VIBRATION_XML
                + "</vibration-select>"
                + "</constant>"
                + "</haptic-feedback-constants>";
        setupCustomizations(xmlConfigFileCustomization, CustomizationSource.DEVICE_CONFIG_FILE);
        HapticFeedbackCustomization customization = createCustomizationForSource(
                xmlResourceCustomization, CustomizationSource.DEVICE_RESOURCE);

        // When config file and resource customizations are both available. Load the config file
        // Customization.
        assertThat(customization.getEffect(/* effectId= */ 10)).isEqualTo(COMPOSITION_VIBRATION);
        assertThat(customization.getEffect(/* effectId= */ 14)).isNull();
    }

    private HapticFeedbackCustomization createCustomizationForSource(String xml,
            CustomizationSource customizationSource) throws Exception {
        setupCustomizations(xml, customizationSource);
        return new HapticFeedbackCustomization(mResourcesMock, mVibratorInfoMock);
    }

    private void setupCustomizations(String xml, CustomizationSource customizationSource)
            throws Exception {
        switch (customizationSource) {
            case DEVICE_CONFIG_FILE -> setupCustomizationFile(xml);
            case DEVICE_RESOURCE -> setupCustomizationResource(xml, haptic_feedback_customization);
            case DEVICE_RESOURCE_INPUT_ROTARY -> setupCustomizationResource(xml,
                    haptic_feedback_customization_source_rotary_encoder);
            case DEVICE_RESOURCE_INPUT_TOUCHSCREEN -> setupCustomizationResource(xml,
                    haptic_feedback_customization_source_touchscreen);
        }
    }

    private void setupCustomizationResource(String xml, int xmlResId) throws Exception {
        mSetFlagsRule.enableFlags(FLAG_HAPTIC_FEEDBACK_INPUT_SOURCE_CUSTOMIZATION_ENABLED);
        mSetFlagsRule.enableFlags(FLAG_LOAD_HAPTIC_FEEDBACK_VIBRATION_CUSTOMIZATION_FROM_RESOURCES);
        doReturn(FakeXmlResourceParser.fromXml(xml)).when(mResourcesMock).getXml(xmlResId);
    }

    private void setupCustomizationFile(String xml) throws Exception {
        File file = createFile(xml);
        setCustomizationFilePath(file.getAbsolutePath());
    }

    private void setCustomizationFilePath(String path) {
        doReturn(path).when(mResourcesMock)
                .getString(R.string.config_hapticFeedbackCustomizationFile);
    }

    private void clearFileAndResourceSetup() {
        doThrow(new Resources.NotFoundException()).when(mResourcesMock)
                .getString(R.string.config_hapticFeedbackCustomizationFile);
        doThrow(new Resources.NotFoundException()).when(mResourcesMock)
                .getXml(haptic_feedback_customization);
        doThrow(new Resources.NotFoundException()).when(mResourcesMock)
                .getXml(haptic_feedback_customization_source_rotary_encoder);
        doThrow(new Resources.NotFoundException()).when(mResourcesMock)
                .getXml(haptic_feedback_customization_source_touchscreen);
    }

    @Nullable
    private VibrationEffect getEffectForSource(int effectId,
            CustomizationSource customizationSource,
            HapticFeedbackCustomization hapticFeedbackCustomization) {
        return switch (customizationSource) {
            case DEVICE_CONFIG_FILE, DEVICE_RESOURCE -> hapticFeedbackCustomization.getEffect(
                    effectId);
            case DEVICE_RESOURCE_INPUT_ROTARY -> hapticFeedbackCustomization.getEffect(effectId,
                    InputDevice.SOURCE_ROTARY_ENCODER);
            case DEVICE_RESOURCE_INPUT_TOUCHSCREEN -> hapticFeedbackCustomization.getEffect(
                    effectId,
                    InputDevice.SOURCE_TOUCHSCREEN);
        };
    }

    private void makeSupported(VibrationEffect... effects) {
        for (VibrationEffect effect : effects) {
            when(mVibratorInfoMock.areVibrationFeaturesSupported(effect)).thenReturn(true);
        }
    }

    private void makeUnsupported(VibrationEffect... effects) {
        for (VibrationEffect effect : effects) {
            when(mVibratorInfoMock.areVibrationFeaturesSupported(effect)).thenReturn(false);
        }
    }

    private static File createFile(String contents) throws Exception {
        File file = new File(InstrumentationRegistry.getContext().getCacheDir(), "test.xml");
        file.createNewFile();

        AtomicFile testAtomicXmlFile = new AtomicFile(file);
        FileOutputStream fos = testAtomicXmlFile.startWrite();
        fos.write(contents.getBytes());
        testAtomicXmlFile.finishWrite(fos);

        return file;
    }
}
