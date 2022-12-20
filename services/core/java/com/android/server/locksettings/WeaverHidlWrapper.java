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

import android.hardware.weaver.V1_0.IWeaver;
import android.hardware.weaver.V1_0.WeaverConfig;
import android.hardware.weaver.V1_0.WeaverReadResponse;
import android.hardware.weaver.V1_0.WeaverReadStatus;
import android.hardware.weaver.V1_0.WeaverStatus;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.Slog;

import java.util.ArrayList;

/**
 * Implement the AIDL IWeaver interface wrapping the HIDL implementation
 */
class WeaverHidlWrapper implements android.hardware.weaver.IWeaver {
    private static final String TAG = "WeaverHidlWrapper";
    private final IWeaver mImpl;

    WeaverHidlWrapper(IWeaver impl) {
        mImpl = impl;
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

    @Override
    public String getInterfaceHash() {
        // We do not require the interface hash as the client.
        throw new UnsupportedOperationException(
            "WeaverHidlWrapper does not support getInterfaceHash");
    }
    @Override
    public int getInterfaceVersion() {
        // Supports only V2 which is at feature parity.
        return 2;
    }
    @Override
    public android.os.IBinder asBinder() {
        // There is no IHwBinder to IBinder. Not required as the client.
        throw new UnsupportedOperationException("WeaverHidlWrapper does not support asBinder");
    }

    @Override
    public android.hardware.weaver.WeaverConfig getConfig() throws RemoteException {
        final WeaverConfig[] res = new WeaverConfig[1];
        mImpl.getConfig((int status, WeaverConfig config) -> {
            if (status == WeaverStatus.OK && config.slots > 0) {
                res[0] = config;
            } else {
                res[0] = null;
                Slog.e(TAG,
                        "Failed to get HIDL weaver config. status: " + status
                                + ", slots: " + config.slots);
            }
        });

        if (res[0] == null) {
            return null;
        }
        android.hardware.weaver.WeaverConfig config = new android.hardware.weaver.WeaverConfig();
        config.slots = res[0].slots;
        config.keySize = res[0].keySize;
        config.valueSize = res[0].valueSize;
        return config;
    }

    @Override
    public android.hardware.weaver.WeaverReadResponse read(int slotId, byte[] key)
            throws RemoteException {
        final WeaverReadResponse[] res = new WeaverReadResponse[1];
        final int[] status = new int[1];
        mImpl.read(
                slotId, toByteArrayList(key), (int inStatus, WeaverReadResponse readResponse) -> {
                    status[0] = inStatus;
                    res[0] = readResponse;
                });

        android.hardware.weaver.WeaverReadResponse aidlRes =
                new android.hardware.weaver.WeaverReadResponse();
        switch (status[0]) {
            case WeaverReadStatus.OK:
                aidlRes.status = android.hardware.weaver.WeaverReadStatus.OK;
                break;
            case WeaverReadStatus.THROTTLE:
                aidlRes.status = android.hardware.weaver.WeaverReadStatus.THROTTLE;
                break;
            case WeaverReadStatus.INCORRECT_KEY:
                aidlRes.status = android.hardware.weaver.WeaverReadStatus.INCORRECT_KEY;
                break;
            case WeaverReadStatus.FAILED:
                aidlRes.status = android.hardware.weaver.WeaverReadStatus.FAILED;
                break;
            default:
                aidlRes.status = android.hardware.weaver.WeaverReadStatus.FAILED;
                break;
        }
        if (res[0] != null) {
            aidlRes.timeout = res[0].timeout;
            aidlRes.value = fromByteArrayList(res[0].value);
        }
        return aidlRes;
    }

    @Override
    public void write(int slotId, byte[] key, byte[] value) throws RemoteException {
        int writeStatus = mImpl.write(slotId, toByteArrayList(key), toByteArrayList(value));
        if (writeStatus != WeaverStatus.OK) {
            throw new ServiceSpecificException(
                android.hardware.weaver.IWeaver.STATUS_FAILED, "Failed IWeaver.write call");
        }
    }
}
