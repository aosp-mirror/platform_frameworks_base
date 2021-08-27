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

package com.android.server.hdmi;

import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.HdmiTvClient;
import android.hardware.hdmi.IHdmiControlCallback;
import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.hdmi.HdmiControlService.SendMessageCallback;

/**
 * Handles an action that selects a logical device as a new active source.
 *
 * Triggered by {@link HdmiTvClient}, attempts to select the given target device
 * for a new active source. It does its best to wake up the target in standby mode
 * before issuing the command &gt;Set Stream path&lt;.
 */
final class DeviceSelectAction extends HdmiCecFeatureAction {
    private static final String TAG = "DeviceSelect";

    // Time in milliseconds we wait for the device power status to switch to 'Standby'
    private static final int TIMEOUT_TRANSIT_TO_STANDBY_MS = 5 * 1000;

    // Time in milliseconds we wait for the device power status to turn to 'On'.
    private static final int TIMEOUT_POWER_ON_MS = 5 * 1000;

    // The number of times we try to wake up the target device before we give up
    // and just send <Set Stream Path>.
    private static final int LOOP_COUNTER_MAX = 20;

    // State in which we wait for <Report Power Status> to come in response to the command
    // <Give Device Power Status> we have sent.
    @VisibleForTesting
    static final int STATE_WAIT_FOR_REPORT_POWER_STATUS = 1;

    // State in which we wait for the device power status to switch to 'Standby'.
    // We wait till the status becomes 'Standby' before we send <Set Stream Path>
    // to wake up the device again.
    private static final int STATE_WAIT_FOR_DEVICE_TO_TRANSIT_TO_STANDBY = 2;

    // State in which we wait for the device power status to switch to 'on'. We wait
    // maximum 100 seconds (20 * 5) before we give up and just send <Set Stream Path>.
    @VisibleForTesting
    static final int STATE_WAIT_FOR_DEVICE_POWER_ON = 3;

    private final HdmiDeviceInfo mTarget;
    private final HdmiCecMessage mGivePowerStatus;
    private final boolean mIsCec20;

    private int mPowerStatusCounter = 0;

    /**
     * Constructor.
     *
     * @param source {@link HdmiCecLocalDevice} instance
     * @param target target logical device that will be a new active source
     * @param callback callback object
     */
    DeviceSelectAction(HdmiCecLocalDeviceTv source, HdmiDeviceInfo target,
                              IHdmiControlCallback callback) {
        this(source, target, callback,
             source.getDeviceInfo().getCecVersion() >= HdmiControlManager.HDMI_CEC_VERSION_2_0
                     && target.getCecVersion() >= HdmiControlManager.HDMI_CEC_VERSION_2_0);
    }

    @VisibleForTesting
    DeviceSelectAction(HdmiCecLocalDeviceTv source, HdmiDeviceInfo target,
                       IHdmiControlCallback callback, boolean isCec20) {
        super(source, callback);
        mTarget = target;
        mGivePowerStatus = HdmiCecMessageBuilder.buildGiveDevicePowerStatus(
                getSourceAddress(), getTargetAddress());
        mIsCec20 = isCec20;
    }

    int getTargetAddress() {
        return mTarget.getLogicalAddress();
    }

    @Override
    public boolean start() {
        // Wake-up on <Set Stream Path> was not mandatory before CEC 2.0.
        // The message is re-sent at the end of the action for devices that don't support 2.0.
        sendSetStreamPath();

        if (!mIsCec20) {
            queryDevicePowerStatus();
        } else {
            int targetPowerStatus = HdmiControlManager.POWER_STATUS_UNKNOWN;
            HdmiDeviceInfo targetDevice = localDevice().mService.getHdmiCecNetwork()
                    .getCecDeviceInfo(getTargetAddress());
            if (targetDevice != null) {
                targetPowerStatus = targetDevice.getDevicePowerStatus();
            }
            if (targetPowerStatus == HdmiControlManager.POWER_STATUS_UNKNOWN) {
                queryDevicePowerStatus();
            } else if (targetPowerStatus == HdmiControlManager.POWER_STATUS_ON) {
                finishWithCallback(HdmiControlManager.RESULT_SUCCESS);
                return true;
            }
        }
        mState = STATE_WAIT_FOR_REPORT_POWER_STATUS;
        addTimer(mState, HdmiConfig.TIMEOUT_MS);
        return true;
    }

