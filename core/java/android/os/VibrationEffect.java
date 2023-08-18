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
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.RampSegment;
import android.os.vibrator.StepSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.util.Log;
import android.util.MathUtils;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * A VibrationEffect describes a haptic effect to be performed by a {@link Vibrator}.
 *
 * <p>These effects may be any number of things, from single shot vibrations to complex waveforms.
 */
public abstract class VibrationEffect implements Parcelable {
    private static final String TAG = "VibrationEffect";
    // Stevens' coefficient to scale the perceived vibration intensity.
    private static final float SCALE_GAMMA = 0.65f;
    // If a vibration is playing for longer than 1s, it's probably not haptic feedback
    private static final long MAX_HAPTIC_FEEDBACK_DURATION = 1000;
    // If a vibration is playing more than 3 constants, it's probably not haptic feedback
    private static final long MAX_HAPTIC_FEEDBACK_COMPOSITION_SIZE = 3;

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
     * <p>Unlike normal effects, texture effects are meant to be called repeatedly, generally in
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
     * <p>One shot vibrations will vibrate constantly for the specified period of time at the
     * specified amplitude, and then stop.
     *
     * @param milliseconds The number of milliseconds to vibrate. This must be a positive number.
     * @param amplitude The strength of the vibration. This must be a value between 1 and 255, or
     * {@link #DEFAULT_AMPLITUDE}.
     *
     * @return The desired effect.
     */
    public static VibrationEffect createOneShot(long milliseconds, int amplitude) {
        if (amplitude == 0) {
            throw new IllegalArgumentException(
                    "amplitude must either be DEFAULT_AMPLITUDE, "
                            + "or between 1 and 255 inclusive (amplitude=" + amplitude + ")");
        }
        return createWaveform(new long[]{milliseconds}, new int[]{amplitude}, -1 /* repeat */);
    }

    /**
     * Create a waveform vibration, using only off/on transitions at the provided time intervals,
     * and potentially repeating.
     *
     * <p>In effect, the timings array represents the number of milliseconds <em>before</em> turning
     * the vibrator on, followed by the number of milliseconds to keep the vibrator on, then
     * the number of milliseconds turned off, and so on. Consequently, the first timing value will
     * often be 0, so that the effect will start vibrating immediately.
     *
     * <p>This method is equivalent to calling {@link #createWaveform(long[], int[], int)} with
     * corresponding amplitude values alternating between 0 and {@link #DEFAULT_AMPLITUDE},
     * beginning with 0.
     *
     * <p>To cause the pattern to repeat, pass the index into the timings array at which to start
     * the repetition, or -1 to disable repeating. Repeating effects will be played indefinitely
     * and should be cancelled via {@link Vibrator#cancel()}.
     *
     * @param timings The pattern of alternating on-off timings, starting with an 'off' timing, and
     *               representing the length of time to sustain the individual item (not
     *               cumulative).
     * @param repeat The index into the timings array at which to repeat, or -1 if you don't
     *               want to repeat indefinitely.
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
     * Computes a legacy vibration pattern (i.e. a pattern with duration values for "off/on"
     * vibration components) that is equivalent to this VibrationEffect.
     *
     * <p>All non-repeating effects created with {@link #createWaveform(long[], int)} are
     * convertible into an equivalent vibration pattern with this method. It is not guaranteed that
     * an effect created with other means becomes converted into an equivalent legacy vibration
     * pattern, even if it has an equivalent vibration pattern. If this method is unable to create
     * an equivalent vibration pattern for such effects, it will return {@code null}.
     *
     * <p>Note that a valid equivalent long[] pattern cannot be created for an effect that has any
     * form of repeating behavior, regardless of how the effect was created. For repeating effects,
     * the method will always return {@code null}.
     *
     * @return a long array representing a vibration pattern equivalent to the VibrationEffect, if
     *               the method successfully derived a vibration pattern equivalent to the effect
     *               (this will always be the case if the effect was created via
     *               {@link #createWaveform(long[], int)} and is non-repeating). Otherwise, returns
     *               {@code null}.
     * @hide
     */
    @TestApi
    @Nullable
    public abstract long[] computeCreateWaveformOffOnTimingsOrNull();

    /**
     * Create a waveform vibration.
     *
     * <p>Waveform vibrations are a potentially repeating series of timing and amplitude pairs,
     * provided in separate arrays. For each pair, the value in the amplitude array determines
     * the strength of the vibration and the value in the timing array determines how long it
     * vibrates for, in milliseconds.
     *
     * <p>To cause the pattern to repeat, pass the index into the timings array at which to start
     * the repetition, or -1 to disable repeating. Repeating effects will be played indefinitely
     * and should be cancelled via {@link Vibrator#cancel()}.
     *
     * @param timings The timing values, in milliseconds, of the timing / amplitude pairs. Timing
     *                values of 0 will cause the pair to be ignored.
     * @param amplitudes The amplitude values of the timing / amplitude pairs. Amplitude values
     *                   must be between 0 and 255, or equal to {@link #DEFAULT_AMPLITUDE}. An
     *                   amplitude value of 0 implies the motor is off.
     * @param repeat The index into the timings array at which to repeat, or -1 if you don't
     *               want to repeat indefinitely.
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
            segments.add(new StepSegment(parsedAmplitude, /* frequencyHz= */ 0, (int) timings[i]));
        }
        VibrationEffect effect = new Composed(segments, repeat);
        effect.validate();
        return effect;
    }

    /**
     * Create a predefined vibration effect.
     *
     * <p>Predefined effects are a set of common vibration effects that should be identical,
     * regardless of the app they come from, in order to provide a cohesive experience for users
     * across the entire device. They also may be custom tailored to the device hardware in order to
     * provide a better experience than you could otherwise build using the generic building
     * blocks.
     *
     * <p>This will fallback to a generic pattern if one exists and there does not exist a
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
     * <p>Predefined effects are a set of common vibration effects that should be identical,
     * regardless of the app they come from, in order to provide a cohesive experience for users
     * across the entire device. They also may be custom tailored to the device hardware in order to
     * provide a better experience than you could otherwise build using the generic building
     * blocks.
     *
     * <p>This will fallback to a generic pattern if one exists and there does not exist a
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
        return get(effectId, PrebakedSegment.DEFAULT_SHOULD_FALLBACK);
    }

    /**
     * Get a predefined vibration effect.
     *
     * <p>Predefined effects are a set of common vibration effects that should be identical,
     * regardless of the app they come from, in order to provide a cohesive experience for users
     * across the entire device. They also may be custom tailored to the device hardware in order to
     * provide a better experience than you could otherwise build using the generic building
     * blocks.
     *
     * <p>Some effects you may only want to play if there's a hardware specific implementation
     * because they may, for example, be too disruptive to the user without tuning. The
     * {@code fallback} parameter allows you to decide whether you want to fallback to the generic
     * implementation or only play if there's a tuned, hardware specific one available.
     *
     * @param effectId The ID of the effect to perform:
     *                 {@link #EFFECT_CLICK}, {@link #EFFECT_DOUBLE_CLICK}, {@link #EFFECT_TICK}
     * @param fallback Whether to fall back to a generic pattern if a hardware specific
     *                 implementation doesn't exist.
     *
     * @return The desired effect.
     * @hide
     */
    @TestApi
    public static VibrationEffect get(int effectId, boolean fallback) {
        VibrationEffect effect = new Composed(
                new PrebakedSegment(effectId, fallback, PrebakedSegment.DEFAULT_STRENGTH));
        effect.validate();
        return effect;
    }

    /**
     * Get a predefined vibration effect associated with a given URI.
     *
     * <p>Predefined effects are a set of common vibration effects that should be identical,
     * regardless of the app they come from, in order to provide a cohesive experience for users
     * across the entire device. They also may be custom tailored to the device hardware in order to
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

        try {
            final ContentResolver cr = context.getContentResolver();
            Uri uncanonicalUri = cr.uncanonicalize(uri);
            if (uncanonicalUri == null) {
                // If we already had an uncanonical URI, it's possible we'll get null back here. In
                // this case, just use the URI as passed in since it wasn't canonicalized in the
                // first place.
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
        } catch (Exception e) {
            // Don't give unexpected exceptions to callers if the Uri's ContentProvider is
            // misbehaving - it's very unlikely to be mapped in that case anyway.
            Log.e(TAG, "Exception getting default vibration for Uri " + uri, e);
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
     * control over vibration amplitude and frequency via smooth transitions between values.
     *
     * <p>The waveform will start the first transition from the vibrator off state, with the
     * resonant frequency by default. To provide an initial state, use
     * {@link #startWaveform(VibrationEffect.VibrationParameter)}.
     *
     * @see VibrationEffect.WaveformBuilder
     * @hide
     */
    @TestApi
    @NonNull
    public static WaveformBuilder startWaveform() {
        return new WaveformBuilder();
    }

    /**
     * Start building a waveform vibration with an initial state specified by a
     * {@link VibrationEffect.VibrationParameter}.
     *
     * <p>The waveform builder offers more flexibility for creating waveform vibrations, allowing
     * control over vibration amplitude and frequency via smooth transitions between values.
     *
     * @param initialParameter The initial {@link VibrationEffect.VibrationParameter} value to be
     *                         applied at the beginning of the vibration.
     * @return The {@link VibrationEffect.WaveformBuilder} started with the initial parameters.
     *
     * @see VibrationEffect.WaveformBuilder
     * @hide
     */
    @TestApi
    @NonNull
    public static WaveformBuilder startWaveform(@NonNull VibrationParameter initialParameter) {
        WaveformBuilder builder = startWaveform();
        builder.addTransition(Duration.ZERO, initialParameter);
        return builder;
    }

    /**
     * Start building a waveform vibration with an initial state specified by two
     * {@link VibrationEffect.VibrationParameter VibrationParameters}.
     *
     * <p>The waveform builder offers more flexibility for creating waveform vibrations, allowing
     * control over vibration amplitude and frequency via smooth transitions between values.
     *
     * @param initialParameter1 The initial {@link VibrationEffect.VibrationParameter} value to be
     *                          applied at the beginning of the vibration.
     * @param initialParameter2 The initial {@link VibrationEffect.VibrationParameter} value to be
     *                          applied at the beginning of the vibration, must be a different type
     *                          of parameter than the one specified by the first argument.
     * @return The {@link VibrationEffect.WaveformBuilder} started with the initial parameters.
     *
     * @see VibrationEffect.WaveformBuilder
     * @hide
     */
    @TestApi
    @NonNull
    public static WaveformBuilder startWaveform(@NonNull VibrationParameter initialParameter1,
            @NonNull VibrationParameter initialParameter2) {
        WaveformBuilder builder = startWaveform();
        builder.addTransition(Duration.ZERO, initialParameter1, initialParameter2);
        return builder;
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
     * <p>For effects without a defined end (e.g. a Waveform with a non-negative repeat index), this
     * returns Long.MAX_VALUE. For effects with an unknown duration (e.g. Prebaked effects where
     * the length is device and potentially run-time dependent), this returns -1.
     *
     * @hide
     */
    @TestApi
    public abstract long getDuration();

    /**
     * Checks if a vibrator with a given {@link VibratorInfo} can play this effect as intended.
     *
     * <p>See {@link VibratorInfo#areVibrationFeaturesSupported(VibrationEffect)} for more
     * information about what counts as supported by a vibrator, and what counts as not.
     *
     * @hide
     */
    public abstract boolean areVibrationFeaturesSupported(@NonNull VibratorInfo vibratorInfo);

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
     * Ensures that the effect is repeating indefinitely or not. This is a lossy operation and
     * should only be applied once to an original effect - it shouldn't be applied to the
     * result of this method.
     *
     * <p>Non-repeating effects will be made repeating by looping the entire effect with the
     * specified delay between each loop. The delay is added irrespective of whether the effect
     * already has a delay at the beginning or end.
     *
     * <p>Repeating effects will be left with their native repeating portion if it should be
     * repeating, and otherwise the loop index is removed, so that the entire effect plays once.
     *
     * @param wantRepeating Whether the effect is required to be repeating or not.
     * @param loopDelayMs The milliseconds to pause between loops, if repeating is to be added to
     *                    the effect. Ignored if {@code repeating==false} or the effect is already
     *                    repeating itself. No delay is added if <= 0.
     * @return this if the effect already satisfies the repeating requirement, or a copy of this
     *         adjusted to repeat or not repeat as appropriate.
     * @hide
     */
    @NonNull
    public abstract VibrationEffect applyRepeatingIndefinitely(
            boolean wantRepeating, int loopDelayMs);

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

    /**
     * Returns a compact version of the {@link #toString()} result for debugging purposes.
     *
     * @hide
     */
    public abstract String toDebugString();

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
     * Transforms a {@link VibrationEffect} using a generic parameter.
     *
     * <p>This can be used for scaling effects based on user settings or adapting them to the
     * capabilities of a specific device vibrator.
     *
     * @param <ParamT> The type of parameter to be used on the effect by this transformation
     * @hide
     */
    public interface Transformation<ParamT> {

        /** Transforms given effect by applying the given parameter. */
        @NonNull
        VibrationEffect transform(@NonNull VibrationEffect effect, @NonNull ParamT param);
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
            this(in.readArrayList(
                    VibrationEffectSegment.class.getClassLoader(), VibrationEffectSegment.class),
                    in.readInt());
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

         /** @hide */
        @Override
        @Nullable
        public long[] computeCreateWaveformOffOnTimingsOrNull() {
            if (getRepeatIndex() >= 0) {
                // Repeating effects cannot be fully represented as a long[] legacy pattern.
                return null;
            }

            List<VibrationEffectSegment> segments = getSegments();

            // The maximum possible size of the final pattern is 1 plus the number of segments in
            // the original effect. This is because we will add an empty "off" segment at the
            // start of the pattern if the first segment of the original effect is an "on" segment.
            // (because the legacy patterns start with an "off" pattern). Other than this one case,
            // we will add the durations of back-to-back segments of similar amplitudes (amplitudes
            // that are all "on" or "off") and create a pattern entry for the total duration, which
            // will not take more number pattern entries than the number of segments processed.
            long[] patternBuffer = new long[segments.size() + 1];
            int patternIndex = 0;

            for (int i = 0; i < segments.size(); i++) {
                StepSegment stepSegment =
                        castToValidStepSegmentForOffOnTimingsOrNull(segments.get(i));
                if (stepSegment == null) {
                    // This means that there is 1 or more segments of this effect that is/are not a
                    // possible component of a legacy vibration pattern. Thus, the VibrationEffect
                    // does not have any equivalent legacy vibration pattern.
                    return null;
                }

                boolean isSegmentOff = stepSegment.getAmplitude() == 0;
                // Even pattern indices are "off", and odd pattern indices are "on"
                boolean isCurrentPatternIndexOff = (patternIndex % 2) == 0;
                if (isSegmentOff != isCurrentPatternIndexOff) {
                    // Move the pattern index one step ahead, so that the current segment's
                    // "off"/"on" property matches that of the index's
                    ++patternIndex;
                }
                patternBuffer[patternIndex] += stepSegment.getDuration();
            }

            return Arrays.copyOf(patternBuffer, patternIndex + 1);
        }

        /** @hide */
        @Override
        public void validate() {
            int segmentCount = mSegments.size();
            boolean hasNonZeroDuration = false;
            for (int i = 0; i < segmentCount; i++) {
                VibrationEffectSegment segment = mSegments.get(i);
                segment.validate();
                // A segment with unknown duration = -1 still counts as a non-zero duration.
                hasNonZeroDuration |= segment.getDuration() != 0;
            }
            if (!hasNonZeroDuration) {
                throw new IllegalArgumentException("at least one timing must be non-zero"
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

        /** @hide */
        @Override
        public boolean areVibrationFeaturesSupported(@NonNull VibratorInfo vibratorInfo) {
            for (VibrationEffectSegment segment : mSegments) {
                if (!segment.areVibrationFeaturesSupported(vibratorInfo)) {
                    return false;
                }
            }
            return true;
        }

        /** @hide */
        @Override
        public boolean isHapticFeedbackCandidate() {
            long totalDuration = getDuration();
            if (totalDuration > MAX_HAPTIC_FEEDBACK_DURATION) {
                // Vibration duration is known and is longer than the max duration used to classify
                // haptic feedbacks (or repeating indefinitely with duration == Long.MAX_VALUE).
                return false;
            }
            int segmentCount = mSegments.size();
            if (segmentCount > MAX_HAPTIC_FEEDBACK_COMPOSITION_SIZE) {
                // Vibration has some prebaked or primitive constants, it should be limited to the
                // max composition size used to classify haptic feedbacks.
                return false;
            }
            totalDuration = 0;
            for (int i = 0; i < segmentCount; i++) {
                if (!mSegments.get(i).isHapticFeedbackCandidate()) {
                    // There is at least one segment that is not a candidate for a haptic feedback.
                    return false;
                }
                long segmentDuration = mSegments.get(i).getDuration();
                if (segmentDuration > 0) {
                    totalDuration += segmentDuration;
                }
            }
            // Vibration might still have some ramp or step segments, check the known duration.
            return totalDuration <= MAX_HAPTIC_FEEDBACK_DURATION;
        }

        /** @hide */
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

        /** @hide */
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

        /** @hide */
        @NonNull
        @Override
        public Composed applyRepeatingIndefinitely(boolean wantRepeating, int loopDelayMs) {
            boolean isRepeating = mRepeatIndex >= 0;
            if (isRepeating == wantRepeating) {
                return this;
            } else if (!wantRepeating) {
                return new Composed(mSegments, -1);
            } else if (loopDelayMs <= 0) {
                // Loop with no delay: repeat at index zero.
                return new Composed(mSegments, 0);
            } else {
                // Append a delay and loop. It doesn't matter that there's a delay on the
                // end because the looping is always indefinite until cancelled.
                ArrayList<VibrationEffectSegment> loopingSegments =
                        new ArrayList<>(mSegments.size() + 1);
                loopingSegments.addAll(mSegments);
                loopingSegments.add(
                        new StepSegment(/* amplitude= */ 0, /* frequencyHz= */ 0, loopDelayMs));
                return new Composed(loopingSegments, 0);
            }
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) {
                return true;
            }
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

        /** @hide */
        @Override
        public String toDebugString() {
            if (mSegments.size() == 1 && mRepeatIndex < 0) {
                // Simplify effect string, use the single segment to represent it.
                return mSegments.get(0).toDebugString();
            }
            StringJoiner sj = new StringJoiner(",", "[", "]");
            for (int i = 0; i < mSegments.size(); i++) {
                sj.add(mSegments.get(i).toDebugString());
            }
            if (mRepeatIndex >= 0) {
                return String.format(Locale.ROOT, "%s, repeat=%d", sj, mRepeatIndex);
            }
            return sj.toString();
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

        /**
         * Casts a provided {@link VibrationEffectSegment} to a {@link StepSegment} and returns it,
         * only if it can possibly be a segment for an effect created via
         * {@link #createWaveform(long[], int)}. Otherwise, returns {@code null}.
         */
        @Nullable
        private static StepSegment castToValidStepSegmentForOffOnTimingsOrNull(
                VibrationEffectSegment segment) {
            if (!(segment instanceof StepSegment)) {
                return null;
            }

            StepSegment stepSegment = (StepSegment) segment;
            if (stepSegment.getFrequencyHz() != 0) {
                return null;
            }

            float amplitude = stepSegment.getAmplitude();
            if (amplitude != 0 && amplitude != DEFAULT_AMPLITUDE) {
                return null;
            }

            return stepSegment;
        }
    }

    /**
     * A composition of haptic elements that are combined to be playable as a single
     * {@link VibrationEffect}.
     *
     * <p>The haptic primitives are available as {@code Composition.PRIMITIVE_*} constants and
     * can be added to a composition to create a custom vibration effect. Here is an example of an
     * effect that grows in intensity and then dies off, with a longer rising portion for emphasis
     * and an extra tick 100ms after:
     *
     * <pre>
     * {@code VibrationEffect effect = VibrationEffect.startComposition()
     *     .addPrimitive(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, 0.5f)
     *     .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.5f)
     *     .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 1.0f, 100)
     *     .compose();}</pre>
     *
     * <p>When choosing to play a composed effect, you should check that individual components are
     * supported by the device by using {@link Vibrator#arePrimitivesSupported}.
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
        public @interface PrimitiveType {
        }

        /**
         * Exception thrown when adding an element to a {@link Composition} that already ends in an
         * indefinitely repeating effect.
         * @hide
         */
        @TestApi
        public static final class UnreachableAfterRepeatingIndefinitelyException
                extends IllegalStateException {
            UnreachableAfterRepeatingIndefinitelyException() {
                super("Compositions ending in an indefinitely repeating effect can't be extended");
            }
        }

        /**
         * No haptic effect. Used to generate extended delays between primitives.
         *
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
         */
        public static final int PRIMITIVE_THUD = 2;
        /**
         * A haptic effect that simulates spinning momentum.
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
         * Adds a time duration to the current composition, during which the vibrator will be
         * turned off.
         *
         * @param duration The length of time the vibrator should be off. Value must be non-negative
         *                 and will be truncated to milliseconds.
         * @return This {@link Composition} object to enable adding multiple elements in one chain.
         *
         * @throws UnreachableAfterRepeatingIndefinitelyException if the composition is currently
         * ending with a repeating effect.
         * @hide
         */
        @TestApi
        @NonNull
        public Composition addOffDuration(@NonNull Duration duration) {
            int durationMs = (int) duration.toMillis();
            Preconditions.checkArgumentNonnegative(durationMs, "Off period must be non-negative");
            if (durationMs > 0) {
                // Created a segment sustaining the zero amplitude to represent the delay.
                addSegment(new StepSegment(/* amplitude= */ 0, /* frequencyHz= */ 0,
                        (int) duration.toMillis()));
            }
            return this;
        }

        /**
         * Add a haptic effect to the end of the current composition.
         *
         * <p>If this effect is repeating (e.g. created by {@link VibrationEffect#createWaveform}
         * with a non-negative repeat index, or created by another composition that has effects
         * repeating indefinitely), then no more effects or primitives will be accepted by this
         * composition after this method. Such effects should be cancelled via
         * {@link Vibrator#cancel()}.
         *
         * @param effect The effect to add to the end of this composition.
         * @return This {@link Composition} object to enable adding multiple elements in one chain.
         *
         * @throws UnreachableAfterRepeatingIndefinitelyException if the composition is currently
         * ending with a repeating effect.
         * @hide
         */
        @TestApi
        @NonNull
        public Composition addEffect(@NonNull VibrationEffect effect) {
            return addSegments(effect);
        }

        /**
         * Add a haptic effect to the end of the current composition and play it on repeat,
         * indefinitely.
         *
         * <p>The entire effect will be played on repeat, indefinitely, after all other elements
         * already added to this composition are played. No more effects or primitives will be
         * accepted by this composition after this method. Such effects should be cancelled via
         * {@link Vibrator#cancel()}.
         *
         * @param effect The effect to add to the end of this composition, must be finite.
         * @return This {@link Composition} object to enable adding multiple elements in one chain,
         * although only {@link #compose()} can follow this call.
         *
         * @throws IllegalArgumentException if the given effect is already repeating indefinitely.
         * @throws UnreachableAfterRepeatingIndefinitelyException if the composition is currently
         * ending with a repeating effect.
         * @hide
         */
        @TestApi
        @NonNull
        public Composition repeatEffectIndefinitely(@NonNull VibrationEffect effect) {
            Preconditions.checkArgument(effect.getDuration() < Long.MAX_VALUE,
                    "Can't repeat an indefinitely repeating effect. Consider addEffect instead.");
            int previousSegmentCount = mSegments.size();
            addSegments(effect);
            // Set repeat after segments were added, since addSegments checks this index.
            mRepeatIndex = previousSegmentCount;
            return this;
        }

        /**
         * Add a haptic primitive to the end of the current composition.
         *
         * <p>Similar to {@link #addPrimitive(int, float, int)}, but with no delay and a
         * default scale applied.
         *
         * @param primitiveId The primitive to add
         * @return This {@link Composition} object to enable adding multiple elements in one chain.
         */
        @NonNull
        public Composition addPrimitive(@PrimitiveType int primitiveId) {
            return addPrimitive(primitiveId, PrimitiveSegment.DEFAULT_SCALE);
        }

        /**
         * Add a haptic primitive to the end of the current composition.
         *
         * <p>Similar to {@link #addPrimitive(int, float, int)}, but with no delay.
         *
         * @param primitiveId The primitive to add
         * @param scale The scale to apply to the intensity of the primitive.
         * @return This {@link Composition} object to enable adding multiple elements in one chain.
         */
        @NonNull
        public Composition addPrimitive(@PrimitiveType int primitiveId,
                @FloatRange(from = 0f, to = 1f) float scale) {
            return addPrimitive(primitiveId, scale, PrimitiveSegment.DEFAULT_DELAY_MILLIS);
        }

        /**
         * Add a haptic primitive to the end of the current composition.
         *
         * @param primitiveId The primitive to add
         * @param scale The scale to apply to the intensity of the primitive.
         * @param delay The amount of time in milliseconds to wait before playing this primitive,
         *              starting at the time the previous element in this composition is finished.
         * @return This {@link Composition} object to enable adding multiple elements in one chain.
         */
        @NonNull
        public Composition addPrimitive(@PrimitiveType int primitiveId,
                @FloatRange(from = 0f, to = 1f) float scale, @IntRange(from = 0) int delay) {
            PrimitiveSegment primitive = new PrimitiveSegment(primitiveId, scale, delay);
            primitive.validate();
            return addSegment(primitive);
        }

        private Composition addSegment(VibrationEffectSegment segment) {
            if (mRepeatIndex >= 0) {
                throw new UnreachableAfterRepeatingIndefinitelyException();
            }
            mSegments.add(segment);
            return this;
        }

        private Composition addSegments(VibrationEffect effect) {
            if (mRepeatIndex >= 0) {
                throw new UnreachableAfterRepeatingIndefinitelyException();
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
         * <p>The {@link Composition} object is still valid after this call, so you can continue
         * adding more primitives to it and generating more {@link VibrationEffect}s by calling this
         * method again.
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
         * Convert the primitive ID to a human readable string for debugging.
         * @param id The ID to convert
         * @return The ID in a human readable format.
         * @hide
         */
        public static String primitiveToString(@PrimitiveType int id) {
            switch (id) {
                case PRIMITIVE_NOOP:
                    return "NOOP";
                case PRIMITIVE_CLICK:
                    return "CLICK";
                case PRIMITIVE_THUD:
                    return "THUD";
                case PRIMITIVE_SPIN:
                    return "SPIN";
                case PRIMITIVE_QUICK_RISE:
                    return "QUICK_RISE";
                case PRIMITIVE_SLOW_RISE:
                    return "SLOW_RISE";
                case PRIMITIVE_QUICK_FALL:
                    return "QUICK_FALL";
                case PRIMITIVE_TICK:
                    return "TICK";
                case PRIMITIVE_LOW_TICK:
                    return "LOW_TICK";
                default:
                    return Integer.toString(id);
            }
        }
    }

    /**
     * A builder for waveform haptic effects.
     *
     * <p>Waveform vibrations constitute of one or more timed transitions to new sets of vibration
     * parameters. These parameters can be the vibration amplitude, frequency, or both.
     *
     * <p>The following example ramps a vibrator turned off to full amplitude at 120Hz, over 100ms
     * starting at 60Hz, then holds that state for 200ms and ramps back down again over 100ms:
     *
     * <pre>
     * {@code import static android.os.VibrationEffect.VibrationParameter.targetAmplitude;
     * import static android.os.VibrationEffect.VibrationParameter.targetFrequency;
     *
     * VibrationEffect effect = VibrationEffect.startWaveform(targetFrequency(60))
     *     .addTransition(Duration.ofMillis(100), targetAmplitude(1), targetFrequency(120))
     *     .addSustain(Duration.ofMillis(200))
     *     .addTransition(Duration.ofMillis(100), targetAmplitude(0), targetFrequency(60))
     *     .build();}</pre>
     *
     * <p>The initial state of the waveform can be set via
     * {@link VibrationEffect#startWaveform(VibrationParameter)} or
     * {@link VibrationEffect#startWaveform(VibrationParameter, VibrationParameter)}. If the initial
     * parameters are not set then the {@link WaveformBuilder} will start with the vibrator off,
     * represented by zero amplitude, at the vibrator's resonant frequency.
     *
     * <p>Repeating waveforms can be created by building the repeating block separately and adding
     * it to the end of a composition with
     * {@link Composition#repeatEffectIndefinitely(VibrationEffect)}:
     *
     * <p>Note that physical vibration actuators have different reaction times for changing
     * amplitude and frequency. Durations specified here represent a timeline for the target
     * parameters, and quality of effects may be improved if the durations allow time for a
     * transition to be smoothly applied.
     *
     * <p>The following example illustrates both an initial state and a repeating section, using
     * a {@link VibrationEffect.Composition}. The resulting effect will have a tick followed by a
     * repeated beating effect with a rise that stretches out and a sharp finish.
     *
     * <pre>
     * {@code VibrationEffect patternToRepeat = VibrationEffect.startWaveform(targetAmplitude(0.2f))
     *     .addSustain(Duration.ofMillis(10))
     *     .addTransition(Duration.ofMillis(20), targetAmplitude(0.4f))
     *     .addSustain(Duration.ofMillis(30))
     *     .addTransition(Duration.ofMillis(40), targetAmplitude(0.8f))
     *     .addSustain(Duration.ofMillis(50))
     *     .addTransition(Duration.ofMillis(60), targetAmplitude(0.2f))
     *     .build();
     *
     * VibrationEffect effect = VibrationEffect.startComposition()
     *     .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK)
     *     .addOffDuration(Duration.ofMillis(20))
     *     .repeatEffectIndefinitely(patternToRepeat)
     *     .compose();}</pre>
     *
     * <p>The amplitude step waveforms that can be created via
     * {@link VibrationEffect#createWaveform(long[], int[], int)} can also be created with
     * {@link WaveformBuilder} by adding zero duration transitions:
     *
     * <pre>
     * {@code // These two effects are the same
     * VibrationEffect waveform = VibrationEffect.createWaveform(
     *     new long[] { 10, 20, 30 },  // timings in milliseconds
     *     new int[] { 51, 102, 204 }, // amplitudes in [0,255]
     *     -1);                        // repeat index
     *
     * VibrationEffect sameWaveform = VibrationEffect.startWaveform(targetAmplitude(0.2f))
     *     .addSustain(Duration.ofMillis(10))
     *     .addTransition(Duration.ZERO, targetAmplitude(0.4f))
     *     .addSustain(Duration.ofMillis(20))
     *     .addTransition(Duration.ZERO, targetAmplitude(0.8f))
     *     .addSustain(Duration.ofMillis(30))
     *     .build();}</pre>
     *
     * @see VibrationEffect#startWaveform
     * @hide
     */
    @TestApi
    public static final class WaveformBuilder {
        // Epsilon used for float comparison of amplitude and frequency values on transitions.
        private static final float EPSILON = 1e-5f;

        private ArrayList<VibrationEffectSegment> mSegments = new ArrayList<>();
        private float mLastAmplitude = 0f;
        private float mLastFrequencyHz = 0f;

        WaveformBuilder() {}

        /**
         * Add a transition to new vibration parameter value to the end of this waveform.
         *
         * <p>The duration represents how long the vibrator should take to smoothly transition to
         * the new vibration parameter. If the duration is zero then the vibrator will jump to the
         * new value as fast as possible.
         *
         * <p>Vibration parameter values will be truncated to conform to the device capabilities
         * according to the {@link android.os.vibrator.VibratorFrequencyProfile}.
         *
         * @param duration        The length of time this transition should take. Value must be
         *                        non-negative and will be truncated to milliseconds.
         * @param targetParameter The new target {@link VibrationParameter} value to be reached
         *                        after the given duration.
         * @return This {@link WaveformBuilder} object to enable adding multiple transitions in
         * chain.
         * @hide
         */
        @TestApi
        @SuppressWarnings("MissingGetterMatchingBuilder") // No getters to segments once created.
        @NonNull
        public WaveformBuilder addTransition(@NonNull Duration duration,
                @NonNull VibrationParameter targetParameter) {
            Preconditions.checkNotNull(duration, "Duration is null");
            checkVibrationParameter(targetParameter, "targetParameter");
            float amplitude = extractTargetAmplitude(targetParameter, /* target2= */ null);
            float frequencyHz = extractTargetFrequency(targetParameter, /* target2= */ null);
            addTransitionSegment(duration, amplitude, frequencyHz);
            return this;
        }

        /**
         * Add a transition to new vibration parameters to the end of this waveform.
         *
         * <p>The duration represents how long the vibrator should take to smoothly transition to
         * the new vibration parameters. If the duration is zero then the vibrator will jump to the
         * new values as fast as possible.
         *
         * <p>Vibration parameters values will be truncated to conform to the device capabilities
         * according to the {@link android.os.vibrator.VibratorFrequencyProfile}.
         *
         * @param duration         The length of time this transition should take. Value must be
         *                         non-negative and will be truncated to milliseconds.
         * @param targetParameter1 The first target {@link VibrationParameter} value to be reached
         *                         after the given duration.
         * @param targetParameter2 The second target {@link VibrationParameter} value to be reached
         *                         after the given duration, must be a different type of parameter
         *                         than the one specified by the first argument.
         * @return This {@link WaveformBuilder} object to enable adding multiple transitions in
         * chain.
         * @hide
         */
        @TestApi
        @SuppressWarnings("MissingGetterMatchingBuilder") // No getters to segments once created.
        @NonNull
        public WaveformBuilder addTransition(@NonNull Duration duration,
                @NonNull VibrationParameter targetParameter1,
                @NonNull VibrationParameter targetParameter2) {
            Preconditions.checkNotNull(duration, "Duration is null");
            checkVibrationParameter(targetParameter1, "targetParameter1");
            checkVibrationParameter(targetParameter2, "targetParameter2");
            Preconditions.checkArgument(
                    !Objects.equals(targetParameter1.getClass(), targetParameter2.getClass()),
                    "Parameter arguments must specify different parameter types");
            float amplitude = extractTargetAmplitude(targetParameter1, targetParameter2);
            float frequencyHz = extractTargetFrequency(targetParameter1, targetParameter2);
            addTransitionSegment(duration, amplitude, frequencyHz);
            return this;
        }

        /**
         * Add a duration to sustain the last vibration parameters of this waveform.
         *
         * <p>The duration represents how long the vibrator should sustain the last set of
         * parameters provided to this builder.
         *
         * @param duration   The length of time the last values should be sustained by the vibrator.
         *                   Value must be >= 1ms.
         * @return This {@link WaveformBuilder} object to enable adding multiple transitions in
         * chain.
         * @hide
         */
        @TestApi
        @SuppressWarnings("MissingGetterMatchingBuilder") // No getters to segments once created.
        @NonNull
        public WaveformBuilder addSustain(@NonNull Duration duration) {
            int durationMs = (int) duration.toMillis();
            Preconditions.checkArgument(durationMs >= 1, "Sustain duration must be >= 1ms");
            mSegments.add(new StepSegment(mLastAmplitude, mLastFrequencyHz, durationMs));
            return this;
        }

        /**
         * Build the waveform as a single {@link VibrationEffect}.
         *
         * <p>The {@link WaveformBuilder} object is still valid after this call, so you can
         * continue adding more primitives to it and generating more {@link VibrationEffect}s by
         * calling this method again.
         *
         * @return The {@link VibrationEffect} resulting from the list of transitions.
         * @hide
         */
        @TestApi
        @NonNull
        public VibrationEffect build() {
            if (mSegments.isEmpty()) {
                throw new IllegalStateException(
                        "WaveformBuilder must have at least one transition to build.");
            }
            VibrationEffect effect = new Composed(mSegments, /* repeatIndex= */ -1);
            effect.validate();
            return effect;
        }

        private void checkVibrationParameter(@NonNull VibrationParameter vibrationParameter,
                String paramName) {
            Preconditions.checkNotNull(vibrationParameter, "%s is null", paramName);
            Preconditions.checkArgument(
                    (vibrationParameter instanceof AmplitudeVibrationParameter)
                            || (vibrationParameter instanceof FrequencyVibrationParameter),
                    "%s is a unknown parameter", paramName);
        }

        private float extractTargetAmplitude(@Nullable VibrationParameter target1,
                @Nullable VibrationParameter target2) {
            if (target2 instanceof AmplitudeVibrationParameter) {
                return ((AmplitudeVibrationParameter) target2).amplitude;
            }
            if (target1 instanceof AmplitudeVibrationParameter) {
                return ((AmplitudeVibrationParameter) target1).amplitude;
            }
            return mLastAmplitude;
        }

        private float extractTargetFrequency(@Nullable VibrationParameter target1,
                @Nullable VibrationParameter target2) {
            if (target2 instanceof FrequencyVibrationParameter) {
                return ((FrequencyVibrationParameter) target2).frequencyHz;
            }
            if (target1 instanceof FrequencyVibrationParameter) {
                return ((FrequencyVibrationParameter) target1).frequencyHz;
            }
            return mLastFrequencyHz;
        }

        private void addTransitionSegment(Duration duration, float targetAmplitude,
                float targetFrequency) {
            Preconditions.checkNotNull(duration, "Duration is null");
            Preconditions.checkArgument(!duration.isNegative(),
                    "Transition duration must be non-negative");
            int durationMs = (int) duration.toMillis();

            // Ignore transitions with zero duration, but keep values for next additions.
            if (durationMs > 0) {
                if ((Math.abs(mLastAmplitude - targetAmplitude) < EPSILON)
                        && (Math.abs(mLastFrequencyHz - targetFrequency) < EPSILON)) {
                    // No value is changing, this can be best represented by a step segment.
                    mSegments.add(new StepSegment(targetAmplitude, targetFrequency, durationMs));
                } else {
                    mSegments.add(new RampSegment(mLastAmplitude, targetAmplitude,
                            mLastFrequencyHz, targetFrequency, durationMs));
                }
            }

            mLastAmplitude = targetAmplitude;
            mLastFrequencyHz = targetFrequency;
        }
    }

    /**
     * A representation of a single vibration parameter.
     *
     * <p>This is to describe a waveform haptic effect, which consists of one or more timed
     * transitions to a new set of {@link VibrationParameter}s.
     *
     * <p>Examples of concrete parameters are the vibration amplitude or frequency.
     *
     * @see VibrationEffect.WaveformBuilder
     * @hide
     */
    @TestApi
    @SuppressWarnings("UserHandleName") // This is not a regular set of parameters, no *Params.
    public static class VibrationParameter {
        VibrationParameter() {
        }

        /**
         * The target vibration amplitude.
         *
         * @param amplitude The amplitude value, between 0 and 1, inclusive, where 0 represents the
         *                  vibrator turned off and 1 represents the maximum amplitude the vibrator
         *                  can reach across all supported frequencies.
         * @return The {@link VibrationParameter} instance that represents given amplitude.
         * @hide
         */
        @TestApi
        @NonNull
        public static VibrationParameter targetAmplitude(
                @FloatRange(from = 0, to = 1) float amplitude) {
            return new AmplitudeVibrationParameter(amplitude);
        }

        /**
         * The target vibration frequency.
         *
         * @param frequencyHz The frequency value, in hertz.
         * @return The {@link VibrationParameter} instance that represents given frequency.
         * @hide
         */
        @TestApi
        @NonNull
        public static VibrationParameter targetFrequency(@FloatRange(from = 1) float frequencyHz) {
            return new FrequencyVibrationParameter(frequencyHz);
        }
    }

    /** The vibration amplitude, represented by a value in [0,1]. */
    private static final class AmplitudeVibrationParameter extends VibrationParameter {
        public final float amplitude;

        AmplitudeVibrationParameter(float amplitude) {
            Preconditions.checkArgument((amplitude >= 0) && (amplitude <= 1),
                    "Amplitude must be within [0,1]");
            this.amplitude = amplitude;
        }
    }

    /** The vibration frequency, in hertz, or zero to represent undefined frequency. */
    private static final class FrequencyVibrationParameter extends VibrationParameter {
        public final float frequencyHz;

        FrequencyVibrationParameter(float frequencyHz) {
            Preconditions.checkArgument(frequencyHz >= 1, "Frequency must be >= 1");
            Preconditions.checkArgument(Float.isFinite(frequencyHz), "Frequency must be finite");
            this.frequencyHz = frequencyHz;
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
