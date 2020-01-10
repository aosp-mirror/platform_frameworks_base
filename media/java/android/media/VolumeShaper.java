/*
 * Copyright 2017 The Android Open Source Project
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
package android.media;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Objects;

/**
 * The {@code VolumeShaper} class is used to automatically control audio volume during media
 * playback, allowing simple implementation of transition effects and ducking.
 * It is created from implementations of {@code VolumeAutomation},
 * such as {@code MediaPlayer} and {@code AudioTrack} (referred to as "players" below),
 * by {@link MediaPlayer#createVolumeShaper} or {@link AudioTrack#createVolumeShaper}.
 *
 * A {@code VolumeShaper} is intended for short volume changes.
 * If the audio output sink changes during
 * a {@code VolumeShaper} transition, the precise curve position may be lost, and the
 * {@code VolumeShaper} may advance to the end of the curve for the new audio output sink.
 *
 * The {@code VolumeShaper} appears as an additional scaling on the audio output,
 * and adjusts independently of track or stream volume controls.
 */
public final class VolumeShaper implements AutoCloseable {
    /* member variables */
    private int mId;
    private final WeakReference<PlayerBase> mWeakPlayerBase;

    /* package */ VolumeShaper(
            @NonNull Configuration configuration, @NonNull PlayerBase playerBase) {
        mWeakPlayerBase = new WeakReference<PlayerBase>(playerBase);
        mId = applyPlayer(configuration, new Operation.Builder().defer().build());
    }

    /* package */ int getId() {
        return mId;
    }

    /**
     * Applies the {@link VolumeShaper.Operation} to the {@code VolumeShaper}.
     *
     * Applying {@link VolumeShaper.Operation#PLAY} after {@code PLAY}
     * or {@link VolumeShaper.Operation#REVERSE} after
     * {@code REVERSE} has no effect.
     *
     * Applying {@link VolumeShaper.Operation#PLAY} when the player
     * hasn't started will synchronously start the {@code VolumeShaper} when
     * playback begins.
     *
     * @param operation the {@code operation} to apply.
     * @throws IllegalStateException if the player is uninitialized or if there
     *         is a critical failure. In that case, the {@code VolumeShaper} should be
     *         recreated.
     */
    public void apply(@NonNull Operation operation) {
        /* void */ applyPlayer(new VolumeShaper.Configuration(mId), operation);
    }

    /**
     * Replaces the current {@code VolumeShaper}
     * {@code configuration} with a new {@code configuration}.
     *
     * This allows the user to change the volume shape
     * while the existing {@code VolumeShaper} is in effect.
     *
     * The effect of {@code replace()} is similar to an atomic close of
     * the existing {@code VolumeShaper} and creation of a new {@code VolumeShaper}.
     *
     * If the {@code operation} is {@link VolumeShaper.Operation#PLAY} then the
     * new curve starts immediately.
     *
     * If the {@code operation} is
     * {@link VolumeShaper.Operation#REVERSE}, then the new curve will
     * be delayed until {@code PLAY} is applied.
     *
     * @param configuration the new {@code configuration} to use.
     * @param operation the {@code operation} to apply to the {@code VolumeShaper}
     * @param join if true, match the start volume of the
     *             new {@code configuration} to the current volume of the existing
     *             {@code VolumeShaper}, to avoid discontinuity.
     * @throws IllegalStateException if the player is uninitialized or if there
     *         is a critical failure. In that case, the {@code VolumeShaper} should be
     *         recreated.
     */
    public void replace(
            @NonNull Configuration configuration, @NonNull Operation operation, boolean join) {
        mId = applyPlayer(
                configuration,
                new Operation.Builder(operation).replace(mId, join).build());
    }

    /**
     * Returns the current volume scale attributable to the {@code VolumeShaper}.
     *
     * This is the last volume from the {@code VolumeShaper} used for the player,
     * or the initial volume if the {@code VolumeShaper} hasn't been started with
     * {@link VolumeShaper.Operation#PLAY}.
     *
     * @return the volume, linearly represented as a value between 0.f and 1.f.
     * @throws IllegalStateException if the player is uninitialized or if there
     *         is a critical failure.  In that case, the {@code VolumeShaper} should be
     *         recreated.
     */
    public float getVolume() {
        return getStatePlayer(mId).getVolume();
    }

    /**
     * Releases the {@code VolumeShaper} object; any volume scale due to the
     * {@code VolumeShaper} is removed after closing.
     *
     * If the volume does not reach 1.f when the {@code VolumeShaper} is closed
     * (or finalized), there may be an abrupt change of volume.
     *
     * {@code close()} may be safely called after a prior {@code close()}.
     * This class implements the Java {@code AutoClosable} interface and
     * may be used with try-with-resources.
     */
    @Override
    public void close() {
        try {
            /* void */ applyPlayer(
                    new VolumeShaper.Configuration(mId),
                    new Operation.Builder().terminate().build());
        } catch (IllegalStateException ise) {
            ; // ok
        }
        if (mWeakPlayerBase != null) {
            mWeakPlayerBase.clear();
        }
    }

    @Override
    protected void finalize() {
        close(); // ensure we remove the native VolumeShaper
    }

    /**
     * Internal call to apply the {@code configuration} and {@code operation} to the player.
     * Returns a valid shaper id or throws the appropriate exception.
     * @param configuration
     * @param operation
     * @return id a non-negative shaper id.
     * @throws IllegalStateException if the player has been deallocated or is uninitialized.
     */
    private int applyPlayer(
            @NonNull VolumeShaper.Configuration configuration,
            @NonNull VolumeShaper.Operation operation) {
        final int id;
        if (mWeakPlayerBase != null) {
            PlayerBase player = mWeakPlayerBase.get();
            if (player == null) {
                throw new IllegalStateException("player deallocated");
            }
            id = player.playerApplyVolumeShaper(configuration, operation);
        } else {
            throw new IllegalStateException("uninitialized shaper");
        }
        if (id < 0) {
            // TODO - get INVALID_OPERATION from platform.
            final int VOLUME_SHAPER_INVALID_OPERATION = -38; // must match with platform
            // Due to RPC handling, we translate integer codes to exceptions right before
            // delivering to the user.
            if (id == VOLUME_SHAPER_INVALID_OPERATION) {
                throw new IllegalStateException("player or VolumeShaper deallocated");
            } else {
                throw new IllegalArgumentException("invalid configuration or operation: " + id);
            }
        }
        return id;
    }

