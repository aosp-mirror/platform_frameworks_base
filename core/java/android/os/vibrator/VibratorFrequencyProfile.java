/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.FloatRange;
import android.annotation.NonNull;
import android.annotation.TestApi;
import android.os.VibratorInfo;

import com.android.internal.util.Preconditions;

/**
 * Describes the output of a {@link android.os.Vibrator} for different vibration frequencies.
 *
 * <p>The profile contains the minimum and maximum supported vibration frequencies, if the device
 * supports independent frequency control.
 *
 * <p>It also describes the relative output acceleration of a vibration at different supported
 * frequencies. The acceleration is defined by a relative amplitude value between 0 and 1,
 * inclusive, where 0 represents the vibrator off state and 1 represents the maximum output
 * acceleration that the vibrator can reach across all supported frequencies.
 *
 * <p>The measurements are returned as an array of uniformly distributed amplitude values for
 * frequencies between the minimum and maximum supported ones. The measurement interval is the
 * frequency increment between each pair of amplitude values.
 *
 * <p>Vibrators without independent frequency control do not have a frequency profile.
 * @hide
 */
@TestApi
public final class VibratorFrequencyProfile {

    private final VibratorInfo.FrequencyProfile mFrequencyProfile;

    /** @hide */
    public VibratorFrequencyProfile(@NonNull VibratorInfo.FrequencyProfile frequencyProfile) {
        Preconditions.checkArgument(!frequencyProfile.isEmpty(),
                "Frequency profile must have a non-empty frequency range");
        mFrequencyProfile = frequencyProfile;
    }

    /**
     * Measurements of the maximum relative amplitude the vibrator can achieve for each supported
     * frequency.
     *
     * <p>The frequency of a measurement is determined as:
     *
     * {@code getMinFrequency() + measurementIndex * getMaxAmplitudeMeasurementInterval()}
     *
     * <p>The returned list will not be empty, and will have entries representing frequencies from
     * {@link #getMinFrequency()} to {@link #getMaxFrequency()}, inclusive.
     *
     * @return Array of maximum relative amplitude measurements.
     */
    @NonNull
    @FloatRange(from = 0, to = 1)
    public float[] getMaxAmplitudeMeasurements() {
        // VibratorInfo getters always return a copy or clone of the data objects.
        return mFrequencyProfile.getMaxAmplitudes();
    }

    /**
     * Gets the frequency interval used to measure the maximum relative amplitudes.
     *
     * @return the frequency interval used for the measurement, in hertz.
     */
    public float getMaxAmplitudeMeasurementInterval() {
        return mFrequencyProfile.getFrequencyResolutionHz();
    }

    /**
     * Gets the minimum frequency supported by the vibrator.
     *
     * @return the minimum frequency supported by the vibrator, in hertz.
     */
    public float getMinFrequency() {
        return mFrequencyProfile.getFrequencyRangeHz().getLower();
    }

    /**
     * Gets the maximum frequency supported by the vibrator.
     *
     * @return the maximum frequency supported by the vibrator, in hertz.
     */
    public float getMaxFrequency() {
        return mFrequencyProfile.getFrequencyRangeHz().getUpper();
    }
}
