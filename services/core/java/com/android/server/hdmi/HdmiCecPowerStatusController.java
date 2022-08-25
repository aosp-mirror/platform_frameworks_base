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

package com.android.server.hdmi;

import static com.android.server.hdmi.HdmiAnnotations.ServiceThreadOnly;

import android.hardware.hdmi.HdmiControlManager;

/**
 * Storage of HDMI-CEC power status and controls possible cases where power status changes must also
 * broadcast {@code <Report Power Status>} messages.
 *
 * All HDMI-CEC related power status changes should be done through this class.
 */
class HdmiCecPowerStatusController {

    private final HdmiControlService mHdmiControlService;

    private int mPowerStatus = HdmiControlManager.POWER_STATUS_STANDBY;

    HdmiCecPowerStatusController(HdmiControlService hdmiControlService) {
        mHdmiControlService = hdmiControlService;
    }

    int getPowerStatus() {
        return mPowerStatus;
    }

    boolean isPowerStatusOn() {
        return mPowerStatus == HdmiControlManager.POWER_STATUS_ON;
    }

    boolean isPowerStatusStandby() {
        return mPowerStatus == HdmiControlManager.POWER_STATUS_STANDBY;
    }

    boolean isPowerStatusTransientToOn() {
        return mPowerStatus == HdmiControlManager.POWER_STATUS_TRANSIENT_TO_ON;
    }

    boolean isPowerStatusTransientToStandby() {
        return mPowerStatus == HdmiControlManager.POWER_STATUS_TRANSIENT_TO_STANDBY;
    }

    @ServiceThreadOnly
    void setPowerStatus(int powerStatus) {
        setPowerStatus(powerStatus, true);
    }

    @ServiceThreadOnly
    void setPowerStatus(int powerStatus, boolean sendPowerStatusUpdate) {
        if (powerStatus == mPowerStatus) {
            return;
        }

        mPowerStatus = powerStatus;
        if (sendPowerStatusUpdate
                && mHdmiControlService.getCecVersion() >= HdmiControlManager.HDMI_CEC_VERSION_2_0) {
            sendReportPowerStatus(mPowerStatus);
        }
    }

    private void sendReportPowerStatus(int powerStatus) {
        for (HdmiCecLocalDevice localDevice : mHdmiControlService.getAllLocalDevices()) {
            mHdmiControlService.sendCecCommand(
                    HdmiCecMessageBuilder.buildReportPowerStatus(
                            localDevice.getDeviceInfo().getLogicalAddress(),
                            Constants.ADDR_BROADCAST,
                            powerStatus));
        }
    }
}
