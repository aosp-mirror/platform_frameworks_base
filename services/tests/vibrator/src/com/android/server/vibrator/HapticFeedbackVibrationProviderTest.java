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

import static android.os.VibrationAttributes.CATEGORY_KEYBOARD;
import static android.os.VibrationAttributes.CATEGORY_UNKNOWN;
import static android.os.VibrationAttributes.FLAG_BYPASS_INTERRUPTION_POLICY;
import static android.os.VibrationAttributes.FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF;
import static android.os.VibrationAttributes.FLAG_BYPASS_USER_VIBRATION_INTENSITY_SCALE;
import static android.os.VibrationAttributes.USAGE_TOUCH;
import static android.os.VibrationEffect.Composition.PRIMITIVE_CLICK;
import static android.os.VibrationEffect.Composition.PRIMITIVE_TICK;
import static android.os.VibrationEffect.EFFECT_CLICK;
import static android.os.VibrationEffect.EFFECT_TEXTURE_TICK;
import static android.os.VibrationEffect.EFFECT_TICK;
import static android.view.HapticFeedbackConstants.CLOCK_TICK;
import static android.view.HapticFeedbackConstants.CONTEXT_CLICK;
import static android.view.HapticFeedbackConstants.KEYBOARD_RELEASE;
import static android.view.HapticFeedbackConstants.KEYBOARD_TAP;
import static android.view.HapticFeedbackConstants.SAFE_MODE_ENABLED;
import static android.view.HapticFeedbackConstants.SCROLL_ITEM_FOCUS;
import static android.view.HapticFeedbackConstants.SCROLL_LIMIT;
import static android.view.HapticFeedbackConstants.SCROLL_TICK;
import static android.view.HapticFeedbackConstants.TEXT_HANDLE_MOVE;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.hardware.vibrator.IVibrator;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.VibratorInfo;
import android.os.vibrator.Flags;
import android.platform.test.flag.junit.SetFlagsRule;
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

public class HapticFeedbackVibrationProviderTest {
    @Rule public MockitoRule rule = MockitoJUnit.rule();

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private static final VibrationEffect PRIMITIVE_TICK_EFFECT =
            VibrationEffect.startComposition().addPrimitive(PRIMITIVE_TICK, 0.2497f).compose();
    private static final VibrationEffect PRIMITIVE_CLICK_EFFECT =
            VibrationEffect.startComposition().addPrimitive(PRIMITIVE_CLICK, 0.3497f).compose();

    private static final int[] SCROLL_FEEDBACK_CONSTANTS =
            new int[] {SCROLL_ITEM_FOCUS, SCROLL_LIMIT, SCROLL_TICK};
    private static final int[] KEYBOARD_FEEDBACK_CONSTANTS =
            new int[] {KEYBOARD_TAP, KEYBOARD_RELEASE};

    private static final float KEYBOARD_VIBRATION_FIXED_AMPLITUDE = 0.62f;

    private Context mContext = InstrumentationRegistry.getContext();
    private VibratorInfo mVibratorInfo = VibratorInfo.EMPTY_VIBRATOR_INFO;

    @Mock private Resources mResourcesMock;

    @Test
    public void testNonExistentCustomization_useDefault() throws Exception {
        // No customization file is set.
        HapticFeedbackVibrationProvider hapticProvider = createProviderWithDefaultCustomizations();

        assertThat(hapticProvider.getVibrationForHapticFeedback(CONTEXT_CLICK))
                .isEqualTo(VibrationEffect.get(EFFECT_TICK));

        // The customization file specifies no customization.
        setupCustomizationFile("<haptic-feedback-constants></haptic-feedback-constants>");
        hapticProvider = createProviderWithDefaultCustomizations();

        assertThat(hapticProvider.getVibrationForHapticFeedback(CONTEXT_CLICK))
                .isEqualTo(VibrationEffect.get(EFFECT_TICK));
    }

    @Test
    public void testExceptionParsingCustomizations_useDefault() throws Exception {
        setupCustomizationFile("<bad-xml></bad-xml>");
        HapticFeedbackVibrationProvider hapticProvider = createProviderWithDefaultCustomizations();

        assertThat(hapticProvider.getVibrationForHapticFeedback(CONTEXT_CLICK))
                .isEqualTo(VibrationEffect.get(EFFECT_TICK));
    }

