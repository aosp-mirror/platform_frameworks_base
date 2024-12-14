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

import static android.os.VibrationAttributes.FLAG_BYPASS_INTERRUPTION_POLICY;
import static android.os.VibrationAttributes.FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF;
import static android.os.VibrationAttributes.USAGE_HARDWARE_FEEDBACK;
import static android.os.VibrationAttributes.USAGE_IME_FEEDBACK;
import static android.os.VibrationAttributes.USAGE_TOUCH;
import static android.os.VibrationEffect.Composition.PRIMITIVE_CLICK;
import static android.os.VibrationEffect.Composition.PRIMITIVE_QUICK_RISE;
import static android.os.VibrationEffect.Composition.PRIMITIVE_THUD;
import static android.os.VibrationEffect.Composition.PRIMITIVE_TICK;
import static android.os.VibrationEffect.EFFECT_CLICK;
import static android.os.VibrationEffect.EFFECT_TEXTURE_TICK;
import static android.os.VibrationEffect.EFFECT_TICK;
import static android.os.vibrator.Flags.FLAG_HAPTIC_FEEDBACK_INPUT_SOURCE_CUSTOMIZATION_ENABLED;
import static android.view.HapticFeedbackConstants.BIOMETRIC_CONFIRM;
import static android.view.HapticFeedbackConstants.BIOMETRIC_REJECT;
import static android.view.HapticFeedbackConstants.CLOCK_TICK;
import static android.view.HapticFeedbackConstants.CONTEXT_CLICK;
import static android.view.HapticFeedbackConstants.DRAG_START;
import static android.view.HapticFeedbackConstants.KEYBOARD_RELEASE;
import static android.view.HapticFeedbackConstants.KEYBOARD_TAP;
import static android.view.HapticFeedbackConstants.NO_HAPTICS;
import static android.view.HapticFeedbackConstants.SAFE_MODE_ENABLED;
import static android.view.HapticFeedbackConstants.SCROLL_ITEM_FOCUS;
import static android.view.HapticFeedbackConstants.SCROLL_LIMIT;
import static android.view.HapticFeedbackConstants.SCROLL_TICK;
import static android.view.HapticFeedbackConstants.TEXT_HANDLE_MOVE;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.vibrator.IVibrator;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.VibratorInfo;
import android.platform.test.flag.junit.SetFlagsRule;
import android.util.AtomicFile;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;

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

public class HapticFeedbackVibrationProviderTest {
    @Rule public MockitoRule rule = MockitoJUnit.rule();

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private static final VibrationEffect PRIMITIVE_TICK_EFFECT =
            VibrationEffect.startComposition().addPrimitive(PRIMITIVE_TICK, 0.2497f).compose();
    private static final VibrationEffect PRIMITIVE_CLICK_EFFECT =
            VibrationEffect.startComposition().addPrimitive(PRIMITIVE_CLICK, 0.3497f).compose();
    private static final VibrationEffect PRIMITIVE_THUD_EFFECT =
            VibrationEffect.startComposition().addPrimitive(PRIMITIVE_THUD, 0.5497f).compose();
    private static final VibrationEffect PRIMITIVE_QUICK_RISE_EFFECT =
            VibrationEffect.startComposition().addPrimitive(PRIMITIVE_QUICK_RISE,
                    0.6497f).compose();

    private static final int[] SCROLL_FEEDBACK_CONSTANTS =
            new int[] {SCROLL_ITEM_FOCUS, SCROLL_LIMIT, SCROLL_TICK};
    private static final int[] KEYBOARD_FEEDBACK_CONSTANTS =
            new int[] {KEYBOARD_TAP, KEYBOARD_RELEASE};
    private static final int[] BIOMETRIC_FEEDBACK_CONSTANTS =
            new int[] {BIOMETRIC_CONFIRM, BIOMETRIC_REJECT};

    private static final float KEYBOARD_VIBRATION_FIXED_AMPLITUDE = 0.62f;

    private Context mContext = InstrumentationRegistry.getContext();
    private VibratorInfo mVibratorInfo = VibratorInfo.EMPTY_VIBRATOR_INFO;

    @Mock private Resources mResourcesMock;

