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

import androidx.mediarouter.media.MediaRouter;

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
        return cachedDevice.getAddress();
    }

    /**
     * Use RouteInfo id to represent unique id
     *
     * @param route the RouteInfo
     * @return RouteInfo id
     */
    public static String getId(MediaRouter.RouteInfo route) {
        return route.getId();
    }
}
