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
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import android.os.VibrationEffect;
import android.util.AtomicFile;
import android.util.SparseArray;

import androidx.test.InstrumentationRegistry;

import com.android.internal.R;

import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.io.File;
import java.io.FileOutputStream;

public class HapticFeedbackCustomizationTest {
    @Rule public MockitoRule rule = MockitoJUnit.rule();

    // Pairs of valid vibration XML along with their equivalent VibrationEffect.
    private static final String COMPOSITION_VIBRATION_XML = "<vibration>"
            + "<primitive-effect name=\"tick\" scale=\"0.2497\"/>"
            + "</vibration>";
    private static final VibrationEffect COMPOSITION_VIBRATION =
            VibrationEffect.startComposition().addPrimitive(PRIMITIVE_TICK, 0.2497f).compose();

    private static final String PREDEFINED_VIBRATION_XML =
            "<vibration><predefined-effect name=\"click\"/></vibration>";
    private static final VibrationEffect PREDEFINED_VIBRATION =
            VibrationEffect.createPredefined(EFFECT_CLICK);

    @Mock private Resources mResourcesMock;

    @Test
    public void testParseCustomizations_noCustomization_success() throws Exception {
        assertParseCustomizationsSucceeds(
                /* xml= */ "<haptic-feedback-constants></haptic-feedback-constants>",
                /* expectedCustomizations= */ new SparseArray<>());
    }

    @Test
    public void testParseCustomizations_oneCustomization_success() throws Exception {
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
    public void testParseCustomizations_multipleCustomizations_success() throws Exception {
        String xml = "<haptic-feedback-constants>"
                + "<constant id=\"1\">"
                + COMPOSITION_VIBRATION_XML
                + "</constant>"
                + "<constant id=\"12\">"
                + PREDEFINED_VIBRATION_XML
                + "</constant>"
                + "<constant id=\"150\">"
                + PREDEFINED_VIBRATION_XML
                + "</constant>"
                + "</haptic-feedback-constants>";
        SparseArray<VibrationEffect> expectedMapping = new SparseArray<>();
        expectedMapping.put(1, COMPOSITION_VIBRATION);
        expectedMapping.put(12, PREDEFINED_VIBRATION);
        expectedMapping.put(150, PREDEFINED_VIBRATION);

        assertParseCustomizationsSucceeds(xml, expectedMapping);
    }

    @Test
    public void testParseCustomizations_noCustomizationFile_returnsNull() throws Exception {
        setCustomizationFilePath("");

        assertThat(HapticFeedbackCustomization.loadVibrations(mResourcesMock)).isNull();

        setCustomizationFilePath(null);

        assertThat(HapticFeedbackCustomization.loadVibrations(mResourcesMock)).isNull();

        setCustomizationFilePath("non_existent_file.xml");

        assertThat(HapticFeedbackCustomization.loadVibrations(mResourcesMock)).isNull();
    }

    @Test
    public void testParseCustomizations_disallowedVibrationForHapticFeedback_throwsException()
            throws Exception {
        // The XML content is good, but the serialized vibration is not supported for haptic
        // feedback usage (i.e. repeating vibration).
        assertParseCustomizationsFails(
                "<haptic-feedback-constants>"
                + "<constant id=\"10\">"
                + "<vibration>"
                + "<waveform-effect>"
                + "<repeating>"
                + "<waveform-entry durationMs=\"10\" amplitude=\"100\"/>"
                + "</repeating>"
                + "</waveform-effect>"
                + "</vibration>"
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
                + "<bad-vibration></bad-vibration>"
                + "</constant>"
                + "</haptic-feedback-constants>");

        assertParseCustomizationsFails(
                "<haptic-feedback-constants>"
                + "<constant id=\"10\">"
                + "<vibration><predefined-effect name=\"bad-effect-name\"/></vibration>"
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
                HapticFeedbackCustomization.loadVibrations(mResourcesMock))).isTrue();
    }

    private void assertParseCustomizationsFails(String xml) throws Exception {
        setupCustomizationFile(xml);
        assertThrows("Expected haptic feedback customization to fail for " + xml,
                CustomizationParserException.class,
                () ->  HapticFeedbackCustomization.loadVibrations(mResourcesMock));
    }

    private void assertParseCustomizationsFails() throws Exception {
        assertThrows("Expected haptic feedback customization to fail",
                CustomizationParserException.class,
                () ->  HapticFeedbackCustomization.loadVibrations(mResourcesMock));
    }

    private void setupCustomizationFile(String xml) throws Exception {
        File file = createFile(xml);
        setCustomizationFilePath(file.getAbsolutePath());
    }

    private void setCustomizationFilePath(String path) {
        when(mResourcesMock.getString(R.string.config_hapticFeedbackCustomizationFile))
                .thenReturn(path);
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
