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

package com.android.server.power.stats;

import static org.mockito.Mockito.mock;

import android.annotation.NonNull;
import android.app.usage.NetworkStatsManager;
import android.net.NetworkStats;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BatteryStatsHistory;
import com.android.internal.os.Clock;
import com.android.internal.os.CpuScalingPolicies;
import com.android.internal.os.KernelCpuSpeedReader;
import com.android.internal.os.KernelCpuUidTimeReader.KernelCpuUidActiveTimeReader;
import com.android.internal.os.KernelCpuUidTimeReader.KernelCpuUidClusterTimeReader;
import com.android.internal.os.KernelCpuUidTimeReader.KernelCpuUidFreqTimeReader;
import com.android.internal.os.KernelCpuUidTimeReader.KernelCpuUidUserSysTimeReader;
import com.android.internal.os.KernelSingleUidTimeReader;
import com.android.internal.os.PowerProfile;
import com.android.internal.power.EnergyConsumerStats;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.Future;

/**
 * Mocks a BatteryStatsImpl object.
 */
public class MockBatteryStatsImpl extends BatteryStatsImpl {
    public boolean mForceOnBattery;
    // The mNetworkStats will be used for both wifi and mobile categories
    private NetworkStats mNetworkStats;
    private DummyExternalStatsSync mExternalStatsSync = new DummyExternalStatsSync();

    MockBatteryStatsImpl() {
        this(new MockClock());
    }

    MockBatteryStatsImpl(Clock clock) {
        this(clock, null);
    }

    MockBatteryStatsImpl(Clock clock, File historyDirectory) {
        this(clock, historyDirectory, new Handler(Looper.getMainLooper()));
    }

    MockBatteryStatsImpl(Clock clock, File historyDirectory, Handler handler) {
        this(clock, historyDirectory, handler, new PowerStatsUidResolver());
    }

    MockBatteryStatsImpl(Clock clock, File historyDirectory, Handler handler,
            PowerStatsUidResolver powerStatsUidResolver) {
        super(clock, historyDirectory, handler, powerStatsUidResolver,
                mock(FrameworkStatsLogger.class), mock(BatteryStatsHistory.TraceDelegate.class),
                mock(BatteryStatsHistory.EventLogger.class));
        initTimersAndCounters();
        setMaxHistoryBuffer(128 * 1024);

        setExternalStatsSyncLocked(mExternalStatsSync);
        informThatAllExternalStatsAreFlushed();

        mHandler = handler;

        mCpuUidFreqTimeReader = mock(KernelCpuUidFreqTimeReader.class);
        mKernelWakelockReader = null;
    }

    public void initMeasuredEnergyStats(String[] customBucketNames) {
        final boolean[] supportedStandardBuckets =
                new boolean[EnergyConsumerStats.NUMBER_STANDARD_POWER_BUCKETS];
        Arrays.fill(supportedStandardBuckets, true);
        synchronized (this) {
            mEnergyConsumerStatsConfig = new EnergyConsumerStats.Config(supportedStandardBuckets,
                    customBucketNames, new int[0], new String[]{""});
            mGlobalEnergyConsumerStats = new EnergyConsumerStats(mEnergyConsumerStatsConfig);
        }
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
        synchronized (this) {
            return mPendingRemovedUids;
        }
    }

    public boolean isOnBattery() {
        return mForceOnBattery ? true : super.isOnBattery();
    }

    public TimeBase getOnBatteryBackgroundTimeBase(int uid) {
        return getUidStatsLocked(uid).mOnBatteryBackgroundTimeBase;
    }

    public TimeBase getOnBatteryScreenOffBackgroundTimeBase(int uid) {
        return getUidStatsLocked(uid).mOnBatteryScreenOffBackgroundTimeBase;
    }

    public long getMobileRadioPowerStateUpdateRateLimit() {
        return MOBILE_RADIO_POWER_STATE_UPDATE_FREQ_MS;
    }