    @Before
    public void setUp() {
        mSetFlagsRule.disableFlags(FLAG_HAPTIC_FEEDBACK_INPUT_SOURCE_CUSTOMIZATION_ENABLED);
    }

    @Test
    public void testNonExistentCustomization_useDefault() {
        HapticFeedbackVibrationProvider provider = createProviderWithoutCustomizations();

        // No customization for `CLOCK_TICK`, so the default vibration is used.
        assertThat(provider.getVibration(CLOCK_TICK)).isEqualTo(
                VibrationEffect.get(EFFECT_TEXTURE_TICK));
        assertThat(provider.getVibration(CLOCK_TICK,
                InputDevice.SOURCE_ROTARY_ENCODER)).isEqualTo(
                VibrationEffect.get(EFFECT_TEXTURE_TICK));
        assertThat(provider.getVibration(CLOCK_TICK, InputDevice.SOURCE_TOUCHSCREEN))
                .isEqualTo(VibrationEffect.get(EFFECT_TEXTURE_TICK));
    }

    @Test
    public void testUseValidCustomizedVibration() {
        mockVibratorPrimitiveSupport(PRIMITIVE_CLICK, PRIMITIVE_TICK, PRIMITIVE_THUD,
                PRIMITIVE_QUICK_RISE);
        SparseArray<VibrationEffect> customizations = new SparseArray<>();
        customizations.put(CONTEXT_CLICK, PRIMITIVE_CLICK_EFFECT);
        SparseArray<VibrationEffect> customizationsRotary = new SparseArray<>();
        customizationsRotary.put(CONTEXT_CLICK, PRIMITIVE_TICK_EFFECT);
        customizationsRotary.put(DRAG_START, PRIMITIVE_QUICK_RISE_EFFECT);
        SparseArray<VibrationEffect> customizationsTouchScreen = new SparseArray<>();
        customizationsTouchScreen.put(CONTEXT_CLICK, PRIMITIVE_THUD_EFFECT);
        customizationsTouchScreen.put(DRAG_START, PRIMITIVE_CLICK_EFFECT);
        HapticFeedbackVibrationProvider provider = createProvider(customizations,
                customizationsRotary, customizationsTouchScreen);

        // The customization for `CONTEXT_CLICK`.
        assertThat(provider.getVibration(CONTEXT_CLICK))
                .isEqualTo(PRIMITIVE_CLICK_EFFECT);
        assertThat(provider.getVibration(CONTEXT_CLICK,
                InputDevice.SOURCE_ROTARY_ENCODER)).isEqualTo(PRIMITIVE_TICK_EFFECT);
        assertThat(provider.getVibration(CONTEXT_CLICK,
                InputDevice.SOURCE_TOUCHSCREEN)).isEqualTo(PRIMITIVE_THUD_EFFECT);
        // The customization for `DRAG_START`.
        assertThat(provider.getVibration(DRAG_START,
                InputDevice.SOURCE_ROTARY_ENCODER)).isEqualTo(PRIMITIVE_QUICK_RISE_EFFECT);
        assertThat(provider.getVibration(DRAG_START,
                InputDevice.SOURCE_TOUCHSCREEN)).isEqualTo(PRIMITIVE_CLICK_EFFECT);
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
        HapticFeedbackVibrationProvider provider = createProviderWithoutCustomizations();

        // The override for `CONTEXT_CLICK` is not used because the vibration is not supported.
        assertThat(provider.getVibration(CONTEXT_CLICK))
                .isEqualTo(VibrationEffect.get(EFFECT_TICK));
        // `CLOCK_TICK` has no override, so the default vibration is used.
        assertThat(provider.getVibration(CLOCK_TICK))
                .isEqualTo(VibrationEffect.get(EFFECT_TEXTURE_TICK));
    }

