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

import static android.media.AudioDeviceInfo.AudioDeviceType;
import static android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
import static android.media.AudioDeviceInfo.TYPE_DOCK;
import static android.media.AudioDeviceInfo.TYPE_HDMI;
import static android.media.AudioDeviceInfo.TYPE_HDMI_ARC;
import static android.media.AudioDeviceInfo.TYPE_HDMI_EARC;
import static android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY;
import static android.media.AudioDeviceInfo.TYPE_USB_DEVICE;
import static android.media.AudioDeviceInfo.TYPE_USB_HEADSET;
import static android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES;
import static android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET;

import android.annotation.DrawableRes;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.media.MediaRoute2Info;
import android.os.SystemProperties;
import android.util.SparseIntArray;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settingslib.R;
import com.android.settingslib.media.flags.Flags;

import java.util.Arrays;
import java.util.Objects;

/** A util class to get the appropriate icon for different device types. */
public class DeviceIconUtil {

    private static final SparseIntArray AUDIO_DEVICE_TO_MEDIA_ROUTE_TYPE = new SparseIntArray();

    private final boolean mIsTv;
    private final boolean mIsTablet;
    private final Context mContext;
    public DeviceIconUtil(@NonNull Context context) {
        mContext = Objects.requireNonNull(context);
        mIsTv =
                mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                        && Flags.enableTvMediaOutputDialog();
        mIsTablet =
                Arrays.asList(SystemProperties.get("ro.build.characteristics").split(","))
                        .contains("tablet");
    }

    @VisibleForTesting
    /* package */ DeviceIconUtil(boolean isTv) {
        mContext = null;
        mIsTv = isTv;
        mIsTablet = false;
    }

    /** Returns a drawable for an icon representing the given audioDeviceType. */
    public Drawable getIconFromAudioDeviceType(@AudioDeviceType int audioDeviceType) {
        return mContext.getDrawable(getIconResIdFromAudioDeviceType(audioDeviceType));
    }

    /** Returns a drawable res ID for an icon representing the given audioDeviceType. */
    @DrawableRes
    public int getIconResIdFromAudioDeviceType(@AudioDeviceType int audioDeviceType) {
        int mediaRouteType =
                AUDIO_DEVICE_TO_MEDIA_ROUTE_TYPE.get(audioDeviceType, /* defaultValue */ -1);
        return getIconResIdFromMediaRouteType(mediaRouteType);
    }

    /** Returns a drawable res ID for an icon representing the given mediaRouteType. */
    @DrawableRes
    public int getIconResIdFromMediaRouteType(@MediaRoute2Info.Type int type) {
        return mIsTv
                ? getIconResourceIdForTv(type)
                : getIconResourceIdForPhoneOrTablet(type, mIsTablet);
    }

    @SuppressLint("SwitchIntDef")
    @DrawableRes
    private static int getIconResourceIdForPhoneOrTablet(
            @MediaRoute2Info.Type int type, boolean isTablet) {
        int defaultResId = isTablet ? R.drawable.ic_media_tablet : R.drawable.ic_smartphone;

        return switch (type) {
            case MediaRoute2Info.TYPE_USB_DEVICE,
                            MediaRoute2Info.TYPE_USB_HEADSET,
                            MediaRoute2Info.TYPE_USB_ACCESSORY,
                            MediaRoute2Info.TYPE_WIRED_HEADSET,
                            MediaRoute2Info.TYPE_WIRED_HEADPHONES ->
                    R.drawable.ic_headphone;
            case MediaRoute2Info.TYPE_DOCK -> R.drawable.ic_dock_device;
            case MediaRoute2Info.TYPE_HDMI,
                            MediaRoute2Info.TYPE_HDMI_ARC,
                            MediaRoute2Info.TYPE_HDMI_EARC ->
                    R.drawable.ic_external_display;
            default -> defaultResId; // Includes TYPE_BUILTIN_SPEAKER.
        };
    }

    @SuppressLint("SwitchIntDef")
    @DrawableRes
    private static int getIconResourceIdForTv(@MediaRoute2Info.Type int type) {
        return switch (type) {
            case MediaRoute2Info.TYPE_USB_DEVICE, MediaRoute2Info.TYPE_USB_HEADSET ->
                    R.drawable.ic_headphone;
            case MediaRoute2Info.TYPE_USB_ACCESSORY -> R.drawable.ic_usb;
            case MediaRoute2Info.TYPE_DOCK -> R.drawable.ic_dock_device;
            case MediaRoute2Info.TYPE_HDMI, MediaRoute2Info.TYPE_BUILTIN_SPEAKER ->
                    R.drawable.ic_tv;
            case MediaRoute2Info.TYPE_HDMI_ARC, MediaRoute2Info.TYPE_HDMI_EARC ->
                    R.drawable.ic_hdmi;
            case MediaRoute2Info.TYPE_WIRED_HEADSET, MediaRoute2Info.TYPE_WIRED_HEADPHONES ->
                    R.drawable.ic_wired_device;
            default -> R.drawable.ic_media_speaker_device;
        };
    }

    static {
        AUDIO_DEVICE_TO_MEDIA_ROUTE_TYPE.put(TYPE_USB_DEVICE, MediaRoute2Info.TYPE_USB_DEVICE);
        AUDIO_DEVICE_TO_MEDIA_ROUTE_TYPE.put(TYPE_USB_HEADSET, MediaRoute2Info.TYPE_USB_HEADSET);
        AUDIO_DEVICE_TO_MEDIA_ROUTE_TYPE.put(
                TYPE_USB_ACCESSORY, MediaRoute2Info.TYPE_USB_ACCESSORY);
        AUDIO_DEVICE_TO_MEDIA_ROUTE_TYPE.put(TYPE_DOCK, MediaRoute2Info.TYPE_DOCK);
        AUDIO_DEVICE_TO_MEDIA_ROUTE_TYPE.put(TYPE_HDMI, MediaRoute2Info.TYPE_HDMI);
        AUDIO_DEVICE_TO_MEDIA_ROUTE_TYPE.put(TYPE_HDMI_ARC, MediaRoute2Info.TYPE_HDMI_ARC);
        AUDIO_DEVICE_TO_MEDIA_ROUTE_TYPE.put(TYPE_HDMI_EARC, MediaRoute2Info.TYPE_HDMI_EARC);
        AUDIO_DEVICE_TO_MEDIA_ROUTE_TYPE.put(
                TYPE_WIRED_HEADSET, MediaRoute2Info.TYPE_WIRED_HEADSET);
        AUDIO_DEVICE_TO_MEDIA_ROUTE_TYPE.put(
                TYPE_WIRED_HEADPHONES, MediaRoute2Info.TYPE_WIRED_HEADPHONES);
        AUDIO_DEVICE_TO_MEDIA_ROUTE_TYPE.put(
                TYPE_BUILTIN_SPEAKER, MediaRoute2Info.TYPE_BUILTIN_SPEAKER);
    }
}
