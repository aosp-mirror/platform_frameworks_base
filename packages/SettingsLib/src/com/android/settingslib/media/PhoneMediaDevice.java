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

import static android.media.MediaRoute2Info.TYPE_BUILTIN_SPEAKER;
import static android.media.MediaRoute2Info.TYPE_DOCK;
import static android.media.MediaRoute2Info.TYPE_HDMI;
import static android.media.MediaRoute2Info.TYPE_HDMI_ARC;
import static android.media.MediaRoute2Info.TYPE_HDMI_EARC;
import static android.media.MediaRoute2Info.TYPE_USB_ACCESSORY;
import static android.media.MediaRoute2Info.TYPE_USB_DEVICE;
import static android.media.MediaRoute2Info.TYPE_USB_HEADSET;
import static android.media.MediaRoute2Info.TYPE_WIRED_HEADPHONES;
import static android.media.MediaRoute2Info.TYPE_WIRED_HEADSET;

import static com.android.settingslib.media.MediaDevice.SelectionBehavior.SELECTION_BEHAVIOR_TRANSFER;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.HdmiPortInfo;
import android.media.MediaRoute2Info;
import android.media.RouteListingPreference;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.settingslib.R;
import com.android.settingslib.media.flags.Flags;

import java.util.List;

/**
 * PhoneMediaDevice extends MediaDevice to represents Phone device.
 */
public class PhoneMediaDevice extends MediaDevice {

    private static final String TAG = "PhoneMediaDevice";

    public static final String PHONE_ID = "phone_media_device_id";
    // For 3.5 mm wired headset
    public static final String WIRED_HEADSET_ID = "wired_headset_media_device_id";
    public static final String USB_HEADSET_ID = "usb_headset_media_device_id";

    private String mSummary = "";

    private final DeviceIconUtil mDeviceIconUtil;

    /** Returns the device name for the given {@code routeInfo}. */
    public static String getSystemRouteNameFromType(
            @NonNull Context context, @NonNull MediaRoute2Info routeInfo) {
        CharSequence name;
        boolean isTv = isTv(context);
        switch (routeInfo.getType()) {
            case TYPE_WIRED_HEADSET:
            case TYPE_WIRED_HEADPHONES:
            case TYPE_USB_DEVICE:
            case TYPE_USB_HEADSET:
            case TYPE_USB_ACCESSORY:
                name = context.getString(R.string.media_transfer_wired_usb_device_name);
                break;
            case TYPE_DOCK:
                name = context.getString(R.string.media_transfer_dock_speaker_device_name);
                break;
            case TYPE_BUILTIN_SPEAKER:
                name = context.getString(R.string.media_transfer_this_device_name);
                break;
            case TYPE_HDMI:
                name = context.getString(isTv ? R.string.tv_media_transfer_default :
                        R.string.media_transfer_external_device_name);
                break;
            case TYPE_HDMI_ARC:
                if (isTv) {
                    String deviceName = getHdmiOutDeviceName(context);
                    if (deviceName != null) {
                        name = deviceName;
                    } else {
                        name = context.getString(R.string.tv_media_transfer_arc_fallback_title);
                    }
                } else {
                    name = context.getString(R.string.media_transfer_external_device_name);
                }
                break;
            case TYPE_HDMI_EARC:
                if (isTv) {
                    String deviceName = getHdmiOutDeviceName(context);
                    if (deviceName != null) {
                        name = deviceName;
                    } else {
                        name = context.getString(R.string.tv_media_transfer_arc_fallback_title);
                    }
                } else {
                    name = context.getString(R.string.media_transfer_external_device_name);
                }
                break;
            default:
                name = context.getString(R.string.media_transfer_default_device_name);
                break;
        }
        return name.toString();
    }

    PhoneMediaDevice(Context context, MediaRoute2Info info) {
        this(context, info, null);
    }

    PhoneMediaDevice(
            Context context,
            MediaRoute2Info info,
            RouteListingPreference.Item item) {
        super(context, info, item);
        mDeviceIconUtil = new DeviceIconUtil(mContext);
        initDeviceRecord();
    }

    static boolean isTv(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                && Flags.enableTvMediaOutputDialog();
    }

