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

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.nsd.NsdManager.DiscoveryListener;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;

import com.android.hardware.usb.externalmanagementtest.UsbDeviceStateController.AoapSwitchRequest;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

public class UsbHostManagementActivity extends Activity
        implements UsbDeviceStateController.UsbDeviceStateListener {

    private static final String TAG = UsbHostManagementActivity.class.getSimpleName();

    private static final String AOAP_APP_PACKAGE_NAME = "com.android.hardware.usb.aoaphosttest";
    private static final String AOAP_APP_ACTIVITY_NAME =
            "com.android.hardware.usb.aoaphosttest.UsbAoapHostTestActivity";

    private TextView mDeviceInfoText;
    private Button mStartAoapButton;
    private Button mStartAoapActivityButton;
    private TextView mAoapAppLog;
    private Button mResetUsbButton;
    private Button mFinishButton;
    private UsbDevice mUsbDevice = null;
    private final UsbDeviceConnectionListener mConnectionListener =
            new UsbDeviceConnectionListener();
    private UsbDeviceStateController mUsbDeviceStateController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.host_management);
        mDeviceInfoText = findViewById(R.id.device_info_text);
        mStartAoapButton = findViewById(R.id.start_aoap_button);
        mStartAoapActivityButton = findViewById(R.id.start_aoap_activity_button);
        mAoapAppLog = findViewById(R.id.aoap_apps_text);
        mResetUsbButton = findViewById(R.id.reset_button);
        mFinishButton = findViewById(R.id.finish_button);

        Intent intent = getIntent();
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            mUsbDevice = intent.<UsbDevice>getParcelableExtra(UsbManager.EXTRA_DEVICE);
        }
        UsbManager usbManager = (UsbManager)getSystemService(Context.USB_SERVICE);
        if (mUsbDevice == null) {
            LinkedList<UsbDevice> devices = UsbUtil.findAllPossibleAndroidDevices(usbManager);
            if (devices.size() > 0) {
                mUsbDevice = devices.getLast();
            }
        }
        updateDevice(mUsbDevice);

        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(mConnectionListener, filter);

        mStartAoapButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mUsbDevice == null) {
                    return;
                }
                startAoap(mUsbDevice);
            }
        });
        mStartAoapActivityButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mUsbDevice == null) {
                    return;
                }
                startAoapActivity(mUsbDevice);
            }
        });
        mResetUsbButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mUsbDevice == null) {
                    return;
                }
                resetDevice(mUsbDevice);
            }
        });
        mFinishButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        mUsbDeviceStateController = new UsbDeviceStateController(this, this, usbManager);
        mUsbDeviceStateController.init();
    }


    private void startAoap(UsbDevice device) {
        AoapSwitchRequest request = new AoapSwitchRequest(device, "Android", "AOAP Test App", "",
                "1.0", "", "");
        mUsbDeviceStateController.startAoap(request);
    }

    private void startAoapActivity(UsbDevice device) {
        if (!AoapInterface.isDeviceInAoapMode(device)) {
            Log.w(TAG, "Device not in AOAP mode:" + device);
            return;
        }
        PackageManager pm = getPackageManager();
        PackageInfo pi = null;
        try {
            pi = pm.getPackageInfo(AOAP_APP_PACKAGE_NAME, 0);
        } catch (NameNotFoundException e) {
            Log.w(TAG, "AOAP Test app not found:" + AOAP_APP_PACKAGE_NAME);
        }
        int uid = pi.applicationInfo.uid;
        UsbManager usbManager = (UsbManager)getSystemService(Context.USB_SERVICE);
        usbManager.grantPermission(device, uid);
        Intent intent = new Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        intent.putExtra(UsbManager.EXTRA_DEVICE, device);
        intent.setComponent(new ComponentName(AOAP_APP_PACKAGE_NAME, AOAP_APP_ACTIVITY_NAME));
        startActivity(intent);
    }

    private void resetDevice(UsbDevice device) {
        Log.i(TAG, "resetDevice");
        mUsbDeviceStateController.startDeviceReset(device);
    }

    private void dumpUsbDevices() {
        UsbManager usbManager = (UsbManager)getSystemService(Context.USB_SERVICE);
        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
        StringBuilder sb = new StringBuilder();
        sb.append("Usb devices\n");
        for (UsbDevice device : devices.values()) {
            sb.append(device.toString() + "\n");
        }
        Log.i(TAG, sb.toString());
    }

    @Override
    protected void onDestroy() {
        Log.i(TAG, "onDestroy");
        super.onDestroy();
        unregisterReceiver(mConnectionListener);
        mUsbDeviceStateController.release();
    }

    private void handleUsbDeviceAttached(UsbDevice device) {
        boolean deviceReplaced = false;
        if (mUsbDevice == null) {
            deviceReplaced = true;
        } else {
            UsbManager usbManager = (UsbManager)getSystemService(Context.USB_SERVICE);
            if (!UsbUtil.isDeviceConnected(usbManager, mUsbDevice)) {
                deviceReplaced = true;
            }
        }
        if (deviceReplaced) {
            Log.i(TAG, "device attached:" + device);
            updateDevice(device);
        }
    }

    private void handleUsbDeviceDetached(UsbDevice device) {
        if (mUsbDevice != null && UsbUtil.isDevicesMatching(mUsbDevice, device)) {
            Log.i(TAG, "device removed ");
            updateDevice(device);
        }
    }

    private void updateDevice(UsbDevice device) {
        mUsbDevice = device;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mUsbDevice == null) {
                    mDeviceInfoText.setText("disconnected");
                } else {
                    mDeviceInfoText.setText(mUsbDevice.toString());
                }
            }
        });
    }

    @Override
    public void onDeviceResetComplete(UsbDevice device) {
        updateDevice(device);
    }


    @Override
    public void onAoapStartComplete(UsbDevice device) {
        updateDevice(device);
    }

    private class UsbDeviceConnectionListener extends BroadcastReceiver {
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
}
