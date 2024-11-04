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
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.os.vibrator.Flags;
import android.util.SparseArray;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.function.Function;

/**
 * A CombinedVibration describes a combination of haptic effects to be performed by one or more
 * {@link Vibrator Vibrators}.
 *
 * These effects may be any number of things, from single shot vibrations to complex waveforms.
 *
 * @see VibrationEffect
 */
@SuppressWarnings({"ParcelNotFinal", "ParcelCreator"}) // Parcel only extended here.
public abstract class CombinedVibration implements Parcelable {
    private static final int PARCEL_TOKEN_MONO = 1;
    private static final int PARCEL_TOKEN_STEREO = 2;
    private static final int PARCEL_TOKEN_SEQUENTIAL = 3;

    /** Prevent subclassing from outside of the framework. */
    CombinedVibration() {
    }

    /**
     * Create a vibration that plays a single effect in parallel on all vibrators.
     *
     * A parallel vibration that takes a single {@link VibrationEffect} to be performed by multiple
     * vibrators at the same time.
     *
     * @param effect The {@link VibrationEffect} to perform.
     * @return The combined vibration representing the single effect to be played in all vibrators.
     */
    @NonNull
    public static CombinedVibration createParallel(@NonNull VibrationEffect effect) {
        CombinedVibration combined = new Mono(effect);
        combined.validate();
        return combined;
    }

    /**
     * Start creating a vibration that plays effects in parallel on one or more vibrators.
     *
     * A parallel vibration takes one or more {@link VibrationEffect VibrationEffects} associated to
     * individual vibrators to be performed at the same time.
     *
     * @see CombinedVibration.ParallelCombination
     */
    @NonNull
    public static ParallelCombination startParallel() {
        return new ParallelCombination();
    }

    /**
     * Start creating a vibration that plays effects in sequence on one or more vibrators.
     *
     * A sequential vibration takes one or more {@link CombinedVibration CombinedVibrations} to be
     * performed by one or more vibrators in order. Each {@link CombinedVibration} starts only after
     * the previous one is finished.
     *
     * @hide
     * @see CombinedVibration.SequentialCombination
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
     * <p>For parallel combinations this means the maximum duration of any individual {@link
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

    /**
     * Gets the estimated duration of the combined vibration in milliseconds.
     *
     * <p>For effects with hardware-dependent constants (e.g. primitive compositions), this returns
     * the estimated duration based on the {@link VibratorInfo}. For all other effects this will
     * return the same as {@link #getDuration()}.
     *
     * @hide
     */
    public abstract long getDuration(@Nullable SparseArray<VibratorInfo> vibratorInfos);

    /**
     * Returns true if this effect could represent a touch haptic feedback.
     *
     * <p>It is strongly recommended that an instance of {@link VibrationAttributes} is specified
     * for each vibration, with the correct usage. When a vibration is played with usage UNKNOWN,
     * then this method will be used to classify the most common use case and make sure they are
     * covered by the user settings for "Touch feedback".
     *
     * @hide
     */
    public boolean isHapticFeedbackCandidate() {
        return false;
    }

    /** @hide */
    public abstract void validate();

    /**
     * Applies given effect transformation with a fixed parameter to each effect in this vibration.
     *
     * @param transformation The vibration effect transformation to be applied to all effects
     * @param param          The fixed parameter to be applied in all effect transformations
     * @return the result of running the given transformation on all effects of this vibration
     * @hide
     */
    public abstract <ParamT> CombinedVibration transform(
            VibrationEffect.Transformation<ParamT> transformation, ParamT param);

    /**
     * Applies given vibrator adapter to each effect in this combined vibration.
     *
     * @param adapter The vibrator adapter to be used on this vibration
     * @return the result of running the given adapter on all effects of this vibration
     * @hide
     */
    public abstract CombinedVibration adapt(VibratorAdapter adapter);

    /** @hide */
    public abstract boolean hasVibrator(int vibratorId);

    /** @hide */
    public abstract boolean hasVendorEffects();

    /**
     * Returns a compact version of the {@link #toString()} result for debugging purposes.
     *
     * @hide
     */
    public abstract String toDebugString();

