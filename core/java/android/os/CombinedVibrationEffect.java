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
import android.annotation.TestApi;
import android.util.SparseArray;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A CombinedVibrationEffect describes a haptic effect to be performed by one or more {@link
 * Vibrator Vibrators}.
 *
 * These effects may be any number of things, from single shot vibrations to complex waveforms.
 * @see VibrationEffect
 */
@SuppressWarnings({"ParcelNotFinal", "ParcelCreator"}) // Parcel only extended here.
public abstract class CombinedVibrationEffect implements Parcelable {
    private static final int PARCEL_TOKEN_MONO = 1;
    private static final int PARCEL_TOKEN_STEREO = 2;
    private static final int PARCEL_TOKEN_SEQUENTIAL = 3;

    /** Prevent subclassing from outside of the framework. */
    CombinedVibrationEffect() {
    }

    /**
     * Create a synced vibration effect.
     *
     * A synced vibration effect should be performed by multiple vibrators at the same time.
     *
     * @param effect The {@link VibrationEffect} to perform.
     * @return The synced effect.
     */
    @NonNull
    public static CombinedVibrationEffect createSynced(@NonNull VibrationEffect effect) {
        CombinedVibrationEffect combined = new Mono(effect);
        combined.validate();
        return combined;
    }

    /**
     * Start creating a synced vibration effect.
     *
     * A synced vibration effect should be performed by multiple vibrators at the same time.
     *
     * @see CombinedVibrationEffect.SyncedCombination
     */
    @NonNull
    public static SyncedCombination startSynced() {
        return new SyncedCombination();
    }

