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
import android.hardware.Usb;
import android.net.Uri;
import android.util.Log;

public class UsbReceiver extends BroadcastReceiver
{
    private static final String TAG = "UsbReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "onReceive " + intent);
        if (Usb.ACTION_USB_CAMERA_ATTACHED.equals(intent.getAction())) {
            Uri uri = intent.getData();
            intent = new Intent(context, StorageBrowser.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                // TODO - add a wrapper to Mtp.Device for this
                int id = Integer.parseInt(uri.getPathSegments().get(1));
                intent.putExtra("device", id);
                context.startActivity(intent);
            } catch (NumberFormatException e) {
                Log.e(TAG, "bad device Uri " + uri);
            }
        }
    }
}
