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
import android.content.Context;
import android.hardware.vibrator.V1_0.EffectStrength;
import android.os.ExternalVibrationScale;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.vibrator.Flags;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Locale;

/** Controls vibration scaling. */
final class VibrationScaler {
    private static final String TAG = "VibrationScaler";

    // Scale levels. Each level, except MUTE, is defined as the delta between the current setting
    // and the default intensity for that type of vibration (i.e. current - default).
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

    private static final ScaleLevel SCALE_LEVEL_NONE = new ScaleLevel(SCALE_FACTOR_NONE);

    // A mapping from the intensity adjustment to the scaling to apply, where the intensity
    // adjustment is defined as the delta between the default intensity level and the user selected
    // intensity level. It's important that we apply the scaling on the delta between the two so
    // that the default intensity level applies no scaling to application provided effects.
    private final SparseArray<ScaleLevel> mScaleLevels;
    private final VibrationSettings mSettingsController;
    private final int mDefaultVibrationAmplitude;
    private final SparseArray<Float> mAdaptiveHapticsScales = new SparseArray<>();

    VibrationScaler(Context context, VibrationSettings settingsController) {
        mSettingsController = settingsController;
        mDefaultVibrationAmplitude = context.getResources().getInteger(
                com.android.internal.R.integer.config_defaultVibrationAmplitude);

        mScaleLevels = new SparseArray<>();
        mScaleLevels.put(SCALE_VERY_LOW, new ScaleLevel(SCALE_FACTOR_VERY_LOW));
        mScaleLevels.put(SCALE_LOW, new ScaleLevel(SCALE_FACTOR_LOW));
        mScaleLevels.put(SCALE_NONE, SCALE_LEVEL_NONE);
        mScaleLevels.put(SCALE_HIGH, new ScaleLevel(SCALE_FACTOR_HIGH));
        mScaleLevels.put(SCALE_VERY_HIGH, new ScaleLevel(SCALE_FACTOR_VERY_HIGH));
    }

    /**
     * Returns the default vibration amplitude configured for this device, value in [1,255].
     */
    public int getDefaultVibrationAmplitude() {
        return mDefaultVibrationAmplitude;
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
        if (!(effect instanceof VibrationEffect.Composed)) {
            // This only scales composed vibration effects.
            Slog.wtf(TAG, "Error scaling unsupported vibration effect: " + effect);
            return effect;
        }

        int newEffectStrength = getEffectStrength(usageHint);
        ScaleLevel scaleLevel = mScaleLevels.get(getScaleLevel(usageHint));
        float adaptiveScale = getAdaptiveHapticsScale(usageHint);

        if (scaleLevel == null) {
            // Something about our scaling has gone wrong, so just play with no scaling.
            Slog.e(TAG, "No configured scaling level found! (current="
                    + mSettingsController.getCurrentIntensity(usageHint) + ", default= "
                    + mSettingsController.getDefaultIntensity(usageHint) + ")");
            scaleLevel = SCALE_LEVEL_NONE;
        }

        VibrationEffect.Composed composedEffect = (VibrationEffect.Composed) effect;
        ArrayList<VibrationEffectSegment> segments =
                new ArrayList<>(composedEffect.getSegments());
        int segmentCount = segments.size();
        for (int i = 0; i < segmentCount; i++) {
            segments.set(i,
                    segments.get(i).resolve(mDefaultVibrationAmplitude)
                            .applyEffectStrength(newEffectStrength)
                            .scale(scaleLevel.factor)
                            .scaleLinearly(adaptiveScale));
        }
        if (segments.equals(composedEffect.getSegments())) {
            // No segment was updated, return original effect.
            return effect;
        }
        VibrationEffect.Composed scaled =
                new VibrationEffect.Composed(segments, composedEffect.getRepeatIndex());
        // Make sure we validate what was scaled, since we're using the constructor directly
        scaled.validate();
        return scaled;
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
        pw.println("defaultVibrationAmplitude = " + mDefaultVibrationAmplitude);

        pw.println("ScaleLevels:");
        pw.increaseIndent();
        for (int i = 0; i < mScaleLevels.size(); i++) {
            int scaleLevelKey = mScaleLevels.keyAt(i);
            ScaleLevel scaleLevel = mScaleLevels.valueAt(i);
            pw.println(scaleLevelToString(scaleLevelKey) + " = " + scaleLevel);
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
        return "VibrationScaler{"
                + "mScaleLevels=" + mScaleLevels
                + ", mDefaultVibrationAmplitude=" + mDefaultVibrationAmplitude
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
