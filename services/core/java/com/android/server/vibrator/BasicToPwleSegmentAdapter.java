/*
 * Copyright (C) 2024 The Android Open Source Project
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
import android.annotation.Nullable;
import android.hardware.vibrator.IVibrator;
import android.os.VibratorInfo;
import android.os.vibrator.BasicPwleSegment;
import android.os.vibrator.Flags;
import android.os.vibrator.PwleSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.util.MathUtils;
import android.util.Pair;
import android.util.Slog;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Adapts {@link BasicPwleSegment} instances to device-specific {@link PwleSegment}
 * representations, considering device capabilities such as the supported frequency range
 * (defined by the intersection points of the frequency-acceleration response curve with the
 * minimum sensitivity threshold) and the maximum achievable sensitivity level.
 *
 * <p>The segments will not be changed if the device doesn't have
 * {@link IVibrator#CAP_COMPOSE_PWLE_EFFECTS_V2}.
 */
final class BasicToPwleSegmentAdapter implements VibrationSegmentsAdapter {
    private static final String TAG = "BasicToPwleSegmentAdapter";
    private static final int MIN_REQUIRED_SENSITIVITY_DB_SL = 10;
    /**
     * An array of (frequency in Hz, minimum perceptible acceleration in dB) pairs.
     * Each pair represents the minimum output level (in dB) required for a human to perceive the
     * vibration at the corresponding frequency.
     */
    private static final Pair<Float, Float>[] MIN_PERCEPTIBLE_CURVE = new Pair[]{
            Pair.create(0.4f, -97.81f), Pair.create(2.0f, -69.86f),
            Pair.create(3.0f, -62.81f), Pair.create(4.0f, -58.81f),
            Pair.create(5.0f, -56.69f), Pair.create(6.0f, -54.77f),
            Pair.create(7.2f, -52.85f), Pair.create(8.0f, -51.77f),
            Pair.create(8.64f, -50.84f), Pair.create(10.0f, -48.90f),
            Pair.create(10.37f, -48.52f), Pair.create(12.44f, -46.50f),
            Pair.create(14.93f, -44.43f), Pair.create(15.0f, -44.35f),
            Pair.create(17.92f, -41.96f), Pair.create(20.0f, -40.36f),
            Pair.create(21.5f, -39.60f), Pair.create(25.0f, -37.48f),
            Pair.create(25.8f, -36.93f), Pair.create(30.0f, -34.31f),
            Pair.create(35.0f, -33.13f), Pair.create(40.0f, -32.81f),
            Pair.create(50.0f, -31.94f), Pair.create(60.0f, -31.77f),
            Pair.create(70.0f, -31.59f), Pair.create(72.0f, -31.55f),
            Pair.create(80.0f, -31.77f), Pair.create(86.4f, -31.94f),
            Pair.create(90.0f, -31.73f), Pair.create(100.0f, -31.90f),
            Pair.create(103.68f, -31.77f), Pair.create(124.42f, -31.70f),
            Pair.create(149.3f, -31.38f), Pair.create(150.0f, -31.35f),
            Pair.create(179.16f, -31.02f), Pair.create(200.0f, -30.86f),
            Pair.create(215.0f, -30.35f), Pair.create(250.0f, -28.98f),
            Pair.create(258.0f, -28.68f), Pair.create(300.0f, -26.81f),
            Pair.create(400.0f, -19.81f)
    };
    private static final float[] sMinPerceptibleFrequenciesHz =
            new float[MIN_PERCEPTIBLE_CURVE.length];
    private static final float[] sMinPerceptibleAccelerationsDb =
            new float[MIN_PERCEPTIBLE_CURVE.length];

    BasicToPwleSegmentAdapter() {

        // Sort the 'MIN_PERCEPTIBLE_LEVEL' data in ascending order based on the
        // frequency values (first element of each pair).
        Arrays.sort(MIN_PERCEPTIBLE_CURVE, Comparator.comparing(pair -> pair.first));

        for (int i = 0; i < MIN_PERCEPTIBLE_CURVE.length; i++) {
            sMinPerceptibleFrequenciesHz[i] = MIN_PERCEPTIBLE_CURVE[i].first;
            sMinPerceptibleAccelerationsDb[i] = MIN_PERCEPTIBLE_CURVE[i].second;
        }
    }

