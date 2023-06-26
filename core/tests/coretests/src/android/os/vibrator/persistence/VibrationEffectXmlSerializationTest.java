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

import static android.os.VibrationEffect.Composition.PRIMITIVE_CLICK;
import static android.os.VibrationEffect.Composition.PRIMITIVE_LOW_TICK;
import static android.os.VibrationEffect.Composition.PRIMITIVE_SPIN;
import static android.os.VibrationEffect.Composition.PRIMITIVE_TICK;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.os.VibrationEffect;
import android.os.vibrator.PrebakedSegment;
import android.platform.test.annotations.Presubmit;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * Unit tests for {@link VibrationXmlParser} and {@link VibrationXmlSerializer}.
 *
 * <p>The {@link VibrationEffect} public APIs are covered by CTS to enforce the schema defined at
 * services/core/xsd/vibrator/vibration/vibration.xsd.
 */
@Presubmit
@RunWith(JUnit4.class)
public class VibrationEffectXmlSerializationTest {

    @Test
    public void testPrimitives_allSucceed() throws IOException {
        VibrationEffect effect = VibrationEffect.startComposition()
                .addPrimitive(PRIMITIVE_CLICK)
                .addPrimitive(PRIMITIVE_TICK, 0.2497f)
                .addPrimitive(PRIMITIVE_LOW_TICK, 1f, 356)
                .addPrimitive(PRIMITIVE_SPIN, 0.6364f, 7)
                .compose();
        String xml = "<vibration>"
                + "<primitive-effect name=\"click\"/>"
                + "<primitive-effect name=\"tick\" scale=\"0.2497\"/>"
                + "<primitive-effect name=\"low_tick\" delayMs=\"356\"/>"
                + "<primitive-effect name=\"spin\" scale=\"0.6364\" delayMs=\"7\"/>"
                + "</vibration>";

        assertPublicApisParserSucceeds(xml, effect);
        assertPublicApisSerializerSucceeds(effect, "click", "tick", "low_tick", "spin");
        assertPublicApisRoundTrip(effect);

        assertHiddenApisParserSucceeds(xml, effect);
        assertHiddenApisSerializerSucceeds(effect, "click", "tick", "low_tick", "spin");
        assertHiddenApisRoundTrip(effect);
    }

    @Test
    public void testWaveforms_allSucceed() throws IOException {
        VibrationEffect effect = VibrationEffect.createWaveform(new long[]{123, 456, 789, 0},
                new int[]{254, 1, 255, 0}, /* repeat= */ 0);
        String xml = "<vibration>"
                + "<waveform-effect><repeating>"
                + "<waveform-entry durationMs=\"123\" amplitude=\"254\"/>"
                + "<waveform-entry durationMs=\"456\" amplitude=\"1\"/>"
                + "<waveform-entry durationMs=\"789\" amplitude=\"255\"/>"
                + "<waveform-entry durationMs=\"0\" amplitude=\"0\"/>"
                + "</repeating></waveform-effect>"
                + "</vibration>";

        assertPublicApisParserSucceeds(xml, effect);
        assertPublicApisSerializerSucceeds(effect, "123", "456", "789", "254", "1", "255", "0");
        assertPublicApisRoundTrip(effect);

        assertHiddenApisParserSucceeds(xml, effect);
        assertHiddenApisSerializerSucceeds(effect, "123", "456", "789", "254", "1", "255", "0");
        assertHiddenApisRoundTrip(effect);
    }

    @Test
    public void testPredefinedEffects_publicEffectsWithDefaultFallback_allSucceed()
            throws IOException {
        for (Map.Entry<String, Integer> entry : createPublicPredefinedEffectsMap().entrySet()) {
            VibrationEffect effect = VibrationEffect.get(entry.getValue());
            String xml = String.format(
                    "<vibration><predefined-effect name=\"%s\"/></vibration>", entry.getKey());

            assertPublicApisParserSucceeds(xml, effect);
            assertPublicApisSerializerSucceeds(effect, entry.getKey());
            assertPublicApisRoundTrip(effect);

            assertHiddenApisParserSucceeds(xml, effect);
            assertHiddenApisSerializerSucceeds(effect, entry.getKey());
            assertHiddenApisRoundTrip(effect);
        }
    }

    @Test
    public void testPredefinedEffects_hiddenEffects_onlySucceedsWithFlag() throws IOException {
        for (Map.Entry<String, Integer> entry : createHiddenPredefinedEffectsMap().entrySet()) {
            VibrationEffect effect = VibrationEffect.get(entry.getValue());
            String xml = String.format(
                    "<vibration><predefined-effect name=\"%s\"/></vibration>", entry.getKey());

            assertPublicApisParserFails(xml);
            assertPublicApisSerializerFails(effect);

            assertHiddenApisParserSucceeds(xml, effect);
            assertHiddenApisSerializerSucceeds(effect, entry.getKey());
            assertHiddenApisRoundTrip(effect);
        }
    }

