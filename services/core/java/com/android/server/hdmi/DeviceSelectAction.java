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

import android.hardware.hdmi.HdmiCec;
import android.hardware.hdmi.HdmiCecDeviceInfo;
import android.hardware.hdmi.HdmiCecMessage;
import android.hardware.hdmi.HdmiTvClient;
import android.hardware.hdmi.IHdmiControlCallback;
import android.os.RemoteException;
import android.util.Slog;

/**
 * Handles an action that selects a logical device as a new active source.
 *
 * Triggered by {@link HdmiTvClient}, attempts to select the given target device
 * for a new active source. It does its best to wake up the target in standby mode
 * before issuing the command &gt;Set Stream path&lt;.
 */
final class DeviceSelectAction extends FeatureAction {
    private static final String TAG = "DeviceSelect";

    // Time in milliseconds we wait for the device power status to switch to 'Standby'
    private static final int TIMEOUT_TRANSIT_TO_STANDBY_MS = 5 * 1000;

    // Time in milliseconds we wait for the device power status to turn to 'On'.
    private static final int TIMEOUT_POWER_ON_MS = 5 * 1000;

    // Time in milliseconds we wait for <Active Source>.
    private static final int TIMEOUT_ACTIVE_SOURCE_MS = 20 * 1000;

    // The number of times we try to wake up the target device before we give up
    // and just send <Set Stream Path>.
    private static final int LOOP_COUNTER_MAX = 20;

    // State in which we wait for <Report Power Status> to come in response to the command
    // <Give Device Power Status> we have sent.
    private static final int STATE_WAIT_FOR_REPORT_POWER_STATUS = 1;

    // State in which we wait for the device power status to switch to 'Standby'.
    // We wait till the status becomes 'Standby' before we send <Set Stream Path>
    // to wake up the device again.
    private static final int STATE_WAIT_FOR_DEVICE_TO_TRANSIT_TO_STANDBY = 2;

    // State in which we wait for the device power status to switch to 'on'. We wait
    // maximum 100 seconds (20 * 5) before we give up and just send <Set Stream Path>.
    private static final int STATE_WAIT_FOR_DEVICE_POWER_ON = 3;

    // State in which we wait for the <Active Source> in response to the command
    // <Set Stream Path> we have sent. We wait as much as TIMEOUT_ACTIVE_SOURCE_MS
    // before we give up and mark the action as failure.
    private static final int STATE_WAIT_FOR_ACTIVE_SOURCE = 4;

    private final HdmiCecDeviceInfo mTarget;
    private final IHdmiControlCallback mCallback;

    private int mPowerStatusCounter = 0;

    /**
     * Constructor.
     *
     * @param source {@link HdmiCecLocalDevice} instance
     * @param target target logical device that will be a new active source
     * @param callback callback object
     */
    public DeviceSelectAction(HdmiCecLocalDevice source,
            HdmiCecDeviceInfo target, IHdmiControlCallback callback) {
        super(source);
        mCallback = callback;
        mTarget = target;
    }

    @Override
    public boolean start() {
        // TODO: Call the logic that display a banner saying the select action got started.
        queryDevicePowerStatus();
        return true;
    }

    private void queryDevicePowerStatus() {
        sendCommand(HdmiCecMessageBuilder.buildGiveDevicePowerStatus(
                getSourceAddress(), mTarget.getLogicalAddress()));
        mState = STATE_WAIT_FOR_REPORT_POWER_STATUS;
        addTimer(mState, TIMEOUT_MS);
    }

    @Override
    public boolean processCommand(HdmiCecMessage cmd) {
        if (cmd.getSource() != mTarget.getLogicalAddress()) {
            return false;
        }
        int opcode = cmd.getOpcode();
        byte[] params = cmd.getParams();

        switch (mState) {
            case STATE_WAIT_FOR_REPORT_POWER_STATUS:
                if (opcode == HdmiCec.MESSAGE_REPORT_POWER_STATUS && params.length == 1) {
                    return handleReportPowerStatus(params[0]);
                }
                return false;
            case STATE_WAIT_FOR_ACTIVE_SOURCE:
                if (opcode == HdmiCec.MESSAGE_ACTIVE_SOURCE && params.length == 2) {
                    int activePath = HdmiUtils.twoBytesToInt(params);
                    ActiveSourceHandler
                            .create(localDevice(), mCallback)
                            .process(cmd.getSource(), activePath);
                    finish();
                    return true;
                }
                return false;
            default:
                break;
        }
        return false;
    }

