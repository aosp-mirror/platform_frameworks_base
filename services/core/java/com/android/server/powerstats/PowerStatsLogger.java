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

import static java.lang.System.currentTimeMillis;

import android.content.Context;
import android.hardware.power.stats.Channel;
import android.hardware.power.stats.EnergyConsumer;
import android.hardware.power.stats.EnergyConsumerResult;
import android.hardware.power.stats.EnergyMeasurement;
import android.hardware.power.stats.PowerEntity;
import android.hardware.power.stats.StateResidencyResult;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.powerstats.PowerStatsHALWrapper.IPowerStatsHALWrapper;
import com.android.server.powerstats.ProtoStreamUtils.ChannelUtils;
import com.android.server.powerstats.ProtoStreamUtils.EnergyConsumerResultUtils;
import com.android.server.powerstats.ProtoStreamUtils.EnergyConsumerUtils;
import com.android.server.powerstats.ProtoStreamUtils.EnergyMeasurementUtils;
import com.android.server.powerstats.ProtoStreamUtils.PowerEntityUtils;
import com.android.server.powerstats.ProtoStreamUtils.StateResidencyResultUtils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * PowerStatsLogger is responsible for logging model, meter, and residency data to on-device
 * storage.  Messages are sent to its message handler to request that energy data be logged, at
 * which time it queries the PowerStats HAL and logs the data to on-device storage.  The on-device
 * storage is dumped to file by calling writeModelDataToFile, writeMeterDataToFile, or
 * writeResidencyDataToFile with a file descriptor that points to the output file.
 */
public final class PowerStatsLogger extends Handler {
    private static final String TAG = PowerStatsLogger.class.getSimpleName();
    private static final boolean DEBUG = false;
    protected static final int MSG_LOG_TO_DATA_STORAGE_BATTERY_DROP = 0;
    protected static final int MSG_LOG_TO_DATA_STORAGE_LOW_FREQUENCY = 1;
    protected static final int MSG_LOG_TO_DATA_STORAGE_HIGH_FREQUENCY = 2;

    // TODO(b/181240441): Add a listener to update the Wall clock baseline when changed
    private final long mStartWallTime;
    private final PowerStatsDataStorage mPowerStatsMeterStorage;
    private final PowerStatsDataStorage mPowerStatsModelStorage;
    private final PowerStatsDataStorage mPowerStatsResidencyStorage;
    private final IPowerStatsHALWrapper mPowerStatsHALWrapper;
    private File mDataStoragePath;
    private boolean mDeleteMeterDataOnBoot;
    private boolean mDeleteModelDataOnBoot;
    private boolean mDeleteResidencyDataOnBoot;

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_LOG_TO_DATA_STORAGE_HIGH_FREQUENCY:
                if (DEBUG) Slog.d(TAG, "Logging to data storage on high frequency timer");

                // Log power meter data.
                EnergyMeasurement[] energyMeasurements =
                    mPowerStatsHALWrapper.readEnergyMeter(new int[0]);
                EnergyMeasurementUtils.adjustTimeSinceBootToEpoch(energyMeasurements,
                        mStartWallTime);
                mPowerStatsMeterStorage.write(
                        EnergyMeasurementUtils.getProtoBytes(energyMeasurements));
                if (DEBUG) EnergyMeasurementUtils.print(energyMeasurements);

                // Log power model data without attribution data.
                EnergyConsumerResult[] ecrNoAttribution =
                    mPowerStatsHALWrapper.getEnergyConsumed(new int[0]);
                EnergyConsumerResultUtils.adjustTimeSinceBootToEpoch(ecrNoAttribution,
                        mStartWallTime);
                mPowerStatsModelStorage.write(
                        EnergyConsumerResultUtils.getProtoBytes(ecrNoAttribution, false));
                if (DEBUG) EnergyConsumerResultUtils.print(ecrNoAttribution);
                break;

