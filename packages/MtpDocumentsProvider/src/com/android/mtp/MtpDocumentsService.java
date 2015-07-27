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

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.IBinder;
import android.util.Log;

import java.io.IOException;

/**
 * Service to manage lifetime of DocumentsProvider's process.
 * The service prevents the system from killing the process that holds USB connections. The service
 * starts to run when the first MTP device is opened, and stops when the last MTP device is closed.
 */
public class MtpDocumentsService extends Service {
    static final String ACTION_OPEN_DEVICE = "com.android.mtp.action.ACTION_OPEN_DEVICE";
    static final String EXTRA_DEVICE = "device";

    Receiver mReceiver;

    @Override
    public IBinder onBind(Intent intent) {
        // The service is used via intents.
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        final IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
        mReceiver = new Receiver();
        registerReceiver(mReceiver, filter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            // If intent is null, the service was restarted.
            // TODO: Recover opened devices here.
            return START_STICKY;
        }
        if (intent.getAction().equals(ACTION_OPEN_DEVICE)) {
            final UsbDevice device = intent.<UsbDevice>getParcelableExtra(EXTRA_DEVICE);
            try {
                final MtpDocumentsProvider provider = MtpDocumentsProvider.getInstance();
                provider.openDevice(device.getDeviceId());
                return START_STICKY;
            } catch (IOException error) {
                Log.d(MtpDocumentsProvider.TAG, error.getMessage());
            }
        } else {
            Log.d(MtpDocumentsProvider.TAG, "Received unknown intent action.");
        }
        stopSelfIfNeeded();
        return Service.START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        final MtpDocumentsProvider provider = MtpDocumentsProvider.getInstance();
        provider.closeAllDevices();
        unregisterReceiver(mReceiver);
        mReceiver = null;
        super.onDestroy();
    }

    private void stopSelfIfNeeded() {
        final MtpDocumentsProvider provider = MtpDocumentsProvider.getInstance();
        if (!provider.hasOpenedDevices()) {
            stopSelf();
        }
    }

    private class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                final UsbDevice device =
                        (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                final MtpDocumentsProvider provider = MtpDocumentsProvider.getInstance();
                try {
                    provider.closeDevice(device.getDeviceId());
                } catch (IOException error) {
                    Log.d(MtpDocumentsProvider.TAG, error.getMessage());
                }
                stopSelfIfNeeded();
            }
        }
    }
}
