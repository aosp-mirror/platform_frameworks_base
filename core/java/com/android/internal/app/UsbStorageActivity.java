/*
 * Copyright (C) 2007 Google Inc.
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

package com.android.internal.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageEventListener;
import android.os.storage.StorageResultCode;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.widget.ImageView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.view.View;
import android.util.Log;

/**
 * This activity is shown to the user for him/her to enable USB mass storage
 * on-demand (that is, when the USB cable is connected). It uses the alert
 * dialog style. It will be launched from a notification.
 */
public class UsbStorageActivity extends Activity {
    private static final String TAG = "UsbStorageActivity";
    private Button mMountButton;
    private Button mUnmountButton;
    private TextView mBanner;
    private TextView mMessage;
    private ImageView mIcon;
    private StorageManager mStorageManager = null;

    /** Used to detect when the USB cable is unplugged, so we can call finish() */
    private BroadcastReceiver mBatteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction() == Intent.ACTION_BATTERY_CHANGED) {
                handleBatteryChanged(intent);
            }
        }
    };

    private StorageEventListener mStorageListener = new StorageEventListener() {
        @Override
        public void onStorageStateChanged(String path, String oldState, String newState) {
            if (newState.equals(Environment.MEDIA_SHARED)) {
                switchDisplay(true);
            } else {
                switchDisplay(false);
            }
        }
    };
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (mStorageManager == null) {
            mStorageManager = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
            if (mStorageManager == null) {
                Log.w(TAG, "Failed to get StorageManager");
            }
            mStorageManager.registerListener(mStorageListener);
        }

        setTitle(getString(com.android.internal.R.string.usb_storage_activity_title));

        setContentView(com.android.internal.R.layout.usb_storage_activity);

        mIcon = (ImageView) findViewById(com.android.internal.R.id.icon);
        mBanner = (TextView) findViewById(com.android.internal.R.id.banner);
        mMessage = (TextView) findViewById(com.android.internal.R.id.message);

        mMountButton = (Button) findViewById(com.android.internal.R.id.mount_button);
        mMountButton.setOnClickListener(
            new View.OnClickListener() { 
                 public void onClick(View v) {
                     int rc = mStorageManager.enableUsbMassStorage();
                     if (rc != StorageResultCode.OperationSucceeded) {
                         Log.e(TAG, String.format("UMS enable failed (%d)", rc));
                         showSharingError();
                     }
                 }
            });

        mUnmountButton = (Button) findViewById(com.android.internal.R.id.unmount_button);
        mUnmountButton.setOnClickListener(
            new View.OnClickListener() { 
                 public void onClick(View v) {
                     int rc = mStorageManager.disableUsbMassStorage();
                     if (rc != StorageResultCode.OperationSucceeded) {
                         Log.e(TAG, String.format("UMS disable failed (%d)", rc));
                         showStoppingError();
                     }
                 }
            });
    }

    private void switchDisplay(boolean usbStorageInUse) {
        if (usbStorageInUse) {
            mUnmountButton.setVisibility(View.VISIBLE);
            mMountButton.setVisibility(View.GONE);
            mIcon.setImageResource(com.android.internal.R.drawable.usb_android_connected);
            mBanner.setText(com.android.internal.R.string.usb_storage_stop_title);
            mMessage.setText(com.android.internal.R.string.usb_storage_stop_message);
        } else {
            mUnmountButton.setVisibility(View.GONE);
            mMountButton.setVisibility(View.VISIBLE);
            mIcon.setImageResource(com.android.internal.R.drawable.usb_android);
            mBanner.setText(com.android.internal.R.string.usb_storage_title);
            mMessage.setText(com.android.internal.R.string.usb_storage_message);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        registerReceiver(mBatteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        try {
            switchDisplay(mStorageManager.isUsbMassStorageEnabled());
        } catch (Exception ex) {
            Log.e(TAG, "Failed to read UMS enable state", ex);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        
        unregisterReceiver(mBatteryReceiver);
    }

    private void handleBatteryChanged(Intent intent) {
        int pluggedType = intent.getIntExtra("plugged", 0);
        if (pluggedType == 0) {
            // It was disconnected from the plug, so finish
            finish();
        }
    }
    
    private void showSharingError() {
        Toast.makeText(this, com.android.internal.R.string.usb_storage_error_message,
                Toast.LENGTH_LONG).show();
    }
    
    private void showStoppingError() {
        Toast.makeText(this, com.android.internal.R.string.usb_storage_stop_error_message,
                Toast.LENGTH_LONG).show();
    }

}
