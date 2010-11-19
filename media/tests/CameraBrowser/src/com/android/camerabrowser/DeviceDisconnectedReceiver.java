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
import android.hardware.Usb;
import android.net.Uri;

public class DeviceDisconnectedReceiver extends BroadcastReceiver {

    private final Activity mActivity;
    private final int mDeviceID;

    public DeviceDisconnectedReceiver(Activity activity, int deviceID) {
        mActivity = activity;
        mDeviceID = deviceID;

     IntentFilter filter = new IntentFilter(Usb.ACTION_USB_CAMERA_DETACHED);
     filter.addDataScheme("content");
     activity.registerReceiver(this, filter);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // close our activity if the device it is displaying is disconnected
        Uri uri = intent.getData();
        int id = Integer.parseInt(uri.getPathSegments().get(1));
        if (id == mDeviceID) {
            mActivity.finish();
        }
    }
}