            case MSG_LOG_TO_DATA_STORAGE_LOW_FREQUENCY:
                if (DEBUG) Slog.d(TAG, "Logging to data storage on low frequency timer");

                // Log power model data with attribution data.
                EnergyConsumerResult[] ecrAttribution =
                    mPowerStatsHALWrapper.getEnergyConsumed(new int[0]);
                EnergyConsumerResultUtils.adjustTimeSinceBootToEpoch(ecrAttribution,
                        mStartWallTime);
                mPowerStatsModelStorage.write(
                        EnergyConsumerResultUtils.getProtoBytes(ecrAttribution, true));
                if (DEBUG) EnergyConsumerResultUtils.print(ecrAttribution);
                break;

            case MSG_LOG_TO_DATA_STORAGE_BATTERY_DROP:
                if (DEBUG) Slog.d(TAG, "Logging to data storage on battery drop");

                // Log state residency data.
                StateResidencyResult[] stateResidencyResults =
                    mPowerStatsHALWrapper.getStateResidency(new int[0]);
                StateResidencyResultUtils.adjustTimeSinceBootToEpoch(stateResidencyResults,
                        mStartWallTime);
                mPowerStatsResidencyStorage.write(
                        StateResidencyResultUtils.getProtoBytes(stateResidencyResults));
                if (DEBUG) StateResidencyResultUtils.print(stateResidencyResults);
                break;
        }
    }

    /**
     * Writes meter data stored in PowerStatsDataStorage to a file descriptor.
     *
     * @param fd FileDescriptor where meter data stored in PowerStatsDataStorage is written.  Data
     *           is written in protobuf format as defined by powerstatsservice.proto.
     */
    public void writeMeterDataToFile(FileDescriptor fd) {
        if (DEBUG) Slog.d(TAG, "Writing meter data to file");

        final ProtoOutputStream pos = new ProtoOutputStream(fd);

        try {
            Channel[] channel = mPowerStatsHALWrapper.getEnergyMeterInfo();
            ChannelUtils.packProtoMessage(channel, pos);
            if (DEBUG) ChannelUtils.print(channel);

            mPowerStatsMeterStorage.read(new PowerStatsDataStorage.DataElementReadCallback() {
                @Override
                public void onReadDataElement(byte[] data) {
                    try {
                        final ProtoInputStream pis =
                                new ProtoInputStream(new ByteArrayInputStream(data));
                        // TODO(b/166535853): ProtoOutputStream doesn't provide a method to write
                        // a byte array that already contains a serialized proto, so I have to
                        // deserialize, then re-serialize.  This is computationally inefficient.
                        EnergyMeasurement[] energyMeasurement =
                            EnergyMeasurementUtils.unpackProtoMessage(data);
                        EnergyMeasurementUtils.packProtoMessage(energyMeasurement, pos);
                        if (DEBUG) EnergyMeasurementUtils.print(energyMeasurement);
                    } catch (IOException e) {
                        Slog.e(TAG, "Failed to write energy meter data to incident report.", e);
                    }
                }
            });
        } catch (IOException e) {
            Slog.e(TAG, "Failed to write energy meter info to incident report.", e);
        }

        pos.flush();
    }

    /**
     * Writes model data stored in PowerStatsDataStorage to a file descriptor.
     *
     * @param fd FileDescriptor where model data stored in PowerStatsDataStorage is written.  Data
     *           is written in protobuf format as defined by powerstatsservice.proto.
     */
    public void writeModelDataToFile(FileDescriptor fd) {
        if (DEBUG) Slog.d(TAG, "Writing model data to file");

        final ProtoOutputStream pos = new ProtoOutputStream(fd);

        try {
            EnergyConsumer[] energyConsumer = mPowerStatsHALWrapper.getEnergyConsumerInfo();
            EnergyConsumerUtils.packProtoMessage(energyConsumer, pos);
            if (DEBUG) EnergyConsumerUtils.print(energyConsumer);

            mPowerStatsModelStorage.read(new PowerStatsDataStorage.DataElementReadCallback() {
                @Override
                public void onReadDataElement(byte[] data) {
                    try {
                        final ProtoInputStream pis =
                                new ProtoInputStream(new ByteArrayInputStream(data));
                        // TODO(b/166535853): ProtoOutputStream doesn't provide a method to write
                        // a byte array that already contains a serialized proto, so I have to
                        // deserialize, then re-serialize.  This is computationally inefficient.
                        EnergyConsumerResult[] energyConsumerResult =
                            EnergyConsumerResultUtils.unpackProtoMessage(data);
                        EnergyConsumerResultUtils.packProtoMessage(energyConsumerResult, pos, true);
                        if (DEBUG) EnergyConsumerResultUtils.print(energyConsumerResult);
                    } catch (IOException e) {
                        Slog.e(TAG, "Failed to write energy model data to incident report.", e);
                    }
                }
            });
        } catch (IOException e) {
            Slog.e(TAG, "Failed to write energy model info to incident report.", e);
        }

        pos.flush();
    }

    /**
     * Writes residency data stored in PowerStatsDataStorage to a file descriptor.
     *
     * @param fd FileDescriptor where residency data stored in PowerStatsDataStorage is written.
     *           Data is written in protobuf format as defined by powerstatsservice.proto.
     */
    public void writeResidencyDataToFile(FileDescriptor fd) {
        if (DEBUG) Slog.d(TAG, "Writing residency data to file");

        final ProtoOutputStream pos = new ProtoOutputStream(fd);

        try {
            PowerEntity[] powerEntity = mPowerStatsHALWrapper.getPowerEntityInfo();
            PowerEntityUtils.packProtoMessage(powerEntity, pos);
            if (DEBUG) PowerEntityUtils.print(powerEntity);

            mPowerStatsResidencyStorage.read(new PowerStatsDataStorage.DataElementReadCallback() {
                @Override
                public void onReadDataElement(byte[] data) {
                    try {
                        final ProtoInputStream pis =
                                new ProtoInputStream(new ByteArrayInputStream(data));
                        // TODO(b/166535853): ProtoOutputStream doesn't provide a method to write
                        // a byte array that already contains a serialized proto, so I have to
                        // deserialize, then re-serialize.  This is computationally inefficient.
                        StateResidencyResult[] stateResidencyResult =
                            StateResidencyResultUtils.unpackProtoMessage(data);
                        StateResidencyResultUtils.packProtoMessage(stateResidencyResult, pos);
                        if (DEBUG) StateResidencyResultUtils.print(stateResidencyResult);
                    } catch (IOException e) {
                        Slog.e(TAG, "Failed to write residency data to incident report.", e);
                    }
                }
            });
        } catch (IOException e) {
            Slog.e(TAG, "Failed to write residency data to incident report.", e);
        }

        pos.flush();
    }

    private boolean dataChanged(String cachedFilename, byte[] dataCurrent) {
        boolean dataChanged = false;

        if (mDataStoragePath.exists() || mDataStoragePath.mkdirs()) {
            final File cachedFile = new File(mDataStoragePath, cachedFilename);

            if (cachedFile.exists()) {
                // Get the byte array for the cached data.
                final byte[] dataCached = new byte[(int) cachedFile.length()];

                // Get the cached data from file.
                try {
                    final FileInputStream fis = new FileInputStream(cachedFile.getPath());
                    fis.read(dataCached);
                } catch (IOException e) {
                    Slog.e(TAG, "Failed to read cached data from file", e);
                }

                // If the cached and current data are different, delete the data store.
                dataChanged = !Arrays.equals(dataCached, dataCurrent);
            } else {
                // Either the cached file was somehow deleted, or this is the first
                // boot of the device and we're creating the file for the first time.
                // In either case, delete the log files.
                dataChanged = true;
            }
        }

        return dataChanged;
    }

    private void updateCacheFile(String cacheFilename, byte[] data) {
        try {
            final AtomicFile atomicCachedFile =
                    new AtomicFile(new File(mDataStoragePath, cacheFilename));
            final FileOutputStream fos = atomicCachedFile.startWrite();
            fos.write(data);
            atomicCachedFile.finishWrite(fos);
        } catch (IOException e) {
            Slog.e(TAG, "Failed to write current data to cached file", e);
        }
    }

    public boolean getDeleteMeterDataOnBoot() {
        return mDeleteMeterDataOnBoot;
    }

    public boolean getDeleteModelDataOnBoot() {
        return mDeleteModelDataOnBoot;
    }

    public boolean getDeleteResidencyDataOnBoot() {
        return mDeleteResidencyDataOnBoot;
    }

    @VisibleForTesting
    public long getStartWallTime() {
        return mStartWallTime;
    }

    public PowerStatsLogger(Context context, Looper looper, File dataStoragePath,
            String meterFilename, String meterCacheFilename,
            String modelFilename, String modelCacheFilename,
            String residencyFilename, String residencyCacheFilename,
            IPowerStatsHALWrapper powerStatsHALWrapper) {
        super(looper);
        mStartWallTime = currentTimeMillis() - SystemClock.elapsedRealtime();
        if (DEBUG) Slog.d(TAG, "mStartWallTime: " + mStartWallTime);
        mPowerStatsHALWrapper = powerStatsHALWrapper;
        mDataStoragePath = dataStoragePath;

        mPowerStatsMeterStorage = new PowerStatsDataStorage(context, mDataStoragePath,
            meterFilename);
        mPowerStatsModelStorage = new PowerStatsDataStorage(context, mDataStoragePath,
            modelFilename);
        mPowerStatsResidencyStorage = new PowerStatsDataStorage(context, mDataStoragePath,
            residencyFilename);

        final Channel[] channels = mPowerStatsHALWrapper.getEnergyMeterInfo();
        final byte[] channelBytes = ChannelUtils.getProtoBytes(channels);
        mDeleteMeterDataOnBoot = dataChanged(meterCacheFilename, channelBytes);
        if (mDeleteMeterDataOnBoot) {
            mPowerStatsMeterStorage.deleteLogs();
            updateCacheFile(meterCacheFilename, channelBytes);
        }

        final EnergyConsumer[] energyConsumers = mPowerStatsHALWrapper.getEnergyConsumerInfo();
        final byte[] energyConsumerBytes = EnergyConsumerUtils.getProtoBytes(energyConsumers);
        mDeleteModelDataOnBoot = dataChanged(modelCacheFilename, energyConsumerBytes);
        if (mDeleteModelDataOnBoot) {
            mPowerStatsModelStorage.deleteLogs();
            updateCacheFile(modelCacheFilename, energyConsumerBytes);
        }

        final PowerEntity[] powerEntities = mPowerStatsHALWrapper.getPowerEntityInfo();
        final byte[] powerEntityBytes = PowerEntityUtils.getProtoBytes(powerEntities);
        mDeleteResidencyDataOnBoot = dataChanged(residencyCacheFilename, powerEntityBytes);
        if (mDeleteResidencyDataOnBoot) {
            mPowerStatsResidencyStorage.deleteLogs();
            updateCacheFile(residencyCacheFilename, powerEntityBytes);
        }
    }

    /**
     * Dump stats about stored data.
     */
    public void dump(IndentingPrintWriter ipw) {
        ipw.println("PowerStats Meter Data:");
        ipw.increaseIndent();
        mPowerStatsMeterStorage.dump(ipw);
        ipw.decreaseIndent();
        ipw.println("PowerStats Model Data:");
        ipw.increaseIndent();
        mPowerStatsModelStorage.dump(ipw);
        ipw.decreaseIndent();
        ipw.println("PowerStats State Residency Data:");
        ipw.increaseIndent();
        mPowerStatsResidencyStorage.dump(ipw);
        ipw.decreaseIndent();
    }

}