    @Test
    public void testUseValidCustomizedVibration() throws Exception {
        mockVibratorPrimitiveSupport(PRIMITIVE_CLICK);
        SparseArray<VibrationEffect> customizations = new SparseArray<>();
        customizations.put(CONTEXT_CLICK, PRIMITIVE_CLICK_EFFECT);

        HapticFeedbackVibrationProvider hapticProvider = createProvider(customizations);

        // The override for `CONTEXT_CLICK` is used.
        assertThat(hapticProvider.getVibrationForHapticFeedback(CONTEXT_CLICK))
                .isEqualTo(PRIMITIVE_CLICK_EFFECT);
        // `CLOCK_TICK` has no override, so the default vibration is used.
        assertThat(hapticProvider.getVibrationForHapticFeedback(CLOCK_TICK))
                .isEqualTo(VibrationEffect.get(EFFECT_TEXTURE_TICK));
    }

    @Test
    public void testDoNotUseInvalidCustomizedVibration() throws Exception {
        mockVibratorPrimitiveSupport(new int[] {});
        String xml = "<haptic-feedback-constants>"
                + "<constant id=\"" + CONTEXT_CLICK + "\">"
                + PRIMITIVE_CLICK_EFFECT
                + "</constant>"
                + "</haptic-feedback-constants>";
        setupCustomizationFile(xml);

        HapticFeedbackVibrationProvider hapticProvider = createProviderWithDefaultCustomizations();

        // The override for `CONTEXT_CLICK` is not used because the vibration is not supported.
        assertThat(hapticProvider.getVibrationForHapticFeedback(CONTEXT_CLICK))
                .isEqualTo(VibrationEffect.get(EFFECT_TICK));
        // `CLOCK_TICK` has no override, so the default vibration is used.
        assertThat(hapticProvider.getVibrationForHapticFeedback(CLOCK_TICK))
                .isEqualTo(VibrationEffect.get(EFFECT_TEXTURE_TICK));
    }

    @Test
    public void testHapticTextDisabled_noVibrationReturnedForTextHandleMove() throws Exception {
        mockHapticTextSupport(false);
        mockVibratorPrimitiveSupport(PRIMITIVE_CLICK);
        SparseArray<VibrationEffect> customizations = new SparseArray<>();
        customizations.put(TEXT_HANDLE_MOVE, PRIMITIVE_CLICK_EFFECT);

        // Test with a customization available for `TEXT_HANDLE_MOVE`.
        HapticFeedbackVibrationProvider hapticProvider = createProvider(customizations);

        assertThat(hapticProvider.getVibrationForHapticFeedback(TEXT_HANDLE_MOVE)).isNull();

        // Test with no customization available for `TEXT_HANDLE_MOVE`.
        hapticProvider = createProvider(/* customizations= */ null);

        assertThat(hapticProvider.getVibrationForHapticFeedback(TEXT_HANDLE_MOVE)).isNull();
    }

    @Test
    public void testHapticTextEnabled_vibrationReturnedForTextHandleMove() throws Exception {
        mockHapticTextSupport(true);
        mockVibratorPrimitiveSupport(PRIMITIVE_CLICK);
        SparseArray<VibrationEffect> customizations = new SparseArray<>();
        customizations.put(TEXT_HANDLE_MOVE, PRIMITIVE_CLICK_EFFECT);

        // Test with a customization available for `TEXT_HANDLE_MOVE`.
        HapticFeedbackVibrationProvider hapticProvider = createProvider(customizations);

        assertThat(hapticProvider.getVibrationForHapticFeedback(TEXT_HANDLE_MOVE))
                .isEqualTo(PRIMITIVE_CLICK_EFFECT);

        // Test with no customization available for `TEXT_HANDLE_MOVE`.
        hapticProvider = createProvider(/* customizations= */ null);

        assertThat(hapticProvider.getVibrationForHapticFeedback(TEXT_HANDLE_MOVE))
                .isEqualTo(VibrationEffect.get(EFFECT_TEXTURE_TICK));
    }

