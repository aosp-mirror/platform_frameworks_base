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

import static com.android.server.vibrator.HapticFeedbackCustomization.CustomizationParserException;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import android.os.VibrationEffect;
import android.os.VibratorInfo;
import android.os.vibrator.Flags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.util.AtomicFile;
import android.util.SparseArray;

import androidx.test.InstrumentationRegistry;

import com.android.internal.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.io.File;
import java.io.FileOutputStream;

public class HapticFeedbackCustomizationTest {
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Rule public MockitoRule rule = MockitoJUnit.rule();

    // Pairs of valid vibration XML along with their equivalent VibrationEffect.
    private static final String COMPOSITION_VIBRATION_XML = "<vibration-effect>"
            + "<primitive-effect name=\"tick\" scale=\"0.2497\"/>"
            + "</vibration-effect>";
    private static final VibrationEffect COMPOSITION_VIBRATION =
            VibrationEffect.startComposition().addPrimitive(PRIMITIVE_TICK, 0.2497f).compose();

    private static final String PREDEFINED_VIBRATION_XML =
            "<vibration-effect><predefined-effect name=\"click\"/></vibration-effect>";
    private static final VibrationEffect PREDEFINED_VIBRATION =
            VibrationEffect.createPredefined(EFFECT_CLICK);

    private static final String WAVEFORM_VIBRATION_XML = "<vibration-effect>"
            + "<waveform-effect>"
            + "<waveform-entry durationMs=\"123\" amplitude=\"254\"/>"
            + "</waveform-effect>"
            + "</vibration-effect>";
    private static final VibrationEffect WAVEFORM_VIBARTION =
            VibrationEffect.createWaveform(new long[] {123}, new int[] {254}, -1);

    @Mock private Resources mResourcesMock;
    @Mock private VibratorInfo mVibratorInfoMock;

    @Before
    public void setUp() {
        when(mVibratorInfoMock.areVibrationFeaturesSupported(any())).thenReturn(true);
        mSetFlagsRule.enableFlags(Flags.FLAG_HAPTIC_FEEDBACK_VIBRATION_OEM_CUSTOMIZATION_ENABLED);
    }

    @Test
    public void testParseCustomizations_noCustomization_success() throws Exception {
        assertParseCustomizationsSucceeds(
                /* xml= */ "<haptic-feedback-constants></haptic-feedback-constants>",
                /* expectedCustomizations= */ new SparseArray<>());
    }

    @Test
    public void testParseCustomizations_featureFlagDisabled_returnsNull() throws Exception {
        mSetFlagsRule.disableFlags(Flags.FLAG_HAPTIC_FEEDBACK_VIBRATION_OEM_CUSTOMIZATION_ENABLED);
        // Valid customization XML.
        String xml = "<haptic-feedback-constants>"
                + "<constant id=\"10\">"
                + COMPOSITION_VIBRATION_XML
                + "</constant>"
                + "</haptic-feedback-constants>";
        setupCustomizationFile(xml);

        assertThat(HapticFeedbackCustomization.loadVibrations(mResourcesMock, mVibratorInfoMock))
                .isNull();
    }

    @Test
    public void testParseCustomizations_oneVibrationCustomization_success() throws Exception {
        String xml = "<haptic-feedback-constants>"
                + "<constant id=\"10\">"
                + COMPOSITION_VIBRATION_XML
                + "</constant>"
                + "</haptic-feedback-constants>";
        SparseArray<VibrationEffect> expectedMapping = new SparseArray<>();
        expectedMapping.put(10, COMPOSITION_VIBRATION);

        assertParseCustomizationsSucceeds(xml, expectedMapping);
    }