    public MockBatteryStatsImpl setBatteryStatsConfig(BatteryStatsConfig config) {
        synchronized (this) {
            mBatteryStatsConfig = config;
        }
        return this;
    }

    public MockBatteryStatsImpl setNetworkStats(NetworkStats networkStats) {
        mNetworkStats = networkStats;
        return this;
    }

    @Override
    protected NetworkStats readMobileNetworkStatsLocked(
            @NonNull NetworkStatsManager networkStatsManager) {
        return mNetworkStats;
    }

    @Override
    protected NetworkStats readWifiNetworkStatsLocked(
            @NonNull NetworkStatsManager networkStatsManager) {
        return mNetworkStats;
    }

    public MockBatteryStatsImpl setPowerProfile(PowerProfile powerProfile) {
        mPowerProfile = powerProfile;
        setTestCpuScalingPolicies();
        return this;
    }

    public MockBatteryStatsImpl setTestCpuScalingPolicies() {
        SparseArray<int[]> cpusByPolicy = new SparseArray<>();
        cpusByPolicy.put(0, new int[]{0});
        SparseArray<int[]> freqsByPolicy = new SparseArray<>();
        freqsByPolicy.put(0, new int[]{100, 200, 300});

        setCpuScalingPolicies(new CpuScalingPolicies(freqsByPolicy, freqsByPolicy));
        return this;
    }

    public MockBatteryStatsImpl setCpuScalingPolicies(CpuScalingPolicies cpuScalingPolicies) {
        mCpuScalingPolicies = cpuScalingPolicies;
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

    public MockBatteryStatsImpl setKernelWakelockReader(KernelWakelockReader reader) {
        mKernelWakelockReader = reader;
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

    @GuardedBy("this")
    public MockBatteryStatsImpl setMaxHistoryFiles(int maxHistoryFiles) {
        mConstants.MAX_HISTORY_FILES = maxHistoryFiles;
        mConstants.onChange();
        return this;
    }

    @GuardedBy("this")
    public MockBatteryStatsImpl setMaxHistoryBuffer(int maxHistoryBuffer) {
        mConstants.MAX_HISTORY_BUFFER = maxHistoryBuffer;
        mConstants.onChange();
        return this;
    }

    @GuardedBy("this")
    public MockBatteryStatsImpl setPerUidModemModel(int perUidModemModel) {
        mConstants.PER_UID_MODEM_MODEL = perUidModemModel;
        mConstants.onChange();
        return this;
    }

    public int getAndClearExternalStatsSyncFlags() {
        final int flags = mExternalStatsSync.flags;
        mExternalStatsSync.flags = 0;
        return flags;
    }

    public void setDummyExternalStatsSync(DummyExternalStatsSync externalStatsSync) {
        mExternalStatsSync = externalStatsSync;
        setExternalStatsSyncLocked(mExternalStatsSync);
    }

    @Override
    public void writeSyncLocked() {
    }

    public void setHandler(Handler handler) {
        mHandler = handler;
    }

    @Override
    protected void updateBatteryPropertiesLocked() {
    }

    public static class DummyExternalStatsSync implements ExternalStatsSync {
        public int flags = 0;

        @Override
        public Future<?> scheduleSync(String reason, int flags) {
            return null;
        }

        @Override
        public Future<?> scheduleCleanupDueToRemovedUser(int userId) {
            return null;
        }

        @Override
        public Future<?> scheduleCpuSyncDueToRemovedUid(int uid) {
            return null;
        }

        @Override
        public Future<?> scheduleSyncDueToScreenStateChange(int flag, boolean onBattery,
                boolean onBatteryScreenOff, int screenState, int[] perDisplayScreenStates) {
            flags |= flag;
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

        @Override
        public void scheduleSyncDueToProcessStateChange(int flags, long delayMillis) {
        }
    }
}
