/*
 * Copyright (C) 2014 The Android Open Source Project
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

import static android.hardware.hdmi.HdmiControlManager.ONE_TOUCH_RECORD_CHECK_RECORDER_CONNECTION;
import static android.hardware.hdmi.HdmiControlManager.ONE_TOUCH_RECORD_RECORDING_ANALOGUE_SERVICE;
import static android.hardware.hdmi.HdmiControlManager.ONE_TOUCH_RECORD_RECORDING_CURRENTLY_SELECTED_SOURCE;
import static android.hardware.hdmi.HdmiControlManager.ONE_TOUCH_RECORD_RECORDING_DIGITAL_SERVICE;
import static android.hardware.hdmi.HdmiControlManager.ONE_TOUCH_RECORD_RECORDING_EXTERNAL_INPUT;

import android.util.Slog;

import com.android.server.hdmi.HdmiControlService.SendMessageCallback;

/**
 * Feature action that performs one touch record.
 */
public class OneTouchRecordAction extends HdmiCecFeatureAction {
    private static final String TAG = "OneTouchRecordAction";

    // Timer out for waiting <Record Status> 120s
    private static final int RECORD_STATUS_TIMEOUT_MS = 120000;

    // State that waits for <Record Status> once sending <Record On>
    private static final int STATE_WAITING_FOR_RECORD_STATUS = 1;
    // State that describes recording in progress.
    private static final int STATE_RECORDING_IN_PROGRESS = 2;

    private final int mRecorderAddress;
    private final byte[] mRecordSource;

    OneTouchRecordAction(HdmiCecLocalDevice source, int recorderAddress, byte[] recordSource) {
        super(source);
        mRecorderAddress = recorderAddress;
        mRecordSource = recordSource;
    }

    @Override
    boolean start() {
        sendRecordOn();
        return true;
    }

    private void sendRecordOn() {
        sendCommand(HdmiCecMessageBuilder.buildRecordOn(getSourceAddress(), mRecorderAddress,
                mRecordSource),
                new SendMessageCallback() {
                @Override
                    public void onSendCompleted(int error) {
                        // if failed to send <Record On>, display error message and finish action.
                        if (error != Constants.SEND_RESULT_SUCCESS) {
                            tv().announceOneTouchRecordResult(
                                    ONE_TOUCH_RECORD_CHECK_RECORDER_CONNECTION);
                            finish();
                            return;
                        }

                        mState = STATE_WAITING_FOR_RECORD_STATUS;
                        addTimer(mState, RECORD_STATUS_TIMEOUT_MS);
                    }
                });
    }

    @Override
    boolean processCommand(HdmiCecMessage cmd) {
        if (mState != STATE_WAITING_FOR_RECORD_STATUS) {
            return false;
        }

        switch (cmd.getOpcode()) {
            case Constants.MESSAGE_RECORD_STATUS:
                return handleRecordStatus(cmd);

        }
        return false;
    }

    private boolean handleRecordStatus(HdmiCecMessage cmd) {
        // Only handle message coming from original recorder.
        if (cmd.getSource() != mRecorderAddress) {
            return false;
        }

        int recordStatus = cmd.getParams()[0];
        tv().announceOneTouchRecordResult(recordStatus);
        Slog.i(TAG, "Got record status:" + recordStatus + " from " + cmd.getSource());

        // If recording started successfully, change state and keep this action until <Record Off>
        // received. Otherwise, finish action.
        switch (recordStatus) {
            case ONE_TOUCH_RECORD_RECORDING_CURRENTLY_SELECTED_SOURCE:
            case ONE_TOUCH_RECORD_RECORDING_DIGITAL_SERVICE:
            case ONE_TOUCH_RECORD_RECORDING_ANALOGUE_SERVICE:
            case ONE_TOUCH_RECORD_RECORDING_EXTERNAL_INPUT:
                mState = STATE_RECORDING_IN_PROGRESS;
                mActionTimer.clearTimerMessage();
                break;
            default:
                finish();
                break;
        }
        return true;
    }

    @Override
    void handleTimerEvent(int state) {
        if (mState != state) {
            Slog.w(TAG, "Timeout in invalid state:[Expected:" + mState + ", Actual:" + state + "]");
            return;
        }

        tv().announceOneTouchRecordResult(ONE_TOUCH_RECORD_CHECK_RECORDER_CONNECTION);
        finish();
    }

    int getRecorderAddress() {
        return mRecorderAddress;
    }
}
