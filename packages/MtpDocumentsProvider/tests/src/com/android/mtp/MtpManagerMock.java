/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.mtp;

import android.content.Context;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class MtpManagerMock extends MtpManager {
    private final Set<Integer> mValidDevices = new HashSet<Integer>();
    private final Set<Integer> mOpenedDevices = new TreeSet<Integer>();
    private final Map<Integer, MtpRoot[]> mRoots = new HashMap<Integer, MtpRoot[]>();

    MtpManagerMock(Context context) {
        super(context);
    }

    void addValidDevice(int deviceId) {
        mValidDevices.add(deviceId);
    }

    void setRoots(int deviceId, MtpRoot[] roots) {
        mRoots.put(deviceId, roots);
    }

    @Override
    void openDevice(int deviceId) throws IOException {
        if (!mValidDevices.contains(deviceId) || mOpenedDevices.contains(deviceId)) {
            throw new IOException();
        }
        mOpenedDevices.add(deviceId);
    }

    @Override
    void closeDevice(int deviceId) throws IOException {
        if (!mValidDevices.contains(deviceId) || !mOpenedDevices.contains(deviceId)) {
            throw new IOException();
        }
        mOpenedDevices.remove(deviceId);
    }

    @Override
    MtpRoot[] getRoots(int deviceId) throws IOException {
        if (mRoots.containsKey(deviceId)) {
            return mRoots.get(deviceId);
        } else {
            throw new IOException("getRoots error");
        }
    }

    @Override
    int[] getOpenedDeviceIds() {
        int i = 0;
        final int[] result = new int[mOpenedDevices.size()];
        for (int deviceId : mOpenedDevices) {
            result[i++] = deviceId;
        }
        return result;
    }
}
