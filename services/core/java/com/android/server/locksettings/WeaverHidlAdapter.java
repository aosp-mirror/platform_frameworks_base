/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.locksettings;

import android.hardware.weaver.IWeaver;
import android.hardware.weaver.WeaverConfig;
import android.hardware.weaver.WeaverReadResponse;
import android.hardware.weaver.WeaverReadStatus;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.Slog;

import java.util.ArrayList;

/**
 * Adapt the legacy HIDL interface to present the AIDL interface.
 */
class WeaverHidlAdapter implements IWeaver {
    private static final String TAG = "WeaverHidlAdapter";
    private final android.hardware.weaver.V1_0.IWeaver mImpl;

    WeaverHidlAdapter(android.hardware.weaver.V1_0.IWeaver impl) {
        mImpl = impl;
    }

    @Override
    public WeaverConfig getConfig() throws RemoteException {
        final WeaverConfig[] res = new WeaverConfig[1];
        mImpl.getConfig((int status, android.hardware.weaver.V1_0.WeaverConfig config) -> {
            if (status == android.hardware.weaver.V1_0.WeaverStatus.OK) {
                WeaverConfig aidlRes = new WeaverConfig();
                aidlRes.slots = config.slots;
                aidlRes.keySize = config.keySize;
                aidlRes.valueSize = config.valueSize;
                res[0] = aidlRes;
            } else {
                Slog.e(TAG,
                        "Failed to get HIDL weaver config. status: " + status
                                + ", slots: " + config.slots);
            }
        });
        return res[0];
    }

    @Override
    public WeaverReadResponse read(int slotId, byte[] key)
            throws RemoteException {
        final WeaverReadResponse[] res = new WeaverReadResponse[1];
        mImpl.read(
                slotId, toByteArrayList(key),
                (int inStatus, android.hardware.weaver.V1_0.WeaverReadResponse readResponse) -> {
                    WeaverReadResponse aidlRes =
                            new WeaverReadResponse();
                    switch (inStatus) {
                        case android.hardware.weaver.V1_0.WeaverReadStatus.OK:
                            aidlRes.status = WeaverReadStatus.OK;
                            break;
                        case android.hardware.weaver.V1_0.WeaverReadStatus.THROTTLE:
                            aidlRes.status = WeaverReadStatus.THROTTLE;
                            break;
                        case android.hardware.weaver.V1_0.WeaverReadStatus.INCORRECT_KEY:
                            aidlRes.status = WeaverReadStatus.INCORRECT_KEY;
                            break;
                        case android.hardware.weaver.V1_0.WeaverReadStatus.FAILED:
                            aidlRes.status = WeaverReadStatus.FAILED;
                            break;
                        default:
                            Slog.e(TAG, "Unexpected status in read: " + inStatus);
                            aidlRes.status = WeaverReadStatus.FAILED;
                            break;
                    }
                    aidlRes.timeout = readResponse.timeout;
                    aidlRes.value = fromByteArrayList(readResponse.value);
                    res[0] = aidlRes;
                });
        return res[0];
    }

    @Override
    public void write(int slotId, byte[] key, byte[] value) throws RemoteException {
        int writeStatus = mImpl.write(slotId, toByteArrayList(key), toByteArrayList(value));
        if (writeStatus != android.hardware.weaver.V1_0.WeaverStatus.OK) {
            throw new ServiceSpecificException(
                    IWeaver.STATUS_FAILED, "Failed IWeaver.write call, status: " + writeStatus);
        }
    }

    @Override
    public String getInterfaceHash() {
        // We do not require the interface hash as the client.
        throw new UnsupportedOperationException(
                "WeaverHidlAdapter does not support getInterfaceHash");
    }

    @Override
    public int getInterfaceVersion() {
        // Supports only V2 which is at feature parity.
        return 2;
    }

    @Override
    public IBinder asBinder() {
        // There is no IHwBinder to IBinder. Not required as the client.
        throw new UnsupportedOperationException("WeaverHidlAdapter does not support asBinder");
    }

    private static ArrayList<Byte> toByteArrayList(byte[] data) {
        ArrayList<Byte> result = new ArrayList<Byte>(data.length);
        for (int i = 0; i < data.length; i++) {
            result.add(data[i]);
        }
        return result;
    }

    private static byte[] fromByteArrayList(ArrayList<Byte> data) {
        byte[] result = new byte[data.size()];
        for (int i = 0; i < data.size(); i++) {
            result[i] = data.get(i);
        }
        return result;
    }
}
