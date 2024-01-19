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

package com.android.server.power.stats;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.annotation.XmlRes;
import android.net.NetworkStats;
import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.UidBatteryConsumer;
import android.os.UserBatteryConsumer;
import android.platform.test.ravenwood.RavenwoodRule;
import android.util.SparseArray;

import androidx.test.InstrumentationRegistry;

import com.android.internal.os.CpuScalingPolicies;
import com.android.internal.os.PowerProfile;
import com.android.internal.power.EnergyConsumerStats;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.util.Arrays;

public class BatteryUsageStatsRule implements TestRule {
    public static final BatteryUsageStatsQuery POWER_PROFILE_MODEL_ONLY =
            new BatteryUsageStatsQuery.Builder()
                    .powerProfileModeledOnly()
                    .includePowerModels()
                    .build();

    private final PowerProfile mPowerProfile;
    private final MockClock mMockClock = new MockClock();
    private final File mHistoryDir;
    private MockBatteryStatsImpl mBatteryStats;
    private Handler mHandler;

    private BatteryUsageStats mBatteryUsageStats;
    private boolean mScreenOn;
    private boolean mDefaultCpuScalingPolicy = true;
    private SparseArray<int[]> mCpusByPolicy = new SparseArray<>();
    private SparseArray<int[]> mFreqsByPolicy = new SparseArray<>();

    private int mDisplayCount = -1;
    private int mPerUidModemModel = -1;
    private NetworkStats mNetworkStats;

    public BatteryUsageStatsRule() {
        this(0, null);
    }

    public BatteryUsageStatsRule(long currentTime) {
        this(currentTime, null);
    }

    public BatteryUsageStatsRule(long currentTime, File historyDir) {
        mHandler = mock(Handler.class);
        mPowerProfile = spy(new PowerProfile());
        mMockClock.currentTime = currentTime;
        mHistoryDir = historyDir;

        if (!RavenwoodRule.isUnderRavenwood()) {
            lateInitBatteryStats();
        }

        mCpusByPolicy.put(0, new int[]{0, 1, 2, 3});
        mCpusByPolicy.put(4, new int[]{4, 5, 6, 7});
        mFreqsByPolicy.put(0, new int[]{300000, 1000000, 2000000});
        mFreqsByPolicy.put(4, new int[]{300000, 1000000, 2500000, 3000000});
    }

    private void lateInitBatteryStats() {
        if (mBatteryStats != null) return;

        mBatteryStats = new MockBatteryStatsImpl(mMockClock, mHistoryDir, mHandler);
        mBatteryStats.setPowerProfile(mPowerProfile);
        mBatteryStats.setCpuScalingPolicies(new CpuScalingPolicies(mCpusByPolicy, mFreqsByPolicy));

        mBatteryStats.onSystemReady();

        if (mDisplayCount != -1) {
            mBatteryStats.setDisplayCountLocked(mDisplayCount);
        }
        if (mPerUidModemModel != -1) {
            synchronized (mBatteryStats) {
                mBatteryStats.setPerUidModemModel(mPerUidModemModel);
            }
        }
        if (mNetworkStats != null) {
            mBatteryStats.setNetworkStats(mNetworkStats);
        }
    }

    public MockClock getMockClock() {
        return mMockClock;
    }

    public Handler getHandler() {
        return mHandler;
    }

    public BatteryUsageStatsRule setTestPowerProfile(@XmlRes int xmlId) {
        mPowerProfile.forceInitForTesting(InstrumentationRegistry.getContext(), xmlId);
        return this;
    }

    public BatteryUsageStatsRule setCpuScalingPolicy(int policy, int[] relatedCpus,
            int[] frequencies) {
        if (mDefaultCpuScalingPolicy) {
            mCpusByPolicy.clear();
            mFreqsByPolicy.clear();
            mDefaultCpuScalingPolicy = false;
        }
        mCpusByPolicy.put(policy, relatedCpus);
        mFreqsByPolicy.put(policy, frequencies);
        if (mBatteryStats != null) {
            mBatteryStats.setCpuScalingPolicies(
                    new CpuScalingPolicies(mCpusByPolicy, mFreqsByPolicy));
        }
        return this;
    }

