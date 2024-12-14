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

import static android.os.vibrator.Flags.hapticFeedbackInputSourceCustomizationEnabled;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Resources;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.VibratorInfo;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;

/**
 * Provides the {@link VibrationEffect} and {@link VibrationAttributes} for haptic feedback.
 */
public final class HapticFeedbackVibrationProvider {
    private static final String TAG = "HapticFeedbackVibrationProvider";

    private static final VibrationAttributes TOUCH_VIBRATION_ATTRIBUTES =
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH);
    private static final VibrationAttributes PHYSICAL_EMULATION_VIBRATION_ATTRIBUTES =
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_PHYSICAL_EMULATION);
    private static final VibrationAttributes HARDWARE_FEEDBACK_VIBRATION_ATTRIBUTES =
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK);
    private static final VibrationAttributes COMMUNICATION_REQUEST_VIBRATION_ATTRIBUTES =
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_COMMUNICATION_REQUEST);
    private static final VibrationAttributes IME_FEEDBACK_VIBRATION_ATTRIBUTES =
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_IME_FEEDBACK);

    private final VibratorInfo mVibratorInfo;
    private final boolean mHapticTextHandleEnabled;
    // Vibrator effect for haptic feedback during boot when safe mode is enabled.
    private final VibrationEffect mSafeModeEnabledVibrationEffect;

    private final HapticFeedbackCustomization mHapticFeedbackCustomization;

    private float mKeyboardVibrationFixedAmplitude;

    public HapticFeedbackVibrationProvider(Resources res, VibratorInfo vibratorInfo) {
        this(res, vibratorInfo, new HapticFeedbackCustomization(res, vibratorInfo));
    }

    @VisibleForTesting
    HapticFeedbackVibrationProvider(Resources res, VibratorInfo vibratorInfo,
            HapticFeedbackCustomization hapticFeedbackCustomization) {
        mVibratorInfo = vibratorInfo;
        mHapticTextHandleEnabled = res.getBoolean(
                com.android.internal.R.bool.config_enableHapticTextHandle);
        mHapticFeedbackCustomization = hapticFeedbackCustomization;

        VibrationEffect safeModeVibration = mHapticFeedbackCustomization.getEffect(
                HapticFeedbackConstants.SAFE_MODE_ENABLED);
        mSafeModeEnabledVibrationEffect = safeModeVibration != null ? safeModeVibration
                : VibrationSettings.createEffectFromResource(res,
                        com.android.internal.R.array.config_safeModeEnabledVibePattern);

        mKeyboardVibrationFixedAmplitude = res.getFloat(
                com.android.internal.R.dimen.config_keyboardHapticFeedbackFixedAmplitude);
        if (mKeyboardVibrationFixedAmplitude < 0 || mKeyboardVibrationFixedAmplitude > 1) {
            mKeyboardVibrationFixedAmplitude = -1;
        }
    }

    /**
     * Provides the {@link VibrationEffect} for a given haptic feedback effect ID (provided in
     * {@link HapticFeedbackConstants}).
     *
     * @param effectId the haptic feedback effect ID whose respective vibration we want to get.
     * @return a {@link VibrationEffect} for the given haptic feedback effect ID, or {@code null} if
     *          the provided effect ID is not supported.
     */
    @Nullable public VibrationEffect getVibration(int effectId) {
        if (!isFeedbackConstantEnabled(effectId)) {
            return null;
        }
        VibrationEffect customizedVibration = mHapticFeedbackCustomization.getEffect(effectId);
        if (customizedVibration != null) {
            return customizedVibration;
        }
        return getVibrationForHapticFeedback(effectId);
    }

    /**
     * Provides the {@link VibrationEffect} for a given haptic feedback effect ID (provided in
     * {@link HapticFeedbackConstants}).
     *
     * @param effectId    the haptic feedback effect ID whose respective vibration we want to get.
     * @param inputSource the {@link InputDevice.Source} that customizes the haptic feedback
     *                    corresponding to the {@code effectId}.
     * @return a {@link VibrationEffect} for the given haptic feedback effect ID, or {@code null} if
     * the provided effect ID is not supported.
     */
    @Nullable public VibrationEffect getVibration(int effectId, int inputSource) {
        if (!isFeedbackConstantEnabled(effectId)) {
            return null;
        }
        VibrationEffect customizedVibration = mHapticFeedbackCustomization.getEffect(effectId,
                inputSource);
        if (customizedVibration != null) {
            return customizedVibration;
        }
        return getVibrationForHapticFeedback(effectId);
    }

    /**
     * Provides the {@link VibrationAttributes} that should be used for a haptic feedback.
     *
     * @param effectId the haptic feedback effect ID whose respective vibration attributes we want
     *      to get.
     * @param flags Additional flags as per {@link HapticFeedbackConstants}.
     * @param privFlags Additional private flags as per {@link HapticFeedbackConstants}.
     * @return the {@link VibrationAttributes} that should be used for the provided haptic feedback.
     */
    public VibrationAttributes getVibrationAttributes(int effectId,
            @HapticFeedbackConstants.Flags int flags,
            @HapticFeedbackConstants.PrivateFlags int privFlags) {
        VibrationAttributes attrs;
        switch (effectId) {
            case HapticFeedbackConstants.EDGE_SQUEEZE:
            case HapticFeedbackConstants.EDGE_RELEASE:
                attrs = PHYSICAL_EMULATION_VIBRATION_ATTRIBUTES;
                break;
            case HapticFeedbackConstants.ASSISTANT_BUTTON:
            case HapticFeedbackConstants.LONG_PRESS_POWER_BUTTON:
                attrs = HARDWARE_FEEDBACK_VIBRATION_ATTRIBUTES;
                break;
            case HapticFeedbackConstants.SCROLL_TICK:
            case HapticFeedbackConstants.SCROLL_ITEM_FOCUS:
            case HapticFeedbackConstants.SCROLL_LIMIT:
                attrs = hapticFeedbackInputSourceCustomizationEnabled() ? TOUCH_VIBRATION_ATTRIBUTES
                        : HARDWARE_FEEDBACK_VIBRATION_ATTRIBUTES;
                break;
            case HapticFeedbackConstants.KEYBOARD_TAP:
            case HapticFeedbackConstants.KEYBOARD_RELEASE:
                attrs = createKeyboardVibrationAttributes(privFlags);
                break;
            case HapticFeedbackConstants.BIOMETRIC_CONFIRM:
            case HapticFeedbackConstants.BIOMETRIC_REJECT:
                attrs = COMMUNICATION_REQUEST_VIBRATION_ATTRIBUTES;
                break;
            default:
                attrs = TOUCH_VIBRATION_ATTRIBUTES;
        }
        return getVibrationAttributesWithFlags(attrs, effectId, flags);
    }

    /**
     * Similar to {@link #getVibrationAttributes(int, int, int)} but also handles
     * input source customization.
     *
     * @param inputSource the {@link InputDevice.Source} that customizes the
     *                    {@link VibrationAttributes}.
     */
    public VibrationAttributes getVibrationAttributes(int effectId,
            int inputSource,
            @HapticFeedbackConstants.Flags int flags,
            @HapticFeedbackConstants.PrivateFlags int privFlags) {
        if (hapticFeedbackInputSourceCustomizationEnabled()
                && inputSource == InputDevice.SOURCE_ROTARY_ENCODER) {
            switch (effectId) {
                case HapticFeedbackConstants.SCROLL_TICK,
                        HapticFeedbackConstants.SCROLL_ITEM_FOCUS,
                        HapticFeedbackConstants.SCROLL_LIMIT -> {
                    return getVibrationAttributesWithFlags(HARDWARE_FEEDBACK_VIBRATION_ATTRIBUTES,
                            effectId, flags);
                }
            }
        }
        return getVibrationAttributes(effectId, flags, privFlags);
    }

    /**
     * Returns true if given haptic feedback is restricted to system apps with permission
     * {@code android.permission.VIBRATE_SYSTEM_CONSTANTS}.
     *
     * @param effectId the haptic feedback effect ID to check.
     * @return true if the haptic feedback is restricted, false otherwise.
     */
    public boolean isRestrictedHapticFeedback(int effectId) {
        switch (effectId) {
            case HapticFeedbackConstants.BIOMETRIC_CONFIRM:
            case HapticFeedbackConstants.BIOMETRIC_REJECT:
                return true;
            default:
                return false;
        }
    }

    /** Dumps relevant state. */
    public void dump(String prefix, PrintWriter pw) {
        pw.print("mHapticTextHandleEnabled="); pw.println(mHapticTextHandleEnabled);
    }

    private boolean isFeedbackConstantEnabled(int effectId) {
        return switch (effectId) {
            case HapticFeedbackConstants.TEXT_HANDLE_MOVE -> mHapticTextHandleEnabled;
            case HapticFeedbackConstants.NO_HAPTICS -> false;
            default -> true;
        };
    }

    /**
     * Get {@link VibrationEffect} respective {@code effectId} from platform-wise mapping. This
     * method doesn't include OEM customizations.
     */
    @Nullable
    private VibrationEffect getVibrationForHapticFeedback(int effectId) {
        switch (effectId) {
            case HapticFeedbackConstants.CONTEXT_CLICK:
            case HapticFeedbackConstants.GESTURE_END:
            case HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE:
            case HapticFeedbackConstants.SCROLL_TICK:
            case HapticFeedbackConstants.SEGMENT_TICK:
                return VibrationEffect.get(VibrationEffect.EFFECT_TICK);

            case HapticFeedbackConstants.TEXT_HANDLE_MOVE:
            case HapticFeedbackConstants.CLOCK_TICK:
            case HapticFeedbackConstants.SEGMENT_FREQUENT_TICK:
                return VibrationEffect.get(VibrationEffect.EFFECT_TEXTURE_TICK);

            case HapticFeedbackConstants.KEYBOARD_RELEASE:
            case HapticFeedbackConstants.KEYBOARD_TAP: // == KEYBOARD_PRESS
                // keyboard effect is not customized by the input source.
                return getKeyboardVibration(effectId);

            case HapticFeedbackConstants.VIRTUAL_KEY_RELEASE:
            case HapticFeedbackConstants.DRAG_CROSSING:
                return VibrationEffect.get(VibrationEffect.EFFECT_TICK, /* fallback= */ false);

            case HapticFeedbackConstants.VIRTUAL_KEY:
            case HapticFeedbackConstants.EDGE_RELEASE:
            case HapticFeedbackConstants.CALENDAR_DATE:
            case HapticFeedbackConstants.CONFIRM:
            case HapticFeedbackConstants.BIOMETRIC_CONFIRM:
            case HapticFeedbackConstants.GESTURE_START:
            case HapticFeedbackConstants.SCROLL_ITEM_FOCUS:
            case HapticFeedbackConstants.SCROLL_LIMIT:
                return VibrationEffect.get(VibrationEffect.EFFECT_CLICK);

            case HapticFeedbackConstants.LONG_PRESS:
            case HapticFeedbackConstants.LONG_PRESS_POWER_BUTTON:
            case HapticFeedbackConstants.DRAG_START:
            case HapticFeedbackConstants.EDGE_SQUEEZE:
                return VibrationEffect.get(VibrationEffect.EFFECT_HEAVY_CLICK);

            case HapticFeedbackConstants.REJECT:
            case HapticFeedbackConstants.BIOMETRIC_REJECT:
                return VibrationEffect.get(VibrationEffect.EFFECT_DOUBLE_CLICK);

            case HapticFeedbackConstants.SAFE_MODE_ENABLED:
                // safe mode effect is not customized by the input source.
                return mSafeModeEnabledVibrationEffect;

            case HapticFeedbackConstants.ASSISTANT_BUTTON:
                // assistant effect is not customized by the input source.
                return getAssistantButtonVibration();

            case HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE:
                return getVibration(
                        VibrationEffect.Composition.PRIMITIVE_TICK,
                        /* primitiveScale= */ 0.4f,
                        VibrationEffect.EFFECT_TEXTURE_TICK);

            case HapticFeedbackConstants.TOGGLE_ON:
                return getVibration(
                        VibrationEffect.Composition.PRIMITIVE_TICK,/* primitiveScale= */ 0.5f,
                        VibrationEffect.EFFECT_TICK);

            case HapticFeedbackConstants.TOGGLE_OFF:
                return getVibration(
                        VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                        /* primitiveScale= */ 0.2f,
                        VibrationEffect.EFFECT_TEXTURE_TICK);

            case HapticFeedbackConstants.NO_HAPTICS:
            default:
                return null;
        }
    }

    @NonNull
    private VibrationEffect getVibration(int primitiveId, float primitiveScale,
            int predefinedVibrationEffectId) {
        if (mVibratorInfo.isPrimitiveSupported(primitiveId)) {
            return VibrationEffect.startComposition()
                    .addPrimitive(primitiveId, primitiveScale)
                    .compose();
        }
        return VibrationEffect.get(predefinedVibrationEffectId);
    }

    @NonNull
    private VibrationEffect getAssistantButtonVibration() {
        if (mVibratorInfo.isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE)
                && mVibratorInfo.isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_TICK)) {
            // quiet ramp, short pause, then sharp tick
            return VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.25f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 1f, 50)
                    .compose();
        }
        // fallback for devices without composition support
        return VibrationEffect.get(VibrationEffect.EFFECT_HEAVY_CLICK);
    }

    @NonNull
    private VibrationEffect getKeyboardVibration(int effectId) {
        int primitiveId;
        int predefinedEffectId;
        boolean predefinedEffectFallback;

        switch (effectId) {
            case HapticFeedbackConstants.KEYBOARD_RELEASE:
                primitiveId = VibrationEffect.Composition.PRIMITIVE_TICK;
                predefinedEffectId = VibrationEffect.EFFECT_TICK;
                predefinedEffectFallback = false;
                break;
            case HapticFeedbackConstants.KEYBOARD_TAP:
            default:
                primitiveId = VibrationEffect.Composition.PRIMITIVE_CLICK;
                predefinedEffectId = VibrationEffect.EFFECT_CLICK;
                predefinedEffectFallback = true;
        }
        if (mKeyboardVibrationFixedAmplitude > 0) {
            if (mVibratorInfo.isPrimitiveSupported(primitiveId)) {
                return VibrationEffect.startComposition()
                        .addPrimitive(primitiveId, mKeyboardVibrationFixedAmplitude)
                        .compose();
            }
        }
        return VibrationEffect.get(predefinedEffectId, predefinedEffectFallback);
    }

    private VibrationAttributes createKeyboardVibrationAttributes(
            @HapticFeedbackConstants.PrivateFlags int privFlags) {
        // Use touch attribute when the haptic is not apply to IME.
        if ((privFlags & HapticFeedbackConstants.PRIVATE_FLAG_APPLY_INPUT_METHOD_SETTINGS) == 0) {
            return TOUCH_VIBRATION_ATTRIBUTES;
        }
        return IME_FEEDBACK_VIBRATION_ATTRIBUTES;
    }

    private VibrationAttributes getVibrationAttributesWithFlags(VibrationAttributes attrs,
            int effectId, int flags) {
        int vibFlags = 0;
        if ((flags & HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING) != 0) {
            vibFlags |= VibrationAttributes.FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF;
        }
        if (shouldBypassInterruptionPolicy(effectId)) {
            vibFlags |= VibrationAttributes.FLAG_BYPASS_INTERRUPTION_POLICY;
        }

        return vibFlags == 0 ? attrs : new VibrationAttributes.Builder(attrs)
                .setFlags(vibFlags).build();
    }

    private static boolean shouldBypassInterruptionPolicy(int effectId) {
        switch (effectId) {
            case HapticFeedbackConstants.SCROLL_TICK:
            case HapticFeedbackConstants.SCROLL_ITEM_FOCUS:
            case HapticFeedbackConstants.SCROLL_LIMIT:
                // The SCROLL_* constants should bypass interruption filter, so that scroll haptics
                // can play regardless of focus modes like DND. Guard this behavior by the feature
                // flag controlling the general scroll feedback APIs.
                return android.view.flags.Flags.scrollFeedbackApi();
            default:
                return false;
        }
    }
}
