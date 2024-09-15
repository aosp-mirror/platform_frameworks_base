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

import android.bluetooth.BluetoothActivityEnergyInfo;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.UidTraffic;
import android.content.pm.PackageManager;
import android.hardware.power.stats.EnergyConsumerType;
import android.os.BatteryConsumer;
import android.os.Handler;
import android.os.PersistableBundle;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.os.Clock;
import com.android.internal.os.PowerStats;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

public class BluetoothPowerStatsCollector extends PowerStatsCollector {
    private static final String TAG = "BluetoothPowerStatsCollector";

    private static final long BLUETOOTH_ACTIVITY_REQUEST_TIMEOUT = 20000;

    private static final long ENERGY_UNSPECIFIED = -1;

    interface BluetoothStatsRetriever {
        interface Callback {
            void onBluetoothScanTime(int uid, long scanTimeMs);
        }

        void retrieveBluetoothScanTimes(Callback callback);

        boolean requestControllerActivityEnergyInfo(Executor executor,
                BluetoothAdapter.OnBluetoothActivityEnergyInfoCallback callback);
    }

    interface Injector {
        Handler getHandler();
        Clock getClock();
        PowerStatsUidResolver getUidResolver();
        long getPowerStatsCollectionThrottlePeriod(String powerComponentName);
        PackageManager getPackageManager();
        ConsumedEnergyRetriever getConsumedEnergyRetriever();
        IntSupplier getVoltageSupplier();
        BluetoothStatsRetriever getBluetoothStatsRetriever();
    }

    private final Injector mInjector;

    private BluetoothPowerStatsLayout mLayout;
    private boolean mIsInitialized;
    private PowerStats mPowerStats;
    private long[] mDeviceStats;
    private BluetoothStatsRetriever mBluetoothStatsRetriever;
    private ConsumedEnergyRetriever mConsumedEnergyRetriever;
    private IntSupplier mVoltageSupplier;
    private int[] mEnergyConsumerIds = new int[0];
    private long[] mLastConsumedEnergyUws;
    private int mLastVoltageMv;

    private long mLastRxTime;
    private long mLastTxTime;
    private long mLastIdleTime;

    private static class UidStats {
        public long rxCount;
        public long lastRxCount;
        public long txCount;
        public long lastTxCount;
        public long scanTime;
        public long lastScanTime;
    }

    private final SparseArray<UidStats> mUidStats = new SparseArray<>();

