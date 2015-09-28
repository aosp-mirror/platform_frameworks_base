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

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.test.InstrumentationTestCase;

import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;

@RealDeviceTest
public class MtpManagerTest extends InstrumentationTestCase {
    private static final String ACTION_USB_PERMISSION =
            "com.android.mtp.USB_PERMISSION";
    private static final int TIMEOUT_MS = 1000;
    UsbManager mUsbManager;
    MtpManager mManager;
    UsbDevice mUsbDevice;
    int mRequest;

    @Override
    public void setUp() throws Exception {
        mUsbManager = getContext().getSystemService(UsbManager.class);
        mUsbDevice = findDevice();
        mManager = new MtpManager(getContext());
        mManager.openDevice(mUsbDevice.getDeviceId());
        waitForStorages(mManager, mUsbDevice.getDeviceId());
    }

    @Override
    public void tearDown() throws IOException {
        mManager.closeDevice(mUsbDevice.getDeviceId());
    }

    public void testCancelEvent() throws Exception {
        final CancellationSignal signal = new CancellationSignal();
        final Thread thread = new Thread() {
            @Override
            public void run() {
                try {
                    mManager.readEvent(mUsbDevice.getDeviceId(), signal);
                } catch (OperationCanceledException | IOException e) {
                    show(e.getMessage());
                }
            }
        };
        thread.start();
        Thread.sleep(TIMEOUT_MS);
        signal.cancel();
        thread.join(TIMEOUT_MS);
    }

    private void requestPermission(UsbDevice device) throws InterruptedException {
        if (mUsbManager.hasPermission(device)) {
            return;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                latch.countDown();
                getInstrumentation().getTargetContext().unregisterReceiver(this);
            }
        };
        getInstrumentation().getTargetContext().registerReceiver(
                receiver, new IntentFilter(ACTION_USB_PERMISSION));
        mUsbManager.requestPermission(device, PendingIntent.getBroadcast(
                getInstrumentation().getTargetContext(),
                0 /* requstCode */,
                new Intent(ACTION_USB_PERMISSION),
                0 /* flags */));
        latch.await();
        assertTrue(mUsbManager.hasPermission(device));
    }

    private UsbDevice findDevice() throws InterruptedException {
        while (true) {
            final HashMap<String,UsbDevice> devices = mUsbManager.getDeviceList();
            if (devices.size() == 0) {
                show("Wait for devices.");
                Thread.sleep(1000);
                continue;
            }
            final UsbDevice device = devices.values().iterator().next();
            requestPermission(device);
            final UsbDeviceConnection connection = mUsbManager.openDevice(device);
            if (connection == null) {
                fail("Cannot open USB connection.");
            }
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                // Since the test runs real environment, we need to call claim interface with
                // force = true to rob interfaces from other applications.
                connection.claimInterface(device.getInterface(i), true);
                connection.releaseInterface(device.getInterface(i));
            }
            connection.close();
            return device;
        }
    }

    private void waitForStorages(MtpManager manager, int deviceId) throws Exception {
        while (true) {
            if (manager.getRoots(deviceId).length == 0) {
                show("Wait for storages.");
                Thread.sleep(1000);
                continue;
            }
            return;
        }
    }

    private void show(String message) {
        if (!(getInstrumentation() instanceof TestResultInstrumentation)) {
            return;
        }
        ((TestResultInstrumentation) getInstrumentation()).show(message);
    }

    private Context getContext() {
        return getInstrumentation().getContext();
    }
}