    /**
     * Start creating a sequential vibration effect.
     *
     * A sequential vibration effect should be performed by multiple vibrators in order.
     *
     * @see CombinedVibrationEffect.SequentialCombination
     * @hide
     */
    @TestApi
    @NonNull
    public static SequentialCombination startSequential() {
        return new SequentialCombination();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Gets the estimated duration of the combined vibration in milliseconds.
     *
     * <p>For synced combinations this means the maximum duration of any individual {@link
     * VibrationEffect}. For sequential combinations, this is a sum of each step and delays.
     *
     * <p>For combinations of effects without a defined end (e.g. a Waveform with a non-negative
     * repeat index), this returns Long.MAX_VALUE. For effects with an unknown duration (e.g.
     * Prebaked effects where the length is device and potentially run-time dependent), this returns
     * -1.
     *
     * @hide
     */
    @TestApi
    public abstract long getDuration();

    /** @hide */
    public abstract void validate();

    /** @hide */
    public abstract boolean hasVibrator(int vibratorId);

    /**
     * A combination of haptic effects that should be played in multiple vibrators in sync.
     *
     * @see CombinedVibrationEffect#startSynced()
     */
    public static final class SyncedCombination {

        private final SparseArray<VibrationEffect> mEffects = new SparseArray<>();

        SyncedCombination() {
        }

        /**
         * Add or replace a one shot vibration effect to be performed by the specified vibrator.
         *
         * @param vibratorId The id of the vibrator that should perform this effect.
         * @param effect     The effect this vibrator should play.
         * @return The {@link CombinedVibrationEffect.SyncedCombination} object to enable adding
         * multiple effects in one chain.
         * @see VibrationEffect#createOneShot(long, int)
         */
        @NonNull
        public SyncedCombination addVibrator(int vibratorId, @NonNull VibrationEffect effect) {
            mEffects.put(vibratorId, effect);
            return this;
        }

        /**
         * Combine all of the added effects into a combined effect.
         *
         * The {@link CombinedVibrationEffect.SyncedCombination} object is still valid after this
         * call, so you can continue adding more effects to it and generating more
         * {@link CombinedVibrationEffect}s by calling this method again.
         *
         * @return The {@link CombinedVibrationEffect} resulting from combining the added effects to
         * be played in sync.
         */
        @NonNull
        public CombinedVibrationEffect combine() {
            if (mEffects.size() == 0) {
                throw new IllegalStateException(
                        "Combination must have at least one element to combine.");
            }
            CombinedVibrationEffect combined = new Stereo(mEffects);
            combined.validate();
            return combined;
        }
    }

    /**
     * A combination of haptic effects that should be played in multiple vibrators in sequence.
     *
     * @see CombinedVibrationEffect#startSequential()
     * @hide
     */
    @TestApi
    public static final class SequentialCombination {

        private final ArrayList<CombinedVibrationEffect> mEffects = new ArrayList<>();
        private final ArrayList<Integer> mDelays = new ArrayList<>();

        SequentialCombination() {
        }

        /**
         * Add a single vibration effect to be performed next.
         *
         * Similar to {@link #addNext(int, VibrationEffect, int)}, but with no delay.
         *
         * @param vibratorId The id of the vibrator that should perform this effect.
         * @param effect     The effect this vibrator should play.
         * @return The {@link CombinedVibrationEffect.SequentialCombination} object to enable adding
         * multiple effects in one chain.
         */
        @NonNull
        public SequentialCombination addNext(int vibratorId, @NonNull VibrationEffect effect) {
            return addNext(vibratorId, effect, /* delay= */ 0);
        }

        /**
         * Add a single vibration effect to be performed next.
         *
         * @param vibratorId The id of the vibrator that should perform this effect.
         * @param effect     The effect this vibrator should play.
         * @param delay      The amount of time, in milliseconds, to wait between playing the prior
         *                   effect and this one.
         * @return The {@link CombinedVibrationEffect.SequentialCombination} object to enable adding
         * multiple effects in one chain.
         */
        @NonNull
        public SequentialCombination addNext(int vibratorId, @NonNull VibrationEffect effect,
                int delay) {
            return addNext(
                    CombinedVibrationEffect.startSynced().addVibrator(vibratorId, effect).combine(),
                    delay);
        }

        /**
         * Add a combined vibration effect to be performed next.
         *
         * Similar to {@link #addNext(CombinedVibrationEffect, int)}, but with no delay.
         *
         * @param effect The combined effect to be performed next.
         * @return The {@link CombinedVibrationEffect.SequentialCombination} object to enable adding
         * multiple effects in one chain.
         * @see VibrationEffect#createOneShot(long, int)
         */
        @NonNull
        public SequentialCombination addNext(@NonNull CombinedVibrationEffect effect) {
            return addNext(effect, /* delay= */ 0);
        }

        /**
         * Add a one shot vibration effect to be performed by the specified vibrator.
         *
         * @param effect The combined effect to be performed next.
         * @param delay  The amount of time, in milliseconds, to wait between playing the prior
         *               effect and this one.
         * @return The {@link CombinedVibrationEffect.SequentialCombination} object to enable adding
         * multiple effects in one chain.
         */
        @NonNull
        public SequentialCombination addNext(@NonNull CombinedVibrationEffect effect, int delay) {
            if (effect instanceof Sequential) {
                Sequential sequentialEffect = (Sequential) effect;
                int firstEffectIndex = mDelays.size();
                mEffects.addAll(sequentialEffect.getEffects());
                mDelays.addAll(sequentialEffect.getDelays());
                mDelays.set(firstEffectIndex, delay + mDelays.get(firstEffectIndex));
            } else {
                mEffects.add(effect);
                mDelays.add(delay);
            }
            return this;
        }

        /**
         * Combine all of the added effects in sequence.
         *
         * The {@link CombinedVibrationEffect.SequentialCombination} object is still valid after
         * this call, so you can continue adding more effects to it and generating more {@link
         * CombinedVibrationEffect}s by calling this method again.
         *
         * @return The {@link CombinedVibrationEffect} resulting from combining the added effects to
         * be played in sequence.
         */
        @NonNull
        public CombinedVibrationEffect combine() {
            if (mEffects.size() == 0) {
                throw new IllegalStateException(
                        "Combination must have at least one element to combine.");
            }
            CombinedVibrationEffect combined = new Sequential(mEffects, mDelays);
            combined.validate();
            return combined;
        }
    }

    /**
     * Represents a single {@link VibrationEffect} that should be executed in all vibrators in sync.
     *
     * @hide
     */
    @TestApi
    public static final class Mono extends CombinedVibrationEffect {
        private final VibrationEffect mEffect;

        Mono(Parcel in) {
            mEffect = VibrationEffect.CREATOR.createFromParcel(in);
        }

        Mono(@NonNull VibrationEffect effect) {
            mEffect = effect;
        }

        @NonNull
        public VibrationEffect getEffect() {
            return mEffect;
        }

        @Override
        public long getDuration() {
            return mEffect.getDuration();
        }

        /** @hide */
        @Override
        public void validate() {
            mEffect.validate();
        }

        /** @hide */
        @Override
        public boolean hasVibrator(int vibratorId) {
            return true;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Mono)) {
                return false;
            }
            Mono other = (Mono) o;
            return mEffect.equals(other.mEffect);
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
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel out, int flags) {
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

    /**
     * Represents a list of {@link VibrationEffect}s that should be executed in sync.
     *
     * @hide
     */
    @TestApi
    public static final class Stereo extends CombinedVibrationEffect {

        /** Mapping vibrator ids to effects. */
        private final SparseArray<VibrationEffect> mEffects;

        Stereo(Parcel in) {
            int size = in.readInt();
            mEffects = new SparseArray<>(size);
            for (int i = 0; i < size; i++) {
                int vibratorId = in.readInt();
                mEffects.put(vibratorId, VibrationEffect.CREATOR.createFromParcel(in));
            }
        }

        Stereo(@NonNull SparseArray<VibrationEffect> effects) {
            mEffects = new SparseArray<>(effects.size());
            for (int i = 0; i < effects.size(); i++) {
                mEffects.put(effects.keyAt(i), effects.valueAt(i));
            }
        }

        /** Effects to be performed in sync, where each key represents the vibrator id. */
        @NonNull
        public SparseArray<VibrationEffect> getEffects() {
            return mEffects;
        }

        @Override
        public long getDuration() {
            long maxDuration = Long.MIN_VALUE;
            boolean hasUnknownStep = false;
            for (int i = 0; i < mEffects.size(); i++) {
                long duration = mEffects.valueAt(i).getDuration();
                if (duration == Long.MAX_VALUE) {
                    // If any duration is repeating, this combination duration is also repeating.
                    return duration;
                }
                maxDuration = Math.max(maxDuration, duration);
                // If any step is unknown, this combination duration will also be unknown, unless
                // any step is repeating. Repeating vibrations take precedence over non-repeating
                // ones in the service, so continue looping to check for repeating steps.
                hasUnknownStep |= duration < 0;
            }
            if (hasUnknownStep) {
                // If any step is unknown, this combination duration is also unknown.
                return -1;
            }
            return maxDuration;
        }

        /** @hide */
        @Override
        public void validate() {
            Preconditions.checkArgument(mEffects.size() > 0,
                    "There should be at least one effect set for a combined effect");
            for (int i = 0; i < mEffects.size(); i++) {
                mEffects.valueAt(i).validate();
            }
        }

        /** @hide */
        @Override
        public boolean hasVibrator(int vibratorId) {
            return mEffects.indexOfKey(vibratorId) >= 0;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Stereo)) {
                return false;
            }
            Stereo other = (Stereo) o;
            if (mEffects.size() != other.mEffects.size()) {
                return false;
            }
            for (int i = 0; i < mEffects.size(); i++) {
                if (!mEffects.valueAt(i).equals(other.mEffects.get(mEffects.keyAt(i)))) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            return mEffects.contentHashCode();
        }

        @Override
        public String toString() {
            return "Stereo{mEffects=" + mEffects + '}';
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel out, int flags) {
            out.writeInt(PARCEL_TOKEN_STEREO);
            out.writeInt(mEffects.size());
            for (int i = 0; i < mEffects.size(); i++) {
                out.writeInt(mEffects.keyAt(i));
                mEffects.valueAt(i).writeToParcel(out, flags);
            }
        }

        @NonNull
        public static final Parcelable.Creator<Stereo> CREATOR =
                new Parcelable.Creator<Stereo>() {
                    @Override
                    public Stereo createFromParcel(@NonNull Parcel in) {
                        // Skip the type token
                        in.readInt();
                        return new Stereo(in);
                    }

                    @Override
                    @NonNull
                    public Stereo[] newArray(int size) {
                        return new Stereo[size];
                    }
                };
    }

