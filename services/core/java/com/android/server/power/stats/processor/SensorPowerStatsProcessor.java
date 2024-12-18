/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.power.stats.processor;

import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.PersistableBundle;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.os.PowerStats;
import com.android.server.power.stats.format.PowerStatsLayout;
import com.android.server.power.stats.format.SensorPowerStatsLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

class SensorPowerStatsProcessor extends PowerStatsProcessor {
    private static final String TAG = "SensorPowerStatsProcessor";
    private static final String ANDROID_SENSOR_TYPE_PREFIX = "android.sensor.";

    private static final double MILLIS_IN_HOUR = 1000.0 * 60 * 60;
    private static final String SENSOR_EVENT_TAG_PREFIX = "sensor:0x";
    private final Supplier<SensorManager> mSensorManagerSupplier;

    private static final long INITIAL_TIMESTAMP = -1;
    private SensorManager mSensorManager;
    private SensorPowerStatsLayout mStatsLayout;
    private PowerStats mPowerStats;
    private boolean mIsInitialized;
    private PowerStats.Descriptor mDescriptor;
    private long mLastUpdateTimestamp;
    private PowerEstimationPlan mPlan;

    private static class SensorState {
        public int sensorHandle;
        public boolean stateOn;
        public int uid;
        public long startTime = INITIAL_TIMESTAMP;
    }

    private static class Intermediates {
        public double power;
    }

    private final SparseArray<SensorState> mSensorStates = new SparseArray<>();
    private long[] mTmpDeviceStatsArray;
    private long[] mTmpUidStatsArray;

    SensorPowerStatsProcessor(Supplier<SensorManager> sensorManagerSupplier) {
        mSensorManagerSupplier = sensorManagerSupplier;
    }

    private boolean ensureInitialized() {
        if (mIsInitialized) {
            return true;
        }

        mSensorManager = mSensorManagerSupplier.get();
        if (mSensorManager == null) {
            return false;
        }

        List<Sensor> sensorList = mSensorManager.getSensorList(Sensor.TYPE_ALL);
        mStatsLayout = new SensorPowerStatsLayout(sensorList.stream().collect(
                Collectors.toMap(Sensor::getHandle, sensor -> makeLabel(sensor, sensorList))));

        PersistableBundle extras = new PersistableBundle();
        mStatsLayout.toExtras(extras);
        mDescriptor = new PowerStats.Descriptor(
                BatteryConsumer.POWER_COMPONENT_SENSORS, mStatsLayout.getDeviceStatsArrayLength(),
                null, 0, mStatsLayout.getUidStatsArrayLength(),
                extras);

        mPowerStats = new PowerStats(mDescriptor);
        mTmpUidStatsArray = new long[mDescriptor.uidStatsArrayLength];
        mTmpDeviceStatsArray = new long[mDescriptor.statsArrayLength];

        mIsInitialized = true;
        return true;
    }

    private String makeLabel(Sensor sensor, List<Sensor> sensorList) {
        int type = sensor.getType();
        String label = sensor.getStringType();

        boolean isSingleton = true;
        for (int i = sensorList.size() - 1; i >= 0; i--) {
            Sensor s = sensorList.get(i);
            if (s == sensor) {
                continue;
            }
            if (s.getType() == type) {
                isSingleton = false;
                break;
            }
        }
        if (!isSingleton) {
            StringBuilder sb = new StringBuilder(label).append('.');
            if (sensor.getId() > 0) { // 0 and -1 are reserved
                sb.append(sensor.getId());
            } else {
                sb.append(sensor.getName());
            }
            label = sb.toString();
        }
        if (label.startsWith(ANDROID_SENSOR_TYPE_PREFIX)) {
            label = label.substring(ANDROID_SENSOR_TYPE_PREFIX.length());
        }
        return label.replace(' ', '_');
    }

    @Override
    void start(PowerComponentAggregatedPowerStats stats, long timestampMs) {
        if (!ensureInitialized()) {
            return;
        }

        // Establish a baseline at the beginning of an accumulation pass
        mLastUpdateTimestamp = timestampMs;
        flushPowerStats(stats, timestampMs);
    }

    @Override
    void noteStateChange(PowerComponentAggregatedPowerStats stats, BatteryStats.HistoryItem item) {
        if (!mIsInitialized) {
            return;
        }

        if (item.eventTag == null || item.eventTag.string == null
                || !item.eventTag.string.startsWith(SENSOR_EVENT_TAG_PREFIX)) {
            return;
        }

        int sensorHandle;
        try {
            sensorHandle = Integer.parseInt(item.eventTag.string, SENSOR_EVENT_TAG_PREFIX.length(),
                    item.eventTag.string.length(), 16);
        } catch (NumberFormatException e) {
            Slog.wtf(TAG, "Bad format of event tag: " + item.eventTag.string);
            return;
        }

        SensorState sensor = mSensorStates.get(sensorHandle);
        if (sensor == null) {
            sensor = new SensorState();
            sensor.sensorHandle = sensorHandle;
            mSensorStates.put(sensorHandle, sensor);
        }

        int uid = item.eventTag.uid;
        boolean sensorOn = (item.states & BatteryStats.HistoryItem.STATE_SENSOR_ON_FLAG) != 0;
        if (sensorOn) {
            if (!sensor.stateOn) {
                sensor.stateOn = true;
                sensor.uid = uid;
                sensor.startTime = item.time;
            } else if (sensor.uid != uid) {
                recordUsageDuration(sensor, item.time);
                sensor.uid = uid;
            }
        } else {
            if (sensor.stateOn) {
                recordUsageDuration(sensor, item.time);
                sensor.stateOn = false;
            }
        }
    }