    @Override
    public int adaptToVibrator(VibratorInfo info, List<VibrationEffectSegment> segments,
            int repeatIndex) {
        if (!Flags.normalizedPwleEffects()
                || !info.hasCapability(IVibrator.CAP_COMPOSE_PWLE_EFFECTS_V2)) {
            // The vibrator does not have PWLE v2 capability, so keep the segments unchanged.
            return repeatIndex;
        }

        VibratorInfo.FrequencyProfile frequencyProfile = info.getFrequencyProfile();
        float[] frequenciesHz = frequencyProfile.getFrequenciesHz();
        float[] accelerationsGs = frequencyProfile.getOutputAccelerationsGs();

        Pair<Float, Float> frequencyRangeHz = calculateFrequencyRangeHz(
                Objects.requireNonNull(frequenciesHz), Objects.requireNonNull(accelerationsGs));

        if (frequencyRangeHz == null) {
            // Failed to retrieve frequency range, so keep the segments unchanged.
            return repeatIndex;
        }
        float minFrequencyHz = frequencyRangeHz.first;
        float maxFrequencyHz = frequencyRangeHz.second;
        float maxSensitivityLevel = getMaxSensitivityLevel(frequenciesHz, accelerationsGs,
                minFrequencyHz, maxFrequencyHz);

        for (int i = 0; i < segments.size(); i++) {
            VibrationEffectSegment segment = segments.get(i);
            if (segment instanceof BasicPwleSegment basicPwleSegment) {
                PwleSegment pwleSegment = convertBasicToPwleSegment(frequencyProfile,
                        basicPwleSegment, minFrequencyHz, maxFrequencyHz,
                        maxSensitivityLevel);
                segments.set(i, pwleSegment);
            }
        }

        return repeatIndex;
    }

    /**
     * Returns the supported frequency range within which {@link BasicPwleSegment}s are created.
     * This range, also referred to as the "sharpness range", is defined by the
     * minimum and maximum frequencies where the actuator's output acceleration exceeds the minimum
     * required sensitivity level.
     *
     * <p>The minimum frequency is the first point where the actuator's frequency to output
     * acceleration response curve intersects the minimum sensitivity threshold. The maximum
     * frequency is determined by the second intersection point, or the maximum available
     * frequency if no second intersection exists.
     *
     * @return The supported frequency range, or null if the minimum frequency cannot be determined.
     */
    @Nullable
    private static Pair<Float, Float> calculateFrequencyRangeHz(@NonNull float[] frequenciesHz,
            @NonNull float[] accelerationsGs) {
        float minFrequencyHz = Float.NaN;
        float maxFrequencyHz = Float.NaN;

        for (int i = 0; i < frequenciesHz.length; i++) {
            float minAcceptableOutputAcceleration = convertSensitivityLevelToAccelerationGs(
                    MIN_REQUIRED_SENSITIVITY_DB_SL, frequenciesHz[i]);

            if (Float.isNaN(minFrequencyHz)
                    && minAcceptableOutputAcceleration <= accelerationsGs[i]) {
                if (i == 0) {
                    minFrequencyHz = frequenciesHz[0];
                } else {
                    minFrequencyHz = MathUtils.constrainedMap(
                            frequenciesHz[i - 1], frequenciesHz[i],
                            accelerationsGs[i - 1], accelerationsGs[i],
                            minAcceptableOutputAcceleration);
                } // Found the lower bound
            } else if (!Float.isNaN(minFrequencyHz)
                    && minAcceptableOutputAcceleration >= accelerationsGs[i]) {
                maxFrequencyHz = MathUtils.constrainedMap(
                        frequenciesHz[i - 1], frequenciesHz[i],
                        accelerationsGs[i - 1], accelerationsGs[i],
                        minAcceptableOutputAcceleration); // Found the upper bound
                return new Pair<>(minFrequencyHz, maxFrequencyHz);
            }
        }

        if (Float.isNaN(minFrequencyHz)) {
            // Lower bound was not found
            Slog.e(TAG,
                    "Failed to retrieve frequency range. A valid frequency range must be "
                            + "available to create envelope vibration effects.");
            return null;
        }

        // If only the lower bound was found, set the upper bound to the maximum frequency.
        maxFrequencyHz = frequenciesHz[frequenciesHz.length - 1];

        return new Pair<>(minFrequencyHz, maxFrequencyHz);
    }

    /**
     * Converts the {@link BasicPwleSegment} to its equivalent {@link PwleSegment} based on the
     * devices capabilities.
     */
    private static PwleSegment convertBasicToPwleSegment(
            @NonNull VibratorInfo.FrequencyProfile frequencyProfile,
            @NonNull BasicPwleSegment basicPwleSegment, float minFrequencyHz, float maxFrequencyHz,
            float maxSensitivityLevel) {

        float startFrequency = convertSharpnessToFrequencyHz(basicPwleSegment.getStartSharpness(),
                minFrequencyHz, maxFrequencyHz);
        float endFrequency = convertSharpnessToFrequencyHz(basicPwleSegment.getEndSharpness(),
                minFrequencyHz, maxFrequencyHz);

        float startAmplitude = convertIntensityToAmplitude(frequencyProfile,
                basicPwleSegment.getStartIntensity(), startFrequency, maxSensitivityLevel);
        float endAmplitude = convertIntensityToAmplitude(frequencyProfile,
                basicPwleSegment.getEndIntensity(), endFrequency, maxSensitivityLevel);

        return new PwleSegment(startAmplitude, endAmplitude, startFrequency, endFrequency,
                basicPwleSegment.getDuration());
    }