    /**
     * Represents a list of {@link VibrationEffect}s that should be executed in sequence.
     *
     * @hide
     */
    @TestApi
    public static final class Sequential extends CombinedVibrationEffect {
        private final List<CombinedVibrationEffect> mEffects;
        private final List<Integer> mDelays;

        Sequential(Parcel in) {
            int size = in.readInt();
            mEffects = new ArrayList<>(size);
            mDelays = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                mDelays.add(in.readInt());
                mEffects.add(CombinedVibrationEffect.CREATOR.createFromParcel(in));
            }
        }

        Sequential(@NonNull List<CombinedVibrationEffect> effects,
                @NonNull List<Integer> delays) {
            mEffects = new ArrayList<>(effects);
            mDelays = new ArrayList<>(delays);
        }

        /** Effects to be performed in sequence. */
        @NonNull
        public List<CombinedVibrationEffect> getEffects() {
            return mEffects;
        }

        /** Delay to be applied before each effect in {@link #getEffects()}. */
        @NonNull
        public List<Integer> getDelays() {
            return mDelays;
        }

        @Override
        public long getDuration() {
            boolean hasUnknownStep = false;
            long durations = 0;
            final int effectCount = mEffects.size();
            for (int i = 0; i < effectCount; i++) {
                CombinedVibrationEffect effect = mEffects.get(i);
                long duration = effect.getDuration();
                if (duration == Long.MAX_VALUE) {
                    // If any duration is repeating, this combination duration is also repeating.
                    return duration;
                }
                durations += duration;
                // If any step is unknown, this combination duration will also be unknown, unless
                // any step is repeating. Repeating vibrations take precedence over non-repeating
                // ones in the service, so continue looping to check for repeating steps.
                hasUnknownStep |= duration < 0;
            }
            if (hasUnknownStep) {
                // If any step is unknown, this combination duration is also unknown.
                return -1;
            }
            long delays = 0;
            for (int i = 0; i < effectCount; i++) {
                delays += mDelays.get(i);
            }
            return durations + delays;
        }

