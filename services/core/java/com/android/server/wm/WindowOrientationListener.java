/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.server.wm;

import static android.provider.DeviceConfig.NAMESPACE_WINDOW_MANAGER;

import static com.android.server.wm.WindowOrientationListenerProto.ENABLED;
import static com.android.server.wm.WindowOrientationListenerProto.ROTATION;

import android.app.ActivityThread;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.DeviceConfig;
import android.rotationresolver.RotationResolverInternal;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.Surface;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.LocalServices;

import java.io.PrintWriter;
import java.util.List;
import java.util.Set;

/**
 * A special helper class used by the WindowManager
 * for receiving notifications from the SensorManager when
 * the orientation of the device has changed.
 *
 * NOTE: If changing anything here, please run the API demo
 * "App/Activity/Screen Orientation" to ensure that all orientation
 * modes still work correctly.
 *
 * You can also visualize the behavior of the WindowOrientationListener.
 * Refer to frameworks/base/tools/orientationplot/README.txt for details.
 */
public abstract class WindowOrientationListener {
    private static final String TAG = "WindowOrientationListener";
    private static final boolean LOG = SystemProperties.getBoolean(
            "debug.orientation.log", false);

    private static final boolean USE_GRAVITY_SENSOR = false;
    private static final int DEFAULT_BATCH_LATENCY = 100000;
    private static final String KEY_ROTATION_RESOLVER_TIMEOUT = "rotation_resolver_timeout_millis";
    private static final String KEY_ROTATION_MEMORIZATION_TIMEOUT =
            "rotation_memorization_timeout_millis";
    private static final long DEFAULT_ROTATION_RESOLVER_TIMEOUT_MILLIS = 700L;
    private static final long DEFAULT_ROTATION_MEMORIZATION_TIMEOUT_MILLIS = 3_000L; // 3 seconds

    private Handler mHandler;
    private SensorManager mSensorManager;
    private boolean mEnabled;
    private int mRate;
    private String mSensorType;
    private Sensor mSensor;

    @VisibleForTesting
    OrientationJudge mOrientationJudge;

    @VisibleForTesting
    RotationResolverInternal mRotationResolverService;

    private int mCurrentRotation = -1;
    private final Context mContext;

    private final Object mLock = new Object();

    /**
     * Creates a new WindowOrientationListener.
     *
     * @param context for the WindowOrientationListener.
     * @param handler Provides the Looper for receiving sensor updates.
     */
    public WindowOrientationListener(Context context, Handler handler) {
        this(context, handler, SensorManager.SENSOR_DELAY_UI);
    }

    /**
     * Creates a new WindowOrientationListener.
     *
     * @param context for the WindowOrientationListener.
     * @param handler Provides the Looper for receiving sensor updates.
     * @param wmService WindowManagerService to read the device config from.
     * @param rate at which sensor events are processed (see also
     * {@link android.hardware.SensorManager SensorManager}). Use the default
     * value of {@link android.hardware.SensorManager#SENSOR_DELAY_NORMAL
     * SENSOR_DELAY_NORMAL} for simple screen orientation change detection.
     *
     * This constructor is private since no one uses it.
     */
    private WindowOrientationListener(
            Context context, Handler handler, int rate) {
        mContext = context;
        mHandler = handler;
        mSensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        mRate = rate;
        List<Sensor> l = mSensorManager.getSensorList(Sensor.TYPE_DEVICE_ORIENTATION);
        Sensor wakeUpDeviceOrientationSensor = null;
        Sensor nonWakeUpDeviceOrientationSensor = null;
        /**
         *  Prefer the wakeup form of the sensor if implemented.
         *  It's OK to look for just two types of this sensor and use
         *  the last found. Typical devices will only have one sensor of
         *  this type.
         */
        for (Sensor s : l) {
            if (s.isWakeUpSensor()) {
                wakeUpDeviceOrientationSensor = s;
            } else {
                nonWakeUpDeviceOrientationSensor = s;
            }
        }

        if (wakeUpDeviceOrientationSensor != null) {
            mSensor = wakeUpDeviceOrientationSensor;
        } else {
            mSensor = nonWakeUpDeviceOrientationSensor;
        }

        if (mSensor != null) {
            mOrientationJudge = new OrientationSensorJudge();
        }

        if (mOrientationJudge == null) {
            mSensor = mSensorManager.getDefaultSensor(USE_GRAVITY_SENSOR
                    ? Sensor.TYPE_GRAVITY : Sensor.TYPE_ACCELEROMETER);
            if (mSensor != null) {
                // Create listener only if sensors do exist
                mOrientationJudge = new AccelSensorJudge(context);
            }
        }
    }

    /**
     * Enables the WindowOrientationListener so it will monitor the sensor and call
     * {@link #onProposedRotationChanged(int)} when the device orientation changes.
     */
    public void enable() {
        enable(true /* clearCurrentRotation */);
    }

    /**
     * Enables the WindowOrientationListener so it will monitor the sensor and call
     * {@link #onProposedRotationChanged(int)} when the device orientation changes.
     *
     * @param clearCurrentRotation True if the current proposed sensor rotation should be cleared as
     *                             part of the reset.
     */
    public void enable(boolean clearCurrentRotation) {
        synchronized (mLock) {
            if (mSensor == null) {
                Slog.w(TAG, "Cannot detect sensors. Not enabled");
                return;
            }
            if (mEnabled) {
                return;
            }
            if (LOG) {
                Slog.d(TAG, "WindowOrientationListener enabled clearCurrentRotation="
                        + clearCurrentRotation);
            }
            mOrientationJudge.resetLocked(clearCurrentRotation);
            if (mSensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                mSensorManager.registerListener(
                        mOrientationJudge, mSensor, mRate, DEFAULT_BATCH_LATENCY, mHandler);
            } else {
                mSensorManager.registerListener(mOrientationJudge, mSensor, mRate, mHandler);
            }
            mEnabled = true;
        }
    }

    /**
     * Disables the WindowOrientationListener.
     */
    public void disable() {
        synchronized (mLock) {
            if (mSensor == null) {
                Slog.w(TAG, "Cannot detect sensors. Invalid disable");
                return;
            }
            if (mEnabled == true) {
                if (LOG) {
                    Slog.d(TAG, "WindowOrientationListener disabled");
                }
                mSensorManager.unregisterListener(mOrientationJudge);
                mEnabled = false;
            }
        }
    }

