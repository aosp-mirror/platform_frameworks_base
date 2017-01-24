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
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.Objects;

/**
 * TODO: remove @hide
 * The {@code VolumeShaper} class is used to automatically control audio volume during media
 * playback, allowing for simple implementation of transition effects and ducking.
 *
 * The {@link VolumeShaper} appears as an additional scaling on the audio output,
 * and can be used independently of track or stream volume controls.
 */
public final class VolumeShaper {
    /* member variables */
    private int mId;
    private final WeakReference<PlayerBase> mPlayerBase;
    private final WeakReference<PlayerProxy> mPlayerProxy;

    /**
     * Constructs a {@code VolumeShaper} from a {@link VolumeShaper.Configuration} and an
     * {@link AudioTrack}.
     *
     * @param configuration
     * @param audioTrack
     */
    public VolumeShaper(@NonNull Configuration configuration, @NonNull AudioTrack audioTrack) {
        this(configuration, (PlayerBase)audioTrack);
    }

    /**
     * Constructs a {@code VolumeShaper} from a {@link VolumeShaper.Configuration} and a
     * {@link MediaPlayer}.
     *
     * @param configuration
     * @param mediaPlayer
     */
    public VolumeShaper(@NonNull Configuration configuration, @NonNull MediaPlayer mediaPlayer) {
        this(configuration, (PlayerBase)mediaPlayer);
    }

    /* package */ VolumeShaper(
            @NonNull Configuration configuration, @NonNull PlayerBase playerBase) {
        mPlayerBase = new WeakReference<PlayerBase>(playerBase);
        mPlayerProxy = null;
        mId = applyPlayer(configuration, new Operation.Builder().defer().build());
    }

    /**
     * @hide
     * TODO SystemApi
     * Constructs a {@code VolumeShaper} from a {@link VolumeShaper.Configuration} and a
     * {@code PlayerProxy} object.  The PlayerProxy object requires that the configuration
     * be set with a system VolumeShaper id (this is a reserved value).
     *
     * @param configuration
     * @param playerProxy
     */
    public VolumeShaper(
            @NonNull Configuration configuration, @NonNull PlayerProxy playerProxy) {
        if (configuration.getId() < 0) {
            throw new IllegalArgumentException("playerProxy configuration id must be specified");
        }
        mPlayerProxy = new WeakReference<PlayerProxy>(playerProxy);
        mPlayerBase = null;
        mId = applyPlayer(configuration, new Operation.Builder().defer().build());
    }

    /* package */ int getId() {
        return mId;
    }

    /**
     * Applies the {@link VolumeShaper.Operation} to the {@code VolumeShaper}.
     * @param operation
     */
    public void apply(@NonNull Operation operation) {
        /* void */ applyPlayer(new VolumeShaper.Configuration(mId), operation);
    }

    /**
     * Replaces the current {@code VolumeShaper}
     * configuration with a new configuration.
     *
     * This can be used to dynamically change the {@code VolumeShaper}
     * configuration by joining several
     * {@code VolumeShaper} configurations together.
     * This is useful if the user changes the volume while the
     * {@code VolumeShaper} is in effect.
     *
     * @param configuration
     * @param operation
     * @param join
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
     * @return the volume, linearly represented as a value between 0.f and 1.f.
     */
    public float getVolume() {
        return getStatePlayer(mId).getVolume();
    }

    /**
     * Releases the {@code VolumeShaper}. Any volume scale due to the
     * {@code VolumeShaper} is removed.
     */
    public void release() {
        try {
            /* void */ applyPlayer(
                    new VolumeShaper.Configuration(mId),
                    new Operation.Builder().terminate().build());
        } catch (IllegalStateException ise) {
            ; // ok
        }
        if (mPlayerBase != null) {
            mPlayerBase.clear();
        }
        if (mPlayerProxy != null) {
            mPlayerProxy.clear();
        }
    }

    @Override
    protected void finalize() {
        release(); // ensure we remove the native volume shaper
    }

