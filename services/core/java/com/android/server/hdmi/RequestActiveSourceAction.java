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

import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.IHdmiControlCallback;
import android.util.Slog;

/**
 * Feature action that sends <Request Active Source> message and waits for <Active Source>.
 */
public class RequestActiveSourceAction extends HdmiCecFeatureAction {
    private static final String TAG = "RequestActiveSourceAction";

    // State to wait for the <Active Source> message.
    private static final int STATE_WAIT_FOR_ACTIVE_SOURCE = 1;

    // Number of retries <Request Active Source> is sent if no device answers this message.
    private static final int MAX_SEND_RETRY_COUNT = 1;

    private int mSendRetryCount = 0;


    RequestActiveSourceAction(HdmiCecLocalDevice source, IHdmiControlCallback callback) {
        super(source, callback);
    }

    @Override
    boolean start() {
        Slog.v(TAG, "RequestActiveSourceAction started.");

        sendCommand(HdmiCecMessageBuilder.buildRequestActiveSource(getSourceAddress()));

        mState = STATE_WAIT_FOR_ACTIVE_SOURCE;
        addTimer(mState, HdmiConfig.TIMEOUT_MS);
        return true;
    }

    @Override
    boolean processCommand(HdmiCecMessage cmd) {
        // The action finishes successfully if the <Active Source> message is received.
        // {@link HdmiCecLocalDevice#onMessage} handles this message, so false is returned.
        if (cmd.getOpcode() == Constants.MESSAGE_ACTIVE_SOURCE) {
            finishWithCallback(HdmiControlManager.RESULT_SUCCESS);
        }
        return false;
    }

    @Override
    void handleTimerEvent(int state) {
        if (mState != state) {
            return;
        }
        if (mState == STATE_WAIT_FOR_ACTIVE_SOURCE) {
            if (mSendRetryCount++ < MAX_SEND_RETRY_COUNT) {
                sendCommand(HdmiCecMessageBuilder.buildRequestActiveSource(getSourceAddress()));
                addTimer(mState, HdmiConfig.TIMEOUT_MS);
            } else {
                finishWithCallback(HdmiControlManager.RESULT_TIMEOUT);
            }
        }
    }
}
