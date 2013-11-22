/*
 * Copyright (C) 2011 The Android Open Source Project
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
 * See the License for the specific language governing permissions an
 * limitations under the License.
 */

package com.android.server;

import android.content.Context;
import android.hardware.ISerialManager;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.util.ArrayList;

public class SerialService extends ISerialManager.Stub {

    private final Context mContext;
    private final String[] mSerialPorts;

    public SerialService(Context context) {
        mContext = context;
        mSerialPorts = context.getResources().getStringArray(
                com.android.internal.R.array.config_serialPorts);
    }

    public String[] getSerialPorts() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.SERIAL_PORT, null);

        ArrayList<String> ports = new ArrayList<String>();
        for (int i = 0; i < mSerialPorts.length; i++) {
            String path = mSerialPorts[i];
            if (new File(path).exists()) {
                ports.add(path);
            }
        }
        String[] result = new String[ports.size()];
        ports.toArray(result);
        return result;
    }

    public ParcelFileDescriptor openSerialPort(String path) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.SERIAL_PORT, null);
        for (int i = 0; i < mSerialPorts.length; i++) {
            if (mSerialPorts[i].equals(path)) {
                return native_open(path);
            }
        }
        throw new IllegalArgumentException("Invalid serial port " + path);
    }

    private native ParcelFileDescriptor native_open(String path);
}
