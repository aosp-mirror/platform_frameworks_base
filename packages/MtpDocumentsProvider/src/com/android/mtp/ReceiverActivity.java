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
import android.content.ComponentName;
import android.content.Intent;
import android.hardware.usb.UsbManager;
import android.os.Bundle;

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
            // TODO: To obtain data URI for the attached device, we need to wait until RootScanner
            // found the device and add it to database. Set correct root URI, and use ACTION_BROWSE
            // to launch Documents UI.
            final Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setComponent(new ComponentName(
                    "com.android.documentsui", "com.android.documentsui.LauncherActivity"));
            this.startActivity(intent);
        }
        finish();
    }
}
