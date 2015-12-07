/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.mtp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;

public class UsbIntentReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final UsbDevice device = intent.getExtras().getParcelable(UsbManager.EXTRA_DEVICE);
        switch (intent.getAction()) {
            case UsbManager.ACTION_USB_DEVICE_ATTACHED:
                startService(context, MtpDocumentsService.ACTION_OPEN_DEVICE, device);
                break;
            case UsbManager.ACTION_USB_DEVICE_DETACHED:
                startService(context, MtpDocumentsService.ACTION_CLOSE_DEVICE, device);
                break;
        }
    }

    private void startService(Context context, String action, UsbDevice device) {
        final Intent intent = new Intent(action, Uri.EMPTY, context, MtpDocumentsService.class);
        intent.putExtra(MtpDocumentsService.EXTRA_DEVICE, device);
        context.startService(intent);
    }
}
