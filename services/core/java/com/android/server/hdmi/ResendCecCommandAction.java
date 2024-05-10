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

import android.hardware.tv.hdmi.cec.SendMessageResult;
import android.util.Slog;

import com.android.server.hdmi.HdmiControlService.SendMessageCallback;

/**
 * Action that retries RETRANSMISSION_COUNT times to send a CEC command when the first attempt to
 * send the message failed.
 *
 * This action starts with a delay of SEND_COMMAND_RETRY_MS milliseconds.
 *
 * If this action can't be started it will be canceled and not deferred.
 * See {@link HdmiCecLocalDevice#addAndStartAction}.
 */
public class ResendCecCommandAction extends HdmiCecFeatureAction {
    private static final String TAG = "ResendCecCommandAction";
    private static final int RETRANSMISSION_COUNT = 1;
    private static final int STATE_WAIT_FOR_RESEND_COMMAND = 1;
    static final int SEND_COMMAND_RETRY_MS = 300;

    private final HdmiCecMessage mCommand;
    private int mRetransmissionCount = 0;
    private final SendMessageCallback mResultCallback;
    private final SendMessageCallback mCallback = new SendMessageCallback(){
        @Override
        public void onSendCompleted(int result) {
            if (result != SendMessageResult.SUCCESS
                    && mRetransmissionCount++ < RETRANSMISSION_COUNT) {
                mState = STATE_WAIT_FOR_RESEND_COMMAND;
                addTimer(mState, SEND_COMMAND_RETRY_MS);
            } else {
                if (mResultCallback != null) {
                    mResultCallback.onSendCompleted(result);
                }
                finish();
            }
        }
    };

    ResendCecCommandAction(HdmiCecLocalDevice source, HdmiCecMessage command,
            SendMessageCallback callback) {
        super(source);
        mCommand = command;
        mResultCallback = callback;
        mState = STATE_WAIT_FOR_RESEND_COMMAND;
        addTimer(mState, SEND_COMMAND_RETRY_MS);
    }

    @Override
    boolean start() {
        Slog.d(TAG, "ResendCecCommandAction started");
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
            Slog.d(TAG, "sendCommandWithoutRetries failed, retry");
            sendCommandWithoutRetries(mCommand, mCallback);
        }
    }

    @Override
    boolean processCommand(HdmiCecMessage command) {
        return false;
    }
}