    @Test
    public void testValidCustomizationPresentForSafeModeEnabled_usedRegardlessOfVibrationResource()
                throws Exception {
        mockSafeModeEnabledVibration(10, 20, 30, 40);
        mockVibratorPrimitiveSupport(PRIMITIVE_CLICK);
        SparseArray<VibrationEffect> customizations = new SparseArray<>();
        customizations.put(SAFE_MODE_ENABLED, PRIMITIVE_CLICK_EFFECT);

        HapticFeedbackVibrationProvider hapticProvider = createProvider(customizations);

        assertThat(hapticProvider.getVibrationForHapticFeedback(SAFE_MODE_ENABLED))
                .isEqualTo(PRIMITIVE_CLICK_EFFECT);

        mockSafeModeEnabledVibration(null);
        hapticProvider = createProvider(customizations);

        assertThat(hapticProvider.getVibrationForHapticFeedback(SAFE_MODE_ENABLED))
                .isEqualTo(PRIMITIVE_CLICK_EFFECT);
    }

    @Test
    public void testNoValidCustomizationPresentForSafeModeEnabled_resourceBasedVibrationUsed()
                throws Exception {
        mockSafeModeEnabledVibration(10, 20, 30, 40);
        HapticFeedbackVibrationProvider hapticProvider = createProvider(/* customizations= */ null);

        assertThat(hapticProvider.getVibrationForHapticFeedback(SAFE_MODE_ENABLED))
                .isEqualTo(VibrationEffect.createWaveform(new long[] {10, 20, 30, 40}, -1));
    }

    @Test
    public void testNoValidCustomizationAndResourcePresentForSafeModeEnabled_noVibrationUsed()
                throws Exception {
        mockSafeModeEnabledVibration(null);
        HapticFeedbackVibrationProvider hapticProvider = createProvider(/* customizations= */ null);

        assertThat(hapticProvider.getVibrationForHapticFeedback(SAFE_MODE_ENABLED)).isNull();
    }

    @Test
    public void testKeyboardHaptic_noFixedAmplitude_defaultVibrationReturned() {
        mockVibratorPrimitiveSupport(PRIMITIVE_CLICK, PRIMITIVE_TICK);
        SparseArray<VibrationEffect> customizations = new SparseArray<>();
        customizations.put(KEYBOARD_TAP, PRIMITIVE_CLICK_EFFECT);
        customizations.put(KEYBOARD_RELEASE, PRIMITIVE_TICK_EFFECT);

        // Test with a customization available for `KEYBOARD_TAP` & `KEYBOARD_RELEASE`.
        HapticFeedbackVibrationProvider hapticProvider = createProvider(customizations);

        assertThat(hapticProvider.getVibrationForHapticFeedback(KEYBOARD_TAP))
                .isEqualTo(PRIMITIVE_CLICK_EFFECT);
        assertThat(hapticProvider.getVibrationForHapticFeedback(KEYBOARD_RELEASE))
                .isEqualTo(PRIMITIVE_TICK_EFFECT);

        // Test with no customization available for `KEYBOARD_TAP` & `KEYBOARD_RELEASE`.
        hapticProvider = createProviderWithDefaultCustomizations();

        assertThat(hapticProvider.getVibrationForHapticFeedback(KEYBOARD_TAP))
                .isEqualTo(VibrationEffect.get(EFFECT_CLICK, true /* fallback */));
        assertThat(hapticProvider.getVibrationForHapticFeedback(KEYBOARD_RELEASE))
                .isEqualTo(VibrationEffect.get(EFFECT_TICK, false /* fallback */));
    }

    @Test
    public void testKeyboardHaptic_fixAmplitude_keyboardCategoryOff_defaultVibrationReturned() {
        mSetFlagsRule.disableFlags(Flags.FLAG_KEYBOARD_CATEGORY_ENABLED);
        mockVibratorPrimitiveSupport(PRIMITIVE_CLICK, PRIMITIVE_TICK);
        mockKeyboardVibrationFixedAmplitude(KEYBOARD_VIBRATION_FIXED_AMPLITUDE);

        HapticFeedbackVibrationProvider hapticProvider = createProviderWithDefaultCustomizations();

        assertThat(hapticProvider.getVibrationForHapticFeedback(KEYBOARD_TAP))
                .isEqualTo(VibrationEffect.get(EFFECT_CLICK, true /* fallback */));
        assertThat(hapticProvider.getVibrationForHapticFeedback(KEYBOARD_RELEASE))
                .isEqualTo(VibrationEffect.get(EFFECT_TICK, false /* fallback */));
    }

