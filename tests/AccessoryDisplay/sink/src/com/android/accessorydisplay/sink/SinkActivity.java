/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.accessorydisplay.sink;

import com.android.accessorydisplay.common.Logger;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Map;

public class SinkActivity extends Activity {
    private static final String TAG = "SinkActivity";

    private static final String ACTION_USB_DEVICE_PERMISSION =
            "com.android.accessorydisplay.sink.ACTION_USB_DEVICE_PERMISSION";

    private static final String MANUFACTURER = "Android";
    private static final String MODEL = "Accessory Display";
    private static final String DESCRIPTION = "Accessory Display Sink Test Application";
    private static final String VERSION = "1.0";
    private static final String URI = "http://www.android.com/";
    private static final String SERIAL = "0000000012345678";

    private static final int MULTITOUCH_DEVICE_ID = 0;
    private static final int MULTITOUCH_REPORT_ID = 1;
    private static final int MULTITOUCH_MAX_CONTACTS = 1;

    private UsbManager mUsbManager;
    private DeviceReceiver mReceiver;
    private TextView mLogTextView;
    private TextView mFpsTextView;
    private SurfaceView mSurfaceView;
    private Logger mLogger;

    private boolean mConnected;
    private int mProtocolVersion;
    private UsbDevice mDevice;
    private UsbInterface mAccessoryInterface;
    private UsbDeviceConnection mAccessoryConnection;
    private UsbEndpoint mControlEndpoint;
    private UsbAccessoryBulkTransport mTransport;

    private boolean mAttached;
    private DisplaySinkService mDisplaySinkService;

    private final ByteBuffer mHidBuffer = ByteBuffer.allocate(4096);
    private UsbHid.Multitouch mMultitouch;
    private boolean mMultitouchEnabled;
    private UsbHid.Multitouch.Contact[] mMultitouchContacts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mUsbManager = (UsbManager)getSystemService(Context.USB_SERVICE);

        setContentView(R.layout.sink_activity);

        mLogTextView = findViewById(R.id.logTextView);
        mLogTextView.setMovementMethod(ScrollingMovementMethod.getInstance());
        mLogger = new TextLogger();

        mFpsTextView = findViewById(R.id.fpsTextView);