    @Test
    public void testHapticTextDisabled_noVibrationReturnedForTextHandleMove() {
        mockHapticTextSupport(false);
        mockVibratorPrimitiveSupport(PRIMITIVE_CLICK);
        SparseArray<VibrationEffect> customizations = new SparseArray<>();
        customizations.put(TEXT_HANDLE_MOVE, PRIMITIVE_CLICK_EFFECT);
        HapticFeedbackVibrationProvider provider = createProvider(
                /* customizations= */ customizations,
                /* customizationsForRotary= */ customizations,
                /* customizationsForTouchScreen= */ customizations);

        // Test with a customization available for `TEXT_HANDLE_MOVE`.
        assertThat(provider.getVibration(TEXT_HANDLE_MOVE)).isNull();
        assertThat(provider.getVibration(TEXT_HANDLE_MOVE,
                InputDevice.SOURCE_ROTARY_ENCODER)).isNull();
        assertThat(
                provider.getVibration(TEXT_HANDLE_MOVE, InputDevice.SOURCE_TOUCHSCREEN)).isNull();

        // Test with no customization available for `TEXT_HANDLE_MOVE`.
        provider = createProviderWithoutCustomizations();

        assertThat(provider.getVibration(TEXT_HANDLE_MOVE)).isNull();
        assertThat(provider.getVibration(TEXT_HANDLE_MOVE,
                InputDevice.SOURCE_ROTARY_ENCODER)).isNull();
        assertThat(
                provider.getVibration(TEXT_HANDLE_MOVE, InputDevice.SOURCE_TOUCHSCREEN)).isNull();
    }

    @Test
    public void testHapticTextEnabled_vibrationReturnedForTextHandleMove() {
        mockHapticTextSupport(true);
        mockVibratorPrimitiveSupport(PRIMITIVE_CLICK, PRIMITIVE_THUD, PRIMITIVE_TICK);
        SparseArray<VibrationEffect> customizations = new SparseArray<>();
        customizations.put(TEXT_HANDLE_MOVE, PRIMITIVE_CLICK_EFFECT);
        SparseArray<VibrationEffect> customizationsByRotary = new SparseArray<>();
        customizationsByRotary.put(TEXT_HANDLE_MOVE, PRIMITIVE_TICK_EFFECT);
        SparseArray<VibrationEffect> customizationsByTouchScreen = new SparseArray<>();
        customizationsByTouchScreen.put(TEXT_HANDLE_MOVE, PRIMITIVE_THUD_EFFECT);
        // Test with a customization available for `TEXT_HANDLE_MOVE`.
        HapticFeedbackVibrationProvider provider = createProvider(customizations,
                customizationsByRotary, customizationsByTouchScreen);

        assertThat(provider.getVibration(TEXT_HANDLE_MOVE)).isEqualTo(PRIMITIVE_CLICK_EFFECT);
        assertThat(provider.getVibration(TEXT_HANDLE_MOVE,
                InputDevice.SOURCE_ROTARY_ENCODER)).isEqualTo(PRIMITIVE_TICK_EFFECT);
        assertThat(provider.getVibration(TEXT_HANDLE_MOVE, InputDevice.SOURCE_TOUCHSCREEN))
                .isEqualTo(PRIMITIVE_THUD_EFFECT);

        // Test with no customization available for `TEXT_HANDLE_MOVE`.
        provider = createProviderWithoutCustomizations();

        assertThat(provider.getVibration(TEXT_HANDLE_MOVE)).isEqualTo(
                VibrationEffect.get(EFFECT_TEXTURE_TICK));
        assertThat(provider.getVibration(TEXT_HANDLE_MOVE,
                InputDevice.SOURCE_ROTARY_ENCODER)).isEqualTo(
                VibrationEffect.get(EFFECT_TEXTURE_TICK));
        assertThat(provider.getVibration(TEXT_HANDLE_MOVE, InputDevice.SOURCE_TOUCHSCREEN))
                .isEqualTo(VibrationEffect.get(EFFECT_TEXTURE_TICK));
    }

