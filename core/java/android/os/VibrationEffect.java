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
        VibrationEffect effect = new Prebaked(effectId, fallback, EffectStrength.MEDIUM);
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
     * Scale given vibration intensity by the given factor.
     *
     * @param amplitude amplitude of the effect, must be between 0 and MAX_AMPLITUDE
     * @param scaleFactor scale factor to be applied to the intensity. Values within [0,1) will
     *                    scale down the intensity, values larger than 1 will scale up
     *
     * @hide
     */
    protected static int scale(int amplitude, float scaleFactor) {
        if (amplitude == 0) {
            return 0;
        }
        int scaled = (int) (scale((float) amplitude / MAX_AMPLITUDE, scaleFactor) * MAX_AMPLITUDE);
        return MathUtils.constrain(scaled, 1, MAX_AMPLITUDE);
    }

    /**
     * Scale given vibration intensity by the given factor.
     *
     * @param intensity relative intensity of the effect, must be between 0 and 1
     * @param scaleFactor scale factor to be applied to the intensity. Values within [0,1) will
     *                    scale down the intensity, values larger than 1 will scale up
     *
     * @hide
     */
    protected static float scale(float intensity, float scaleFactor) {
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

        /** @hide */
        @Override
        public OneShot scale(float scaleFactor) {
            if (scaleFactor == 1f || mAmplitude == DEFAULT_AMPLITUDE) {
                // Just return this if there's no scaling to be done or if amplitude is not yet set.
                return this;
            }
            return new OneShot(mDuration, scale(mAmplitude, scaleFactor));
        }

        /** @hide */
        @Override
        public OneShot resolve(int defaultAmplitude) {
            if (defaultAmplitude > MAX_AMPLITUDE || defaultAmplitude <= 0) {
                throw new IllegalArgumentException(
                        "amplitude must be between 1 and 255 inclusive (amplitude="
                        + defaultAmplitude + ")");
            }
            if (mAmplitude == DEFAULT_AMPLITUDE) {
                return new OneShot(mDuration, defaultAmplitude);
            }
            return this;
        }

        /** @hide */
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
        public boolean equals(@Nullable Object o) {
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

        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
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

        /** @hide */
        @Override
        public Waveform scale(float scaleFactor) {
            if (scaleFactor == 1f) {
                // Just return this if there's no scaling to be done.
                return this;
            }
            boolean scaled = false;
            int[] scaledAmplitudes = Arrays.copyOf(mAmplitudes, mAmplitudes.length);
            for (int i = 0; i < scaledAmplitudes.length; i++) {
                if (scaledAmplitudes[i] == DEFAULT_AMPLITUDE) {
                    // Skip amplitudes that are not set.
                    continue;
                }
                scaled = true;
                scaledAmplitudes[i] = scale(scaledAmplitudes[i], scaleFactor);
            }
            if (!scaled) {
                // Just return this if no scaling was done.
                return this;
            }
            return new Waveform(mTimings, scaledAmplitudes, mRepeat);
        }

        /** @hide */
        @Override
        public Waveform resolve(int defaultAmplitude) {
            if (defaultAmplitude > MAX_AMPLITUDE || defaultAmplitude < 0) {
                throw new IllegalArgumentException(
                        "Amplitude is negative or greater than MAX_AMPLITUDE");
            }
            boolean resolved = false;
            int[] resolvedAmplitudes = Arrays.copyOf(mAmplitudes, mAmplitudes.length);
            for (int i = 0; i < resolvedAmplitudes.length; i++) {
                if (resolvedAmplitudes[i] == DEFAULT_AMPLITUDE) {
                    resolvedAmplitudes[i] = defaultAmplitude;
                    resolved = true;
                }
            }
            if (!resolved) {
                return this;
            }
            return new Waveform(mTimings, resolvedAmplitudes, mRepeat);
        }

        /** @hide */
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
        public boolean equals(@Nullable Object o) {
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
        private final int mEffectStrength;
        @Nullable
        private final VibrationEffect mFallbackEffect;

        public Prebaked(Parcel in) {
            mEffectId = in.readInt();
            mFallback = in.readByte() != 0;
            mEffectStrength = in.readInt();
            mFallbackEffect = in.readParcelable(VibrationEffect.class.getClassLoader());
        }

        public Prebaked(int effectId, boolean fallback, int effectStrength) {
            mEffectId = effectId;
            mFallback = fallback;
            mEffectStrength = effectStrength;
            mFallbackEffect = null;
        }

        /** @hide */
        public Prebaked(int effectId, int effectStrength, @NonNull VibrationEffect fallbackEffect) {
            mEffectId = effectId;
            mFallback = true;
            mEffectStrength = effectStrength;
            mFallbackEffect = fallbackEffect;
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

        /** @hide */
        @Override
        public Prebaked resolve(int defaultAmplitude) {
            if (mFallbackEffect != null) {
                VibrationEffect resolvedFallback = mFallbackEffect.resolve(defaultAmplitude);
                if (!mFallbackEffect.equals(resolvedFallback)) {
                    return new Prebaked(mEffectId, mEffectStrength, resolvedFallback);
                }
            }
            return this;
        }

        /** @hide */
        @Override
        public Prebaked scale(float scaleFactor) {
            if (mFallbackEffect != null) {
                VibrationEffect scaledFallback = mFallbackEffect.scale(scaleFactor);
                if (!mFallbackEffect.equals(scaledFallback)) {
                    return new Prebaked(mEffectId, mEffectStrength, scaledFallback);
                }
            }
            // Prebaked effect strength cannot be scaled with this method.
            return this;
        }

        /**
         * Set the effect strength.
         */
        public int getEffectStrength() {
            return mEffectStrength;
        }

        /**
         * Return the fallback effect, if set.
         *
         * @hide
         */
        @Nullable
        public VibrationEffect getFallbackEffect() {
            return mFallbackEffect;
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

        /** @hide */
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
        public boolean equals(@Nullable Object o) {
            if (!(o instanceof VibrationEffect.Prebaked)) {
                return false;
            }
            VibrationEffect.Prebaked other = (VibrationEffect.Prebaked) o;
            return mEffectId == other.mEffectId
                && mFallback == other.mFallback
                && mEffectStrength == other.mEffectStrength
                && Objects.equals(mFallbackEffect, other.mFallbackEffect);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mEffectId, mFallback, mEffectStrength, mFallbackEffect);
        }

        @Override
        public String toString() {
            return "Prebaked{mEffectId=" + effectIdToString(mEffectId)
                + ", mEffectStrength=" + effectStrengthToString(mEffectStrength)
                + ", mFallback=" + mFallback
                + ", mFallbackEffect=" + mFallbackEffect
                + "}";
        }


        @Override
        public void writeToParcel(Parcel out, int flags) {
            out.writeInt(PARCEL_TOKEN_EFFECT);
            out.writeInt(mEffectId);
            out.writeByte((byte) (mFallback ? 1 : 0));
            out.writeInt(mEffectStrength);
            out.writeParcelable(mFallbackEffect, flags);
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

        /** @hide */
        @Override
        public VibrationEffect resolve(int defaultAmplitude) {
            // Primitive effects already have default primitive intensity set, so ignore this.
            return this;
        }

        /** @hide */
        @Override
        public Composed scale(float scaleFactor) {
            if (scaleFactor == 1f) {
                // Just return this if there's no scaling to be done.
                return this;
            }
            final int primitiveCount = mPrimitiveEffects.size();
            List<Composition.PrimitiveEffect> scaledPrimitives = new ArrayList<>();
            for (int i = 0; i < primitiveCount; i++) {
                Composition.PrimitiveEffect primitive = mPrimitiveEffects.get(i);
                scaledPrimitives.add(new Composition.PrimitiveEffect(
                        primitive.id, scale(primitive.scale, scaleFactor), primitive.delay));
            }
            return new Composed(scaledPrimitives);
        }

        /** @hide */
        @Override
        public void validate() {
            final int primitiveCount = mPrimitiveEffects.size();
            for (int i = 0; i < primitiveCount; i++) {
                Composition.PrimitiveEffect primitive = mPrimitiveEffects.get(i);
                Composition.checkPrimitive(primitive.id);
                Preconditions.checkArgumentInRange(primitive.scale, 0.0f, 1.0f, "scale");
                Preconditions.checkArgumentNonNegative(primitive.delay,
                        "Primitive delay must be zero or positive");
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
        public boolean equals(@Nullable Object o) {
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
                PRIMITIVE_LOW_TICK,
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
        /**
         * This very short low frequency effect should produce a light crisp sensation
         * intended to be used repetitively for dynamic feedback.
         */
        // Internally this maps to the HAL constant CompositePrimitive::LOW_TICK
        public static final int PRIMITIVE_LOW_TICK = 8;


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
         * @param delay The amount of time in milliseconds to wait before playing this primitive
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
            Preconditions.checkArgumentInRange(primitiveId, PRIMITIVE_NOOP, PRIMITIVE_LOW_TICK,
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
                case PRIMITIVE_LOW_TICK:
                    return "PRIMITIVE_LOW_TICK";
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
            public boolean equals(@Nullable Object o) {
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
