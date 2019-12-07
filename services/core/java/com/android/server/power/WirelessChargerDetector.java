/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.power;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.Slog;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;

/**
 * Implements heuristics to detect docking or undocking from a wireless charger.
 * <p>
 * Some devices have wireless charging circuits that are unable to detect when the
 * device is resting on a wireless charger except when the device is actually
 * receiving power from the charger.  The device may stop receiving power
 * if the battery is already nearly full or if it is too hot.  As a result, we cannot
 * always rely on the battery service wireless plug signal to accurately indicate
 * whether the device has been docked or undocked from a wireless charger.
 * </p><p>
 * This is a problem because the power manager typically wakes up the screen and
 * plays a tone when the device is docked in a wireless charger.  It is important
 * for the system to suppress spurious docking and undocking signals because they
 * can be intrusive for the user (especially if they cause a tone to be played
 * late at night for no apparent reason).
 * </p><p>
 * To avoid spurious signals, we apply some special policies to wireless chargers.
 * </p><p>
 * 1. Don't wake the device when undocked from the wireless charger because
 * it might be that the device is still resting on the wireless charger
 * but is not receiving power anymore because the battery is full.
 * Ideally we would wake the device if we could be certain that the user had
 * picked it up from the wireless charger but due to hardware limitations we
 * must be more conservative.
 * </p><p>
 * 2. Don't wake the device when docked on a wireless charger if the
 * battery already appears to be mostly full.  This situation may indicate
 * that the device was resting on the charger the whole time and simply
 * wasn't receiving power because the battery was already full.  We can't tell
 * whether the device was just placed on the charger or whether it has
 * been there for half of the night slowly discharging until it reached
 * the point where it needed to start charging again.  So we suppress docking
 * signals that occur when the battery level is above a given threshold.
 * </p><p>
 * 3. Don't wake the device when docked on a wireless charger if it does
 * not appear to have moved since it was last undocked because it may
 * be that the prior undocking signal was spurious.  We use the gravity
 * sensor to detect this case.
 * </p>
 */
@VisibleForTesting
public class WirelessChargerDetector {
    private static final String TAG = "WirelessChargerDetector";
    private static final boolean DEBUG = false;

    // The minimum amount of time to spend watching the sensor before making
    // a determination of whether movement occurred.
    private static final long SETTLE_TIME_MILLIS = 800;

    // The sensor sampling interval.
    private static final int SAMPLING_INTERVAL_MILLIS = 50;

    // The minimum number of samples that must be collected.
    private static final int MIN_SAMPLES = 3;

    // To detect movement, we compute the angle between the gravity vector
    // at rest and the current gravity vector.  This field specifies the
    // cosine of the maximum angle variance that we tolerate while at rest.
    private static final double MOVEMENT_ANGLE_COS_THRESHOLD = Math.cos(5 * Math.PI / 180);

    // Sanity thresholds for the gravity vector.
    private static final double MIN_GRAVITY = SensorManager.GRAVITY_EARTH - 1.0f;
    private static final double MAX_GRAVITY = SensorManager.GRAVITY_EARTH + 1.0f;

    private final Object mLock = new Object();

    private final SensorManager mSensorManager;
    private final SuspendBlocker mSuspendBlocker;
    private final Handler mHandler;

    // The gravity sensor, or null if none.
    private Sensor mGravitySensor;

    // Previously observed wireless power state.
    private boolean mPoweredWirelessly;

    // True if the device is thought to be at rest on a wireless charger.
    private boolean mAtRest;

    // The gravity vector most recently observed while at rest.
    private float mRestX, mRestY, mRestZ;

    /* These properties are only meaningful while detection is in progress. */

    // True if detection is in progress.
    // The suspend blocker is held while this is the case.
    private boolean mDetectionInProgress;

    // The time when detection was last performed.
    private long mDetectionStartTime;

    // True if the rest position should be updated if at rest.
    // Otherwise, the current rest position is simply checked and cleared if movement
    // is detected but no new rest position is stored.
    private boolean mMustUpdateRestPosition;

    // The total number of samples collected.
    private int mTotalSamples;

    // The number of samples collected that showed evidence of not being at rest.
    private int mMovingSamples;

    // The value of the first sample that was collected.
    private float mFirstSampleX, mFirstSampleY, mFirstSampleZ;

    // The value of the last sample that was collected.
    private float mLastSampleX, mLastSampleY, mLastSampleZ;

    public WirelessChargerDetector(SensorManager sensorManager,
            SuspendBlocker suspendBlocker, Handler handler) {
        mSensorManager = sensorManager;
        mSuspendBlocker = suspendBlocker;
        mHandler = handler;

        mGravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
    }