    // MediaRoute2Info.getType was made public on API 34, but exists since API 30.
    @SuppressWarnings("NewApi")
    @Override
    public String getName() {
        return getSystemRouteNameFromType(mContext, mRouteInfo);
    }

    @Override
    public int getSelectionBehavior() {
        // We don't allow apps to override the selection behavior of system routes.
        return SELECTION_BEHAVIOR_TRANSFER;
    }

    private static String getHdmiOutDeviceName(Context context) {
        HdmiControlManager hdmiControlManager;
        if (context.checkCallingOrSelfPermission(Manifest.permission.HDMI_CEC)
                == PackageManager.PERMISSION_GRANTED) {
            hdmiControlManager = context.getSystemService(HdmiControlManager.class);
        } else {
            Log.w(TAG, "Could not get HDMI device name, android.permission.HDMI_CEC denied");
            return null;
        }

        HdmiPortInfo hdmiOutputPortInfo = null;
        for (HdmiPortInfo hdmiPortInfo : hdmiControlManager.getPortInfo()) {
            if (hdmiPortInfo.getType() == HdmiPortInfo.PORT_OUTPUT) {
                hdmiOutputPortInfo = hdmiPortInfo;
                break;
            }
        }
        if (hdmiOutputPortInfo == null) {
            return null;
        }
        List<HdmiDeviceInfo> connectedDevices = hdmiControlManager.getConnectedDevices();
        for (HdmiDeviceInfo deviceInfo : connectedDevices) {
            if (deviceInfo.getPortId() == hdmiOutputPortInfo.getId()) {
                String deviceName = deviceInfo.getDisplayName();
                if (deviceName != null && !deviceName.isEmpty()) {
                    return deviceName;
                }
            }
        }
        return null;
    }

    @Override
    public String getSummary() {
        if (!isTv(mContext)) {
            return mSummary;
        }
        switch (mRouteInfo.getType()) {
            case TYPE_BUILTIN_SPEAKER:
                return mContext.getString(R.string.tv_media_transfer_internal_speakers);
            case TYPE_HDMI:
                return mContext.getString(R.string.tv_media_transfer_hdmi);
            case TYPE_HDMI_ARC:
                if (getHdmiOutDeviceName(mContext) == null) {
                    // Connection type is already part of the title.
                    return mContext.getString(R.string.tv_media_transfer_connected);
                }
                return mContext.getString(R.string.tv_media_transfer_arc_subtitle);
            case TYPE_HDMI_EARC:
                if (getHdmiOutDeviceName(mContext) == null) {
                    // Connection type is already part of the title.
                    return mContext.getString(R.string.tv_media_transfer_connected);
                }
                return mContext.getString(R.string.tv_media_transfer_earc_subtitle);
            default:
                return null;
        }

    }

    @Override
    public Drawable getIcon() {
        return getIconWithoutBackground();
    }

    @Override
    public Drawable getIconWithoutBackground() {
        return mContext.getDrawable(getDrawableResId());
    }

    // MediaRoute2Info.getType was made public on API 34, but exists since API 30.
    @SuppressWarnings("NewApi")
    @VisibleForTesting
    int getDrawableResId() {
        return mDeviceIconUtil.getIconResIdFromMediaRouteType(mRouteInfo.getType());
    }

    // MediaRoute2Info.getType was made public on API 34, but exists since API 30.
    @SuppressWarnings("NewApi")
    @Override
    public String getId() {
        String id;
        switch (mRouteInfo.getType()) {
            case TYPE_WIRED_HEADSET:
            case TYPE_WIRED_HEADPHONES:
                id = WIRED_HEADSET_ID;
                break;
            case TYPE_USB_DEVICE:
            case TYPE_USB_HEADSET:
            case TYPE_USB_ACCESSORY:
            case TYPE_DOCK:
            case TYPE_HDMI:
            case TYPE_HDMI_ARC:
            case TYPE_HDMI_EARC:
                id = USB_HEADSET_ID;
                break;
            case TYPE_BUILTIN_SPEAKER:
            default:
                id = PHONE_ID;
                break;
        }
        return id;
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    /**
     * According current active device is {@link PhoneMediaDevice} or not to update summary.
     */
    public void updateSummary(boolean isActive) {
        mSummary = isActive
                ? mContext.getString(R.string.bluetooth_active_no_battery_level)
                : "";
    }
}
