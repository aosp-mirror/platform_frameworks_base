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

package com.android.server.power.stats;

import android.content.pm.PackageManager;
import android.hardware.power.stats.EnergyConsumerType;
import android.net.NetworkStats;
import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.Handler;
import android.os.OutcomeReceiver;
import android.os.PersistableBundle;
import android.telephony.AccessNetworkConstants;
import android.telephony.ModemActivityInfo;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.Clock;
import com.android.internal.os.PowerStats;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public class MobileRadioPowerStatsCollector extends PowerStatsCollector {
    private static final String TAG = "MobileRadioPowerStatsCollector";

    /**
     * The soonest the Mobile Radio stats can be updated due to a mobile radio power state change
     * after it was last updated.
     */
    @VisibleForTesting
    protected static final long MOBILE_RADIO_POWER_STATE_UPDATE_FREQ_MS = 1000 * 60 * 10;

    private static final long MODEM_ACTIVITY_REQUEST_TIMEOUT = 20000;

    private static final long ENERGY_UNSPECIFIED = -1;

    @VisibleForTesting
    @AccessNetworkConstants.RadioAccessNetworkType
    static final int[] NETWORK_TYPES = {
            AccessNetworkConstants.AccessNetworkType.UNKNOWN,
            AccessNetworkConstants.AccessNetworkType.GERAN,
            AccessNetworkConstants.AccessNetworkType.UTRAN,
            AccessNetworkConstants.AccessNetworkType.EUTRAN,
            AccessNetworkConstants.AccessNetworkType.CDMA2000,
            AccessNetworkConstants.AccessNetworkType.IWLAN,
            AccessNetworkConstants.AccessNetworkType.NGRAN
    };

    interface Injector {
        Handler getHandler();
        Clock getClock();
        PowerStatsUidResolver getUidResolver();
        long getPowerStatsCollectionThrottlePeriod(String powerComponentName);
        PackageManager getPackageManager();
        ConsumedEnergyRetriever getConsumedEnergyRetriever();
        IntSupplier getVoltageSupplier();
        Supplier<NetworkStats> getMobileNetworkStatsSupplier();
        TelephonyManager getTelephonyManager();
        LongSupplier getCallDurationSupplier();
        LongSupplier getPhoneSignalScanDurationSupplier();
    }

    private final Injector mInjector;

    private MobileRadioPowerStatsLayout mLayout;
    private boolean mIsInitialized;

    private PowerStats mPowerStats;
    private long[] mDeviceStats;
    private volatile TelephonyManager mTelephonyManager;
    private LongSupplier mCallDurationSupplier;
    private LongSupplier mScanDurationSupplier;
    private volatile Supplier<NetworkStats> mNetworkStatsSupplier;
    private ConsumedEnergyRetriever mConsumedEnergyRetriever;
    private IntSupplier mVoltageSupplier;
    private int[] mEnergyConsumerIds = new int[0];
    private long mLastUpdateTimestampMillis;
    private ModemActivityInfo mLastModemActivityInfo;
    private NetworkStats mLastNetworkStats;
    private long[] mLastConsumedEnergyUws;
    private int mLastVoltageMv;
    private long mLastCallDuration;
    private long mLastScanDuration;

    MobileRadioPowerStatsCollector(Injector injector) {
        super(injector.getHandler(), injector.getPowerStatsCollectionThrottlePeriod(
                        BatteryConsumer.powerComponentIdToString(
                                BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO)),
                injector.getUidResolver(),
                injector.getClock());
        mInjector = injector;
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (enabled) {
            PackageManager packageManager = mInjector.getPackageManager();
            super.setEnabled(packageManager != null
                    && packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY));
        } else {
            super.setEnabled(false);
        }
    }

    private boolean ensureInitialized() {
        if (mIsInitialized) {
            return true;
        }

        if (!isEnabled()) {
            return false;
        }

        mConsumedEnergyRetriever = mInjector.getConsumedEnergyRetriever();
        mVoltageSupplier = mInjector.getVoltageSupplier();

        mTelephonyManager = mInjector.getTelephonyManager();
        mNetworkStatsSupplier = mInjector.getMobileNetworkStatsSupplier();
        mCallDurationSupplier = mInjector.getCallDurationSupplier();
        mScanDurationSupplier = mInjector.getPhoneSignalScanDurationSupplier();

        mEnergyConsumerIds = mConsumedEnergyRetriever.getEnergyConsumerIds(
                EnergyConsumerType.MOBILE_RADIO);
        mLastConsumedEnergyUws = new long[mEnergyConsumerIds.length];
        Arrays.fill(mLastConsumedEnergyUws, ENERGY_UNSPECIFIED);

        mLayout = new MobileRadioPowerStatsLayout();
        mLayout.addDeviceMobileActivity();
        mLayout.addDeviceSectionEnergyConsumers(mEnergyConsumerIds.length);
        mLayout.addStateStats();
        mLayout.addUidNetworkStats();
        mLayout.addDeviceSectionUsageDuration();
        mLayout.addDeviceSectionPowerEstimate();
        mLayout.addUidSectionPowerEstimate();

        SparseArray<String> stateLabels = new SparseArray<>();
        for (int rat = 0; rat < BatteryStats.RADIO_ACCESS_TECHNOLOGY_COUNT; rat++) {
            final int freqCount = rat == BatteryStats.RADIO_ACCESS_TECHNOLOGY_NR
                    ? ServiceState.FREQUENCY_RANGE_COUNT : 1;
            for (int freq = 0; freq < freqCount; freq++) {
                int stateKey = makeStateKey(rat, freq);
                StringBuilder sb = new StringBuilder();
                if (rat != BatteryStats.RADIO_ACCESS_TECHNOLOGY_OTHER) {
                    sb.append(BatteryStats.RADIO_ACCESS_TECHNOLOGY_NAMES[rat]);
                }
                if (freq != ServiceState.FREQUENCY_RANGE_UNKNOWN) {
                    if (!sb.isEmpty()) {
                        sb.append(" ");
                    }
                    sb.append(ServiceState.frequencyRangeToString(freq));
                }
                stateLabels.put(stateKey, !sb.isEmpty() ? sb.toString() : "other");
            }
        }

        PersistableBundle extras = new PersistableBundle();
        mLayout.toExtras(extras);
        PowerStats.Descriptor powerStatsDescriptor = new PowerStats.Descriptor(
                BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO, mLayout.getDeviceStatsArrayLength(),
                stateLabels, mLayout.getStateStatsArrayLength(), mLayout.getUidStatsArrayLength(),
                extras);
        mPowerStats = new PowerStats(powerStatsDescriptor);
        mDeviceStats = mPowerStats.stats;

        mIsInitialized = true;
        return true;
    }

    @Override
    protected PowerStats collectStats() {
        if (!ensureInitialized()) {
            return null;
        }

        collectModemActivityInfo();

        collectNetworkStats();

        if (mEnergyConsumerIds.length != 0) {
            collectEnergyConsumers();
        }

        if (mPowerStats.durationMs == 0) {
            setTimestamp(mClock.elapsedRealtime());
        }

        return mPowerStats;
    }

    private void collectModemActivityInfo() {
        if (mTelephonyManager == null) {
            return;
        }

        CompletableFuture<ModemActivityInfo> immediateFuture = new CompletableFuture<>();
        mTelephonyManager.requestModemActivityInfo(Runnable::run,
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(ModemActivityInfo result) {
                        immediateFuture.complete(result);
                    }

                    @Override
                    public void onError(TelephonyManager.ModemActivityInfoException e) {
                        Slog.w(TAG, "error reading modem stats:" + e);
                        immediateFuture.complete(null);
                    }
                });

        ModemActivityInfo activityInfo;
        try {
            activityInfo = immediateFuture.get(MODEM_ACTIVITY_REQUEST_TIMEOUT,
                    TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Slog.e(TAG, "Cannot acquire ModemActivityInfo");
            activityInfo = null;
        }

        ModemActivityInfo deltaInfo = mLastModemActivityInfo == null
                ? (activityInfo == null ? null : activityInfo.getDelta(activityInfo))
                : mLastModemActivityInfo.getDelta(activityInfo);

        mLastModemActivityInfo = activityInfo;

        if (deltaInfo == null) {
            return;
        }

        setTimestamp(deltaInfo.getTimestampMillis());
        mLayout.setDeviceSleepTime(mDeviceStats, deltaInfo.getSleepTimeMillis());
        mLayout.setDeviceIdleTime(mDeviceStats, deltaInfo.getIdleTimeMillis());

        long callDuration = mCallDurationSupplier.getAsLong();
        if (callDuration >= mLastCallDuration) {
            mLayout.setDeviceCallTime(mDeviceStats, callDuration - mLastCallDuration);
        }
        mLastCallDuration = callDuration;

        long scanDuration = mScanDurationSupplier.getAsLong();
        if (scanDuration >= mLastScanDuration) {
            mLayout.setDeviceScanTime(mDeviceStats, scanDuration - mLastScanDuration);
        }
        mLastScanDuration = scanDuration;

        SparseArray<long[]> stateStats = mPowerStats.stateStats;
        stateStats.clear();

        if (deltaInfo.getSpecificInfoLength() == 0) {
            mLayout.addRxTxTimesForRat(stateStats,
                    AccessNetworkConstants.AccessNetworkType.UNKNOWN,
                    ServiceState.FREQUENCY_RANGE_UNKNOWN,
                    deltaInfo.getReceiveTimeMillis(),
                    deltaInfo.getTransmitTimeMillis());
        } else {
            for (int rat = 0; rat < NETWORK_TYPES.length; rat++) {
                if (rat == AccessNetworkConstants.AccessNetworkType.NGRAN) {
                    for (int freq = 0; freq < ServiceState.FREQUENCY_RANGE_COUNT; freq++) {
                        mLayout.addRxTxTimesForRat(stateStats, rat, freq,
                                deltaInfo.getReceiveTimeMillis(rat, freq),
                                deltaInfo.getTransmitTimeMillis(rat, freq));
                    }
                } else {
                    mLayout.addRxTxTimesForRat(stateStats, rat,
                            ServiceState.FREQUENCY_RANGE_UNKNOWN,
                            deltaInfo.getReceiveTimeMillis(rat),
                            deltaInfo.getTransmitTimeMillis(rat));
                }
            }
        }
    }

    private void collectNetworkStats() {
        mPowerStats.uidStats.clear();

        NetworkStats networkStats = mNetworkStatsSupplier.get();
        if (networkStats == null) {
            return;
        }

        List<BatteryStatsImpl.NetworkStatsDelta> delta =
                BatteryStatsImpl.computeDelta(networkStats, mLastNetworkStats);
        mLastNetworkStats = networkStats;
        for (int i = delta.size() - 1; i >= 0; i--) {
            BatteryStatsImpl.NetworkStatsDelta uidDelta = delta.get(i);
            long rxBytes = uidDelta.getRxBytes();
            long txBytes = uidDelta.getTxBytes();
            long rxPackets = uidDelta.getRxPackets();
            long txPackets = uidDelta.getTxPackets();
            if (rxBytes == 0 && txBytes == 0 && rxPackets == 0 && txPackets == 0) {
                continue;
            }

            int uid = mUidResolver.mapUid(uidDelta.getUid());
            long[] stats = mPowerStats.uidStats.get(uid);
            if (stats == null) {
                stats = new long[mLayout.getUidStatsArrayLength()];
                mPowerStats.uidStats.put(uid, stats);
                mLayout.setUidRxBytes(stats, rxBytes);
                mLayout.setUidTxBytes(stats, txBytes);
                mLayout.setUidRxPackets(stats, rxPackets);
                mLayout.setUidTxPackets(stats, txPackets);
            } else {
                mLayout.setUidRxBytes(stats, mLayout.getUidRxBytes(stats) + rxBytes);
                mLayout.setUidTxBytes(stats, mLayout.getUidTxBytes(stats) + txBytes);
                mLayout.setUidRxPackets(stats, mLayout.getUidRxPackets(stats) + rxPackets);
                mLayout.setUidTxPackets(stats, mLayout.getUidTxPackets(stats) + txPackets);
            }
        }
    }

    private void collectEnergyConsumers() {
        int voltageMv = mVoltageSupplier.getAsInt();
        if (voltageMv <= 0) {
            Slog.wtf(TAG, "Unexpected battery voltage (" + voltageMv
                    + " mV) when querying energy consumers");
            return;
        }

        int averageVoltage = mLastVoltageMv != 0 ? (mLastVoltageMv + voltageMv) / 2 : voltageMv;
        mLastVoltageMv = voltageMv;

        long[] energyUws = mConsumedEnergyRetriever.getConsumedEnergyUws(mEnergyConsumerIds);
        if (energyUws == null) {
            return;
        }

        for (int i = energyUws.length - 1; i >= 0; i--) {
            long energyDelta = mLastConsumedEnergyUws[i] != ENERGY_UNSPECIFIED
                    ? energyUws[i] - mLastConsumedEnergyUws[i] : 0;
            if (energyDelta < 0) {
                // Likely, restart of powerstats HAL
                energyDelta = 0;
            }
            mLayout.setConsumedEnergy(mPowerStats.stats, i, uJtoUc(energyDelta, averageVoltage));
            mLastConsumedEnergyUws[i] = energyUws[i];
        }
    }

    static int makeStateKey(int rat, int freqRange) {
        if (rat == BatteryStats.RADIO_ACCESS_TECHNOLOGY_NR) {
            return rat | (freqRange << 8);
        } else {
            return rat;
        }
    }

    private void setTimestamp(long timestamp) {
        mPowerStats.durationMs = Math.max(timestamp - mLastUpdateTimestampMillis, 0);
        mLastUpdateTimestampMillis = timestamp;
    }

    @BatteryStats.RadioAccessTechnology
    static int mapRadioAccessNetworkTypeToRadioAccessTechnology(
            @AccessNetworkConstants.RadioAccessNetworkType int networkType) {
        switch (networkType) {
            case AccessNetworkConstants.AccessNetworkType.NGRAN:
                return BatteryStats.RADIO_ACCESS_TECHNOLOGY_NR;
            case AccessNetworkConstants.AccessNetworkType.EUTRAN:
                return BatteryStats.RADIO_ACCESS_TECHNOLOGY_LTE;
            case AccessNetworkConstants.AccessNetworkType.UNKNOWN: //fallthrough
            case AccessNetworkConstants.AccessNetworkType.GERAN: //fallthrough
            case AccessNetworkConstants.AccessNetworkType.UTRAN: //fallthrough
            case AccessNetworkConstants.AccessNetworkType.CDMA2000: //fallthrough
            case AccessNetworkConstants.AccessNetworkType.IWLAN:
                return BatteryStats.RADIO_ACCESS_TECHNOLOGY_OTHER;
            default:
                Slog.w(TAG,
                        "Unhandled RadioAccessNetworkType (" + networkType + "), mapping to OTHER");
                return BatteryStats.RADIO_ACCESS_TECHNOLOGY_OTHER;
        }
    }
}
