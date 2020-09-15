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
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;

import com.android.server.powerstats.PowerStatsHALWrapper.IPowerStatsHALWrapper;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;

/**
 * PowerStatsLogger is responsible for logging energy data to on-device
 * storage.  Messages are sent to its message handler to request that energy
 * data be logged, at which time it queries the PowerStats HAL and logs the
 * data to on-device storage.  The on-device storage is dumped to file by
 * calling writeToFile with a file descriptor that points to the output file.
 */
public final class PowerStatsLogger extends Handler {
    private static final String TAG = PowerStatsLogger.class.getSimpleName();
    private static final boolean DEBUG = false;
    protected static final int MSG_LOG_TO_DATA_STORAGE = 0;

    private final PowerStatsDataStorage mPowerStatsDataStorage;
    private final IPowerStatsHALWrapper mPowerStatsHALWrapper;

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_LOG_TO_DATA_STORAGE:
                if (DEBUG) Log.d(TAG, "Logging to data storage");
                PowerStatsData energyData =
                        new PowerStatsData(mPowerStatsHALWrapper.readEnergyData());
                mPowerStatsDataStorage.write(energyData.getProtoBytes());
                break;
        }
    }

    /**
     * Writes data stored in PowerStatsDataStorage to a file descriptor.
     *
     * @param fd FileDescriptor where data stored in PowerStatsDataStorage is
     *           written.  Data is written in protobuf format as defined by
     *           powerstatsservice.proto.
     */
    public void writeToFile(FileDescriptor fd) {
        if (DEBUG) Log.d(TAG, "Writing to file");

        final ProtoOutputStream pos = new ProtoOutputStream(fd);

        try {
            PowerStatsData railInfo = new PowerStatsData(mPowerStatsHALWrapper.readRailInfo());
            railInfo.toProto(pos);
            if (DEBUG) railInfo.print();

            mPowerStatsDataStorage.read(new PowerStatsDataStorage.DataElementReadCallback() {
                @Override
                public void onReadDataElement(byte[] data) {
                    try {
                        final ProtoInputStream pis =
                                new ProtoInputStream(new ByteArrayInputStream(data));
                        // TODO(b/166535853): ProtoOutputStream doesn't provide a method to write
                        // a byte array that already contains a serialized proto, so I have to
                        // deserialize, then re-serialize.  This is computationally inefficient.
                        final PowerStatsData energyData = new PowerStatsData(pis);
                        energyData.toProto(pos);
                        if (DEBUG) energyData.print();
                    } catch (IOException e) {
                        Log.e(TAG, "Failed to write energy data to incident report.");
                    }
                }
            });
        } catch (IOException e) {
            Log.e(TAG, "Failed to write rail info to incident report.");
        }

        pos.flush();
    }

    public PowerStatsLogger(Context context, File dataStoragePath, String dataStorageFilename,
            IPowerStatsHALWrapper powerStatsHALWrapper) {
        super(Looper.getMainLooper());
        mPowerStatsHALWrapper = powerStatsHALWrapper;
        mPowerStatsDataStorage = new PowerStatsDataStorage(context, dataStoragePath,
            dataStorageFilename);
    }
}
