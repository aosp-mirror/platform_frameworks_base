/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.systemui.usb;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.PermissionChecker;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.usb.IUsbManager;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.TextView;

import com.android.internal.app.AlertActivity;
import com.android.internal.app.AlertController;
import com.android.systemui.R;

public class UsbPermissionActivity extends AlertActivity
        implements DialogInterface.OnClickListener, CheckBox.OnCheckedChangeListener {

    private static final String TAG = "UsbPermissionActivity";

    private CheckBox mAlwaysUse;
    private TextView mClearDefaultHint;
    private UsbDevice mDevice;
    private UsbAccessory mAccessory;
    private PendingIntent mPendingIntent;
    private String mPackageName;
    private int mUid;
    private boolean mPermissionGranted;
    private UsbDisconnectedReceiver mDisconnectedReceiver;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        getWindow().addPrivateFlags(
                WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);
        Intent intent = getIntent();
        mDevice = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        mAccessory = (UsbAccessory)intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
        mPendingIntent = (PendingIntent)intent.getParcelableExtra(Intent.EXTRA_INTENT);
        mUid = intent.getIntExtra(Intent.EXTRA_UID, -1);
        mPackageName = intent.getStringExtra(UsbManager.EXTRA_PACKAGE);
        boolean canBeDefault = intent.getBooleanExtra(UsbManager.EXTRA_CAN_BE_DEFAULT, false);

        PackageManager packageManager = getPackageManager();
        ApplicationInfo aInfo;
        try {
            aInfo = packageManager.getApplicationInfo(mPackageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "unable to look up package name", e);
            finish();
            return;
        }
        String appName = aInfo.loadLabel(packageManager).toString();

        final AlertController.AlertParams ap = mAlertParams;
        ap.mTitle = appName;
        boolean useRecordWarning = false;
        if (mDevice == null) {
            // Accessory Case

            ap.mMessage = getString(R.string.usb_accessory_permission_prompt, appName,
                    mAccessory.getDescription());
            mDisconnectedReceiver = new UsbDisconnectedReceiver(this, mAccessory);
        } else {
            boolean hasRecordPermission =
                    PermissionChecker.checkPermissionForPreflight(
                            this, android.Manifest.permission.RECORD_AUDIO, -1, aInfo.uid,
                            mPackageName)
                            == android.content.pm.PackageManager.PERMISSION_GRANTED;
            boolean isAudioCaptureDevice = mDevice.getHasAudioCapture();
            useRecordWarning = isAudioCaptureDevice && !hasRecordPermission;

            int strID = useRecordWarning
                    ? R.string.usb_device_permission_prompt_warn
                    : R.string.usb_device_permission_prompt;
            ap.mMessage = getString(strID, appName, mDevice.getProductName());
            mDisconnectedReceiver = new UsbDisconnectedReceiver(this, mDevice);

        }

        ap.mPositiveButtonText = getString(android.R.string.ok);
        ap.mNegativeButtonText = getString(android.R.string.cancel);
        ap.mPositiveButtonListener = this;
        ap.mNegativeButtonListener = this;

        // Don't show the "always use" checkbox if the USB/Record warning is in effect
        if (!useRecordWarning && canBeDefault && (mDevice != null || mAccessory != null)) {
            // add "open when" checkbox
            LayoutInflater inflater = (LayoutInflater) getSystemService(
                    Context.LAYOUT_INFLATER_SERVICE);
            ap.mView = inflater.inflate(com.android.internal.R.layout.always_use_checkbox, null);
            mAlwaysUse = (CheckBox) ap.mView.findViewById(com.android.internal.R.id.alwaysUse);
            if (mDevice == null) {
                mAlwaysUse.setText(getString(R.string.always_use_accessory, appName,
                        mAccessory.getDescription()));
            } else {
                mAlwaysUse.setText(getString(R.string.always_use_device, appName,
                        mDevice.getProductName()));
            }
            mAlwaysUse.setOnCheckedChangeListener(this);

            mClearDefaultHint = (TextView)ap.mView.findViewById(
                    com.android.internal.R.id.clearDefaultHint);
            mClearDefaultHint.setVisibility(View.GONE);
        }

        setupAlert();
    }

    @Override
    public void onDestroy() {
        IBinder b = ServiceManager.getService(USB_SERVICE);
        IUsbManager service = IUsbManager.Stub.asInterface(b);

        // send response via pending intent
        Intent intent = new Intent();
        try {
            if (mDevice != null) {
                intent.putExtra(UsbManager.EXTRA_DEVICE, mDevice);
                if (mPermissionGranted) {
                    service.grantDevicePermission(mDevice, mUid);
                    if (mAlwaysUse != null && mAlwaysUse.isChecked()) {
                        final int userId = UserHandle.getUserId(mUid);
                        service.setDevicePackage(mDevice, mPackageName, userId);
                    }
                }
            }
            if (mAccessory != null) {
                intent.putExtra(UsbManager.EXTRA_ACCESSORY, mAccessory);
                if (mPermissionGranted) {
                    service.grantAccessoryPermission(mAccessory, mUid);
                    if (mAlwaysUse != null && mAlwaysUse.isChecked()) {
                        final int userId = UserHandle.getUserId(mUid);
                        service.setAccessoryPackage(mAccessory, mPackageName, userId);
                    }
                }
            }
            intent.putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, mPermissionGranted);
            mPendingIntent.send(this, 0, intent);
        } catch (PendingIntent.CanceledException e) {
            Log.w(TAG, "PendingIntent was cancelled");
        } catch (RemoteException e) {
            Log.e(TAG, "IUsbService connection failed", e);
        }

        if (mDisconnectedReceiver != null) {
            unregisterReceiver(mDisconnectedReceiver);
        }
        super.onDestroy();
    }

    public void onClick(DialogInterface dialog, int which) {
        if (which == AlertDialog.BUTTON_POSITIVE) {
            mPermissionGranted = true;
        }
        finish();
    }

    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (mClearDefaultHint == null) return;

        if(isChecked) {
            mClearDefaultHint.setVisibility(View.VISIBLE);
        } else {
            mClearDefaultHint.setVisibility(View.GONE);
        }
    }
}