    public BatteryUsageStatsRule setAveragePower(String key, double value) {
        when(mPowerProfile.getAveragePower(key)).thenReturn(value);
        when(mPowerProfile.getAveragePowerOrDefault(eq(key), anyDouble())).thenReturn(value);
        return this;
    }

    public BatteryUsageStatsRule setAveragePowerUnspecified(String key) {
        when(mPowerProfile.getAveragePower(key)).thenReturn(0.0);
        when(mPowerProfile.getAveragePowerOrDefault(eq(key), anyDouble()))
                .thenAnswer((Answer<Double>) invocation -> (Double) invocation.getArguments()[1]);
        return this;
    }

    public BatteryUsageStatsRule setAveragePower(String key, double[] values) {
        when(mPowerProfile.getNumElements(key)).thenReturn(values.length);
        for (int i = 0; i < values.length; i++) {
            when(mPowerProfile.getAveragePower(key, i)).thenReturn(values[i]);
        }
        return this;
    }

    public BatteryUsageStatsRule setAveragePowerForCpuScalingPolicy(int policy, double value) {
        when(mPowerProfile.getAveragePowerForCpuScalingPolicy(policy)).thenReturn(value);
        return this;
    }

    public BatteryUsageStatsRule setAveragePowerForCpuScalingStep(int policy, int step,
            double value) {
        when(mPowerProfile.getAveragePowerForCpuScalingStep(policy, step)).thenReturn(value);
        return this;
    }

    /**
     * Mocks the CPU bracket count
     */
    public BatteryUsageStatsRule setCpuPowerBracketCount(int count) {
        when(mPowerProfile.getCpuPowerBracketCount()).thenReturn(count);
        return this;
    }

    /**
     * Mocks the CPU bracket for the given CPU scaling policy and step
     */
    public BatteryUsageStatsRule setCpuPowerBracket(int policy, int step, int bracket) {
        when(mPowerProfile.getCpuPowerBracketForScalingStep(policy, step)).thenReturn(bracket);
        return this;
    }

    public BatteryUsageStatsRule setAveragePowerForOrdinal(String group, int ordinal,
            double value) {
        when(mPowerProfile.getAveragePowerForOrdinal(group, ordinal)).thenReturn(value);
        when(mPowerProfile.getAveragePowerForOrdinal(eq(group), eq(ordinal),
                anyDouble())).thenReturn(value);
        return this;
    }

    public BatteryUsageStatsRule setNumDisplays(int value) {
        when(mPowerProfile.getNumDisplays()).thenReturn(value);
        mDisplayCount = value;
        if (mBatteryStats != null) {
            mBatteryStats.setDisplayCountLocked(mDisplayCount);
        }
        return this;
    }

    public BatteryUsageStatsRule setPerUidModemModel(int perUidModemModel) {
        mPerUidModemModel = perUidModemModel;
        if (mBatteryStats != null) {
            synchronized (mBatteryStats) {
                mBatteryStats.setPerUidModemModel(mPerUidModemModel);
            }
        }
        return this;
    }

    /** Call only after setting the power profile information. */
    public BatteryUsageStatsRule initMeasuredEnergyStatsLocked() {
        return initMeasuredEnergyStatsLocked(new String[0]);
    }

    /** Call only after setting the power profile information. */
    public BatteryUsageStatsRule initMeasuredEnergyStatsLocked(
            String[] customPowerComponentNames) {
        final boolean[] supportedStandardBuckets =
                new boolean[EnergyConsumerStats.NUMBER_STANDARD_POWER_BUCKETS];
        Arrays.fill(supportedStandardBuckets, true);
        synchronized (mBatteryStats) {
            mBatteryStats.initEnergyConsumerStatsLocked(supportedStandardBuckets,
                    customPowerComponentNames);
            mBatteryStats.informThatAllExternalStatsAreFlushed();
        }
        return this;
    }

    public BatteryUsageStatsRule startWithScreenOn(boolean screenOn) {
        mScreenOn = screenOn;
        return this;
    }

