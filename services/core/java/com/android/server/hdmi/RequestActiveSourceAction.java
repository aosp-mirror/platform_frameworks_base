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
import com.android.internal.annotations.VisibleForTesting;

/**
 * Feature action that sends <Request Active Source> message and waits for <Active Source> on TV
 * panels.
 * This action has a delay before sending <Request Active Source>. This is because it should wait
 * for a possible request from LauncherX and can be cancelled if an <Active Source> message was
 * received or the TV switched to another input.
 */
public class RequestActiveSourceAction extends HdmiCecFeatureAction {
    private static final String TAG = "RequestActiveSourceAction";

    // State to wait for the LauncherX to call the CEC API.
    private static final int STATE_WAIT_FOR_LAUNCHERX_API_CALL = 1;

    // State to wait for the <Active Source> message.
    private static final int STATE_WAIT_FOR_ACTIVE_SOURCE = 2;

    // Number of retries <Request Active Source> is sent if no device answers this message.
    private static final int MAX_SEND_RETRY_COUNT = 1;

    // Timeout to wait for the LauncherX API call to be completed.
    @VisibleForTesting
    protected static final int TIMEOUT_WAIT_FOR_LAUNCHERX_API_CALL_MS = 10000;

    private int mSendRetryCount = 0;


    RequestActiveSourceAction(HdmiCecLocalDevice source, IHdmiControlCallback callback) {
        super(source, callback);
    }

    @Override
    boolean start() {
        Slog.v(TAG, "RequestActiveSourceAction started.");

        mState = STATE_WAIT_FOR_LAUNCHERX_API_CALL;

        // We wait for default timeout to allow the message triggered by the LauncherX API call to
        // be sent by the TV and another default timeout in case the message has to be answered
        // (e.g. TV sent a <Set Stream Path> or <Routing Change>).
        addTimer(mState, TIMEOUT_WAIT_FOR_LAUNCHERX_API_CALL_MS);
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

        switch (mState) {
            case STATE_WAIT_FOR_LAUNCHERX_API_CALL:
                mState = STATE_WAIT_FOR_ACTIVE_SOURCE;
                sendCommand(HdmiCecMessageBuilder.buildRequestActiveSource(getSourceAddress()));
                addTimer(mState, HdmiConfig.TIMEOUT_MS);
                return;
            case STATE_WAIT_FOR_ACTIVE_SOURCE:
                if (mSendRetryCount++ < MAX_SEND_RETRY_COUNT) {
                    sendCommand(HdmiCecMessageBuilder.buildRequestActiveSource(getSourceAddress()));
                    addTimer(mState, HdmiConfig.TIMEOUT_MS);
                } else {
                    finishWithCallback(HdmiControlManager.RESULT_TIMEOUT);
                }
                return;
            default:
                return;
        }
    }
}
