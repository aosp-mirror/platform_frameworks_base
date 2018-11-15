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

import static com.android.internal.app.IntentForwarderActivity.FORWARD_INTENT_TO_MANAGED_PROFILE;

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
import android.os.UserHandle;
import android.util.Log;
import android.widget.CheckBox;

import com.android.internal.app.ResolverActivity;
import com.android.systemui.R;

import java.util.ArrayList;
import java.util.Iterator;

/* Activity for choosing an application for a USB device or accessory */
public class UsbResolverActivity extends ResolverActivity {
    public static final String TAG = "UsbResolverActivity";
    public static final String EXTRA_RESOLVE_INFOS = "rlist";
    public static final String EXTRA_RESOLVE_INFO = "rinfo";

    private UsbDevice mDevice;
    private UsbAccessory mAccessory;
    private UsbDisconnectedReceiver mDisconnectedReceiver;

    /** Resolve info that switches user profiles */
    private ResolveInfo mForwardResolveInfo;

    /** The intent that should be started when the profile is switched */
    private Intent mOtherProfileIntent;

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
        ArrayList<ResolveInfo> rList = new ArrayList<>(
                intent.getParcelableArrayListExtra(EXTRA_RESOLVE_INFOS));

        // The rList contains the apps for all profiles of this users. Separate those. We currently
        // only support two profiles, i.e. one forward resolve info.
        ArrayList<ResolveInfo> rListOtherProfile = new ArrayList<>();
        mForwardResolveInfo = null;
        for (Iterator<ResolveInfo> iterator = rList.iterator(); iterator.hasNext();) {
            ResolveInfo ri = iterator.next();

            if (ri.getComponentInfo().name.equals(FORWARD_INTENT_TO_MANAGED_PROFILE)) {
                mForwardResolveInfo = ri;
            } else if (UserHandle.getUserId(ri.activityInfo.applicationInfo.uid)
                    != UserHandle.myUserId()) {
                iterator.remove();
                rListOtherProfile.add(ri);
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

        // Create intent that will be used when switching to other profile. Emulate the behavior of
        // UsbProfileGroupSettingsManager#resolveActivity
        if (mForwardResolveInfo != null) {
            if (rListOtherProfile.size() > 1) {
                mOtherProfileIntent = new Intent(intent);
                mOtherProfileIntent.putParcelableArrayListExtra(EXTRA_RESOLVE_INFOS,
                        rListOtherProfile);
            } else {
                mOtherProfileIntent = new Intent(this, UsbConfirmActivity.class);
                mOtherProfileIntent.putExtra(EXTRA_RESOLVE_INFO, rListOtherProfile.get(0));

                if (mDevice != null) {
                    mOtherProfileIntent.putExtra(UsbManager.EXTRA_DEVICE, mDevice);
                }

                if (mAccessory != null) {
                    mOtherProfileIntent.putExtra(UsbManager.EXTRA_ACCESSORY, mAccessory);
                }
            }
        }

        CharSequence title = getResources().getText(com.android.internal.R.string.chooseUsbActivity);
        super.onCreate(savedInstanceState, target, title, null, rList, true);

        CheckBox alwaysUse = (CheckBox)findViewById(com.android.internal.R.id.alwaysUse);
        if (alwaysUse != null) {
            if (mDevice == null) {
                alwaysUse.setText(R.string.always_use_accessory);
            } else {
                alwaysUse.setText(R.string.always_use_device);
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (mDisconnectedReceiver != null) {
            unregisterReceiver(mDisconnectedReceiver);
        }
        super.onDestroy();
    }

    @Override
    protected boolean onTargetSelected(TargetInfo target, boolean alwaysCheck) {
        final ResolveInfo ri = target.getResolveInfo();
        if (ri == mForwardResolveInfo) {
            startActivityAsUser(mOtherProfileIntent, null,
                    UserHandle.of(mForwardResolveInfo.targetUserId));
            return true;
        }

        try {
            IBinder b = ServiceManager.getService(USB_SERVICE);
            IUsbManager service = IUsbManager.Stub.asInterface(b);
            final int uid = ri.activityInfo.applicationInfo.uid;
            final int userId = UserHandle.myUserId();

            if (mDevice != null) {
                // grant permission for the device
                service.grantDevicePermission(mDevice, uid);
                // set or clear default setting
                if (alwaysCheck) {
                    service.setDevicePackage(mDevice, ri.activityInfo.packageName, userId);
                } else {
                    service.setDevicePackage(mDevice, null, userId);
                }
            } else if (mAccessory != null) {
                // grant permission for the accessory
                service.grantAccessoryPermission(mAccessory, uid);
                // set or clear default setting
                if (alwaysCheck) {
                    service.setAccessoryPackage(mAccessory, ri.activityInfo.packageName, userId);
                } else {
                    service.setAccessoryPackage(mAccessory, null, userId);
                }
            }

            try {
                target.startAsUser(this, null, UserHandle.of(userId));
            } catch (ActivityNotFoundException e) {
                Log.e(TAG, "startActivity failed", e);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "onIntentSelected failed", e);
        }
        return true;
    }
}
