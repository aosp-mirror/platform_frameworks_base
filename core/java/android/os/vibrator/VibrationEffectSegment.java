/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.VibrationEffect;
import android.os.VibratorInfo;

/**
 * Representation of a single segment of a {@link VibrationEffect}.
 *
 * <p>Vibration effects are represented as a sequence of segments that describes how vibration
 * amplitude and frequency changes over time. Segments can be described as one of the following:
 *
 * <ol>
 *     <li>A predefined vibration effect;
 *     <li>A composable effect primitive;
 *     <li>Fixed amplitude and frequency values to be held for a specified duration;
 *     <li>Pairs of amplitude and frequency values to be ramped to for a specified duration;
 * </ol>
 *
 * @hide
 */
@TestApi
@SuppressWarnings({"ParcelNotFinal", "ParcelCreator"}) // Parcel only extended here.
public abstract class VibrationEffectSegment implements Parcelable {
    static final int PARCEL_TOKEN_PREBAKED = 1;
    static final int PARCEL_TOKEN_PRIMITIVE = 2;
    static final int PARCEL_TOKEN_STEP = 3;
    static final int PARCEL_TOKEN_RAMP = 4;

    /** Prevent subclassing from outside of this package */
    VibrationEffectSegment() {
    }

    /**
     * Gets the estimated duration of the segment in milliseconds.
     *
     * <p>For segments with an unknown duration (e.g. prebaked or primitive effects where the length
     * is device and potentially run-time dependent), this returns -1.
     */
    public abstract long getDuration();

   /**
     * Checks if a given {@link Vibrator} can play this segment as intended. See
     * {@link Vibrator#areVibrationFeaturesSupported(VibrationEffect)} for more information about
     * what counts as supported by a vibrator, and what counts as not.
     *
     * @hide
     */
    public abstract boolean areVibrationFeaturesSupported(@NonNull VibratorInfo vibratorInfo);

    /**
     * Returns true if this segment could be a haptic feedback effect candidate.
     *
     * @see VibrationEffect#isHapticFeedbackCandidate()
     * @hide
     */
    public abstract boolean isHapticFeedbackCandidate();

    /**
     * Validates the segment, throwing exceptions if any parameter is invalid.
     *
     * @hide
     */
    public abstract void validate();

    /**
     * Resolves amplitudes set to {@link VibrationEffect#DEFAULT_AMPLITUDE}.
     *
     * <p>This might fail with {@link IllegalArgumentException} if value is non-positive or larger
     * than {@link VibrationEffect#MAX_AMPLITUDE}.
     *
     * @hide
     */
    @NonNull
    public abstract <T extends VibrationEffectSegment> T resolve(int defaultAmplitude);

    /**
     * Scale the segment intensity with the given factor.
     *
     * <p> This scale is not necessarily linear and may apply a gamma correction to the scale
     * factor before using it.
     *
     * @param scaleFactor scale factor to be applied to the intensity. Values within [0,1) will
     *                    scale down the intensity, values larger than 1 will scale up
     *
     * @hide
     */
    @NonNull
    public abstract <T extends VibrationEffectSegment> T scale(float scaleFactor);

    /**
     * Performs a linear scaling on the segment intensity with the given factor.
     *
     * @param scaleFactor scale factor to be applied to the intensity. Values within [0,1) will
     *                    scale down the intensity, values larger than 1 will scale up
     *
     * @hide
     */
    @NonNull
    public abstract <T extends VibrationEffectSegment> T scaleLinearly(float scaleFactor);

    /**
     * Applies given effect strength to prebaked effects.
     *
     * @param effectStrength new effect strength to be applied, one of
     *                       VibrationEffect.EFFECT_STRENGTH_*.
     *
     * @hide
     */
    @NonNull
    public abstract <T extends VibrationEffectSegment> T applyEffectStrength(int effectStrength);

    /**
     * Returns a compact version of the {@link #toString()} result for debugging purposes.
     *
     * @hide
     */
    public abstract String toDebugString();

    /**
     * Checks the given frequency argument is valid to represent a vibration effect frequency in
     * hertz, i.e. a finite non-negative value.
     *
     * @param value the frequency argument value to be checked
     * @param name the argument name for the error message.
     *
     * @hide
     */
    public static void checkFrequencyArgument(float value, @NonNull String name) {
        // Similar to combining Preconditions checkArgumentFinite + checkArgumentNonnegative,
        // but this implementation doesn't create the error message unless a check fail.
        if (Float.isNaN(value)) {
            throw new IllegalArgumentException(name + " must not be NaN");
        }
        if (Float.isInfinite(value)) {
            throw new IllegalArgumentException(name + " must not be infinite");
        }
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be >= 0, got " + value);
        }
    }

    /**
     * Checks the given duration argument is valid, i.e. a non-negative value.
     *
     * @param value the duration value to be checked
     * @param name the argument name for the error message.
     *
     * @hide
     */
    public static void checkDurationArgument(long value, @NonNull String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be >= 0, got " + value);
        }
    }

    /**
     * Helper method to check if an amplitude requires a vibrator to have amplitude control to play.
     *
     * @hide
     */
    protected static boolean amplitudeRequiresAmplitudeControl(float amplitude) {
        return (amplitude != 0)
                && (amplitude != 1)
                && (amplitude != VibrationEffect.DEFAULT_AMPLITUDE);
    }

    /**
     * Helper method to check if a frequency requires a vibrator to have frequency control to play.
     *
     * @hide
     */
    protected static boolean frequencyRequiresFrequencyControl(float frequency) {
        // Anything other than the default frequency value (represented with "0") requires frequency
        // control.
        return frequency != 0;
    }

    @NonNull
    public static final Creator<VibrationEffectSegment> CREATOR =
            new Creator<VibrationEffectSegment>() {
                @Override
                public VibrationEffectSegment createFromParcel(Parcel in) {
                    switch (in.readInt()) {
                        case PARCEL_TOKEN_STEP:
                            return new StepSegment(in);
                        case PARCEL_TOKEN_RAMP:
                            return new RampSegment(in);
                        case PARCEL_TOKEN_PREBAKED:
                            return new PrebakedSegment(in);
                        case PARCEL_TOKEN_PRIMITIVE:
                            return new PrimitiveSegment(in);
                        default:
                            throw new IllegalStateException(
                                    "Unexpected vibration event type token in parcel.");
                    }
                }

                @Override
                public VibrationEffectSegment[] newArray(int size) {
                    return new VibrationEffectSegment[size];
                }
            };
}