    @Test
    public void testFeedbackConstantNoHapticEffect_noVibrationRegardlessCustomizations() {
        mockVibratorPrimitiveSupport(PRIMITIVE_CLICK, PRIMITIVE_THUD, PRIMITIVE_TICK);
        SparseArray<VibrationEffect> customizations = new SparseArray<>();
        customizations.put(NO_HAPTICS, PRIMITIVE_CLICK_EFFECT);
        SparseArray<VibrationEffect> customizationsByRotary = new SparseArray<>();
        customizationsByRotary.put(NO_HAPTICS, PRIMITIVE_TICK_EFFECT);
        SparseArray<VibrationEffect> customizationsByTouchScreen = new SparseArray<>();
        customizationsByTouchScreen.put(NO_HAPTICS, PRIMITIVE_THUD_EFFECT);
        HapticFeedbackVibrationProvider provider = createProvider(customizations,
                customizationsByRotary, customizationsByTouchScreen);

        // Whatever customization set to NO_HAPTICS, no vibration happens.
        assertThat(provider.getVibration(NO_HAPTICS)).isNull();
        assertThat(provider.getVibration(NO_HAPTICS, InputDevice.SOURCE_ROTARY_ENCODER)).isNull();
        assertThat(provider.getVibration(NO_HAPTICS, InputDevice.SOURCE_TOUCHSCREEN)).isNull();
    }

    @Test
    public void testValidCustomizationPresentForSafeModeEnabled_usedRegardlessOfVibrationResource() {
        mockSafeModeEnabledVibration(10, 20, 30, 40);
        mockVibratorPrimitiveSupport(PRIMITIVE_CLICK, PRIMITIVE_TICK, PRIMITIVE_THUD);
        SparseArray<VibrationEffect> safeModeCustomizations = new SparseArray<>();
        safeModeCustomizations.put(SAFE_MODE_ENABLED, PRIMITIVE_CLICK_EFFECT);
        SparseArray<VibrationEffect> safeModeCustomizationsByRotary = new SparseArray<>();
        safeModeCustomizationsByRotary.put(SAFE_MODE_ENABLED, PRIMITIVE_THUD_EFFECT);
        SparseArray<VibrationEffect> safeModeCustomizationsByTouchScreen = new SparseArray<>();
        safeModeCustomizationsByTouchScreen.put(SAFE_MODE_ENABLED, PRIMITIVE_TICK_EFFECT);
        HapticFeedbackVibrationProvider provider =
                createProvider(safeModeCustomizations, safeModeCustomizationsByRotary,
                        safeModeCustomizationsByTouchScreen);

        assertThat(provider.getVibration(SAFE_MODE_ENABLED)).isEqualTo(PRIMITIVE_CLICK_EFFECT);
        assertThat(provider.getVibration(SAFE_MODE_ENABLED, InputDevice.SOURCE_ROTARY_ENCODER))
                .isEqualTo(PRIMITIVE_THUD_EFFECT);
        assertThat(provider.getVibration(SAFE_MODE_ENABLED, InputDevice.SOURCE_TOUCHSCREEN))
                .isEqualTo(PRIMITIVE_TICK_EFFECT);

        // Resource changed
        mockSafeModeEnabledVibration(null);
        provider =
                createProvider(safeModeCustomizations, safeModeCustomizationsByRotary,
                        safeModeCustomizationsByTouchScreen);

        assertThat(provider.getVibration(SAFE_MODE_ENABLED)).isEqualTo(PRIMITIVE_CLICK_EFFECT);
        assertThat(provider.getVibration(SAFE_MODE_ENABLED, InputDevice.SOURCE_ROTARY_ENCODER))
                .isEqualTo(PRIMITIVE_THUD_EFFECT);
        assertThat(provider.getVibration(SAFE_MODE_ENABLED, InputDevice.SOURCE_TOUCHSCREEN))
                .isEqualTo(PRIMITIVE_TICK_EFFECT);
    }

    @Test
    public void testNoValidCustomizationPresentForSafeModeEnabled_resourceBasedVibrationUsed() {
        mockSafeModeEnabledVibration(10, 20, 30, 40);
        HapticFeedbackVibrationProvider provider = createProviderWithoutCustomizations();

        assertThat(provider.getVibration(SAFE_MODE_ENABLED))
                .isEqualTo(VibrationEffect.createWaveform(new long[]{10, 20, 30, 40}, -1));
        assertThat(provider.getVibration(SAFE_MODE_ENABLED, InputDevice.SOURCE_ROTARY_ENCODER))
                .isEqualTo(VibrationEffect.createWaveform(new long[]{10, 20, 30, 40}, -1));
        assertThat(provider.getVibration(SAFE_MODE_ENABLED, InputDevice.SOURCE_TOUCHSCREEN))
                .isEqualTo(VibrationEffect.createWaveform(new long[]{10, 20, 30, 40}, -1));
    }

