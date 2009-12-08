/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.bluetooth;

import android.bluetooth.BluetoothDevice;

/**
 * System private API for Bluetooth A2DP service
 *
 * {@hide}
 */
interface IBluetoothA2dp {
    boolean connectSink(in BluetoothDevice device);
    boolean disconnectSink(in BluetoothDevice device);
    boolean suspendSink(in BluetoothDevice device);
    boolean resumeSink(in BluetoothDevice device);
    BluetoothDevice[] getConnectedSinks();  // change to Set<> once AIDL supports
    BluetoothDevice[] getNonDisconnectedSinks();  // change to Set<> once AIDL supports
    int getSinkState(in BluetoothDevice device);
    boolean setSinkPriority(in BluetoothDevice device, int priority);
    int getSinkPriority(in BluetoothDevice device);
}
