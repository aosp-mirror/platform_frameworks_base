/*
 * Copyright 2018 The Android Open Source Project
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
package com.android.settingslib.media;

import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaRoute2Info;
import android.media.MediaRouter2Manager;

import com.android.settingslib.R;
import com.android.settingslib.bluetooth.BluetoothUtils;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;

/**
 * BluetoothMediaDevice extends MediaDevice to represents Bluetooth device.
 */
public class BluetoothMediaDevice extends MediaDevice {

    private static final String TAG = "BluetoothMediaDevice";

    private CachedBluetoothDevice mCachedDevice;

    BluetoothMediaDevice(Context context, CachedBluetoothDevice device,
            MediaRouter2Manager routerManager, MediaRoute2Info info, String packageName) {
        super(context, routerManager, info, packageName);
        mCachedDevice = device;
        initDeviceRecord();
    }

    @Override
    public String getName() {
        return mCachedDevice.getName();
    }

    @Override
    public String getSummary() {
        return isConnected() || mCachedDevice.isBusy()
                ? mCachedDevice.getConnectionSummary()
                : mContext.getString(R.string.bluetooth_disconnected);
    }

    @Override
    public Drawable getIcon() {
        final Drawable drawable =
                BluetoothUtils.getBtDrawableWithDescription(mContext, mCachedDevice).first;
        if (!(drawable instanceof BitmapDrawable)) {
            setColorFilter(drawable);
        }
        return BluetoothUtils.buildAdvancedDrawable(mContext, drawable);
    }

    @Override
    public Drawable getIconWithoutBackground() {
        return BluetoothUtils.getBtClassDrawableWithDescription(mContext, mCachedDevice).first;
    }

    @Override
    public String getId() {
        return MediaDeviceUtils.getId(mCachedDevice);
    }

    /**
     * Get current CachedBluetoothDevice
     */
    public CachedBluetoothDevice getCachedDevice() {
        return mCachedDevice;
    }

    @Override
    protected boolean isCarKitDevice() {
        final BluetoothClass bluetoothClass = mCachedDevice.getDevice().getBluetoothClass();
        if (bluetoothClass != null) {
            switch (bluetoothClass.getDeviceClass()) {
                // Both are common CarKit class
                case BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE:
                case BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO:
                    return true;
            }
        }
        return false;
    }

    @Override
    public boolean isFastPairDevice() {
        return mCachedDevice != null
                && BluetoothUtils.getBooleanMetaData(
                mCachedDevice.getDevice(), BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET);
    }

    @Override
    public boolean isConnected() {
        return mCachedDevice.getBondState() == BluetoothDevice.BOND_BONDED
                && mCachedDevice.isConnected();
    }
}
