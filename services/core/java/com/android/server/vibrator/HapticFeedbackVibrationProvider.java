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
import android.view.HapticFeedbackConstants;

import java.io.PrintWriter;

/**
 * Provides the {@link VibrationEffect} and {@link VibrationAttributes} for haptic feedback.
 *
 * @hide
 */
public final class HapticFeedbackVibrationProvider {
    private static final VibrationAttributes TOUCH_VIBRATION_ATTRIBUTES =
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH);
    private static final VibrationAttributes PHYSICAL_EMULATION_VIBRATION_ATTRIBUTES =
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_PHYSICAL_EMULATION);
    private static final VibrationAttributes HARDWARE_FEEDBACK_VIBRATION_ATTRIBUTES =
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK);

    private final Vibrator mVibrator;
    private final boolean mHapticTextHandleEnabled;
    // Vibrator effect for haptic feedback during boot when safe mode is enabled.
    private final VibrationEffect mSafeModeEnabledVibrationEffect;

    /** @hide */
    public HapticFeedbackVibrationProvider(Resources res, Vibrator vibrator) {
        mVibrator = vibrator;
        mHapticTextHandleEnabled = res.getBoolean(
                com.android.internal.R.bool.config_enableHapticTextHandle);
        mSafeModeEnabledVibrationEffect =
                VibrationSettings.createEffectFromResource(
                        res, com.android.internal.R.array.config_safeModeEnabledVibePattern);
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
                return VibrationEffect.get(VibrationEffect.EFFECT_TICK);

            case HapticFeedbackConstants.TEXT_HANDLE_MOVE:
                if (!mHapticTextHandleEnabled) {
                    return null;
                }
                // fallthrough
            case HapticFeedbackConstants.CLOCK_TICK:
            case HapticFeedbackConstants.SEGMENT_FREQUENT_TICK:
                return VibrationEffect.get(VibrationEffect.EFFECT_TEXTURE_TICK);

            case HapticFeedbackConstants.KEYBOARD_RELEASE:
            case HapticFeedbackConstants.VIRTUAL_KEY_RELEASE:
            case HapticFeedbackConstants.ENTRY_BUMP:
            case HapticFeedbackConstants.DRAG_CROSSING:
                return VibrationEffect.get(VibrationEffect.EFFECT_TICK, false);

            case HapticFeedbackConstants.KEYBOARD_TAP: // == KEYBOARD_PRESS
            case HapticFeedbackConstants.VIRTUAL_KEY:
            case HapticFeedbackConstants.EDGE_RELEASE:
            case HapticFeedbackConstants.CALENDAR_DATE:
            case HapticFeedbackConstants.CONFIRM:
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
                return VibrationEffect.get(VibrationEffect.EFFECT_DOUBLE_CLICK);

            case HapticFeedbackConstants.SAFE_MODE_ENABLED:
                return mSafeModeEnabledVibrationEffect;

            case HapticFeedbackConstants.ASSISTANT_BUTTON:
                if (mVibrator.areAllPrimitivesSupported(
                        VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
                        VibrationEffect.Composition.PRIMITIVE_TICK)) {
                    // quiet ramp, short pause, then sharp tick
                    return VibrationEffect.startComposition()
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.25f)
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 1f, 50)
                            .compose();
                }
                // fallback for devices without composition support
                return VibrationEffect.get(VibrationEffect.EFFECT_HEAVY_CLICK);

            case HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE:
                return getScaledPrimitiveOrElseEffect(
                        VibrationEffect.Composition.PRIMITIVE_TICK, 0.4f,
                        VibrationEffect.EFFECT_TEXTURE_TICK);

            case HapticFeedbackConstants.TOGGLE_ON:
                return getScaledPrimitiveOrElseEffect(
                        VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f,
                        VibrationEffect.EFFECT_TICK);

            case HapticFeedbackConstants.TOGGLE_OFF:
                return getScaledPrimitiveOrElseEffect(
                        VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.2f,
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
     * @return the {@link VibrationAttributes} that should be used for the provided haptic feedback.
     */
    public VibrationAttributes getVibrationAttributesForHapticFeedback(
            int effectId, boolean bypassVibrationIntensitySetting) {
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
            default:
                attrs = TOUCH_VIBRATION_ATTRIBUTES;
        }
        if (bypassVibrationIntensitySetting) {
            attrs = new VibrationAttributes.Builder(attrs)
                    .setFlags(VibrationAttributes.FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF)
                    .build();
        }
        return attrs;
    }

    /** Dumps relevant state. */
    public void dump(String prefix, PrintWriter pw) {
        pw.print("mHapticTextHandleEnabled="); pw.println(mHapticTextHandleEnabled);
    }

    private VibrationEffect getScaledPrimitiveOrElseEffect(
            int primitiveId, float scale, int elseEffectId) {
        if (mVibrator.areAllPrimitivesSupported(primitiveId)) {
            return VibrationEffect.startComposition()
                    .addPrimitive(primitiveId, scale)
                    .compose();
        } else {
            return VibrationEffect.get(elseEffectId);
        }
    }
}
