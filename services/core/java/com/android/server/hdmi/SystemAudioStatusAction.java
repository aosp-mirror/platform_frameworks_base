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
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.IHdmiControlCallback;
import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.os.RemoteException;
import android.util.Slog;
import com.android.server.hdmi.HdmiControlService.SendMessageCallback;

/**
 * Action to update audio status (volume or mute) of audio amplifier
 */
final class SystemAudioStatusAction extends HdmiCecFeatureAction {
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
        addTimer(mState, HdmiConfig.TIMEOUT_MS);
        sendGiveAudioStatus();
        return true;
    }

    private void sendGiveAudioStatus() {
        sendCommand(HdmiCecMessageBuilder.buildGiveAudioStatus(getSourceAddress(), mAvrAddress),
                new SendMessageCallback() {
            @Override
            public void onSendCompleted(int error) {
                if (error != SendMessageResult.SUCCESS) {
                    handleSendGiveAudioStatusFailure();
                }
            }
        });
    }

    private void handleSendGiveAudioStatusFailure() {

        // Still return SUCCESS to callback.
        finishWithCallback(HdmiControlManager.RESULT_SUCCESS);
    }

    @Override
    boolean processCommand(HdmiCecMessage cmd) {
        if (mState != STATE_WAIT_FOR_REPORT_AUDIO_STATUS || mAvrAddress != cmd.getSource()) {
            return false;
        }

        switch (cmd.getOpcode()) {
            case Constants.MESSAGE_REPORT_AUDIO_STATUS:
                handleReportAudioStatus(cmd);
                return true;
        }

        return false;
    }

    private void handleReportAudioStatus(HdmiCecMessage cmd) {
        byte[] params = cmd.getParams();
        boolean mute = HdmiUtils.isAudioStatusMute(cmd);
        int volume = HdmiUtils.getAudioStatusVolume(cmd);
        tv().setAudioStatus(mute, volume);

        if (!(tv().isSystemAudioActivated() ^ mute)) {
            // Toggle AVR's mute status to match with the system audio status.
            sendUserControlPressedAndReleased(mAvrAddress, HdmiCecKeycode.CEC_KEYCODE_MUTE);
        }
        finishWithCallback(HdmiControlManager.RESULT_SUCCESS);
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