    /**
     * Internal call to retrieve the current {@code VolumeShaper} state.
     * @param id
     * @return the current {@code VolumeShaper.State}
     * @throws IllegalStateException if the player has been deallocated or is uninitialized.
     */
    private @NonNull VolumeShaper.State getStatePlayer(int id) {
        final VolumeShaper.State state;
        if (mWeakPlayerBase != null) {
            PlayerBase player = mWeakPlayerBase.get();
            if (player == null) {
                throw new IllegalStateException("player deallocated");
            }
            state = player.playerGetVolumeShaperState(id);
        } else {
            throw new IllegalStateException("uninitialized shaper");
        }
        if (state == null) {
            throw new IllegalStateException("shaper cannot be found");
        }
        return state;
    }

    /**
     * The {@code VolumeShaper.Configuration} class contains curve
     * and duration information.
     * It is constructed by the {@link VolumeShaper.Configuration.Builder}.
     * <p>
     * A {@code VolumeShaper.Configuration} is used by
     * {@link VolumeAutomation#createVolumeShaper(Configuration)
     * VolumeAutomation.createVolumeShaper(Configuration)} to create
     * a {@code VolumeShaper} and
     * by {@link VolumeShaper#replace(Configuration, Operation, boolean)
     * VolumeShaper.replace(Configuration, Operation, boolean)}
     * to replace an existing {@code configuration}.
     * <p>
     * The {@link AudioTrack} and {@link MediaPlayer} classes implement
     * the {@link VolumeAutomation} interface.
     */
    public static final class Configuration implements Parcelable {
        private static final int MAXIMUM_CURVE_POINTS = 16;

        /**
         * Returns the maximum number of curve points allowed for
         * {@link VolumeShaper.Builder#setCurve(float[], float[])}.
         */
        public static int getMaximumCurvePoints() {
            return MAXIMUM_CURVE_POINTS;
        }

        // These values must match the native VolumeShaper::Configuration::Type
        /** @hide */
        @IntDef({
            TYPE_ID,
            TYPE_SCALE,
            })
        @Retention(RetentionPolicy.SOURCE)
        public @interface Type {}

        /**
         * Specifies a {@link VolumeShaper} handle created by {@link #VolumeShaper(int)}
         * from an id returned by {@code setVolumeShaper()}.
         * The type, curve, etc. may not be queried from
         * a {@code VolumeShaper} object of this type;
         * the handle is used to identify and change the operation of
         * an existing {@code VolumeShaper} sent to the player.
         */
        /* package */ static final int TYPE_ID = 0;

        /**
         * Specifies a {@link VolumeShaper} to be used
         * as an additional scale to the current volume.
         * This is created by the {@link VolumeShaper.Builder}.
         */
        /* package */ static final int TYPE_SCALE = 1;

        // These values must match the native InterpolatorType enumeration.
        /** @hide */
        @IntDef({
            INTERPOLATOR_TYPE_STEP,
            INTERPOLATOR_TYPE_LINEAR,
            INTERPOLATOR_TYPE_CUBIC,
            INTERPOLATOR_TYPE_CUBIC_MONOTONIC,
            })
        @Retention(RetentionPolicy.SOURCE)
        public @interface InterpolatorType {}

        /**
         * Stepwise volume curve.
         */
        public static final int INTERPOLATOR_TYPE_STEP = 0;

        /**
         * Linear interpolated volume curve.
         */
        public static final int INTERPOLATOR_TYPE_LINEAR = 1;

        /**
         * Cubic interpolated volume curve.
         * This is default if unspecified.
         */
        public static final int INTERPOLATOR_TYPE_CUBIC = 2;

        /**
         * Cubic interpolated volume curve
         * that preserves local monotonicity.
         * So long as the control points are locally monotonic,
         * the curve interpolation between those points are monotonic.
         * This is useful for cubic spline interpolated
         * volume ramps and ducks.
         */
        public static final int INTERPOLATOR_TYPE_CUBIC_MONOTONIC = 3;

        // These values must match the native VolumeShaper::Configuration::InterpolatorType
        /** @hide */
        @IntDef({
            OPTION_FLAG_VOLUME_IN_DBFS,
            OPTION_FLAG_CLOCK_TIME,
            })
        @Retention(RetentionPolicy.SOURCE)
        public @interface OptionFlag {}

        /**
         * @hide
         * Use a dB full scale volume range for the volume curve.
         *<p>
         * The volume scale is typically from 0.f to 1.f on a linear scale;
         * this option changes to -inf to 0.f on a db full scale,
         * where 0.f is equivalent to a scale of 1.f.
         */
        public static final int OPTION_FLAG_VOLUME_IN_DBFS = (1 << 0);

        /**
         * @hide
         * Use clock time instead of media time.
         *<p>
         * The default implementation of {@code VolumeShaper} is to apply
         * volume changes by the media time of the player.
         * Hence, the {@code VolumeShaper} will speed or slow down to
         * match player changes of playback rate, pause, or resume.
         *<p>
         * The {@code OPTION_FLAG_CLOCK_TIME} option allows the {@code VolumeShaper}
         * progress to be determined by clock time instead of media time.
         */
        public static final int OPTION_FLAG_CLOCK_TIME = (1 << 1);

        private static final int OPTION_FLAG_PUBLIC_ALL =
                OPTION_FLAG_VOLUME_IN_DBFS | OPTION_FLAG_CLOCK_TIME;