    private boolean handleReportPowerStatus(int powerStatus) {
        // TODO: Check TV's own status which might have been updated during the action.
        //       If in 'Standby' or 'Transit to standby', remove the banner
        //       and stop this action. Otherwise, send <Set Stream Path>
        switch (powerStatus) {
            case HdmiCec.POWER_STATUS_ON:
                sendSetStreamPath();
                return true;
            case HdmiCec.POWER_STATUS_TRANSIENT_TO_STANDBY:
                if (mPowerStatusCounter < 4) {
                    mState = STATE_WAIT_FOR_DEVICE_TO_TRANSIT_TO_STANDBY;
                    addTimer(mState, TIMEOUT_TRANSIT_TO_STANDBY_MS);
                } else {
                    sendSetStreamPath();
                }
                return true;
            case HdmiCec.POWER_STATUS_STANDBY:
                if (mPowerStatusCounter == 0) {
                    turnOnDevice();
                } else {
                    sendSetStreamPath();
                }
                return true;
            case HdmiCec.POWER_STATUS_TRANSIENT_TO_ON:
                if (mPowerStatusCounter < LOOP_COUNTER_MAX) {
                    mState = STATE_WAIT_FOR_DEVICE_POWER_ON;
                    addTimer(mState, TIMEOUT_POWER_ON_MS);
                } else {
                    sendSetStreamPath();
                }
                return true;
        }
        return false;
    }

    private void turnOnDevice() {
        sendRemoteKeyCommand(HdmiConstants.UI_COMMAND_POWER);
        sendRemoteKeyCommand(HdmiConstants.UI_COMMAND_POWER_ON_FUNCTION);
        mState = STATE_WAIT_FOR_DEVICE_POWER_ON;
        addTimer(mState, TIMEOUT_POWER_ON_MS);
    }

    private void sendSetStreamPath() {
        sendCommand(HdmiCecMessageBuilder.buildSetStreamPath(
                getSourceAddress(), mTarget.getPhysicalAddress()));
        mState = STATE_WAIT_FOR_ACTIVE_SOURCE;
        addTimer(mState, TIMEOUT_ACTIVE_SOURCE_MS);
    }

    private void sendRemoteKeyCommand(int keyCode) {
        sendCommand(HdmiCecMessageBuilder.buildUserControlPressed(getSourceAddress(),
                mTarget.getLogicalAddress(), keyCode));
        sendCommand(HdmiCecMessageBuilder.buildUserControlReleased(getSourceAddress(),
                mTarget.getLogicalAddress()));
    }

    @Override
    public void handleTimerEvent(int timeoutState) {
        if (mState != timeoutState) {
            Slog.w(TAG, "Timer in a wrong state. Ignored.");
            return;
        }
        switch (mState) {
            case STATE_WAIT_FOR_REPORT_POWER_STATUS:
                sendSetStreamPath();
                break;
            case STATE_WAIT_FOR_DEVICE_TO_TRANSIT_TO_STANDBY:
            case STATE_WAIT_FOR_DEVICE_POWER_ON:
                mPowerStatusCounter++;
                queryDevicePowerStatus();
                break;
            case STATE_WAIT_FOR_ACTIVE_SOURCE:
                // TODO: Remove the banner
                //       Display banner "Communication failed. Please check your cable or connection"
                invokeCallback(HdmiCec.RESULT_TIMEOUT);
                finish();
                break;
        }
    }

    private void invokeCallback(int result) {
        if (mCallback == null) {
            return;
        }
        try {
            mCallback.onComplete(result);
        } catch (RemoteException e) {
            Slog.e(TAG, "Callback failed:" + e);
        }
    }

    int getTargetAddress() {
        return mTarget.getLogicalAddress();
    }
}
