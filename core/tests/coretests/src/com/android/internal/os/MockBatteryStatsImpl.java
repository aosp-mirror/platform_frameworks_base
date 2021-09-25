/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.internal.os;

import android.net.NetworkStats;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseIntArray;

import com.android.internal.os.KernelCpuUidTimeReader.KernelCpuUidActiveTimeReader;
import com.android.internal.os.KernelCpuUidTimeReader.KernelCpuUidClusterTimeReader;
import com.android.internal.os.KernelCpuUidTimeReader.KernelCpuUidFreqTimeReader;
import com.android.internal.os.KernelCpuUidTimeReader.KernelCpuUidUserSysTimeReader;
import com.android.internal.power.MeasuredEnergyStats;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.Future;

/**
 * Mocks a BatteryStatsImpl object.
 */
public class MockBatteryStatsImpl extends BatteryStatsImpl {
    public BatteryStatsImpl.Clocks clocks;
    public boolean mForceOnBattery;
    private NetworkStats mNetworkStats;

    MockBatteryStatsImpl(Clocks clocks) {
        super(clocks);
        this.clocks = mClocks;
        initTimersAndCounters();

        setExternalStatsSyncLocked(new DummyExternalStatsSync());
        informThatAllExternalStatsAreFlushed();

        // A no-op handler.
        mHandler = new Handler(Looper.getMainLooper()) {
        };
    }

    MockBatteryStatsImpl() {
        this(new MockClocks());
    }

    public void initMeasuredEnergyStats(String[] customBucketNames) {
        final boolean[] supportedStandardBuckets =
                new boolean[MeasuredEnergyStats.NUMBER_STANDARD_POWER_BUCKETS];
        Arrays.fill(supportedStandardBuckets, true);
        mGlobalMeasuredEnergyStats =
                new MeasuredEnergyStats(supportedStandardBuckets, customBucketNames);
    }

    public TimeBase getOnBatteryTimeBase() {
        return mOnBatteryTimeBase;
    }

    public TimeBase getOnBatteryScreenOffTimeBase() {
        return mOnBatteryScreenOffTimeBase;
    }

    public int getScreenState() {
        return mScreenState;
    }

    public Queue<UidToRemove> getPendingRemovedUids() {
        return mPendingRemovedUids;
    }

    public boolean isOnBattery() {
        return mForceOnBattery ? true : super.isOnBattery();
    }

    public void forceRecordAllHistory() {
        mHaveBatteryLevel = true;
        mRecordingHistory = true;
        mRecordAllHistory = true;
    }

    public TimeBase getOnBatteryBackgroundTimeBase(int uid) {
        return getUidStatsLocked(uid).mOnBatteryBackgroundTimeBase;
    }

    public TimeBase getOnBatteryScreenOffBackgroundTimeBase(int uid) {
        return getUidStatsLocked(uid).mOnBatteryScreenOffBackgroundTimeBase;
    }

    public MockBatteryStatsImpl setNetworkStats(NetworkStats networkStats) {
        mNetworkStats = networkStats;
        return this;
    }

    @Override
    protected NetworkStats readNetworkStatsLocked(String[] ifaces) {
        return mNetworkStats;
    }

    public MockBatteryStatsImpl setPowerProfile(PowerProfile powerProfile) {
        mPowerProfile = powerProfile;
        return this;
    }

    public MockBatteryStatsImpl setKernelCpuUidFreqTimeReader(KernelCpuUidFreqTimeReader reader) {
        mCpuUidFreqTimeReader = reader;
        return this;
    }

    public MockBatteryStatsImpl setKernelCpuUidActiveTimeReader(
            KernelCpuUidActiveTimeReader reader) {
        mCpuUidActiveTimeReader = reader;
        return this;
    }

    public MockBatteryStatsImpl setKernelCpuUidClusterTimeReader(
            KernelCpuUidClusterTimeReader reader) {
        mCpuUidClusterTimeReader = reader;
        return this;
    }

    public MockBatteryStatsImpl setKernelCpuUidUserSysTimeReader(
            KernelCpuUidUserSysTimeReader reader) {
        mCpuUidUserSysTimeReader = reader;
        return this;
    }

    public MockBatteryStatsImpl setKernelSingleUidTimeReader(KernelSingleUidTimeReader reader) {
        mKernelSingleUidTimeReader = reader;
        return this;
    }

    public MockBatteryStatsImpl setKernelCpuSpeedReaders(KernelCpuSpeedReader[] readers) {
        mKernelCpuSpeedReaders = readers;
        return this;
    }

    public MockBatteryStatsImpl setSystemServerCpuThreadReader(
            SystemServerCpuThreadReader systemServerCpuThreadReader) {
        mSystemServerCpuThreadReader = systemServerCpuThreadReader;
        return this;
    }

    public MockBatteryStatsImpl setUserInfoProvider(UserInfoProvider provider) {
        mUserInfoProvider = provider;
        return this;
    }

    public MockBatteryStatsImpl setPartialTimers(ArrayList<StopwatchTimer> partialTimers) {
        mPartialTimers = partialTimers;
        return this;
    }

    public MockBatteryStatsImpl setLastPartialTimers(ArrayList<StopwatchTimer> lastPartialTimers) {
        mLastPartialTimers = lastPartialTimers;
        return this;
    }

    public MockBatteryStatsImpl setOnBatteryInternal(boolean onBatteryInternal) {
        mOnBatteryInternal = onBatteryInternal;
        return this;
    }

    public MockBatteryStatsImpl setTrackingCpuByProcStateEnabled(boolean enabled) {
        mConstants.TRACK_CPU_TIMES_BY_PROC_STATE = enabled;
        return this;
    }

    public SparseIntArray getPendingUids() {
        return mPendingUids;
    }

    private class DummyExternalStatsSync implements ExternalStatsSync {
        @Override
        public Future<?> scheduleSync(String reason, int flags) {
            return null;
        }

        @Override
        public Future<?> scheduleCpuSyncDueToRemovedUid(int uid) {
            return null;
        }

        @Override
        public Future<?> scheduleCpuSyncDueToSettingChange() {
            return null;
        }

        @Override
        public Future<?> scheduleReadProcStateCpuTimes(boolean onBattery,
                boolean onBatteryScreenOff, long delayMillis) {
            return null;
        }

        @Override
        public Future<?> scheduleCopyFromAllUidsCpuTimes(
                boolean onBattery, boolean onBatteryScreenOff) {
            return null;
        }

        @Override
        public Future<?> scheduleSyncDueToScreenStateChange(
                int flag, boolean onBattery, boolean onBatteryScreenOff, int screenState) {
            return null;
        }

        @Override
        public Future<?> scheduleCpuSyncDueToWakelockChange(long delayMillis) {
            return null;
        }

        @Override
        public void cancelCpuSyncDueToWakelockChange() {
        }

        @Override
        public Future<?> scheduleSyncDueToBatteryLevelChange(long delayMillis) {
            return null;
        }
    }
}