    public void onTouchStart() {
        synchronized (mLock) {
            if (mOrientationJudge != null) {
                mOrientationJudge.onTouchStartLocked();
            }
        }
    }

    public void onTouchEnd() {
        long whenElapsedNanos = SystemClock.elapsedRealtimeNanos();

        synchronized (mLock) {
            if (mOrientationJudge != null) {
                mOrientationJudge.onTouchEndLocked(whenElapsedNanos);
            }
        }
    }

    public Handler getHandler() {
        return mHandler;
    }

    /**
     * Sets the current rotation.
     *
     * @param rotation The current rotation.
     */
    public void setCurrentRotation(int rotation) {
        synchronized (mLock) {
            mCurrentRotation = rotation;
        }
    }

    /**
     * Gets the proposed rotation.
     *
     * This method only returns a rotation if the orientation listener is certain
     * of its proposal.  If the rotation is indeterminate, returns -1.
     *
     * @return The proposed rotation, or -1 if unknown.
     */
    public int getProposedRotation() {
        synchronized (mLock) {
            if (mEnabled) {
                return mOrientationJudge.getProposedRotationLocked();
            }
            return -1;
        }
    }

    /**
     * Returns true if sensor is enabled and false otherwise
     */
    public boolean canDetectOrientation() {
        synchronized (mLock) {
            return mSensor != null;
        }
    }


    /**
     * Returns true if the rotation resolver feature is enabled by setting. It means {@link
     * WindowOrientationListener} will then ask {@link RotationResolverInternal} for the appropriate
     * screen rotation.
     */
    @VisibleForTesting
    abstract boolean isRotationResolverEnabled();

    /**
     * Called when the rotation view of the device has changed.
     *
     * This method is called whenever the orientation becomes certain of an orientation.
     * It is called each time the orientation determination transitions from being
     * uncertain to being certain again, even if it is the same orientation as before.
     *
     * This should only be called on the Handler thread.
     *
     * @param rotation The new orientation of the device, one of the Surface.ROTATION_* constants.
     * @see android.view.Surface
     */
    public abstract void onProposedRotationChanged(int rotation);

