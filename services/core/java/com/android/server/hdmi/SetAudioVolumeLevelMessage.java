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

import static com.android.server.hdmi.HdmiCecMessageValidator.ERROR_PARAMETER_SHORT;
import static com.android.server.hdmi.HdmiCecMessageValidator.OK;
import static com.android.server.hdmi.HdmiCecMessageValidator.ValidationResult;

/**
 * Represents a validated <Set Audio Volume Level> message with parsed parameters.
 */
public class SetAudioVolumeLevelMessage extends HdmiCecMessage {
    private final int mAudioVolumeLevel;

    private SetAudioVolumeLevelMessage(int source, int destination, byte[] params,
            int audioVolumeLevel) {
        super(source, destination, Constants.MESSAGE_SET_AUDIO_VOLUME_LEVEL, params, OK);
        mAudioVolumeLevel = audioVolumeLevel;
    }

    /**
     * Static factory method. Intended for constructing outgoing or test messages, as it uses
     * structured types instead of raw bytes to construct the parameters.
     *
     * @param source Initiator address. Cannot be {@link Constants#ADDR_UNREGISTERED}
     * @param destination Destination address. Cannot be {@link Constants#ADDR_BROADCAST}
     * @param audioVolumeLevel [Audio Volume Level]. Either 0x7F (representing no volume change)
     *                         or between 0 and 100 inclusive (representing volume percentage).
     */
    public static HdmiCecMessage build(int source, int destination, int audioVolumeLevel) {
        byte[] params = { (byte) (audioVolumeLevel & 0xFF) };

        @ValidationResult
        int addressValidationResult = validateAddress(source, destination);
        if (addressValidationResult == OK) {
            return new SetAudioVolumeLevelMessage(source, destination, params, audioVolumeLevel);
        } else {
            return new HdmiCecMessage(source, destination, Constants.MESSAGE_SET_AUDIO_VOLUME_LEVEL,
                    params, addressValidationResult);
        }
    }

    /**
     * Must only be called from {@link HdmiCecMessage#build}.
     *
     * Parses and validates CEC message data as a <SetAudioVolumeLevel> message. Intended for
     * constructing a representation of an incoming message, as it takes raw bytes for
     * parameters.
     *
     * If successful, returns an instance of {@link SetAudioVolumeLevelMessage}.
     * If unsuccessful, returns an {@link HdmiCecMessage} with the reason for validation failure
     * accessible through {@link HdmiCecMessage#getValidationResult}.
     */
    public static HdmiCecMessage build(int source, int destination, byte[] params) {
        if (params.length == 0) {
            return new HdmiCecMessage(source, destination, Constants.MESSAGE_SET_AUDIO_VOLUME_LEVEL,
                    params, ERROR_PARAMETER_SHORT);
        }

        int audioVolumeLevel = params[0];

        @ValidationResult
        int addressValidationResult = validateAddress(source, destination);
        if (addressValidationResult == OK) {
            return new SetAudioVolumeLevelMessage(source, destination, params, audioVolumeLevel);
        } else {
            return new HdmiCecMessage(source, destination, Constants.MESSAGE_SET_AUDIO_VOLUME_LEVEL,
                    params, addressValidationResult);
        }
    }

    /**
     * Validates the source and destination addresses for a <Set Audio Volume Level> message.
     */
    public static int validateAddress(int source, int destination) {
        return HdmiCecMessageValidator.validateAddress(source, destination,
                HdmiCecMessageValidator.DEST_DIRECT);
    }

    /**
     * Returns the contents of the [Audio Volume Level] operand:
     * either 0x7F, indicating no change to the current volume level,
     * or a percentage between 0 and 100 (inclusive).
     */
    public int getAudioVolumeLevel() {
        return mAudioVolumeLevel;
    }
}