    @Test
    public void testNoValidCustomizationAndResourcePresentForSafeModeEnabled_noVibrationUsed() {
        mockSafeModeEnabledVibration(null);
        HapticFeedbackVibrationProvider provider = createProviderWithoutCustomizations();

        assertThat(provider.getVibration(SAFE_MODE_ENABLED)).isNull();
        assertThat(provider.getVibration(SAFE_MODE_ENABLED)).isNull();
        assertThat(provider.getVibration(SAFE_MODE_ENABLED, InputDevice.SOURCE_ROTARY_ENCODER))
                .isNull();
        assertThat(provider.getVibration(SAFE_MODE_ENABLED, InputDevice.SOURCE_TOUCHSCREEN))
                .isNull();
    }

    @Test
    public void testKeyboardHaptic_noFixedAmplitude_defaultVibrationReturned() {
        mockVibratorPrimitiveSupport(PRIMITIVE_CLICK, PRIMITIVE_TICK);
        SparseArray<VibrationEffect> customizations = new SparseArray<>();
        customizations.put(KEYBOARD_TAP, PRIMITIVE_CLICK_EFFECT);
        customizations.put(KEYBOARD_RELEASE, PRIMITIVE_TICK_EFFECT);

        // Test with a customization available for `KEYBOARD_TAP` & `KEYBOARD_RELEASE`.
        HapticFeedbackVibrationProvider provider = createProvider(customizations);

        assertThat(provider.getVibration(KEYBOARD_TAP)).isEqualTo(PRIMITIVE_CLICK_EFFECT);
        assertThat(provider.getVibration(KEYBOARD_RELEASE)).isEqualTo(PRIMITIVE_TICK_EFFECT);

        // Test with no customization available for `KEYBOARD_TAP` & `KEYBOARD_RELEASE`.
        provider = createProviderWithoutCustomizations();

        assertThat(provider.getVibration(KEYBOARD_TAP))
                .isEqualTo(VibrationEffect.get(EFFECT_CLICK, true /* fallback */));
        assertThat(provider.getVibration(KEYBOARD_RELEASE))
                .isEqualTo(VibrationEffect.get(EFFECT_TICK, false /* fallback */));
        assertThat(provider.getVibration(KEYBOARD_TAP, InputDevice.SOURCE_ROTARY_ENCODER))
                .isEqualTo(VibrationEffect.get(EFFECT_CLICK, true /* fallback */));
        assertThat(provider.getVibration(KEYBOARD_RELEASE, InputDevice.SOURCE_ROTARY_ENCODER))
                .isEqualTo(VibrationEffect.get(EFFECT_TICK, false /* fallback */));
        assertThat(provider.getVibration(KEYBOARD_TAP, InputDevice.SOURCE_TOUCHSCREEN))
                .isEqualTo(VibrationEffect.get(EFFECT_CLICK, true /* fallback */));
        assertThat(provider.getVibration(KEYBOARD_RELEASE, InputDevice.SOURCE_TOUCHSCREEN))
                .isEqualTo(VibrationEffect.get(EFFECT_TICK, false /* fallback */));
    }

