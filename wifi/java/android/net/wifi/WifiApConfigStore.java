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
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;

import com.android.internal.util.AsyncChannel;
import com.android.internal.R;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.util.UUID;

/**
 * Provides API to the WifiStateMachine for doing read/write access
 * to soft access point configuration
 */
class WifiApConfigStore extends StateMachine {

    private Context mContext;
    private static final String TAG = "WifiApConfigStore";

    private static final String AP_CONFIG_FILE = Environment.getDataDirectory() +
        "/misc/wifi/softap.conf";

    private static final int AP_CONFIG_FILE_VERSION = 1;

    private State mDefaultState = new DefaultState();
    private State mInactiveState = new InactiveState();
    private State mActiveState = new ActiveState();

    private WifiConfiguration mWifiApConfig = null;
    private AsyncChannel mReplyChannel = new AsyncChannel();

    WifiApConfigStore(Context context, Handler target) {
        super(TAG, target.getLooper());

        mContext = context;
        addState(mDefaultState);
            addState(mInactiveState, mDefaultState);
            addState(mActiveState, mDefaultState);

        setInitialState(mInactiveState);
    }

    public static WifiApConfigStore makeWifiApConfigStore(Context context, Handler target) {
        WifiApConfigStore s = new WifiApConfigStore(context, target);
        s.start();
        return s;
    }

    class DefaultState extends State {
        public boolean processMessage(Message message) {
            switch (message.what) {
                case WifiStateMachine.CMD_SET_AP_CONFIG:
                case WifiStateMachine.CMD_SET_AP_CONFIG_COMPLETED:
                    Log.e(TAG, "Unexpected message: " + message);
                    break;
                case WifiStateMachine.CMD_REQUEST_AP_CONFIG:
                    mReplyChannel.replyToMessage(message,
                            WifiStateMachine.CMD_RESPONSE_AP_CONFIG, mWifiApConfig);
                    break;
                default:
                    Log.e(TAG, "Failed to handle " + message);
                    break;
            }
            return HANDLED;
        }
    }

    class InactiveState extends State {
        public boolean processMessage(Message message) {
            switch (message.what) {
                case WifiStateMachine.CMD_SET_AP_CONFIG:
                    mWifiApConfig = (WifiConfiguration) message.obj;
                    transitionTo(mActiveState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class ActiveState extends State {
        public void enter() {
            new Thread(new Runnable() {
                public void run() {
                    writeApConfiguration(mWifiApConfig);
                    sendMessage(WifiStateMachine.CMD_SET_AP_CONFIG_COMPLETED);
                }
            }).start();
        }

        public boolean processMessage(Message message) {
            switch (message.what) {
                //TODO: have feedback to the user when we do this
                //to indicate the write is currently in progress
                case WifiStateMachine.CMD_SET_AP_CONFIG:
                    deferMessage(message);
                    break;
                case WifiStateMachine.CMD_SET_AP_CONFIG_COMPLETED:
                    transitionTo(mInactiveState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    void loadApConfiguration() {
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
            mWifiApConfig = config;
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

    Messenger getMessenger() {
        return new Messenger(getHandler());
    }

    private void writeApConfiguration(final WifiConfiguration config) {
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

    /* Generate a default WPA2 based configuration with a random password.
       We are changing the Wifi Ap configuration storage from secure settings to a
       flat file accessible only by the system. A WPA2 based default configuration
       will keep the device secure after the update */
    private void setDefaultApConfiguration() {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = mContext.getString(R.string.wifi_tether_configure_ssid_default);
        config.allowedKeyManagement.set(KeyMgmt.WPA2_PSK);
        String randomUUID = UUID.randomUUID().toString();
        //first 12 chars from xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx
        config.preSharedKey = randomUUID.substring(0, 8) + randomUUID.substring(9,13);
        sendMessage(WifiStateMachine.CMD_SET_AP_CONFIG, config);
    }
}
