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
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorInfo;
import android.os.vibrator.Flags;
import android.util.Slog;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;

import com.android.internal.annotations.VisibleForTesting;

import java.io.IOException;
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

    private final VibratorInfo mVibratorInfo;
    private final boolean mHapticTextHandleEnabled;
    // Vibrator effect for haptic feedback during boot when safe mode is enabled.
    private final VibrationEffect mSafeModeEnabledVibrationEffect;
    // Haptic feedback vibration customizations specific to the device.
    // If present and valid, a vibration here will be used for an effect.
    // Otherwise, the system's default vibration will be used.
    @Nullable private final SparseArray<VibrationEffect> mHapticCustomizations;

    private float mKeyboardVibrationFixedAmplitude;

    public HapticFeedbackVibrationProvider(Resources res, Vibrator vibrator) {
        this(res, vibrator.getInfo());
    }

    public HapticFeedbackVibrationProvider(Resources res, VibratorInfo vibratorInfo) {
        this(res, vibratorInfo, loadHapticCustomizations(res, vibratorInfo));
    }

    @VisibleForTesting HapticFeedbackVibrationProvider(
            Resources res,
            VibratorInfo vibratorInfo,
            @Nullable SparseArray<VibrationEffect> hapticCustomizations) {
        mVibratorInfo = vibratorInfo;
        mHapticTextHandleEnabled = res.getBoolean(
                com.android.internal.R.bool.config_enableHapticTextHandle);

        if (hapticCustomizations != null && hapticCustomizations.size() == 0) {
            hapticCustomizations = null;
        }
        mHapticCustomizations = hapticCustomizations;
        mSafeModeEnabledVibrationEffect =
                effectHasCustomization(HapticFeedbackConstants.SAFE_MODE_ENABLED)
                        ? mHapticCustomizations.get(HapticFeedbackConstants.SAFE_MODE_ENABLED)
                        : VibrationSettings.createEffectFromResource(
                                res,
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
    @Nullable public VibrationEffect getVibrationForHapticFeedback(int effectId) {
        switch (effectId) {
            case HapticFeedbackConstants.CONTEXT_CLICK:
            case HapticFeedbackConstants.GESTURE_END:
            case HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE:
            case HapticFeedbackConstants.SCROLL_TICK:
            case HapticFeedbackConstants.SEGMENT_TICK:
                return getVibration(effectId, VibrationEffect.EFFECT_TICK);

            case HapticFeedbackConstants.TEXT_HANDLE_MOVE:
                if (!mHapticTextHandleEnabled) {
                    return null;
                }
                // fallthrough
            case HapticFeedbackConstants.CLOCK_TICK:
            case HapticFeedbackConstants.SEGMENT_FREQUENT_TICK:
                return getVibration(effectId, VibrationEffect.EFFECT_TEXTURE_TICK);

            case HapticFeedbackConstants.KEYBOARD_RELEASE:
            case HapticFeedbackConstants.KEYBOARD_TAP: // == KEYBOARD_PRESS
                return getKeyboardVibration(effectId);

            case HapticFeedbackConstants.VIRTUAL_KEY_RELEASE:
            case HapticFeedbackConstants.ENTRY_BUMP:
            case HapticFeedbackConstants.DRAG_CROSSING:
                return getVibration(
                        effectId,
                        VibrationEffect.EFFECT_TICK,
                        /* fallbackForPredefinedEffect= */ false);

            case HapticFeedbackConstants.VIRTUAL_KEY:
            case HapticFeedbackConstants.EDGE_RELEASE:
            case HapticFeedbackConstants.CALENDAR_DATE:
            case HapticFeedbackConstants.CONFIRM:
            case HapticFeedbackConstants.GESTURE_START:
            case HapticFeedbackConstants.SCROLL_ITEM_FOCUS:
            case HapticFeedbackConstants.SCROLL_LIMIT:
                return getVibration(effectId, VibrationEffect.EFFECT_CLICK);

            case HapticFeedbackConstants.LONG_PRESS:
            case HapticFeedbackConstants.LONG_PRESS_POWER_BUTTON:
            case HapticFeedbackConstants.DRAG_START:
            case HapticFeedbackConstants.EDGE_SQUEEZE:
                return getVibration(effectId, VibrationEffect.EFFECT_HEAVY_CLICK);

            case HapticFeedbackConstants.REJECT:
                return getVibration(effectId, VibrationEffect.EFFECT_DOUBLE_CLICK);

            case HapticFeedbackConstants.SAFE_MODE_ENABLED:
                return mSafeModeEnabledVibrationEffect;

            case HapticFeedbackConstants.ASSISTANT_BUTTON:
                return getAssistantButtonVibration();

            case HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE:
                return getVibration(
                        effectId,
                        VibrationEffect.Composition.PRIMITIVE_TICK,
                        /* primitiveScale= */ 0.4f,
                        VibrationEffect.EFFECT_TEXTURE_TICK);

            case HapticFeedbackConstants.TOGGLE_ON:
                return getVibration(
                        effectId,
                        VibrationEffect.Composition.PRIMITIVE_TICK,
                        /* primitiveScale= */ 0.5f,
                        VibrationEffect.EFFECT_TICK);

            case HapticFeedbackConstants.TOGGLE_OFF:
                return getVibration(
                        effectId,
                        VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                        /* primitiveScale= */ 0.2f,
                        VibrationEffect.EFFECT_TEXTURE_TICK);

            case HapticFeedbackConstants.NO_HAPTICS:
            default:
                return null;
        }
    }

    /**
     * Provides the {@link VibrationAttributes} that should be used for a haptic feedback.
     *
     * @param effectId the haptic feedback effect ID whose respective vibration attributes we want
     *      to get.
     * @param bypassVibrationIntensitySetting {@code true} if the returned attribute should bypass
     *      vibration intensity settings. {@code false} otherwise.
     * @param fromIme the haptic feedback is performed from an IME.
     * @return the {@link VibrationAttributes} that should be used for the provided haptic feedback.
     */
    public VibrationAttributes getVibrationAttributesForHapticFeedback(
            int effectId, boolean bypassVibrationIntensitySetting, boolean fromIme) {
        VibrationAttributes attrs;
        switch (effectId) {
            case HapticFeedbackConstants.EDGE_SQUEEZE:
            case HapticFeedbackConstants.EDGE_RELEASE:
                attrs = PHYSICAL_EMULATION_VIBRATION_ATTRIBUTES;
                break;
            case HapticFeedbackConstants.ASSISTANT_BUTTON:
            case HapticFeedbackConstants.LONG_PRESS_POWER_BUTTON:
            case HapticFeedbackConstants.SCROLL_TICK:
            case HapticFeedbackConstants.SCROLL_ITEM_FOCUS:
            case HapticFeedbackConstants.SCROLL_LIMIT:
                attrs = HARDWARE_FEEDBACK_VIBRATION_ATTRIBUTES;
                break;
            case HapticFeedbackConstants.KEYBOARD_TAP:
            case HapticFeedbackConstants.KEYBOARD_RELEASE:
                attrs = createKeyboardVibrationAttributes(fromIme);
                break;
            default:
                attrs = TOUCH_VIBRATION_ATTRIBUTES;
        }

        int flags = 0;
        if (bypassVibrationIntensitySetting) {
            flags |= VibrationAttributes.FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF;
        }
        if (shouldBypassInterruptionPolicy(effectId)) {
            flags |= VibrationAttributes.FLAG_BYPASS_INTERRUPTION_POLICY;
        }
        if (shouldBypassIntensityScale(effectId, fromIme)) {
            flags |= VibrationAttributes.FLAG_BYPASS_USER_VIBRATION_INTENSITY_SCALE;
        }

        return flags == 0 ? attrs : new VibrationAttributes.Builder(attrs).setFlags(flags).build();
    }

    /** Dumps relevant state. */
    public void dump(String prefix, PrintWriter pw) {
        pw.print("mHapticTextHandleEnabled="); pw.println(mHapticTextHandleEnabled);
    }

    private VibrationEffect getVibration(int effectId, int predefinedVibrationEffectId) {
        return getVibration(
                effectId, predefinedVibrationEffectId, /* fallbackForPredefinedEffect= */ true);
    }

    /**
     * Returns the customized vibration for {@code hapticFeedbackId}, or
     * {@code predefinedVibrationEffectId} if a customization does not exist for the haptic
     * feedback.
     *
     * <p>If a customization does not exist and the default predefined effect is to be returned,
     * {@code fallbackForPredefinedEffect} will be used to decide whether or not to fallback
     * to a generic pattern if the predefined effect is not hardware supported.
     *
     * @see VibrationEffect#get(int, boolean)
     */
    private VibrationEffect getVibration(
            int hapticFeedbackId,
            int predefinedVibrationEffectId,
            boolean fallbackForPredefinedEffect) {
        if (effectHasCustomization(hapticFeedbackId)) {
            return mHapticCustomizations.get(hapticFeedbackId);
        }
        return VibrationEffect.get(predefinedVibrationEffectId, fallbackForPredefinedEffect);
    }

    /**
     * Returns the customized vibration for {@code hapticFeedbackId}, or some fallback vibration if
     * a customization does not exist for the ID.
     *
     * <p>The fallback will be a primitive composition formed of {@code primitiveId} and
     * {@code primitiveScale}, if the primitive is supported. Otherwise, it will be a predefined
     * vibration of {@code elsePredefinedVibrationEffectId}.
     */
    private VibrationEffect getVibration(
            int hapticFeedbackId,
            int primitiveId,
            float primitiveScale,
            int elsePredefinedVibrationEffectId) {
        if (effectHasCustomization(hapticFeedbackId)) {
            return mHapticCustomizations.get(hapticFeedbackId);
        }
        if (mVibratorInfo.isPrimitiveSupported(primitiveId)) {
            return VibrationEffect.startComposition()
                    .addPrimitive(primitiveId, primitiveScale)
                    .compose();
        } else {
            return VibrationEffect.get(elsePredefinedVibrationEffectId);
        }
    }

    private VibrationEffect getAssistantButtonVibration() {
        if (effectHasCustomization(HapticFeedbackConstants.ASSISTANT_BUTTON)) {
            return mHapticCustomizations.get(HapticFeedbackConstants.ASSISTANT_BUTTON);
        }
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

    private boolean effectHasCustomization(int effectId) {
        return mHapticCustomizations != null && mHapticCustomizations.contains(effectId);
    }

    private VibrationEffect getKeyboardVibration(int effectId) {
        if (effectHasCustomization(effectId)) {
            return mHapticCustomizations.get(effectId);
        }

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
        if (Flags.keyboardCategoryEnabled() && mKeyboardVibrationFixedAmplitude > 0) {
            if (mVibratorInfo.isPrimitiveSupported(primitiveId)) {
                return VibrationEffect.startComposition()
                        .addPrimitive(primitiveId, mKeyboardVibrationFixedAmplitude)
                        .compose();
            }
        }
        return getVibration(effectId, predefinedEffectId,
                /* fallbackForPredefinedEffect= */ predefinedEffectFallback);
    }

    private boolean shouldBypassIntensityScale(int effectId, boolean isIme) {
        if (!Flags.keyboardCategoryEnabled() || mKeyboardVibrationFixedAmplitude < 0 || !isIme) {
            // Shouldn't bypass if not support keyboard category, no fixed amplitude or not an IME.
            return false;
        }
        switch (effectId) {
            case HapticFeedbackConstants.KEYBOARD_TAP:
                return mVibratorInfo.isPrimitiveSupported(
                        VibrationEffect.Composition.PRIMITIVE_CLICK);
            case HapticFeedbackConstants.KEYBOARD_RELEASE:
                return mVibratorInfo.isPrimitiveSupported(
                        VibrationEffect.Composition.PRIMITIVE_TICK);
        }
        return false;
    }

    private VibrationAttributes createKeyboardVibrationAttributes(boolean fromIme) {
        // Use touch attribute when the keyboard category is disable or it's not from an IME.
        if (!Flags.keyboardCategoryEnabled() || !fromIme) {
            return TOUCH_VIBRATION_ATTRIBUTES;
        }

        return new VibrationAttributes.Builder(TOUCH_VIBRATION_ATTRIBUTES)
                .setCategory(VibrationAttributes.CATEGORY_KEYBOARD)
                .build();
    }

    @Nullable
    private static SparseArray<VibrationEffect> loadHapticCustomizations(
            Resources res, VibratorInfo vibratorInfo) {
        try {
            return HapticFeedbackCustomization.loadVibrations(res, vibratorInfo);
        } catch (IOException | HapticFeedbackCustomization.CustomizationParserException e) {
            Slog.e(TAG, "Unable to load haptic customizations.", e);
            return null;
        }
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
