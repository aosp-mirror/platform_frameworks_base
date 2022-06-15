/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.media.dialog;

import static android.media.MediaRoute2ProviderService.REASON_INVALID_COMMAND;
import static android.media.MediaRoute2ProviderService.REASON_NETWORK_ERROR;
import static android.media.MediaRoute2ProviderService.REASON_REJECTED;
import static android.media.MediaRoute2ProviderService.REASON_ROUTE_NOT_AVAILABLE;
import static android.media.MediaRoute2ProviderService.REASON_UNKNOWN_ERROR;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import com.android.settingslib.media.MediaDevice;
import com.android.systemui.shared.system.SysUiStatsLog;

import java.util.List;

/**
 * Metric logger for media output features
 */
public class MediaOutputMetricLogger {

    private static final String TAG = "MediaOutputMetricLogger";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final Context mContext;
    private final String mPackageName;
    private MediaDevice mSourceDevice, mTargetDevice;
    private int mWiredDeviceCount;
    private int mConnectedBluetoothDeviceCount;
    private int mRemoteDeviceCount;
    private int mAppliedDeviceCountWithinRemoteGroup;

    public MediaOutputMetricLogger(Context context, String packageName) {
        mContext = context;
        mPackageName = packageName;
    }

    /**
     * Update the endpoints of a content switching operation.
     * This method should be called before a switching operation, so the metric logger can track
     * source and target devices.
     * @param source the current connected media device
     * @param target the target media device for content switching to
     */
    public void updateOutputEndPoints(MediaDevice source, MediaDevice target) {
        mSourceDevice = source;
        mTargetDevice = target;

        if (DEBUG) {
            Log.d(TAG, "updateOutputEndPoints -"
                    + " source:" + mSourceDevice.toString()
                    + " target:" + mTargetDevice.toString());
        }
    }

    /**
     * Do the metric logging of content switching success.
     * @param selectedDeviceType string representation of the target media device
     * @param deviceList media device list for device count updating
     */
    public void logOutputSuccess(String selectedDeviceType, List<MediaDevice> deviceList) {
        if (DEBUG) {
            Log.d(TAG, "logOutputSuccess - selected device: " + selectedDeviceType);
        }

        updateLoggingDeviceCount(deviceList);

        SysUiStatsLog.write(
                SysUiStatsLog.MEDIAOUTPUT_OP_SWITCH_REPORTED,
                getLoggingDeviceType(mSourceDevice, true),
                getLoggingDeviceType(mTargetDevice, false),
                SysUiStatsLog.MEDIA_OUTPUT_OP_SWITCH_REPORTED__RESULT__OK,
                SysUiStatsLog.MEDIA_OUTPUT_OP_SWITCH_REPORTED__SUBRESULT__NO_ERROR,
                getLoggingPackageName(),
                mWiredDeviceCount,
                mConnectedBluetoothDeviceCount,
                mRemoteDeviceCount,
                mAppliedDeviceCountWithinRemoteGroup);
    }

    /**
     * Do the metric logging of content switching failure.
     * @param deviceList media device list for device count updating
     * @param reason the reason of content switching failure
     */
    public void logOutputFailure(List<MediaDevice> deviceList, int reason) {
        if (DEBUG) {
            Log.e(TAG, "logRequestFailed - " + reason);
        }

        updateLoggingDeviceCount(deviceList);

        SysUiStatsLog.write(
                SysUiStatsLog.MEDIAOUTPUT_OP_SWITCH_REPORTED,
                getLoggingDeviceType(mSourceDevice, true),
                getLoggingDeviceType(mTargetDevice, false),
                SysUiStatsLog.MEDIA_OUTPUT_OP_SWITCH_REPORTED__RESULT__ERROR,
                getLoggingSwitchOpSubResult(reason),
                getLoggingPackageName(),
                mWiredDeviceCount,
                mConnectedBluetoothDeviceCount,
                mRemoteDeviceCount,
                mAppliedDeviceCountWithinRemoteGroup);
    }

    private void updateLoggingDeviceCount(List<MediaDevice> deviceList) {
        mWiredDeviceCount = mConnectedBluetoothDeviceCount = mRemoteDeviceCount = 0;
        mAppliedDeviceCountWithinRemoteGroup = 0;

        for (MediaDevice mediaDevice : deviceList) {
            if (mediaDevice.isConnected()) {
                switch (mediaDevice.getDeviceType()) {
                    case MediaDevice.MediaDeviceType.TYPE_3POINT5_MM_AUDIO_DEVICE:
                    case MediaDevice.MediaDeviceType.TYPE_USB_C_AUDIO_DEVICE:
                        mWiredDeviceCount++;
                        break;
                    case MediaDevice.MediaDeviceType.TYPE_BLUETOOTH_DEVICE:
                        mConnectedBluetoothDeviceCount++;
                        break;
                    case MediaDevice.MediaDeviceType.TYPE_CAST_DEVICE:
                    case MediaDevice.MediaDeviceType.TYPE_CAST_GROUP_DEVICE:
                        mRemoteDeviceCount++;
                        break;
                    default:
                }
            }
        }

        if (DEBUG) {
            Log.d(TAG, "connected devices:" + " wired: " + mWiredDeviceCount
                    + " bluetooth: " + mConnectedBluetoothDeviceCount
                    + " remote: " + mRemoteDeviceCount);
        }
    }

