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

import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.hardware.UsbDevice;
import android.hardware.UsbManager;
import android.mtp.MtpClient;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.Log;

public class UsbReceiver extends BroadcastReceiver
{
    private static final String TAG = "UsbReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "onReceive " + intent);
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (MtpClient.isCamera(device)) {
                String deviceName = device.getDeviceName();
                Log.d(TAG, "Got camera: " + deviceName);
                intent = new Intent(context, StorageBrowser.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.putExtra("device", deviceName);
                context.startActivity(intent);
            }
        }
    }
}
