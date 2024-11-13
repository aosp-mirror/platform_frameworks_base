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

import android.annotation.NonNull;
import android.hardware.vibrator.V1_0.EffectStrength;
import android.os.ExternalVibrationScale;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.vibrator.Flags;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.VibrationConfig;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import java.io.PrintWriter;
import java.util.Locale;

/** Controls vibration scaling. */
final class VibrationScaler {
    private static final String TAG = "VibrationScaler";

    // TODO(b/345186129): remove this once we finish migrating to scale factor and clean up flags.
    // Scale levels. Each level, except MUTE, is defined as the delta between the current setting
    // and the default intensity for that type of vibration (i.e. current - default).
    // It's important that we apply the scaling on the delta between the two so
    // that the default intensity level applies no scaling to application provided effects.
    static final int SCALE_VERY_LOW = ExternalVibrationScale.ScaleLevel.SCALE_VERY_LOW; // -2
    static final int SCALE_LOW = ExternalVibrationScale.ScaleLevel.SCALE_LOW; // -1
    static final int SCALE_NONE = ExternalVibrationScale.ScaleLevel.SCALE_NONE; // 0
    static final int SCALE_HIGH = ExternalVibrationScale.ScaleLevel.SCALE_HIGH; // 1
    static final int SCALE_VERY_HIGH = ExternalVibrationScale.ScaleLevel.SCALE_VERY_HIGH; // 2
    static final float ADAPTIVE_SCALE_NONE = 1f;

    // Scale factors for each level.
    private static final float SCALE_FACTOR_VERY_LOW = 0.6f;
    private static final float SCALE_FACTOR_LOW = 0.8f;
    private static final float SCALE_FACTOR_NONE = 1f;
    private static final float SCALE_FACTOR_HIGH = 1.2f;
    private static final float SCALE_FACTOR_VERY_HIGH = 1.4f;

    private final VibrationSettings mSettingsController;
    private final int mDefaultVibrationAmplitude;
    private final float mDefaultVibrationScaleLevelGain;
    private final SparseArray<Float> mAdaptiveHapticsScales = new SparseArray<>();

    VibrationScaler(VibrationConfig config, VibrationSettings settingsController) {
        mSettingsController = settingsController;
        mDefaultVibrationAmplitude = config.getDefaultVibrationAmplitude();
        mDefaultVibrationScaleLevelGain = config.getDefaultVibrationScaleLevelGain();
    }

    /**
     * Calculates the scale to be applied to external vibration with given usage.
     *
     * @param usageHint one of VibrationAttributes.USAGE_*
     * @return one of ExternalVibrationScale.ScaleLevel.SCALE_*
     */
    public int getScaleLevel(int usageHint) {
        int defaultIntensity = mSettingsController.getDefaultIntensity(usageHint);
        int currentIntensity = mSettingsController.getCurrentIntensity(usageHint);
        if (currentIntensity == Vibrator.VIBRATION_INTENSITY_OFF) {
            // Bypassing user settings, or it has changed between checking and scaling. Use default.
            return SCALE_NONE;
        }

        int scaleLevel = currentIntensity - defaultIntensity;
        if (scaleLevel >= SCALE_VERY_LOW && scaleLevel <= SCALE_VERY_HIGH) {
            return scaleLevel;
        }

        // Something about our scaling has gone wrong, so just play with no scaling.
        Slog.wtf(TAG, "Error in scaling calculations, ended up with invalid scale level "
                + scaleLevel + " for vibration with usage " + usageHint);

        return SCALE_NONE;
    }

    /**
     * Calculates the scale factor to be applied to a vibration with given usage.
     *
     * @param usageHint one of VibrationAttributes.USAGE_*
     * @return The scale factor.
     */
    public float getScaleFactor(int usageHint) {
        return scaleLevelToScaleFactor(getScaleLevel(usageHint));
    }

