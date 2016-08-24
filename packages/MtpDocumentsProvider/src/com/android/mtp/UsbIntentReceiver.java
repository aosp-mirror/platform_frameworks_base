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
import android.util.Log;

import java.io.IOException;

public class UsbIntentReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final UsbDevice device = intent.getExtras().getParcelable(UsbManager.EXTRA_DEVICE);
        switch (intent.getAction()) {
            case UsbManager.ACTION_USB_DEVICE_ATTACHED:
                MtpDocumentsProvider.getInstance().resumeRootScanner();
                break;
            case UsbManager.ACTION_USB_DEVICE_DETACHED:
                try {
                    MtpDocumentsProvider.getInstance().closeDevice(device.getDeviceId());
                } catch (IOException | InterruptedException e) {
                    Log.e(MtpDocumentsProvider.TAG, "Failed to close device", e);
                }
                break;
        }
    }
}
