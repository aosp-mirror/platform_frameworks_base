/*
 * Copyright (c) 2013, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *   * Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *   * Redistributions in binary form must reproduce the above
 *     copyright notice, this list of conditions and the following
 *     disclaimer in the documentation and/or other materials provided
 *     with the distribution.
 *   * Neither the name of The Linux Foundation nor the names of its
 *     contributors may be used to endorse or promote products derived
 *     from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package android.bluetooth;

import android.bluetooth.IBluetoothCallback;
import android.bluetooth.IQBluetoothAdapterCallback;
import android.bluetooth.BluetoothLEServiceUuid;
import android.bluetooth.IBluetooth;
import android.bluetooth.IBluetoothStateChangeCallback;
import android.bluetooth.BluetoothDevice;
import android.os.ParcelUuid;
import android.os.ParcelFileDescriptor;

/**
 * System private API for talking with the Bluetooth service.
 *
 * {@hide}
 */
interface IQBluetooth
{
    int     startLeScanEx(in BluetoothLEServiceUuid[] services, in IQBluetoothAdapterCallback callback);
    void    stopLeScanEx(in int token);
    boolean registerLeLppRssiMonitorClient(in String address, in IQBluetoothAdapterCallback client, in boolean add);
    void writeLeLppRssiThreshold(in String address, in byte min, in byte max);
    void readLeLppRssiThreshold(in String address);
    void enableLeLppRssiMonitor(in String address, in boolean enable);
}