    @Test
    public void testParseCustomizations_oneVibrationSelectCustomization_success() throws Exception {
        String xml = "<haptic-feedback-constants>"
                + "<constant id=\"10\">"
                + "<vibration-select>"
                + COMPOSITION_VIBRATION_XML
                + "</vibration-select>"
                + "</constant>"
                + "</haptic-feedback-constants>";
        SparseArray<VibrationEffect> expectedMapping = new SparseArray<>();
        expectedMapping.put(10, COMPOSITION_VIBRATION);

        assertParseCustomizationsSucceeds(xml, expectedMapping);
    }

    @Test
    public void testParseCustomizations_multipleCustomizations_success() throws Exception {
        String xml = "<haptic-feedback-constants>"
                + "<constant id=\"1\">"
                + COMPOSITION_VIBRATION_XML
                + "</constant>"
                + "<constant id=\"12\">"
                + "<vibration-select>"
                + PREDEFINED_VIBRATION_XML
                + WAVEFORM_VIBRATION_XML
                + "</vibration-select>"
                + "</constant>"
                + "<constant id=\"150\">"
                + PREDEFINED_VIBRATION_XML
                + "</constant>"
                + "<constant id=\"10\">"
                + "<vibration-select>"
                + WAVEFORM_VIBRATION_XML
                + COMPOSITION_VIBRATION_XML
                + "</vibration-select>"
                + "</constant>"
                + "</haptic-feedback-constants>";
        SparseArray<VibrationEffect> expectedMapping = new SparseArray<>();
        expectedMapping.put(1, COMPOSITION_VIBRATION);
        expectedMapping.put(12, PREDEFINED_VIBRATION);
        expectedMapping.put(150, PREDEFINED_VIBRATION);
        expectedMapping.put(10, WAVEFORM_VIBARTION);

        assertParseCustomizationsSucceeds(xml, expectedMapping);
    }

    @Test
    public void testParseCustomizations_multipleCustomizations_noSupportedVibration_success()
                throws Exception {
        makeUnsupported(COMPOSITION_VIBRATION, PREDEFINED_VIBRATION, WAVEFORM_VIBARTION);
        String xml = "<haptic-feedback-constants>"
                + "<constant id=\"1\">"
                + COMPOSITION_VIBRATION_XML
                + "</constant>"
                + "<constant id=\"12\">"
                + "<vibration-select>"
                + PREDEFINED_VIBRATION_XML
                + WAVEFORM_VIBRATION_XML
                + "</vibration-select>"
                + "</constant>"
                + "<constant id=\"150\">"
                + PREDEFINED_VIBRATION_XML
                + "</constant>"
                + "<constant id=\"10\">"
                + "<vibration-select>"
                + WAVEFORM_VIBRATION_XML
                + COMPOSITION_VIBRATION_XML
                + "</vibration-select>"
                + "</constant>"
                + "</haptic-feedback-constants>";

        assertParseCustomizationsSucceeds(xml, new SparseArray<>());
    }

    @Test
    public void testParseCustomizations_multipleCustomizations_someUnsupportedVibration_success()
                throws Exception {
        makeSupported(PREDEFINED_VIBRATION, WAVEFORM_VIBARTION);
        makeUnsupported(COMPOSITION_VIBRATION);
        String xml = "<haptic-feedback-constants>"
                + "<constant id=\"1\">" // No supported customization.
                + COMPOSITION_VIBRATION_XML
                + "</constant>"
                + "<constant id=\"12\">" // PREDEFINED_VIBRATION is the first/only supported.
                + "<vibration-select>"
                + PREDEFINED_VIBRATION_XML
                + COMPOSITION_VIBRATION_XML
                + "</vibration-select>"
                + "</constant>"
                + "<constant id=\"14\">" // WAVEFORM_VIBARTION is the first/only supported.
                + "<vibration-select>"
                + COMPOSITION_VIBRATION_XML
                + WAVEFORM_VIBRATION_XML
                + "</vibration-select>"
                + "</constant>"
                + "<constant id=\"150\">" // PREDEFINED_VIBRATION is the first/only supported.
                + PREDEFINED_VIBRATION_XML
                + "</constant>"
                + "<constant id=\"10\">" // PREDEFINED_VIBRATION is the first supported.
                + "<vibration-select>"
                + PREDEFINED_VIBRATION_XML
                + WAVEFORM_VIBRATION_XML
                + "</vibration-select>"
                + "</constant>"
                + "</haptic-feedback-constants>";
        SparseArray<VibrationEffect> expectedMapping = new SparseArray<>();
        expectedMapping.put(12, PREDEFINED_VIBRATION);
        expectedMapping.put(14, WAVEFORM_VIBARTION);
        expectedMapping.put(150, PREDEFINED_VIBRATION);
        expectedMapping.put(10, PREDEFINED_VIBRATION);

        assertParseCustomizationsSucceeds(xml, expectedMapping);
    }

