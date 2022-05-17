/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.companion.presence;

import android.annotation.NonNull;
import android.bluetooth.BluetoothDevice;

/** Utilities for working with Bluetooth and BLE devices. */
class Utils {

    /**
     * @return short String representation of {@link BluetoothDevice}.
     */
    static String btDeviceToString(@NonNull BluetoothDevice btDevice) {
        final StringBuilder sb = new StringBuilder(btDevice.getAddress());

        sb.append(" [name=");
        final String name = btDevice.getName();
        if (name != null) {
            sb.append('\'').append(name).append('\'');
        } else {
            sb.append("null");
        }

        final String alias = btDevice.getAlias();
        if (alias != null) {
            sb.append(", alias='").append(alias).append("'");
        }

        return sb.append(']').toString();
    }

    private Utils() {
    }
}