    /**
     * Adapts a {@link VibrationEffect} to a specific device vibrator using the ID.
     *
     * <p>This can be used for adapting effects to the capabilities of the specific device vibrator
     * it's been mapped to by the combined vibration.
     *
     * @hide
     */
    public interface VibratorAdapter {

        /**
         * Return the list of vibrator IDs available on the device, to be used by {@link
         * CombinedVibration} to fan-out individual effects that aren't assigned to a specific
         * vibrator.
         */
        int[] getAvailableVibratorIds();

        /** Adapts a {@link VibrationEffect} to a given vibrator. */
        VibrationEffect adaptToVibrator(int vibratorId, @NonNull VibrationEffect effect);
    }

    /**
     * A combination of haptic effects that should be played in multiple vibrators in parallel.
     *
     * @see CombinedVibration#startParallel()
     */
    public static final class ParallelCombination {

        private final SparseArray<VibrationEffect> mEffects = new SparseArray<>();

        ParallelCombination() {
        }

        /**
         * Add or replace a one shot vibration effect to be performed by the specified vibrator.
         *
         * @param vibratorId The id of the vibrator that should perform this effect.
         * @param effect     The effect this vibrator should play.
         * @return The {@link ParallelCombination} object to enable adding
         * multiple effects in one chain.
         * @see VibrationEffect#createOneShot(long, int)
         */
        @NonNull
        public ParallelCombination addVibrator(int vibratorId, @NonNull VibrationEffect effect) {
            mEffects.put(vibratorId, effect);
            return this;
        }

        /**
         * Combine all of the added effects into a {@link CombinedVibration}.
         *
         * The {@link ParallelCombination} object is still valid after this
         * call, so you can continue adding more effects to it and generating more
         * {@link CombinedVibration}s by calling this method again.
         *
         * @return The {@link CombinedVibration} resulting from combining the added effects to
         * be played in parallel.
         */
        @NonNull
        public CombinedVibration combine() {
            if (mEffects.size() == 0) {
                throw new IllegalStateException(
                        "Combination must have at least one element to combine.");
            }
            CombinedVibration combined = new Stereo(mEffects);
            combined.validate();
            return combined;
        }
    }

    /**
     * A combination of haptic effects that should be played in multiple vibrators in sequence.
     *
     * @hide
     * @see CombinedVibration#startSequential()
     */
    @TestApi
    public static final class SequentialCombination {

        private final ArrayList<CombinedVibration> mEffects = new ArrayList<>();
        private final ArrayList<Integer> mDelays = new ArrayList<>();

        SequentialCombination() {
        }

        /**
         * Add a single vibration effect to be performed next.
         *
         * Similar to {@link #addNext(int, VibrationEffect, int)}, but with no delay. The effect
         * will start playing immediately after the previous vibration is finished.
         *
         * @param vibratorId The id of the vibrator that should perform this effect.
         * @param effect     The effect this vibrator should play.
         * @return The {@link CombinedVibration.SequentialCombination} object to enable adding
         * multiple effects in one chain.
         */
        @NonNull
        public SequentialCombination addNext(int vibratorId, @NonNull VibrationEffect effect) {
            return addNext(vibratorId, effect, /* delay= */ 0);
        }

        /**
         * Add a single vibration effect to be performed next.
         *
         * The delay is applied immediately after the previous vibration is finished. The effect
         * will start playing after the delay.
         *
         * @param vibratorId The id of the vibrator that should perform this effect.
         * @param effect     The effect this vibrator should play.
         * @param delay      The amount of time, in milliseconds, to wait between playing the prior
         *                   vibration and this one, starting at the time the previous vibration in
         *                   this sequence is finished.
         * @return The {@link CombinedVibration.SequentialCombination} object to enable adding
         * multiple effects in one chain.
         */
        @NonNull
        public SequentialCombination addNext(int vibratorId, @NonNull VibrationEffect effect,
                int delay) {
            return addNext(
                    CombinedVibration.startParallel().addVibrator(vibratorId, effect).combine(),
                    delay);
        }

