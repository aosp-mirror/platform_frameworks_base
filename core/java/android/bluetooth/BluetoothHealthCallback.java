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


package android.bluetooth;

import android.os.ParcelFileDescriptor;
import android.util.Log;

/**
 * This class is used for all the {@link BluetoothHealth} callbacks.
 * @hide
 */
public abstract class BluetoothHealthCallback {

    private static final String TAG = "BluetoothHealthCallback";

    public void onHealthAppConfigurationStatusChange(BluetoothHealthAppConfiguration config,
                                                int status) {
        Log.d(TAG, "onHealthAppConfigurationStatusChange: " + config + " Status:" + status);
    }

    public void onHealthChannelStateChange(BluetoothHealthAppConfiguration config,
                                    BluetoothDevice device, int prevState, int newState,
                                    ParcelFileDescriptor fd) {
        Log.d(TAG, "onHealthChannelStateChange: " + config + " Device:" + device +
            "PrevState:" + prevState + "NewState:" + newState + "FileDescriptor:" + fd);
    }
}