    @Test
    public void testParseCustomizations_noCustomizationFile_returnsNull() throws Exception {
        setCustomizationFilePath("");

        assertThat(HapticFeedbackCustomization.loadVibrations(mResourcesMock, mVibratorInfoMock))
                .isNull();

        setCustomizationFilePath(null);

        assertThat(HapticFeedbackCustomization.loadVibrations(mResourcesMock, mVibratorInfoMock))
                .isNull();

        setCustomizationFilePath("non_existent_file.xml");

        assertThat(HapticFeedbackCustomization.loadVibrations(mResourcesMock, mVibratorInfoMock))
                .isNull();
    }

    @Test
    public void testParseCustomizations_disallowedVibrationForHapticFeedback_throwsException()
            throws Exception {
        // The XML content is good, but the serialized vibration is not supported for haptic
        // feedback usage (i.e. repeating vibration).
        assertParseCustomizationsFails(
                "<haptic-feedback-constants>"
                + "<constant id=\"10\">"
                + "<vibration-effect>"
                + "<waveform-effect>"
                + "<repeating>"
                + "<waveform-entry durationMs=\"10\" amplitude=\"100\"/>"
                + "</repeating>"
                + "</waveform-effect>"
                + "</vibration-effect>"
                + "</constant>"
                + "</haptic-feedback-constants>");
    }

    @Test
    public void testParseCustomizations_emptyXml_throwsException() throws Exception {
        assertParseCustomizationsFails("");
    }

    @Test
    public void testParseCustomizations_noVibrationXml_throwsException() throws Exception {
        assertParseCustomizationsFails(
                "<haptic-feedback-constants>"
                + "<constant id=\"1\">"
                + "</constant>"
                + "</haptic-feedback-constants>");
    }

    @Test
    public void testParseCustomizations_badEffectId_throwsException() throws Exception {
        // Negative id
        assertParseCustomizationsFails(
                "<haptic-feedback-constants>"
                + "<constant id=\"-10\">"
                + COMPOSITION_VIBRATION_XML
                + "</constant>"
                + "</haptic-feedback-constants>");

        // Non-numeral id
        assertParseCustomizationsFails(
                "<haptic-feedback-constants>"
                + "<constant id=\"xyz\">"
                + COMPOSITION_VIBRATION_XML
                + "</constant>"
                + "</haptic-feedback-constants>");
    }

    @Test
    public void testParseCustomizations_malformedXml_throwsException() throws Exception {
        // No start "<constant>" tag
        assertParseCustomizationsFails(
                "<haptic-feedback-constants>"
                + COMPOSITION_VIBRATION_XML
                + "</constant>"
                + "</haptic-feedback-constants>");

        // No end "<constant>" tag
        assertParseCustomizationsFails(
                "<haptic-feedback-constants>"
                + "<constant id=\"10\">"
                + COMPOSITION_VIBRATION_XML
                + "</haptic-feedback-constants>");

        // No start "<haptic-feedback-constants>" tag
        assertParseCustomizationsFails(
                "<constant id=\"10\">"
                + COMPOSITION_VIBRATION_XML
                + "</constant>"
                + "</haptic-feedback-constants>");

        // No end "<haptic-feedback-constants>" tag
        assertParseCustomizationsFails(
                "<haptic-feedback-constants>"
                + "<constant id=\"10\">"
                + COMPOSITION_VIBRATION_XML
                + "</constant>");
    }

