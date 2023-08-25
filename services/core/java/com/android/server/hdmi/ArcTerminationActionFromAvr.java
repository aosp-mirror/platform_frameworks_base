/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * Feature action that handles Audio Return Channel terminated by AVR devices.
 */
public class ArcTerminationActionFromAvr extends HdmiCecFeatureAction {

    // State in which waits for ARC response.
    private static final int STATE_WAITING_FOR_INITIATE_ARC_RESPONSE = 1;
    private static final int STATE_ARC_TERMINATED = 2;

    // the required maximum response time specified in CEC 9.2
    public static final int TIMEOUT_MS = 1000;

    ArcTerminationActionFromAvr(HdmiCecLocalDevice source) {
        super(source);
    }

    ArcTerminationActionFromAvr(HdmiCecLocalDevice source, IHdmiControlCallback callback) {
        super(source, callback);
    }

    @Override
    boolean start() {
        mState = STATE_WAITING_FOR_INITIATE_ARC_RESPONSE;
        addTimer(mState, TIMEOUT_MS);
        sendTerminateArc();
        return true;
    }

    @Override
    boolean processCommand(HdmiCecMessage cmd) {
        if (mState != STATE_WAITING_FOR_INITIATE_ARC_RESPONSE) {
            return false;
        }
        switch (cmd.getOpcode()) {
            case Constants.MESSAGE_FEATURE_ABORT:
                int originalOpcode = cmd.getParams()[0] & 0xFF;
                if (originalOpcode == Constants.MESSAGE_TERMINATE_ARC) {
                    mState = STATE_ARC_TERMINATED;
                    audioSystem().processArcTermination();
                    finishWithCallback(HdmiControlManager.RESULT_TARGET_NOT_AVAILABLE);
                    return true;
                }
                return false;
            case Constants.MESSAGE_REPORT_ARC_TERMINATED:
                mState = STATE_ARC_TERMINATED;
                audioSystem().processArcTermination();
                finishWithCallback(HdmiControlManager.RESULT_SUCCESS);
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
            case STATE_WAITING_FOR_INITIATE_ARC_RESPONSE:
                handleTerminateArcTimeout();
                break;
        }
    }

    protected void sendTerminateArc() {
        sendCommand(HdmiCecMessageBuilder.buildTerminateArc(getSourceAddress(), Constants.ADDR_TV),
            result -> {
                if (result != SendMessageResult.SUCCESS) {
                    // If the physical connection is already off or TV does not handle
                    // Terminate ARC, turn off ARC internally.
                    if (result == SendMessageResult.NACK) {
                        audioSystem().setArcStatus(false);
                    }
                    HdmiLogger.debug("Terminate ARC was not successfully sent.");
                    finishWithCallback(HdmiControlManager.RESULT_TARGET_NOT_AVAILABLE);
                }
            });
    }

    private void handleTerminateArcTimeout() {
        // Disable ARC if TV didn't respond with <Report ARC Terminated> in time.
        audioSystem().setArcStatus(false);
        HdmiLogger.debug("handleTerminateArcTimeout");
        finishWithCallback(HdmiControlManager.RESULT_TIMEOUT);
    }
}
