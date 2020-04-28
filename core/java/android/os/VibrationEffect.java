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
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ContentResolver;
import android.content.Context;
import android.hardware.vibrator.V1_0.EffectStrength;
import android.hardware.vibrator.V1_3.Effect;
import android.net.Uri;
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
    private static final int PARCEL_TOKEN_ONE_SHOT = 1;
    private static final int PARCEL_TOKEN_WAVEFORM = 2;
    private static final int PARCEL_TOKEN_EFFECT = 3;
    private static final int PARCEL_TOKEN_COMPOSITION = 4;


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
    @UnsupportedAppUsage
    @TestApi
    public static final int EFFECT_THUD = Effect.THUD;

    /**
     * A pop effect.
     * @see #get(int)
     * @hide
     */
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
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
        VibrationEffect effect = new OneShot(milliseconds, amplitude);
        effect.validate();
        return effect;
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
     * value in the timing array determines how long it vibrates for. An amplitude of 0 implies no
     * vibration (i.e. off), and any pairs with a timing value of 0 will be ignored.
     * </p><p>
     * To cause the pattern to repeat, pass the index into the timings array at which to start the
     * repetition, or -1 to disable repeating.
     * </p>
     *
     * @param timings The timing values of the timing / amplitude pairs. Timing values of 0
     *                will cause the pair to be ignored.
     * @param amplitudes The amplitude values of the timing / amplitude pairs. Amplitude values
     *                   must be between 0 and 255, or equal to {@link #DEFAULT_AMPLITUDE}. An
     *                   amplitude value of 0 implies the motor is off.
     * @param repeat The index into the timings array at which to repeat, or -1 if you you don't
     *               want to repeat.
     *
     * @return The desired effect.
     */
    public static VibrationEffect createWaveform(long[] timings, int[] amplitudes, int repeat) {
        VibrationEffect effect = new Waveform(timings, amplitudes, repeat);
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
        VibrationEffect effect = new Prebaked(effectId, fallback);
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
    public static VibrationEffect.Composition startComposition() {
        return new VibrationEffect.Composition();
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
     * Scale the amplitude with the given constraints.
     *
     * This assumes that the previous value was in the range [0, MAX_AMPLITUDE]
     * @hide
     */
    @TestApi
    protected static int scale(int amplitude, float gamma, int maxAmplitude) {
        float val = MathUtils.pow(amplitude / (float) MAX_AMPLITUDE, gamma);
        return (int) (val * maxAmplitude);
    }

    /** @hide */
    @TestApi
    public static class OneShot extends VibrationEffect implements Parcelable {
        private final long mDuration;
        private final int mAmplitude;

        public OneShot(Parcel in) {
            mDuration = in.readLong();
            mAmplitude = in.readInt();
        }

        public OneShot(long milliseconds, int amplitude) {
            mDuration = milliseconds;
            mAmplitude = amplitude;
        }

        @Override
        public long getDuration() {
            return mDuration;
        }

        public int getAmplitude() {
            return mAmplitude;
        }

        /**
         * Scale the amplitude of this effect.
         *
         * @param gamma the gamma adjustment to apply
         * @param maxAmplitude the new maximum amplitude of the effect, must be between 0 and
         *         MAX_AMPLITUDE
         * @throws IllegalArgumentException if maxAmplitude less than 0 or more than MAX_AMPLITUDE
         *
         * @return A {@link OneShot} effect with the same timing but scaled amplitude.
         */
        public OneShot scale(float gamma, int maxAmplitude) {
            if (maxAmplitude > MAX_AMPLITUDE || maxAmplitude < 0) {
                throw new IllegalArgumentException(
                        "Amplitude is negative or greater than MAX_AMPLITUDE");
            }
            int newAmplitude = scale(mAmplitude, gamma, maxAmplitude);
            return new OneShot(mDuration, newAmplitude);
        }

        /**
         * Resolve default values into integer amplitude numbers.
         *
         * @param defaultAmplitude the default amplitude to apply, must be between 0 and
         *         MAX_AMPLITUDE
         * @return A {@link OneShot} effect with same physical meaning but explicitly set amplitude
         *
         * @hide
         */
        public OneShot resolve(int defaultAmplitude) {
            if (defaultAmplitude > MAX_AMPLITUDE || defaultAmplitude < 0) {
                throw new IllegalArgumentException(
                        "Amplitude is negative or greater than MAX_AMPLITUDE");
            }
            if (mAmplitude == DEFAULT_AMPLITUDE) {
                return new OneShot(mDuration, defaultAmplitude);
            }
            return this;
        }

        @Override
        public void validate() {
            if (mAmplitude < -1 || mAmplitude == 0 || mAmplitude > 255) {
                throw new IllegalArgumentException(
                        "amplitude must either be DEFAULT_AMPLITUDE, "
                        + "or between 1 and 255 inclusive (amplitude=" + mAmplitude + ")");
            }
            if (mDuration <= 0) {
                throw new IllegalArgumentException(
                        "duration must be positive (duration=" + mDuration + ")");
            }
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof VibrationEffect.OneShot)) {
                return false;
            }
            VibrationEffect.OneShot other = (VibrationEffect.OneShot) o;
            return other.mDuration == mDuration && other.mAmplitude == mAmplitude;
        }

        @Override
        public int hashCode() {
            int result = 17;
            result += 37 * (int) mDuration;
            result += 37 * mAmplitude;
            return result;
        }

        @Override
        public String toString() {
            return "OneShot{mDuration=" + mDuration + ", mAmplitude=" + mAmplitude + "}";
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            out.writeInt(PARCEL_TOKEN_ONE_SHOT);
            out.writeLong(mDuration);
            out.writeInt(mAmplitude);
        }

        @UnsupportedAppUsage
        public static final @android.annotation.NonNull Parcelable.Creator<OneShot> CREATOR =
            new Parcelable.Creator<OneShot>() {
                @Override
                public OneShot createFromParcel(Parcel in) {
                    // Skip the type token
                    in.readInt();
                    return new OneShot(in);
                }
                @Override
                public OneShot[] newArray(int size) {
                    return new OneShot[size];
                }
            };
    }

    /** @hide */
    @TestApi
    public static class Waveform extends VibrationEffect implements Parcelable {
        private final long[] mTimings;
        private final int[] mAmplitudes;
        private final int mRepeat;

        public Waveform(Parcel in) {
            this(in.createLongArray(), in.createIntArray(), in.readInt());
        }

        public Waveform(long[] timings, int[] amplitudes, int repeat) {
            mTimings = new long[timings.length];
            System.arraycopy(timings, 0, mTimings, 0, timings.length);
            mAmplitudes = new int[amplitudes.length];
            System.arraycopy(amplitudes, 0, mAmplitudes, 0, amplitudes.length);
            mRepeat = repeat;
        }

        public long[] getTimings() {
            return mTimings;
        }

        public int[] getAmplitudes() {
            return mAmplitudes;
        }

        public int getRepeatIndex() {
            return mRepeat;
        }

        @Override
        public long getDuration() {
            if (mRepeat >= 0) {
                return Long.MAX_VALUE;
            }
            long duration = 0;
            for (long d : mTimings) {
                duration += d;
            }
            return duration;
        }

        /**
         * Scale the Waveform with the given gamma and new max amplitude.
         *
         * @param gamma the gamma adjustment to apply
         * @param maxAmplitude the new maximum amplitude of the effect, must be between 0 and
         *         MAX_AMPLITUDE
         * @throws IllegalArgumentException if maxAmplitude less than 0 or more than MAX_AMPLITUDE
         *
         * @return A {@link Waveform} effect with the same timings and repeat index
         *         but scaled amplitude.
         */
        public Waveform scale(float gamma, int maxAmplitude) {
            if (maxAmplitude > MAX_AMPLITUDE || maxAmplitude < 0) {
                throw new IllegalArgumentException(
                        "Amplitude is negative or greater than MAX_AMPLITUDE");
            }
            if (gamma == 1.0f && maxAmplitude == MAX_AMPLITUDE) {
                // Just return a copy of the original if there's no scaling to be done.
                return new Waveform(mTimings, mAmplitudes, mRepeat);
            }

            int[] scaledAmplitudes = Arrays.copyOf(mAmplitudes, mAmplitudes.length);
            for (int i = 0; i < scaledAmplitudes.length; i++) {
                scaledAmplitudes[i] = scale(scaledAmplitudes[i], gamma, maxAmplitude);
            }
            return new Waveform(mTimings, scaledAmplitudes, mRepeat);
        }

        /**
         * Resolve default values into integer amplitude numbers.
         *
         * @param defaultAmplitude the default amplitude to apply, must be between 0 and
         *         MAX_AMPLITUDE
         * @return A {@link Waveform} effect with same physical meaning but explicitly set
         *         amplitude
         *
         * @hide
         */
        public Waveform resolve(int defaultAmplitude) {
            if (defaultAmplitude > MAX_AMPLITUDE || defaultAmplitude < 0) {
                throw new IllegalArgumentException(
                        "Amplitude is negative or greater than MAX_AMPLITUDE");
            }
            int[] resolvedAmplitudes = Arrays.copyOf(mAmplitudes, mAmplitudes.length);
            for (int i = 0; i < resolvedAmplitudes.length; i++) {
                if (resolvedAmplitudes[i] == DEFAULT_AMPLITUDE) {
                    resolvedAmplitudes[i] = defaultAmplitude;
                }
            }
            return new Waveform(mTimings, resolvedAmplitudes, mRepeat);
        }

        @Override
        public void validate() {
            if (mTimings.length != mAmplitudes.length) {
                throw new IllegalArgumentException(
                        "timing and amplitude arrays must be of equal length"
                        + " (timings.length=" + mTimings.length
                        + ", amplitudes.length=" + mAmplitudes.length + ")");
            }
            if (!hasNonZeroEntry(mTimings)) {
                throw new IllegalArgumentException("at least one timing must be non-zero"
                        + " (timings=" + Arrays.toString(mTimings) + ")");
            }
            for (long timing : mTimings) {
                if (timing < 0) {
                    throw new IllegalArgumentException("timings must all be >= 0"
                            + " (timings=" + Arrays.toString(mTimings) + ")");
                }
            }
            for (int amplitude : mAmplitudes) {
                if (amplitude < -1 || amplitude > 255) {
                    throw new IllegalArgumentException(
                            "amplitudes must all be DEFAULT_AMPLITUDE or between 0 and 255"
                            + " (amplitudes=" + Arrays.toString(mAmplitudes) + ")");
                }
            }
            if (mRepeat < -1 || mRepeat >= mTimings.length) {
                throw new IllegalArgumentException(
                        "repeat index must be within the bounds of the timings array"
                        + " (timings.length=" + mTimings.length + ", index=" + mRepeat + ")");
            }
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof VibrationEffect.Waveform)) {
                return false;
            }
            VibrationEffect.Waveform other = (VibrationEffect.Waveform) o;
            return Arrays.equals(mTimings, other.mTimings)
                && Arrays.equals(mAmplitudes, other.mAmplitudes)
                && mRepeat == other.mRepeat;
        }

        @Override
        public int hashCode() {
            int result = 17;
            result += 37 * Arrays.hashCode(mTimings);
            result += 37 * Arrays.hashCode(mAmplitudes);
            result += 37 * mRepeat;
            return result;
        }

        @Override
        public String toString() {
            return "Waveform{mTimings=" + Arrays.toString(mTimings)
                + ", mAmplitudes=" + Arrays.toString(mAmplitudes)
                + ", mRepeat=" + mRepeat
                + "}";
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            out.writeInt(PARCEL_TOKEN_WAVEFORM);
            out.writeLongArray(mTimings);
            out.writeIntArray(mAmplitudes);
            out.writeInt(mRepeat);
        }

        private static boolean hasNonZeroEntry(long[] vals) {
            for (long val : vals) {
                if (val != 0) {
                    return true;
                }
            }
            return false;
        }


        public static final @android.annotation.NonNull Parcelable.Creator<Waveform> CREATOR =
            new Parcelable.Creator<Waveform>() {
                @Override
                public Waveform createFromParcel(Parcel in) {
                    // Skip the type token
                    in.readInt();
                    return new Waveform(in);
                }
                @Override
                public Waveform[] newArray(int size) {
                    return new Waveform[size];
                }
            };
    }

    /** @hide */
    @TestApi
    public static class Prebaked extends VibrationEffect implements Parcelable {
        private final int mEffectId;
        private final boolean mFallback;

        private int mEffectStrength;

        public Prebaked(Parcel in) {
            this(in.readInt(), in.readByte() != 0);
            mEffectStrength = in.readInt();
        }

        public Prebaked(int effectId, boolean fallback) {
            mEffectId = effectId;
            mFallback = fallback;
            mEffectStrength = EffectStrength.MEDIUM;
        }

        public int getId() {
            return mEffectId;
        }

        /**
         * Whether the effect should fall back to a generic pattern if there's no hardware specific
         * implementation of it.
         */
        public boolean shouldFallback() {
            return mFallback;
        }

        @Override
        public long getDuration() {
            return -1;
        }

        /**
         * Set the effect strength of the prebaked effect.
         */
        public void setEffectStrength(int strength) {
            if (!isValidEffectStrength(strength)) {
                throw new IllegalArgumentException("Invalid effect strength: " + strength);
            }
            mEffectStrength = strength;
        }

        /**
         * Set the effect strength.
         */
        public int getEffectStrength() {
            return mEffectStrength;
        }

        private static boolean isValidEffectStrength(int strength) {
            switch (strength) {
                case EffectStrength.LIGHT:
                case EffectStrength.MEDIUM:
                case EffectStrength.STRONG:
                    return true;
                default:
                    return false;
            }
        }

        @Override
        public void validate() {
            switch (mEffectId) {
                case EFFECT_CLICK:
                case EFFECT_DOUBLE_CLICK:
                case EFFECT_TICK:
                case EFFECT_TEXTURE_TICK:
                case EFFECT_THUD:
                case EFFECT_POP:
                case EFFECT_HEAVY_CLICK:
                    break;
                default:
                    if (mEffectId < RINGTONES[0] || mEffectId > RINGTONES[RINGTONES.length - 1]) {
                        throw new IllegalArgumentException(
                                "Unknown prebaked effect type (value=" + mEffectId + ")");
                    }
            }
            if (!isValidEffectStrength(mEffectStrength)) {
                throw new IllegalArgumentException(
                        "Unknown prebaked effect strength (value=" + mEffectStrength + ")");
            }
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof VibrationEffect.Prebaked)) {
                return false;
            }
            VibrationEffect.Prebaked other = (VibrationEffect.Prebaked) o;
            return mEffectId == other.mEffectId
                && mFallback == other.mFallback
                && mEffectStrength == other.mEffectStrength;
        }

        @Override
        public int hashCode() {
            int result = 17;
            result += 37 * mEffectId;
            result += 37 * mEffectStrength;
            return result;
        }

        @Override
        public String toString() {
            return "Prebaked{mEffectId=" + mEffectId
                + ", mEffectStrength=" + mEffectStrength
                + ", mFallback=" + mFallback
                + "}";
        }


        @Override
        public void writeToParcel(Parcel out, int flags) {
            out.writeInt(PARCEL_TOKEN_EFFECT);
            out.writeInt(mEffectId);
            out.writeByte((byte) (mFallback ? 1 : 0));
            out.writeInt(mEffectStrength);
        }

        public static final @NonNull Parcelable.Creator<Prebaked> CREATOR =
            new Parcelable.Creator<Prebaked>() {
                @Override
                public Prebaked createFromParcel(Parcel in) {
                    // Skip the type token
                    in.readInt();
                    return new Prebaked(in);
                }
                @Override
                public Prebaked[] newArray(int size) {
                    return new Prebaked[size];
                }
            };
    }

    /** @hide */
    public static final class Composed extends VibrationEffect implements Parcelable {
        private final ArrayList<Composition.PrimitiveEffect> mPrimitiveEffects;

        /**
         * @hide
         */
        @SuppressWarnings("unchecked")
        public Composed(@NonNull Parcel in) {
            this(in.readArrayList(Composed.class.getClassLoader()));
        }

        /**
         * @hide
         */
        public Composed(List<Composition.PrimitiveEffect> effects) {
            mPrimitiveEffects = new ArrayList<>(Objects.requireNonNull(effects));
        }

        /**
         * @hide
         */
        @NonNull
        public List<Composition.PrimitiveEffect> getPrimitiveEffects() {
            return mPrimitiveEffects;
        }

        @Override
        public long getDuration() {
            return -1;
        }

        /**
         * Scale all primitives of this effect.
         *
         * @param gamma the gamma adjustment to apply
         * @param maxAmplitude the new maximum amplitude of the effect, must be between 0 and
         *         MAX_AMPLITUDE
         * @throws IllegalArgumentException if maxAmplitude less than 0 or more than MAX_AMPLITUDE
         *
         * @return A {@link Composed} effect with same but scaled primitives.
         */
        public Composed scale(float gamma, int maxAmplitude) {
            if (maxAmplitude > MAX_AMPLITUDE || maxAmplitude < 0) {
                throw new IllegalArgumentException(
                        "Amplitude is negative or greater than MAX_AMPLITUDE");
            }
            if (gamma == 1.0f && maxAmplitude == MAX_AMPLITUDE) {
                // Just return a copy of the original if there's no scaling to be done.
                return new Composed(mPrimitiveEffects);
            }
            List<Composition.PrimitiveEffect> scaledPrimitives = new ArrayList<>();
            for (Composition.PrimitiveEffect primitive : mPrimitiveEffects) {
                float adjustedScale = MathUtils.pow(primitive.scale, gamma);
                float newScale = adjustedScale * maxAmplitude / (float) MAX_AMPLITUDE;
                scaledPrimitives.add(new Composition.PrimitiveEffect(
                        primitive.id, newScale, primitive.delay));
            }
            return new Composed(scaledPrimitives);
        }

        /**
         * @hide
         */
        @Override
        public void validate() {
            for (Composition.PrimitiveEffect effect : mPrimitiveEffects) {
                Composition.checkPrimitive(effect.id);
                Preconditions.checkArgumentInRange(
                        effect.scale, 0.0f, 1.0f, "scale");
            }
        }

        @Override
        public void writeToParcel(@NonNull Parcel out, int flags) {
            out.writeInt(PARCEL_TOKEN_COMPOSITION);
            out.writeList(mPrimitiveEffects);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Composed composed = (Composed) o;
            return mPrimitiveEffects.equals(composed.mPrimitiveEffects);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mPrimitiveEffects);
        }

        @Override
        public String toString() {
            return "Composed{mPrimitiveEffects=" + mPrimitiveEffects + '}';
        }

        public static final @NonNull Parcelable.Creator<Composed> CREATOR =
                new Parcelable.Creator<Composed>() {
                    @Override
                    public Composed createFromParcel(@NonNull Parcel in) {
                        // Skip the type token
                        in.readInt();
                        return new Composed(in);
                    }

                    @Override
                    @NonNull
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
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface Primitive {}

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


        private ArrayList<PrimitiveEffect> mEffects = new ArrayList<>();

        Composition() { }

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
        public Composition addPrimitive(@Primitive int primitiveId) {
            addPrimitive(primitiveId, /*scale*/ 1.0f, /*delay*/ 0);
            return this;
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
        public Composition addPrimitive(@Primitive int primitiveId,
                @FloatRange(from = 0f, to = 1f) float scale) {
            addPrimitive(primitiveId, scale, /*delay*/ 0);
            return this;
        }

        /**
         * Add a haptic primitive to the end of the current composition.
         *
         * @param primitiveId The primitive to add
         * @param scale The scale to apply to the intensity of the primitive.
         * @param delay The amount of time, in milliseconds, to wait between playing the prior
         *              primitive and this one
         * @return The {@link Composition} object to enable adding multiple primitives in one chain.
         */
        @NonNull
        public Composition addPrimitive(@Primitive int primitiveId,
                @FloatRange(from = 0f, to = 1f) float scale, @IntRange(from = 0) int delay) {
            mEffects.add(new PrimitiveEffect(checkPrimitive(primitiveId), scale, delay));
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
            if (mEffects.isEmpty()) {
                throw new IllegalStateException(
                        "Composition must have at least one element to compose.");
            }
            return new VibrationEffect.Composed(mEffects);
        }

        /**
         * @throws IllegalArgumentException throws if the primitive ID is not within the valid range
         * @hide
         *
         */
        static int checkPrimitive(int primitiveId) {
            Preconditions.checkArgumentInRange(primitiveId, PRIMITIVE_NOOP, PRIMITIVE_TICK,
                    "primitiveId");
            return primitiveId;
        }

        /**
         * Convert the primitive ID to a human readable string for debugging
         * @param id The ID to convert
         * @return The ID in a human readable format.
         * @hide
         */
        public static String primitiveToString(@Primitive int id) {
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

                default:
                    return Integer.toString(id);

            }
        }


        /**
         * @hide
         */
        public static class PrimitiveEffect implements Parcelable {
            public int id;
            public float scale;
            public int delay;

            PrimitiveEffect(int id, float scale, int delay) {
                this.id = id;
                this.scale = scale;
                this.delay = delay;
            }

            @Override
            public void writeToParcel(Parcel dest, int flags) {
                dest.writeInt(id);
                dest.writeFloat(scale);
                dest.writeInt(delay);
            }

            @Override
            public int describeContents() {
                return 0;
            }

            @Override
            public String toString() {
                return "PrimitiveEffect{"
                        + "id=" + primitiveToString(id)
                        + ", scale=" + scale
                        + ", delay=" + delay
                        + '}';
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                PrimitiveEffect that = (PrimitiveEffect) o;
                return id == that.id
                        && Float.compare(that.scale, scale) == 0
                        && delay == that.delay;
            }

            @Override
            public int hashCode() {
                return Objects.hash(id, scale, delay);
            }


            public static final @NonNull Parcelable.Creator<PrimitiveEffect> CREATOR =
                    new Parcelable.Creator<PrimitiveEffect>() {
                        @Override
                        public PrimitiveEffect createFromParcel(Parcel in) {
                            return new PrimitiveEffect(in.readInt(), in.readFloat(), in.readInt());
                        }
                        @Override
                        public PrimitiveEffect[] newArray(int size) {
                            return new PrimitiveEffect[size];
                        }
                    };
        }
    }

    public static final @NonNull Parcelable.Creator<VibrationEffect> CREATOR =
            new Parcelable.Creator<VibrationEffect>() {
                @Override
                public VibrationEffect createFromParcel(Parcel in) {
                    int token = in.readInt();
                    if (token == PARCEL_TOKEN_ONE_SHOT) {
                        return new OneShot(in);
                    } else if (token == PARCEL_TOKEN_WAVEFORM) {
                        return new Waveform(in);
                    } else if (token == PARCEL_TOKEN_EFFECT) {
                        return new Prebaked(in);
                    } else if (token == PARCEL_TOKEN_COMPOSITION) {
                        return new Composed(in);
                    } else {
                        throw new IllegalStateException(
                                "Unexpected vibration event type token in parcel.");
                    }
                }
                @Override
                public VibrationEffect[] newArray(int size) {
                    return new VibrationEffect[size];
                }
            };
}
