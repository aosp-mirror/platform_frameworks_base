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

import android.annotation.NonNull;
import android.hardware.hdmi.DeviceFeatures;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * Represents a validated <Report Features> message with parsed parameters.
 *
 * Only parses the [CEC Version] and [Device Features] operands.
 * [All Device Types] and [RC Profile] are not parsed, but can be specified in construction.
 */
public class ReportFeaturesMessage extends HdmiCecMessage {

    @HdmiControlManager.HdmiCecVersion
    private final int mCecVersion;

    @NonNull
    private final DeviceFeatures mDeviceFeatures;

    private ReportFeaturesMessage(int source, int destination, byte[] params, int cecVersion,
            DeviceFeatures deviceFeatures) {
        super(source, destination, Constants.MESSAGE_REPORT_FEATURES, params, OK);
        mCecVersion = cecVersion;
        mDeviceFeatures = deviceFeatures;
    }

    /**
     * Static factory method. Intended for constructing outgoing or test messages, as it uses
     * structured types instead of raw bytes to construct the parameters.
     */
    public static HdmiCecMessage build(
            int source,
            @HdmiControlManager.HdmiCecVersion int cecVersion,
            List<Integer> allDeviceTypes,
            @Constants.RcProfile int rcProfile,
            List<Integer> rcFeatures,
            DeviceFeatures deviceFeatures) {

        byte cecVersionByte = (byte) (cecVersion & 0xFF);
        byte deviceTypes = 0;
        for (Integer deviceType : allDeviceTypes) {
            deviceTypes |= (byte) (1 << hdmiDeviceInfoDeviceTypeToShiftValue(deviceType));
        }

        byte rcProfileByte = 0;
        rcProfileByte |= (byte) (rcProfile << 6);
        if (rcProfile == Constants.RC_PROFILE_SOURCE) {
            for (@Constants.RcProfileSource Integer rcFeature : rcFeatures) {
                rcProfileByte |= (byte) (1 << rcFeature);
            }
        } else {
            @Constants.RcProfileTv byte rcProfileTv = (byte) (rcFeatures.get(0) & 0xFFFF);
            rcProfileByte |= rcProfileTv;
        }

        byte[] fixedOperands = {cecVersionByte, deviceTypes, rcProfileByte};
        byte[] deviceFeaturesBytes = deviceFeatures.toOperand();

        // Concatenate fixed length operands and [Device Features]
        byte[] params = Arrays.copyOf(fixedOperands,
                fixedOperands.length + deviceFeaturesBytes.length);
        System.arraycopy(deviceFeaturesBytes, 0, params,
                fixedOperands.length, deviceFeaturesBytes.length);

        @ValidationResult
        int addressValidationResult = validateAddress(source, Constants.ADDR_BROADCAST);
        if (addressValidationResult != OK) {
            return new HdmiCecMessage(source, Constants.ADDR_BROADCAST,
                    Constants.MESSAGE_REPORT_FEATURES, params, addressValidationResult);
        } else {
            return new ReportFeaturesMessage(source, Constants.ADDR_BROADCAST, params,
                    cecVersion, deviceFeatures);
        }
    }

    @Constants.DeviceType
    private static int hdmiDeviceInfoDeviceTypeToShiftValue(int deviceType) {
        switch (deviceType) {
            case HdmiDeviceInfo.DEVICE_TV:
                return Constants.ALL_DEVICE_TYPES_TV;
            case HdmiDeviceInfo.DEVICE_RECORDER:
                return Constants.ALL_DEVICE_TYPES_RECORDER;
            case HdmiDeviceInfo.DEVICE_TUNER:
                return Constants.ALL_DEVICE_TYPES_TUNER;
            case HdmiDeviceInfo.DEVICE_PLAYBACK:
                return Constants.ALL_DEVICE_TYPES_PLAYBACK;
            case HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM:
                return Constants.ALL_DEVICE_TYPES_AUDIO_SYSTEM;
            case HdmiDeviceInfo.DEVICE_PURE_CEC_SWITCH:
                return Constants.ALL_DEVICE_TYPES_SWITCH;
            default:
                throw new IllegalArgumentException("Unhandled device type: " + deviceType);
        }
    }

    /**
     * Must only be called from {@link HdmiCecMessage#build}.
     *
     * Parses and validates CEC message data as a <Report Features> message. Intended for
     * constructing a representation of an incoming message, as it takes raw bytes for parameters.
     *
     * If successful, returns an instance of {@link ReportFeaturesMessage}.
     * If unsuccessful, returns an {@link HdmiCecMessage} with the reason for validation failure
     * accessible through {@link HdmiCecMessage#getValidationResult}.
     */
    static HdmiCecMessage build(int source, int destination, byte[] params) {
        // Helper function for building a message that failed validation
        Function<Integer, HdmiCecMessage> invalidMessage =
                validationResult -> new HdmiCecMessage(source, destination,
                        Constants.MESSAGE_REPORT_FEATURES, params, validationResult);

        @ValidationResult int addressValidationResult = validateAddress(source, destination);
        if (addressValidationResult != OK) {
            return invalidMessage.apply(addressValidationResult);
        }

        if (params.length < 4) {
            return invalidMessage.apply(ERROR_PARAMETER_SHORT);
        }

        int cecVersion = Byte.toUnsignedInt(params[0]);

        int rcProfileEnd = HdmiUtils.getEndOfSequence(params, 2);
        if (rcProfileEnd == -1) {
            return invalidMessage.apply(ERROR_PARAMETER_SHORT);
        }
        int deviceFeaturesEnd = HdmiUtils.getEndOfSequence(
                params, rcProfileEnd + 1);
        if (deviceFeaturesEnd == -1) {
            return invalidMessage.apply(ERROR_PARAMETER_SHORT);
        }
        int deviceFeaturesStart = HdmiUtils.getEndOfSequence(params, 2) + 1;
        byte[] deviceFeaturesBytes = Arrays.copyOfRange(params, deviceFeaturesStart, params.length);
        DeviceFeatures deviceFeatures = DeviceFeatures.fromOperand(deviceFeaturesBytes);

        return new ReportFeaturesMessage(source, destination, params, cecVersion, deviceFeatures);
    }

    /**
     * Validates the source and destination addresses for a <Report Features> message.
     */
    public static int validateAddress(int source, int destination) {
        return HdmiCecMessageValidator.validateAddress(source, destination,
                HdmiCecMessageValidator.DEST_BROADCAST);
    }

    /**
     * Returns the contents of the [CEC Version] operand: the version number of the CEC
     * specification which was used to design the device.
     */
    @HdmiControlManager.HdmiCecVersion
    public int getCecVersion() {
        return mCecVersion;
    }

    /**
     * Returns the contents of the [Device Features] operand: the set of features supported by
     * the device.
     */
    public DeviceFeatures getDeviceFeatures() {
        return mDeviceFeatures;
    }
}
