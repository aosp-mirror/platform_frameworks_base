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

package com.android.camerabrowser;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Log;

public class DeviceDisconnectedReceiver extends BroadcastReceiver {

    private static final String TAG = "DeviceDisconnectedReceiver";

    private final Activity mActivity;
    private final String mDeviceName;

    public DeviceDisconnectedReceiver(Activity activity, String deviceName) {
        mActivity = activity;
        mDeviceName = deviceName;

        IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
        activity.registerReceiver(this, filter);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        String deviceName = device.getDeviceName();
        Log.d(TAG, "ACTION_USB_DEVICE_DETACHED " + deviceName);

        // close our activity if the device it is displaying is disconnected
        if (deviceName.equals(mDeviceName)) {
            mActivity.finish();
        }
    }
}