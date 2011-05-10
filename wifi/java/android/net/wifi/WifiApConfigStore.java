/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.net.wifi;

import android.content.Context;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.os.Environment;
import android.os.Message;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.util.UUID;

import com.android.internal.R;


/**
 * Provides API to the WifiStateMachine for doing read/write access
 * to soft access point configuration
 */
class WifiApConfigStore {

    private static Context sContext;
    private static final String TAG = "WifiApConfigStore";

    private static final String AP_CONFIG_FILE = Environment.getDataDirectory() +
        "/misc/wifi/softap.conf";

    private static final int AP_CONFIG_FILE_VERSION = 1;

    private static WifiConfiguration sApConfig = new WifiConfiguration();
    private static final Object sApConfigLock = new Object();

    private static FileReadWriteHandler sFileReadWriteHandler;
    private static final int READ_AP_CONFIG               = 1;
    private static final int WRITE_AP_CONFIG              = 2;

    static void initialize(Context context) {
        sContext = context;

        /* File operations happen on a seperate thread */
        HandlerThread configThread = new HandlerThread("WifiApConfigStore");
        configThread.start();
        sFileReadWriteHandler = new FileReadWriteHandler(configThread.getLooper());
        Message.obtain(sFileReadWriteHandler, READ_AP_CONFIG).sendToTarget();
    }


    static void setApConfiguration(WifiConfiguration config) {
        synchronized (sApConfigLock) {
            sApConfig = config;
        }
        Message.obtain(sFileReadWriteHandler, WRITE_AP_CONFIG, new WifiConfiguration(config))
            .sendToTarget();
    }

    static WifiConfiguration getApConfiguration() {
        synchronized (sApConfigLock) {
            return new WifiConfiguration(sApConfig);
        }
    }

    /**
     * File read/write handler
     */
    private static class FileReadWriteHandler extends Handler {

        public FileReadWriteHandler(android.os.Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case WRITE_AP_CONFIG:
                    writeApConfiguration((WifiConfiguration) msg.obj);
                    break;
                case READ_AP_CONFIG:
                    readApConfiguration();
                    break;
                default:
                    Log.e(TAG, "Unknown command in FileReadWriteHandler: " + msg);
                    break;
            }
        }

        private static void writeApConfiguration(final WifiConfiguration config) {
            DataOutputStream out = null;
            try {
                out = new DataOutputStream(new BufferedOutputStream(
                            new FileOutputStream(AP_CONFIG_FILE)));

                out.writeInt(AP_CONFIG_FILE_VERSION);
                out.writeUTF(config.SSID);
                int authType = config.getAuthType();
                out.writeInt(authType);
                if(authType != KeyMgmt.NONE) {
                    out.writeUTF(config.preSharedKey);
                }
            } catch (IOException e) {
                Log.e(TAG, "Error writing hotspot configuration" + e);
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException e) {}
                }
            }
        }

        private static void readApConfiguration() {
            DataInputStream in = null;
            try {
                WifiConfiguration config = new WifiConfiguration();
                in = new DataInputStream(new BufferedInputStream(new FileInputStream(
                                AP_CONFIG_FILE)));

                int version = in.readInt();
                if (version != 1) {
                    Log.e(TAG, "Bad version on hotspot configuration file, set defaults");
                    setDefaultApConfiguration();
                    return;
                }
                config.SSID = in.readUTF();
                int authType = in.readInt();
                config.allowedKeyManagement.set(authType);
                if (authType != KeyMgmt.NONE) {
                    config.preSharedKey = in.readUTF();
                }
                synchronized (sApConfigLock) {
                    sApConfig = config;
                }
            } catch (IOException ignore) {
                setDefaultApConfiguration();
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException e) {}
                }
            }
        }

        /* Generate a default WPA2 based configuration with a random password.
           We are changing the Wifi Ap configuration storage from secure settings to a
           flat file accessible only by the system. A WPA2 based default configuration
           will keep the device secure after the update */
        private static void setDefaultApConfiguration() {
            WifiConfiguration config = new WifiConfiguration();
            config.SSID = sContext.getString(R.string.wifi_tether_configure_ssid_default);
            config.allowedKeyManagement.set(KeyMgmt.WPA2_PSK);
            String randomUUID = UUID.randomUUID().toString();
            //first 12 chars from xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx
            config.preSharedKey = randomUUID.substring(0, 8) + randomUUID.substring(9,13);
            setApConfiguration(config);
        }
    }
}