    public void dump(PrintWriter pw) {
        synchronized (mLock) {
            pw.println();
            pw.println("Wireless Charger Detector State:");
            pw.println("  mGravitySensor=" + mGravitySensor);
            pw.println("  mPoweredWirelessly=" + mPoweredWirelessly);
            pw.println("  mAtRest=" + mAtRest);
            pw.println("  mRestX=" + mRestX + ", mRestY=" + mRestY + ", mRestZ=" + mRestZ);
            pw.println("  mDetectionInProgress=" + mDetectionInProgress);
            pw.println("  mDetectionStartTime=" + (mDetectionStartTime == 0 ? "0 (never)"
                    : TimeUtils.formatUptime(mDetectionStartTime)));
            pw.println("  mMustUpdateRestPosition=" + mMustUpdateRestPosition);
            pw.println("  mTotalSamples=" + mTotalSamples);
            pw.println("  mMovingSamples=" + mMovingSamples);
            pw.println("  mFirstSampleX=" + mFirstSampleX
                    + ", mFirstSampleY=" + mFirstSampleY + ", mFirstSampleZ=" + mFirstSampleZ);
            pw.println("  mLastSampleX=" + mLastSampleX
                    + ", mLastSampleY=" + mLastSampleY + ", mLastSampleZ=" + mLastSampleZ);
        }
    }

    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long wcdToken = proto.start(fieldId);
        synchronized (mLock) {
            proto.write(WirelessChargerDetectorProto.IS_POWERED_WIRELESSLY, mPoweredWirelessly);
            proto.write(WirelessChargerDetectorProto.IS_AT_REST, mAtRest);

            final long restVectorToken = proto.start(WirelessChargerDetectorProto.REST);
            proto.write(WirelessChargerDetectorProto.VectorProto.X, mRestX);
            proto.write(WirelessChargerDetectorProto.VectorProto.Y, mRestY);
            proto.write(WirelessChargerDetectorProto.VectorProto.Z, mRestZ);
            proto.end(restVectorToken);

            proto.write(
                    WirelessChargerDetectorProto.IS_DETECTION_IN_PROGRESS, mDetectionInProgress);
            proto.write(WirelessChargerDetectorProto.DETECTION_START_TIME_MS, mDetectionStartTime);
            proto.write(
                    WirelessChargerDetectorProto.IS_MUST_UPDATE_REST_POSITION,
                    mMustUpdateRestPosition);
            proto.write(WirelessChargerDetectorProto.TOTAL_SAMPLES, mTotalSamples);
            proto.write(WirelessChargerDetectorProto.MOVING_SAMPLES, mMovingSamples);

            final long firstSampleVectorToken =
                    proto.start(WirelessChargerDetectorProto.FIRST_SAMPLE);
            proto.write(WirelessChargerDetectorProto.VectorProto.X, mFirstSampleX);
            proto.write(WirelessChargerDetectorProto.VectorProto.Y, mFirstSampleY);
            proto.write(WirelessChargerDetectorProto.VectorProto.Z, mFirstSampleZ);
            proto.end(firstSampleVectorToken);

            final long lastSampleVectorToken =
                    proto.start(WirelessChargerDetectorProto.LAST_SAMPLE);
            proto.write(WirelessChargerDetectorProto.VectorProto.X, mLastSampleX);
            proto.write(WirelessChargerDetectorProto.VectorProto.Y, mLastSampleY);
            proto.write(WirelessChargerDetectorProto.VectorProto.Z, mLastSampleZ);
            proto.end(lastSampleVectorToken);
        }
        proto.end(wcdToken);
    }

    /**
     * Updates the charging state and returns true if docking was detected.
     *
     * @param isPowered True if the device is powered.
     * @param plugType The current plug type.
     * @return True if the device is determined to have just been docked on a wireless
     * charger, after suppressing spurious docking or undocking signals.
     */
    public boolean update(boolean isPowered, int plugType) {
        synchronized (mLock) {
            final boolean wasPoweredWirelessly = mPoweredWirelessly;

            if (isPowered && plugType == BatteryManager.BATTERY_PLUGGED_WIRELESS) {
                // The device is receiving power from the wireless charger.
                // Update the rest position asynchronously.
                mPoweredWirelessly = true;
                mMustUpdateRestPosition = true;
                startDetectionLocked();
            } else {
                // The device may or may not be on the wireless charger depending on whether
                // the unplug signal that we received was spurious.
                mPoweredWirelessly = false;
                if (mAtRest) {
                    if (plugType != 0 && plugType != BatteryManager.BATTERY_PLUGGED_WIRELESS) {
                        // The device was plugged into a new non-wireless power source.
                        // It's safe to assume that it is no longer on the wireless charger.
                        mMustUpdateRestPosition = false;
                        clearAtRestLocked();
                    } else {
                        // The device may still be on the wireless charger but we don't know.
                        // Check whether the device has remained at rest on the charger
                        // so that we will know to ignore the next wireless plug event
                        // if needed.
                        startDetectionLocked();
                    }
                }
            }

            // Report that the device has been docked only if the device just started
            // receiving power wirelessly and the device is not known to already be at rest
            // on the wireless charger from earlier.
            return mPoweredWirelessly && !wasPoweredWirelessly && !mAtRest;
        }
    }

    private void startDetectionLocked() {
        if (!mDetectionInProgress && mGravitySensor != null) {
            if (mSensorManager.registerListener(mListener, mGravitySensor,
                    SAMPLING_INTERVAL_MILLIS * 1000)) {
                mSuspendBlocker.acquire();
                mDetectionInProgress = true;
                mDetectionStartTime = SystemClock.uptimeMillis();
                mTotalSamples = 0;
                mMovingSamples = 0;

                Message msg = Message.obtain(mHandler, mSensorTimeout);
                msg.setAsynchronous(true);
                mHandler.sendMessageDelayed(msg, SETTLE_TIME_MILLIS);
            }
        }
    }

    private void finishDetectionLocked() {
        if (mDetectionInProgress) {
            mSensorManager.unregisterListener(mListener);
            mHandler.removeCallbacks(mSensorTimeout);

            if (mMustUpdateRestPosition) {
                clearAtRestLocked();
                if (mTotalSamples < MIN_SAMPLES) {
                    Slog.w(TAG, "Wireless charger detector is broken.  Only received "
                            + mTotalSamples + " samples from the gravity sensor but we "
                            + "need at least " + MIN_SAMPLES + " and we expect to see "
                            + "about " + SETTLE_TIME_MILLIS / SAMPLING_INTERVAL_MILLIS
                            + " on average.");
                } else if (mMovingSamples == 0) {
                    mAtRest = true;
                    mRestX = mLastSampleX;
                    mRestY = mLastSampleY;
                    mRestZ = mLastSampleZ;
                }
                mMustUpdateRestPosition = false;
            }

            if (DEBUG) {
                Slog.d(TAG, "New state: mAtRest=" + mAtRest
                        + ", mRestX=" + mRestX + ", mRestY=" + mRestY + ", mRestZ=" + mRestZ
                        + ", mTotalSamples=" + mTotalSamples
                        + ", mMovingSamples=" + mMovingSamples);
            }

            mDetectionInProgress = false;
            mSuspendBlocker.release();
        }
    }

    private void processSampleLocked(float x, float y, float z) {
        if (mDetectionInProgress) {
            mLastSampleX = x;
            mLastSampleY = y;
            mLastSampleZ = z;

            mTotalSamples += 1;
            if (mTotalSamples == 1) {
                // Save information about the first sample collected.
                mFirstSampleX = x;
                mFirstSampleY = y;
                mFirstSampleZ = z;
            } else {
                // Determine whether movement has occurred relative to the first sample.
                if (hasMoved(mFirstSampleX, mFirstSampleY, mFirstSampleZ, x, y, z)) {
                    mMovingSamples += 1;
                }
            }

            // Clear the at rest flag if movement has occurred relative to the rest sample.
            if (mAtRest && hasMoved(mRestX, mRestY, mRestZ, x, y, z)) {
                if (DEBUG) {
                    Slog.d(TAG, "No longer at rest: "
                            + "mRestX=" + mRestX + ", mRestY=" + mRestY + ", mRestZ=" + mRestZ
                            + ", x=" + x + ", y=" + y + ", z=" + z);
                }
                clearAtRestLocked();
            }
        }
    }

    private void clearAtRestLocked() {
        mAtRest = false;
        mRestX = 0;
        mRestY = 0;
        mRestZ = 0;
    }

    private static boolean hasMoved(float x1, float y1, float z1,
            float x2, float y2, float z2) {
        final double dotProduct = (x1 * x2) + (y1 * y2) + (z1 * z2);
        final double mag1 = Math.sqrt((x1 * x1) + (y1 * y1) + (z1 * z1));
        final double mag2 = Math.sqrt((x2 * x2) + (y2 * y2) + (z2 * z2));
        if (mag1 < MIN_GRAVITY || mag1 > MAX_GRAVITY
                || mag2 < MIN_GRAVITY || mag2 > MAX_GRAVITY) {
            if (DEBUG) {
                Slog.d(TAG, "Weird gravity vector: mag1=" + mag1 + ", mag2=" + mag2);
            }
            return true;
        }
        final boolean moved = (dotProduct < mag1 * mag2 * MOVEMENT_ANGLE_COS_THRESHOLD);
        if (DEBUG) {
            Slog.d(TAG, "Check: moved=" + moved
                    + ", x1=" + x1 + ", y1=" + y1 + ", z1=" + z1
                    + ", x2=" + x2 + ", y2=" + y2 + ", z2=" + z2
                    + ", angle=" + (Math.acos(dotProduct / mag1 / mag2) * 180 / Math.PI)
                    + ", dotProduct=" + dotProduct
                    + ", mag1=" + mag1 + ", mag2=" + mag2);
        }
        return moved;
    }

    private final SensorEventListener mListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            synchronized (mLock) {
                processSampleLocked(event.values[0], event.values[1], event.values[2]);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    private final Runnable mSensorTimeout = new Runnable() {
        @Override
        public void run() {
            synchronized (mLock) {
                finishDetectionLocked();
            }
        }
    };
}
