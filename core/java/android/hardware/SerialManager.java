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
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package android.hardware;

import android.app.PendingIntent;
import android.content.Context;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.Log;

import java.io.IOException;
import java.util.HashMap;

/**
 * @hide
 */
public class SerialManager {
    private static final String TAG = "SerialManager";

    private final Context mContext;
    private final ISerialManager mService;

    /**
     * {@hide}
     */
    public SerialManager(Context context, ISerialManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Returns a string array containing the names of available serial ports
     *
     * @return names of available serial ports
     */
    public String[] getSerialPorts() {
        try {
            return mService.getSerialPorts();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getSerialPorts", e);
            return null;
        }
    }

    /**
     * Opens and returns the {@link android.hardware.SerialPort} with the given name.
     * The speed of the serial port must be one of:
     * 50, 75, 110, 134, 150, 200, 300, 600, 1200, 1800, 2400, 4800, 9600,
     * 19200, 38400, 57600, 115200, 230400, 460800, 500000, 576000, 921600, 1000000, 1152000,
     * 1500000, 2000000, 2500000, 3000000, 3500000 or 4000000
     *
     * @param name of the serial port
     * @param speed at which to open the serial port
     * @return the serial port
     */
    public SerialPort openSerialPort(String name, int speed) throws IOException {
        try {
            ParcelFileDescriptor pfd = mService.openSerialPort(name);
            if (pfd != null) {
                SerialPort port = new SerialPort(name);
                port.open(pfd, speed);
                return port;
            } else {
                throw new IOException("Could not open serial port " + name);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "exception in UsbManager.openDevice", e);
        }
        return null;
    }
}
