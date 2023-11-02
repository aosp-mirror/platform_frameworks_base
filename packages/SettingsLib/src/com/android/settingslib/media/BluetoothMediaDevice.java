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

import static com.android.settingslib.media.MediaDevice.SelectionBehavior.SELECTION_BEHAVIOR_TRANSFER;

import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaRoute2Info;
import android.media.RouteListingPreference;

import com.android.settingslib.R;
import com.android.settingslib.bluetooth.BluetoothUtils;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;

/**
 * BluetoothMediaDevice extends MediaDevice to represents Bluetooth device.
 */
public class BluetoothMediaDevice extends MediaDevice {

    private static final String TAG = "BluetoothMediaDevice";

    private CachedBluetoothDevice mCachedDevice;
    private final AudioManager mAudioManager;

    BluetoothMediaDevice(
            Context context,
            CachedBluetoothDevice device,
            MediaRoute2Info info,
            String packageName) {
        this(context, device, info, packageName, null);
    }

    BluetoothMediaDevice(
            Context context,
            CachedBluetoothDevice device,
            MediaRoute2Info info,
            String packageName,
            RouteListingPreference.Item item) {
        super(context, info, packageName, item);
        mCachedDevice = device;
        mAudioManager = context.getSystemService(AudioManager.class);
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
    public CharSequence getSummaryForTv(int lowBatteryColorRes) {
        return isConnected() || mCachedDevice.isBusy()
                ? mCachedDevice.getTvConnectionSummary(lowBatteryColorRes)
                : mContext.getString(R.string.bluetooth_saved_device);
    }

    @Override
    public int getSelectionBehavior() {
        // We don't allow apps to override the selection behavior of system routes.
        return SELECTION_BEHAVIOR_TRANSFER;
    }

    @Override
    public Drawable getIcon() {
        return BluetoothUtils.isAdvancedUntetheredDevice(mCachedDevice.getDevice())
                ? mContext.getDrawable(R.drawable.ic_earbuds_advanced)
                : BluetoothUtils.getBtClassDrawableWithDescription(mContext, mCachedDevice).first;
    }

    @Override
    public Drawable getIconWithoutBackground() {
        return BluetoothUtils.isAdvancedUntetheredDevice(mCachedDevice.getDevice())
                ? mContext.getDrawable(R.drawable.ic_earbuds_advanced)
                : BluetoothUtils.getBtClassDrawableWithDescription(mContext, mCachedDevice).first;
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
    public boolean isMutingExpectedDevice() {
        return mAudioManager.getMutingExpectedDevice() != null && mCachedDevice.getAddress().equals(
                mAudioManager.getMutingExpectedDevice().getAddress());
    }

    @Override
    public boolean isConnected() {
        return mCachedDevice.getBondState() == BluetoothDevice.BOND_BONDED
                && mCachedDevice.isConnected();
    }
}