    @Override
    void finish(PowerComponentAggregatedPowerStats stats, long timestampMs) {
        if (!mIsInitialized) {
            return;
        }

        for (int i = mSensorStates.size() - 1; i >= 0; i--) {
            SensorState sensor = mSensorStates.valueAt(i);
            if (sensor.stateOn) {
                recordUsageDuration(sensor, timestampMs);
            }
        }
        flushPowerStats(stats, timestampMs);

        if (mPlan == null) {
            mPlan = new PowerEstimationPlan(stats.getConfig());
        }

        List<Integer> uids = new ArrayList<>();
        stats.collectUids(uids);

        computeUidPowerEstimates(stats, uids);
        computeDevicePowerEstimates(stats);

        mPlan.resetIntermediates();
    }

    protected void recordUsageDuration(SensorState sensorState, long time) {
        long durationMs = Math.max(0, time - sensorState.startTime);
        if (durationMs > 0) {
            long[] uidStats = mPowerStats.uidStats.get(sensorState.uid);
            if (uidStats == null) {
                uidStats = new long[mDescriptor.uidStatsArrayLength];
                mPowerStats.uidStats.put(sensorState.uid, uidStats);
            }
            mStatsLayout.addUidSensorDuration(uidStats, sensorState.sensorHandle, durationMs);
        }
        sensorState.startTime = time;
    }

    private void flushPowerStats(
            PowerComponentAggregatedPowerStats stats, long timestamp) {
        mPowerStats.durationMs = timestamp - mLastUpdateTimestamp;
        stats.addProcessedPowerStats(mPowerStats, timestamp);

        Arrays.fill(mPowerStats.stats, 0);
        mPowerStats.uidStats.clear();
        mLastUpdateTimestamp = timestamp;
    }

    private void computeUidPowerEstimates(
            PowerComponentAggregatedPowerStats stats,
            List<Integer> uids) {
        List<Sensor> sensorList = mSensorManager.getSensorList(Sensor.TYPE_ALL);
        int[] uidSensorDurationPositions = new int[sensorList.size()];
        double[] sensorPower = new double[sensorList.size()];
        for (int i = sensorList.size() - 1; i >= 0; i--) {
            Sensor sensor = sensorList.get(i);
            uidSensorDurationPositions[i] =
                    mStatsLayout.getUidSensorDurationPosition(sensor.getHandle());
            sensorPower[i] = sensor.getPower() / MILLIS_IN_HOUR;
        }

        for (int i = mPlan.uidStateEstimates.size() - 1; i >= 0; i--) {
            UidStateEstimate uidStateEstimate = mPlan.uidStateEstimates.get(i);
            List<UidStateProportionalEstimate> proportionalEstimates =
                    uidStateEstimate.proportionalEstimates;
            for (int j = proportionalEstimates.size() - 1; j >= 0; j--) {
                UidStateProportionalEstimate proportionalEstimate = proportionalEstimates.get(j);
                for (int k = uids.size() - 1; k >= 0; k--) {
                    int uid = uids.get(k);
                    if (!stats.getUidStats(mTmpUidStatsArray, uid,
                            proportionalEstimate.stateValues)) {
                        continue;
                    }
                    double power = 0;
                    for (int m = 0; m < uidSensorDurationPositions.length; m++) {
                        int position = uidSensorDurationPositions[m];
                        if (position == PowerStatsLayout.UNSUPPORTED
                                || mTmpUidStatsArray[position] == 0) {
                            continue;
                        }
                        power += sensorPower[m] * mTmpUidStatsArray[position];
                    }
                    if (power == 0) {
                        continue;
                    }

                    mStatsLayout.setUidPowerEstimate(mTmpUidStatsArray, power);
                    stats.setUidStats(uid, proportionalEstimate.stateValues, mTmpUidStatsArray);

                    Intermediates intermediates = (Intermediates) uidStateEstimate
                            .combinedDeviceStateEstimate.intermediates;
                    if (intermediates == null) {
                        intermediates = new Intermediates();
                        uidStateEstimate.combinedDeviceStateEstimate.intermediates = intermediates;
                    }
                    intermediates.power += power;
                }
            }
        }
    }

    private void computeDevicePowerEstimates(
            PowerComponentAggregatedPowerStats stats) {
        for (int i = mPlan.combinedDeviceStateEstimations.size() - 1; i >= 0; i--) {
            CombinedDeviceStateEstimate estimation =
                    mPlan.combinedDeviceStateEstimations.get(i);
            if (estimation.intermediates == null) {
                continue;
            }

            if (!stats.getDeviceStats(mTmpDeviceStatsArray, estimation.stateValues)) {
                continue;
            }

            mStatsLayout.setDevicePowerEstimate(mTmpDeviceStatsArray,
                    ((Intermediates) estimation.intermediates).power);
            stats.setDeviceStats(estimation.stateValues, mTmpDeviceStatsArray);
        }
    }
}
