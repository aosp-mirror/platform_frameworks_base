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

package android.os;

import android.annotation.NonNull;

import java.util.Objects;

/**
 * A CombinedVibrationEffect describes a haptic effect to be performed by one or more {@link
 * Vibrator Vibrators}.
 *
 * These effects may be any number of things, from single shot vibrations to complex waveforms.
 *
 * @hide
 * @see VibrationEffect
 */
public abstract class CombinedVibrationEffect implements Parcelable {
    private static final int PARCEL_TOKEN_MONO = 1;

    /** @hide to prevent subclassing from outside of the framework */
    public CombinedVibrationEffect() {
    }

    /**
     * Create a synced vibration effect.
     *
     * A synced vibration effect should be performed by multiple vibrators at the same time.
     *
     * @param effect The {@link VibrationEffect} to perform
     * @return The desired combined effect.
     */
    @NonNull
    public static CombinedVibrationEffect createSynced(@NonNull VibrationEffect effect) {
        CombinedVibrationEffect combined = new Mono(effect);
        combined.validate();
        return combined;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    public abstract void validate();

    /**
     * Represents a single {@link VibrationEffect} that should be executed in all vibrators in sync.
     *
     * @hide
     */
    public static final class Mono extends CombinedVibrationEffect {
        private final VibrationEffect mEffect;

        public Mono(Parcel in) {
            mEffect = VibrationEffect.CREATOR.createFromParcel(in);
        }

        public Mono(@NonNull VibrationEffect effect) {
            mEffect = effect;
        }

        public VibrationEffect getEffect() {
            return mEffect;
        }

        /** @hide */
        @Override
        public void validate() {
            mEffect.validate();
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof CombinedVibrationEffect.Mono)) {
                return false;
            }
            CombinedVibrationEffect.Mono other = (CombinedVibrationEffect.Mono) o;
            return other.mEffect.equals(other.mEffect);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mEffect);
        }

        @Override
        public String toString() {
            return "Mono{mEffect=" + mEffect + '}';
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            out.writeInt(PARCEL_TOKEN_MONO);
            mEffect.writeToParcel(out, flags);
        }

        @NonNull
        public static final Parcelable.Creator<Mono> CREATOR =
                new Parcelable.Creator<Mono>() {
                    @Override
                    public Mono createFromParcel(@NonNull Parcel in) {
                        // Skip the type token
                        in.readInt();
                        return new Mono(in);
                    }

                    @Override
                    @NonNull
                    public Mono[] newArray(int size) {
                        return new Mono[size];
                    }
                };
    }

    @NonNull
    public static final Parcelable.Creator<CombinedVibrationEffect> CREATOR =
            new Parcelable.Creator<CombinedVibrationEffect>() {
                @Override
                public CombinedVibrationEffect createFromParcel(Parcel in) {
                    int token = in.readInt();
                    if (token == PARCEL_TOKEN_MONO) {
                        return new CombinedVibrationEffect.Mono(in);
                    } else {
                        throw new IllegalStateException(
                                "Unexpected combined vibration event type token in parcel.");
                    }
                }

                @Override
                public CombinedVibrationEffect[] newArray(int size) {
                    return new CombinedVibrationEffect[size];
                }
            };
}
