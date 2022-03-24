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
import android.hardware.hdmi.IHdmiControlCallback;
import android.hardware.tv.cec.V1_0.SendMessageResult;

/**
 * Sends <Give Features> to a target device. This action succeeds if the device responds with
 * <Report Features> within {@link HdmiConfig.TIMEOUT_MS}.
 *
 * This action does not update the CEC network directly; an incoming <Report Features> message
 * should be handled separately by {@link HdmiCecNetwork}.
 */
public class GiveFeaturesAction extends HdmiCecFeatureAction {
    private static final String TAG = "GiveFeaturesAction";

    private static final int STATE_WAITING_FOR_REPORT_FEATURES = 1;

    private final int mTargetAddress;

    public GiveFeaturesAction(HdmiCecLocalDevice source, int targetAddress,
            IHdmiControlCallback callback) {
        super(source, callback);

        mTargetAddress = targetAddress;
    }

    boolean start() {
        sendCommand(HdmiCecMessageBuilder.buildGiveFeatures(getSourceAddress(), mTargetAddress),
                result -> {
                    if (result == SendMessageResult.SUCCESS) {
                        mState = STATE_WAITING_FOR_REPORT_FEATURES;
                        addTimer(STATE_WAITING_FOR_REPORT_FEATURES, HdmiConfig.TIMEOUT_MS);
                    } else {
                        finishWithCallback(HdmiControlManager.RESULT_COMMUNICATION_FAILED);
                    }
                });
        return true;
    }

    boolean processCommand(HdmiCecMessage cmd) {
        if (mState != STATE_WAITING_FOR_REPORT_FEATURES) {
            return false;
        }
        if (cmd instanceof ReportFeaturesMessage) {
            return handleReportFeatures((ReportFeaturesMessage) cmd);
        }
        return false;
    }

    private boolean handleReportFeatures(ReportFeaturesMessage cmd) {
        if (cmd.getSource() == mTargetAddress) {
            // No need to update the network, since it should already have processed this message.
            finishWithCallback(HdmiControlManager.RESULT_SUCCESS);
            return true;
        }
        return false;
    }

    void handleTimerEvent(int state) {
        finishWithCallback(HdmiControlManager.RESULT_COMMUNICATION_FAILED);
    }
}