    private int getLoggingDeviceType(MediaDevice device, boolean isSourceDevice) {
        switch (device.getDeviceType()) {
            case MediaDevice.MediaDeviceType.TYPE_PHONE_DEVICE:
                return isSourceDevice
                        ? SysUiStatsLog.MEDIA_OUTPUT_OP_SWITCH_REPORTED__SOURCE__BUILTIN_SPEAKER
                        : SysUiStatsLog.MEDIA_OUTPUT_OP_SWITCH_REPORTED__TARGET__BUILTIN_SPEAKER;
            case MediaDevice.MediaDeviceType.TYPE_3POINT5_MM_AUDIO_DEVICE:
                return isSourceDevice
                        ? SysUiStatsLog
                        .MEDIA_OUTPUT_OP_SWITCH_REPORTED__SOURCE__WIRED_3POINT5_MM_AUDIO
                        : SysUiStatsLog
                                .MEDIA_OUTPUT_OP_SWITCH_REPORTED__TARGET__WIRED_3POINT5_MM_AUDIO;
            case MediaDevice.MediaDeviceType.TYPE_USB_C_AUDIO_DEVICE:
                return isSourceDevice
                        ? SysUiStatsLog.MEDIA_OUTPUT_OP_SWITCH_REPORTED__SOURCE__USB_C_AUDIO
                        : SysUiStatsLog.MEDIA_OUTPUT_OP_SWITCH_REPORTED__TARGET__USB_C_AUDIO;
            case MediaDevice.MediaDeviceType.TYPE_BLUETOOTH_DEVICE:
                return isSourceDevice
                        ? SysUiStatsLog.MEDIA_OUTPUT_OP_SWITCH_REPORTED__SOURCE__BLUETOOTH
                        : SysUiStatsLog.MEDIA_OUTPUT_OP_SWITCH_REPORTED__TARGET__BLUETOOTH;
            case MediaDevice.MediaDeviceType.TYPE_CAST_DEVICE:
                return isSourceDevice
                        ? SysUiStatsLog.MEDIA_OUTPUT_OP_SWITCH_REPORTED__SOURCE__REMOTE_SINGLE
                        : SysUiStatsLog.MEDIA_OUTPUT_OP_SWITCH_REPORTED__TARGET__REMOTE_SINGLE;
            case MediaDevice.MediaDeviceType.TYPE_CAST_GROUP_DEVICE:
                return isSourceDevice
                        ? SysUiStatsLog.MEDIA_OUTPUT_OP_SWITCH_REPORTED__SOURCE__REMOTE_GROUP
                        : SysUiStatsLog.MEDIA_OUTPUT_OP_SWITCH_REPORTED__TARGET__REMOTE_GROUP;
            default:
                return isSourceDevice
                        ? SysUiStatsLog.MEDIA_OUTPUT_OP_SWITCH_REPORTED__SOURCE__UNKNOWN_TYPE
                        : SysUiStatsLog.MEDIA_OUTPUT_OP_SWITCH_REPORTED__TARGET__UNKNOWN_TYPE;
        }
    }

    private int getLoggingSwitchOpSubResult(int reason) {
        switch (reason) {
            case REASON_REJECTED:
                return SysUiStatsLog.MEDIA_OUTPUT_OP_SWITCH_REPORTED__SUBRESULT__REJECTED;
            case REASON_NETWORK_ERROR:
                return SysUiStatsLog.MEDIA_OUTPUT_OP_SWITCH_REPORTED__SUBRESULT__NETWORK_ERROR;
            case REASON_ROUTE_NOT_AVAILABLE:
                return SysUiStatsLog
                        .MEDIA_OUTPUT_OP_SWITCH_REPORTED__SUBRESULT__ROUTE_NOT_AVAILABLE;
            case REASON_INVALID_COMMAND:
                return SysUiStatsLog.MEDIA_OUTPUT_OP_SWITCH_REPORTED__SUBRESULT__INVALID_COMMAND;
            case REASON_UNKNOWN_ERROR:
            default:
                return SysUiStatsLog.MEDIA_OUTPUT_OP_SWITCH_REPORTED__SUBRESULT__UNKNOWN_ERROR;
        }
    }

    private String getLoggingPackageName() {
        if (mPackageName != null && !mPackageName.isEmpty()) {
            try {
                final ApplicationInfo applicationInfo = mContext.getPackageManager()
                        .getApplicationInfo(mPackageName, /* default flag */ 0);
                if ((applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        || (applicationInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
                    return mPackageName;
                }
            } catch (Exception ex) {
                Log.e(TAG, mPackageName + " is invalid.");
            }
        }

        return "";
    }
}
