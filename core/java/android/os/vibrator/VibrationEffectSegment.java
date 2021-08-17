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

    /** Returns true if this segment plays at a non-zero amplitude at some point. */
    public abstract boolean hasNonZeroAmplitude();

    /** Validates the segment, throwing exceptions if any parameter is invalid. */
    public abstract void validate();

    /**
     * Resolves amplitudes set to {@link VibrationEffect#DEFAULT_AMPLITUDE}.
     *
     * <p>This might fail with {@link IllegalArgumentException} if value is non-positive or larger
     * than {@link VibrationEffect#MAX_AMPLITUDE}.
     */
    @NonNull
    public abstract <T extends VibrationEffectSegment> T resolve(int defaultAmplitude);

    /**
     * Scale the segment intensity with the given factor.
     *
     * @param scaleFactor scale factor to be applied to the intensity. Values within [0,1) will
     *                    scale down the intensity, values larger than 1 will scale up
     */
    @NonNull
    public abstract <T extends VibrationEffectSegment> T scale(float scaleFactor);

    /**
     * Applies given effect strength to prebaked effects.
     *
     * @param effectStrength new effect strength to be applied, one of
     *                       VibrationEffect.EFFECT_STRENGTH_*.
     */
    @NonNull
    public abstract <T extends VibrationEffectSegment> T applyEffectStrength(int effectStrength);

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