        mSurfaceView = findViewById(R.id.surfaceView);
        mSurfaceView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                sendHidTouch(event);
                return true;
            }
        });

        mLogger.log("Waiting for accessory display source to be attached to USB...");

        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(ACTION_USB_DEVICE_PERMISSION);
        mReceiver = new DeviceReceiver();
        registerReceiver(mReceiver, filter);

        Intent intent = getIntent();
        if (intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
            UsbDevice device = intent.<UsbDevice>getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device != null) {
                onDeviceAttached(device);
            }
        } else {
            Map<String, UsbDevice> devices = mUsbManager.getDeviceList();
            if (devices != null) {
                for (UsbDevice device : devices.values()) {
                    onDeviceAttached(device);
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        unregisterReceiver(mReceiver);
    }

    private void onDeviceAttached(UsbDevice device) {
        mLogger.log("USB device attached: " + device);
        if (!mConnected) {
            connect(device);
        }
    }

    private void onDeviceDetached(UsbDevice device) {
        mLogger.log("USB device detached: " + device);
        if (mConnected && device.equals(mDevice)) {
            disconnect();
        }
    }

    private void connect(UsbDevice device) {
        if (mConnected) {
            disconnect();
        }

        // Check whether we have permission to access the device.
        if (!mUsbManager.hasPermission(device)) {
            mLogger.log("Prompting the user for access to the device.");
            Intent intent = new Intent(ACTION_USB_DEVICE_PERMISSION);
            intent.setPackage(getPackageName());
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    this, 0, intent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_MUTABLE);
            mUsbManager.requestPermission(device, pendingIntent);
            return;
        }

        // Claim the device.
        UsbDeviceConnection conn = mUsbManager.openDevice(device);
        if (conn == null) {
            mLogger.logError("Could not obtain device connection.");
            return;
        }
        UsbInterface iface = device.getInterface(0);
        UsbEndpoint controlEndpoint = iface.getEndpoint(0);
        if (!conn.claimInterface(iface, true)) {
            mLogger.logError("Could not claim interface.");
            return;
        }
        try {
            // If already in accessory mode, then connect to the device.
            if (isAccessory(device)) {
                mLogger.log("Connecting to accessory...");

                int protocolVersion = getProtocol(conn);
                if (protocolVersion < 1) {
                    mLogger.logError("Device does not support accessory protocol.");
                    return;
                }
                mLogger.log("Protocol version: " + protocolVersion);

                // Setup bulk endpoints.
                UsbEndpoint bulkIn = null;
                UsbEndpoint bulkOut = null;
                for (int i = 0; i < iface.getEndpointCount(); i++) {
                    UsbEndpoint ep = iface.getEndpoint(i);
                    if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                        if (bulkIn == null) {
                            mLogger.log(String.format("Bulk IN endpoint: %d", i));
                            bulkIn = ep;
                        }
                    } else {
                        if (bulkOut == null) {
                            mLogger.log(String.format("Bulk OUT endpoint: %d", i));
                            bulkOut = ep;
                        }
                    }
                }
                if (bulkIn == null || bulkOut == null) {
                    mLogger.logError("Unable to find bulk endpoints");
                    return;
                }

                mLogger.log("Connected");
                mConnected = true;
                mDevice = device;
                mProtocolVersion = protocolVersion;
                mAccessoryInterface = iface;
                mAccessoryConnection = conn;
                mControlEndpoint = controlEndpoint;
                mTransport = new UsbAccessoryBulkTransport(mLogger, conn, bulkIn, bulkOut);
                if (mProtocolVersion >= 2) {
                    registerHid();
                }
                startServices();
                mTransport.startReading();
                return;
            }

            // Do accessory negotiation.
            mLogger.log("Attempting to switch device to accessory mode...");

            // Send get protocol.
            int protocolVersion = getProtocol(conn);
            if (protocolVersion < 1) {
                mLogger.logError("Device does not support accessory protocol.");
                return;
            }
            mLogger.log("Protocol version: " + protocolVersion);

            // Send identifying strings.
            sendString(conn, UsbAccessoryConstants.ACCESSORY_STRING_MANUFACTURER, MANUFACTURER);
            sendString(conn, UsbAccessoryConstants.ACCESSORY_STRING_MODEL, MODEL);
            sendString(conn, UsbAccessoryConstants.ACCESSORY_STRING_DESCRIPTION, DESCRIPTION);
            sendString(conn, UsbAccessoryConstants.ACCESSORY_STRING_VERSION, VERSION);
            sendString(conn, UsbAccessoryConstants.ACCESSORY_STRING_URI, URI);
            sendString(conn, UsbAccessoryConstants.ACCESSORY_STRING_SERIAL, SERIAL);

            // Send start.
            // The device should re-enumerate as an accessory.
            mLogger.log("Sending accessory start request.");
            int len = conn.controlTransfer(UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_VENDOR,
                    UsbAccessoryConstants.ACCESSORY_START, 0, 0, null, 0, 10000);
            if (len != 0) {
                mLogger.logError("Device refused to switch to accessory mode.");
            } else {
                mLogger.log("Waiting for device to re-enumerate...");
            }
        } finally {
            if (!mConnected) {
                conn.releaseInterface(iface);
            }
        }
    }

    private void disconnect() {
        mLogger.log("Disconnecting from device: " + mDevice);
        stopServices();
        unregisterHid();

        mLogger.log("Disconnected.");
        mConnected = false;
        mDevice = null;
        mAccessoryConnection = null;
        mAccessoryInterface = null;
        mControlEndpoint = null;
        if (mTransport != null) {
            mTransport.close();
            mTransport = null;
        }
    }

    private void registerHid() {
        mLogger.log("Registering HID multitouch device.");

        mMultitouch = new UsbHid.Multitouch(MULTITOUCH_REPORT_ID, MULTITOUCH_MAX_CONTACTS,
                mSurfaceView.getWidth(), mSurfaceView.getHeight());

        mHidBuffer.clear();
        mMultitouch.generateDescriptor(mHidBuffer);
        mHidBuffer.flip();

        mLogger.log("HID descriptor size: " + mHidBuffer.limit());
        mLogger.log("HID report size: " + mMultitouch.getReportSize());

        final int maxPacketSize = mControlEndpoint.getMaxPacketSize();
        mLogger.log("Control endpoint max packet size: " + maxPacketSize);
        if (mMultitouch.getReportSize() > maxPacketSize) {
            mLogger.logError("HID report is too big for this accessory.");
            return;
        }

        int len = mAccessoryConnection.controlTransfer(
                UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_VENDOR,
                UsbAccessoryConstants.ACCESSORY_REGISTER_HID,
                MULTITOUCH_DEVICE_ID, mHidBuffer.limit(), null, 0, 10000);
        if (len != 0) {
            mLogger.logError("Device rejected ACCESSORY_REGISTER_HID request.");
            return;
        }

        while (mHidBuffer.hasRemaining()) {
            int position = mHidBuffer.position();
            int count = Math.min(mHidBuffer.remaining(), maxPacketSize);
            len = mAccessoryConnection.controlTransfer(
                    UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_VENDOR,
                    UsbAccessoryConstants.ACCESSORY_SET_HID_REPORT_DESC,
                    MULTITOUCH_DEVICE_ID, 0,
                    mHidBuffer.array(), position, count, 10000);
            if (len != count) {
                mLogger.logError("Device rejected ACCESSORY_SET_HID_REPORT_DESC request.");
                return;
            }
            mHidBuffer.position(position + count);
        }

        mLogger.log("HID device registered.");

        mMultitouchEnabled = true;
        if (mMultitouchContacts == null) {
            mMultitouchContacts = new UsbHid.Multitouch.Contact[MULTITOUCH_MAX_CONTACTS];
            for (int i = 0; i < MULTITOUCH_MAX_CONTACTS; i++) {
                mMultitouchContacts[i] = new UsbHid.Multitouch.Contact();
            }
        }
    }

    private void unregisterHid() {
        mMultitouch = null;
        mMultitouchContacts = null;
        mMultitouchEnabled = false;
    }

    private void sendHidTouch(MotionEvent event) {
        if (mMultitouchEnabled) {
            mLogger.log("Sending touch event: " + event);

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE: {
                    final int pointerCount =
                            Math.min(MULTITOUCH_MAX_CONTACTS, event.getPointerCount());
                    final int historySize = event.getHistorySize();
                    for (int p = 0; p < pointerCount; p++) {
                        mMultitouchContacts[p].id = event.getPointerId(p);
                    }
                    for (int h = 0; h < historySize; h++) {
                        for (int p = 0; p < pointerCount; p++) {
                            mMultitouchContacts[p].x = (int)event.getHistoricalX(p, h);
                            mMultitouchContacts[p].y = (int)event.getHistoricalY(p, h);
                        }
                        sendHidTouchReport(pointerCount);
                    }
                    for (int p = 0; p < pointerCount; p++) {
                        mMultitouchContacts[p].x = (int)event.getX(p);
                        mMultitouchContacts[p].y = (int)event.getY(p);
                    }
                    sendHidTouchReport(pointerCount);
                    break;
                }

                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_UP:
                    sendHidTouchReport(0);
                    break;
            }
        }
    }

    private void sendHidTouchReport(int contactCount) {
        mHidBuffer.clear();
        mMultitouch.generateReport(mHidBuffer, mMultitouchContacts, contactCount);
        mHidBuffer.flip();

        int count = mHidBuffer.limit();
        int len = mAccessoryConnection.controlTransfer(
                UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_VENDOR,
                UsbAccessoryConstants.ACCESSORY_SEND_HID_EVENT,
                MULTITOUCH_DEVICE_ID, 0,
                mHidBuffer.array(), 0, count, 10000);
        if (len != count) {
            mLogger.logError("Device rejected ACCESSORY_SEND_HID_EVENT request.");
            return;
        }
    }

    private void startServices() {
        mDisplaySinkService = new DisplaySinkService(this, mTransport,
                getResources().getConfiguration().densityDpi);
        mDisplaySinkService.start();

        if (mAttached) {
            mDisplaySinkService.setSurfaceView(mSurfaceView);
        }
    }

    private void stopServices() {
        if (mDisplaySinkService != null) {
            mDisplaySinkService.stop();
            mDisplaySinkService = null;
        }
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        mAttached = true;
        if (mDisplaySinkService != null) {
            mDisplaySinkService.setSurfaceView(mSurfaceView);
        }
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        mAttached = false;
        if (mDisplaySinkService != null) {
            mDisplaySinkService.setSurfaceView(null);
        }
    }

    private int getProtocol(UsbDeviceConnection conn) {
        byte buffer[] = new byte[2];
        int len = conn.controlTransfer(
                UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_VENDOR,
                UsbAccessoryConstants.ACCESSORY_GET_PROTOCOL, 0, 0, buffer, 2, 10000);
        if (len != 2) {
            return -1;
        }
        return (buffer[1] << 8) | buffer[0];
    }

    private void sendString(UsbDeviceConnection conn, int index, String string) {
        byte[] buffer = (string + "\0").getBytes();
        int len = conn.controlTransfer(UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_VENDOR,
                UsbAccessoryConstants.ACCESSORY_SEND_STRING, 0, index,
                buffer, buffer.length, 10000);
        if (len != buffer.length) {
            mLogger.logError("Failed to send string " + index + ": \"" + string + "\"");
        } else {
            mLogger.log("Sent string " + index + ": \"" + string + "\"");
        }
    }

    private static boolean isAccessory(UsbDevice device) {
        final int vid = device.getVendorId();
        final int pid = device.getProductId();
        return vid == UsbAccessoryConstants.USB_ACCESSORY_VENDOR_ID
                && (pid == UsbAccessoryConstants.USB_ACCESSORY_PRODUCT_ID
                        || pid == UsbAccessoryConstants.USB_ACCESSORY_ADB_PRODUCT_ID);
    }

    class TextLogger extends Logger {
        @Override
        public void log(final String message) {
            Log.d(TAG, message);

            mLogTextView.post(new Runnable() {
                @Override
                public void run() {
                    mLogTextView.append(message);
                    mLogTextView.append("\n");
                }
            });
        }
    }

    class DeviceReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            UsbDevice device = intent.<UsbDevice>getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device != null) {
                String action = intent.getAction();
                if (action.equals(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
                    onDeviceAttached(device);
                } else if (action.equals(UsbManager.ACTION_USB_DEVICE_DETACHED)) {
                    onDeviceDetached(device);
                } else if (action.equals(ACTION_USB_DEVICE_PERMISSION)) {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        mLogger.log("Device permission granted: " + device);
                        onDeviceAttached(device);
                    } else {
                        mLogger.logError("Device permission denied: " + device);
                    }
                }
            }
        }
    }
}
