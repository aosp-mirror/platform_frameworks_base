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
import android.util.Slog;
import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;

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
import java.io.IOException;

/**
 * PowerStatsLogger is responsible for logging model and meter energy data to on-device storage.
 * Messages are sent to its message handler to request that energy data be logged, at which time it
 * queries the PowerStats HAL and logs the data to on-device storage.  The on-device storage is
 * dumped to file by calling writeModelDataToFile, writeMeterDataToFile, or writeResidencyDataToFile
 * with a file descriptor that points to the output file.
 */
public final class PowerStatsLogger extends Handler {
    private static final String TAG = PowerStatsLogger.class.getSimpleName();
    private static final boolean DEBUG = false;
    protected static final int MSG_LOG_TO_DATA_STORAGE_TIMER = 0;
    protected static final int MSG_LOG_TO_DATA_STORAGE_BATTERY_DROP = 1;

    private final PowerStatsDataStorage mPowerStatsMeterStorage;
    private final PowerStatsDataStorage mPowerStatsModelStorage;
    private final PowerStatsDataStorage mPowerStatsResidencyStorage;
    private final IPowerStatsHALWrapper mPowerStatsHALWrapper;

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_LOG_TO_DATA_STORAGE_TIMER:
                if (DEBUG) Slog.d(TAG, "Logging to data storage on timer");

                // Log power meter data.
                EnergyMeasurement[] energyMeasurements =
                    mPowerStatsHALWrapper.readEnergyMeter(new int[0]);
                mPowerStatsMeterStorage.write(
                        EnergyMeasurementUtils.getProtoBytes(energyMeasurements));
                if (DEBUG) EnergyMeasurementUtils.print(energyMeasurements);

                // Log power model data.
                EnergyConsumerResult[] energyConsumerResults =
                    mPowerStatsHALWrapper.getEnergyConsumed(new int[0]);
                mPowerStatsModelStorage.write(
                        EnergyConsumerResultUtils.getProtoBytes(energyConsumerResults));
                if (DEBUG) EnergyConsumerResultUtils.print(energyConsumerResults);
                break;

            case MSG_LOG_TO_DATA_STORAGE_BATTERY_DROP:
                if (DEBUG) Slog.d(TAG, "Logging to data storage on battery drop");

                // Log state residency data.
                StateResidencyResult[] stateResidencyResults =
                    mPowerStatsHALWrapper.getStateResidency(new int[0]);
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
                        Slog.e(TAG, "Failed to write energy meter data to incident report.");
                    }
                }
            });
        } catch (IOException e) {
            Slog.e(TAG, "Failed to write energy meter info to incident report.");
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
                        EnergyConsumerResultUtils.packProtoMessage(energyConsumerResult, pos);
                        if (DEBUG) EnergyConsumerResultUtils.print(energyConsumerResult);
                    } catch (IOException e) {
                        Slog.e(TAG, "Failed to write energy model data to incident report.");
                    }
                }
            });
        } catch (IOException e) {
            Slog.e(TAG, "Failed to write energy model info to incident report.");
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
                        Slog.e(TAG, "Failed to write residency data to incident report.");
                    }
                }
            });
        } catch (IOException e) {
            Slog.e(TAG, "Failed to write residency data to incident report.");
        }

        pos.flush();
    }

    public PowerStatsLogger(Context context, File dataStoragePath, String meterFilename,
            String modelFilename, String residencyFilename,
            IPowerStatsHALWrapper powerStatsHALWrapper) {
        super(Looper.getMainLooper());
        mPowerStatsHALWrapper = powerStatsHALWrapper;
        mPowerStatsMeterStorage = new PowerStatsDataStorage(context, dataStoragePath,
            meterFilename);
        mPowerStatsModelStorage = new PowerStatsDataStorage(context, dataStoragePath,
            modelFilename);
        mPowerStatsResidencyStorage = new PowerStatsDataStorage(context, dataStoragePath,
            residencyFilename);
    }
}