    @Test
    public void testPredefinedEffects_allEffectsWithNonDefaultFallback_onlySucceedsWithFlag()
            throws IOException {
        for (Map.Entry<String, Integer> entry : createAllPredefinedEffectsMap().entrySet()) {
            boolean nonDefaultFallback = !PrebakedSegment.DEFAULT_SHOULD_FALLBACK;
            VibrationEffect effect = VibrationEffect.get(entry.getValue(), nonDefaultFallback);
            String xml = String.format(
                    "<vibration><predefined-effect name=\"%s\" fallback=\"%s\"/></vibration>",
                    entry.getKey(), nonDefaultFallback);

            assertPublicApisParserFails(xml);
            assertPublicApisSerializerFails(effect);

            assertHiddenApisParserSucceeds(xml, effect);
            assertHiddenApisSerializerSucceeds(effect, entry.getKey());
            assertHiddenApisRoundTrip(effect);
        }
    }

    private void assertPublicApisParserFails(String xml) throws IOException {
        assertThat(parse(xml, /* flags= */ 0)).isNull();
    }

    private void assertPublicApisParserSucceeds(String xml, VibrationEffect effect)
            throws IOException {
        assertThat(parse(xml, /* flags= */ 0)).isEqualTo(effect);
    }

    private void assertHiddenApisParserSucceeds(String xml, VibrationEffect effect)
            throws IOException {
        assertThat(parse(xml, VibrationXmlParser.FLAG_ALLOW_HIDDEN_APIS)).isEqualTo(effect);
    }

    private void assertPublicApisSerializerFails(VibrationEffect effect) {
        assertThrows("Expected serialization to fail for " + effect,
                VibrationXmlSerializer.SerializationFailedException.class,
                () -> serialize(effect, /* flags= */ 0));
    }

    private void assertPublicApisSerializerSucceeds(VibrationEffect effect,
            String... expectedSegments) throws IOException {
        assertSerializationContainsSegments(serialize(effect, /* flags= */ 0), expectedSegments);
    }

    private void assertHiddenApisSerializerSucceeds(VibrationEffect effect,
            String... expectedSegments) throws IOException {
        assertSerializationContainsSegments(
                serialize(effect, VibrationXmlSerializer.FLAG_ALLOW_HIDDEN_APIS), expectedSegments);
    }

    private void assertSerializationContainsSegments(String xml, String[] expectedSegments) {
        for (String expectedSegment : expectedSegments) {
            assertThat(xml).contains(expectedSegment);
        }
    }

    private void assertPublicApisRoundTrip(VibrationEffect effect) throws IOException {
        assertThat(parse(serialize(effect, /* flags= */ 0), /* flags= */ 0)).isEqualTo(effect);
    }

    private void assertHiddenApisRoundTrip(VibrationEffect effect) throws IOException {
        String xml = serialize(effect, VibrationXmlSerializer.FLAG_ALLOW_HIDDEN_APIS);
        assertThat(parse(xml, VibrationXmlParser.FLAG_ALLOW_HIDDEN_APIS)).isEqualTo(effect);
    }

    private static VibrationEffect parse(String xml, @VibrationXmlParser.Flags int flags)
            throws IOException {
        return VibrationXmlParser.parse(new StringReader(xml), flags);
    }

    private static String serialize(VibrationEffect effect, @VibrationXmlSerializer.Flags int flags)
            throws IOException {
        StringWriter writer = new StringWriter();
        VibrationXmlSerializer.serialize(effect, writer, flags);
        return writer.toString();
    }

    private static Map<String, Integer> createAllPredefinedEffectsMap() {
        Map<String, Integer> map = createHiddenPredefinedEffectsMap();
        map.putAll(createPublicPredefinedEffectsMap());
        return map;
    }

    private static Map<String, Integer> createPublicPredefinedEffectsMap() {
        Map<String, Integer> map = new HashMap<>();
        map.put("tick", VibrationEffect.EFFECT_TICK);
        map.put("click", VibrationEffect.EFFECT_CLICK);
        map.put("heavy_click", VibrationEffect.EFFECT_HEAVY_CLICK);
        map.put("double_click", VibrationEffect.EFFECT_DOUBLE_CLICK);
        return map;
    }

    private static Map<String, Integer> createHiddenPredefinedEffectsMap() {
        Map<String, Integer> map = new HashMap<>();
        map.put("texture_tick", VibrationEffect.EFFECT_TEXTURE_TICK);
        map.put("pop", VibrationEffect.EFFECT_POP);
        map.put("thud", VibrationEffect.EFFECT_THUD);
        for (int i = 0; i < VibrationEffect.RINGTONES.length; i++) {
            map.put(String.format("ringtone_%d", i + 1), VibrationEffect.RINGTONES[i]);
        }
        return map;
    }
}
