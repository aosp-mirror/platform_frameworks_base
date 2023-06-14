/*
 * Copyright (C) 2023 The Android Open Source Project
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
import android.util.Slog;

import com.android.server.hdmi.HdmiControlService.SendMessageCallback;

/**
 * Action that sends a CEC command. If the message fails to be sent, it tries again for
 * RETRANSMISSION_COUNT times.
 */
public class SendCecCommandAction extends HdmiCecFeatureAction {
    private static final String TAG = "SendCecCommandAction";
    private static final int RETRANSMISSION_COUNT = 2;
    private static final int STATE_WAIT_FOR_RESEND_COMMAND = 1;
    static final int SEND_COMMAND_RETRY_MS = 300;

    private final HdmiCecMessage mCommand;
    private int mRetransmissionCount = 0;
    private final SendMessageCallback mCallback = new SendMessageCallback(){
        @Override
        public void onSendCompleted(int result) {
            if (result != SendMessageResult.SUCCESS
                    && mRetransmissionCount++ < RETRANSMISSION_COUNT) {
                mState = STATE_WAIT_FOR_RESEND_COMMAND;
                addTimer(mState, SEND_COMMAND_RETRY_MS);
            } else {
                finish();
            }
        }
    };

    SendCecCommandAction(HdmiCecLocalDevice source, HdmiCecMessage command) {
        super(source);
        mCommand = command;
    }

    @Override
    boolean start() {
        Slog.d(TAG, "SendCecCommandAction started");
        sendCommand(mCommand, mCallback);
        return true;
    }

    @Override
    void handleTimerEvent(int state) {
        if (mState != state) {
            Slog.w(TAG, "Timeout in invalid state:[Expected:" + mState + ", Actual:" + state
                    + "]");
            return;
        }
        if (mState == STATE_WAIT_FOR_RESEND_COMMAND) {
            Slog.d(TAG, "sendCecCommand failed, retry");
            sendCommand(mCommand, mCallback);
        }
    }

    @Override
    boolean processCommand(HdmiCecMessage command) {
        return false;
    }
}
