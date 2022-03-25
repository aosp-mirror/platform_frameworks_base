/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.hardware.hdmi.DeviceFeatures.FEATURE_SUPPORTED;

import static com.android.server.hdmi.Constants.MESSAGE_SET_AUDIO_VOLUME_LEVEL;

import android.hardware.hdmi.DeviceFeatures;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.IHdmiControlCallback;
import android.hardware.tv.cec.V1_0.SendMessageResult;

/**
 * Determines whether a target device supports the <Set Audio Volume Level> message.
 *
 * Sends the device <Set Audio Volume Level>[0x7F]. The value 0x7F is defined by the spec such that
 * setting the volume to this level results in no change to the current volume level.
 *
 * The target device supports <Set Audio Volume Level> only if it does not respond with
 * <Feature Abort> within {@link HdmiConfig.TIMEOUT_MS} milliseconds.
 */
public class SetAudioVolumeLevelDiscoveryAction extends HdmiCecFeatureAction {
    private static final String TAG = "SetAudioVolumeLevelDiscoveryAction";

    private static final int STATE_WAITING_FOR_FEATURE_ABORT = 1;

    private final int mTargetAddress;

    public SetAudioVolumeLevelDiscoveryAction(HdmiCecLocalDevice source,
            int targetAddress, IHdmiControlCallback callback) {
        super(source, callback);

        mTargetAddress = targetAddress;
    }

    boolean start() {
        sendCommand(SetAudioVolumeLevelMessage.build(
                getSourceAddress(), mTargetAddress, Constants.AUDIO_VOLUME_STATUS_UNKNOWN),
                result -> {
                    if (result == SendMessageResult.SUCCESS) {
                        // Message sent successfully; wait for <Feature Abort> in response
                        mState = STATE_WAITING_FOR_FEATURE_ABORT;
                        addTimer(mState, HdmiConfig.TIMEOUT_MS);
                    } else {
                        finishWithCallback(HdmiControlManager.RESULT_COMMUNICATION_FAILED);
                    }
                });
        return true;
    }

    boolean processCommand(HdmiCecMessage cmd) {
        if (mState != STATE_WAITING_FOR_FEATURE_ABORT) {
            return false;
        }
        switch (cmd.getOpcode()) {
            case Constants.MESSAGE_FEATURE_ABORT:
                return handleFeatureAbort(cmd);
            default:
                return false;
        }
    }

    private boolean handleFeatureAbort(HdmiCecMessage cmd) {
        if (cmd.getParams().length < 2) {
            return false;
        }
        int originalOpcode = cmd.getParams()[0] & 0xFF;
        if (originalOpcode == MESSAGE_SET_AUDIO_VOLUME_LEVEL && cmd.getSource() == mTargetAddress) {
            // No need to update the network, since it should already have processed this message.
            finishWithCallback(HdmiControlManager.RESULT_SUCCESS);
            return true;
        }
        return false;
    }

    void handleTimerEvent(int state) {
        if (updateAvcSupport(FEATURE_SUPPORTED)) {
            finishWithCallback(HdmiControlManager.RESULT_SUCCESS);
        } else {
            finishWithCallback(HdmiControlManager.RESULT_EXCEPTION);
        }
    }

    /**
     * Updates the System Audio device's support for <Set Audio Volume Level> in the
     * {@link HdmiCecNetwork}. Can fail if the System Audio device is not in our
     * {@link HdmiCecNetwork}.
     *
     * @return Whether support was successfully updated in the network.
     */
    private boolean updateAvcSupport(
            @DeviceFeatures.FeatureSupportStatus int setAudioVolumeLevelSupport) {
        HdmiCecNetwork network = localDevice().mService.getHdmiCecNetwork();
        HdmiDeviceInfo currentDeviceInfo = network.getCecDeviceInfo(mTargetAddress);

        if (currentDeviceInfo == null) {
            return false;
        } else {
            network.updateCecDevice(
                    currentDeviceInfo.toBuilder()
                            .setDeviceFeatures(currentDeviceInfo.getDeviceFeatures().toBuilder()
                                    .setSetAudioVolumeLevelSupport(setAudioVolumeLevelSupport)
                                    .build())
                            .build()
            );
            return true;
        }
    }
}