        /**
         * Add a combined vibration effect to be performed next.
         *
         * Similar to {@link #addNext(CombinedVibration, int)}, but with no delay. The effect will
         * start playing immediately after the previous vibration is finished.
         *
         * @param effect The combined effect to be performed next.
         * @return The {@link CombinedVibration.SequentialCombination} object to enable adding
         * multiple effects in one chain.
         * @see VibrationEffect#createOneShot(long, int)
         */
        @NonNull
        public SequentialCombination addNext(@NonNull CombinedVibration effect) {
            return addNext(effect, /* delay= */ 0);
        }

        /**
         * Add a combined vibration effect to be performed next.
         *
         * The delay is applied immediately after the previous vibration is finished. The vibration
         * will start playing after the delay.
         *
         * @param effect The combined effect to be performed next.
         * @param delay  The amount of time, in milliseconds, to wait between playing the prior
         *               vibration and this one, starting at the time the previous vibration in this
         *               sequence is finished.
         * @return The {@link CombinedVibration.SequentialCombination} object to enable adding
         * multiple effects in one chain.
         */
        @NonNull
        public SequentialCombination addNext(@NonNull CombinedVibration effect, int delay) {
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
         * The {@link CombinedVibration.SequentialCombination} object is still valid after
         * this call, so you can continue adding more effects to it and generating more {@link
         * CombinedVibration}s by calling this method again.
         *
         * @return The {@link CombinedVibration} resulting from combining the added effects to
         * be played in sequence.
         */
        @NonNull
        public CombinedVibration combine() {
            if (mEffects.size() == 0) {
                throw new IllegalStateException(
                        "Combination must have at least one element to combine.");
            }
            CombinedVibration combined = new Sequential(mEffects, mDelays);
            combined.validate();
            return combined;
        }
    }

    /**
     * Represents a single {@link VibrationEffect} that should be played in all vibrators at the
     * same time.
     *
     * @hide
     */
    @TestApi
    public static final class Mono extends CombinedVibration {
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
        public long getDuration(@Nullable SparseArray<VibratorInfo> vibratorInfos) {
            if (vibratorInfos == null) {
                return getDuration();
            }
            long maxDuration = 0;
            for (int i = 0; i < vibratorInfos.size(); i++) {
                long duration = mEffect.getDuration(vibratorInfos.valueAt(i));
                if ((duration == Long.MAX_VALUE) || (duration < 0)) {
                    return duration;
                }
                maxDuration = Math.max(maxDuration, duration);
            }
            return maxDuration;
        }

        /** @hide */
        @Override
        public boolean isHapticFeedbackCandidate() {
            return mEffect.isHapticFeedbackCandidate();
        }

        /** @hide */
        @Override
        public void validate() {
            mEffect.validate();
        }

        /** @hide */
        @Override
        public <ParamT> CombinedVibration transform(
                VibrationEffect.Transformation<ParamT> transformation, ParamT param) {
            VibrationEffect newEffect = transformation.transform(mEffect, param);
            if (mEffect.equals(newEffect)) {
                return this;
            }
            // Make sure the validate methods are triggered
            return CombinedVibration.createParallel(newEffect);
        }

        /** @hide */
        @Override
        public CombinedVibration adapt(VibratorAdapter adapter) {
            ParallelCombination combination = CombinedVibration.startParallel();
            boolean hasSameEffects = true;
            for (int vibratorId : adapter.getAvailableVibratorIds()) {
                VibrationEffect newEffect = adapter.adaptToVibrator(vibratorId, mEffect);
                if (newEffect == null) {
                    // The vibration effect contains unsupported segments and cannot be played.
                    return null;
                }
                combination.addVibrator(vibratorId, newEffect);
                hasSameEffects &= mEffect.equals(newEffect);
            }
            if (hasSameEffects) {
                return this;
            }
            // Make sure the validate methods are triggered
            return combination.combine();
        }

        /** @hide */
        @Override
        public boolean hasVibrator(int vibratorId) {
            return true;
        }

