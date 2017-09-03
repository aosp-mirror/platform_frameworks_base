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

import android.hardware.vibrator.V1_1.Constants.Effect_1_1;

import java.util.Arrays;

/**
 * A VibrationEffect describes a haptic effect to be performed by a {@link Vibrator}.
 *
 * These effects may be any number of things, from single shot vibrations to complex waveforms.
 */
public abstract class VibrationEffect implements Parcelable {
    private static final int PARCEL_TOKEN_ONE_SHOT = 1;
    private static final int PARCEL_TOKEN_WAVEFORM = 2;
    private static final int PARCEL_TOKEN_EFFECT = 3;

    /**
     * The default vibration strength of the device.
     */
    public static final int DEFAULT_AMPLITUDE = -1;

    /**
     * A click effect.
     *
     * @see #get(int)
     * @hide
     */
    public static final int EFFECT_CLICK = Effect_1_1.CLICK;

    /**
     * A double click effect.
     *
     * @see #get(int)
     * @hide
     */
    public static final int EFFECT_DOUBLE_CLICK = Effect_1_1.DOUBLE_CLICK;

    /**
     * A tick effect.
     * @see #get(int)
     * @hide
     */
    public static final int EFFECT_TICK = Effect_1_1.TICK;

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
    public static VibrationEffect get(int effectId, boolean fallback) {
        VibrationEffect effect = new Prebaked(effectId, fallback);
        effect.validate();
        return effect;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    public abstract void validate();

    /** @hide */
    public static class OneShot extends VibrationEffect implements Parcelable {
        private long mTiming;
        private int mAmplitude;

        public OneShot(Parcel in) {
            this(in.readLong(), in.readInt());
        }

        public OneShot(long milliseconds, int amplitude) {
            mTiming = milliseconds;
            mAmplitude = amplitude;
        }

        public long getTiming() {
            return mTiming;
        }

        public int getAmplitude() {
            return mAmplitude;
        }

        @Override
        public void validate() {
            if (mAmplitude < -1 || mAmplitude == 0 || mAmplitude > 255) {
                throw new IllegalArgumentException(
                        "amplitude must either be DEFAULT_AMPLITUDE, " +
                        "or between 1 and 255 inclusive (amplitude=" + mAmplitude + ")");
            }
            if (mTiming <= 0) {
                throw new IllegalArgumentException(
                        "timing must be positive (timing=" + mTiming + ")");
            }
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof VibrationEffect.OneShot)) {
                return false;
            }
            VibrationEffect.OneShot other = (VibrationEffect.OneShot) o;
            return other.mTiming == mTiming && other.mAmplitude == mAmplitude;
        }

        @Override
        public int hashCode() {
            int result = 17;
            result = 37 * (int) mTiming;
            result = 37 * mAmplitude;
            return result;
        }

        @Override
        public String toString() {
            return "OneShot{mTiming=" + mTiming +", mAmplitude=" + mAmplitude + "}";
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            out.writeInt(PARCEL_TOKEN_ONE_SHOT);
            out.writeLong(mTiming);
            out.writeInt(mAmplitude);
        }

        public static final Parcelable.Creator<OneShot> CREATOR =
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
    public static class Waveform extends VibrationEffect implements Parcelable {
        private long[] mTimings;
        private int[] mAmplitudes;
        private int mRepeat;

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
        public void validate() {
            if (mTimings.length != mAmplitudes.length) {
                throw new IllegalArgumentException(
                        "timing and amplitude arrays must be of equal length" +
                        " (timings.length=" + mTimings.length +
                        ", amplitudes.length=" + mAmplitudes.length + ")");
            }
            if (!hasNonZeroEntry(mTimings)) {
                throw new IllegalArgumentException("at least one timing must be non-zero" +
                        " (timings=" + Arrays.toString(mTimings) + ")");
            }
            for (long timing : mTimings) {
                if (timing < 0) {
                    throw new IllegalArgumentException("timings must all be >= 0" +
                            " (timings=" + Arrays.toString(mTimings) + ")");
                }
            }
            for (int amplitude : mAmplitudes) {
                if (amplitude < -1 || amplitude > 255) {
                    throw new IllegalArgumentException(
                            "amplitudes must all be DEFAULT_AMPLITUDE or between 0 and 255" +
                            " (amplitudes=" + Arrays.toString(mAmplitudes) + ")");
                }
            }
            if (mRepeat < -1 || mRepeat >= mTimings.length) {
                throw new IllegalArgumentException(
                        "repeat index must be within the bounds of the timings array" +
                        " (timings.length=" + mTimings.length + ", index=" + mRepeat +")");
            }
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof VibrationEffect.Waveform)) {
                return false;
            }
            VibrationEffect.Waveform other = (VibrationEffect.Waveform) o;
            return Arrays.equals(mTimings, other.mTimings) &&
                Arrays.equals(mAmplitudes, other.mAmplitudes) &&
                mRepeat == other.mRepeat;
        }

        @Override
        public int hashCode() {
            int result = 17;
            result = 37 * Arrays.hashCode(mTimings);
            result = 37 * Arrays.hashCode(mAmplitudes);
            result = 37 * mRepeat;
            return result;
        }

        @Override
        public String toString() {
            return "Waveform{mTimings=" + Arrays.toString(mTimings) +
                ", mAmplitudes=" + Arrays.toString(mAmplitudes) +
                ", mRepeat=" + mRepeat +
                "}";
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


        public static final Parcelable.Creator<Waveform> CREATOR =
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
    public static class Prebaked extends VibrationEffect implements Parcelable {
        private int mEffectId;
        private boolean mFallback;

        public Prebaked(Parcel in) {
            this(in.readInt(), in.readByte() != 0);
        }

        public Prebaked(int effectId, boolean fallback) {
            mEffectId = effectId;
            mFallback = fallback;
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
        public void validate() {
            switch (mEffectId) {
                case EFFECT_CLICK:
                case EFFECT_DOUBLE_CLICK:
                case EFFECT_TICK:
                    break;
                default:
                    throw new IllegalArgumentException(
                            "Unknown prebaked effect type (value=" + mEffectId + ")");
            }
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof VibrationEffect.Prebaked)) {
                return false;
            }
            VibrationEffect.Prebaked other = (VibrationEffect.Prebaked) o;
            return mEffectId == other.mEffectId && mFallback == other.mFallback;
        }

        @Override
        public int hashCode() {
            return mEffectId;
        }

        @Override
        public String toString() {
            return "Prebaked{mEffectId=" + mEffectId + ", mFallback=" + mFallback + "}";
        }


        @Override
        public void writeToParcel(Parcel out, int flags) {
            out.writeInt(PARCEL_TOKEN_EFFECT);
            out.writeInt(mEffectId);
            out.writeByte((byte) (mFallback ? 1 : 0));
        }

        public static final Parcelable.Creator<Prebaked> CREATOR =
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

    public static final Parcelable.Creator<VibrationEffect> CREATOR =
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