    @Test
    public void testKeyboardHaptic_fixAmplitude_keyboardCategoryOn_keyboardVibrationReturned() {
        mSetFlagsRule.enableFlags(Flags.FLAG_KEYBOARD_CATEGORY_ENABLED);
        mockVibratorPrimitiveSupport(PRIMITIVE_CLICK, PRIMITIVE_TICK);
        mockKeyboardVibrationFixedAmplitude(KEYBOARD_VIBRATION_FIXED_AMPLITUDE);

        HapticFeedbackVibrationProvider hapticProvider = createProviderWithDefaultCustomizations();

        assertThat(hapticProvider.getVibrationForHapticFeedback(KEYBOARD_TAP))
                .isEqualTo(VibrationEffect.startComposition()
                        .addPrimitive(PRIMITIVE_CLICK, KEYBOARD_VIBRATION_FIXED_AMPLITUDE)
                        .compose());
        assertThat(hapticProvider.getVibrationForHapticFeedback(KEYBOARD_RELEASE))
                .isEqualTo(VibrationEffect.startComposition()
                        .addPrimitive(PRIMITIVE_TICK, KEYBOARD_VIBRATION_FIXED_AMPLITUDE)
                        .compose());
    }

    @Test
    public void testVibrationAttribute_forNotBypassingIntensitySettings() {
        HapticFeedbackVibrationProvider hapticProvider = createProviderWithDefaultCustomizations();

        VibrationAttributes attrs = hapticProvider.getVibrationAttributesForHapticFeedback(
                SAFE_MODE_ENABLED, /* bypassVibrationIntensitySetting= */ false,
                false /* fromIme*/);

        assertThat(attrs.isFlagSet(FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF)).isFalse();
    }

    @Test
    public void testVibrationAttribute_forByassingIntensitySettings() {
        HapticFeedbackVibrationProvider hapticProvider = createProviderWithDefaultCustomizations();

        VibrationAttributes attrs = hapticProvider.getVibrationAttributesForHapticFeedback(
                SAFE_MODE_ENABLED, /* bypassVibrationIntensitySetting= */ true, false /* fromIme*/);

        assertThat(attrs.isFlagSet(FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF)).isTrue();
    }

    @Test
    public void testVibrationAttribute_scrollFeedback_scrollApiFlagOn_bypassInterruptPolicy() {
        mSetFlagsRule.enableFlags(android.view.flags.Flags.FLAG_SCROLL_FEEDBACK_API);
        HapticFeedbackVibrationProvider hapticProvider = createProviderWithDefaultCustomizations();

        for (int effectId : SCROLL_FEEDBACK_CONSTANTS) {
            VibrationAttributes attrs = hapticProvider.getVibrationAttributesForHapticFeedback(
                    effectId, /* bypassVibrationIntensitySetting= */ false, false /* fromIme*/);
            assertWithMessage("Expected FLAG_BYPASS_INTERRUPTION_POLICY for effect " + effectId)
                   .that(attrs.isFlagSet(FLAG_BYPASS_INTERRUPTION_POLICY)).isTrue();
        }
    }

    @Test
    public void testVibrationAttribute_scrollFeedback_scrollApiFlagOff_noBypassInterruptPolicy() {
        mSetFlagsRule.disableFlags(android.view.flags.Flags.FLAG_SCROLL_FEEDBACK_API);
        HapticFeedbackVibrationProvider hapticProvider = createProviderWithDefaultCustomizations();

        for (int effectId : SCROLL_FEEDBACK_CONSTANTS) {
            VibrationAttributes attrs = hapticProvider.getVibrationAttributesForHapticFeedback(
                    effectId, /* bypassVibrationIntensitySetting= */ false, false /* fromIme*/);
            assertWithMessage("Expected no FLAG_BYPASS_INTERRUPTION_POLICY for effect " + effectId)
                   .that(attrs.isFlagSet(FLAG_BYPASS_INTERRUPTION_POLICY)).isFalse();
        }
    }