        /** @hide */
        @Override
        public boolean hasVendorEffects() {
            if (!Flags.vendorVibrationEffects()) {
                return false;
            }
            return mEffect instanceof VibrationEffect.VendorEffect;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
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

        /** @hide */
        @Override
        public String toDebugString() {
            // Simplify vibration string, use the single effect to represent it.
            return mEffect.toDebugString();
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
     * Represents a set of {@link VibrationEffect VibrationEffects} associated to individual
     * vibrators that should be played at the same time.
     *
     * @hide
     */
    @TestApi
    public static final class Stereo extends CombinedVibration {

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

        /** Effects to be performed in parallel, where each key represents the vibrator id. */
        @NonNull
        public SparseArray<VibrationEffect> getEffects() {
            return mEffects;
        }

        @Override
        public long getDuration() {
            return getDuration(idx -> mEffects.valueAt(idx).getDuration());
        }

        /** @hide */
        @Override
        public long getDuration(@Nullable SparseArray<VibratorInfo> vibratorInfos) {
            if (vibratorInfos == null) {
                return getDuration();
            }
            return getDuration(idx -> {
                VibrationEffect effect = mEffects.valueAt(idx);
                VibratorInfo info = vibratorInfos.get(mEffects.keyAt(idx));
                return effect.getDuration(info);
            });
        }

        private long getDuration(Function<Integer, Long> durationFn) {
            long maxDuration = Long.MIN_VALUE;
            boolean hasUnknownStep = false;
            for (int i = 0; i < mEffects.size(); i++) {
                long duration = durationFn.apply(i);
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
        public boolean isHapticFeedbackCandidate() {
            for (int i = 0; i < mEffects.size(); i++) {
                if (!mEffects.valueAt(i).isHapticFeedbackCandidate()) {
                    return false;
                }
            }
            return true;
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
        public <ParamT> CombinedVibration transform(
                VibrationEffect.Transformation<ParamT> transformation, ParamT param) {
            ParallelCombination combination = CombinedVibration.startParallel();
            boolean hasSameEffects = true;
            for (int i = 0; i < mEffects.size(); i++) {
                int vibratorId = mEffects.keyAt(i);
                VibrationEffect effect = mEffects.valueAt(i);
                VibrationEffect newEffect = transformation.transform(effect, param);
                combination.addVibrator(vibratorId, newEffect);
                hasSameEffects &= effect.equals(newEffect);
            }
            if (hasSameEffects) {
                return this;
            }
            // Make sure the validate methods are triggered
            return combination.combine();
        }

        /** @hide */
        @Override
        public CombinedVibration adapt(VibratorAdapter adapter) {
            ParallelCombination combination = CombinedVibration.startParallel();
            boolean hasSameEffects = true;
            for (int i = 0; i < mEffects.size(); i++) {
                int vibratorId = mEffects.keyAt(i);
                VibrationEffect effect = mEffects.valueAt(i);
                VibrationEffect newEffect = adapter.adaptToVibrator(vibratorId, effect);
                if (newEffect == null) {
                    // The vibration effect contains unsupported segments and cannot be played.
                    return null;
                }
                combination.addVibrator(vibratorId, newEffect);
                hasSameEffects &= effect.equals(newEffect);
            }
            if (hasSameEffects) {
                return this;
            }
            // Make sure the validate methods are triggered
            return combination.combine();
        }

        /** @hide */
        @Override
        public boolean hasVibrator(int vibratorId) {
            return mEffects.indexOfKey(vibratorId) >= 0;
        }

        /** @hide */
        @Override
        public boolean hasVendorEffects() {
            if (!Flags.vendorVibrationEffects()) {
                return false;
            }
            for (int i = 0; i < mEffects.size(); i++) {
                if (mEffects.get(i) instanceof VibrationEffect.VendorEffect) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
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

        /** @hide */
        @Override
        public String toDebugString() {
            StringJoiner sj = new StringJoiner(",", "Stereo{", "}");
            for (int i = 0; i < mEffects.size(); i++) {
                sj.add(String.format(Locale.ROOT, "vibrator(id=%d): %s",
                        mEffects.keyAt(i), mEffects.valueAt(i).toDebugString()));
            }
            return sj.toString();
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
     * Represents a list of {@link CombinedVibration CombinedVibrations} that should be played in
     * sequence.
     *
     * @hide
     */
    @TestApi
    public static final class Sequential extends CombinedVibration {
        // If a vibration is playing more than 3 effects, it's probably not haptic feedback
        private static final long MAX_HAPTIC_FEEDBACK_SEQUENCE_SIZE = 3;

        private final List<CombinedVibration> mEffects;
        private final List<Integer> mDelays;

        Sequential(Parcel in) {
            int size = in.readInt();
            mEffects = new ArrayList<>(size);
            mDelays = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                mDelays.add(in.readInt());
                mEffects.add(CombinedVibration.CREATOR.createFromParcel(in));
            }
        }

        Sequential(@NonNull List<CombinedVibration> effects,
                @NonNull List<Integer> delays) {
            mEffects = new ArrayList<>(effects);
            mDelays = new ArrayList<>(delays);
        }

        /** Effects to be performed in sequence. */
        @NonNull
        public List<CombinedVibration> getEffects() {
            return mEffects;
        }

        /** Delay to be applied before each effect in {@link #getEffects()}. */
        @NonNull
        public List<Integer> getDelays() {
            return mDelays;
        }

        @Override
        public long getDuration() {
            return getDuration(CombinedVibration::getDuration);
        }

        /** @hide */
        @Override
        public long getDuration(@Nullable SparseArray<VibratorInfo> vibratorInfos) {
            return getDuration(effect -> effect.getDuration(vibratorInfos));
        }

        private long getDuration(Function<CombinedVibration, Long> durationFn) {
            boolean hasUnknownStep = false;
            long durations = 0;
            final int effectCount = mEffects.size();
            for (int i = 0; i < effectCount; i++) {
                long duration = durationFn.apply(mEffects.get(i));
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
        public boolean isHapticFeedbackCandidate() {
            final int effectCount = mEffects.size();
            if (effectCount > MAX_HAPTIC_FEEDBACK_SEQUENCE_SIZE) {
                return false;
            }
            for (int i = 0; i < effectCount; i++) {
                if (!mEffects.get(i).isHapticFeedbackCandidate()) {
                    return false;
                }
            }
            return true;
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
                CombinedVibration effect = mEffects.get(i);
                if (effect instanceof Sequential) {
                    throw new IllegalArgumentException(
                            "There should be no nested sequential effects in a combined effect");
                }
                effect.validate();
            }
        }

        /** @hide */
        @Override
        public <ParamT> CombinedVibration transform(
                VibrationEffect.Transformation<ParamT> transformation, ParamT param) {
            SequentialCombination combination = CombinedVibration.startSequential();
            boolean hasSameEffects = true;
            for (int i = 0; i < mEffects.size(); i++) {
                CombinedVibration vibration = mEffects.get(i);
                CombinedVibration newVibration = vibration.transform(transformation, param);
                combination.addNext(newVibration, mDelays.get(i));
                hasSameEffects &= vibration.equals(newVibration);
            }
            if (hasSameEffects) {
                return this;
            }
            // Make sure the validate methods are triggered
            return combination.combine();
        }

        /** @hide */
        @Override
        public CombinedVibration adapt(VibratorAdapter adapter) {
            SequentialCombination combination = CombinedVibration.startSequential();
            boolean hasSameEffects = true;
            for (int i = 0; i < mEffects.size(); i++) {
                CombinedVibration vibration = mEffects.get(i);
                CombinedVibration newVibration = vibration.adapt(adapter);
                combination.addNext(newVibration, mDelays.get(i));
                hasSameEffects &= vibration.equals(newVibration);
            }
            if (hasSameEffects) {
                return this;
            }
            // Make sure the validate methods are triggered
            return combination.combine();
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

        /** @hide */
        @Override
        public boolean hasVendorEffects() {
            for (int i = 0; i < mEffects.size(); i++) {
                if (mEffects.get(i).hasVendorEffects()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
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

        /** @hide */
        @Override
        public String toDebugString() {
            StringJoiner sj = new StringJoiner(",", "Sequential{", "}");
            for (int i = 0; i < mEffects.size(); i++) {
                sj.add(String.format(Locale.ROOT, "delayMs=%d, effect=%s",
                        mDelays.get(i), mEffects.get(i).toDebugString()));
            }
            return sj.toString();
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
    public static final Parcelable.Creator<CombinedVibration> CREATOR =
            new Parcelable.Creator<CombinedVibration>() {
                @Override
                public CombinedVibration createFromParcel(Parcel in) {
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
                public CombinedVibration[] newArray(int size) {
                    return new CombinedVibration[size];
                }
            };
}