    @Test
    public void testParseCustomizations_badVibrationXml_throwsException() throws Exception {
        assertParseCustomizationsFails(
                "<haptic-feedback-constants>"
                + "<constant id=\"10\">"
                + "<bad-vibration-effect></bad-vibration-effect>"
                + "</constant>"
                + "</haptic-feedback-constants>");

        assertParseCustomizationsFails(
                "<haptic-feedback-constants>"
                + "<constant id=\"10\">"
                + "<vibration-effect><predefined-effect name=\"bad-effect\"/></vibration-effect>"
                + "</constant>"
                + "</haptic-feedback-constants>");

        assertParseCustomizationsFails(
                "<haptic-feedback-constants>"
                + "<constant id=\"10\">"
                + "<vibration-select>"
                + "<vibration-effect><predefined-effect name=\"bad-effect\"/></vibration-effect>"
                + "</constant>"
                + "</haptic-feedback-constants>");

        assertParseCustomizationsFails(
                "<haptic-feedback-constants>"
                + "<constant id=\"10\">"
                + "<vibration-effect><predefined-effect name=\"bad-effect\"/></vibration-effect>"
                + "</vibration-select>"
                + "</constant>"
                + "</haptic-feedback-constants>");
    }

    @Test
    public void testParseCustomizations_badConstantAttribute_throwsException() throws Exception {
        assertParseCustomizationsFails(
                "<haptic-feedback-constants>"
                + "<constant iddddd=\"10\">"
                + COMPOSITION_VIBRATION_XML
                + "</constant>"
                + "</haptic-feedback-constants>");

        assertParseCustomizationsFails(
                "<haptic-feedback-constants>"
                + "<constant id=\"10\" unwanted-attr=\"1\">"
                + COMPOSITION_VIBRATION_XML
                + "</constant>"
                + "</haptic-feedback-constants>");
    }

    @Test
    public void testParseCustomizations_duplicateEffects_throwsException() throws Exception {
        assertParseCustomizationsFails(
                "<haptic-feedback-constants>"
                + "<constant id=\"10\">"
                + COMPOSITION_VIBRATION_XML
                + "</constant>"
                + "<constant id=\"10\">"
                + PREDEFINED_VIBRATION_XML
                + "</constant>"
                + "<constant id=\"11\">"
                + PREDEFINED_VIBRATION_XML
                + "</constant>"
                + "</haptic-feedback-constants>");
    }

    private void assertParseCustomizationsSucceeds(
            String xml, SparseArray<VibrationEffect> expectedCustomizations) throws Exception {
        setupCustomizationFile(xml);
        assertThat(expectedCustomizations.contentEquals(
                HapticFeedbackCustomization.loadVibrations(mResourcesMock, mVibratorInfoMock)))
                        .isTrue();
    }

    private void assertParseCustomizationsFails(String xml) throws Exception {
        setupCustomizationFile(xml);
        assertThrows("Expected haptic feedback customization to fail for " + xml,
                CustomizationParserException.class,
                () ->  HapticFeedbackCustomization.loadVibrations(
                        mResourcesMock, mVibratorInfoMock));
    }

    private void assertParseCustomizationsFails() throws Exception {
        assertThrows("Expected haptic feedback customization to fail",
                CustomizationParserException.class,
                () ->  HapticFeedbackCustomization.loadVibrations(
                        mResourcesMock, mVibratorInfoMock));
    }

    private void setupCustomizationFile(String xml) throws Exception {
        File file = createFile(xml);
        setCustomizationFilePath(file.getAbsolutePath());
    }

    private void setCustomizationFilePath(String path) {
        when(mResourcesMock.getString(R.string.config_hapticFeedbackCustomizationFile))
                .thenReturn(path);
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
