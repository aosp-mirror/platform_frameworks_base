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
 * System private API for Bluetooth Headset service
 *
 * {@hide}
 */
interface IBluetoothHeadset {
    int getState();
    BluetoothDevice getCurrentHeadset();
    boolean connectHeadset(in BluetoothDevice device);
    void disconnectHeadset();
    boolean isConnected(in BluetoothDevice device);
    boolean startVoiceRecognition();
    boolean stopVoiceRecognition();
    boolean setPriority(in BluetoothDevice device, int priority);
    int getPriority(in BluetoothDevice device);
    int getBatteryUsageHint();
}
