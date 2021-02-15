/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.content.Context;
import android.hardware.vibrator.V1_0.EffectStrength;
import android.os.CombinedVibrationEffect;
import android.os.IExternalVibratorService;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Slog;
import android.util.SparseArray;

import java.util.List;
import java.util.Objects;

/** Controls vibration scaling. */
final class VibrationScaler {
    private static final String TAG = "VibrationScaler";

    // Scale levels. Each level, except MUTE, is defined as the delta between the current setting
    // and the default intensity for that type of vibration (i.e. current - default).
    private static final int SCALE_VERY_LOW = IExternalVibratorService.SCALE_VERY_LOW; // -2
    private static final int SCALE_LOW = IExternalVibratorService.SCALE_LOW; // -1
    private static final int SCALE_NONE = IExternalVibratorService.SCALE_NONE; // 0
    private static final int SCALE_HIGH = IExternalVibratorService.SCALE_HIGH; // 1
    private static final int SCALE_VERY_HIGH = IExternalVibratorService.SCALE_VERY_HIGH; // 2

    // Scale factors for each level.
    private static final float SCALE_FACTOR_VERY_LOW = 0.6f;
    private static final float SCALE_FACTOR_LOW = 0.8f;
    private static final float SCALE_FACTOR_NONE = 1f;
    private static final float SCALE_FACTOR_HIGH = 1.2f;
    private static final float SCALE_FACTOR_VERY_HIGH = 1.4f;

    // A mapping from the intensity adjustment to the scaling to apply, where the intensity
    // adjustment is defined as the delta between the default intensity level and the user selected
    // intensity level. It's important that we apply the scaling on the delta between the two so
    // that the default intensity level applies no scaling to application provided effects.
    private final SparseArray<ScaleLevel> mScaleLevels;
    private final VibrationSettings mSettingsController;
    private final int mDefaultVibrationAmplitude;

    VibrationScaler(Context context, VibrationSettings settingsController) {
        mSettingsController = settingsController;
        mDefaultVibrationAmplitude = context.getResources().getInteger(
                com.android.internal.R.integer.config_defaultVibrationAmplitude);

        mScaleLevels = new SparseArray<>();
        mScaleLevels.put(SCALE_VERY_LOW, new ScaleLevel(SCALE_FACTOR_VERY_LOW));
        mScaleLevels.put(SCALE_LOW, new ScaleLevel(SCALE_FACTOR_LOW));
        mScaleLevels.put(SCALE_NONE, new ScaleLevel(SCALE_FACTOR_NONE));
        mScaleLevels.put(SCALE_HIGH, new ScaleLevel(SCALE_FACTOR_HIGH));
        mScaleLevels.put(SCALE_VERY_HIGH, new ScaleLevel(SCALE_FACTOR_VERY_HIGH));
    }

    /**
     * Calculates the scale to be applied to external vibration with given usage.
     *
     * @param usageHint one of VibrationAttributes.USAGE_*
     * @return one of IExternalVibratorService.SCALE_*
     */
    public int getExternalVibrationScale(int usageHint) {
        int defaultIntensity = mSettingsController.getDefaultIntensity(usageHint);
        int currentIntensity = mSettingsController.getCurrentIntensity(usageHint);
        int scaleLevel = currentIntensity - defaultIntensity;

        if (scaleLevel >= SCALE_VERY_LOW && scaleLevel <= SCALE_VERY_HIGH) {
            return scaleLevel;
        } else {
            // Something about our scaling has gone wrong, so just play with no scaling.
            Slog.w(TAG, "Error in scaling calculations, ended up with invalid scale level "
                    + scaleLevel + " for vibration with usage " + usageHint);
            return SCALE_NONE;
        }
    }

