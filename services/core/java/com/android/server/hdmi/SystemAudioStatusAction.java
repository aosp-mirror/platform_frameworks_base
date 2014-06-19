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

import android.annotation.Nullable;
import android.hardware.hdmi.HdmiCec;
import android.hardware.hdmi.HdmiCecMessage;
import android.hardware.hdmi.IHdmiControlCallback;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.hdmi.HdmiControlService.SendMessageCallback;

/**
 * Action to update audio status (volume or mute) of audio amplifier
 */
final class SystemAudioStatusAction extends FeatureAction {
    private static final String TAG = "SystemAudioStatusAction";

    // State that waits for <ReportAudioStatus>.
    private static final int STATE_WAIT_FOR_REPORT_AUDIO_STATUS = 1;

    private final int mAvrAddress;
    @Nullable private final IHdmiControlCallback mCallback;

    SystemAudioStatusAction(HdmiCecLocalDevice source, int avrAddress,
            IHdmiControlCallback callback) {
        super(source);
        mAvrAddress = avrAddress;
        mCallback = callback;
    }

    @Override
    boolean start() {
        mState = STATE_WAIT_FOR_REPORT_AUDIO_STATUS;
        addTimer(mState, TIMEOUT_MS);
        sendGiveAudioStatus();
        return true;
    }

    private void sendGiveAudioStatus() {
        sendCommand(HdmiCecMessageBuilder.buildGiveAudioStatus(getSourceAddress(), mAvrAddress),
                new SendMessageCallback() {
            @Override
            public void onSendCompleted(int error) {
                if (error != HdmiConstants.SEND_RESULT_SUCCESS) {
                    handleSendGiveAudioStatusFailure();
                }
            }
        });
    }

    private void handleSendGiveAudioStatusFailure() {
        // Inform to all application that the audio status (volumn, mute) of
        // the audio amplifier is unknown.
        tv().setAudioStatus(false, HdmiConstants.UNKNOWN_VOLUME);

        int uiCommand = tv().getSystemAudioMode()
                ? HdmiConstants.UI_COMMAND_RESTORE_VOLUME_FUNCTION  // SystemAudioMode: ON
                : HdmiConstants.UI_COMMAND_MUTE_FUNCTION;           // SystemAudioMode: OFF
        sendUserControlPressedAndReleased(uiCommand);

        // Still return SUCCESS to callback.
        finishWithCallback(HdmiCec.RESULT_SUCCESS);
    }

    private void sendUserControlPressedAndReleased(int uiCommand) {
        sendCommand(HdmiCecMessageBuilder.buildUserControlPressed(
                getSourceAddress(), mAvrAddress, uiCommand));
        sendCommand(HdmiCecMessageBuilder.buildUserControlReleased(
                getSourceAddress(), mAvrAddress));
    }

    @Override
    boolean processCommand(HdmiCecMessage cmd) {
        if (mState != STATE_WAIT_FOR_REPORT_AUDIO_STATUS) {
            return false;
        }

        switch (cmd.getOpcode()) {
            case HdmiCec.MESSAGE_REPORT_AUDIO_STATUS:
                handleReportAudioStatus(cmd);
                return true;
        }

        return false;
    }

    private void handleReportAudioStatus(HdmiCecMessage cmd) {
        byte[] params = cmd.getParams();
        if (params.length > 0) {
            boolean mute = (params[0] & 0x80) == 0x80;
            int volume = params[0] & 0x7F;
            tv().setAudioStatus(mute, volume);

            if ((tv().getSystemAudioMode() && mute) || (!tv().getSystemAudioMode() && !mute)) {
                // Toggle AVR's mute status to match with the system audio status.
                sendUserControlPressedAndReleased(HdmiConstants.UI_COMMAND_MUTE);
            }
            finishWithCallback(HdmiCec.RESULT_SUCCESS);
        } else {
            Slog.e(TAG, "Invalid <Report Audio Status> message:" + cmd);
            handleSendGiveAudioStatusFailure();
            return;
        }
    }

    private void finishWithCallback(int returnCode) {
        if (mCallback != null) {
            try {
                mCallback.onComplete(returnCode);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to invoke callback.", e);
            }
        }
        finish();
    }

    @Override
    void handleTimerEvent(int state) {
        if (mState != state) {
            return;
        }

        handleSendGiveAudioStatusFailure();
    }
}