        /** @hide */
        @Override
        public void validate() {
            Preconditions.checkArgument(mEffects.size() > 0,
                    "There should be at least one effect set for a combined effect");
            Preconditions.checkArgument(mEffects.size() == mDelays.size(),
                    "Effect and delays should have equal length");
            final int effectCount = mEffects.size();
            for (int i = 0; i < effectCount; i++) {
                if (mDelays.get(i) < 0) {
                    throw new IllegalArgumentException("Delays must all be >= 0"
                            + " (delays=" + mDelays + ")");
                }
            }
            for (int i = 0; i < effectCount; i++) {
                CombinedVibrationEffect effect = mEffects.get(i);
                if (effect instanceof Sequential) {
                    throw new IllegalArgumentException(
                            "There should be no nested sequential effects in a combined effect");
                }
                effect.validate();
            }
        }

        /** @hide */
        @Override
        public boolean hasVibrator(int vibratorId) {
            final int effectCount = mEffects.size();
            for (int i = 0; i < effectCount; i++) {
                if (mEffects.get(i).hasVibrator(vibratorId)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Sequential)) {
                return false;
            }
            Sequential other = (Sequential) o;
            return mDelays.equals(other.mDelays) && mEffects.equals(other.mEffects);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mEffects, mDelays);
        }

        @Override
        public String toString() {
            return "Sequential{mEffects=" + mEffects + ", mDelays=" + mDelays + '}';
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel out, int flags) {
            out.writeInt(PARCEL_TOKEN_SEQUENTIAL);
            out.writeInt(mEffects.size());
            for (int i = 0; i < mEffects.size(); i++) {
                out.writeInt(mDelays.get(i));
                mEffects.get(i).writeToParcel(out, flags);
            }
        }

        @NonNull
        public static final Parcelable.Creator<Sequential> CREATOR =
                new Parcelable.Creator<Sequential>() {
                    @Override
                    public Sequential createFromParcel(@NonNull Parcel in) {
                        // Skip the type token
                        in.readInt();
                        return new Sequential(in);
                    }

                    @Override
                    @NonNull
                    public Sequential[] newArray(int size) {
                        return new Sequential[size];
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
                        return new Mono(in);
                    } else if (token == PARCEL_TOKEN_STEREO) {
                        return new Stereo(in);
                    } else if (token == PARCEL_TOKEN_SEQUENTIAL) {
                        return new Sequential(in);
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
