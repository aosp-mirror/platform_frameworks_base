/*
 * Copyright (C) 2021 The Android Open Source Project
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
import android.hardware.hdmi.HdmiPlaybackClient;
import android.hardware.hdmi.IHdmiControlCallback;
import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Handles an action that selects a logical device as a new active source.
 *
 * Triggered by {@link HdmiPlaybackClient}, attempts to select the given target device
 * for a new active source. A &gt;Routing Change&lt; command is issued in order to select
 * the target device. If that doesn't succeed a &gt;Set Stream Path&lt; command is sent.
 * It does its best to wake up the target in standby mode, before issuing the device
 * select command.
 */
final class DeviceSelectActionFromPlayback extends HdmiCecFeatureAction {
    private static final String TAG = "DeviceSelectActionFromPlayback";

    // Time in milliseconds we wait for the device power status to switch to 'Standby'
    private static final int TIMEOUT_TRANSIT_TO_STANDBY_MS = 5 * 1000;

    // Time in milliseconds we wait for the device power status to turn to 'On'.
    private static final int TIMEOUT_POWER_ON_MS = 5 * 1000;

    // The number of times we try to wake up the target device before we give up
    // and just send <Routing Change>.
    private static final int LOOP_COUNTER_MAX = 2;

    // State in which we wait for <Report Power Status> to come in response to the command
    // <Give Device Power Status> we have sent.
    @VisibleForTesting
    static final int STATE_WAIT_FOR_REPORT_POWER_STATUS = 1;

    // State in which we wait for the device power status to switch to 'Standby'.
    // We wait till the status becomes 'Standby' before we send <Routing Change>
    // to wake up the device again.
    private static final int STATE_WAIT_FOR_DEVICE_TO_TRANSIT_TO_STANDBY = 2;

    // State in which we wait for the device power status to switch to 'on'. We wait
    // maximum 100 seconds (20 * 5) before we give up and just send <Set Stream Path>.
    @VisibleForTesting
    static final int STATE_WAIT_FOR_DEVICE_POWER_ON = 3;

    // State in which we wait for <Active Source> to come in response to the command
    // <Routing Change> we have sent.
    @VisibleForTesting
    static final int STATE_WAIT_FOR_ACTIVE_SOURCE_MESSAGE_AFTER_ROUTING_CHANGE = 4;

    // State in which we wait for <Active Source> to come in response to the command
    // <Set Stream Path> we have sent.
    @VisibleForTesting
    private static final int STATE_WAIT_FOR_ACTIVE_SOURCE_MESSAGE_AFTER_SET_STREAM_PATH = 5;

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
    DeviceSelectActionFromPlayback(HdmiCecLocalDevicePlayback source, HdmiDeviceInfo target,
            IHdmiControlCallback callback) {
        this(source, target, callback,
                source.getDeviceInfo().getCecVersion() >= HdmiControlManager.HDMI_CEC_VERSION_2_0
                        && target.getCecVersion() >= HdmiControlManager.HDMI_CEC_VERSION_2_0);
    }

    @VisibleForTesting
    DeviceSelectActionFromPlayback(HdmiCecLocalDevicePlayback source, HdmiDeviceInfo target,
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

    private int getTargetPath() {
        return mTarget.getPhysicalAddress();
    }

    @Override
    public boolean start() {
        // This <Routing Change> message wakes up the target device in most cases.
        sendRoutingChange();

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
                mState = STATE_WAIT_FOR_ACTIVE_SOURCE_MESSAGE_AFTER_ROUTING_CHANGE;
                addTimer(mState, HdmiConfig.TIMEOUT_MS);
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
                new HdmiControlService.SendMessageCallback() {
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
        if (opcode == Constants.MESSAGE_ACTIVE_SOURCE) {
            // The target device was successfully set as the active source
            finishWithCallback(HdmiControlManager.RESULT_SUCCESS);
            return true;
        }
        if (mState == STATE_WAIT_FOR_REPORT_POWER_STATUS
                && opcode == Constants.MESSAGE_REPORT_POWER_STATUS) {
            return handleReportPowerStatus(params[0]);
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
                    sendRoutingChange();
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

    private void selectDevice() {
        sendRoutingChange();
        mState = STATE_WAIT_FOR_ACTIVE_SOURCE_MESSAGE_AFTER_ROUTING_CHANGE;
        addTimer(mState, HdmiConfig.TIMEOUT_MS);
    }

    @Override
    void handleTimerEvent(int timeoutState) {
        if (mState != timeoutState) {
            Slog.w(TAG, "Timer in a wrong state. Ignored.");
            return;
        }
        switch (mState) {
            case STATE_WAIT_FOR_REPORT_POWER_STATUS:
                selectDevice();
                addTimer(mState, HdmiConfig.TIMEOUT_MS);
                break;
            case STATE_WAIT_FOR_DEVICE_POWER_ON:
                mPowerStatusCounter++;
                queryDevicePowerStatus();
                mState = STATE_WAIT_FOR_REPORT_POWER_STATUS;
                addTimer(mState, HdmiConfig.TIMEOUT_MS);
                break;
            case STATE_WAIT_FOR_ACTIVE_SOURCE_MESSAGE_AFTER_ROUTING_CHANGE:
                sendSetStreamPath();
                mState = STATE_WAIT_FOR_ACTIVE_SOURCE_MESSAGE_AFTER_SET_STREAM_PATH;
                addTimer(mState, HdmiConfig.TIMEOUT_MS);
                break;
            case STATE_WAIT_FOR_ACTIVE_SOURCE_MESSAGE_AFTER_SET_STREAM_PATH:
                finishWithCallback(HdmiControlManager.RESULT_TIMEOUT);
                break;
        }
    }

    private void sendRoutingChange() {
        sendCommand(HdmiCecMessageBuilder.buildRoutingChange(getSourceAddress(),
                playback().getActiveSource().physicalAddress, getTargetPath()));
    }

    private void sendSetStreamPath() {
        sendCommand(HdmiCecMessageBuilder.buildSetStreamPath(getSourceAddress(),
                getTargetPath()));
    }
}
