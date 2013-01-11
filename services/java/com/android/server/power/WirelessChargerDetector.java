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
import android.util.Slog;

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
final class WirelessChargerDetector {
    private static final String TAG = "WirelessChargerDetector";
    private static final boolean DEBUG = false;

    // Number of nanoseconds per millisecond.
    private static final long NANOS_PER_MS = 1000000;

    // The minimum amount of time to spend watching the sensor before making
    // a determination of whether movement occurred.
    private static final long SETTLE_TIME_NANOS = 500 * NANOS_PER_MS;

    // The minimum number of samples that must be collected.
    private static final int MIN_SAMPLES = 3;

    // Upper bound on the battery charge percentage in order to consider turning
    // the screen on when the device starts charging wirelessly.
    private static final int WIRELESS_CHARGER_TURN_ON_BATTERY_LEVEL_LIMIT = 95;

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

    // True if the rest position should be updated if at rest.
    // Otherwise, the current rest position is simply checked and cleared if movement
    // is detected but no new rest position is stored.
    private boolean mMustUpdateRestPosition;

    // The total number of samples collected.
    private int mTotalSamples;

    // The number of samples collected that showed evidence of not being at rest.
    private int mMovingSamples;

    // The time and value of the first sample that was collected.
    private long mFirstSampleTime;
    private float mFirstSampleX, mFirstSampleY, mFirstSampleZ;

    public WirelessChargerDetector(SensorManager sensorManager,
            SuspendBlocker suspendBlocker) {
        mSensorManager = sensorManager;
        mSuspendBlocker = suspendBlocker;

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
            pw.println("  mMustUpdateRestPosition=" + mMustUpdateRestPosition);
            pw.println("  mTotalSamples=" + mTotalSamples);
            pw.println("  mMovingSamples=" + mMovingSamples);
            pw.println("  mFirstSampleTime=" + mFirstSampleTime);
            pw.println("  mFirstSampleX=" + mFirstSampleX
                    + ", mFirstSampleY=" + mFirstSampleY + ", mFirstSampleZ=" + mFirstSampleZ);
        }
    }

    /**
     * Updates the charging state and returns true if docking was detected.
     *
     * @param isPowered True if the device is powered.
     * @param plugType The current plug type.
     * @param batteryLevel The current battery level.
     * @return True if the device is determined to have just been docked on a wireless
     * charger, after suppressing spurious docking or undocking signals.
     */
    public boolean update(boolean isPowered, int plugType, int batteryLevel) {
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
            // receiving power wirelessly, has a high enough battery level that we
            // can be assured that charging was not delayed due to the battery previously
            // having been full, and the device is not known to already be at rest
            // on the wireless charger from earlier.
            return mPoweredWirelessly && !wasPoweredWirelessly
                    && batteryLevel < WIRELESS_CHARGER_TURN_ON_BATTERY_LEVEL_LIMIT
                    && !mAtRest;
        }
    }

    private void startDetectionLocked() {
        if (!mDetectionInProgress && mGravitySensor != null) {
            if (mSensorManager.registerListener(mListener, mGravitySensor,
                    SensorManager.SENSOR_DELAY_UI)) {
                mSuspendBlocker.acquire();
                mDetectionInProgress = true;
                mTotalSamples = 0;
                mMovingSamples = 0;
            }
        }
    }

    private void processSample(long timeNanos, float x, float y, float z) {
        synchronized (mLock) {
            if (!mDetectionInProgress) {
                return;
            }

            mTotalSamples += 1;
            if (mTotalSamples == 1) {
                // Save information about the first sample collected.
                mFirstSampleTime = timeNanos;
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

            // Save the result when done.
            if (timeNanos - mFirstSampleTime >= SETTLE_TIME_NANOS
                    && mTotalSamples >= MIN_SAMPLES) {
                mSensorManager.unregisterListener(mListener);
                if (mMustUpdateRestPosition) {
                    if (mMovingSamples == 0) {
                        mAtRest = true;
                        mRestX = x;
                        mRestY = y;
                        mRestZ = z;
                    } else {
                        clearAtRestLocked();
                    }
                    mMustUpdateRestPosition = false;
                }
                mDetectionInProgress = false;
                mSuspendBlocker.release();

                if (DEBUG) {
                    Slog.d(TAG, "New state: mAtRest=" + mAtRest
                            + ", mRestX=" + mRestX + ", mRestY=" + mRestY + ", mRestZ=" + mRestZ
                            + ", mTotalSamples=" + mTotalSamples
                            + ", mMovingSamples=" + mMovingSamples);
                }
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
            processSample(event.timestamp, event.values[0], event.values[1], event.values[2]);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };
}