    /**
     * Returns the adaptive haptics scale that should be applied to the vibrations with
     * the given usage. When no adaptive scales are available for the usages, then returns 1
     * indicating no scaling will be applied
     *
     * @param usageHint one of VibrationAttributes.USAGE_*
     * @return The adaptive haptics scale.
     */
    public float getAdaptiveHapticsScale(int usageHint) {
        return Flags.adaptiveHapticsEnabled()
                ? mAdaptiveHapticsScales.get(usageHint, ADAPTIVE_SCALE_NONE)
                : ADAPTIVE_SCALE_NONE;
    }

    /**
     * Scale a {@link VibrationEffect} based on the given usage hint for this vibration.
     *
     * @param effect    the effect to be scaled
     * @param usageHint one of VibrationAttributes.USAGE_*
     * @return The same given effect, if no changes were made, or a new {@link VibrationEffect} with
     * resolved and scaled amplitude
     */
    @NonNull
    public VibrationEffect scale(@NonNull VibrationEffect effect, int usageHint) {
        int newEffectStrength = getEffectStrength(usageHint);
        float scaleFactor = getScaleFactor(usageHint);
        float adaptiveScale = getAdaptiveHapticsScale(usageHint);

        return effect.resolve(mDefaultVibrationAmplitude)
                .applyEffectStrength(newEffectStrength)
                .scale(scaleFactor)
                // Make sure this is the last one so it is applied on top of the settings scaling.
                .applyAdaptiveScale(adaptiveScale);
    }

    /**
     * Scale a {@link PrebakedSegment} based on the given usage hint for this vibration.
     *
     * @param prebaked  the prebaked segment to be scaled
     * @param usageHint one of VibrationAttributes.USAGE_*
     * @return The same segment if no changes were made, or a new {@link PrebakedSegment} with
     * updated effect strength
     */
    public PrebakedSegment scale(PrebakedSegment prebaked, int usageHint) {
        return prebaked.applyEffectStrength(getEffectStrength(usageHint));
    }

    /**
     * Updates the adaptive haptics scales list by adding or modifying the scale for this usage.
     *
     * @param usageHint one of VibrationAttributes.USAGE_*.
     * @param scale The scaling factor that should be applied to vibrations of this usage.
     */
    public void updateAdaptiveHapticsScale(@VibrationAttributes.Usage int usageHint, float scale) {
        mAdaptiveHapticsScales.put(usageHint, scale);
    }

    /**
     * Removes the usage from the cached adaptive haptics scales list.
     *
     * @param usageHint one of VibrationAttributes.USAGE_*.
     */
    public void removeAdaptiveHapticsScale(@VibrationAttributes.Usage int usageHint) {
        mAdaptiveHapticsScales.remove(usageHint);
    }

    /** Removes all cached adaptive haptics scales. */
    public void clearAdaptiveHapticsScales() {
        mAdaptiveHapticsScales.clear();
    }

    /** Write current settings into given {@link PrintWriter}. */
    void dump(IndentingPrintWriter pw) {
        pw.println("VibrationScaler:");
        pw.increaseIndent();

        pw.println("ScaleLevels:");
        pw.increaseIndent();
        for (int level = SCALE_VERY_LOW; level <= SCALE_VERY_HIGH; level++) {
            pw.println(scaleLevelToString(level) + " = " + scaleLevelToScaleFactor(level));
        }
        pw.decreaseIndent();

        pw.println("AdaptiveHapticsScales:");
        pw.increaseIndent();
        for (int i = 0; i < mAdaptiveHapticsScales.size(); i++) {
            int usage = mAdaptiveHapticsScales.keyAt(i);
            float scale = mAdaptiveHapticsScales.valueAt(i);
            pw.println(VibrationAttributes.usageToString(usage)
                    + " = " + String.format(Locale.ROOT, "%.2f", scale));
        }
        pw.decreaseIndent();

        pw.decreaseIndent();
    }