    /**
     * Calculates the amplitude for the vibrator, ranging [0.0, 1.0], based on the desired
     * intensity and the vibrator's capabilities at the specified frequency.
     *
     * <p>This method first converts the desired intensity to an equivalent acceleration value
     * based on the maximum sensitivity level within the sharpness range. It then compares this
     * desired acceleration to the maximum acceleration the vibrator can produce at the given
     * frequency.
     *
     * <p>If the desired acceleration exceeds the vibrator's capability, the method returns
     * 1.0 (maximum amplitude). Otherwise, it returns a normalized amplitude value, calculated as
     * the ratio of the desired acceleration to the maximum available acceleration at the given
     * frequency.
     */
    private static float convertIntensityToAmplitude(VibratorInfo.FrequencyProfile frequencyProfile,
            float intensity, float frequencyHz, float maxSensitivityLevel) {
        if (intensity == 0) {
            // Zero intensity should map to zero amplitude (i.e. vibrator off)
            // instead of 0 db SL (i.e. the minimum perceivable output).
            // This is for consistency with waveform envelopes, to ensure effects
            // are able to ramp from/to the vibrator off state.
            return 0;
        }

        float desiredAcceleration = convertIntensityToAccelerationGs(intensity, frequencyHz,
                maxSensitivityLevel);
        float availableAcceleration = frequencyProfile.getOutputAccelerationGs(
                frequencyHz);
        return desiredAcceleration >= availableAcceleration ? 1.0f
                : desiredAcceleration / availableAcceleration;
    }

    private static float getMaxSensitivityLevel(float[] frequenciesHz, float[] accelerationsGs,
            float minFrequencyHz, float maxFrequencyHz) {
        float maxAccelerationGs = Float.MIN_VALUE;
        int maxAccelerationIndex = -1;
        for (int i = 0; i < frequenciesHz.length; i++) {
            float frequency = frequenciesHz[i];
            if (frequency < minFrequencyHz) {
                continue;
            }
            if (frequency > maxFrequencyHz) {
                break;
            }
            if (accelerationsGs[i] > maxAccelerationGs) {
                maxAccelerationGs = accelerationsGs[i];
                maxAccelerationIndex = i;
            }
        }

        return convertDecibelToSensitivityLevel(convertAccelerationToDecibel(maxAccelerationGs),
                frequenciesHz[maxAccelerationIndex]);
    }

    private static float convertSharpnessToFrequencyHz(float sharpness, float minFrequencyHz,
            float maxFrequencyHz) {
        return minFrequencyHz + sharpness * (maxFrequencyHz - minFrequencyHz);
    }

    private static float convertIntensityToAccelerationGs(float intensity, float frequencyHz,
            float maxSensitivityLevel) {
        return convertSensitivityLevelToAccelerationGs(intensity * maxSensitivityLevel,
                frequencyHz);
    }

    private static float convertSensitivityLevelToAccelerationGs(float sensitivityLevel,
            float frequencyHz) {
        return convertDecibelToAccelerationGs(
                convertSensitivityLevelToDecibel(sensitivityLevel, frequencyHz));
    }

    private static float convertDecibelToAccelerationGs(float db) {
        return (float) Math.pow(10, db / 20);
    }

    private static float convertSensitivityLevelToDecibel(float sensitivityLevel,
            float frequencyHz) {
        float minPerceptibleDbAtFrequency = getMinPerceptibleAccelerationDb(frequencyHz);
        return sensitivityLevel + minPerceptibleDbAtFrequency;
    }

    private static float convertAccelerationToDecibel(float accelerationGs) {
        return (float) (20 * Math.log10(accelerationGs));
    }

    private static float convertDecibelToSensitivityLevel(float db, float frequencyHz) {
        float minPerceptibleDbAtFrequency = getMinPerceptibleAccelerationDb(frequencyHz);
        return db - minPerceptibleDbAtFrequency;
    }

    /**
     * Retrieves the minimum perceptible acceleration, in dB, for the specified frequency (hz).
     */
    private static float getMinPerceptibleAccelerationDb(float frequencyHz) {

        if (frequencyHz <= sMinPerceptibleFrequenciesHz[0]) {
            return sMinPerceptibleAccelerationsDb[0];
        }
        if (frequencyHz >= sMinPerceptibleFrequenciesHz[sMinPerceptibleFrequenciesHz.length - 1]) {
            return sMinPerceptibleAccelerationsDb[sMinPerceptibleAccelerationsDb.length - 1];
        }

        int idx = Arrays.binarySearch(sMinPerceptibleFrequenciesHz, frequencyHz);
        if (idx >= 0) {
            return sMinPerceptibleAccelerationsDb[idx];
        }
        // This indicates that the value was not found in the list. Adjust index of the
        // insertion point to be at the lower bound.
        idx = -idx - 2;

        return MathUtils.constrainedMap(
                sMinPerceptibleAccelerationsDb[idx],
                sMinPerceptibleAccelerationsDb[idx + 1],
                sMinPerceptibleFrequenciesHz[idx], sMinPerceptibleFrequenciesHz[idx + 1],
                frequencyHz);
    }
}
