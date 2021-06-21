package com.android.server.hdmi;

/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.HdmiPlaybackClient;
import android.hardware.hdmi.HdmiPlaybackClient.DisplayStatusCallback;
import android.hardware.hdmi.IHdmiControlCallback;
import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.util.Slog;


/**
 * Feature action that queries the power status of other device. This action is initiated via
 * {@link HdmiPlaybackClient#queryDisplayStatus(DisplayStatusCallback)} from the Android system
 * working as playback device to get the power status of TV device.
 * <p>
 * Package-private, accessed by {@link HdmiControlService} only.
 */
final class DevicePowerStatusAction extends HdmiCecFeatureAction {
    private static final String TAG = "DevicePowerStatusAction";

    // State in which the action is waiting for <Report Power Status>.
    private static final int STATE_WAITING_FOR_REPORT_POWER_STATUS = 1;

    private final int mTargetAddress;

    // Retry the power status query as it might happen when the target device is waking up. In
    // that case a device may be quite busy and can fail to respond within the 2s timeout.
    private int mRetriesOnTimeout = 1;

    static DevicePowerStatusAction create(HdmiCecLocalDevice source,
            int targetAddress, IHdmiControlCallback callback) {
        if (source == null || callback == null) {
            Slog.e(TAG, "Wrong arguments");
            return null;
        }
        return new DevicePowerStatusAction(source, targetAddress, callback);
    }

    private DevicePowerStatusAction(HdmiCecLocalDevice localDevice,
            int targetAddress, IHdmiControlCallback callback) {
        super(localDevice, callback);
        mTargetAddress = targetAddress;
    }

    @Override
    boolean start() {
        HdmiControlService service = localDevice().mService;
        if (service.getCecVersion() >= HdmiControlManager.HDMI_CEC_VERSION_2_0) {
            HdmiDeviceInfo deviceInfo = service.getHdmiCecNetwork().getCecDeviceInfo(
                    mTargetAddress);
            if (deviceInfo != null
                    && deviceInfo.getCecVersion() >= HdmiControlManager.HDMI_CEC_VERSION_2_0) {
                int powerStatus = deviceInfo.getDevicePowerStatus();
                if (powerStatus != HdmiControlManager.POWER_STATUS_UNKNOWN) {
                    finishWithCallback(powerStatus);
                    return true;
                }
            }
        }
        queryDevicePowerStatus();
        mState = STATE_WAITING_FOR_REPORT_POWER_STATUS;
        addTimer(mState, HdmiConfig.TIMEOUT_MS);
        return true;
    }

    private void queryDevicePowerStatus() {
        sendCommand(HdmiCecMessageBuilder.buildGiveDevicePowerStatus(getSourceAddress(),
                mTargetAddress), error -> {
                // Don't retry on timeout if the remote device didn't even ACK the message. Assume
                // the device is not present or not capable of CEC.
                if (error == SendMessageResult.NACK) {
                    // Got no response from TV. Report status 'unknown'.
                    finishWithCallback(HdmiControlManager.POWER_STATUS_UNKNOWN);
                }
            });
    }

    @Override
    boolean processCommand(HdmiCecMessage cmd) {
        if (mState != STATE_WAITING_FOR_REPORT_POWER_STATUS
                || mTargetAddress != cmd.getSource()) {
            return false;
        }
        if (cmd.getOpcode() == Constants.MESSAGE_REPORT_POWER_STATUS) {
            int status = cmd.getParams()[0];
            finishWithCallback(status);
            return true;
        }
        return false;
    }

    @Override
    void handleTimerEvent(int state) {
        if (mState != state) {
            return;
        }
        if (state == STATE_WAITING_FOR_REPORT_POWER_STATUS) {
            if (mRetriesOnTimeout > 0) {
                mRetriesOnTimeout--;
                start();
                return;
            }

            // Got no response from TV. Report status 'unknown'.
            finishWithCallback(HdmiControlManager.POWER_STATUS_UNKNOWN);
        }
    }
}
