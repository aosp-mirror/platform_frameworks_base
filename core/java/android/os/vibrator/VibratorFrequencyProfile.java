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

package android.os.vibrator;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.VibratorInfo;
import android.util.Range;
import android.util.SparseArray;

import com.android.internal.util.Preconditions;

import java.util.Objects;

/**
 * Describes the output of a {@link android.os.Vibrator} for different vibration frequencies.
 *
 * <p>The profile contains the vibrator's frequency range (minimum/maximum) and maximum
 * acceleration, enabling retrieval of supported acceleration levels for specific frequencies, if
 * the device supports independent frequency control.
 *
 * <p>It also describes the max output acceleration (Gs), of a vibration at different supported
 * frequencies (Hz).
 *
 * <p>Vibrators without independent frequency control do not have a frequency profile.
 */
@FlaggedApi(Flags.FLAG_NORMALIZED_PWLE_EFFECTS)
public final class VibratorFrequencyProfile {

    private final VibratorInfo.FrequencyProfile mFrequencyProfile;
    private final SparseArray<Float> mFrequenciesOutputAcceleration;

    /** @hide */
    public VibratorFrequencyProfile(@NonNull VibratorInfo.FrequencyProfile frequencyProfile) {
        Objects.requireNonNull(frequencyProfile);
        Preconditions.checkArgument(!frequencyProfile.isEmpty(),
                "Frequency profile must not be empty");
        mFrequencyProfile = frequencyProfile;
        mFrequenciesOutputAcceleration = generateFrequencyToAccelerationMap(
                frequencyProfile.getFrequenciesHz(), frequencyProfile.getOutputAccelerationsGs());
    }

    /**
     * Returns a {@link SparseArray} representing the vibrator's output acceleration capabilities
     * across different frequencies. This map defines the maximum acceleration
     * the vibrator can achieve at each supported frequency.
     * <p>The map's keys are frequencies in Hz, and the corresponding values
     * are the maximum achievable output accelerations in Gs.
     *
     * @return A map of frequencies (Hz) to maximum accelerations (Gs).
     */
    @FlaggedApi(Flags.FLAG_NORMALIZED_PWLE_EFFECTS)
    @NonNull
    public SparseArray<Float> getFrequenciesOutputAcceleration() {
        return mFrequenciesOutputAcceleration;
    }

    /**
     * Returns the maximum output acceleration (in Gs) supported by the vibrator.
     * This value represents the highest acceleration the vibrator can achieve
     * across its entire frequency range.
     *
     * @return The maximum output acceleration in Gs.
     */
    @FlaggedApi(Flags.FLAG_NORMALIZED_PWLE_EFFECTS)
    public float getMaxOutputAccelerationGs() {
        return mFrequencyProfile.getMaxOutputAccelerationGs();
    }

    /**
     * Returns the frequency range (in Hz) where the vibrator can sustain at least
     * the given minimum output acceleration (Gs).
     *
     * @param minOutputAccelerationGs The minimum desired output acceleration in Gs.
     * @return A {@link Range} object representing the frequency range where the
     *         vibrator can sustain at least the given minimum acceleration, or null if
     *         the minimum output acceleration cannot be achieved.
     *
     */
    @FlaggedApi(Flags.FLAG_NORMALIZED_PWLE_EFFECTS)
    @Nullable
    public Range<Float> getFrequencyRange(float minOutputAccelerationGs) {
        return mFrequencyProfile.getFrequencyRangeHz(minOutputAccelerationGs);
    }

    /**
     * Returns the output acceleration (in Gs) for the given frequency (Hz).
     * This method provides the actual acceleration the vibrator will produce
     * when operating at the specified frequency, using linear interpolation over
     * the {@link #getFrequenciesOutputAcceleration()}.
     *
     * @param frequencyHz The frequency in Hz.
     * @return The output acceleration in Gs for the given frequency.
     */
    @FlaggedApi(Flags.FLAG_NORMALIZED_PWLE_EFFECTS)
    public float getOutputAccelerationGs(float frequencyHz) {
        return mFrequencyProfile.getOutputAccelerationGs(frequencyHz);
    }

    /**
     * Gets the minimum frequency supported by the vibrator.
     *
     * @return the minimum frequency supported by the vibrator, in hertz.
     */
    @FlaggedApi(Flags.FLAG_NORMALIZED_PWLE_EFFECTS)
    public float getMinFrequencyHz() {
        return mFrequencyProfile.getMinFrequencyHz();
    }

    /**
     * Gets the maximum frequency supported by the vibrator.
     *
     * @return the maximum frequency supported by the vibrator, in hertz.
     */
    @FlaggedApi(Flags.FLAG_NORMALIZED_PWLE_EFFECTS)
    public float getMaxFrequencyHz() {
        return mFrequencyProfile.getMaxFrequencyHz();
    }

    private static SparseArray<Float> generateFrequencyToAccelerationMap(
            float[] frequencies, float[] accelerations) {
        SparseArray<Float> sparseArray = new SparseArray<>(frequencies.length);

        for (int i = 0; i < frequencies.length; i++) {
            int frequency = (int) frequencies[i];
            float acceleration = accelerations[i];

            sparseArray.put(frequency,
                    Math.min(acceleration, sparseArray.get(frequency, Float.MAX_VALUE)));

        }

        return sparseArray;
    }
}