    private void queryDevicePowerStatus() {
        sendCommand(
                mGivePowerStatus,
                new SendMessageCallback() {
                    @Override
                    public void onSendCompleted(int error) {
                        if (error != SendMessageResult.SUCCESS) {
                            finishWithCallback(HdmiControlManager.RESULT_COMMUNICATION_FAILED);
                            return;
                        }
                    }
                });
    }

    @Override
    public boolean processCommand(HdmiCecMessage cmd) {
        if (cmd.getSource() != getTargetAddress()) {
            return false;
        }
        int opcode = cmd.getOpcode();
        byte[] params = cmd.getParams();
        switch (mState) {
            case STATE_WAIT_FOR_REPORT_POWER_STATUS:
                if (opcode == Constants.MESSAGE_REPORT_POWER_STATUS) {
                    return handleReportPowerStatus(params[0]);
                }
                return false;
            default:
                break;
        }
        return false;
    }

    private boolean handleReportPowerStatus(int powerStatus) {
        switch (powerStatus) {
            case HdmiControlManager.POWER_STATUS_ON:
                selectDevice();
                return true;
            case HdmiControlManager.POWER_STATUS_TRANSIENT_TO_STANDBY:
                if (mPowerStatusCounter < 4) {
                    mState = STATE_WAIT_FOR_DEVICE_TO_TRANSIT_TO_STANDBY;
                    addTimer(mState, TIMEOUT_TRANSIT_TO_STANDBY_MS);
                } else {
                    selectDevice();
                }
                return true;
            case HdmiControlManager.POWER_STATUS_STANDBY:
                if (mPowerStatusCounter == 0) {
                    turnOnDevice();
                    mState = STATE_WAIT_FOR_DEVICE_POWER_ON;
                    addTimer(mState, TIMEOUT_POWER_ON_MS);
                } else {
                    selectDevice();
                }
                return true;
            case HdmiControlManager.POWER_STATUS_TRANSIENT_TO_ON:
                if (mPowerStatusCounter < LOOP_COUNTER_MAX) {
                    mState = STATE_WAIT_FOR_DEVICE_POWER_ON;
                    addTimer(mState, TIMEOUT_POWER_ON_MS);
                } else {
                    selectDevice();
                }
                return true;
        }
        return false;
    }

    @Override
    public void handleTimerEvent(int timeoutState) {
        if (mState != timeoutState) {
            Slog.w(TAG, "Timer in a wrong state. Ignored.");
            return;
        }
        switch (mState) {
            case STATE_WAIT_FOR_REPORT_POWER_STATUS:
                if (tv().isPowerStandbyOrTransient()) {
                    finishWithCallback(HdmiControlManager.RESULT_INCORRECT_MODE);
                    return;
                }
                selectDevice();
                break;
            case STATE_WAIT_FOR_DEVICE_TO_TRANSIT_TO_STANDBY:
            case STATE_WAIT_FOR_DEVICE_POWER_ON:
                mPowerStatusCounter++;
                queryDevicePowerStatus();
                mState = STATE_WAIT_FOR_REPORT_POWER_STATUS;
                addTimer(mState, HdmiConfig.TIMEOUT_MS);
                break;
        }
    }

    private void turnOnDevice() {
        if (!mIsCec20) {
            sendUserControlPressedAndReleased(mTarget.getLogicalAddress(),
                    HdmiCecKeycode.CEC_KEYCODE_POWER);
            sendUserControlPressedAndReleased(mTarget.getLogicalAddress(),
                    HdmiCecKeycode.CEC_KEYCODE_POWER_ON_FUNCTION);
        }
    }

    private void selectDevice() {
        if (!mIsCec20) {
            sendSetStreamPath();
        }
        finishWithCallback(HdmiControlManager.RESULT_SUCCESS);
    }

    private void sendSetStreamPath() {
        // Turn the active source invalidated, which remains so till <Active Source> comes from
        // the selected device.
        tv().getActiveSource().invalidate();
        tv().setActivePath(mTarget.getPhysicalAddress());
        sendCommand(HdmiCecMessageBuilder.buildSetStreamPath(
                getSourceAddress(), mTarget.getPhysicalAddress()));
    }
}