    @Test
    public void testVibrationAttribute_keyboardCategoryOff_isIme_notUseKeyboardCategory() {
        mSetFlagsRule.disableFlags(Flags.FLAG_KEYBOARD_CATEGORY_ENABLED);
        HapticFeedbackVibrationProvider hapticProvider = createProviderWithDefaultCustomizations();

        for (int effectId : KEYBOARD_FEEDBACK_CONSTANTS) {
            VibrationAttributes attrs = hapticProvider.getVibrationAttributesForHapticFeedback(
                    effectId, /* bypassVibrationIntensitySetting= */ false, true /* fromIme*/);
            assertWithMessage("Expected USAGE_TOUCH for effect " + effectId)
                    .that(attrs.getUsage()).isEqualTo(USAGE_TOUCH);
            assertWithMessage("Expected no CATEGORY_KEYBOARD for effect " + effectId)
                    .that(attrs.getCategory()).isEqualTo(CATEGORY_UNKNOWN);
        }
    }

    @Test
    public void testVibrationAttribute_keyboardCategoryOn_notIme_notUseKeyboardCategory() {
        mSetFlagsRule.enableFlags(Flags.FLAG_KEYBOARD_CATEGORY_ENABLED);
        HapticFeedbackVibrationProvider hapticProvider = createProviderWithDefaultCustomizations();

        for (int effectId : KEYBOARD_FEEDBACK_CONSTANTS) {
            VibrationAttributes attrs = hapticProvider.getVibrationAttributesForHapticFeedback(
                    effectId, /* bypassVibrationIntensitySetting= */ false, false /* fromIme*/);
            assertWithMessage("Expected USAGE_TOUCH for effect " + effectId)
                    .that(attrs.getUsage()).isEqualTo(USAGE_TOUCH);
            assertWithMessage("Expected CATEGORY_KEYBOARD for effect " + effectId)
                    .that(attrs.getCategory()).isEqualTo(CATEGORY_UNKNOWN);
        }
    }

    @Test
    public void testVibrationAttribute_keyboardCategoryOn_isIme_useKeyboardCategory() {
        mSetFlagsRule.enableFlags(Flags.FLAG_KEYBOARD_CATEGORY_ENABLED);
        HapticFeedbackVibrationProvider hapticProvider = createProviderWithDefaultCustomizations();

        for (int effectId : KEYBOARD_FEEDBACK_CONSTANTS) {
            VibrationAttributes attrs = hapticProvider.getVibrationAttributesForHapticFeedback(
                    effectId, /* bypassVibrationIntensitySetting= */ false, true /* fromIme*/);
            assertWithMessage("Expected USAGE_TOUCH for effect " + effectId)
                    .that(attrs.getUsage()).isEqualTo(USAGE_TOUCH);
            assertWithMessage("Expected CATEGORY_KEYBOARD for effect " + effectId)
                    .that(attrs.getCategory()).isEqualTo(CATEGORY_KEYBOARD);
        }
    }

    @Test
    public void testVibrationAttribute_noFixAmplitude_notBypassIntensityScale() {
        mSetFlagsRule.enableFlags(Flags.FLAG_KEYBOARD_CATEGORY_ENABLED);
        mockVibratorPrimitiveSupport(PRIMITIVE_CLICK, PRIMITIVE_TICK);
        mockKeyboardVibrationFixedAmplitude(-1);
        HapticFeedbackVibrationProvider hapticProvider = createProviderWithDefaultCustomizations();

        for (int effectId : KEYBOARD_FEEDBACK_CONSTANTS) {
            VibrationAttributes attrs = hapticProvider.getVibrationAttributesForHapticFeedback(
                    effectId, /* bypassVibrationIntensitySetting= */ false, true /* fromIme*/);
            assertWithMessage("Expected no FLAG_BYPASS_USER_VIBRATION_INTENSITY_SCALE for effect "
                    + effectId)
                    .that(attrs.isFlagSet(FLAG_BYPASS_USER_VIBRATION_INTENSITY_SCALE)).isFalse();
        }
    }

