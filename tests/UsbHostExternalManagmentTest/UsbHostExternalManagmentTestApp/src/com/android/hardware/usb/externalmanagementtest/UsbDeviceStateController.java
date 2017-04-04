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
package com.android.hardware.usb.externalmanagementtest;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.util.LinkedList;

import dalvik.system.CloseGuard;

public class UsbDeviceStateController {

    public interface UsbDeviceStateListener {
        void onDeviceResetComplete(UsbDevice device);
        void onAoapStartComplete(UsbDevice devie);
    }

    private static final String TAG = UsbDeviceStateController.class.getSimpleName();

    private static final int MAX_USB_STATE_CHANGE_WAIT = 5;
    private static final long USB_STATE_CHANGE_WAIT_TIMEOUT_MS = 500;

    private final Context mContext;
    private final UsbDeviceStateListener mListener;
    private final UsbManager mUsbManager;
    private final HandlerThread mHandlerThread;
    private final UsbStateHandler mHandler;
    private final UsbDeviceBroadcastReceiver mUsbStateBroadcastReceiver;
    private final CloseGuard mCloseGuard = CloseGuard.get();

    private final Object mUsbConnectionChangeWait = new Object();
    private final LinkedList<UsbDevice> mDevicesRemoved = new LinkedList<>();
    private final LinkedList<UsbDevice> mDevicesAdded = new LinkedList<>();
    private boolean mShouldQuit = false;

    public UsbDeviceStateController(Context context, UsbDeviceStateListener listener,
            UsbManager usbManager) {
        mContext = context;
        mListener = listener;
        mUsbManager = usbManager;
        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mCloseGuard.open("release");
        mHandler = new UsbStateHandler(mHandlerThread.getLooper());
        mUsbStateBroadcastReceiver = new UsbDeviceBroadcastReceiver();
    }

