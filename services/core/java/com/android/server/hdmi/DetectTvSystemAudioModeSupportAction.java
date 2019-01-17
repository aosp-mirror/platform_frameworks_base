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

import com.android.server.hdmi.HdmiCecLocalDeviceAudioSystem.TvSystemAudioModeSupportedCallback;

/**
 * Feature action that detects if TV supports system audio control.
 */
public class DetectTvSystemAudioModeSupportAction extends HdmiCecFeatureAction {

    // State that waits for <Active Source> once send <Request Active Source>.
    private static final int STATE_WAITING_FOR_FEATURE_ABORT = 1;

    private TvSystemAudioModeSupportedCallback mCallback;
    private int mState;

    DetectTvSystemAudioModeSupportAction(HdmiCecLocalDevice source,
            TvSystemAudioModeSupportedCallback callback) {
        super(source);
        mCallback = callback;
    }

    @Override
    boolean start() {
        mState = STATE_WAITING_FOR_FEATURE_ABORT;
        addTimer(mState, HdmiConfig.TIMEOUT_MS);
        sendSetSystemAudioMode();
        return true;
    }

    @Override
    boolean processCommand(HdmiCecMessage cmd) {
        if (cmd.getOpcode() == Constants.MESSAGE_FEATURE_ABORT) {
            if (mState != STATE_WAITING_FOR_FEATURE_ABORT) {
                return false;
            }
            if ((cmd.getParams()[0] & 0xFF) == Constants.MESSAGE_SET_SYSTEM_AUDIO_MODE) {
                finishAction(false);
                return true;
            }
        }
        return false;
    }

    @Override
    void handleTimerEvent(int state) {
        if (mState != state) {
            return;
        }

        switch (mState) {
            case STATE_WAITING_FOR_FEATURE_ABORT:
                finishAction(true);
                break;
        }
    }

    protected void sendSetSystemAudioMode() {
        sendCommand(
                HdmiCecMessageBuilder.buildSetSystemAudioMode(getSourceAddress(),Constants.ADDR_TV,
                        true),
                result -> {
                    if (result != SendMessageResult.SUCCESS) {
                        finishAction(false);
                    }
                });
    }

    private void finishAction(boolean supported) {
        mCallback.onResult(supported);
        audioSystem().setTvSystemAudioModeSupport(supported);
        finish();
    }
}
