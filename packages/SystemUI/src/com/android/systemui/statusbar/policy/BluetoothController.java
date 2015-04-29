/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import java.util.Set;

public interface BluetoothController {
    void addStateChangedCallback(Callback callback);
    void removeStateChangedCallback(Callback callback);

    boolean isBluetoothSupported();
    boolean isBluetoothEnabled();
    boolean isBluetoothConnected();
    boolean isBluetoothConnecting();
    String getLastDeviceName();
    void setBluetoothEnabled(boolean enabled);
    Set<PairedDevice> getPairedDevices();
    void connect(PairedDevice device);
    void disconnect(PairedDevice device);

    public interface Callback {
        void onBluetoothStateChange(boolean enabled, boolean connecting);
        void onBluetoothPairedDevicesChanged();
    }

    public static final class PairedDevice implements Comparable<PairedDevice> {
        public static int STATE_DISCONNECTED = 0;
        public static int STATE_CONNECTING = 1;
        public static int STATE_CONNECTED = 2;
        public static int STATE_DISCONNECTING = 3;

        public String id;
        public String name;
        public int state = STATE_DISCONNECTED;
        public Object tag;

        public static String stateToString(int state) {
            if (state == STATE_DISCONNECTED) return "STATE_DISCONNECTED";
            if (state == STATE_CONNECTING) return "STATE_CONNECTING";
            if (state == STATE_CONNECTED) return "STATE_CONNECTED";
            if (state == STATE_DISCONNECTING) return "STATE_DISCONNECTING";
            return "UNKNOWN";
        }

        public int compareTo(PairedDevice another) {
            return name.compareTo(another.name);
        }
    }
}