    BluetoothPowerStatsCollector(Injector injector) {
        super(injector.getHandler(),  injector.getPowerStatsCollectionThrottlePeriod(
                        BatteryConsumer.powerComponentIdToString(
                                BatteryConsumer.POWER_COMPONENT_BLUETOOTH)),
                injector.getUidResolver(),
                injector.getClock());
        mInjector = injector;
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (enabled) {
            PackageManager packageManager = mInjector.getPackageManager();
            super.setEnabled(packageManager != null
                    && packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH));
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
        mBluetoothStatsRetriever = mInjector.getBluetoothStatsRetriever();
        mEnergyConsumerIds =
                mConsumedEnergyRetriever.getEnergyConsumerIds(EnergyConsumerType.BLUETOOTH);
        mLastConsumedEnergyUws = new long[mEnergyConsumerIds.length];
        Arrays.fill(mLastConsumedEnergyUws, ENERGY_UNSPECIFIED);

        mLayout = new BluetoothPowerStatsLayout();
        mLayout.addDeviceBluetoothControllerActivity();
        mLayout.addDeviceSectionEnergyConsumers(mEnergyConsumerIds.length);
        mLayout.addDeviceSectionUsageDuration();
        mLayout.addDeviceSectionPowerEstimate();
        mLayout.addUidTrafficStats();
        mLayout.addUidSectionPowerEstimate();

        PersistableBundle extras = new PersistableBundle();
        mLayout.toExtras(extras);
        PowerStats.Descriptor powerStatsDescriptor = new PowerStats.Descriptor(
                BatteryConsumer.POWER_COMPONENT_BLUETOOTH, mLayout.getDeviceStatsArrayLength(),
                null, 0, mLayout.getUidStatsArrayLength(),
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

        mPowerStats.uidStats.clear();

        collectBluetoothActivityInfo();
        collectBluetoothScanStats();

        if (mEnergyConsumerIds.length != 0) {
            collectEnergyConsumers();
        }

        return mPowerStats;
    }

    private void collectBluetoothActivityInfo() {
        CompletableFuture<BluetoothActivityEnergyInfo> immediateFuture = new CompletableFuture<>();
        boolean success = mBluetoothStatsRetriever.requestControllerActivityEnergyInfo(
                Runnable::run,
                new BluetoothAdapter.OnBluetoothActivityEnergyInfoCallback() {
                    @Override
                    public void onBluetoothActivityEnergyInfoAvailable(
                            BluetoothActivityEnergyInfo info) {
                        immediateFuture.complete(info);
                    }

                    @Override
                    public void onBluetoothActivityEnergyInfoError(int error) {
                        immediateFuture.completeExceptionally(
                                new RuntimeException("error: " + error));
                    }
                });

        if (!success) {
            return;
        }

        BluetoothActivityEnergyInfo activityInfo;
        try {
            activityInfo = immediateFuture.get(BLUETOOTH_ACTIVITY_REQUEST_TIMEOUT,
                    TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Slog.e(TAG, "Cannot acquire BluetoothActivityEnergyInfo", e);
            activityInfo = null;
        }

        if (activityInfo == null) {
            return;
        }

        long rxTime = activityInfo.getControllerRxTimeMillis();
        long rxTimeDelta = Math.max(0, rxTime - mLastRxTime);
        mLayout.setDeviceRxTime(mDeviceStats, rxTimeDelta);
        mLastRxTime = rxTime;

        long txTime = activityInfo.getControllerTxTimeMillis();
        long txTimeDelta = Math.max(0, txTime - mLastTxTime);
        mLayout.setDeviceTxTime(mDeviceStats, txTimeDelta);
        mLastTxTime = txTime;

        long idleTime = activityInfo.getControllerIdleTimeMillis();
        long idleTimeDelta = Math.max(0, idleTime - mLastIdleTime);
        mLayout.setDeviceIdleTime(mDeviceStats, idleTimeDelta);
        mLastIdleTime = idleTime;

        mPowerStats.durationMs = rxTimeDelta + txTimeDelta + idleTimeDelta;

        List<UidTraffic> uidTraffic = activityInfo.getUidTraffic();
        for (int i = uidTraffic.size() - 1; i >= 0; i--) {
            UidTraffic ut = uidTraffic.get(i);
            int uid = mUidResolver.mapUid(ut.getUid());
            UidStats counts = mUidStats.get(uid);
            if (counts == null) {
                counts = new UidStats();
                mUidStats.put(uid, counts);
            }
            counts.rxCount += ut.getRxBytes();
            counts.txCount += ut.getTxBytes();
        }

        for (int i = mUidStats.size() - 1; i >= 0; i--) {
            UidStats counts = mUidStats.valueAt(i);
            long rxDelta = Math.max(0, counts.rxCount - counts.lastRxCount);
            counts.lastRxCount = counts.rxCount;
            counts.rxCount = 0;

            long txDelta = Math.max(0, counts.txCount - counts.lastTxCount);
            counts.lastTxCount = counts.txCount;
            counts.txCount = 0;

            if (rxDelta != 0 || txDelta != 0) {
                int uid = mUidStats.keyAt(i);
                long[] stats = mPowerStats.uidStats.get(uid);
                if (stats == null) {
                    stats = new long[mLayout.getUidStatsArrayLength()];
                    mPowerStats.uidStats.put(uid, stats);
                }

                mLayout.setUidRxBytes(stats, rxDelta);
                mLayout.setUidTxBytes(stats, txDelta);
            }
        }
    }

    private void collectBluetoothScanStats() {
        mBluetoothStatsRetriever.retrieveBluetoothScanTimes((uid, scanTimeMs) -> {
            uid = mUidResolver.mapUid(uid);
            UidStats uidStats = mUidStats.get(uid);
            if (uidStats == null) {
                uidStats = new UidStats();
                mUidStats.put(uid, uidStats);
            }
            uidStats.scanTime += scanTimeMs;
        });

        long totalScanTime = 0;
        for (int i = mUidStats.size() - 1; i >= 0; i--) {
            UidStats counts = mUidStats.valueAt(i);
            if (counts.scanTime == 0) {
                continue;
            }

            long delta = Math.max(0, counts.scanTime - counts.lastScanTime);
            counts.lastScanTime = counts.scanTime;
            counts.scanTime = 0;

            if (delta != 0) {
                int uid = mUidStats.keyAt(i);
                long[] stats = mPowerStats.uidStats.get(uid);
                if (stats == null) {
                    stats = new long[mLayout.getUidStatsArrayLength()];
                    mPowerStats.uidStats.put(uid, stats);
                }

                mLayout.setUidScanTime(stats, delta);
                totalScanTime += delta;
            }
        }

        mLayout.setDeviceScanTime(mDeviceStats, totalScanTime);
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

    @Override
    protected void onUidRemoved(int uid) {
        super.onUidRemoved(uid);
        mUidStats.remove(uid);
    }
}
