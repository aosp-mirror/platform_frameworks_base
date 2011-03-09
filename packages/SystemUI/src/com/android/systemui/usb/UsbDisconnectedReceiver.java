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

package com.android.systemui.usb;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

// This class is used to close UsbPermissionsActivity and UsbResolverActivity
// if their device/accessory is disconnected while the dialog is still open
class UsbDisconnectedReceiver extends BroadcastReceiver {
    private final Activity mActivity;
    private UsbDevice mDevice;
    private UsbAccessory mAccessory;

    public UsbDisconnectedReceiver(Activity activity, UsbDevice device) {
       mActivity = activity;
        mDevice = device;

        IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
        activity.registerReceiver(this, filter);
    }

    public UsbDisconnectedReceiver(Activity activity, UsbAccessory accessory) {
        mActivity = activity;
        mAccessory = accessory;

        IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        activity.registerReceiver(this, filter);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
            UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device != null && device.equals(mDevice)) {
                mActivity.finish();
            }
        } else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(action)) {
            UsbAccessory accessory =
                    (UsbAccessory)intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
            if (accessory != null && accessory.equals(mAccessory)) {
                mActivity.finish();
            }
        }
    }
}