/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ContentResolver;
import android.content.Context;
import android.hardware.vibrator.V1_0.EffectStrength;
import android.hardware.vibrator.V1_3.Effect;
import android.net.Uri;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.RampSegment;
import android.os.vibrator.StepSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.util.MathUtils;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A VibrationEffect describes a haptic effect to be performed by a {@link Vibrator}.
 *
 * These effects may be any number of things, from single shot vibrations to complex waveforms.
 */
public abstract class VibrationEffect implements Parcelable {
    // Stevens' coefficient to scale the perceived vibration intensity.
    private static final float SCALE_GAMMA = 0.65f;


    /**
     * The default vibration strength of the device.
     */
    public static final int DEFAULT_AMPLITUDE = -1;

    /**
     * The maximum amplitude value
     * @hide
     */
    public static final int MAX_AMPLITUDE = 255;

    /**
     * A click effect. Use this effect as a baseline, as it's the most common type of click effect.
     */
    public static final int EFFECT_CLICK = Effect.CLICK;

    /**
     * A double click effect.
     */
    public static final int EFFECT_DOUBLE_CLICK = Effect.DOUBLE_CLICK;

    /**
     * A tick effect. This effect is less strong compared to {@link #EFFECT_CLICK}.
     */
    public static final int EFFECT_TICK = Effect.TICK;

    /**
     * A thud effect.
     * @see #get(int)
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @TestApi
    public static final int EFFECT_THUD = Effect.THUD;

    /**
     * A pop effect.
     * @see #get(int)
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @TestApi
    public static final int EFFECT_POP = Effect.POP;

    /**
     * A heavy click effect. This effect is stronger than {@link #EFFECT_CLICK}.
     */
    public static final int EFFECT_HEAVY_CLICK = Effect.HEAVY_CLICK;

    /**
     * A texture effect meant to replicate soft ticks.
     *
     * Unlike normal effects, texture effects are meant to be called repeatedly, generally in
     * response to some motion, in order to replicate the feeling of some texture underneath the
     * user's fingers.
     *
     * @see #get(int)
     * @hide
     */
    @TestApi
    public static final int EFFECT_TEXTURE_TICK = Effect.TEXTURE_TICK;

    /** {@hide} */
    @TestApi
    public static final int EFFECT_STRENGTH_LIGHT = EffectStrength.LIGHT;

    /** {@hide} */
    @TestApi
    public static final int EFFECT_STRENGTH_MEDIUM = EffectStrength.MEDIUM;

    /** {@hide} */
    @TestApi
    public static final int EFFECT_STRENGTH_STRONG = EffectStrength.STRONG;

