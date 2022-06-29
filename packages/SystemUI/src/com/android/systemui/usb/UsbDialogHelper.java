/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.Manifest.permission.RECORD_AUDIO;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.PermissionChecker;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.usb.IUsbManager;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Log;

/**
 * Helper class to separate model and view for USB permission and confirm dialogs.
 */
public class UsbDialogHelper {
    private static final String TAG = UsbDialogHelper.class.getSimpleName();
    private static final String EXTRA_RESOLVE_INFO = "rinfo";

    private final UsbDevice mDevice;
    private final UsbAccessory mAccessory;
    private final ResolveInfo mResolveInfo;
    private final String mPackageName;
    private final CharSequence mAppName;
    private final Context mContext;
    private final PendingIntent mPendingIntent;
    private final IUsbManager mUsbService;
    private final int mUid;
    private final boolean mCanBeDefault;

    private UsbDisconnectedReceiver mDisconnectedReceiver;
    private boolean mIsUsbDevice;
    private boolean mResponseSent;

    /**
     * @param context The Context of the caller.
     * @param intent The intent of the caller.
     * @throws IllegalStateException Thrown if both UsbDevice and UsbAccessory are null or if the
     *                               query for the matching ApplicationInfo is unsuccessful.
     */
    public UsbDialogHelper(Context context, Intent intent) throws IllegalStateException {
        mContext = context;
        mDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        mAccessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
        mCanBeDefault = intent.getBooleanExtra(UsbManager.EXTRA_CAN_BE_DEFAULT, false);
        if (mDevice == null && mAccessory == null) {
            throw new IllegalStateException("Device and accessory are both null.");
        }
        if (mDevice != null) {
            mIsUsbDevice = true;
        }
        mResolveInfo = intent.getParcelableExtra(EXTRA_RESOLVE_INFO);
        PackageManager packageManager = mContext.getPackageManager();
        if (mResolveInfo != null) {
            // If a ResolveInfo is provided it will be used to determine the activity to start
            mUid = mResolveInfo.activityInfo.applicationInfo.uid;
            mPackageName = mResolveInfo.activityInfo.packageName;
            mPendingIntent = null;
        } else {
            mUid = intent.getIntExtra(Intent.EXTRA_UID, -1);
            mPackageName = intent.getStringExtra(UsbManager.EXTRA_PACKAGE);
            mPendingIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT);
        }
        try {
            ApplicationInfo aInfo = packageManager.getApplicationInfo(mPackageName, 0);
            mAppName = aInfo.loadLabel(packageManager);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException("unable to look up package name", e);
        }
        IBinder b = ServiceManager.getService(Context.USB_SERVICE);
        mUsbService = IUsbManager.Stub.asInterface(b);
    }

    /**
     * Registers UsbDisconnectedReceiver to dismiss dialog automatically when device or accessory
     * gets disconnected
     * @param activity The activity to finish when device / accessory gets disconnected.
     */
    public void registerUsbDisconnectedReceiver(Activity activity) {
        if (mIsUsbDevice) {
            mDisconnectedReceiver = new UsbDisconnectedReceiver(activity, mDevice);
        } else {
            mDisconnectedReceiver = new UsbDisconnectedReceiver(activity, mAccessory);
        }
    }

    /**
     * Unregisters the UsbDisconnectedReceiver. To be called when the activity is destroyed.
     * @param activity The activity registered to finish when device / accessory gets disconnected.
     */
    public void unregisterUsbDisconnectedReceiver(Activity activity) {
        if (mDisconnectedReceiver != null) {
            try {
                activity.unregisterReceiver(mDisconnectedReceiver);
            } catch (Exception e) {
                // pass
            }
            mDisconnectedReceiver = null;
        }
    }

    /**
     * @return True if the intent contains a UsbDevice which can capture audio.
     */
    public boolean deviceHasAudioCapture() {
        return mDevice != null && mDevice.getHasAudioCapture();
    }

    /**
     * @return True if the intent contains a UsbDevice which can play audio.
     */
    public boolean deviceHasAudioPlayback() {
        return mDevice != null && mDevice.getHasAudioPlayback();
    }

    /**
     * @return True if the package has RECORD_AUDIO permission specified in its manifest.
     */
    public boolean packageHasAudioRecordingPermission() {
        return PermissionChecker.checkPermissionForPreflight(mContext, RECORD_AUDIO,
                PermissionChecker.PID_UNKNOWN, mUid, mPackageName)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /**
     * @return True if the intent contains a UsbDevice.
     */
    public boolean isUsbDevice() {
        return mIsUsbDevice;
    }

    /**
     * @return True if the intent contains a UsbAccessory.
     */
    public boolean isUsbAccessory() {
        return !mIsUsbDevice;
    }

    /**
     * Grants USB permission to the device / accessory to the calling uid.
     */
    public void grantUidAccessPermission() {
        try {
            if (mIsUsbDevice) {
                mUsbService.grantDevicePermission(mDevice, mUid);
            } else {
                mUsbService.grantAccessoryPermission(mAccessory, mUid);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "IUsbService connection failed", e);
        }
    }

    /**
     * Sets the package as default for the device / accessory.
     */
    public void setDefaultPackage() {
        final int userId = UserHandle.myUserId();
        try {
            if (mIsUsbDevice) {
                mUsbService.setDevicePackage(mDevice, mPackageName, userId);
            } else {
                mUsbService.setAccessoryPackage(mAccessory, mPackageName, userId);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "IUsbService connection failed", e);
        }
    }

    /**
     * Clears the default package of the device / accessory.
     */
    public void clearDefaultPackage() {
        final int userId = UserHandle.myUserId();
        try {
            if (mIsUsbDevice) {
                mUsbService.setDevicePackage(mDevice, null, userId);
            } else {
                mUsbService.setAccessoryPackage(mAccessory, null, userId);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "IUsbService connection failed", e);
        }
    }

    /**
     * Starts the activity which was selected to handle the device / accessory.
     */
    public void confirmDialogStartActivity() {
        final int userId = UserHandle.myUserId();
        Intent intent;

        if (mIsUsbDevice) {
            intent = new Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            intent.putExtra(UsbManager.EXTRA_DEVICE, mDevice);
        } else {
            intent = new Intent(UsbManager.ACTION_USB_ACCESSORY_ATTACHED);
            intent.putExtra(UsbManager.EXTRA_ACCESSORY, mAccessory);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setComponent(
                new ComponentName(mResolveInfo.activityInfo.packageName,
                        mResolveInfo.activityInfo.name));
        try {
            mContext.startActivityAsUser(intent, new UserHandle(userId));
        } catch (Exception e) {
            Log.e(TAG, "Unable to start activity", e);
        }
    }

    /**
     * Sends the result of the permission dialog via the provided PendingIntent.
     *
     * @param permissionGranted True if the user pressed ok in the permission dialog.
     */
    public void sendPermissionDialogResponse(boolean permissionGranted) {
        if (!mResponseSent) {
            // send response via pending intent
            Intent intent = new Intent();
            if (mIsUsbDevice) {
                intent.putExtra(UsbManager.EXTRA_DEVICE, mDevice);
            } else {
                intent.putExtra(UsbManager.EXTRA_ACCESSORY, mAccessory);
            }
            intent.putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, permissionGranted);
            try {
                mPendingIntent.send(mContext, 0, intent);
                mResponseSent = true;
            } catch (PendingIntent.CanceledException e) {
                Log.w(TAG, "PendingIntent was cancelled");
            }
        }
    }

    /**
     * @return A description of the device / accessory
     */
    public String getDeviceDescription() {
        String desc;
        if (mIsUsbDevice) {
            desc = mDevice.getProductName();
            if (desc == null) {
                desc = mDevice.getDeviceName();
            }
        } else {
            // UsbAccessory
            desc = mAccessory.getDescription();
            if (desc == null) {
                desc = String.format("%s %s", mAccessory.getManufacturer(), mAccessory.getModel());
            }
        }
        return desc;
    }

    /**
     * Whether the calling package can set as default handler of the USB device or accessory.
     * In case of a UsbAccessory this is the case if the calling package has an intent filter for
     * {@link UsbManager#ACTION_USB_ACCESSORY_ATTACHED} with a usb-accessory filter matching the
     * attached accessory. In case of a UsbDevice this is the case if the calling package has an
     * intent filter for {@link UsbManager#ACTION_USB_DEVICE_ATTACHED} with a usb-device filter
     * matching the attached device.
     *
     * @return True if the package can be default for the USB device.
     */
    public boolean canBeDefault() {
        return mCanBeDefault;
    }

    /**
     * @return The name of the app which requested permission or the name of the app which will be
     * opened if the user allows it to handle the USB device.
     */
    public CharSequence getAppName() {
        return mAppName;
    }
}