    public void init() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        mContext.registerReceiver(mUsbStateBroadcastReceiver, filter);
    }

    public void release() {
        mCloseGuard.close();
        mContext.unregisterReceiver(mUsbStateBroadcastReceiver);
        synchronized (mUsbConnectionChangeWait) {
            mShouldQuit = true;
            mUsbConnectionChangeWait.notifyAll();
        }
        mHandlerThread.quit();
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }

            release();
        } finally {
            super.finalize();
        }
    }

    public void startDeviceReset(UsbDevice device) {
        mHandler.requestDeviceReset(device);
    }

    public void startAoap(AoapSwitchRequest request) {
        mHandler.requestAoap(request);
    }

    private void doHandleDeviceReset(UsbDevice device) {
        boolean isInAoap = AoapInterface.isDeviceInAoapMode(device);
        UsbDevice completedDevice = null;
        if (isInAoap) {
            completedDevice = resetUsbDeviceAndConfirmModeChange(device);
        } else {
            UsbDeviceConnection conn = openConnection(device);
            if (conn == null) {
                throw new RuntimeException("cannot open conneciton for device: " + device);
            } else {
                try {
                    if (!conn.resetDevice()) {
                        throw new RuntimeException("resetDevice failed for devie: " + device);
                    } else {
                        completedDevice = device;
                    }
                } finally {
                    conn.close();
                }
            }
        }
        mListener.onDeviceResetComplete(completedDevice);
    }

    private void doHandleAoapStart(AoapSwitchRequest request) {
        UsbDevice device = request.device;
        boolean isInAoap = AoapInterface.isDeviceInAoapMode(device);
        if (isInAoap) {
            device = resetUsbDeviceAndConfirmModeChange(device);
            if (device == null) {
                mListener.onAoapStartComplete(null);
                return;
            }
        }
        UsbDeviceConnection connection = openConnection(device);
        AoapInterface.sendString(connection, AoapInterface.ACCESSORY_STRING_MANUFACTURER,
                request.manufacturer);
        AoapInterface.sendString(connection, AoapInterface.ACCESSORY_STRING_MODEL,
                request.model);
        AoapInterface.sendString(connection, AoapInterface.ACCESSORY_STRING_DESCRIPTION,
                request.description);
        AoapInterface.sendString(connection, AoapInterface.ACCESSORY_STRING_VERSION,
                request.version);
        AoapInterface.sendString(connection, AoapInterface.ACCESSORY_STRING_URI, request.uri);
        AoapInterface.sendString(connection, AoapInterface.ACCESSORY_STRING_SERIAL, request.serial);
        AoapInterface.sendAoapStart(connection);
        device = resetUsbDeviceAndConfirmModeChange(device);
        if (device == null) {
            mListener.onAoapStartComplete(null);
        }
        if (!AoapInterface.isDeviceInAoapMode(device)) {
            Log.w(TAG, "Device not in AOAP mode after switching: " + device);
            mListener.onAoapStartComplete(device);
        }
        mListener.onAoapStartComplete(device);
    }

    private UsbDevice resetUsbDeviceAndConfirmModeChange(UsbDevice device) {
        int retry = 0;
        boolean removalDetected = false;
        while (retry < MAX_USB_STATE_CHANGE_WAIT) {
            UsbDeviceConnection connNow = openConnection(device);
            if (connNow == null) {
                removalDetected = true;
                break;
            }
            connNow.resetDevice();
            connNow.close();
            synchronized (mUsbConnectionChangeWait) {
                try {
                    mUsbConnectionChangeWait.wait(USB_STATE_CHANGE_WAIT_TIMEOUT_MS);
                } catch (InterruptedException e) {
                    break;
                }
                if (mShouldQuit) {
                    return null;
                }
                if (isDeviceRemovedLocked(device)) {
                    removalDetected = true;
                    break;
                }
            }
            retry++;
        }
        if (!removalDetected) {
            Log.w(TAG, "resetDevice failed for device, device still in the same mode: " + device);
            return null;
        }
        retry = 0;
        UsbDevice newlyAttached = null;
        while (retry < MAX_USB_STATE_CHANGE_WAIT) {
            synchronized (mUsbConnectionChangeWait) {
                try {
                    mUsbConnectionChangeWait.wait(USB_STATE_CHANGE_WAIT_TIMEOUT_MS);
                } catch (InterruptedException e) {
                    break;
                }
                if (mShouldQuit) {
                    return null;
                }
                newlyAttached = checkDeviceAttachedLocked(device);
            }
            if (newlyAttached != null) {
                break;
            }
            retry++;
        }
        if (newlyAttached == null) {
            Log.w(TAG, "resetDevice failed for device, device disconnected: " + device);
            return null;
        }
        return newlyAttached;
    }

    private boolean isDeviceRemovedLocked(UsbDevice device) {
        for (UsbDevice removed : mDevicesRemoved) {
            if (UsbUtil.isDevicesMatching(device, removed)) {
                mDevicesRemoved.clear();
                return true;
            }
        }
        mDevicesRemoved.clear();
        return false;
    }

    private UsbDevice checkDeviceAttachedLocked(UsbDevice device) {
        for (UsbDevice attached : mDevicesAdded) {
            if (UsbUtil.isTheSameDevice(device, attached)) {
                mDevicesAdded.clear();
                return attached;
            }
        }
        mDevicesAdded.clear();
        return null;
    }

    public UsbDeviceConnection openConnection(UsbDevice device) {
        mUsbManager.grantPermission(device);
        return mUsbManager.openDevice(device);
    }

    private void handleUsbDeviceAttached(UsbDevice device) {
        synchronized (mUsbConnectionChangeWait) {
            mDevicesAdded.add(device);
            mUsbConnectionChangeWait.notifyAll();
        }
    }

    private void handleUsbDeviceDetached(UsbDevice device) {
        synchronized (mUsbConnectionChangeWait) {
            mDevicesRemoved.add(device);
            mUsbConnectionChangeWait.notifyAll();
        }
    }

    private class UsbStateHandler extends Handler {
        private final int MSG_RESET_DEVICE = 1;
        private final int MSG_AOAP = 2;

        private UsbStateHandler(Looper looper) {
            super(looper);
        }

        private void requestDeviceReset(UsbDevice device) {
            Message msg = obtainMessage(MSG_RESET_DEVICE, device);
            sendMessage(msg);
        }

        private void requestAoap(AoapSwitchRequest request) {
            Message msg = obtainMessage(MSG_AOAP, request);
            sendMessage(msg);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_RESET_DEVICE:
                    doHandleDeviceReset((UsbDevice) msg.obj);
                    break;
                case MSG_AOAP:
                    doHandleAoapStart((AoapSwitchRequest) msg.obj);
                    break;
            }
        }
    }

    private class UsbDeviceBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                UsbDevice device = intent.<UsbDevice>getParcelableExtra(UsbManager.EXTRA_DEVICE);
                handleUsbDeviceDetached(device);
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
                UsbDevice device = intent.<UsbDevice>getParcelableExtra(UsbManager.EXTRA_DEVICE);
                handleUsbDeviceAttached(device);
            }
        }
    }

    public static class AoapSwitchRequest {
        public final UsbDevice device;
        public final String manufacturer;
        public final String model;
        public final String description;
        public final String version;
        public final String uri;
        public final String serial;

        public AoapSwitchRequest(UsbDevice device, String manufacturer, String model,
                String description, String version, String uri, String serial) {
            this.device = device;
            this.manufacturer = manufacturer;
            this.model = model;
            this.description = description;
            this.version = version;
            this.uri = uri;
            this.serial = serial;
        }
    }
}