    /**
     * Internal call to apply the configuration and operation to the Player.
     * Returns a valid shaper id or throws the appropriate exception.
     * @param configuration
     * @param operation
     * @return id a non-negative shaper id.
     */
    private int applyPlayer(
            @NonNull VolumeShaper.Configuration configuration,
            @NonNull VolumeShaper.Operation operation) {
        final int id;
        if (mPlayerProxy != null) {
            // The PlayerProxy accepts only one way transactions so
            // the Configuration must have an id set to one of the system
            // ids (a positive value less than 16).
            PlayerProxy player = mPlayerProxy.get();
            if (player == null) {
                throw new IllegalStateException("player deallocated");
            }
            id = configuration.getId();
            if (id < 0) {
                throw new IllegalArgumentException("proxy requires configuration with id");
            }
            player.applyVolumeShaper(configuration, operation);
        } else if (mPlayerBase != null) {
            PlayerBase player = mPlayerBase.get();
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
                throw new IllegalStateException("player or volume shaper deallocated");
            } else {
                throw new IllegalArgumentException("invalid configuration or operation: " + id);
            }
        }
        return id;
    }

    /**
     * Internal call to retrieve the current VolumeShaper state.
     * @param id
     * @return the current {@vode VolumeShaper.State}
     */
    private @NonNull VolumeShaper.State getStatePlayer(int id) {
        final VolumeShaper.State state;
        if (mPlayerProxy != null) {
            PlayerProxy player = mPlayerProxy.get();
            if (player == null) {
                throw new IllegalStateException("player deallocated");
            }
            throw new IllegalStateException("getStatePlayer not permitted through proxy");
        } else if (mPlayerBase != null) {
            PlayerBase player = mPlayerBase.get();
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
     * The {@code VolumeShaper.Configuration} class contains curve shape
     * and parameter information for constructing a {@code VolumeShaper}.
     * This curve shape and parameter information is specified
     * on {@code VolumeShaper} creation
     * and may be replaced through {@link VolumeShaper#replace}.
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
         * with local monotonicity preservation.
         * So long as the control points are locally monotonic,
         * the curve interpolation will also be locally monotonic.
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
         * Use a dB full scale volume range for the volume curve.
         *<p>
         * The volume scale is typically from 0.f to 1.f on a linear scale;
         * this option changes to -inf to 0.f on a db full scale,
         * where 0.f is equivalent to a scale of 1.f.
         */
        public static final int OPTION_FLAG_VOLUME_IN_DBFS = (1 << 0);

        /**
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
         * Use {@link VolumeShaper.Builder#reflectTimes()} to generate
         * the matching linear duck.
         */
        public static final Configuration LINEAR_RAMP = new VolumeShaper.Configuration.Builder()
                .setInterpolatorType(INTERPOLATOR_TYPE_LINEAR)
                .setCurve(new float[] {0.f, 1.f} /* times */,
                        new float[] {0.f, 1.f} /* volumes */)
                .setDurationMs(1000.)
                .build();

        /**
         * A one second cubic ramp from silence to full volume.
         * Use {@link VolumeShaper.Builder#reflectTimes()} to generate
         * the matching cubic duck.
         */
        public static final Configuration CUBIC_RAMP = new VolumeShaper.Configuration.Builder()
                .setInterpolatorType(INTERPOLATOR_TYPE_CUBIC)
                .setCurve(new float[] {0.f, 1.f} /* times */,
                        new float[] {0.f, 1.f}  /* volumes */)
                .setDurationMs(1000.)
                .build();

        /**
         * A one second sine curve for energy preserving cross fades.
         * Use {@link VolumeShaper.Builder#reflectTimes()} to generate
         * the matching cosine duck.
         */
        public static final Configuration SINE_RAMP;

        /**
         * A one second sine-squared s-curve ramp.
         * Use {@link VolumeShaper.Builder#reflectTimes()}
         * or {@link VolumeShaper.Builder#invertVolumes()} to generate
         * the matching s-curve duck.
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
                .setDurationMs(1000.)
                .build();
            SCURVE_RAMP = new VolumeShaper.Configuration.Builder()
                .setInterpolatorType(INTERPOLATOR_TYPE_CUBIC)
                .setCurve(times, scurve)
                .setDurationMs(1000.)
                .build();
        }

        /*
         * member variables - these are all final
         */

        // type of VolumeShaper
        private final int mType;

        // valid when mType is TYPE_ID
        private final int mId;

        // valid when mType is TYPE_SCALE
        private final int mInterpolatorType;
        private final int mOptionFlags;
        private final double mDurationMs;
        private final float[] mTimes;
        private final float[] mVolumes;

        @Override
        public String toString() {
            return "VolumeShaper.Configuration["
                    + "mType=" + mType
                    + (mType == TYPE_ID
                    ? ",mId" + mId
                    : ",mInterpolatorType=" + mInterpolatorType
                    + ",mOptionFlags=" + mOptionFlags
                    + ",mDurationMs=" + mDurationMs
                    + ",mTimes[]=" + mTimes
                    + ",mVolumes[]=" + mVolumes
                    + "]");
        }

        @Override
        public int hashCode() {
            return mType == TYPE_ID
                    ? Objects.hash(mType, mId)
                    : Objects.hash(mType, mInterpolatorType, mDurationMs, mTimes, mVolumes);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Configuration)) return false;
            if (o == this) return true;
            final Configuration other = (Configuration) o;
            return mType == other.mType &&
                    (mType == TYPE_ID ? mId == other.mId
                    : mInterpolatorType == other.mInterpolatorType
                    && mDurationMs == other.mDurationMs
                    && mTimes == other.mTimes
                    && mVolumes == other.mVolumes);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mType);
            dest.writeInt(mId);
            if (mType != TYPE_ID) {
                dest.writeInt(mInterpolatorType);
                dest.writeInt(mOptionFlags);
                dest.writeDouble(mDurationMs);
                dest.writeFloatArray(mTimes);
                dest.writeFloatArray(mVolumes);
            }
        }

        public static final Parcelable.Creator<VolumeShaper.Configuration> CREATOR
                = new Parcelable.Creator<VolumeShaper.Configuration>() {
            @Override
            public VolumeShaper.Configuration createFromParcel(Parcel p) {
                final int type = p.readInt();
                final int id = p.readInt();
                if (type == TYPE_ID) {
                    return new VolumeShaper.Configuration(id);
                } else {
                    return new VolumeShaper.Configuration(
                        type,
                        id,                    // id
                        p.readInt(),           // interpolatorType
                        p.readInt(),           // optionFlags
                        p.readDouble(),        // durationMs
                        p.createFloatArray(),  // times
                        p.createFloatArray()); // volumes
                }
            }

            @Override
            public VolumeShaper.Configuration[] newArray(int size) {
                return new VolumeShaper.Configuration[size];
            }
        };

        /**
         * Constructs a volume shaper from an id.
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
        private Configuration(int id) {
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
        private Configuration(@Type int type,
                int id,
                @InterpolatorType int interpolatorType,
                @OptionFlag int optionFlags,
                double durationMs,
                @NonNull float[] times,
                @NonNull float[] volumes) {
            mType = type;
            mId = id;
            mInterpolatorType = interpolatorType;
            mOptionFlags = optionFlags;
            mDurationMs = durationMs;
            // Builder should have cloned these arrays already.
            mTimes = times;
            mVolumes = volumes;
        }

        /**
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
         * Returns the option flags
         */
        public @OptionFlag int getOptionFlags() {
            return mOptionFlags & OPTION_FLAG_PUBLIC_ALL;
        }

        /* package */ @OptionFlag int getAllOptionFlags() {
            return mOptionFlags;
        }

        /**
         * Returns the duration of the effect in milliseconds.
         */
        public double getDurationMs() {
            return mDurationMs;
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
         * @param times the x coordinates for the points,
         *        must be between 0.f and 1.f and be monotonic.
         * @param volumes the y coordinates for the points,
         *        must be between 0.f and 1.f for linear and
         *        must be no greater than 0.f for log (dBFS).
         * @param log set to true if the scale is logarithmic.
         * @return null if no error, or the reason in a {@code String} for an error.
         */
        private static @Nullable String checkCurveForErrors(
                @NonNull float[] times, @NonNull float[] volumes, boolean log) {
            if (times.length != volumes.length) {
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

        private static void checkValidVolume(float volume, boolean log) {
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
         *             .setDurationMs(1000.)
         *             .build();
         * </pre>
         * <p>
         */
        public static final class Builder {
            private int mType = TYPE_SCALE;
            private int mId = -1; // invalid
            private int mInterpolatorType = INTERPOLATOR_TYPE_CUBIC;
            private int mOptionFlags = 0;
            private double mDurationMs = 1000.;
            private float[] mTimes = null;
            private float[] mVolumes = null;

            /**
             * Constructs a new Builder with the defaults.
             */
            public Builder() {
            }

            /**
             * Constructs a new Builder from a given {@code VolumeShaper.Configuration}
             * @param configuration prototypical configuration
             *        which will be reused in the new Builder.
             */
            public Builder(@NonNull Configuration configuration) {
                mType = configuration.getType();
                mId = configuration.getId();
                mOptionFlags = configuration.getAllOptionFlags();
                mInterpolatorType = configuration.getInterpolatorType();
                mDurationMs = configuration.getDurationMs();
                mTimes = configuration.getTimes();
                mVolumes = configuration.getVolumes();
            }

            /**
             * @hide
             * TODO make SystemApi
             *
             * Set the id for system defined shapers.
             * @param id
             * @return
             */
            public @NonNull Builder setId(int id) {
                mId = id;
                return this;
            }

            /**
             * Sets the interpolator type.
             *
             * If omitted the interplator type is {@link #INTERPOLATOR_TYPE_CUBIC}.
             *
             * @param interpolatorType method of interpolation used for the volume curve.
             * @return the same Builder instance.
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
             * Sets the optional flags
             *
             * If omitted, flags are 0. If {@link #OPTION_FLAG_VOLUME_IN_DBFS} has
             * changed the volume curve needs to be set again as the acceptable
             * volume domain has changed.
             *
             * @param optionFlags new value to replace the old {@code optionFlags}.
             * @return the same Builder instance.
             * @throws IllegalArgumentException if flag is not recognized.
             */
            public @NonNull Builder setOptionFlags(@OptionFlag int optionFlags) {
                if ((optionFlags & ~OPTION_FLAG_PUBLIC_ALL) != 0) {
                    throw new IllegalArgumentException("invalid bits in flag: " + optionFlags);
                }
                mOptionFlags = mOptionFlags & ~OPTION_FLAG_PUBLIC_ALL | optionFlags;
                return this;
            }

            /**
             * Sets the volume shaper duration in milliseconds.
             *
             * If omitted, the default duration is 1 second.
             *
             * @param durationMs
             * @return the same Builder instance.
             * @throws IllegalArgumentException if duration is not positive.
             */
            public @NonNull Builder setDurationMs(double durationMs) {
                if (durationMs <= 0.) {
                    throw new IllegalArgumentException(
                            "duration: " + durationMs + " not positive");
                }
                mDurationMs = durationMs;
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
             * (1) time (x) coordinates should be monotonically increasing, from 0.f to 1.f;
             * (2) volume (y) coordinates must be within 0.f to 1.f for linear and be non-positive
             *     for log scaling.
             * <p>
             * The time scale is set by {@link #setDurationMs} in seconds.
             * <p>
             * @param times an array of float values representing
             *        the time line of the volume curve.
             * @param volumes an array of float values representing
             *        the amplitude of the volume curve.
             * @return the same Builder instance.
             * @throws IllegalArgumentException if {@code times} or {@code volumes} is invalid.
             */
            public @NonNull Builder setCurve(@NonNull float[] times, @NonNull float[] volumes) {
                String error = checkCurveForErrors(
                        times, volumes, (mOptionFlags & OPTION_FLAG_VOLUME_IN_DBFS) != 0);
                if (error != null) {
                    throw new IllegalArgumentException(error);
                }
                mTimes = times.clone();
                mVolumes = volumes.clone();
                return this;
            }

            /**
             * Reflects the volume curve so that
             * the shaper changes volume from the end
             * to the start.
             *
             * @return the same Builder instance.
             */
            public @NonNull Builder reflectTimes() {
                int i;
                for (i = 0; i < mTimes.length / 2; ++i) {
                    float temp = mTimes[0];
                    mTimes[i] = 1.f - mTimes[mTimes.length - 1 - i];
                    mTimes[mTimes.length - 1 - i] = 1.f - temp;
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
             * @return the same Builder instance.
             */
            public @NonNull Builder invertVolumes() {
                if (mVolumes.length >= 2) {
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
                }
                return this;
            }

            /**
             * Scale the curve end volume to a target value.
             *
             * Keeps the start volume the same.
             * This works best if the volume curve is monotonic.
             *
             * @return the same Builder instance.
             * @throws IllegalArgumentException if volume is not valid.
             */
            public @NonNull Builder scaleToEndVolume(float volume) {
                final boolean log = (mOptionFlags & OPTION_FLAG_VOLUME_IN_DBFS) != 0;
                checkValidVolume(volume, log);
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
             * @return the same Builder instance.
             * @throws IllegalArgumentException if volume is not valid.
             */
            public @NonNull Builder scaleToStartVolume(float volume) {
                final boolean log = (mOptionFlags & OPTION_FLAG_VOLUME_IN_DBFS) != 0;
                checkValidVolume(volume, log);
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
             * @return a new {@link VolumeShaper} object
             */
            public @NonNull Configuration build() {
                String error = checkCurveForErrors(
                        mTimes, mVolumes, (mOptionFlags & OPTION_FLAG_VOLUME_IN_DBFS) != 0);
                if (error != null) {
                    throw new IllegalArgumentException(error);
                }
                return new Configuration(mType, mId, mInterpolatorType, mOptionFlags,
                        mDurationMs, mTimes, mVolumes);
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
         */
        public static final Operation PLAY =
                new VolumeShaper.Operation.Builder()
                    .build();

        /**
         * Reverse playback from current volume time position.
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
         * when starting a VolumeShaper effect.
         */
        private static final int FLAG_DEFER = 1 << 3;

        private static final int FLAG_PUBLIC_ALL = FLAG_REVERSE | FLAG_TERMINATE;

        private final int mFlags;
        private final int mReplaceId;

        @Override
        public String toString() {
            return "VolumeShaper.Operation["
                    + "mFlags=" + mFlags
                    + ",mReplaceId" + mReplaceId
                    + "]";
        }

        @Override
        public int hashCode() {
            return Objects.hash(mFlags, mReplaceId);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Operation)) return false;
            if (o == this) return true;
            final Operation other = (Operation) o;
            return mFlags == other.mFlags
                    && mReplaceId == other.mReplaceId;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mFlags);
            dest.writeInt(mReplaceId);
        }

        public static final Parcelable.Creator<VolumeShaper.Operation> CREATOR
                = new Parcelable.Creator<VolumeShaper.Operation>() {
            @Override
            public VolumeShaper.Operation createFromParcel(Parcel p) {
                return new VolumeShaper.Operation(
                        p.readInt()     // flags
                        , p.readInt()); // replaceId
            }

            @Override
            public VolumeShaper.Operation[] newArray(int size) {
                return new VolumeShaper.Operation[size];
            }
        };

        private Operation(@Flag int flags, int replaceId) {
            mFlags = flags;
            mReplaceId = replaceId;
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

            /**
             * Constructs a new {@code Builder} with the defaults.
             */
            public Builder() {
                mFlags = 0;
                mReplaceId = -1;
            }

            /**
             * Constructs a new Builder from a given {@code VolumeShaper.Operation}
             * @param operation the {@code VolumeShaper.operation} whose data will be
             *        reused in the new Builder.
             */
            public Builder(@NonNull VolumeShaper.Operation operation) {
                mReplaceId = operation.mReplaceId;
                mFlags = operation.mFlags;
            }

            /**
             * Replaces the previous {@code VolumeShaper}.
             * It has no other effect if the {@code VolumeShaper} is
             * already expired. If the replaceId is the same as the id associated with
             * the {@code VolumeShaper} in a {@code setVolumeShaper()} call,
             * an error is returned.
             * @param handle is a previous volumeShaper {@code VolumeShaper}.
             * @param join the start to match the current volume of the previous
             * shaper.
             * @return the same Builder instance.
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
             * @return the same Builder instance.
             */
            public @NonNull Builder defer() {
                mFlags |= FLAG_DEFER;
                return this;
            }

            /**
             * Terminates the VolumeShaper.
             * Do not call directly, use {@link VolumeShaper#release()}.
             * @return the same Builder instance.
             */
            public @NonNull Builder terminate() {
                mFlags |= FLAG_TERMINATE;
                return this;
            }

            /**
             * Reverses direction.
             * @return the same Builder instance.
             */
            public @NonNull Builder reverse() {
                mFlags ^= FLAG_REVERSE;
                return this;
            }

            /**
             * Sets the operation flag.  Do not call this directly but one of the
             * other builder methods.
             *
             * @param flags new value for {@code flags}, consisting of ORed flags.
             * @return the same Builder instance.
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
                return new Operation(mFlags, mReplaceId);
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
        private float mVolume;
        private float mXOffset;

        @Override
        public String toString() {
            return "VolumeShaper.State["
                    + "mVolume=" + mVolume
                    + ",mXOffset" + mXOffset
                    + "]";
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

        public static final Parcelable.Creator<VolumeShaper.State> CREATOR
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

        /* package */ State(float volume, float xOffset) {
            mVolume = volume;
            mXOffset = xOffset;
        }

        /**
         * Gets the volume of the {@link VolumeShaper.State}.
         */
        public float getVolume() {
            return mVolume;
        }

        /**
         * Gets the elapsed ms of the {@link VolumeShaper.State}
         */
        public double getXOffset() {
            return mXOffset;
        }
    } // State
}
