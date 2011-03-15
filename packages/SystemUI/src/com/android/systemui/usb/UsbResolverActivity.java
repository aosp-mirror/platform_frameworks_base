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

import com.android.internal.app.ResolverActivity;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.hardware.usb.IUsbManager;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.widget.CheckBox;

import com.android.systemui.R;

import java.util.ArrayList;

/* Activity for choosing an application for a USB device or accessory */
public class UsbResolverActivity extends ResolverActivity {
    public static final String TAG = "UsbResolverActivity";
    public static final String EXTRA_RESOLVE_INFOS = "rlist";

    private UsbDevice mDevice;
    private UsbAccessory mAccessory;
    private UsbDisconnectedReceiver mDisconnectedReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Intent intent = getIntent();
        Parcelable targetParcelable = intent.getParcelableExtra(Intent.EXTRA_INTENT);
        if (!(targetParcelable instanceof Intent)) {
            Log.w("UsbResolverActivity", "Target is not an intent: " + targetParcelable);
            finish();
            return;
        }
        Intent target = (Intent)targetParcelable;
        ArrayList<ResolveInfo> rList = intent.getParcelableArrayListExtra(EXTRA_RESOLVE_INFOS);
        CharSequence title = getResources().getText(com.android.internal.R.string.chooseUsbActivity);
        super.onCreate(savedInstanceState, target, title, null, rList,
                true /* Set alwaysUseOption to true to enable "always use this app" checkbox. */ );

        CheckBox alwaysUse = (CheckBox)findViewById(com.android.internal.R.id.alwaysUse);
        if (alwaysUse != null) {
            if (mDevice == null) {
                alwaysUse.setText(R.string.always_use_accessory);
            } else {
                alwaysUse.setText(R.string.always_use_device);
            }
        }

        mDevice = (UsbDevice)target.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (mDevice != null) {
            mDisconnectedReceiver = new UsbDisconnectedReceiver(this, mDevice);
        } else {
            mAccessory = (UsbAccessory)target.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
            if (mAccessory == null) {
                Log.e(TAG, "no device or accessory");
                finish();
                return;
            }
            mDisconnectedReceiver = new UsbDisconnectedReceiver(this, mAccessory);
        }
    }

    @Override
    protected void onDestroy() {
        if (mDisconnectedReceiver != null) {
            unregisterReceiver(mDisconnectedReceiver);
        }
        super.onDestroy();
    }

    protected void onIntentSelected(ResolveInfo ri, Intent intent, boolean alwaysCheck) {
        try {
            IBinder b = ServiceManager.getService(USB_SERVICE);
            IUsbManager service = IUsbManager.Stub.asInterface(b);
            int uid = ri.activityInfo.applicationInfo.uid;

            if (mDevice != null) {
                // grant permission for the device
                service.grantDevicePermission(mDevice, uid);
                // set or clear default setting
                if (alwaysCheck) {
                    service.setDevicePackage(mDevice, ri.activityInfo.packageName);
                } else {
                    service.setDevicePackage(mDevice, null);
                }
            } else if (mAccessory != null) {
                // grant permission for the accessory
                service.grantAccessoryPermission(mAccessory, uid);
                // set or clear default setting
                if (alwaysCheck) {
                    service.setAccessoryPackage(mAccessory, ri.activityInfo.packageName);
                } else {
                    service.setAccessoryPackage(mAccessory, null);
                }
            }

            try {
                startActivity(intent);
            } catch (ActivityNotFoundException e) {
                Log.e(TAG, "startActivity failed", e);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "onIntentSelected failed", e);
        }
    }
}
