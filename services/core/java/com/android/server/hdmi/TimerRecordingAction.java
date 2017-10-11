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

import static android.hardware.hdmi.HdmiControlManager.TIMER_RECORDING_RESULT_EXTRA_CHECK_RECORDER_CONNECTION;
import static android.hardware.hdmi.HdmiControlManager.TIMER_RECORDING_RESULT_EXTRA_FAIL_TO_RECORD_SELECTED_SOURCE;
import static android.hardware.hdmi.HdmiControlManager.TIMER_RECORDING_TYPE_ANALOGUE;
import static android.hardware.hdmi.HdmiControlManager.TIMER_RECORDING_TYPE_DIGITAL;
import static android.hardware.hdmi.HdmiControlManager.TIMER_RECORDING_TYPE_EXTERNAL;

import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.util.Slog;
import com.android.server.hdmi.HdmiControlService.SendMessageCallback;
import java.util.Arrays;

/**
 * Feature action that performs timer recording.
 */
public class TimerRecordingAction extends HdmiCecFeatureAction {
    private static final String TAG = "TimerRecordingAction";

    // Timer out for waiting <Timer Status> 120s.
    private static final int TIMER_STATUS_TIMEOUT_MS = 120000;

    // State that waits for <Timer Status> once sending <Set XXX Timer>
    private static final int STATE_WAITING_FOR_TIMER_STATUS = 1;

    private final int mRecorderAddress;
    private final int mSourceType;
    private final byte[] mRecordSource;

    TimerRecordingAction(HdmiCecLocalDevice source, int recorderAddress, int sourceType,
            byte[] recordSource) {
        super(source);
        mRecorderAddress = recorderAddress;
        mSourceType = sourceType;
        mRecordSource = recordSource;
    }

    @Override
    boolean start() {
        sendTimerMessage();
        return true;
    }

    private void sendTimerMessage() {
        HdmiCecMessage message = null;
        switch (mSourceType) {
            case TIMER_RECORDING_TYPE_DIGITAL:
                message = HdmiCecMessageBuilder.buildSetDigitalTimer(getSourceAddress(),
                        mRecorderAddress, mRecordSource);
                break;
            case TIMER_RECORDING_TYPE_ANALOGUE:
                message = HdmiCecMessageBuilder.buildSetAnalogueTimer(getSourceAddress(),
                        mRecorderAddress, mRecordSource);
                break;
            case TIMER_RECORDING_TYPE_EXTERNAL:
                message = HdmiCecMessageBuilder.buildSetExternalTimer(getSourceAddress(),
                        mRecorderAddress, mRecordSource);
                break;
            default:
                tv().announceTimerRecordingResult(mRecorderAddress,
                        TIMER_RECORDING_RESULT_EXTRA_FAIL_TO_RECORD_SELECTED_SOURCE);
                finish();
                return;
        }
        sendCommand(message, new SendMessageCallback() {
            @Override
            public void onSendCompleted(int error) {
                if (error != SendMessageResult.SUCCESS) {
                    tv().announceTimerRecordingResult(mRecorderAddress,
                            TIMER_RECORDING_RESULT_EXTRA_CHECK_RECORDER_CONNECTION);
                    finish();
                    return;
                }
                mState = STATE_WAITING_FOR_TIMER_STATUS;
                addTimer(mState, TIMER_STATUS_TIMEOUT_MS);
            }
        });
    }

    @Override
    boolean processCommand(HdmiCecMessage cmd) {
        if (mState != STATE_WAITING_FOR_TIMER_STATUS
                || cmd.getSource() != mRecorderAddress) {
            return false;
        }

        switch (cmd.getOpcode()) {
            case Constants.MESSAGE_TIMER_STATUS:
                return handleTimerStatus(cmd);
            case Constants.MESSAGE_FEATURE_ABORT:
                return handleFeatureAbort(cmd);
        }
        return false;
    }

    private boolean handleTimerStatus(HdmiCecMessage cmd) {
        byte[] timerStatusData = cmd.getParams();
        // [Timer Status Data] should be one or three bytes.
        if (timerStatusData.length == 1 || timerStatusData.length == 3) {
            tv().announceTimerRecordingResult(mRecorderAddress, bytesToInt(timerStatusData));
            Slog.i(TAG, "Received [Timer Status Data]:" + Arrays.toString(timerStatusData));
        } else {
            Slog.w(TAG, "Invalid [Timer Status Data]:" + Arrays.toString(timerStatusData));
        }

        // Unlike one touch record, finish timer record when <Timer Status> is received.
        finish();
        return true;
    }

    private boolean handleFeatureAbort(HdmiCecMessage cmd) {
        byte[] params = cmd.getParams();
        int messageType = params[0] & 0xFF;
        switch (messageType) {
            case Constants.MESSAGE_SET_DIGITAL_TIMER: // fall through
            case Constants.MESSAGE_SET_ANALOG_TIMER: // fall through
            case Constants.MESSAGE_SET_EXTERNAL_TIMER: // fall through
                break;
            default:
                return false;
        }
        int reason = params[1] & 0xFF;
        Slog.i(TAG, "[Feature Abort] for " + messageType + " reason:" + reason);
        tv().announceTimerRecordingResult(mRecorderAddress,
                TIMER_RECORDING_RESULT_EXTRA_CHECK_RECORDER_CONNECTION);
        finish();
        return true;
    }

    // Convert byte array to int.
    private static int bytesToInt(byte[] data) {
        if (data.length > 4) {
            throw new IllegalArgumentException("Invalid data size:" + Arrays.toString(data));
        }
        int result = 0;
        for (int i = 0; i < data.length; ++i) {
            int shift = (3 - i) * 8;
            result |= ((data[i] & 0xFF) << shift);
        }
        return result;
    }

    @Override
    void handleTimerEvent(int state) {
        if (mState != state) {
            Slog.w(TAG, "Timeout in invalid state:[Expected:" + mState + ", Actual:" + state + "]");
            return;
        }

        tv().announceTimerRecordingResult(mRecorderAddress,
                TIMER_RECORDING_RESULT_EXTRA_CHECK_RECORDER_CONNECTION);
        finish();
    }
}
