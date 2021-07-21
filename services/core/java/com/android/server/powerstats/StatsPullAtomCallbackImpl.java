/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.powerstats;

import android.app.StatsManager;
import android.content.Context;
import android.hardware.power.stats.Channel;
import android.hardware.power.stats.EnergyMeasurement;
import android.hardware.power.stats.PowerEntity;
import android.hardware.power.stats.State;
import android.hardware.power.stats.StateResidency;
import android.hardware.power.stats.StateResidencyResult;
import android.power.PowerStatsInternal;
import android.util.Slog;
import android.util.StatsEvent;

import com.android.internal.util.ConcurrentUtils;
import com.android.internal.util.FrameworkStatsLog;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * StatsPullAtomCallbackImpl is responsible implementing the stats pullers for
 * SUBSYSTEM_SLEEP_STATE and ON_DEVICE_POWER_MEASUREMENT statsd atoms.
 */
public class StatsPullAtomCallbackImpl implements StatsManager.StatsPullAtomCallback {
    private static final String TAG = StatsPullAtomCallbackImpl.class.getSimpleName();
    private Context mContext;
    private PowerStatsInternal mPowerStatsInternal;
    private Map<Integer, Channel> mChannels = new HashMap();
    private Map<Integer, String> mEntityNames = new HashMap();
    private Map<Integer, Map<Integer, String>> mStateNames = new HashMap();
    private static final int STATS_PULL_TIMEOUT_MILLIS = 2000;
    private static final boolean DEBUG = false;

    @Override
    public int onPullAtom(int atomTag, List<StatsEvent> data) {
        switch (atomTag) {
            case FrameworkStatsLog.SUBSYSTEM_SLEEP_STATE:
                return pullSubsystemSleepState(atomTag, data);
            case FrameworkStatsLog.ON_DEVICE_POWER_MEASUREMENT:
                return pullOnDevicePowerMeasurement(atomTag, data);
            default:
                throw new UnsupportedOperationException("Unknown tagId=" + atomTag);
        }
    }

    private boolean initPullOnDevicePowerMeasurement() {
        Channel[] channels = mPowerStatsInternal.getEnergyMeterInfo();
        if (channels == null || channels.length == 0) {
            Slog.e(TAG, "Failed to init OnDevicePowerMeasurement puller");
            return false;
        }

        for (int i = 0; i < channels.length; i++) {
            final Channel channel = channels[i];
            mChannels.put(channel.id, channel);
        }

        return true;
    }

    private int pullOnDevicePowerMeasurement(int atomTag, List<StatsEvent> events) {
        final EnergyMeasurement[] energyMeasurements;
        try {
            energyMeasurements = mPowerStatsInternal.readEnergyMeterAsync(new int[0])
                    .get(STATS_PULL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to readEnergyMeterAsync", e);
            return StatsManager.PULL_SKIP;
        }

        if (energyMeasurements == null) return StatsManager.PULL_SKIP;

        for (int i = 0; i < energyMeasurements.length; i++) {
            // Only report energy measurements that have been accumulated since boot
            final EnergyMeasurement energyMeasurement = energyMeasurements[i];
            if (energyMeasurement.durationMs == energyMeasurement.timestampMs) {
                events.add(FrameworkStatsLog.buildStatsEvent(
                        atomTag,
                        mChannels.get(energyMeasurement.id).subsystem,
                        mChannels.get(energyMeasurement.id).name,
                        energyMeasurement.durationMs,
                        energyMeasurement.energyUWs));
            }
        }

        return StatsManager.PULL_SUCCESS;
    }

    private boolean initSubsystemSleepState() {
        PowerEntity[] entities = mPowerStatsInternal.getPowerEntityInfo();
        if (entities == null || entities.length == 0) {
            Slog.e(TAG, "Failed to init SubsystemSleepState puller");
            return false;
        }

        for (int i = 0; i < entities.length; i++) {
            final PowerEntity entity = entities[i];
            Map<Integer, String> states = new HashMap();
            for (int j = 0; j < entity.states.length; j++) {
                final State state = entity.states[j];
                states.put(state.id, state.name);
            }

            mEntityNames.put(entity.id, entity.name);
            mStateNames.put(entity.id, states);
        }

        return true;
    }

    private int pullSubsystemSleepState(int atomTag, List<StatsEvent> events) {
        final StateResidencyResult[] results;
        try {
            results = mPowerStatsInternal.getStateResidencyAsync(new int[0])
                    .get(STATS_PULL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to getStateResidencyAsync", e);
            return StatsManager.PULL_SKIP;
        }

        if (results == null) return StatsManager.PULL_SKIP;

        for (int i = 0; i < results.length; i++) {
            final StateResidencyResult result = results[i];
            for (int j = 0; j < result.stateResidencyData.length; j++) {
                final StateResidency stateResidency = result.stateResidencyData[j];
                events.add(FrameworkStatsLog.buildStatsEvent(
                        atomTag,
                        mEntityNames.get(result.id),
                        mStateNames.get(result.id).get(stateResidency.id),
                        stateResidency.totalStateEntryCount,
                        stateResidency.totalTimeInStateMs));
            }
        }

        return StatsManager.PULL_SUCCESS;
    }

    public StatsPullAtomCallbackImpl(Context context, PowerStatsInternal powerStatsInternal) {
        if (DEBUG) Slog.d(TAG, "Starting PowerStatsService statsd pullers");

        mContext = context;
        mPowerStatsInternal = powerStatsInternal;

        if (powerStatsInternal == null) {
            Slog.e(TAG, "Failed to start PowerStatsService statsd pullers");
            return;
        }

        StatsManager manager = mContext.getSystemService(StatsManager.class);

        if (initPullOnDevicePowerMeasurement()) {
            manager.setPullAtomCallback(
                    FrameworkStatsLog.ON_DEVICE_POWER_MEASUREMENT,
                    null, // use default PullAtomMetadata values
                    ConcurrentUtils.DIRECT_EXECUTOR,
                    this);
        }

        if (initSubsystemSleepState()) {
            manager.setPullAtomCallback(
                    FrameworkStatsLog.SUBSYSTEM_SLEEP_STATE,
                    null, // use default PullAtomMetadata values
                    ConcurrentUtils.DIRECT_EXECUTOR,
                    this);
        }
    }
}