    /**
     * Scale a {@link CombinedVibrationEffect} based on the given usage hint for this vibration.
     *
     * @param combinedEffect the effect to be scaled
     * @param usageHint      one of VibrationAttributes.USAGE_*
     * @return The same given effect, if no changes were made, or a new
     * {@link CombinedVibrationEffect} with resolved and scaled amplitude
     */
    public <T extends CombinedVibrationEffect> T scale(CombinedVibrationEffect combinedEffect,
            int usageHint) {
        if (combinedEffect instanceof CombinedVibrationEffect.Mono) {
            VibrationEffect effect = ((CombinedVibrationEffect.Mono) combinedEffect).getEffect();
            return (T) CombinedVibrationEffect.createSynced(scale(effect, usageHint));
        } else if (combinedEffect instanceof CombinedVibrationEffect.Stereo) {
            SparseArray<VibrationEffect> effects =
                    ((CombinedVibrationEffect.Stereo) combinedEffect).getEffects();
            CombinedVibrationEffect.SyncedCombination combination =
                    CombinedVibrationEffect.startSynced();
            for (int i = 0; i < effects.size(); i++) {
                combination.addVibrator(effects.keyAt(i), scale(effects.valueAt(i), usageHint));
            }
            return (T) combination.combine();
        } else if (combinedEffect instanceof CombinedVibrationEffect.Sequential) {
            List<CombinedVibrationEffect> effects =
                    ((CombinedVibrationEffect.Sequential) combinedEffect).getEffects();
            CombinedVibrationEffect.SequentialCombination combination =
                    CombinedVibrationEffect.startSequential();
            for (CombinedVibrationEffect effect : effects) {
                combination.addNext(scale(effect, usageHint));
            }
            return (T) combination.combine();
        } else {
            // Unknown combination, return same effect.
            return (T) combinedEffect;
        }
    }

    /**
     * Scale a {@link VibrationEffect} based on the given usage hint for this vibration.
     *
     * @param effect    the effect to be scaled
     * @param usageHint one of VibrationAttributes.USAGE_*
     * @return The same given effect, if no changes were made, or a new {@link VibrationEffect} with
     * resolved and scaled amplitude
     */
    public <T extends VibrationEffect> T scale(VibrationEffect effect, int usageHint) {
        if (effect instanceof VibrationEffect.Prebaked) {
            // Prebaked effects are always just a direct translation to EffectStrength.
            int intensity = mSettingsController.getCurrentIntensity(usageHint);
            int newStrength = intensityToEffectStrength(intensity);
            VibrationEffect.Prebaked prebaked = (VibrationEffect.Prebaked) effect;
            int strength = prebaked.getEffectStrength();
            VibrationEffect fallback = prebaked.getFallbackEffect();

            if (fallback != null) {
                VibrationEffect scaledFallback = scale(fallback, usageHint);
                if (strength == newStrength && Objects.equals(fallback, scaledFallback)) {
                    return (T) prebaked;
                }

                return (T) new VibrationEffect.Prebaked(prebaked.getId(), newStrength,
                        scaledFallback);
            } else if (strength == newStrength) {
                return (T) prebaked;
            } else {
                return (T) new VibrationEffect.Prebaked(prebaked.getId(), prebaked.shouldFallback(),
                        newStrength);
            }
        }

        effect = effect.resolve(mDefaultVibrationAmplitude);
        int defaultIntensity = mSettingsController.getDefaultIntensity(usageHint);
        int currentIntensity = mSettingsController.getCurrentIntensity(usageHint);
        ScaleLevel scale = mScaleLevels.get(currentIntensity - defaultIntensity);

        if (scale == null) {
            // Something about our scaling has gone wrong, so just play with no scaling.
            Slog.e(TAG, "No configured scaling level!"
                    + " (current=" + currentIntensity + ", default= " + defaultIntensity + ")");
            return (T) effect;
        }

        return effect.scale(scale.factor);
    }

    /** Mapping of Vibrator.VIBRATION_INTENSITY_* values to {@link EffectStrength}. */
    private static int intensityToEffectStrength(int intensity) {
        switch (intensity) {
            case Vibrator.VIBRATION_INTENSITY_LOW:
                return EffectStrength.LIGHT;
            case Vibrator.VIBRATION_INTENSITY_MEDIUM:
                return EffectStrength.MEDIUM;
            case Vibrator.VIBRATION_INTENSITY_HIGH:
                return EffectStrength.STRONG;
            default:
                Slog.w(TAG, "Got unexpected vibration intensity: " + intensity);
                return EffectStrength.STRONG;
        }
    }

    /** Represents the scale that must be applied to a vibration effect intensity. */
    private static final class ScaleLevel {
        public final float factor;

        ScaleLevel(float factor) {
            this.factor = factor;
        }

        @Override
        public String toString() {
            return "ScaleLevel{factor=" + factor + "}";
        }
    }
}
