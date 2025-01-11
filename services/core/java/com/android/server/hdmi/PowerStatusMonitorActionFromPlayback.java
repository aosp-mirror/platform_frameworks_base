/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static android.hardware.hdmi.HdmiControlManager.POWER_STATUS_STANDBY;

import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

/**
 * This action is used by playback devices to query TV's power status such that they can go to
 * standby when the TV reports power off.
 */
public class PowerStatusMonitorActionFromPlayback extends HdmiCecFeatureAction {
    private static final String TAG = "PowerStatusMonitorActionFromPlayback";
    // State that waits for next monitoring.
    private static final int STATE_WAIT_FOR_NEXT_MONITORING = 1;

    // State that waits for <Report Power Status> once sending <Give Device Power Status>
    // to the TV.
    private static final int STATE_WAIT_FOR_REPORT_POWER_STATUS = 2;

    // Monitoring interval (60s)
    @VisibleForTesting
    protected static final int MONITORING_INTERVAL_MS = 60000;
    // Timeout once sending <Give Device Power Status>

    PowerStatusMonitorActionFromPlayback(HdmiCecLocalDevice source) {
        super(source);
    }

    @Override
    boolean start() {
        // Start after timeout since the device just finished allocation.
        mState = STATE_WAIT_FOR_NEXT_MONITORING;
        addTimer(mState, MONITORING_INTERVAL_MS);
        return true;
    }

    @Override
    boolean processCommand(HdmiCecMessage cmd) {
        if (mState == STATE_WAIT_FOR_REPORT_POWER_STATUS
                && cmd.getOpcode() == Constants.MESSAGE_REPORT_POWER_STATUS
                && cmd.getSource() == Constants.ADDR_TV) {
            return handleReportPowerStatusFromTv(cmd);
        }
        return false;
    }

    private boolean handleReportPowerStatusFromTv(HdmiCecMessage cmd) {
        int powerStatus = cmd.getParams()[0] & 0xFF;
        if (powerStatus == POWER_STATUS_STANDBY) {
            Slog.d(TAG, "TV reported it turned off, going to sleep.");
            source().getService().standby();
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

        switch (mState) {
            case STATE_WAIT_FOR_NEXT_MONITORING:
                queryPowerStatus();
                break;
            case STATE_WAIT_FOR_REPORT_POWER_STATUS:
                mState = STATE_WAIT_FOR_NEXT_MONITORING;
                addTimer(mState, MONITORING_INTERVAL_MS);
                break;
            default:
                break;
        }
    }

    private void queryPowerStatus() {
        sendCommand(HdmiCecMessageBuilder.buildGiveDevicePowerStatus(getSourceAddress(),
                Constants.ADDR_TV));

        mState = STATE_WAIT_FOR_REPORT_POWER_STATUS;
        addTimer(mState, HdmiConfig.TIMEOUT_MS);
    }
}