        /**
         * A one second linear ramp from silence to full volume.
         * Use {@link VolumeShaper.Builder#reflectTimes()}
         * or {@link VolumeShaper.Builder#invertVolumes()} to generate
         * the matching linear duck.
         */
        public static final Configuration LINEAR_RAMP = new VolumeShaper.Configuration.Builder()
                .setInterpolatorType(INTERPOLATOR_TYPE_LINEAR)
                .setCurve(new float[] {0.f, 1.f} /* times */,
                        new float[] {0.f, 1.f} /* volumes */)
                .setDuration(1000)
                .build();

        /**
         * A one second cubic ramp from silence to full volume.
         * Use {@link VolumeShaper.Builder#reflectTimes()}
         * or {@link VolumeShaper.Builder#invertVolumes()} to generate
         * the matching cubic duck.
         */
        public static final Configuration CUBIC_RAMP = new VolumeShaper.Configuration.Builder()
                .setInterpolatorType(INTERPOLATOR_TYPE_CUBIC)
                .setCurve(new float[] {0.f, 1.f} /* times */,
                        new float[] {0.f, 1.f}  /* volumes */)
                .setDuration(1000)
                .build();

        /**
         * A one second sine curve
         * from silence to full volume for energy preserving cross fades.
         * Use {@link VolumeShaper.Builder#reflectTimes()} to generate
         * the matching cosine duck.
         */
        public static final Configuration SINE_RAMP;

        /**
         * A one second sine-squared s-curve ramp
         * from silence to full volume.
         * Use {@link VolumeShaper.Builder#reflectTimes()}
         * or {@link VolumeShaper.Builder#invertVolumes()} to generate
         * the matching sine-squared s-curve duck.
         */
        public static final Configuration SCURVE_RAMP;

        static {
            final int POINTS = MAXIMUM_CURVE_POINTS;
            final float times[] = new float[POINTS];
            final float sines[] = new float[POINTS];
            final float scurve[] = new float[POINTS];
            for (int i = 0; i < POINTS; ++i) {
                times[i] = (float)i / (POINTS - 1);
                final float sine = (float)Math.sin(times[i] * Math.PI / 2.);
                sines[i] = sine;
                scurve[i] = sine * sine;
            }
            SINE_RAMP = new VolumeShaper.Configuration.Builder()
                .setInterpolatorType(INTERPOLATOR_TYPE_CUBIC)
                .setCurve(times, sines)
                .setDuration(1000)
                .build();
            SCURVE_RAMP = new VolumeShaper.Configuration.Builder()
                .setInterpolatorType(INTERPOLATOR_TYPE_CUBIC)
                .setCurve(times, scurve)
                .setDuration(1000)
                .build();
        }

        /*
         * member variables - these are all final
         */

        // type of VolumeShaper
        @UnsupportedAppUsage
        private final int mType;

        // valid when mType is TYPE_ID
        @UnsupportedAppUsage
        private final int mId;

        // valid when mType is TYPE_SCALE
        @UnsupportedAppUsage
        private final int mOptionFlags;
        @UnsupportedAppUsage
        private final double mDurationMs;
        @UnsupportedAppUsage
        private final int mInterpolatorType;
        @UnsupportedAppUsage
        private final float[] mTimes;
        @UnsupportedAppUsage
        private final float[] mVolumes;

        @Override
        public String toString() {
            return "VolumeShaper.Configuration{"
                    + "mType = " + mType
                    + ", mId = " + mId
                    + (mType == TYPE_ID
                        ? "}"
                        : ", mOptionFlags = 0x" + Integer.toHexString(mOptionFlags).toUpperCase()
                        + ", mDurationMs = " + mDurationMs
                        + ", mInterpolatorType = " + mInterpolatorType
                        + ", mTimes[] = " + Arrays.toString(mTimes)
                        + ", mVolumes[] = " + Arrays.toString(mVolumes)
                        + "}");
        }