    @Test
    public void testVibrationAttribute_notIme_notBypassIntensityScale() {
        mSetFlagsRule.enableFlags(Flags.FLAG_KEYBOARD_CATEGORY_ENABLED);
        mockVibratorPrimitiveSupport(PRIMITIVE_CLICK, PRIMITIVE_TICK);
        mockKeyboardVibrationFixedAmplitude(KEYBOARD_VIBRATION_FIXED_AMPLITUDE);
        HapticFeedbackVibrationProvider hapticProvider = createProviderWithDefaultCustomizations();

        for (int effectId : KEYBOARD_FEEDBACK_CONSTANTS) {
            VibrationAttributes attrs = hapticProvider.getVibrationAttributesForHapticFeedback(
                    effectId, /* bypassVibrationIntensitySetting= */ false, false /* fromIme*/);
            assertWithMessage("Expected no FLAG_BYPASS_USER_VIBRATION_INTENSITY_SCALE for effect "
                    + effectId)
                    .that(attrs.isFlagSet(FLAG_BYPASS_USER_VIBRATION_INTENSITY_SCALE)).isFalse();
        }
    }

    @Test
    public void testVibrationAttribute_fixAmplitude_isIme_bypassIntensityScale() {
        mSetFlagsRule.enableFlags(Flags.FLAG_KEYBOARD_CATEGORY_ENABLED);
        mockVibratorPrimitiveSupport(PRIMITIVE_CLICK, PRIMITIVE_TICK);
        mockKeyboardVibrationFixedAmplitude(KEYBOARD_VIBRATION_FIXED_AMPLITUDE);
        HapticFeedbackVibrationProvider hapticProvider = createProviderWithDefaultCustomizations();

        for (int effectId : KEYBOARD_FEEDBACK_CONSTANTS) {
            VibrationAttributes attrs = hapticProvider.getVibrationAttributesForHapticFeedback(
                    effectId, /* bypassVibrationIntensitySetting= */ false, true /* fromIme*/);
            assertWithMessage("Expected FLAG_BYPASS_USER_VIBRATION_INTENSITY_SCALE for effect "
                    + effectId)
                    .that(attrs.isFlagSet(FLAG_BYPASS_USER_VIBRATION_INTENSITY_SCALE)).isTrue();
        }
    }

    private HapticFeedbackVibrationProvider createProviderWithDefaultCustomizations() {
        return createProvider(/* customizations= */ null);
    }

    private HapticFeedbackVibrationProvider createProvider(
            SparseArray<VibrationEffect> customizations) {
        return new HapticFeedbackVibrationProvider(mResourcesMock, mVibratorInfo, customizations);
    }

    private void mockVibratorPrimitiveSupport(int... supportedPrimitives) {
        VibratorInfo.Builder builder = new VibratorInfo.Builder(/* id= */ 1)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        for (int primitive : supportedPrimitives) {
            builder.setSupportedPrimitive(primitive, 10);
        }
        mVibratorInfo = builder.build();
    }

    private void mockHapticTextSupport(boolean supported) {
        when(mResourcesMock.getBoolean(R.bool.config_enableHapticTextHandle)).thenReturn(supported);
    }

    private void mockSafeModeEnabledVibration(int... vibrationPattern) {
        when(mResourcesMock.getIntArray(R.array.config_safeModeEnabledVibePattern))
                .thenReturn(vibrationPattern);
    }

    private void mockKeyboardVibrationFixedAmplitude(float amplitude) {
        when(mResourcesMock.getFloat(R.dimen.config_keyboardHapticFeedbackFixedAmplitude))
                .thenReturn(amplitude);
    }

    private void setupCustomizationFile(String xml) throws Exception {
        File file = new File(mContext.getCacheDir(), "test.xml");
        file.createNewFile();

        AtomicFile atomicXmlFile = new AtomicFile(file);
        FileOutputStream fos = atomicXmlFile.startWrite();
        fos.write(xml.getBytes());
        atomicXmlFile.finishWrite(fos);

        when(mResourcesMock.getString(R.string.config_hapticFeedbackCustomizationFile))
                .thenReturn(file.getAbsolutePath());
    }
}
