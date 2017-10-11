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
package com.android.hardware.usb.aoaphosttest;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;

import libcore.io.IoUtils;

public class UsbAoapHostTestActivity extends Activity {

    private static final String TAG = UsbAoapHostTestActivity.class.getSimpleName();

    private UsbManager mUsbManager;
    private UsbStateReceiver mReceiver;
    private UsbDevice mUsbDevice;
    private UsbDeviceConnection mUsbConnection;
    private ReaderThread mReaderThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.host);

        mUsbManager = (UsbManager)getSystemService(Context.USB_SERVICE);
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        mReceiver = new UsbStateReceiver();
        registerReceiver(mReceiver, filter);

        Intent intent = getIntent();
        if (intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
            mUsbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            mUsbConnection = mUsbManager.openDevice(mUsbDevice);
            mReaderThread = new ReaderThread(mUsbDevice, mUsbConnection);
            mReaderThread.start();
        } else {
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mReceiver);
        if (mUsbConnection != null) {
            mUsbConnection.close();
        }
        if (mReaderThread != null) {
            mReaderThread.requestToQuit();
            try {
                mReaderThread.join(1000);
            } catch (InterruptedException e) {
            }
            if (mReaderThread.isAlive()) { // reader thread stuck
                throw new RuntimeException("ReaderThread still alive");
            }
        }
    }

    private static boolean isDevicesMatching(UsbDevice l, UsbDevice r) {
        if (l.getVendorId() == r.getVendorId() && l.getProductId() == r.getProductId() &&
                TextUtils.equals(l.getSerialNumber(), r.getSerialNumber())) {
            return true;
        }
        return false;
    }

    private class ReaderThread extends Thread {
        private boolean mShouldQuit = false;
        private final UsbDevice mDevice;
        private final UsbDeviceConnection mUsbConnection;
        private final UsbEndpoint mBulkIn;
        private final UsbEndpoint mBulkOut;
        private final byte[] mBuffer = new byte[16384];

        private ReaderThread(UsbDevice device, UsbDeviceConnection conn) {
            super("AOAP");
            mDevice = device;
            mUsbConnection = conn;
            UsbInterface iface = mDevice.getInterface(0);
            // Setup bulk endpoints.
            UsbEndpoint bulkIn = null;
            UsbEndpoint bulkOut = null;
            for (int i = 0; i < iface.getEndpointCount(); i++) {
                UsbEndpoint ep = iface.getEndpoint(i);
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                    if (bulkIn == null) {
                        bulkIn = ep;
                    }
                } else {
                    if (bulkOut == null) {
                        bulkOut = ep;
                    }
                }
            }
            if (bulkIn == null || bulkOut == null) {
                throw new IllegalStateException("Unable to find bulk endpoints");
            }
            mBulkIn = bulkIn;
            mBulkOut = bulkOut;
        }

        private synchronized void requestToQuit() {
            mShouldQuit = true;
        }

        private synchronized boolean shouldQuit() {
            return mShouldQuit;
        }

        @Override
        public void run() {
            while (!shouldQuit()) {
                int read = mUsbConnection.bulkTransfer(mBulkIn, mBuffer, mBuffer.length,
                        Integer.MAX_VALUE);
                if (read < 0) {
                    throw new RuntimeException("bulkTransfer failed, read = " + read);
                }
            }
        }
    }

    private class UsbStateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (isDevicesMatching(mUsbDevice, device)) {
                    finish();
                }
            }
        }
    }
}
