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

import android.annotation.NonNull;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.XmlResourceParser;
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
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.TextView;

import com.android.internal.app.AlertActivity;
import com.android.internal.app.AlertController;
import com.android.internal.util.XmlUtils;
import android.hardware.usb.AccessoryFilter;
import android.hardware.usb.DeviceFilter;
import com.android.systemui.R;

import org.xmlpull.v1.XmlPullParser;

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

       Intent intent = getIntent();
        mDevice = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        mAccessory = (UsbAccessory)intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
        mPendingIntent = (PendingIntent)intent.getParcelableExtra(Intent.EXTRA_INTENT);
        mUid = intent.getIntExtra(Intent.EXTRA_UID, -1);
        mPackageName = intent.getStringExtra("package");

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
        if (mDevice == null) {
            ap.mMessage = getString(R.string.usb_accessory_permission_prompt, appName,
                    mAccessory.getDescription());
            mDisconnectedReceiver = new UsbDisconnectedReceiver(this, mAccessory);
        } else {
            ap.mMessage = getString(R.string.usb_device_permission_prompt, appName,
                    mDevice.getProductName());
            mDisconnectedReceiver = new UsbDisconnectedReceiver(this, mDevice);
        }
        ap.mPositiveButtonText = getString(android.R.string.ok);
        ap.mNegativeButtonText = getString(android.R.string.cancel);
        ap.mPositiveButtonListener = this;
        ap.mNegativeButtonListener = this;

        try {
            PackageInfo packageInfo = packageManager.getPackageInfo(mPackageName,
                    PackageManager.GET_ACTIVITIES | PackageManager.GET_META_DATA);

            if ((mDevice != null && canBeDefault(mDevice, packageInfo))
                    || (mAccessory != null && canBeDefault(mAccessory, packageInfo))) {
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
        } catch (PackageManager.NameNotFoundException e) {
            // ignore
        }

        setupAlert();

    }

    /**
     * Can the app be the default for the USB device. I.e. can the app be launched by default if
     * the device is plugged in.
     *
     * @param device The device the app would be default for
     * @param packageInfo The package info of the app
     *
     * @return {@code true} iff the app can be default
     */
    private boolean canBeDefault(@NonNull UsbDevice device, @NonNull PackageInfo packageInfo) {
        ActivityInfo[] activities = packageInfo.activities;
        if (activities != null) {
            int numActivities = activities.length;
            for (int i = 0; i < numActivities; i++) {
                ActivityInfo activityInfo = activities[i];

                try (XmlResourceParser parser = activityInfo.loadXmlMetaData(getPackageManager(),
                        UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
                    if (parser == null) {
                        continue;
                    }

                    XmlUtils.nextElement(parser);
                    while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                        if ("usb-device".equals(parser.getName())) {
                            DeviceFilter filter = DeviceFilter.read(parser);
                            if (filter.matches(device)) {
                                return true;
                            }
                        }

                        XmlUtils.nextElement(parser);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Unable to load component info " + activityInfo.toString(), e);
                }
            }
        }

        return false;
    }

    /**
     * Can the app be the default for the USB accessory. I.e. can the app be launched by default if
     * the accessory is plugged in.
     *
     * @param accessory The accessory the app would be default for
     * @param packageInfo The package info of the app
     *
     * @return {@code true} iff the app can be default
     */
    private boolean canBeDefault(@NonNull UsbAccessory accessory,
            @NonNull PackageInfo packageInfo) {
        ActivityInfo[] activities = packageInfo.activities;
        if (activities != null) {
            int numActivities = activities.length;
            for (int i = 0; i < numActivities; i++) {
                ActivityInfo activityInfo = activities[i];

                try (XmlResourceParser parser = activityInfo.loadXmlMetaData(getPackageManager(),
                        UsbManager.ACTION_USB_ACCESSORY_ATTACHED)) {
                    if (parser == null) {
                        continue;
                    }

                    XmlUtils.nextElement(parser);
                    while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                        if ("usb-accessory".equals(parser.getName())) {
                            AccessoryFilter filter = AccessoryFilter.read(parser);
                            if (filter.matches(accessory)) {
                                return true;
                            }
                        }

                        XmlUtils.nextElement(parser);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Unable to load component info " + activityInfo.toString(), e);
                }
            }
        }

        return false;
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
