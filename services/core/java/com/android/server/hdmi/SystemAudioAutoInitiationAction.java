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

import com.android.server.hdmi.HdmiControlService.SendMessageCallback;

/**
 * Action to initiate system audio once AVR is detected on Device discovery action.
 */
// Seq #27
final class SystemAudioAutoInitiationAction extends HdmiCecFeatureAction {
    private final int mAvrAddress;

    // State that waits for <System Audio Mode Status> once send
    // <Give System Audio Mode Status> to AV Receiver.
    private static final int STATE_WAITING_FOR_SYSTEM_AUDIO_MODE_STATUS = 1;

    SystemAudioAutoInitiationAction(HdmiCecLocalDevice source, int avrAddress) {
        super(source);
        mAvrAddress = avrAddress;
    }

    @Override
    boolean start() {
        mState = STATE_WAITING_FOR_SYSTEM_AUDIO_MODE_STATUS;

        addTimer(mState, HdmiConfig.TIMEOUT_MS);
        sendGiveSystemAudioModeStatus();
        return true;
    }

    private void sendGiveSystemAudioModeStatus() {
        sendCommand(HdmiCecMessageBuilder.buildGiveSystemAudioModeStatus(getSourceAddress(),
                mAvrAddress), new SendMessageCallback() {
            @Override
            public void onSendCompleted(int error) {
                if (error != Constants.SEND_RESULT_SUCCESS) {
                    tv().setSystemAudioMode(false, true);
                    finish();
                }
            }
        });
    }

    @Override
    boolean processCommand(HdmiCecMessage cmd) {
        if (mState != STATE_WAITING_FOR_SYSTEM_AUDIO_MODE_STATUS) {
            return false;
        }

        switch (cmd.getOpcode()) {
            case Constants.MESSAGE_SYSTEM_AUDIO_MODE_STATUS:
                handleSystemAudioModeStatusMessage();
                return true;
            default:
                return false;
        }
    }

    private void handleSystemAudioModeStatusMessage() {
        // If the last setting is system audio, turn on system audio whatever AVR status is.
        if (tv().getSystemAudioModeSetting()) {
            if (canChangeSystemAudio()) {
                addAndStartAction(new SystemAudioActionFromTv(tv(), mAvrAddress, true, null));
            }
        } else {
            // If the last setting is non-system audio, turn off system audio mode
            // and update system audio status (volume or mute).
            tv().setSystemAudioMode(false, true);
            if (canChangeSystemAudio()) {
                addAndStartAction(new SystemAudioStatusAction(tv(), mAvrAddress, null));
            }
        }
        finish();
    }

    @Override
    void handleTimerEvent(int state) {
        if (mState != state) {
            return;
        }

        switch (mState) {
            case STATE_WAITING_FOR_SYSTEM_AUDIO_MODE_STATUS:
                handleSystemAudioModeStatusTimeout();
                break;
        }
    }

    private void handleSystemAudioModeStatusTimeout() {
        if (tv().getSystemAudioModeSetting()) {
            if (canChangeSystemAudio()) {
                addAndStartAction(new SystemAudioActionFromTv(tv(), mAvrAddress, true, null));
            }
        } else {
            tv().setSystemAudioMode(false, true);
        }
        finish();
    }

    private boolean canChangeSystemAudio() {
        return !(tv().hasAction(SystemAudioActionFromTv.class)
               || tv().hasAction(SystemAudioActionFromAvr.class));
    }
}
