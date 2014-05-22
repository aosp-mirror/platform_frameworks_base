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

import android.hardware.hdmi.IHdmiControlCallback;
import android.hardware.hdmi.HdmiCec;
import android.hardware.hdmi.HdmiCecMessage;
import android.os.RemoteException;
import android.util.Slog;

/**
 * Feature action that queries the power status of other device.
 *
 * This action is initiated via {@link HdmiControlManager#queryDisplayStatus()} from
 * the Android system working as playback device to get the power status of TV device.
 *
 * <p>Package-private, accessed by {@link HdmiControlService} only.
 */

final class DevicePowerStatusAction extends FeatureAction {
    private static final String TAG = "DevicePowerStatusAction";

    // State in which the action is waiting for <Report Power Status>.
    private static final int STATE_WAITING_FOR_REPORT_POWER_STATUS = 1;

    private final int mTargetAddress;
    private final IHdmiControlCallback mCallback;

    static DevicePowerStatusAction create(HdmiControlService service, int sourceAddress,
            int targetAddress, IHdmiControlCallback callback) {
        if (service == null || callback == null) {
            Slog.e(TAG, "Wrong arguments");
            return null;
        }
        return new DevicePowerStatusAction(service, sourceAddress, targetAddress, callback);
    }

    private DevicePowerStatusAction(HdmiControlService service, int sourceAddress,
            int targetAddress, IHdmiControlCallback callback) {
        super(service, sourceAddress);
        mTargetAddress = targetAddress;
        mCallback = callback;
    }

    @Override
    boolean start() {
        queryDevicePowerStatus();
        mState = STATE_WAITING_FOR_REPORT_POWER_STATUS;
        addTimer(mState, FeatureAction.TIMEOUT_MS);
        return true;
    }

    private void queryDevicePowerStatus() {
        mService.sendCecCommand(
                HdmiCecMessageBuilder.buildGiveDevicePowerStatus(mSourceAddress, mTargetAddress));
    }

    @Override
    boolean processCommand(HdmiCecMessage cmd) {
        if (mState != STATE_WAITING_FOR_REPORT_POWER_STATUS) {
            return false;
        }
        if (cmd.getOpcode() == HdmiCec.MESSAGE_REPORT_POWER_STATUS) {
            int status = cmd.getParams()[0];
            invokeCallback(status);
            finish();
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
            // Got no response from TV. Report status 'unknown'.
            invokeCallback(HdmiCec.POWER_STATUS_UNKNOWN);
            finish();
        }
    }

    private void invokeCallback(int result) {
        try {
            mCallback.onComplete(result);
        } catch (RemoteException e) {
            Slog.e(TAG, "Callback failed:" + e);
        }
    }
}