    @Test
    public void testKeyboardHaptic_fixAmplitude_keyboardVibrationReturned() {
        mockVibratorPrimitiveSupport(PRIMITIVE_CLICK, PRIMITIVE_TICK);
        mockKeyboardVibrationFixedAmplitude(KEYBOARD_VIBRATION_FIXED_AMPLITUDE);
        HapticFeedbackVibrationProvider provider = createProviderWithoutCustomizations();

        assertThat(provider.getVibration(KEYBOARD_TAP)).isEqualTo(
                VibrationEffect.startComposition().addPrimitive(PRIMITIVE_CLICK,
                        KEYBOARD_VIBRATION_FIXED_AMPLITUDE).compose());
        assertThat(provider.getVibration(KEYBOARD_RELEASE)).isEqualTo(
                VibrationEffect.startComposition().addPrimitive(PRIMITIVE_TICK,
                        KEYBOARD_VIBRATION_FIXED_AMPLITUDE).compose());
        assertThat(provider.getVibration(KEYBOARD_TAP,
                InputDevice.SOURCE_ROTARY_ENCODER)).isEqualTo(
                VibrationEffect.startComposition().addPrimitive(PRIMITIVE_CLICK,
                        KEYBOARD_VIBRATION_FIXED_AMPLITUDE).compose());
        assertThat(provider.getVibration(KEYBOARD_RELEASE,
                InputDevice.SOURCE_ROTARY_ENCODER)).isEqualTo(
                VibrationEffect.startComposition().addPrimitive(PRIMITIVE_TICK,
                        KEYBOARD_VIBRATION_FIXED_AMPLITUDE).compose());
        assertThat(provider.getVibration(KEYBOARD_TAP,
                InputDevice.SOURCE_TOUCHSCREEN)).isEqualTo(
                VibrationEffect.startComposition().addPrimitive(PRIMITIVE_CLICK,
                        KEYBOARD_VIBRATION_FIXED_AMPLITUDE).compose());
        assertThat(provider.getVibration(KEYBOARD_RELEASE,
                InputDevice.SOURCE_TOUCHSCREEN)).isEqualTo(
                VibrationEffect.startComposition().addPrimitive(PRIMITIVE_TICK,
                        KEYBOARD_VIBRATION_FIXED_AMPLITUDE).compose());
    }

    @Test
    public void testKeyboardHaptic_withCustomizations_customEffectsUsed() {
        mockVibratorPrimitiveSupport(PRIMITIVE_CLICK, PRIMITIVE_TICK, PRIMITIVE_THUD,
                PRIMITIVE_QUICK_RISE);
        SparseArray<VibrationEffect> customizations = new SparseArray<>();
        customizations.put(KEYBOARD_TAP, PRIMITIVE_CLICK_EFFECT);
        customizations.put(KEYBOARD_RELEASE, PRIMITIVE_TICK_EFFECT);
        SparseArray<VibrationEffect> customizationsByRotary = new SparseArray<>();
        customizationsByRotary.put(KEYBOARD_TAP, PRIMITIVE_THUD_EFFECT);
        customizationsByRotary.put(KEYBOARD_RELEASE, PRIMITIVE_QUICK_RISE_EFFECT);
        SparseArray<VibrationEffect> customizationsByTouchScreen = new SparseArray<>();
        customizationsByTouchScreen.put(KEYBOARD_TAP, PRIMITIVE_QUICK_RISE_EFFECT);
        customizationsByTouchScreen.put(KEYBOARD_RELEASE, PRIMITIVE_THUD_EFFECT);
        HapticFeedbackVibrationProvider provider = createProvider(customizations,
                customizationsByRotary, customizationsByTouchScreen);

        assertThat(provider.getVibration(KEYBOARD_TAP, InputDevice.SOURCE_ROTARY_ENCODER))
                .isEqualTo(PRIMITIVE_THUD_EFFECT);
        assertThat(provider.getVibration(KEYBOARD_RELEASE, InputDevice.SOURCE_ROTARY_ENCODER))
                .isEqualTo(PRIMITIVE_QUICK_RISE_EFFECT);
        assertThat(provider.getVibration(KEYBOARD_TAP, InputDevice.SOURCE_TOUCHSCREEN))
                .isEqualTo(PRIMITIVE_QUICK_RISE_EFFECT);
        assertThat(provider.getVibration(KEYBOARD_RELEASE, InputDevice.SOURCE_TOUCHSCREEN))
                .isEqualTo(PRIMITIVE_THUD_EFFECT);
    }

    @Test
    public void testVibrationAttribute_biometricConstants_returnsCommunicationRequestUsage() {
        HapticFeedbackVibrationProvider provider = createProviderWithoutCustomizations();

        for (int effectId : BIOMETRIC_FEEDBACK_CONSTANTS) {
            VibrationAttributes attrs = provider.getVibrationAttributes(
                    effectId, /* flags */ 0, /* privFlags */ 0);
            assertThat(attrs.getUsage()).isEqualTo(VibrationAttributes.USAGE_COMMUNICATION_REQUEST);
        }
    }

