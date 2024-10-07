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

    // State that waits for <Report Power Status> once sending <Give Device Power Status>
    // to all external devices.
    private static final int STATE_WAIT_FOR_REPORT_POWER_STATUS = 1;
    // State that waits for next monitoring.
    private static final int STATE_WAIT_FOR_NEXT_MONITORING = 2;
    // Monitoring interval (60s)
    @VisibleForTesting
    protected static final int MONITORING_INTERVAL_MS = 60000;
    // Timeout once sending <Give Device Power Status>
    private static final int REPORT_POWER_STATUS_TIMEOUT_MS = 5000;
    // Maximum number of retries in case the <Give Device Power Status> failed being sent or times
    // out.
    private static final int GIVE_POWER_STATUS_FOR_SOURCE_RETRIES = 5;
    private int mPowerStatusRetries = 0;

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
            return true;
        }
        return false;
    }

    @Override
    void handleTimerEvent(int state) {
        switch (mState) {
            case STATE_WAIT_FOR_NEXT_MONITORING:
                mPowerStatusRetries = 0;
                queryPowerStatus();
                break;
            case STATE_WAIT_FOR_REPORT_POWER_STATUS:
                handleTimeout();
                break;
        }
    }

    private void queryPowerStatus() {
        sendCommand(HdmiCecMessageBuilder.buildGiveDevicePowerStatus(getSourceAddress(),
                        Constants.ADDR_TV));

        mState = STATE_WAIT_FOR_REPORT_POWER_STATUS;
        addTimer(mState, REPORT_POWER_STATUS_TIMEOUT_MS);
    }

    private void handleTimeout() {
        if (mState == STATE_WAIT_FOR_REPORT_POWER_STATUS) {
            if (mPowerStatusRetries++ < GIVE_POWER_STATUS_FOR_SOURCE_RETRIES) {
                queryPowerStatus();
            } else {
                mPowerStatusRetries = 0;
                mState = STATE_WAIT_FOR_NEXT_MONITORING;
                addTimer(mState, MONITORING_INTERVAL_MS);
            }
        }
    }
}