        @Override
        public int hashCode() {
            return mType == TYPE_ID
                    ? Objects.hash(mType, mId)
                    : Objects.hash(mType, mId,
                            mOptionFlags, mDurationMs, mInterpolatorType,
                            Arrays.hashCode(mTimes), Arrays.hashCode(mVolumes));
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Configuration)) return false;
            if (o == this) return true;
            final Configuration other = (Configuration) o;
            // Note that exact floating point equality may not be guaranteed
            // for a theoretically idempotent operation; for example,
            // there are many cases where a + b - b != a.
            return mType == other.mType
                    && mId == other.mId
                    && (mType == TYPE_ID
                        ||  (mOptionFlags == other.mOptionFlags
                            && mDurationMs == other.mDurationMs
                            && mInterpolatorType == other.mInterpolatorType
                            && Arrays.equals(mTimes, other.mTimes)
                            && Arrays.equals(mVolumes, other.mVolumes)));
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            // this needs to match the native VolumeShaper.Configuration parceling
            dest.writeInt(mType);
            dest.writeInt(mId);
            if (mType != TYPE_ID) {
                dest.writeInt(mOptionFlags);
                dest.writeDouble(mDurationMs);
                // this needs to match the native Interpolator parceling
                dest.writeInt(mInterpolatorType);
                dest.writeFloat(0.f); // first slope (specifying for native side)
                dest.writeFloat(0.f); // last slope (specifying for native side)
                // mTimes and mVolumes should have the same length.
                dest.writeInt(mTimes.length);
                for (int i = 0; i < mTimes.length; ++i) {
                    dest.writeFloat(mTimes[i]);
                    dest.writeFloat(mVolumes[i]);
                }
            }
        }

        public static final @android.annotation.NonNull Parcelable.Creator<VolumeShaper.Configuration> CREATOR
                = new Parcelable.Creator<VolumeShaper.Configuration>() {
            @Override
            public VolumeShaper.Configuration createFromParcel(Parcel p) {
                // this needs to match the native VolumeShaper.Configuration parceling
                final int type = p.readInt();
                final int id = p.readInt();
                if (type == TYPE_ID) {
                    return new VolumeShaper.Configuration(id);
                } else {
                    final int optionFlags = p.readInt();
                    final double durationMs = p.readDouble();
                    // this needs to match the native Interpolator parceling
                    final int interpolatorType = p.readInt();
                    final float firstSlope = p.readFloat(); // ignored on the Java side
                    final float lastSlope = p.readFloat();  // ignored on the Java side
                    final int length = p.readInt();
                    final float[] times = new float[length];
                    final float[] volumes = new float[length];
                    for (int i = 0; i < length; ++i) {
                        times[i] = p.readFloat();
                        volumes[i] = p.readFloat();
                    }

                    return new VolumeShaper.Configuration(
                        type,
                        id,
                        optionFlags,
                        durationMs,
                        interpolatorType,
                        times,
                        volumes);
                }
            }

            @Override
            public VolumeShaper.Configuration[] newArray(int size) {
                return new VolumeShaper.Configuration[size];
            }
        };

        /**
         * @hide
         * Constructs a {@code VolumeShaper} from an id.
         *
         * This is an opaque handle for controlling a {@code VolumeShaper} that has
         * already been sent to a player.  The {@code id} is returned from the
         * initial {@code setVolumeShaper()} call on success.
         *
         * These configurations are for native use only,
         * they are never returned directly to the user.
         *
         * @param id
         * @throws IllegalArgumentException if id is negative.
         */
        public Configuration(int id) {
            if (id < 0) {
                throw new IllegalArgumentException("negative id " + id);
            }
            mType = TYPE_ID;
            mId = id;
            mInterpolatorType = 0;
            mOptionFlags = 0;
            mDurationMs = 0;
            mTimes = null;
            mVolumes = null;
        }

        /**
         * Direct constructor for VolumeShaper.
         * Use the Builder instead.
         */
        @UnsupportedAppUsage
        private Configuration(@Type int type,
                int id,
                @OptionFlag int optionFlags,
                double durationMs,
                @InterpolatorType int interpolatorType,
                @NonNull float[] times,
                @NonNull float[] volumes) {
            mType = type;
            mId = id;
            mOptionFlags = optionFlags;
            mDurationMs = durationMs;
            mInterpolatorType = interpolatorType;
            // Builder should have cloned these arrays already.
            mTimes = times;
            mVolumes = volumes;
        }

        /**
         * @hide
         * Returns the {@code VolumeShaper} type.
         */
        public @Type int getType() {
            return mType;
        }

        /**
         * @hide
         * Returns the {@code VolumeShaper} id.
         */
        public int getId() {
            return mId;
        }

        /**
         * Returns the interpolator type.
         */
        public @InterpolatorType int getInterpolatorType() {
            return mInterpolatorType;
        }

        /**
         * @hide
         * Returns the option flags
         */
        public @OptionFlag int getOptionFlags() {
            return mOptionFlags & OPTION_FLAG_PUBLIC_ALL;
        }

        /* package */ @OptionFlag int getAllOptionFlags() {
            return mOptionFlags;
        }

        /**
         * Returns the duration of the volume shape in milliseconds.
         */
        public long getDuration() {
            // casting is safe here as the duration was set as a long in the Builder
            return (long) mDurationMs;
        }

        /**
         * Returns the times (x) coordinate array of the volume curve points.
         */
        public float[] getTimes() {
            return mTimes;
        }

        /**
         * Returns the volumes (y) coordinate array of the volume curve points.
         */
        public float[] getVolumes() {
            return mVolumes;
        }

        /**
         * Checks the validity of times and volumes point representation.
         *
         * {@code times[]} and {@code volumes[]} are two arrays representing points
         * for the volume curve.
         *
         * Note that {@code times[]} and {@code volumes[]} are explicitly checked against
         * null here to provide the proper error string - those are legitimate
         * arguments to this method.
         *
         * @param times the x coordinates for the points,
         *        must be between 0.f and 1.f and be monotonic.
         * @param volumes the y coordinates for the points,
         *        must be between 0.f and 1.f for linear and
         *        must be no greater than 0.f for log (dBFS).
         * @param log set to true if the scale is logarithmic.
         * @return null if no error, or the reason in a {@code String} for an error.
         */
        private static @Nullable String checkCurveForErrors(
                @Nullable float[] times, @Nullable float[] volumes, boolean log) {
            if (times == null) {
                return "times array must be non-null";
            } else if (volumes == null) {
                return "volumes array must be non-null";
            } else if (times.length != volumes.length) {
                return "array length must match";
            } else if (times.length < 2) {
                return "array length must be at least 2";
            } else if (times.length > MAXIMUM_CURVE_POINTS) {
                return "array length must be no larger than " + MAXIMUM_CURVE_POINTS;
            } else if (times[0] != 0.f) {
                return "times must start at 0.f";
            } else if (times[times.length - 1] != 1.f) {
                return "times must end at 1.f";
            }

            // validate points along the curve
            for (int i = 1; i < times.length; ++i) {
                if (!(times[i] > times[i - 1]) /* handle nan */) {
                    return "times not monotonic increasing, check index " + i;
                }
            }
            if (log) {
                for (int i = 0; i < volumes.length; ++i) {
                    if (!(volumes[i] <= 0.f) /* handle nan */) {
                        return "volumes for log scale cannot be positive, "
                                + "check index " + i;
                    }
                }
            } else {
                for (int i = 0; i < volumes.length; ++i) {
                    if (!(volumes[i] >= 0.f) || !(volumes[i] <= 1.f) /* handle nan */) {
                        return "volumes for linear scale must be between 0.f and 1.f, "
                                + "check index " + i;
                    }
                }
            }
            return null; // no errors
        }

        private static void checkCurveForErrorsAndThrowException(
                @Nullable float[] times, @Nullable float[] volumes, boolean log, boolean ise) {
            final String error = checkCurveForErrors(times, volumes, log);
            if (error != null) {
                if (ise) {
                    throw new IllegalStateException(error);
                } else {
                    throw new IllegalArgumentException(error);
                }
            }
        }

        private static void checkValidVolumeAndThrowException(float volume, boolean log) {
            if (log) {
                if (!(volume <= 0.f) /* handle nan */) {
                    throw new IllegalArgumentException("dbfs volume must be 0.f or less");
                }
            } else {
                if (!(volume >= 0.f) || !(volume <= 1.f) /* handle nan */) {
                    throw new IllegalArgumentException("volume must be >= 0.f and <= 1.f");
                }
            }
        }

        private static void clampVolume(float[] volumes, boolean log) {
            if (log) {
                for (int i = 0; i < volumes.length; ++i) {
                    if (!(volumes[i] <= 0.f) /* handle nan */) {
                        volumes[i] = 0.f;
                    }
                }
            } else {
                for (int i = 0; i < volumes.length; ++i) {
                    if (!(volumes[i] >= 0.f) /* handle nan */) {
                        volumes[i] = 0.f;
                    } else if (!(volumes[i] <= 1.f)) {
                        volumes[i] = 1.f;
                    }
                }
            }
        }

        /**
         * Builder class for a {@link VolumeShaper.Configuration} object.
         * <p> Here is an example where {@code Builder} is used to define the
         * {@link VolumeShaper.Configuration}.
         *
         * <pre class="prettyprint">
         * VolumeShaper.Configuration LINEAR_RAMP =
         *         new VolumeShaper.Configuration.Builder()
         *             .setInterpolatorType(VolumeShaper.Configuration.INTERPOLATOR_TYPE_LINEAR)
         *             .setCurve(new float[] { 0.f, 1.f }, // times
         *                       new float[] { 0.f, 1.f }) // volumes
         *             .setDuration(1000)
         *             .build();
         * </pre>
         * <p>
         */
        public static final class Builder {
            private int mType = TYPE_SCALE;
            private int mId = -1; // invalid
            private int mInterpolatorType = INTERPOLATOR_TYPE_CUBIC;
            private int mOptionFlags = OPTION_FLAG_CLOCK_TIME;
            private double mDurationMs = 1000.;
            private float[] mTimes = null;
            private float[] mVolumes = null;

            /**
             * Constructs a new {@code Builder} with the defaults.
             */
            public Builder() {
            }

            /**
             * Constructs a new {@code Builder} with settings
             * copied from a given {@code VolumeShaper.Configuration}.
             * @param configuration prototypical configuration
             *        which will be reused in the new {@code Builder}.
             */
            public Builder(@NonNull Configuration configuration) {
                mType = configuration.getType();
                mId = configuration.getId();
                mOptionFlags = configuration.getAllOptionFlags();
                mInterpolatorType = configuration.getInterpolatorType();
                mDurationMs = configuration.getDuration();
                mTimes = configuration.getTimes().clone();
                mVolumes = configuration.getVolumes().clone();
            }

            /**
             * @hide
             * Set the {@code id} for system defined shapers.
             * @param id the {@code id} to set. If non-negative, then it is used.
             *        If -1, then the system is expected to assign one.
             * @return the same {@code Builder} instance.
             * @throws IllegalArgumentException if {@code id} < -1.
             */
            public @NonNull Builder setId(int id) {
                if (id < -1) {
                    throw new IllegalArgumentException("invalid id: " + id);
                }
                mId = id;
                return this;
            }

            /**
             * Sets the interpolator type.
             *
             * If omitted the default interpolator type is {@link #INTERPOLATOR_TYPE_CUBIC}.
             *
             * @param interpolatorType method of interpolation used for the volume curve.
             *        One of {@link #INTERPOLATOR_TYPE_STEP},
             *        {@link #INTERPOLATOR_TYPE_LINEAR},
             *        {@link #INTERPOLATOR_TYPE_CUBIC},
             *        {@link #INTERPOLATOR_TYPE_CUBIC_MONOTONIC}.
             * @return the same {@code Builder} instance.
             * @throws IllegalArgumentException if {@code interpolatorType} is not valid.
             */
            public @NonNull Builder setInterpolatorType(@InterpolatorType int interpolatorType) {
                switch (interpolatorType) {
                    case INTERPOLATOR_TYPE_STEP:
                    case INTERPOLATOR_TYPE_LINEAR:
                    case INTERPOLATOR_TYPE_CUBIC:
                    case INTERPOLATOR_TYPE_CUBIC_MONOTONIC:
                        mInterpolatorType = interpolatorType;
                        break;
                    default:
                        throw new IllegalArgumentException("invalid interpolatorType: "
                                + interpolatorType);
                }
                return this;
            }

            /**
             * @hide
             * Sets the optional flags
             *
             * If omitted, flags are 0. If {@link #OPTION_FLAG_VOLUME_IN_DBFS} has
             * changed the volume curve needs to be set again as the acceptable
             * volume domain has changed.
             *
             * @param optionFlags new value to replace the old {@code optionFlags}.
             * @return the same {@code Builder} instance.
             * @throws IllegalArgumentException if flag is not recognized.
             */
            @TestApi
            public @NonNull Builder setOptionFlags(@OptionFlag int optionFlags) {
                if ((optionFlags & ~OPTION_FLAG_PUBLIC_ALL) != 0) {
                    throw new IllegalArgumentException("invalid bits in flag: " + optionFlags);
                }
                mOptionFlags = mOptionFlags & ~OPTION_FLAG_PUBLIC_ALL | optionFlags;
                return this;
            }

            /**
             * Sets the {@code VolumeShaper} duration in milliseconds.
             *
             * If omitted, the default duration is 1 second.
             *
             * @param durationMillis
             * @return the same {@code Builder} instance.
             * @throws IllegalArgumentException if {@code durationMillis}
             *         is not strictly positive.
             */
            public @NonNull Builder setDuration(long durationMillis) {
                if (durationMillis <= 0) {
                    throw new IllegalArgumentException(
                            "duration: " + durationMillis + " not positive");
                }
                mDurationMs = (double) durationMillis;
                return this;
            }

            /**
             * Sets the volume curve.
             *
             * The volume curve is represented by a set of control points given by
             * two float arrays of equal length,
             * one representing the time (x) coordinates
             * and one corresponding to the volume (y) coordinates.
             * The length must be at least 2
             * and no greater than {@link VolumeShaper.Configuration#getMaximumCurvePoints()}.
             * <p>
             * The volume curve is normalized as follows:
             * time (x) coordinates should be monotonically increasing, from 0.f to 1.f;
             * volume (y) coordinates must be within 0.f to 1.f.
             * <p>
             * The time scale is set by {@link #setDuration}.
             * <p>
             * @param times an array of float values representing
             *        the time line of the volume curve.
             * @param volumes an array of float values representing
             *        the amplitude of the volume curve.
             * @return the same {@code Builder} instance.
             * @throws IllegalArgumentException if {@code times} or {@code volumes} is invalid.
             */

            /* Note: volume (y) coordinates must be non-positive for log scaling,
             * if {@link VolumeShaper.Configuration#OPTION_FLAG_VOLUME_IN_DBFS} is set.
             */

            public @NonNull Builder setCurve(@NonNull float[] times, @NonNull float[] volumes) {
                final boolean log = (mOptionFlags & OPTION_FLAG_VOLUME_IN_DBFS) != 0;
                checkCurveForErrorsAndThrowException(times, volumes, log, false /* ise */);
                mTimes = times.clone();
                mVolumes = volumes.clone();
                return this;
            }

            /**
             * Reflects the volume curve so that
             * the shaper changes volume from the end
             * to the start.
             *
             * @return the same {@code Builder} instance.
             * @throws IllegalStateException if curve has not been set.
             */
            public @NonNull Builder reflectTimes() {
                final boolean log = (mOptionFlags & OPTION_FLAG_VOLUME_IN_DBFS) != 0;
                checkCurveForErrorsAndThrowException(mTimes, mVolumes, log, true /* ise */);
                int i;
                for (i = 0; i < mTimes.length / 2; ++i) {
                    float temp = mTimes[i];
                    mTimes[i] = 1.f - mTimes[mTimes.length - 1 - i];
                    mTimes[mTimes.length - 1 - i] = 1.f - temp;
                    temp = mVolumes[i];
                    mVolumes[i] = mVolumes[mVolumes.length - 1 - i];
                    mVolumes[mVolumes.length - 1 - i] = temp;
                }
                if ((mTimes.length & 1) != 0) {
                    mTimes[i] = 1.f - mTimes[i];
                }
                return this;
            }

            /**
             * Inverts the volume curve so that the max volume
             * becomes the min volume and vice versa.
             *
             * @return the same {@code Builder} instance.
             * @throws IllegalStateException if curve has not been set.
             */
            public @NonNull Builder invertVolumes() {
                final boolean log = (mOptionFlags & OPTION_FLAG_VOLUME_IN_DBFS) != 0;
                checkCurveForErrorsAndThrowException(mTimes, mVolumes, log, true /* ise */);
                float min = mVolumes[0];
                float max = mVolumes[0];
                for (int i = 1; i < mVolumes.length; ++i) {
                    if (mVolumes[i] < min) {
                        min = mVolumes[i];
                    } else if (mVolumes[i] > max) {
                        max = mVolumes[i];
                    }
                }

                final float maxmin = max + min;
                for (int i = 0; i < mVolumes.length; ++i) {
                    mVolumes[i] = maxmin - mVolumes[i];
                }
                return this;
            }

            /**
             * Scale the curve end volume to a target value.
             *
             * Keeps the start volume the same.
             * This works best if the volume curve is monotonic.
             *
             * @param volume the target end volume to use.
             * @return the same {@code Builder} instance.
             * @throws IllegalArgumentException if {@code volume} is not valid.
             * @throws IllegalStateException if curve has not been set.
             */
            public @NonNull Builder scaleToEndVolume(float volume) {
                final boolean log = (mOptionFlags & OPTION_FLAG_VOLUME_IN_DBFS) != 0;
                checkCurveForErrorsAndThrowException(mTimes, mVolumes, log, true /* ise */);
                checkValidVolumeAndThrowException(volume, log);
                final float startVolume = mVolumes[0];
                final float endVolume = mVolumes[mVolumes.length - 1];
                if (endVolume == startVolume) {
                    // match with linear ramp
                    final float offset = volume - startVolume;
                    for (int i = 0; i < mVolumes.length; ++i) {
                        mVolumes[i] = mVolumes[i] + offset * mTimes[i];
                    }
                } else {
                    // scale
                    final float scale = (volume - startVolume) / (endVolume - startVolume);
                    for (int i = 0; i < mVolumes.length; ++i) {
                        mVolumes[i] = scale * (mVolumes[i] - startVolume) + startVolume;
                    }
                }
                clampVolume(mVolumes, log);
                return this;
            }

            /**
             * Scale the curve start volume to a target value.
             *
             * Keeps the end volume the same.
             * This works best if the volume curve is monotonic.
             *
             * @param volume the target start volume to use.
             * @return the same {@code Builder} instance.
             * @throws IllegalArgumentException if {@code volume} is not valid.
             * @throws IllegalStateException if curve has not been set.
             */
            public @NonNull Builder scaleToStartVolume(float volume) {
                final boolean log = (mOptionFlags & OPTION_FLAG_VOLUME_IN_DBFS) != 0;
                checkCurveForErrorsAndThrowException(mTimes, mVolumes, log, true /* ise */);
                checkValidVolumeAndThrowException(volume, log);
                final float startVolume = mVolumes[0];
                final float endVolume = mVolumes[mVolumes.length - 1];
                if (endVolume == startVolume) {
                    // match with linear ramp
                    final float offset = volume - startVolume;
                    for (int i = 0; i < mVolumes.length; ++i) {
                        mVolumes[i] = mVolumes[i] + offset * (1.f - mTimes[i]);
                    }
                } else {
                    final float scale = (volume - endVolume) / (startVolume - endVolume);
                    for (int i = 0; i < mVolumes.length; ++i) {
                        mVolumes[i] = scale * (mVolumes[i] - endVolume) + endVolume;
                    }
                }
                clampVolume(mVolumes, log);
                return this;
            }

            /**
             * Builds a new {@link VolumeShaper} object.
             *
             * @return a new {@link VolumeShaper} object.
             * @throws IllegalStateException if curve is not properly set.
             */
            public @NonNull Configuration build() {
                final boolean log = (mOptionFlags & OPTION_FLAG_VOLUME_IN_DBFS) != 0;
                checkCurveForErrorsAndThrowException(mTimes, mVolumes, log, true /* ise */);
                return new Configuration(mType, mId, mOptionFlags, mDurationMs,
                        mInterpolatorType, mTimes, mVolumes);
            }
        } // Configuration.Builder
    } // Configuration

    /**
     * The {@code VolumeShaper.Operation} class is used to specify operations
     * to the {@code VolumeShaper} that affect the volume change.
     */
    public static final class Operation implements Parcelable {
        /**
         * Forward playback from current volume time position.
         * At the end of the {@code VolumeShaper} curve,
         * the last volume value persists.
         */
        public static final Operation PLAY =
                new VolumeShaper.Operation.Builder()
                    .build();

        /**
         * Reverse playback from current volume time position.
         * When the position reaches the start of the {@code VolumeShaper} curve,
         * the first volume value persists.
         */
        public static final Operation REVERSE =
                new VolumeShaper.Operation.Builder()
                    .reverse()
                    .build();

        // No user serviceable parts below.

        // These flags must match the native VolumeShaper::Operation::Flag
        /** @hide */
        @IntDef({
            FLAG_NONE,
            FLAG_REVERSE,
            FLAG_TERMINATE,
            FLAG_JOIN,
            FLAG_DEFER,
            })
        @Retention(RetentionPolicy.SOURCE)
        public @interface Flag {}

        /**
         * No special {@code VolumeShaper} operation.
         */
        private static final int FLAG_NONE = 0;

        /**
         * Reverse the {@code VolumeShaper} progress.
         *
         * Reverses the {@code VolumeShaper} curve from its current
         * position. If the {@code VolumeShaper} curve has not started,
         * it automatically is considered finished.
         */
        private static final int FLAG_REVERSE = 1 << 0;

        /**
         * Terminate the existing {@code VolumeShaper}.
         * This flag is generally used by itself;
         * it takes precedence over all other flags.
         */
        private static final int FLAG_TERMINATE = 1 << 1;

        /**
         * Attempt to join as best as possible to the previous {@code VolumeShaper}.
         * This requires the previous {@code VolumeShaper} to be active and
         * {@link #setReplaceId} to be set.
         */
        private static final int FLAG_JOIN = 1 << 2;

        /**
         * Defer playback until next operation is sent. This is used
         * when starting a {@code VolumeShaper} effect.
         */
        private static final int FLAG_DEFER = 1 << 3;

        /**
         * Use the id specified in the configuration, creating
         * {@code VolumeShaper} as needed; the configuration should be
         * TYPE_SCALE.
         */
        private static final int FLAG_CREATE_IF_NEEDED = 1 << 4;

        private static final int FLAG_PUBLIC_ALL = FLAG_REVERSE | FLAG_TERMINATE;

        @UnsupportedAppUsage
        private final int mFlags;
        @UnsupportedAppUsage
        private final int mReplaceId;
        @UnsupportedAppUsage
        private final float mXOffset;

        @Override
        public String toString() {
            return "VolumeShaper.Operation{"
                    + "mFlags = 0x" + Integer.toHexString(mFlags).toUpperCase()
                    + ", mReplaceId = " + mReplaceId
                    + ", mXOffset = " + mXOffset
                    + "}";
        }

        @Override
        public int hashCode() {
            return Objects.hash(mFlags, mReplaceId, mXOffset);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Operation)) return false;
            if (o == this) return true;
            final Operation other = (Operation) o;

            return mFlags == other.mFlags
                    && mReplaceId == other.mReplaceId
                    && Float.compare(mXOffset, other.mXOffset) == 0;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            // this needs to match the native VolumeShaper.Operation parceling
            dest.writeInt(mFlags);
            dest.writeInt(mReplaceId);
            dest.writeFloat(mXOffset);
        }

        public static final @android.annotation.NonNull Parcelable.Creator<VolumeShaper.Operation> CREATOR
                = new Parcelable.Creator<VolumeShaper.Operation>() {
            @Override
            public VolumeShaper.Operation createFromParcel(Parcel p) {
                // this needs to match the native VolumeShaper.Operation parceling
                final int flags = p.readInt();
                final int replaceId = p.readInt();
                final float xOffset = p.readFloat();

                return new VolumeShaper.Operation(
                        flags
                        , replaceId
                        , xOffset);
            }

            @Override
            public VolumeShaper.Operation[] newArray(int size) {
                return new VolumeShaper.Operation[size];
            }
        };

        @UnsupportedAppUsage
        private Operation(@Flag int flags, int replaceId, float xOffset) {
            mFlags = flags;
            mReplaceId = replaceId;
            mXOffset = xOffset;
        }

        /**
         * @hide
         * {@code Builder} class for {@link VolumeShaper.Operation} object.
         *
         * Not for public use.
         */
        public static final class Builder {
            int mFlags;
            int mReplaceId;
            float mXOffset;

            /**
             * Constructs a new {@code Builder} with the defaults.
             */
            public Builder() {
                mFlags = 0;
                mReplaceId = -1;
                mXOffset = Float.NaN;
            }

            /**
             * Constructs a new {@code Builder} from a given {@code VolumeShaper.Operation}
             * @param operation the {@code VolumeShaper.operation} whose data will be
             *        reused in the new {@code Builder}.
             */
            public Builder(@NonNull VolumeShaper.Operation operation) {
                mReplaceId = operation.mReplaceId;
                mFlags = operation.mFlags;
                mXOffset = operation.mXOffset;
            }

            /**
             * Replaces the previous {@code VolumeShaper} specified by {@code id}.
             *
             * The {@code VolumeShaper} specified by the {@code id} is removed
             * if it exists. The configuration should be TYPE_SCALE.
             *
             * @param id the {@code id} of the previous {@code VolumeShaper}.
             * @param join if true, match the volume of the previous
             * shaper to the start volume of the new {@code VolumeShaper}.
             * @return the same {@code Builder} instance.
             */
            public @NonNull Builder replace(int id, boolean join) {
                mReplaceId = id;
                if (join) {
                    mFlags |= FLAG_JOIN;
                } else {
                    mFlags &= ~FLAG_JOIN;
                }
                return this;
            }

            /**
             * Defers all operations.
             * @return the same {@code Builder} instance.
             */
            public @NonNull Builder defer() {
                mFlags |= FLAG_DEFER;
                return this;
            }

            /**
             * Terminates the {@code VolumeShaper}.
             *
             * Do not call directly, use {@link VolumeShaper#close()}.
             * @return the same {@code Builder} instance.
             */
            public @NonNull Builder terminate() {
                mFlags |= FLAG_TERMINATE;
                return this;
            }

            /**
             * Reverses direction.
             * @return the same {@code Builder} instance.
             */
            public @NonNull Builder reverse() {
                mFlags ^= FLAG_REVERSE;
                return this;
            }

            /**
             * Use the id specified in the configuration, creating
             * {@code VolumeShaper} only as needed; the configuration should be
             * TYPE_SCALE.
             *
             * If the {@code VolumeShaper} with the same id already exists
             * then the operation has no effect.
             *
             * @return the same {@code Builder} instance.
             */
            public @NonNull Builder createIfNeeded() {
                mFlags |= FLAG_CREATE_IF_NEEDED;
                return this;
            }

            /**
             * Sets the {@code xOffset} to use for the {@code VolumeShaper}.
             *
             * The {@code xOffset} is the position on the volume curve,
             * and setting takes effect when the {@code VolumeShaper} is used next.
             *
             * @param xOffset a value between (or equal to) 0.f and 1.f, or Float.NaN to ignore.
             * @return the same {@code Builder} instance.
             * @throws IllegalArgumentException if {@code xOffset} is not between 0.f and 1.f,
             *         or a Float.NaN.
             */
            public @NonNull Builder setXOffset(float xOffset) {
                if (xOffset < -0.f) {
                    throw new IllegalArgumentException("Negative xOffset not allowed");
                } else if (xOffset > 1.f) {
                    throw new IllegalArgumentException("xOffset > 1.f not allowed");
                }
                // Float.NaN passes through
                mXOffset = xOffset;
                return this;
            }

            /**
             * Sets the operation flag.  Do not call this directly but one of the
             * other builder methods.
             *
             * @param flags new value for {@code flags}, consisting of ORed flags.
             * @return the same {@code Builder} instance.
             * @throws IllegalArgumentException if {@code flags} contains invalid set bits.
             */
            private @NonNull Builder setFlags(@Flag int flags) {
                if ((flags & ~FLAG_PUBLIC_ALL) != 0) {
                    throw new IllegalArgumentException("flag has unknown bits set: " + flags);
                }
                mFlags = mFlags & ~FLAG_PUBLIC_ALL | flags;
                return this;
            }

            /**
             * Builds a new {@link VolumeShaper.Operation} object.
             *
             * @return a new {@code VolumeShaper.Operation} object
             */
            public @NonNull Operation build() {
                return new Operation(mFlags, mReplaceId, mXOffset);
            }
        } // Operation.Builder
    } // Operation

    /**
     * @hide
     * {@code VolumeShaper.State} represents the current progress
     * of the {@code VolumeShaper}.
     *
     *  Not for public use.
     */
    public static final class State implements Parcelable {
        @UnsupportedAppUsage
        private float mVolume;
        @UnsupportedAppUsage
        private float mXOffset;

        @Override
        public String toString() {
            return "VolumeShaper.State{"
                    + "mVolume = " + mVolume
                    + ", mXOffset = " + mXOffset
                    + "}";
        }

        @Override
        public int hashCode() {
            return Objects.hash(mVolume, mXOffset);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof State)) return false;
            if (o == this) return true;
            final State other = (State) o;
            return mVolume == other.mVolume
                    && mXOffset == other.mXOffset;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeFloat(mVolume);
            dest.writeFloat(mXOffset);
        }

        public static final @android.annotation.NonNull Parcelable.Creator<VolumeShaper.State> CREATOR
                = new Parcelable.Creator<VolumeShaper.State>() {
            @Override
            public VolumeShaper.State createFromParcel(Parcel p) {
                return new VolumeShaper.State(
                        p.readFloat()     // volume
                        , p.readFloat()); // xOffset
            }

            @Override
            public VolumeShaper.State[] newArray(int size) {
                return new VolumeShaper.State[size];
            }
        };

        @UnsupportedAppUsage
        /* package */ State(float volume, float xOffset) {
            mVolume = volume;
            mXOffset = xOffset;
        }

        /**
         * Gets the volume of the {@link VolumeShaper.State}.
         * @return linear volume between 0.f and 1.f.
         */
        public float getVolume() {
            return mVolume;
        }

        /**
         * Gets the {@code xOffset} position on the normalized curve
         * of the {@link VolumeShaper.State}.
         * @return the curve x position between 0.f and 1.f.
         */
        public float getXOffset() {
            return mXOffset;
        }
    } // State
}