    @Test
    public void testVibrationAttribute_forNotBypassingIntensitySettings() {
        HapticFeedbackVibrationProvider provider = createProviderWithoutCustomizations();

        VibrationAttributes attrs = provider.getVibrationAttributes(
                SAFE_MODE_ENABLED, /* flags */ 0, /* privFlags */ 0);

        assertThat(attrs.isFlagSet(FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF)).isFalse();
    }

    @Test
    public void testVibrationAttribute_forByassingIntensitySettings() {
        HapticFeedbackVibrationProvider provider = createProviderWithoutCustomizations();

        VibrationAttributes attrs = provider.getVibrationAttributes(
                SAFE_MODE_ENABLED,
                /* flags */ HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING, /* privFlags */ 0);

        assertThat(attrs.isFlagSet(FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF)).isTrue();
    }

    @Test
    public void testVibrationAttribute_scrollFeedback_scrollApiFlagOn_bypassInterruptPolicy() {
        mSetFlagsRule.enableFlags(android.view.flags.Flags.FLAG_SCROLL_FEEDBACK_API);
        HapticFeedbackVibrationProvider provider = createProviderWithoutCustomizations();

        for (int effectId : SCROLL_FEEDBACK_CONSTANTS) {
            VibrationAttributes attrs = provider.getVibrationAttributes(
                    effectId, /* flags */ 0, /* privFlags */ 0);
            assertWithMessage("Expected FLAG_BYPASS_INTERRUPTION_POLICY for effect " + effectId)
                   .that(attrs.isFlagSet(FLAG_BYPASS_INTERRUPTION_POLICY)).isTrue();
        }
    }

    @Test
    public void testVibrationAttribute_scrollFeedback_scrollApiFlagOff_noBypassInterruptPolicy() {
        mSetFlagsRule.disableFlags(android.view.flags.Flags.FLAG_SCROLL_FEEDBACK_API);
        HapticFeedbackVibrationProvider provider = createProviderWithoutCustomizations();

        for (int effectId : SCROLL_FEEDBACK_CONSTANTS) {
            VibrationAttributes attrs = provider.getVibrationAttributes(
                    effectId, /* flags */ 0, /* privFlags */ 0);
            assertWithMessage("Expected no FLAG_BYPASS_INTERRUPTION_POLICY for effect " + effectId)
                   .that(attrs.isFlagSet(FLAG_BYPASS_INTERRUPTION_POLICY)).isFalse();
        }
    }

    @Test
    public void testVibrationAttribute_scrollFeedback_inputCustomizedFlag_useTouchUsage() {
        mSetFlagsRule.enableFlags(FLAG_HAPTIC_FEEDBACK_INPUT_SOURCE_CUSTOMIZATION_ENABLED);
        HapticFeedbackVibrationProvider provider = createProviderWithoutCustomizations();

        for (int effectId : SCROLL_FEEDBACK_CONSTANTS) {
            VibrationAttributes attrs = provider.getVibrationAttributes(effectId, /* flags */
                    0, /* privFlags */ 0);
            assertWithMessage("Expected USAGE_TOUCH for scroll effect " + effectId
                    + ", if no input customization").that(attrs.getUsage()).isEqualTo(USAGE_TOUCH);
        }
    }

    @Test
    public void testVibrationAttribute_scrollFeedback_noInputCustomizedFlag_useHardwareFeedback() {
        HapticFeedbackVibrationProvider provider = createProviderWithoutCustomizations();

        for (int effectId : SCROLL_FEEDBACK_CONSTANTS) {
            VibrationAttributes attrs = provider.getVibrationAttributes(effectId, /* flags */
                    0, /* privFlags */ 0);
            assertWithMessage("Expected USAGE_HARDWARE_FEEDBACK for scroll effect " + effectId
                    + ", if no input customization").that(attrs.getUsage()).isEqualTo(
                    USAGE_HARDWARE_FEEDBACK);
        }
    }