    /**
     * Whether the device is in the lock screen.
     * @return returns true if the screen is locked. Otherwise, returns false.
     */
    public abstract boolean isKeyguardLocked();

    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        synchronized (mLock) {
            proto.write(ENABLED, mEnabled);
            proto.write(ROTATION, mCurrentRotation);
        }
        proto.end(token);
    }

    public void dump(PrintWriter pw, String prefix) {
        synchronized (mLock) {
            pw.println(prefix + TAG);
            prefix += "  ";
            pw.println(prefix + "mEnabled=" + mEnabled);
            pw.println(prefix + "mCurrentRotation=" + Surface.rotationToString(mCurrentRotation));
            pw.println(prefix + "mSensorType=" + mSensorType);
            pw.println(prefix + "mSensor=" + mSensor);
            pw.println(prefix + "mRate=" + mRate);

            if (mOrientationJudge != null) {
                mOrientationJudge.dumpLocked(pw, prefix);
            }
        }
    }

    /**
     * Returns whether this WindowOrientationListener can remain enabled while the device is dozing.
     * If this returns true, it implies that the underlying sensor can still run while the AP is
     * asleep, and that the underlying sensor will wake the AP on an event.
     */
    public boolean shouldStayEnabledWhileDreaming() {
        if (mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_forceOrientationListenerEnabledWhileDreaming)) {
            return true;
        }
        return mSensor.getType() == Sensor.TYPE_DEVICE_ORIENTATION && mSensor.isWakeUpSensor();
    }

    abstract class OrientationJudge implements SensorEventListener {
        // Number of nanoseconds per millisecond.
        protected static final long NANOS_PER_MS = 1000000;

        // Number of milliseconds per nano second.
        protected static final float MILLIS_PER_NANO = 0.000001f;

        // The minimum amount of time that must have elapsed since the screen was last touched
        // before the proposed rotation can change.
        protected static final long PROPOSAL_MIN_TIME_SINCE_TOUCH_END_NANOS =
                500 * NANOS_PER_MS;

        /**
         * Gets the proposed rotation.
         *
         * This method only returns a rotation if the orientation listener is certain
         * of its proposal.  If the rotation is indeterminate, returns -1.
         *
         * Should only be called when holding WindowOrientationListener lock.
         *
         * @return The proposed rotation, or -1 if unknown.
         */
        public abstract int getProposedRotationLocked();

        /**
         * Notifies the orientation judge that the screen is being touched.
         *
         * Should only be called when holding WindowOrientationListener lock.
         */
        public abstract void onTouchStartLocked();

        /**
         * Notifies the orientation judge that the screen is no longer being touched.
         *
         * Should only be called when holding WindowOrientationListener lock.
         *
         * @param whenElapsedNanos Given in the elapsed realtime nanos time base.
         */
        public abstract void onTouchEndLocked(long whenElapsedNanos);

        /**
         * Resets the state of the judge.
         *
         * Should only be called when holding WindowOrientationListener lock.
         *
         * @param clearCurrentRotation True if the current proposed sensor rotation should be
         *                             cleared as part of the reset.
         */
        public abstract void resetLocked(boolean clearCurrentRotation);

        /**
         * Dumps internal state of the orientation judge.
         *
         * Should only be called when holding WindowOrientationListener lock.
         */
        public abstract void dumpLocked(PrintWriter pw, String prefix);

        @Override
        public abstract void onAccuracyChanged(Sensor sensor, int accuracy);

        @Override
        public abstract void onSensorChanged(SensorEvent event);
    }

    /**
     * This class filters the raw accelerometer data and tries to detect actual changes in
     * orientation. This is a very ill-defined problem so there are a lot of tweakable parameters,
     * but here's the outline:
     *
     *  - Low-pass filter the accelerometer vector in cartesian coordinates.  We do it in
     *    cartesian space because the orientation calculations are sensitive to the
     *    absolute magnitude of the acceleration.  In particular, there are singularities
     *    in the calculation as the magnitude approaches 0.  By performing the low-pass
     *    filtering early, we can eliminate most spurious high-frequency impulses due to noise.
     *
     *  - Convert the acceleromter vector from cartesian to spherical coordinates.
     *    Since we're dealing with rotation of the device, this is the sensible coordinate
     *    system to work in.  The zenith direction is the Z-axis, the direction the screen
     *    is facing.  The radial distance is referred to as the magnitude below.
     *    The elevation angle is referred to as the "tilt" below.
     *    The azimuth angle is referred to as the "orientation" below (and the azimuth axis is
     *    the Y-axis).
     *    See http://en.wikipedia.org/wiki/Spherical_coordinate_system for reference.
     *
     *  - If the tilt angle is too close to horizontal (near 90 or -90 degrees), do nothing.
     *    The orientation angle is not meaningful when the device is nearly horizontal.
     *    The tilt angle thresholds are set differently for each orientation and different
     *    limits are applied when the device is facing down as opposed to when it is facing
     *    forward or facing up.
     *
     *  - When the orientation angle reaches a certain threshold, consider transitioning
     *    to the corresponding orientation.  These thresholds have some hysteresis built-in
     *    to avoid oscillations between adjacent orientations.
     *
     *  - Wait for the device to settle for a little bit.  Once that happens, issue the
     *    new orientation proposal.
     *
     * Details are explained inline.
     *
     * See http://en.wikipedia.org/wiki/Low-pass_filter#Discrete-time_realization for
     * signal processing background.
     */
    final class AccelSensorJudge extends OrientationJudge {
        // We work with all angles in degrees in this class.
        private static final float RADIANS_TO_DEGREES = (float) (180 / Math.PI);

        // Indices into SensorEvent.values for the accelerometer sensor.
        private static final int ACCELEROMETER_DATA_X = 0;
        private static final int ACCELEROMETER_DATA_Y = 1;
        private static final int ACCELEROMETER_DATA_Z = 2;

        // The minimum amount of time that a predicted rotation must be stable before it
        // is accepted as a valid rotation proposal.  This value can be quite small because
        // the low-pass filter already suppresses most of the noise so we're really just
        // looking for quick confirmation that the last few samples are in agreement as to
        // the desired orientation.
        private static final long PROPOSAL_SETTLE_TIME_NANOS = 40 * NANOS_PER_MS;

        // The minimum amount of time that must have elapsed since the device last exited
        // the flat state (time since it was picked up) before the proposed rotation
        // can change.
        private static final long PROPOSAL_MIN_TIME_SINCE_FLAT_ENDED_NANOS = 500 * NANOS_PER_MS;

        // The minimum amount of time that must have elapsed since the device stopped
        // swinging (time since device appeared to be in the process of being put down
        // or put away into a pocket) before the proposed rotation can change.
        private static final long PROPOSAL_MIN_TIME_SINCE_SWING_ENDED_NANOS = 300 * NANOS_PER_MS;

        // The minimum amount of time that must have elapsed since the device stopped
        // undergoing external acceleration before the proposed rotation can change.
        private static final long PROPOSAL_MIN_TIME_SINCE_ACCELERATION_ENDED_NANOS =
                500 * NANOS_PER_MS;

        // If the tilt angle remains greater than the specified angle for a minimum of
        // the specified time, then the device is deemed to be lying flat
        // (just chillin' on a table).
        private static final float FLAT_ANGLE = 80;
        private static final long FLAT_TIME_NANOS = 1000 * NANOS_PER_MS;

        // If the tilt angle has increased by at least delta degrees within the specified amount
        // of time, then the device is deemed to be swinging away from the user
        // down towards flat (tilt = 90).
        private static final float SWING_AWAY_ANGLE_DELTA = 20;
        private static final long SWING_TIME_NANOS = 300 * NANOS_PER_MS;

        // The maximum sample inter-arrival time in milliseconds.
        // If the acceleration samples are further apart than this amount in time, we reset the
        // state of the low-pass filter and orientation properties.  This helps to handle
        // boundary conditions when the device is turned on, wakes from suspend or there is
        // a significant gap in samples.
        private static final long MAX_FILTER_DELTA_TIME_NANOS = 1000 * NANOS_PER_MS;

        // The acceleration filter time constant.
        //
        // This time constant is used to tune the acceleration filter such that
        // impulses and vibrational noise (think car dock) is suppressed before we
        // try to calculate the tilt and orientation angles.
        //
        // The filter time constant is related to the filter cutoff frequency, which is the
        // frequency at which signals are attenuated by 3dB (half the passband power).
        // Each successive octave beyond this frequency is attenuated by an additional 6dB.
        //
        // Given a time constant t in seconds, the filter cutoff frequency Fc in Hertz
        // is given by Fc = 1 / (2pi * t).
        //
        // The higher the time constant, the lower the cutoff frequency, so more noise
        // will be suppressed.
        //
        // Filtering adds latency proportional the time constant (inversely proportional
        // to the cutoff frequency) so we don't want to make the time constant too
        // large or we can lose responsiveness.  Likewise we don't want to make it too
        // small or we do a poor job suppressing acceleration spikes.
        // Empirically, 100ms seems to be too small and 500ms is too large.
        private static final float FILTER_TIME_CONSTANT_MS = 200.0f;

        /* State for orientation detection. */

        // Thresholds for minimum and maximum allowable deviation from gravity.
        //
        // If the device is undergoing external acceleration (being bumped, in a car
        // that is turning around a corner or a plane taking off) then the magnitude
        // may be substantially more or less than gravity.  This can skew our orientation
        // detection by making us think that up is pointed in a different direction.
        //
        // Conversely, if the device is in freefall, then there will be no gravity to
        // measure at all.  This is problematic because we cannot detect the orientation
        // without gravity to tell us which way is up.  A magnitude near 0 produces
        // singularities in the tilt and orientation calculations.
        //
        // In both cases, we postpone choosing an orientation.
        //
        // However, we need to tolerate some acceleration because the angular momentum
        // of turning the device can skew the observed acceleration for a short period of time.
        private static final float NEAR_ZERO_MAGNITUDE = 1; // m/s^2
        private static final float ACCELERATION_TOLERANCE = 4; // m/s^2
        private static final float MIN_ACCELERATION_MAGNITUDE =
                SensorManager.STANDARD_GRAVITY - ACCELERATION_TOLERANCE;
        private static final float MAX_ACCELERATION_MAGNITUDE =
                SensorManager.STANDARD_GRAVITY + ACCELERATION_TOLERANCE;

        // Maximum absolute tilt angle at which to consider orientation data.  Beyond this (i.e.
        // when screen is facing the sky or ground), we completely ignore orientation data
        // because it's too unstable.
        private static final int MAX_TILT = 80;

        // The tilt angle below which we conclude that the user is holding the device
        // overhead reading in bed and lock into that state.
        private static final int TILT_OVERHEAD_ENTER = -40;

        // The tilt angle above which we conclude that the user would like a rotation
        // change to occur and unlock from the overhead state.
        private static final int TILT_OVERHEAD_EXIT = -15;

        // The gap angle in degrees between adjacent orientation angles for hysteresis.
        // This creates a "dead zone" between the current orientation and a proposed
        // adjacent orientation.  No orientation proposal is made when the orientation
        // angle is within the gap between the current orientation and the adjacent
        // orientation.
        private static final int ADJACENT_ORIENTATION_ANGLE_GAP = 45;

        // The tilt angle range in degrees for each orientation.
        // Beyond these tilt angles, we don't even consider transitioning into the
        // specified orientation.  We place more stringent requirements on unnatural
        // orientations than natural ones to make it less likely to accidentally transition
        // into those states.
        // The first value of each pair is negative so it applies a limit when the device is
        // facing down (overhead reading in bed).
        // The second value of each pair is positive so it applies a limit when the device is
        // facing up (resting on a table).
        // The ideal tilt angle is 0 (when the device is vertical) so the limits establish
        // how close to vertical the device must be in order to change orientation.
        private final int[][] mTiltToleranceConfig = new int[][] {
            /* ROTATION_0   */ { -25, 70 }, // note: these are overridden by config.xml
            /* ROTATION_90  */ { -25, 65 },
            /* ROTATION_180 */ { -25, 60 },
            /* ROTATION_270 */ { -25, 65 }
        };

        // Timestamp and value of the last accelerometer sample.
        private long mLastFilteredTimestampNanos;
        private float mLastFilteredX, mLastFilteredY, mLastFilteredZ;

        // The last proposed rotation, -1 if unknown.
        private int mProposedRotation;

        // Value of the current predicted rotation, -1 if unknown.
        private int mPredictedRotation;

        // Timestamp of when the predicted rotation most recently changed.
        private long mPredictedRotationTimestampNanos;

        // Timestamp when the device last appeared to be flat for sure (the flat delay elapsed).
        private long mFlatTimestampNanos;
        private boolean mFlat;

        // Timestamp when the device last appeared to be swinging.
        private long mSwingTimestampNanos;
        private boolean mSwinging;

        // Timestamp when the device last appeared to be undergoing external acceleration.
        private long mAccelerationTimestampNanos;
        private boolean mAccelerating;

        // Timestamp when the last touch to the touch screen ended
        private long mTouchEndedTimestampNanos = Long.MIN_VALUE;
        private boolean mTouched;

        // Whether we are locked into an overhead usage mode.
        private boolean mOverhead;

        // History of observed tilt angles.
        private static final int TILT_HISTORY_SIZE = 200;
        private float[] mTiltHistory = new float[TILT_HISTORY_SIZE];
        private long[] mTiltHistoryTimestampNanos = new long[TILT_HISTORY_SIZE];
        private int mTiltHistoryIndex;

        public AccelSensorJudge(Context context) {
            // Load tilt tolerance configuration.
            int[] tiltTolerance = context.getResources().getIntArray(
                    com.android.internal.R.array.config_autoRotationTiltTolerance);
            if (tiltTolerance.length == 8) {
                for (int i = 0; i < 4; i++) {
                    int min = tiltTolerance[i * 2];
                    int max = tiltTolerance[i * 2 + 1];
                    if (min >= -90 && min <= max && max <= 90) {
                        mTiltToleranceConfig[i][0] = min;
                        mTiltToleranceConfig[i][1] = max;
                    } else {
                        Slog.wtf(TAG, "config_autoRotationTiltTolerance contains invalid range: "
                                + "min=" + min + ", max=" + max);
                    }
                }
            } else {
                Slog.wtf(TAG, "config_autoRotationTiltTolerance should have exactly 8 elements");
            }
        }

        @Override
        public int getProposedRotationLocked() {
            return mProposedRotation;
        }

        @Override
        public void dumpLocked(PrintWriter pw, String prefix) {
            pw.println(prefix + "AccelSensorJudge");
            prefix += "  ";
            pw.println(prefix + "mProposedRotation=" + mProposedRotation);
            pw.println(prefix + "mPredictedRotation=" + mPredictedRotation);
            pw.println(prefix + "mLastFilteredX=" + mLastFilteredX);
            pw.println(prefix + "mLastFilteredY=" + mLastFilteredY);
            pw.println(prefix + "mLastFilteredZ=" + mLastFilteredZ);
            final long delta = SystemClock.elapsedRealtimeNanos() - mLastFilteredTimestampNanos;
            pw.println(prefix + "mLastFilteredTimestampNanos=" + mLastFilteredTimestampNanos
                    + " (" + (delta * 0.000001f) + "ms ago)");
            pw.println(prefix + "mTiltHistory={last: " + getLastTiltLocked() + "}");
            pw.println(prefix + "mFlat=" + mFlat);
            pw.println(prefix + "mSwinging=" + mSwinging);
            pw.println(prefix + "mAccelerating=" + mAccelerating);
            pw.println(prefix + "mOverhead=" + mOverhead);
            pw.println(prefix + "mTouched=" + mTouched);
            pw.print(prefix + "mTiltToleranceConfig=[");
            for (int i = 0; i < 4; i++) {
                if (i != 0) {
                    pw.print(", ");
                }
                pw.print("[");
                pw.print(mTiltToleranceConfig[i][0]);
                pw.print(", ");
                pw.print(mTiltToleranceConfig[i][1]);
                pw.print("]");
            }
            pw.println("]");
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            int proposedRotation;
            int oldProposedRotation;

            synchronized (mLock) {
                // The vector given in the SensorEvent points straight up (towards the sky) under
                // ideal conditions (the phone is not accelerating).  I'll call this up vector
                // elsewhere.
                float x = event.values[ACCELEROMETER_DATA_X];
                float y = event.values[ACCELEROMETER_DATA_Y];
                float z = event.values[ACCELEROMETER_DATA_Z];

                if (LOG) {
                    Slog.v(TAG, "Raw acceleration vector: "
                            + "x=" + x + ", y=" + y + ", z=" + z
                            + ", magnitude=" + Math.sqrt(x * x + y * y + z * z));
                }

                // Apply a low-pass filter to the acceleration up vector in cartesian space.
                // Reset the orientation listener state if the samples are too far apart in time
                // or when we see values of (0, 0, 0) which indicates that we polled the
                // accelerometer too soon after turning it on and we don't have any data yet.
                final long now = event.timestamp;
                final long then = mLastFilteredTimestampNanos;
                final float timeDeltaMS = (now - then) * 0.000001f;
                final boolean skipSample;
                if (now < then
                        || now > then + MAX_FILTER_DELTA_TIME_NANOS
                        || (x == 0 && y == 0 && z == 0)) {
                    if (LOG) {
                        Slog.v(TAG, "Resetting orientation listener.");
                    }
                    resetLocked(true /* clearCurrentRotation */);
                    skipSample = true;
                } else {
                    final float alpha = timeDeltaMS / (FILTER_TIME_CONSTANT_MS + timeDeltaMS);
                    x = alpha * (x - mLastFilteredX) + mLastFilteredX;
                    y = alpha * (y - mLastFilteredY) + mLastFilteredY;
                    z = alpha * (z - mLastFilteredZ) + mLastFilteredZ;
                    if (LOG) {
                        Slog.v(TAG, "Filtered acceleration vector: "
                                + "x=" + x + ", y=" + y + ", z=" + z
                                + ", magnitude=" + Math.sqrt(x * x + y * y + z * z));
                    }
                    skipSample = false;
                }
                mLastFilteredTimestampNanos = now;
                mLastFilteredX = x;
                mLastFilteredY = y;
                mLastFilteredZ = z;

                boolean isAccelerating = false;
                boolean isFlat = false;
                boolean isSwinging = false;
                if (!skipSample) {
                    // Calculate the magnitude of the acceleration vector.
                    final float magnitude = (float) Math.sqrt(x * x + y * y + z * z);
                    if (magnitude < NEAR_ZERO_MAGNITUDE) {
                        if (LOG) {
                            Slog.v(TAG, "Ignoring sensor data, magnitude too close to zero.");
                        }
                        clearPredictedRotationLocked();
                    } else {
                        // Determine whether the device appears to be undergoing external
                        // acceleration.
                        if (isAcceleratingLocked(magnitude)) {
                            isAccelerating = true;
                            mAccelerationTimestampNanos = now;
                        }

                        // Calculate the tilt angle.
                        // This is the angle between the up vector and the x-y plane (the plane of
                        // the screen) in a range of [-90, 90] degrees.
                        //   -90 degrees: screen horizontal and facing the ground (overhead)
                        //     0 degrees: screen vertical
                        //    90 degrees: screen horizontal and facing the sky (on table)
                        final int tiltAngle = (int) Math.round(
                                Math.asin(z / magnitude) * RADIANS_TO_DEGREES);
                        addTiltHistoryEntryLocked(now, tiltAngle);

                        // Determine whether the device appears to be flat or swinging.
                        if (isFlatLocked(now)) {
                            isFlat = true;
                            mFlatTimestampNanos = now;
                        }
                        if (isSwingingLocked(now, tiltAngle)) {
                            isSwinging = true;
                            mSwingTimestampNanos = now;
                        }

                        // If the tilt angle is too close to horizontal then we cannot determine
                        // the orientation angle of the screen.
                        if (tiltAngle <= TILT_OVERHEAD_ENTER) {
                            mOverhead = true;
                        } else if (tiltAngle >= TILT_OVERHEAD_EXIT) {
                            mOverhead = false;
                        }
                        if (mOverhead) {
                            if (LOG) {
                                Slog.v(TAG, "Ignoring sensor data, device is overhead: "
                                        + "tiltAngle=" + tiltAngle);
                            }
                            clearPredictedRotationLocked();
                        } else if (Math.abs(tiltAngle) > MAX_TILT) {
                            if (LOG) {
                                Slog.v(TAG, "Ignoring sensor data, tilt angle too high: "
                                        + "tiltAngle=" + tiltAngle);
                            }
                            clearPredictedRotationLocked();
                        } else {
                            // Calculate the orientation angle.
                            // This is the angle between the x-y projection of the up vector onto
                            // the +y-axis, increasing clockwise in a range of [0, 360] degrees.
                            int orientationAngle = (int) Math.round(
                                    -Math.atan2(-x, y) * RADIANS_TO_DEGREES);
                            if (orientationAngle < 0) {
                                // atan2 returns [-180, 180]; normalize to [0, 360]
                                orientationAngle += 360;
                            }

                            // Find the nearest rotation.
                            int nearestRotation = (orientationAngle + 45) / 90;
                            if (nearestRotation == 4) {
                                nearestRotation = 0;
                            }

                            // Determine the predicted orientation.
                            if (isTiltAngleAcceptableLocked(nearestRotation, tiltAngle)
                                    && isOrientationAngleAcceptableLocked(nearestRotation,
                                            orientationAngle)) {
                                updatePredictedRotationLocked(now, nearestRotation);
                                if (LOG) {
                                    Slog.v(TAG, "Predicted: "
                                            + "tiltAngle=" + tiltAngle
                                            + ", orientationAngle=" + orientationAngle
                                            + ", predictedRotation=" + mPredictedRotation
                                            + ", predictedRotationAgeMS="
                                                    + ((now - mPredictedRotationTimestampNanos)
                                                            * 0.000001f));
                                }
                            } else {
                                if (LOG) {
                                    Slog.v(TAG, "Ignoring sensor data, no predicted rotation: "
                                            + "tiltAngle=" + tiltAngle
                                            + ", orientationAngle=" + orientationAngle);
                                }
                                clearPredictedRotationLocked();
                            }
                        }
                    }
                }
                mFlat = isFlat;
                mSwinging = isSwinging;
                mAccelerating = isAccelerating;

                // Determine new proposed rotation.
                oldProposedRotation = mProposedRotation;
                if (mPredictedRotation < 0 || isPredictedRotationAcceptableLocked(now)) {
                    mProposedRotation = mPredictedRotation;
                }
                proposedRotation = mProposedRotation;

                // Write final statistics about where we are in the orientation detection process.
                if (LOG) {
                    Slog.v(TAG, "Result: currentRotation=" + mCurrentRotation
                            + ", proposedRotation=" + proposedRotation
                            + ", predictedRotation=" + mPredictedRotation
                            + ", timeDeltaMS=" + timeDeltaMS
                            + ", isAccelerating=" + isAccelerating
                            + ", isFlat=" + isFlat
                            + ", isSwinging=" + isSwinging
                            + ", isOverhead=" + mOverhead
                            + ", isTouched=" + mTouched
                            + ", timeUntilSettledMS=" + remainingMS(now,
                                    mPredictedRotationTimestampNanos + PROPOSAL_SETTLE_TIME_NANOS)
                            + ", timeUntilAccelerationDelayExpiredMS=" + remainingMS(now,
                                    mAccelerationTimestampNanos + PROPOSAL_MIN_TIME_SINCE_ACCELERATION_ENDED_NANOS)
                            + ", timeUntilFlatDelayExpiredMS=" + remainingMS(now,
                                    mFlatTimestampNanos + PROPOSAL_MIN_TIME_SINCE_FLAT_ENDED_NANOS)
                            + ", timeUntilSwingDelayExpiredMS=" + remainingMS(now,
                                    mSwingTimestampNanos + PROPOSAL_MIN_TIME_SINCE_SWING_ENDED_NANOS)
                            + ", timeUntilTouchDelayExpiredMS=" + remainingMS(now,
                                    mTouchEndedTimestampNanos + PROPOSAL_MIN_TIME_SINCE_TOUCH_END_NANOS));
                }
            }

            // Tell the listener.
            if (proposedRotation != oldProposedRotation && proposedRotation >= 0) {
                if (LOG) {
                    Slog.v(TAG, "Proposed rotation changed!  proposedRotation=" + proposedRotation
                            + ", oldProposedRotation=" + oldProposedRotation);
                }
                onProposedRotationChanged(proposedRotation);
            }
        }

        @Override
        public void onTouchStartLocked() {
            mTouched = true;
        }

        @Override
        public void onTouchEndLocked(long whenElapsedNanos) {
            mTouched = false;
            mTouchEndedTimestampNanos = whenElapsedNanos;
        }

        @Override
        public void resetLocked(boolean clearCurrentRotation) {
            mLastFilteredTimestampNanos = Long.MIN_VALUE;
            if (clearCurrentRotation) {
                mProposedRotation = -1;
            }
            mFlatTimestampNanos = Long.MIN_VALUE;
            mFlat = false;
            mSwingTimestampNanos = Long.MIN_VALUE;
            mSwinging = false;
            mAccelerationTimestampNanos = Long.MIN_VALUE;
            mAccelerating = false;
            mOverhead = false;
            clearPredictedRotationLocked();
            clearTiltHistoryLocked();
        }


        /**
         * Returns true if the tilt angle is acceptable for a given predicted rotation.
         */
        private boolean isTiltAngleAcceptableLocked(int rotation, int tiltAngle) {
            return tiltAngle >= mTiltToleranceConfig[rotation][0]
                    && tiltAngle <= mTiltToleranceConfig[rotation][1];
        }

        /**
         * Returns true if the orientation angle is acceptable for a given predicted rotation.
         *
         * This function takes into account the gap between adjacent orientations
         * for hysteresis.
         */
        private boolean isOrientationAngleAcceptableLocked(int rotation, int orientationAngle) {
            // If there is no current rotation, then there is no gap.
            // The gap is used only to introduce hysteresis among advertised orientation
            // changes to avoid flapping.
            final int currentRotation = mCurrentRotation;
            if (currentRotation >= 0) {
                // If the specified rotation is the same or is counter-clockwise adjacent
                // to the current rotation, then we set a lower bound on the orientation angle.
                // For example, if currentRotation is ROTATION_0 and proposed is ROTATION_90,
                // then we want to check orientationAngle > 45 + GAP / 2.
                if (rotation == currentRotation
                        || rotation == (currentRotation + 1) % 4) {
                    int lowerBound = rotation * 90 - 45
                            + ADJACENT_ORIENTATION_ANGLE_GAP / 2;
                    if (rotation == 0) {
                        if (orientationAngle >= 315 && orientationAngle < lowerBound + 360) {
                            return false;
                        }
                    } else {
                        if (orientationAngle < lowerBound) {
                            return false;
                        }
                    }
                }

                // If the specified rotation is the same or is clockwise adjacent,
                // then we set an upper bound on the orientation angle.
                // For example, if currentRotation is ROTATION_0 and rotation is ROTATION_270,
                // then we want to check orientationAngle < 315 - GAP / 2.
                if (rotation == currentRotation
                        || rotation == (currentRotation + 3) % 4) {
                    int upperBound = rotation * 90 + 45
                            - ADJACENT_ORIENTATION_ANGLE_GAP / 2;
                    if (rotation == 0) {
                        if (orientationAngle <= 45 && orientationAngle > upperBound) {
                            return false;
                        }
                    } else {
                        if (orientationAngle > upperBound) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        /**
         * Returns true if the predicted rotation is ready to be advertised as a
         * proposed rotation.
         */
        private boolean isPredictedRotationAcceptableLocked(long now) {
            // The predicted rotation must have settled long enough.
            if (now < mPredictedRotationTimestampNanos + PROPOSAL_SETTLE_TIME_NANOS) {
                return false;
            }

            // The last flat state (time since picked up) must have been sufficiently long ago.
            if (now < mFlatTimestampNanos + PROPOSAL_MIN_TIME_SINCE_FLAT_ENDED_NANOS) {
                return false;
            }

            // The last swing state (time since last movement to put down) must have been
            // sufficiently long ago.
            if (now < mSwingTimestampNanos + PROPOSAL_MIN_TIME_SINCE_SWING_ENDED_NANOS) {
                return false;
            }

            // The last acceleration state must have been sufficiently long ago.
            if (now < mAccelerationTimestampNanos
                    + PROPOSAL_MIN_TIME_SINCE_ACCELERATION_ENDED_NANOS) {
                return false;
            }

            // The last touch must have ended sufficiently long ago.
            if (mTouched || now < mTouchEndedTimestampNanos
                    + PROPOSAL_MIN_TIME_SINCE_TOUCH_END_NANOS) {
                return false;
            }

            // Looks good!
            return true;
        }

        private void clearPredictedRotationLocked() {
            mPredictedRotation = -1;
            mPredictedRotationTimestampNanos = Long.MIN_VALUE;
        }

        private void updatePredictedRotationLocked(long now, int rotation) {
            if (mPredictedRotation != rotation) {
                mPredictedRotation = rotation;
                mPredictedRotationTimestampNanos = now;
            }
        }

        private boolean isAcceleratingLocked(float magnitude) {
            return magnitude < MIN_ACCELERATION_MAGNITUDE
                    || magnitude > MAX_ACCELERATION_MAGNITUDE;
        }

        private void clearTiltHistoryLocked() {
            mTiltHistoryTimestampNanos[0] = Long.MIN_VALUE;
            mTiltHistoryIndex = 1;
        }

        private void addTiltHistoryEntryLocked(long now, float tilt) {
            mTiltHistory[mTiltHistoryIndex] = tilt;
            mTiltHistoryTimestampNanos[mTiltHistoryIndex] = now;
            mTiltHistoryIndex = (mTiltHistoryIndex + 1) % TILT_HISTORY_SIZE;
            mTiltHistoryTimestampNanos[mTiltHistoryIndex] = Long.MIN_VALUE;
        }

        private boolean isFlatLocked(long now) {
            for (int i = mTiltHistoryIndex; (i = nextTiltHistoryIndexLocked(i)) >= 0; ) {
                if (mTiltHistory[i] < FLAT_ANGLE) {
                    break;
                }
                if (mTiltHistoryTimestampNanos[i] + FLAT_TIME_NANOS <= now) {
                    // Tilt has remained greater than FLAT_TILT_ANGLE for FLAT_TIME_NANOS.
                    return true;
                }
            }
            return false;
        }

        private boolean isSwingingLocked(long now, float tilt) {
            for (int i = mTiltHistoryIndex; (i = nextTiltHistoryIndexLocked(i)) >= 0; ) {
                if (mTiltHistoryTimestampNanos[i] + SWING_TIME_NANOS < now) {
                    break;
                }
                if (mTiltHistory[i] + SWING_AWAY_ANGLE_DELTA <= tilt) {
                    // Tilted away by SWING_AWAY_ANGLE_DELTA within SWING_TIME_NANOS.
                    return true;
                }
            }
            return false;
        }

        private int nextTiltHistoryIndexLocked(int index) {
            index = (index == 0 ? TILT_HISTORY_SIZE : index) - 1;
            return mTiltHistoryTimestampNanos[index] != Long.MIN_VALUE ? index : -1;
        }

        private float getLastTiltLocked() {
            int index = nextTiltHistoryIndexLocked(mTiltHistoryIndex);
            return index >= 0 ? mTiltHistory[index] : Float.NaN;
        }

        private float remainingMS(long now, long until) {
            return now >= until ? 0 : (until - now) * 0.000001f;
        }
    }

    final class OrientationSensorJudge extends OrientationJudge {
        private static final int ROTATION_UNSET = -1;
        private boolean mTouching;
        private long mTouchEndedTimestampNanos = Long.MIN_VALUE;
        private int mProposedRotation = -1;
        private int mDesiredRotation = -1;
        private boolean mRotationEvaluationScheduled;
        private long mRotationResolverTimeoutMillis;
        private long mRotationMemorizationTimeoutMillis;
        private final ActivityTaskManagerInternal mActivityTaskManagerInternal;
        private long mLastRotationResolutionTimeStamp;
        private int mLastRotationResolution = ROTATION_UNSET;
        private int mCurrentCallbackId = 0;
        private Runnable mCancelRotationResolverRequest;

        OrientationSensorJudge() {
            super();
            setupRotationResolverParameters();

            mActivityTaskManagerInternal =
                    LocalServices.getService(ActivityTaskManagerInternal.class);
        }

        private void setupRotationResolverParameters() {
            DeviceConfig.addOnPropertiesChangedListener(NAMESPACE_WINDOW_MANAGER,
                    ActivityThread.currentApplication().getMainExecutor(), (properties) -> {
                        final Set<String> keys = properties.getKeyset();
                        if (keys.contains(KEY_ROTATION_RESOLVER_TIMEOUT)
                                || keys.contains(KEY_ROTATION_MEMORIZATION_TIMEOUT)) {
                            readRotationResolverParameters();
                        }
                    });
            readRotationResolverParameters();
        }

        private void readRotationResolverParameters() {
            mRotationResolverTimeoutMillis = DeviceConfig.getLong(
                    NAMESPACE_WINDOW_MANAGER,
                    KEY_ROTATION_RESOLVER_TIMEOUT,
                    DEFAULT_ROTATION_RESOLVER_TIMEOUT_MILLIS);
            mRotationMemorizationTimeoutMillis = DeviceConfig.getLong(
                    NAMESPACE_WINDOW_MANAGER,
                    KEY_ROTATION_MEMORIZATION_TIMEOUT,
                    DEFAULT_ROTATION_MEMORIZATION_TIMEOUT_MILLIS);
        }

        @Override
        public int getProposedRotationLocked() {
            return mProposedRotation;
        }

        @Override
        public void onTouchStartLocked() {
            mTouching = true;
        }

        @Override
        public void onTouchEndLocked(long whenElapsedNanos) {
            mTouching = false;
            mTouchEndedTimestampNanos = whenElapsedNanos;
            if (mDesiredRotation != mProposedRotation) {
                final long now = SystemClock.elapsedRealtimeNanos();
                scheduleRotationEvaluationIfNecessaryLocked(now);
            }
        }


        @Override
        public void onSensorChanged(SensorEvent event) {
            int reportedRotation = (int) event.values[0];
            if (reportedRotation < 0 || reportedRotation > 3) {
                return;
            }

            FrameworkStatsLog.write(
                    FrameworkStatsLog.DEVICE_ROTATED,
                    event.timestamp,
                    rotationToLogEnum(reportedRotation),
                    FrameworkStatsLog.DEVICE_ROTATED__ROTATION_EVENT_TYPE__ACTUAL_EVENT);

            if (isRotationResolverEnabled()) {
                if (isKeyguardLocked()) {
                    if (mLastRotationResolution != ROTATION_UNSET
                            && SystemClock.uptimeMillis() - mLastRotationResolutionTimeStamp
                            < mRotationMemorizationTimeoutMillis) {
                        Slog.d(TAG,
                                "Reusing the last rotation resolution: " + mLastRotationResolution);
                        finalizeRotation(mLastRotationResolution);
                    } else {
                        finalizeRotation(Surface.ROTATION_0);
                    }
                    return;
                }

                if (mRotationResolverService == null) {
                    mRotationResolverService = LocalServices.getService(
                            RotationResolverInternal.class);
                    if (mRotationResolverService == null) {
                        finalizeRotation(reportedRotation);
                        return;
                    }
                }

                String packageName = null;
                if (mActivityTaskManagerInternal != null) {
                    final WindowProcessController controller =
                            mActivityTaskManagerInternal.getTopApp();
                    if (controller != null
                            && controller.mInfo != null
                            && controller.mInfo.packageName != null) {
                        packageName = controller.mInfo.packageName;
                    }
                }

                mCurrentCallbackId++;

                if (mCancelRotationResolverRequest != null) {
                    getHandler().removeCallbacks(mCancelRotationResolverRequest);
                }
                final CancellationSignal cancellationSignal = new CancellationSignal();
                mCancelRotationResolverRequest = cancellationSignal::cancel;
                getHandler().postDelayed(
                        mCancelRotationResolverRequest, mRotationResolverTimeoutMillis);

                mRotationResolverService.resolveRotation(
                        new RotationResolverInternal.RotationResolverCallbackInternal() {
                            private final int mCallbackId = mCurrentCallbackId;
                            @Override
                            public void onSuccess(int result) {
                                finalizeRotationIfFresh(result);
                            }

                            @Override
                            public void onFailure(int error) {
                                finalizeRotationIfFresh(reportedRotation);
                            }

                            private void finalizeRotationIfFresh(int rotation) {
                                // Ignore the callback if it's not the latest.
                                if (mCallbackId == mCurrentCallbackId) {
                                    getHandler().removeCallbacks(mCancelRotationResolverRequest);
                                    finalizeRotation(rotation);
                                } else {
                                    Slog.d(TAG, String.format(
                                            "An outdated callback received [%s vs. %s]. Ignoring "
                                                    + "it.",
                                            mCallbackId, mCurrentCallbackId));
                                }
                            }
                        },
                        packageName,
                        reportedRotation,
                        mCurrentRotation,
                        mRotationResolverTimeoutMillis,
                        cancellationSignal);
            } else {
                finalizeRotation(reportedRotation);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) { }

        @Override
        public void dumpLocked(PrintWriter pw, String prefix) {
            pw.println(prefix + "OrientationSensorJudge");
            prefix += "  ";
            pw.println(prefix + "mDesiredRotation=" + Surface.rotationToString(mDesiredRotation));
            pw.println(prefix + "mProposedRotation="
                    + Surface.rotationToString(mProposedRotation));
            pw.println(prefix + "mTouching=" + mTouching);
            pw.println(prefix + "mTouchEndedTimestampNanos=" + mTouchEndedTimestampNanos);
            pw.println(prefix + "mLastRotationResolution=" + mLastRotationResolution);
        }

        @Override
        public void resetLocked(boolean clearCurrentRotation) {
            if (clearCurrentRotation) {
                mProposedRotation = -1;
                mDesiredRotation = -1;
            }
            mTouching = false;
            mTouchEndedTimestampNanos = Long.MIN_VALUE;
            unscheduleRotationEvaluationLocked();
        }

        public int evaluateRotationChangeLocked() {
            unscheduleRotationEvaluationLocked();
            if (mDesiredRotation == mProposedRotation) {
                return -1;
            }
            final long now = SystemClock.elapsedRealtimeNanos();
            if (isDesiredRotationAcceptableLocked(now)) {
                mProposedRotation = mDesiredRotation;
                return mProposedRotation;
            } else {
                scheduleRotationEvaluationIfNecessaryLocked(now);
            }
            return -1;
        }

        private void finalizeRotation(int reportedRotation) {
            int newRotation;
            synchronized (mLock) {
                mDesiredRotation = reportedRotation;
                newRotation = evaluateRotationChangeLocked();
            }
            if (newRotation >= 0) {
                mLastRotationResolution = newRotation;
                mLastRotationResolutionTimeStamp = SystemClock.uptimeMillis();
                onProposedRotationChanged(newRotation);
            }
        }

        private boolean isDesiredRotationAcceptableLocked(long now) {
            if (mTouching) {
                return false;
            }
            if (now < mTouchEndedTimestampNanos + PROPOSAL_MIN_TIME_SINCE_TOUCH_END_NANOS) {
                return false;
            }
            return true;
        }

        private void scheduleRotationEvaluationIfNecessaryLocked(long now) {
            if (mRotationEvaluationScheduled || mDesiredRotation == mProposedRotation) {
                if (LOG) {
                    Slog.d(TAG, "scheduleRotationEvaluationLocked: " +
                            "ignoring, an evaluation is already scheduled or is unnecessary.");
                }
                return;
            }
            if (mTouching) {
                if (LOG) {
                    Slog.d(TAG, "scheduleRotationEvaluationLocked: " +
                            "ignoring, user is still touching the screen.");
                }
                return;
            }
            long timeOfNextPossibleRotationNanos =
                mTouchEndedTimestampNanos + PROPOSAL_MIN_TIME_SINCE_TOUCH_END_NANOS;
            if (now >= timeOfNextPossibleRotationNanos) {
                if (LOG) {
                    Slog.d(TAG, "scheduleRotationEvaluationLocked: " +
                            "ignoring, already past the next possible time of rotation.");
                }
                return;
            }
            // Use a delay instead of an absolute time since handlers are in uptime millis and we
            // use elapsed realtime.
            final long delayMs =
                    (long) Math.ceil((timeOfNextPossibleRotationNanos - now) * MILLIS_PER_NANO);
            mHandler.postDelayed(mRotationEvaluator, delayMs);
            mRotationEvaluationScheduled = true;
        }

        private void unscheduleRotationEvaluationLocked() {
            if (!mRotationEvaluationScheduled) {
                return;
            }
            mHandler.removeCallbacks(mRotationEvaluator);
            mRotationEvaluationScheduled = false;
        }

        private Runnable mRotationEvaluator = new Runnable() {
            @Override
            public void run() {
                int newRotation;
                synchronized (mLock) {
                    mRotationEvaluationScheduled = false;
                    newRotation = evaluateRotationChangeLocked();
                }
                if (newRotation >= 0) {
                    onProposedRotationChanged(newRotation);
                }
            }
        };

        private int rotationToLogEnum(int rotation) {
            switch (rotation) {
                case 0:
                    return FrameworkStatsLog.DEVICE_ROTATED__PROPOSED_ORIENTATION__ROTATION_0;
                case 1:
                    return FrameworkStatsLog.DEVICE_ROTATED__PROPOSED_ORIENTATION__ROTATION_90;
                case 2:
                    return FrameworkStatsLog.DEVICE_ROTATED__PROPOSED_ORIENTATION__ROTATION_180;
                case 3:
                    return FrameworkStatsLog.DEVICE_ROTATED__PROPOSED_ORIENTATION__ROTATION_270;
                default:
                    return FrameworkStatsLog.DEVICE_ROTATED__PROPOSED_ORIENTATION__UNKNOWN;
            }
        }
    }
}
