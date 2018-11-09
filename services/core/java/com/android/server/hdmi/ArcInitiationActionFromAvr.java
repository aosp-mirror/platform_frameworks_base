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

import android.hardware.tv.cec.V1_0.SendMessageResult;

/**
 * Feature action that handles Audio Return Channel initiated by AVR devices.
 */
public class ArcInitiationActionFromAvr extends HdmiCecFeatureAction {
    // State in which waits for ARC response.
    private static final int STATE_WAITING_FOR_INITIATE_ARC_RESPONSE = 1;
    private static final int STATE_ARC_INITIATED = 2;

    // the required maximum response time specified in CEC 9.2
    private static final int TIMEOUT_MS = 1000;
    private static final int MAX_RETRY_COUNT = 5;

    private int mSendRequestActiveSourceRetryCount = 0;

    ArcInitiationActionFromAvr(HdmiCecLocalDevice source) {
        super(source);
    }

    @Override
    boolean start() {
        audioSystem().setArcStatus(true);
        mState = STATE_WAITING_FOR_INITIATE_ARC_RESPONSE;
        addTimer(mState, TIMEOUT_MS);
        sendInitiateArc();
        return true;
    }

    @Override
    boolean processCommand(HdmiCecMessage cmd) {
        if (mState != STATE_WAITING_FOR_INITIATE_ARC_RESPONSE) {
            return false;
        }
        switch (cmd.getOpcode()) {
            case Constants.MESSAGE_FEATURE_ABORT:
                if ((cmd.getParams()[0] & 0xFF) == Constants.MESSAGE_INITIATE_ARC) {
                    audioSystem().setArcStatus(false);
                    finish();
                    return true;
                } else {
                    return false;
                }
            case Constants.MESSAGE_REPORT_ARC_TERMINATED:
                audioSystem().setArcStatus(false);
                finish();
                return true;
            case Constants.MESSAGE_REPORT_ARC_INITIATED:
                mState = STATE_ARC_INITIATED;
                if (audioSystem().getActiveSource().physicalAddress != getSourcePath()
                        && audioSystem().isSystemAudioActivated()) {
                    sendRequestActiveSource();
                } else {
                    finish();
                }
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
                handleInitiateArcTimeout();
                break;
        }
    }

    protected void sendInitiateArc() {
        sendCommand(HdmiCecMessageBuilder.buildInitiateArc(getSourceAddress(), Constants.ADDR_TV),
                result -> {
                    if (result != SendMessageResult.SUCCESS) {
                        audioSystem().setArcStatus(false);
                        finish();
                    }
                });
    }

    private void handleInitiateArcTimeout() {
        HdmiLogger.debug("handleInitiateArcTimeout");
        audioSystem().setArcStatus(false);
        finish();
    }

    protected void sendRequestActiveSource() {
        sendCommand(HdmiCecMessageBuilder.buildRequestActiveSource(getSourceAddress()),
                result -> {
                    if (result != SendMessageResult.SUCCESS) {
                        if (mSendRequestActiveSourceRetryCount < MAX_RETRY_COUNT) {
                            mSendRequestActiveSourceRetryCount++;
                            sendRequestActiveSource();
                        } else {
                            finish();
                        }
                    } else {
                        finish();
                    }
                });
    }
}