    @Test
    public void testVibrationAttribute_scrollFeedback_rotaryInputSource_useHardwareFeedback() {
        mSetFlagsRule.enableFlags(FLAG_HAPTIC_FEEDBACK_INPUT_SOURCE_CUSTOMIZATION_ENABLED);
        HapticFeedbackVibrationProvider provider = createProviderWithoutCustomizations();

        for (int effectId : SCROLL_FEEDBACK_CONSTANTS) {
            VibrationAttributes attrs = provider.getVibrationAttributes(
                    effectId, InputDevice.SOURCE_ROTARY_ENCODER, /* flags */ 0, /* privFlags */ 0);
            assertWithMessage(
                    "Expected USAGE_HARDWARE_FEEDBACK for input source SOURCE_ROTARY_ENCODER").that(
                    attrs.getUsage()).isEqualTo(USAGE_HARDWARE_FEEDBACK);
        }
    }

    @Test
    public void testVibrationAttribute_scrollFeedback_touchInputSource_useTouchUsage() {
        mSetFlagsRule.enableFlags(FLAG_HAPTIC_FEEDBACK_INPUT_SOURCE_CUSTOMIZATION_ENABLED);
        HapticFeedbackVibrationProvider provider = createProviderWithoutCustomizations();

        for (int effectId : SCROLL_FEEDBACK_CONSTANTS) {
            VibrationAttributes attrs = provider.getVibrationAttributes(
                    effectId, InputDevice.SOURCE_TOUCHSCREEN, /* flags */ 0, /* privFlags */ 0);
            assertWithMessage("Expected USAGE_TOUCH for input source SOURCE_TOUCHSCREEN").that(
                    attrs.getUsage()).isEqualTo(USAGE_TOUCH);
        }
    }

    @Test
    public void testVibrationAttribute_notIme_useTouchUsage() {
        HapticFeedbackVibrationProvider provider = createProviderWithoutCustomizations();

        for (int effectId : KEYBOARD_FEEDBACK_CONSTANTS) {
            VibrationAttributes attrs = provider.getVibrationAttributes(
                    effectId, /* flags */ 0, /* privFlags */ 0);
            assertWithMessage("Expected USAGE_TOUCH for effect " + effectId)
                    .that(attrs.getUsage()).isEqualTo(USAGE_TOUCH);
        }
    }

    @Test
    public void testVibrationAttribute_isIme_useImeFeedbackUsage() {
        HapticFeedbackVibrationProvider provider = createProviderWithoutCustomizations();

        for (int effectId : KEYBOARD_FEEDBACK_CONSTANTS) {
            VibrationAttributes attrs = provider.getVibrationAttributes(
                    effectId, /* flags */ 0,
                    HapticFeedbackConstants.PRIVATE_FLAG_APPLY_INPUT_METHOD_SETTINGS);
            assertWithMessage("Expected USAGE_IME_FEEDBACK for effect " + effectId)
                    .that(attrs.getUsage()).isEqualTo(USAGE_IME_FEEDBACK);
        }
    }

    @Test
    public void testIsRestricted_biometricConstants_returnsTrue() {
        HapticFeedbackVibrationProvider provider = createProviderWithoutCustomizations();

        for (int effectId : BIOMETRIC_FEEDBACK_CONSTANTS) {
            assertThat(provider.isRestrictedHapticFeedback(effectId)).isTrue();
        }
    }

    private HapticFeedbackVibrationProvider createProviderWithoutCustomizations() {
        return createProvider(/* customizations= */ new SparseArray<>(),
                /* customizationsRotary= */ new SparseArray<>(),
                /* customizationsTouchScreen */ new SparseArray<>());
    }

    private HapticFeedbackVibrationProvider createProvider(
            SparseArray<VibrationEffect> customizations) {
        return createProvider(customizations, /* customizationsRotary= */ new SparseArray<>(),
                /* customizationsTouchScreen */ new SparseArray<>());
    }

    private HapticFeedbackVibrationProvider createProvider(
            @NonNull SparseArray<VibrationEffect> customizations,
            @NonNull SparseArray<VibrationEffect> customizationsRotary,
            @NonNull SparseArray<VibrationEffect> customizationsTouchScreen) {
        return new HapticFeedbackVibrationProvider(mResourcesMock, mVibratorInfo,
                new HapticFeedbackCustomization(
                        customizations,
                        customizationsRotary,
                        customizationsTouchScreen));
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