    public void setNetworkStats(NetworkStats networkStats) {
        mNetworkStats = networkStats;
        if (mBatteryStats != null) {
            mBatteryStats.setNetworkStats(mNetworkStats);
        }
    }

    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                before();
                base.evaluate();
            }
        };
    }

    private void before() {
        lateInitBatteryStats();
        HandlerThread bgThread = new HandlerThread("bg thread");
        bgThread.start();
        mHandler = new Handler(bgThread.getLooper());
        mBatteryStats.setHandler(mHandler);
        mBatteryStats.setOnBatteryInternal(true);
        mBatteryStats.getOnBatteryTimeBase().setRunning(true, 0, 0);
        mBatteryStats.getOnBatteryScreenOffTimeBase().setRunning(!mScreenOn, 0, 0);
    }

    public PowerProfile getPowerProfile() {
        return mPowerProfile;
    }

    public CpuScalingPolicies getCpuScalingPolicies() {
        synchronized (mBatteryStats) {
            return mBatteryStats.getCpuScalingPolicies();
        }
    }

    public MockBatteryStatsImpl getBatteryStats() {
        return mBatteryStats;
    }

    public BatteryStatsImpl.Uid getUidStats(int uid) {
        return mBatteryStats.getUidStatsLocked(uid);
    }

    public void setTime(long realtimeMs, long uptimeMs) {
        mMockClock.currentTime = realtimeMs;
        mMockClock.realtime = realtimeMs;
        mMockClock.uptime = uptimeMs;
    }

    public void setCurrentTime(long currentTimeMs) {
        mMockClock.currentTime = currentTimeMs;
    }

    BatteryUsageStats apply(PowerCalculator... calculators) {
        return apply(new BatteryUsageStatsQuery.Builder().includePowerModels().build(),
                calculators);
    }

    BatteryUsageStats apply(BatteryUsageStatsQuery query, PowerCalculator... calculators) {
        final String[] customPowerComponentNames = mBatteryStats.getCustomEnergyConsumerNames();
        final boolean includePowerModels = (query.getFlags()
                & BatteryUsageStatsQuery.FLAG_BATTERY_USAGE_STATS_INCLUDE_POWER_MODELS) != 0;
        final boolean includeProcessStateData = (query.getFlags()
                & BatteryUsageStatsQuery.FLAG_BATTERY_USAGE_STATS_INCLUDE_PROCESS_STATE_DATA) != 0;
        final double minConsumedPowerThreshold = query.getMinConsumedPowerThreshold();
        BatteryUsageStats.Builder builder = new BatteryUsageStats.Builder(
                customPowerComponentNames, includePowerModels, includeProcessStateData,
                minConsumedPowerThreshold);
        SparseArray<? extends BatteryStats.Uid> uidStats = mBatteryStats.getUidStats();
        for (int i = 0; i < uidStats.size(); i++) {
            builder.getOrCreateUidBatteryConsumerBuilder(uidStats.valueAt(i));
        }

        for (PowerCalculator calculator : calculators) {
            calculator.calculate(builder, mBatteryStats, mMockClock.realtime * 1000,
                    mMockClock.uptime * 1000, query);
        }

        mBatteryUsageStats = builder.build();
        return mBatteryUsageStats;
    }

    public BatteryConsumer getDeviceBatteryConsumer() {
        return mBatteryUsageStats.getAggregateBatteryConsumer(
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE);
    }

    public BatteryConsumer getAppsBatteryConsumer() {
        return mBatteryUsageStats.getAggregateBatteryConsumer(
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS);
    }

    public UidBatteryConsumer getUidBatteryConsumer(int uid) {
        for (UidBatteryConsumer ubc : mBatteryUsageStats.getUidBatteryConsumers()) {
            if (ubc.getUid() == uid) {
                return ubc;
            }
        }
        return null;
    }

    public UserBatteryConsumer getUserBatteryConsumer(int userId) {
        for (UserBatteryConsumer ubc : mBatteryUsageStats.getUserBatteryConsumers()) {
            if (ubc.getUserId() == userId) {
                return ubc;
            }
        }
        return null;
    }
}