    /**
     * Ringtone patterns. They may correspond with the device's ringtone audio, or may just be a
     * pattern that can be played as a ringtone with any audio, depending on the device.
     *
     * @see #get(Uri, Context)
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @TestApi
    public static final int[] RINGTONES = {
        Effect.RINGTONE_1,
        Effect.RINGTONE_2,
        Effect.RINGTONE_3,
        Effect.RINGTONE_4,
        Effect.RINGTONE_5,
        Effect.RINGTONE_6,
        Effect.RINGTONE_7,
        Effect.RINGTONE_8,
        Effect.RINGTONE_9,
        Effect.RINGTONE_10,
        Effect.RINGTONE_11,
        Effect.RINGTONE_12,
        Effect.RINGTONE_13,
        Effect.RINGTONE_14,
        Effect.RINGTONE_15
    };

    /** @hide */
    @IntDef(prefix = { "EFFECT_" }, value = {
            EFFECT_TICK,
            EFFECT_CLICK,
            EFFECT_HEAVY_CLICK,
            EFFECT_DOUBLE_CLICK,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EffectType {}

    /** @hide to prevent subclassing from outside of the framework */
    public VibrationEffect() { }

    /**
     * Create a one shot vibration.
     *
     * One shot vibrations will vibrate constantly for the specified period of time at the
     * specified amplitude, and then stop.
     *
     * @param milliseconds The number of milliseconds to vibrate. This must be a positive number.
     * @param amplitude The strength of the vibration. This must be a value between 1 and 255, or
     * {@link #DEFAULT_AMPLITUDE}.
     *
     * @return The desired effect.
     */
    public static VibrationEffect createOneShot(long milliseconds, int amplitude) {
        return createWaveform(new long[]{milliseconds}, new int[]{amplitude}, -1 /* repeat */);
    }

    /**
     * Create a waveform vibration.
     *
     * Waveform vibrations are a potentially repeating series of timing and amplitude pairs. For
     * each pair, the value in the amplitude array determines the strength of the vibration and the
     * value in the timing array determines how long it vibrates for. An amplitude of 0 implies no
     * vibration (i.e. off), and any pairs with a timing value of 0 will be ignored.
     * <p>
     * The amplitude array of the generated waveform will be the same size as the given
     * timing array with alternating values of 0 (i.e. off) and {@link #DEFAULT_AMPLITUDE},
     * starting with 0. Therefore the first timing value will be the period to wait before turning
     * the vibrator on, the second value will be how long to vibrate at {@link #DEFAULT_AMPLITUDE}
     * strength, etc.
     * </p><p>
     * To cause the pattern to repeat, pass the index into the timings array at which to start the
     * repetition, or -1 to disable repeating.
     * </p>
     *
     * @param timings The pattern of alternating on-off timings, starting with off. Timing values
     *                of 0 will cause the timing / amplitude pair to be ignored.
     * @param repeat The index into the timings array at which to repeat, or -1 if you you don't
     *               want to repeat.
     *
     * @return The desired effect.
     */
    public static VibrationEffect createWaveform(long[] timings, int repeat) {
        int[] amplitudes = new int[timings.length];
        for (int i = 0; i < (timings.length / 2); i++) {
            amplitudes[i*2 + 1] = VibrationEffect.DEFAULT_AMPLITUDE;
        }
        return createWaveform(timings, amplitudes, repeat);
    }

    /**
     * Create a waveform vibration.
     *
     * Waveform vibrations are a potentially repeating series of timing and amplitude pairs. For
     * each pair, the value in the amplitude array determines the strength of the vibration and the
     * value in the timing array determines how long it vibrates for, in milliseconds. Amplitude
     * values must be between 0 and 255, and an amplitude of 0 implies no vibration (i.e. off). Any
     * pairs with a timing value of 0 will be ignored.
     * </p><p>
     * To cause the pattern to repeat, pass the index into the timings array at which to start the
     * repetition, or -1 to disable repeating.
     * </p>
     *
     * @param timings The timing values, in milliseconds, of the timing / amplitude pairs. Timing
     *                values of 0 will cause the pair to be ignored.
     * @param amplitudes The amplitude values of the timing / amplitude pairs. Amplitude values
     *                   must be between 0 and 255, or equal to {@link #DEFAULT_AMPLITUDE}. An
     *                   amplitude value of 0 implies the motor is off.
     * @param repeat The index into the timings array at which to repeat, or -1 if you you don't
     *               want to repeat.
     *
     * @return The desired effect.
     */
    public static VibrationEffect createWaveform(long[] timings, int[] amplitudes, int repeat) {
        if (timings.length != amplitudes.length) {
            throw new IllegalArgumentException(
                    "timing and amplitude arrays must be of equal length"
                            + " (timings.length=" + timings.length
                            + ", amplitudes.length=" + amplitudes.length + ")");
        }
        List<StepSegment> segments = new ArrayList<>();
        for (int i = 0; i < timings.length; i++) {
            float parsedAmplitude = amplitudes[i] == DEFAULT_AMPLITUDE
                    ? DEFAULT_AMPLITUDE : (float) amplitudes[i] / MAX_AMPLITUDE;
            segments.add(new StepSegment(parsedAmplitude, /* frequency= */ 0, (int) timings[i]));
        }
        VibrationEffect effect = new Composed(segments, repeat);
        effect.validate();
        return effect;
    }

    /**
     * Create a predefined vibration effect.
     *
     * Predefined effects are a set of common vibration effects that should be identical, regardless
     * of the app they come from, in order to provide a cohesive experience for users across
     * the entire device. They also may be custom tailored to the device hardware in order to
     * provide a better experience than you could otherwise build using the generic building
     * blocks.
     *
     * This will fallback to a generic pattern if one exists and there does not exist a
     * hardware-specific implementation of the effect.
     *
     * @param effectId The ID of the effect to perform:
     *                 {@link #EFFECT_CLICK}, {@link #EFFECT_DOUBLE_CLICK}, {@link #EFFECT_TICK}
     *
     * @return The desired effect.
     */
    @NonNull
    public static VibrationEffect createPredefined(@EffectType int effectId) {
        return get(effectId, true);
    }

    /**
     * Get a predefined vibration effect.
     *
     * Predefined effects are a set of common vibration effects that should be identical, regardless
     * of the app they come from, in order to provide a cohesive experience for users across
     * the entire device. They also may be custom tailored to the device hardware in order to
     * provide a better experience than you could otherwise build using the generic building
     * blocks.
     *
     * This will fallback to a generic pattern if one exists and there does not exist a
     * hardware-specific implementation of the effect.
     *
     * @param effectId The ID of the effect to perform:
     *                 {@link #EFFECT_CLICK}, {@link #EFFECT_DOUBLE_CLICK}, {@link #EFFECT_TICK}
     *
     * @return The desired effect.
     * @hide
     */
    @TestApi
    public static VibrationEffect get(int effectId) {
        return get(effectId, true);
    }

    /**
     * Get a predefined vibration effect.
     *
     * Predefined effects are a set of common vibration effects that should be identical, regardless
     * of the app they come from, in order to provide a cohesive experience for users across
     * the entire device. They also may be custom tailored to the device hardware in order to
     * provide a better experience than you could otherwise build using the generic building
     * blocks.
     *
     * Some effects you may only want to play if there's a hardware specific implementation because
     * they may, for example, be too disruptive to the user without tuning. The {@code fallback}
     * parameter allows you to decide whether you want to fallback to the generic implementation or
     * only play if there's a tuned, hardware specific one available.
     *
     * @param effectId The ID of the effect to perform:
     *                 {@link #EFFECT_CLICK}, {@link #EFFECT_DOUBLE_CLICK}, {@link #EFFECT_TICK}
     * @param fallback Whether to fallback to a generic pattern if a hardware specific
     *                 implementation doesn't exist.
     *
     * @return The desired effect.
     * @hide
     */
    @TestApi
    public static VibrationEffect get(int effectId, boolean fallback) {
        VibrationEffect effect = new Composed(
                new PrebakedSegment(effectId, fallback, EffectStrength.MEDIUM));
        effect.validate();
        return effect;
    }

    /**
     * Get a predefined vibration effect associated with a given URI.
     *
     * Predefined effects are a set of common vibration effects that should be identical, regardless
     * of the app they come from, in order to provide a cohesive experience for users across
     * the entire device. They also may be custom tailored to the device hardware in order to
     * provide a better experience than you could otherwise build using the generic building
     * blocks.
     *
     * @param uri The URI associated with the haptic effect.
     * @param context The context used to get the URI to haptic effect association.
     *
     * @return The desired effect, or {@code null} if there's no associated effect.
     *
     * @hide
     */
    @TestApi
    @Nullable
    public static VibrationEffect get(Uri uri, Context context) {
        String[] uris = context.getResources().getStringArray(
                com.android.internal.R.array.config_ringtoneEffectUris);

        // Skip doing any IPC if we don't have any effects configured.
        if (uris.length == 0) {
            return null;
        }

        final ContentResolver cr = context.getContentResolver();
        Uri uncanonicalUri = cr.uncanonicalize(uri);
        if (uncanonicalUri == null) {
            // If we already had an uncanonical URI, it's possible we'll get null back here. In
            // this case, just use the URI as passed in since it wasn't canonicalized in the first
            // place.
            uncanonicalUri = uri;
        }

        for (int i = 0; i < uris.length && i < RINGTONES.length; i++) {
            if (uris[i] == null) {
                continue;
            }
            Uri mappedUri = cr.uncanonicalize(Uri.parse(uris[i]));
            if (mappedUri == null) {
                continue;
            }
            if (mappedUri.equals(uncanonicalUri)) {
                return get(RINGTONES[i]);
            }
        }
        return null;
    }

    /**
     * Start composing a haptic effect.
     *
     * @see VibrationEffect.Composition
     */
    @NonNull
    public static Composition startComposition() {
        return new Composition();
    }

    /**
     * Start building a waveform vibration.
     *
     * <p>The waveform builder offers more flexibility for creating waveform vibrations, allowing
     * control over vibration frequency and ramping up or down the vibration amplitude, frequency or
     * both.
     *
     * <p>For simpler waveform patterns see {@link #createWaveform} methods.
     *
     * @hide
     * @see VibrationEffect.WaveformBuilder
     */
    @TestApi
    @NonNull
    public static WaveformBuilder startWaveform() {
        return new WaveformBuilder();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    public abstract void validate();

    /**
     * Gets the estimated duration of the vibration in milliseconds.
     *
     * For effects without a defined end (e.g. a Waveform with a non-negative repeat index), this
     * returns Long.MAX_VALUE. For effects with an unknown duration (e.g. Prebaked effects where
     * the length is device and potentially run-time dependent), this returns -1.
     *
     * @hide
     */
    @TestApi
    public abstract long getDuration();

    /**
     * Resolve default values into integer amplitude numbers.
     *
     * @param defaultAmplitude the default amplitude to apply, must be between 0 and
     *                         MAX_AMPLITUDE
     * @return this if amplitude value is already set, or a copy of this effect with given default
     *         amplitude otherwise
     *
     * @hide
     */
    public abstract <T extends VibrationEffect> T resolve(int defaultAmplitude);

    /**
     * Scale the vibration effect intensity with the given constraints.
     *
     * @param scaleFactor scale factor to be applied to the intensity. Values within [0,1) will
     *                    scale down the intensity, values larger than 1 will scale up
     * @return this if there is no scaling to be done, or a copy of this effect with scaled
     *         vibration intensity otherwise
     *
     * @hide
     */
    public abstract <T extends VibrationEffect> T scale(float scaleFactor);

    /**
     * Applies given effect strength to prebaked effects represented by one of
     * VibrationEffect.EFFECT_*.
     *
     * @param effectStrength new effect strength to be applied, one of
     *                       VibrationEffect.EFFECT_STRENGTH_*.
     * @return this if there is no change to this effect, or a copy of this effect with applied
     * effect strength otherwise.
     * @hide
     */
    public <T extends VibrationEffect> T applyEffectStrength(int effectStrength) {
        return (T) this;
    }

    /**
     * Scale given vibration intensity by the given factor.
     *
     * @param intensity   relative intensity of the effect, must be between 0 and 1
     * @param scaleFactor scale factor to be applied to the intensity. Values within [0,1) will
     *                    scale down the intensity, values larger than 1 will scale up
     * @hide
     */
    public static float scale(float intensity, float scaleFactor) {
        // Applying gamma correction to the scale factor, which is the same as encoding the input
        // value, scaling it, then decoding the scaled value.
        float scale = MathUtils.pow(scaleFactor, 1f / SCALE_GAMMA);

        if (scaleFactor <= 1) {
            // Scale down is simply a gamma corrected application of scaleFactor to the intensity.
            // Scale up requires a different curve to ensure the intensity will not become > 1.
            return intensity * scale;
        }

        // Apply the scale factor a few more times to make the ramp curve closer to the raw scale.
        float extraScale = MathUtils.pow(scaleFactor, 4f - scaleFactor);
        float x = intensity * scale * extraScale;
        float maxX = scale * extraScale; // scaled x for intensity == 1

        float expX = MathUtils.exp(x);
        float expMaxX = MathUtils.exp(maxX);

        // Using f = tanh as the scale up function so the max value will converge.
        // a = 1/f(maxX), used to scale f so that a*f(maxX) = 1 (the value will converge to 1).
        float a = (expMaxX + 1f) / (expMaxX - 1f);
        float fx = (expX - 1f) / (expX + 1f);

        return MathUtils.constrain(a * fx, 0f, 1f);
    }

    /** @hide */
    public static String effectIdToString(int effectId) {
        switch (effectId) {
            case EFFECT_CLICK:
                return "CLICK";
            case EFFECT_TICK:
                return "TICK";
            case EFFECT_HEAVY_CLICK:
                return "HEAVY_CLICK";
            case EFFECT_DOUBLE_CLICK:
                return "DOUBLE_CLICK";
            case EFFECT_POP:
                return "POP";
            case EFFECT_THUD:
                return "THUD";
            case EFFECT_TEXTURE_TICK:
                return "TEXTURE_TICK";
            default:
                return Integer.toString(effectId);
        }
    }

    /** @hide */
    public static String effectStrengthToString(int effectStrength) {
        switch (effectStrength) {
            case EFFECT_STRENGTH_LIGHT:
                return "LIGHT";
            case EFFECT_STRENGTH_MEDIUM:
                return "MEDIUM";
            case EFFECT_STRENGTH_STRONG:
                return "STRONG";
            default:
                return Integer.toString(effectStrength);
        }
    }

    /**
     * Implementation of {@link VibrationEffect} described by a composition of one or more
     * {@link VibrationEffectSegment}, with an optional index to represent repeating effects.
     *
     * @hide
     */
    @TestApi
    public static final class Composed extends VibrationEffect {
        private final ArrayList<VibrationEffectSegment> mSegments;
        private final int mRepeatIndex;

        Composed(@NonNull Parcel in) {
            this(in.readArrayList(VibrationEffectSegment.class.getClassLoader()), in.readInt());
        }

        Composed(@NonNull VibrationEffectSegment segment) {
            this(Arrays.asList(segment), /* repeatIndex= */ -1);
        }

        /** @hide */
        public Composed(@NonNull List<? extends VibrationEffectSegment> segments, int repeatIndex) {
            super();
            mSegments = new ArrayList<>(segments);
            mRepeatIndex = repeatIndex;
        }

        @NonNull
        public List<VibrationEffectSegment> getSegments() {
            return mSegments;
        }

        public int getRepeatIndex() {
            return mRepeatIndex;
        }

        @Override
        public void validate() {
            int segmentCount = mSegments.size();
            boolean hasNonZeroDuration = false;
            boolean hasNonZeroAmplitude = false;
            for (int i = 0; i < segmentCount; i++) {
                VibrationEffectSegment segment = mSegments.get(i);
                segment.validate();
                // A segment with unknown duration = -1 still counts as a non-zero duration.
                hasNonZeroDuration |= segment.getDuration() != 0;
                hasNonZeroAmplitude |= segment.hasNonZeroAmplitude();
            }
            if (!hasNonZeroDuration) {
                throw new IllegalArgumentException("at least one timing must be non-zero"
                        + " (segments=" + mSegments + ")");
            }
            if (!hasNonZeroAmplitude) {
                throw new IllegalArgumentException("at least one amplitude must be non-zero"
                        + " (segments=" + mSegments + ")");
            }
            if (mRepeatIndex != -1) {
                Preconditions.checkArgumentInRange(mRepeatIndex, 0, segmentCount - 1,
                        "repeat index must be within the bounds of the segments (segments.length="
                                + segmentCount + ", index=" + mRepeatIndex + ")");
            }
        }

        @Override
        public long getDuration() {
            if (mRepeatIndex >= 0) {
                return Long.MAX_VALUE;
            }
            int segmentCount = mSegments.size();
            long totalDuration = 0;
            for (int i = 0; i < segmentCount; i++) {
                long segmentDuration = mSegments.get(i).getDuration();
                if (segmentDuration < 0) {
                    return segmentDuration;
                }
                totalDuration += segmentDuration;
            }
            return totalDuration;
        }

        @NonNull
        @Override
        public Composed resolve(int defaultAmplitude) {
            int segmentCount = mSegments.size();
            ArrayList<VibrationEffectSegment> resolvedSegments = new ArrayList<>(segmentCount);
            for (int i = 0; i < segmentCount; i++) {
                resolvedSegments.add(mSegments.get(i).resolve(defaultAmplitude));
            }
            if (resolvedSegments.equals(mSegments)) {
                return this;
            }
            Composed resolved = new Composed(resolvedSegments, mRepeatIndex);
            resolved.validate();
            return resolved;
        }

        @NonNull
        @Override
        public Composed scale(float scaleFactor) {
            int segmentCount = mSegments.size();
            ArrayList<VibrationEffectSegment> scaledSegments = new ArrayList<>(segmentCount);
            for (int i = 0; i < segmentCount; i++) {
                scaledSegments.add(mSegments.get(i).scale(scaleFactor));
            }
            if (scaledSegments.equals(mSegments)) {
                return this;
            }
            Composed scaled = new Composed(scaledSegments, mRepeatIndex);
            scaled.validate();
            return scaled;
        }

        @NonNull
        @Override
        public Composed applyEffectStrength(int effectStrength) {
            int segmentCount = mSegments.size();
            ArrayList<VibrationEffectSegment> scaledSegments = new ArrayList<>(segmentCount);
            for (int i = 0; i < segmentCount; i++) {
                scaledSegments.add(mSegments.get(i).applyEffectStrength(effectStrength));
            }
            if (scaledSegments.equals(mSegments)) {
                return this;
            }
            Composed scaled = new Composed(scaledSegments, mRepeatIndex);
            scaled.validate();
            return scaled;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (!(o instanceof Composed)) {
                return false;
            }
            Composed other = (Composed) o;
            return mSegments.equals(other.mSegments) && mRepeatIndex == other.mRepeatIndex;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mSegments, mRepeatIndex);
        }

        @Override
        public String toString() {
            return "Composed{segments=" + mSegments
                    + ", repeat=" + mRepeatIndex
                    + "}";
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel out, int flags) {
            out.writeList(mSegments);
            out.writeInt(mRepeatIndex);
        }

        @NonNull
        public static final Creator<Composed> CREATOR =
                new Creator<Composed>() {
                    @Override
                    public Composed createFromParcel(Parcel in) {
                        return new Composed(in);
                    }

                    @Override
                    public Composed[] newArray(int size) {
                        return new Composed[size];
                    }
                };
    }

    /**
     * A composition of haptic primitives that, when combined, create a single haptic effect.
     *
     * @see VibrationEffect#startComposition()
     */
    public static final class Composition {
        /** @hide */
        @IntDef(prefix = { "PRIMITIVE_" }, value = {
                PRIMITIVE_CLICK,
                PRIMITIVE_THUD,
                PRIMITIVE_SPIN,
                PRIMITIVE_QUICK_RISE,
                PRIMITIVE_SLOW_RISE,
                PRIMITIVE_QUICK_FALL,
                PRIMITIVE_TICK,
                PRIMITIVE_LOW_TICK,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface PrimitiveType {}

        /**
         * No haptic effect. Used to generate extended delays between primitives.
         * @hide
         */
        public static final int PRIMITIVE_NOOP = 0;
        /**
         * This effect should produce a sharp, crisp click sensation.
         */
        public static final int PRIMITIVE_CLICK = 1;
        /**
         * A haptic effect that simulates downwards movement with gravity. Often
         * followed by extra energy of hitting and reverberation to augment
         * physicality.
         *
         * @hide Not confident enough to expose publicly yet
         */
        public static final int PRIMITIVE_THUD = 2;
        /**
         * A haptic effect that simulates spinning momentum.
         *
         * @hide Not confident enough to expose publicly yet
         */
        public static final int PRIMITIVE_SPIN = 3;
        /**
         * A haptic effect that simulates quick upward movement against gravity.
         */
        public static final int PRIMITIVE_QUICK_RISE = 4;
        /**
         * A haptic effect that simulates slow upward movement against gravity.
         */
        public static final int PRIMITIVE_SLOW_RISE = 5;
        /**
         * A haptic effect that simulates quick downwards movement with gravity.
         */
        public static final int PRIMITIVE_QUICK_FALL = 6;
        /**
         * This very short effect should produce a light crisp sensation intended
         * to be used repetitively for dynamic feedback.
         */
        // Internally this maps to the HAL constant CompositePrimitive::LIGHT_TICK
        public static final int PRIMITIVE_TICK = 7;
        /**
         * This very short low frequency effect should produce a light crisp sensation
         * intended to be used repetitively for dynamic feedback.
         */
        // Internally this maps to the HAL constant CompositePrimitive::LOW_TICK
        public static final int PRIMITIVE_LOW_TICK = 8;


        private final ArrayList<VibrationEffectSegment> mSegments = new ArrayList<>();
        private int mRepeatIndex = -1;

        Composition() {}

        /**
         * Add a haptic effect to the end of the current composition.
         *
         * <p>Similar to {@link #addEffect(VibrationEffect, int)} , but with no delay applied.
         *
         * @param effect The effect to add to this composition as a primitive
         * @return The {@link Composition} object to enable adding multiple primitives in one chain.
         * @hide
         */
        @TestApi
        @NonNull
        public Composition addEffect(@NonNull VibrationEffect effect) {
            return addEffect(effect, /* delay= */ 0);
        }

        /**
         * Add a haptic effect to the end of the current composition.
         *
         * @param effect The effect to add to this composition as a primitive
         * @param delay  The amount of time in milliseconds to wait before playing this primitive
         * @return The {@link Composition} object to enable adding multiple primitives in one chain.
         * @hide
         */
        @TestApi
        @NonNull
        public Composition addEffect(@NonNull VibrationEffect effect,
                @IntRange(from = 0) int delay) {
            Preconditions.checkArgumentNonnegative(delay);
            if (delay > 0) {
                // Created a segment sustaining the zero amplitude to represent the delay.
                addSegment(new StepSegment(/* amplitude= */ 0, /* frequency= */ 0,
                        /* duration= */ delay));
            }
            return addSegments(effect);
        }

        /**
         * Add a haptic primitive to the end of the current composition.
         *
         * Similar to {@link #addPrimitive(int, float, int)}, but with no delay and a
         * default scale applied.
         *
         * @param primitiveId The primitive to add
         *
         * @return The {@link Composition} object to enable adding multiple primitives in one chain.
         */
        @NonNull
        public Composition addPrimitive(@PrimitiveType int primitiveId) {
            return addPrimitive(primitiveId, /*scale*/ 1.0f, /*delay*/ 0);
        }

        /**
         * Add a haptic primitive to the end of the current composition.
         *
         * Similar to {@link #addPrimitive(int, float, int)}, but with no delay.
         *
         * @param primitiveId The primitive to add
         * @param scale The scale to apply to the intensity of the primitive.
         *
         * @return The {@link Composition} object to enable adding multiple primitives in one chain.
         */
        @NonNull
        public Composition addPrimitive(@PrimitiveType int primitiveId,
                @FloatRange(from = 0f, to = 1f) float scale) {
            return addPrimitive(primitiveId, scale, /*delay*/ 0);
        }

        /**
         * Add a haptic primitive to the end of the current composition.
         *
         * @param primitiveId The primitive to add
         * @param scale The scale to apply to the intensity of the primitive.
         * @param delay The amount of time in milliseconds to wait before playing this primitive,
         *              starting at the time the previous element in this composition is finished.
         * @return The {@link Composition} object to enable adding multiple primitives in one chain.
         */
        @NonNull
        public Composition addPrimitive(@PrimitiveType int primitiveId,
                @FloatRange(from = 0f, to = 1f) float scale, @IntRange(from = 0) int delay) {
            PrimitiveSegment primitive = new PrimitiveSegment(primitiveId, scale,
                    delay);
            primitive.validate();
            return addSegment(primitive);
        }

        private Composition addSegment(VibrationEffectSegment segment) {
            if (mRepeatIndex >= 0) {
                throw new IllegalStateException(
                        "Composition already have a repeating effect so any new primitive would be"
                                + " unreachable.");
            }
            mSegments.add(segment);
            return this;
        }

        private Composition addSegments(VibrationEffect effect) {
            if (mRepeatIndex >= 0) {
                throw new IllegalStateException(
                        "Composition already have a repeating effect so any new primitive would be"
                                + " unreachable.");
            }
            Composed composed = (Composed) effect;
            if (composed.getRepeatIndex() >= 0) {
                // Start repeating from the index relative to the composed waveform.
                mRepeatIndex = mSegments.size() + composed.getRepeatIndex();
            }
            mSegments.addAll(composed.getSegments());
            return this;
        }

        /**
         * Compose all of the added primitives together into a single {@link VibrationEffect}.
         *
         * The {@link Composition} object is still valid after this call, so you can continue adding
         * more primitives to it and generating more {@link VibrationEffect}s by calling this method
         * again.
         *
         * @return The {@link VibrationEffect} resulting from the composition of the primitives.
         */
        @NonNull
        public VibrationEffect compose() {
            if (mSegments.isEmpty()) {
                throw new IllegalStateException(
                        "Composition must have at least one element to compose.");
            }
            VibrationEffect effect = new Composed(mSegments, mRepeatIndex);
            effect.validate();
            return effect;
        }

        /**
         * Convert the primitive ID to a human readable string for debugging
         * @param id The ID to convert
         * @return The ID in a human readable format.
         * @hide
         */
        public static String primitiveToString(@PrimitiveType int id) {
            switch (id) {
                case PRIMITIVE_NOOP:
                    return "PRIMITIVE_NOOP";
                case PRIMITIVE_CLICK:
                    return "PRIMITIVE_CLICK";
                case PRIMITIVE_THUD:
                    return "PRIMITIVE_THUD";
                case PRIMITIVE_SPIN:
                    return "PRIMITIVE_SPIN";
                case PRIMITIVE_QUICK_RISE:
                    return "PRIMITIVE_QUICK_RISE";
                case PRIMITIVE_SLOW_RISE:
                    return "PRIMITIVE_SLOW_RISE";
                case PRIMITIVE_QUICK_FALL:
                    return "PRIMITIVE_QUICK_FALL";
                case PRIMITIVE_TICK:
                    return "PRIMITIVE_TICK";
                case PRIMITIVE_LOW_TICK:
                    return "PRIMITIVE_LOW_TICK";
                default:
                    return Integer.toString(id);
            }
        }
    }

    /**
     * A builder for waveform haptic effects.
     *
     * <p>Waveform vibrations constitute of one or more timed segments where the vibration
     * amplitude, frequency or both can linearly ramp to new values.
     *
     * <p>Waveform segments may have zero duration, which represent a jump to new vibration
     * amplitude and/or frequency values.
     *
     * <p>Waveform segments may have the same start and end vibration amplitude and frequency,
     * which represent a step where the amplitude and frequency are maintained for that duration.
     *
     * @hide
     * @see VibrationEffect#startWaveform()
     */
    @TestApi
    public static final class WaveformBuilder {
        private ArrayList<VibrationEffectSegment> mSegments = new ArrayList<>();

        WaveformBuilder() {}

        /**
         * Vibrate with given amplitude for the given duration, in millis, keeping the previous
         * frequency the same.
         *
         * <p>If the duration is zero the vibrator will jump to new amplitude.
         *
         * @param amplitude The amplitude for this step
         * @param duration  The duration of this step in milliseconds
         * @return The {@link WaveformBuilder} object to enable adding multiple steps in chain.
         */
        @SuppressLint("MissingGetterMatchingBuilder")
        @NonNull
        public WaveformBuilder addStep(@FloatRange(from = 0f, to = 1f) float amplitude,
                @IntRange(from = 0) int duration) {
            return addStep(amplitude, getPreviousFrequency(), duration);
        }

        /**
         * Vibrate with given amplitude for the given duration, in millis, keeping the previous
         * vibration frequency the same.
         *
         * <p>If the duration is zero the vibrator will jump to new amplitude.
         *
         * @param amplitude The amplitude for this step
         * @param frequency The frequency for this step
         * @param duration  The duration of this step in milliseconds
         * @return The {@link WaveformBuilder} object to enable adding multiple steps in chain.
         */
        @SuppressLint("MissingGetterMatchingBuilder")
        @NonNull
        public WaveformBuilder addStep(@FloatRange(from = 0f, to = 1f) float amplitude,
                @FloatRange(from = -1f, to = 1f) float frequency,
                @IntRange(from = 0) int duration) {
            mSegments.add(new StepSegment(amplitude, frequency, duration));
            return this;
        }

        /**
         * Ramp vibration linearly for the given duration, in millis, from previous amplitude value
         * to the given one, keeping previous frequency.
         *
         * <p>If the duration is zero the vibrator will jump to new amplitude.
         *
         * @param amplitude The final amplitude this ramp should reach
         * @param duration  The duration of this ramp in milliseconds
         * @return The {@link WaveformBuilder} object to enable adding multiple steps in chain.
         */
        @SuppressLint("MissingGetterMatchingBuilder")
        @NonNull
        public WaveformBuilder addRamp(@FloatRange(from = 0f, to = 1f) float amplitude,
                @IntRange(from = 0) int duration) {
            return addRamp(amplitude, getPreviousFrequency(), duration);
        }

        /**
         * Ramp vibration linearly for the given duration, in millis, from previous amplitude and
         * frequency values to the given ones.
         *
         * <p>If the duration is zero the vibrator will jump to new amplitude and frequency.
         *
         * @param amplitude The final amplitude this ramp should reach
         * @param frequency The final frequency this ramp should reach
         * @param duration  The duration of this ramp in milliseconds
         * @return The {@link WaveformBuilder} object to enable adding multiple steps in chain.
         */
        @SuppressLint("MissingGetterMatchingBuilder")
        @NonNull
        public WaveformBuilder addRamp(@FloatRange(from = 0f, to = 1f) float amplitude,
                @FloatRange(from = -1f, to = 1f) float frequency,
                @IntRange(from = 0) int duration) {
            mSegments.add(new RampSegment(getPreviousAmplitude(), amplitude, getPreviousFrequency(),
                    frequency, duration));
            return this;
        }

        /**
         * Compose all of the steps together into a single {@link VibrationEffect}.
         *
         * The {@link WaveformBuilder} object is still valid after this call, so you can
         * continue adding more primitives to it and generating more {@link VibrationEffect}s by
         * calling this method again.
         *
         * @return The {@link VibrationEffect} resulting from the composition of the steps.
         */
        @NonNull
        public VibrationEffect build() {
            return build(/* repeat= */ -1);
        }

        /**
         * Compose all of the steps together into a single {@link VibrationEffect}.
         *
         * <p>To cause the pattern to repeat, pass the index at which to start the repetition
         * (starting at 0), or -1 to disable repeating.
         *
         * <p>The {@link WaveformBuilder} object is still valid after this call, so you can
         * continue adding more primitives to it and generating more {@link VibrationEffect}s by
         * calling this method again.
         *
         * @return The {@link VibrationEffect} resulting from the composition of the steps.
         */
        @NonNull
        public VibrationEffect build(int repeat) {
            if (mSegments.isEmpty()) {
                throw new IllegalStateException(
                        "WaveformBuilder must have at least one element to build.");
            }
            VibrationEffect effect = new Composed(mSegments, repeat);
            effect.validate();
            return effect;
        }

        private float getPreviousFrequency() {
            if (!mSegments.isEmpty()) {
                VibrationEffectSegment segment = mSegments.get(mSegments.size() - 1);
                if (segment instanceof StepSegment) {
                    return ((StepSegment) segment).getFrequency();
                } else if (segment instanceof RampSegment) {
                    return ((RampSegment) segment).getEndFrequency();
                }
            }
            return 0;
        }

        private float getPreviousAmplitude() {
            if (!mSegments.isEmpty()) {
                VibrationEffectSegment segment = mSegments.get(mSegments.size() - 1);
                if (segment instanceof StepSegment) {
                    return ((StepSegment) segment).getAmplitude();
                } else if (segment instanceof RampSegment) {
                    return ((RampSegment) segment).getEndAmplitude();
                }
            }
            return 0;
        }
    }

    @NonNull
    public static final Parcelable.Creator<VibrationEffect> CREATOR =
            new Parcelable.Creator<VibrationEffect>() {
                @Override
                public VibrationEffect createFromParcel(Parcel in) {
                    return new Composed(in);
                }
                @Override
                public VibrationEffect[] newArray(int size) {
                    return new VibrationEffect[size];
                }
            };
}
