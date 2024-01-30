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

package com.android.settingslib.media;

import android.annotation.DrawableRes;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.media.AudioDeviceInfo;
import android.media.MediaRoute2Info;

import com.android.settingslib.R;
import com.android.settingslib.media.flags.Flags;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A util class to get the appropriate icon for different device types. */
public class DeviceIconUtil {

    // A default icon to use if the type is not present in the map.
    @DrawableRes private static final int DEFAULT_ICON = R.drawable.ic_smartphone;
    @DrawableRes private static final int DEFAULT_ICON_TV = R.drawable.ic_media_speaker_device;

    // A map from a @AudioDeviceInfo.AudioDeviceType to full device information.
    private final Map<Integer, Device> mAudioDeviceTypeToIconMap = new HashMap<>();
    // A map from a @MediaRoute2Info.Type to full device information.
    private final Map<Integer, Device> mMediaRouteTypeToIconMap = new HashMap<>();

    private final boolean mIsTv;

    public DeviceIconUtil(Context context) {
        this(context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK));
    }

    public DeviceIconUtil(boolean isTv) {
        mIsTv = isTv && Flags.enableTvMediaOutputDialog();
        List<Device> deviceList = Arrays.asList(
                        new Device(
                                AudioDeviceInfo.TYPE_USB_DEVICE,
                                MediaRoute2Info.TYPE_USB_DEVICE,
                                R.drawable.ic_headphone),
                        new Device(
                                AudioDeviceInfo.TYPE_USB_HEADSET,
                                MediaRoute2Info.TYPE_USB_HEADSET,
                                R.drawable.ic_headphone),
                        new Device(
                                AudioDeviceInfo.TYPE_USB_ACCESSORY,
                                MediaRoute2Info.TYPE_USB_ACCESSORY,
                                mIsTv ? R.drawable.ic_usb : R.drawable.ic_headphone),
                        new Device(
                                AudioDeviceInfo.TYPE_DOCK,
                                MediaRoute2Info.TYPE_DOCK,
                                R.drawable.ic_dock_device),
                        new Device(
                                AudioDeviceInfo.TYPE_HDMI,
                                MediaRoute2Info.TYPE_HDMI,
                                mIsTv ? R.drawable.ic_tv : R.drawable.ic_headphone),
                        new Device(
                                AudioDeviceInfo.TYPE_HDMI_ARC,
                                MediaRoute2Info.TYPE_HDMI_ARC,
                                mIsTv ? R.drawable.ic_hdmi : R.drawable.ic_headphone),
                        new Device(
                                AudioDeviceInfo.TYPE_HDMI_EARC,
                                MediaRoute2Info.TYPE_HDMI_EARC,
                                mIsTv ? R.drawable.ic_hdmi : R.drawable.ic_headphone),
                        new Device(
                                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                                MediaRoute2Info.TYPE_WIRED_HEADSET,
                                mIsTv ? R.drawable.ic_wired_device : R.drawable.ic_headphone),
                        new Device(
                                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                                MediaRoute2Info.TYPE_WIRED_HEADPHONES,
                                mIsTv ? R.drawable.ic_wired_device : R.drawable.ic_headphone),
                        new Device(
                                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                                MediaRoute2Info.TYPE_BUILTIN_SPEAKER,
                                mIsTv ? R.drawable.ic_tv : R.drawable.ic_smartphone));
        for (int i = 0; i < deviceList.size(); i++) {
            Device device = deviceList.get(i);
            mAudioDeviceTypeToIconMap.put(device.mAudioDeviceType, device);
            mMediaRouteTypeToIconMap.put(device.mMediaRouteType, device);
        }
    }

    private int getDefaultIcon() {
        return mIsTv ? DEFAULT_ICON_TV : DEFAULT_ICON;
    }

    /** Returns a drawable for an icon representing the given audioDeviceType. */
    public Drawable getIconFromAudioDeviceType(
            @AudioDeviceInfo.AudioDeviceType int audioDeviceType, Context context) {
        return context.getDrawable(getIconResIdFromAudioDeviceType(audioDeviceType));
    }

    /** Returns a drawable res ID for an icon representing the given audioDeviceType. */
    @DrawableRes
    public int getIconResIdFromAudioDeviceType(
            @AudioDeviceInfo.AudioDeviceType int audioDeviceType) {
        if (mAudioDeviceTypeToIconMap.containsKey(audioDeviceType)) {
            return mAudioDeviceTypeToIconMap.get(audioDeviceType).mIconDrawableRes;
        }
        return getDefaultIcon();
    }

    /** Returns a drawable res ID for an icon representing the given mediaRouteType. */
    @DrawableRes
    public int getIconResIdFromMediaRouteType(
            @MediaRoute2Info.Type int mediaRouteType) {
        if (mMediaRouteTypeToIconMap.containsKey(mediaRouteType)) {
            return mMediaRouteTypeToIconMap.get(mediaRouteType).mIconDrawableRes;
        }
        return getDefaultIcon();
    }

    private static class Device {
        @AudioDeviceInfo.AudioDeviceType
        private final int mAudioDeviceType;

        @MediaRoute2Info.Type
        private final int mMediaRouteType;

        @DrawableRes
        private final int mIconDrawableRes;

        Device(@AudioDeviceInfo.AudioDeviceType int audioDeviceType,
                @MediaRoute2Info.Type int mediaRouteType,
                @DrawableRes int iconDrawableRes) {
            mAudioDeviceType = audioDeviceType;
            mMediaRouteType = mediaRouteType;
            mIconDrawableRes = iconDrawableRes;
        }
    }
}
