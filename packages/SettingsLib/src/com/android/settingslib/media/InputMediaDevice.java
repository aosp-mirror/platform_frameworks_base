/*
 * Copyright 2024 The Android Open Source Project
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

import static android.media.AudioDeviceInfo.TYPE_BLE_HEADSET;
import static android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
import static android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC;
import static android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY;
import static android.media.AudioDeviceInfo.TYPE_USB_DEVICE;
import static android.media.AudioDeviceInfo.TYPE_USB_HEADSET;
import static android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET;

import static com.android.settingslib.media.MediaDevice.SelectionBehavior.SELECTION_BEHAVIOR_TRANSFER;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.media.AudioDeviceInfo.AudioDeviceType;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.settingslib.R;

/** {@link MediaDevice} implementation that represents an input device. */
public class InputMediaDevice extends MediaDevice {

    private static final String TAG = "InputMediaDevice";

    private final String mId;

    private final @AudioDeviceType int mAudioDeviceInfoType;

    private final int mMaxVolume;

    private final int mCurrentVolume;

    private final boolean mIsVolumeFixed;

    private final String mProductName;

    private InputMediaDevice(
            @NonNull Context context,
            @NonNull String id,
            @AudioDeviceType int audioDeviceInfoType,
            int maxVolume,
            int currentVolume,
            boolean isVolumeFixed,
            @Nullable String productName) {
        super(context, /* info= */ null, /* item= */ null);
        mId = id;
        mAudioDeviceInfoType = audioDeviceInfoType;
        mMaxVolume = maxVolume;
        mCurrentVolume = currentVolume;
        mIsVolumeFixed = isVolumeFixed;
        mProductName = productName;
        initDeviceRecord();
    }

    @Nullable
    public static InputMediaDevice create(
            @NonNull Context context,
            @NonNull String id,
            @AudioDeviceType int audioDeviceInfoType,
            int maxVolume,
            int currentVolume,
            boolean isVolumeFixed,
            @Nullable String productName) {
        if (!isSupportedInputDevice(audioDeviceInfoType)) {
            return null;
        }

        return new InputMediaDevice(
                context,
                id,
                audioDeviceInfoType,
                maxVolume,
                currentVolume,
                isVolumeFixed,
                productName);
    }

    public @AudioDeviceType int getAudioDeviceInfoType() {
        return mAudioDeviceInfoType;
    }

    public static boolean isSupportedInputDevice(@AudioDeviceType int audioDeviceInfoType) {
        return switch (audioDeviceInfoType) {
            case TYPE_BUILTIN_MIC,
                            TYPE_WIRED_HEADSET,
                            TYPE_USB_DEVICE,
                            TYPE_USB_HEADSET,
                            TYPE_USB_ACCESSORY,
                            TYPE_BLUETOOTH_SCO,
                            TYPE_BLE_HEADSET ->
                    true;
            default -> false;
        };
    }

    @Nullable
    public String getProductName() {
        return mProductName;
    }

    @Override
    public @NonNull String getName() {
        return switch (mAudioDeviceInfoType) {
            case TYPE_WIRED_HEADSET ->
                    mContext.getString(R.string.media_transfer_wired_device_mic_name);
            case TYPE_USB_DEVICE, TYPE_USB_HEADSET, TYPE_USB_ACCESSORY ->
                    // The product name is assumed to be a well-formed string if it's not null.
                    mProductName != null
                            ? mProductName
                            : mContext.getString(R.string.media_transfer_usb_device_mic_name);
            case TYPE_BLUETOOTH_SCO, TYPE_BLE_HEADSET ->
                    mProductName != null
                            ? mProductName
                            : mContext.getString(R.string.media_transfer_bt_device_mic_name);
            default -> mContext.getString(R.string.media_transfer_this_device_name_desktop);
        };
    }

    @Override
    public @SelectionBehavior int getSelectionBehavior() {
        // We don't allow apps to override the selection behavior of system routes.
        return SELECTION_BEHAVIOR_TRANSFER;
    }

    @Override
    public @NonNull String getSummary() {
        return "";
    }

    @Override
    public @Nullable Drawable getIcon() {
        return getIconWithoutBackground();
    }

    @Override
    public @Nullable Drawable getIconWithoutBackground() {
        return mContext.getDrawable(getDrawableResId());
    }

    @VisibleForTesting
    int getDrawableResId() {
        return R.drawable.ic_media_microphone;
    }

    @Override
    public @NonNull String getId() {
        return mId;
    }

    @Override
    public boolean isConnected() {
        // Indicating if the device is connected and thus showing the status of STATE_CONNECTED.
        // Upon creation, this device is already connected.
        return true;
    }

    @Override
    public int getMaxVolume() {
        return mMaxVolume;
    }

    @Override
    public int getCurrentVolume() {
        return mCurrentVolume;
    }

    @Override
    public boolean isVolumeFixed() {
        return mIsVolumeFixed;
    }
}
