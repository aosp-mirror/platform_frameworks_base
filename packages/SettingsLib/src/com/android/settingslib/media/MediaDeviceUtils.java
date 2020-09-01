/*
 * Copyright 2018 The Android Open Source Project
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
package com.android.settingslib.media;

import android.bluetooth.BluetoothDevice;
import android.media.MediaRoute2Info;

import com.android.settingslib.bluetooth.CachedBluetoothDevice;

/**
 * MediaDeviceUtils provides utility function for MediaDevice
 */
public class MediaDeviceUtils {
    /**
     * Use CachedBluetoothDevice address to represent unique id
     *
     * @param cachedDevice the CachedBluetoothDevice
     * @return CachedBluetoothDevice address
     */
    public static String getId(CachedBluetoothDevice cachedDevice) {
        if (cachedDevice.isHearingAidDevice()) {
            return Long.toString(cachedDevice.getHiSyncId());
        }
        return cachedDevice.getAddress();
    }

    /**
     * Use BluetoothDevice address to represent unique id
     *
     * @param bluetoothDevice the BluetoothDevice
     * @return BluetoothDevice address
     */
    public static String getId(BluetoothDevice bluetoothDevice) {
        return bluetoothDevice.getAddress();
    }

    /**
     * Use MediaRoute2Info id to represent unique id
     *
     * @param route the MediaRoute2Info
     * @return MediaRoute2Info id
     */
    public static String getId(MediaRoute2Info route) {
        return route.getId();
    }
}