    /** Write current settings into given {@link ProtoOutputStream}. */
    void dump(ProtoOutputStream proto) {
        proto.write(VibratorManagerServiceDumpProto.DEFAULT_VIBRATION_AMPLITUDE,
                mDefaultVibrationAmplitude);
    }

    @Override
    public String toString() {
        StringBuilder scaleLevelsStr = new StringBuilder("{");
        for (int level = SCALE_VERY_LOW; level <= SCALE_VERY_HIGH; level++) {
            scaleLevelsStr.append(scaleLevelToString(level))
                    .append("=").append(scaleLevelToScaleFactor(level));
            if (level < SCALE_FACTOR_VERY_HIGH) {
                scaleLevelsStr.append(", ");
            }
        }
        scaleLevelsStr.append("}");

        return "VibrationScaler{"
                + "mScaleLevels=" + scaleLevelsStr
                + ", mAdaptiveHapticsScales=" + mAdaptiveHapticsScales
                + '}';
    }

    private int getEffectStrength(int usageHint) {
        int currentIntensity = mSettingsController.getCurrentIntensity(usageHint);
        if (currentIntensity == Vibrator.VIBRATION_INTENSITY_OFF) {
            // Bypassing user settings, or it has changed between checking and scaling. Use default.
            currentIntensity = mSettingsController.getDefaultIntensity(usageHint);
        }

        return intensityToEffectStrength(currentIntensity);
    }

    /** Mapping of Vibrator.VIBRATION_INTENSITY_* values to {@link EffectStrength}. */
    private static int intensityToEffectStrength(int intensity) {
        return switch (intensity) {
            case Vibrator.VIBRATION_INTENSITY_LOW -> EffectStrength.LIGHT;
            case Vibrator.VIBRATION_INTENSITY_MEDIUM -> EffectStrength.MEDIUM;
            case Vibrator.VIBRATION_INTENSITY_HIGH -> EffectStrength.STRONG;
            default -> {
                Slog.w(TAG, "Got unexpected vibration intensity: " + intensity);
                yield EffectStrength.STRONG;
            }
        };
    }

    /** Mapping of ExternalVibrationScale.ScaleLevel.SCALE_* values to scale factor. */
    private float scaleLevelToScaleFactor(int level) {
        if (Flags.hapticsScaleV2Enabled()) {
            if (level == SCALE_NONE || level < SCALE_VERY_LOW || level > SCALE_VERY_HIGH) {
                // Scale set to none or to a bad value, use default factor for no scaling.
                return SCALE_FACTOR_NONE;
            }
            float scaleFactor = (float) Math.pow(mDefaultVibrationScaleLevelGain, level);
            if (scaleFactor <= 0) {
                // Something about our scaling has gone wrong, so just play with no scaling.
                Slog.wtf(TAG, String.format(Locale.ROOT, "Error in scaling calculations, ended up"
                                + " with invalid scale factor %.2f for scale level %s and default"
                                + " level gain of %.2f", scaleFactor, scaleLevelToString(level),
                        mDefaultVibrationScaleLevelGain));
                scaleFactor = SCALE_FACTOR_NONE;
            }
            return scaleFactor;
        }

        return switch (level) {
            case SCALE_VERY_LOW -> SCALE_FACTOR_VERY_LOW;
            case SCALE_LOW -> SCALE_FACTOR_LOW;
            case SCALE_HIGH -> SCALE_FACTOR_HIGH;
            case SCALE_VERY_HIGH -> SCALE_FACTOR_VERY_HIGH;
            // Scale set to none or to a bad value, use default factor for no scaling.
            default -> SCALE_FACTOR_NONE;
        };
    }

    static String scaleLevelToString(int scaleLevel) {
        return switch (scaleLevel) {
            case SCALE_VERY_LOW -> "VERY_LOW";
            case SCALE_LOW -> "LOW";
            case SCALE_NONE -> "NONE";
            case SCALE_HIGH -> "HIGH";
            case SCALE_VERY_HIGH -> "VERY_HIGH";
            default -> String.valueOf(scaleLevel);
        };
    }
}
