/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.app.Activity;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.IOException;

/**
 * Invisible activity to receive intents.
 * To show Files app for the UsbManager.ACTION_USB_DEVICE_ATTACHED intent, the intent should be
 * received by activity. The activity has NoDisplay theme and immediately terminate after routing
 * intent to DocumentsUI.
 */
public class ReceiverActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(getIntent().getAction())) {
            final UsbDevice device = getIntent().getParcelableExtra(UsbManager.EXTRA_DEVICE);
            try {
                final MtpDocumentsProvider provider = MtpDocumentsProvider.getInstance();
                provider.openDevice(device.getDeviceId());
                final String deviceRootId = provider.getDeviceDocumentId(device.getDeviceId());
                final Uri uri = DocumentsContract.buildRootUri(
                        MtpDocumentsProvider.AUTHORITY, deviceRootId);

                final Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(uri, DocumentsContract.Root.MIME_TYPE_ITEM);
                intent.addCategory(Intent.CATEGORY_DEFAULT);
                this.startActivity(intent);
            } catch (IOException exception) {
                Log.e(MtpDocumentsProvider.TAG, "Failed to open device", exception);
            }
        }
        finish();
    }
}
