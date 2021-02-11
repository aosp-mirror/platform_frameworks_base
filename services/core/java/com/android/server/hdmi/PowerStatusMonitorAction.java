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

import static android.hardware.hdmi.HdmiControlManager.POWER_STATUS_UNKNOWN;

import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.util.SparseIntArray;

import com.android.server.hdmi.HdmiControlService.SendMessageCallback;

import java.util.List;

/**
 * Action that check each device's power status.
 */
public class PowerStatusMonitorAction extends HdmiCecFeatureAction {
    private static final String TAG = "PowerStatusMonitorAction";

    // State that waits for <Report Power Status> once sending <Give Device Power Status>
    // to all external devices.
    private static final int STATE_WAIT_FOR_REPORT_POWER_STATUS = 1;
    // State that waits for next monitoring
    private static final int STATE_WAIT_FOR_NEXT_MONITORING = 2;

    private static final int INVALID_POWER_STATUS = POWER_STATUS_UNKNOWN - 1;

    // Monitoring interval (60s)
    private static final int MONITORING_INTERNAL_MS = 60000;

    // Timeout once sending <Give Device Power Status>
    private static final int REPORT_POWER_STATUS_TIMEOUT_MS = 5000;

    // Container for current power status of all external devices.
    // The key is a logical address a device and the value is current power status of it
    // Whenever the action receives <Report Power Status> from a device,
    // it removes an entry of the given device.
    // If this is non-empty when timeout for STATE_WAIT_FOR_REPORT_POWER_STATUS happens,
    // updates power status of all remaining devices into POWER_STATUS_UNKNOWN.
    private final SparseIntArray mPowerStatus = new SparseIntArray();

    PowerStatusMonitorAction(HdmiCecLocalDevice source) {
        super(source);
    }

    @Override
    boolean start() {
        queryPowerStatus();
        return true;
    }

    @Override
    boolean processCommand(HdmiCecMessage cmd) {
        if (mState == STATE_WAIT_FOR_REPORT_POWER_STATUS
                && cmd.getOpcode() == Constants.MESSAGE_REPORT_POWER_STATUS) {
            return handleReportPowerStatus(cmd);
        }
        return false;
    }

    private boolean handleReportPowerStatus(HdmiCecMessage cmd) {
        int sourceAddress = cmd.getSource();
        int oldStatus = mPowerStatus.get(sourceAddress, INVALID_POWER_STATUS);
        if (oldStatus == INVALID_POWER_STATUS) {
            // if no device exists for incoming message, hands it over to other actions.
            return false;
        }
        int newStatus = cmd.getParams()[0] & 0xFF;
        updatePowerStatus(sourceAddress, newStatus, true);
        return true;
    }

    @Override
    void handleTimerEvent(int state) {
        switch (mState) {
            case STATE_WAIT_FOR_NEXT_MONITORING:
                queryPowerStatus();
                break;
            case STATE_WAIT_FOR_REPORT_POWER_STATUS:
                handleTimeout();
                break;
        }
    }

    private void handleTimeout() {
        for (int i = 0; i < mPowerStatus.size(); ++i) {
            int logicalAddress = mPowerStatus.keyAt(i);
            updatePowerStatus(logicalAddress, POWER_STATUS_UNKNOWN, false);
        }
        mPowerStatus.clear();
        mState = STATE_WAIT_FOR_NEXT_MONITORING;
    }

    private void resetPowerStatus(List<HdmiDeviceInfo> deviceInfos) {
        mPowerStatus.clear();
        for (HdmiDeviceInfo info : deviceInfos) {
            if (localDevice().mService.getCecVersion() < HdmiControlManager.HDMI_CEC_VERSION_2_0
                    || info.getCecVersion() < HdmiControlManager.HDMI_CEC_VERSION_2_0) {
                mPowerStatus.append(info.getLogicalAddress(), info.getDevicePowerStatus());
            }
        }
    }

    private void queryPowerStatus() {
        List<HdmiDeviceInfo> deviceInfos =
                localDevice().mService.getHdmiCecNetwork().getDeviceInfoList(false);
        resetPowerStatus(deviceInfos);
        for (HdmiDeviceInfo info : deviceInfos) {
            if (localDevice().mService.getCecVersion() < HdmiControlManager.HDMI_CEC_VERSION_2_0
                    || info.getCecVersion() < HdmiControlManager.HDMI_CEC_VERSION_2_0) {
                final int logicalAddress = info.getLogicalAddress();
                sendCommand(HdmiCecMessageBuilder.buildGiveDevicePowerStatus(getSourceAddress(),
                        logicalAddress),
                        new SendMessageCallback() {
                            @Override
                            public void onSendCompleted(int error) {
                                // If fails to send <Give Device Power Status>,
                                // update power status into UNKNOWN.
                                if (error != SendMessageResult.SUCCESS) {
                                    updatePowerStatus(logicalAddress, POWER_STATUS_UNKNOWN, true);
                                }
                            }
                        });
            }
        }

        mState = STATE_WAIT_FOR_REPORT_POWER_STATUS;

        // Add both timers, monitoring and timeout.
        addTimer(STATE_WAIT_FOR_NEXT_MONITORING, MONITORING_INTERNAL_MS);
        addTimer(STATE_WAIT_FOR_REPORT_POWER_STATUS, REPORT_POWER_STATUS_TIMEOUT_MS);
    }

    private void updatePowerStatus(int logicalAddress, int newStatus, boolean remove) {
        localDevice().mService.getHdmiCecNetwork().updateDevicePowerStatus(logicalAddress,
                newStatus);

        if (remove) {
            mPowerStatus.delete(logicalAddress);
        }
    }
}
