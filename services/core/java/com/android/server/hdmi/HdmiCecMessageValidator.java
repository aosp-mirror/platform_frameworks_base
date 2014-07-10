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

import android.hardware.hdmi.HdmiCecDeviceInfo;
import android.util.Slog;
import android.util.SparseArray;

/**
 * A helper class to validates {@link HdmiCecMessage}.
 */
public final class HdmiCecMessageValidator {
    private static final String TAG = "HdmiCecMessageValidator";

    private final HdmiControlService mService;

    interface ParameterValidator {
        boolean isValid(byte[] params);
    }

    final SparseArray<ParameterValidator> mValidators = new SparseArray<>();

    public HdmiCecMessageValidator(HdmiControlService service) {
        mService = service;

        // Messages related to the physical address.
        PhysicalAddressValidator physicalAddressValidator = new PhysicalAddressValidator();
        mValidators.append(Constants.MESSAGE_ACTIVE_SOURCE, physicalAddressValidator);
        mValidators.append(Constants.MESSAGE_INACTIVE_SOURCE, physicalAddressValidator);
        mValidators.append(Constants.MESSAGE_REPORT_PHYSICAL_ADDRESS,
                new ReportPhysicalAddressValidator());
        mValidators.append(Constants.MESSAGE_ROUTING_CHANGE, new RoutingChangeValidator());
        mValidators.append(Constants.MESSAGE_ROUTING_INFORMATION, physicalAddressValidator);
        mValidators.append(Constants.MESSAGE_SET_STREAM_PATH, physicalAddressValidator);
        mValidators.append(Constants.MESSAGE_SYSTEM_AUDIO_MODE_REQUEST, physicalAddressValidator);

        // Messages have no parameter.
        FixedLengthValidator noneValidator = new FixedLengthValidator(0);
        mValidators.append(Constants.MESSAGE_ABORT, noneValidator);
        mValidators.append(Constants.MESSAGE_GET_CEC_VERSION, noneValidator);
        mValidators.append(Constants.MESSAGE_GET_MENU_LANGUAGE, noneValidator);
        mValidators.append(Constants.MESSAGE_GIVE_AUDIO_STATUS, noneValidator);
        mValidators.append(Constants.MESSAGE_GIVE_DEVICE_POWER_STATUS, noneValidator);
        mValidators.append(Constants.MESSAGE_GIVE_DEVICE_VENDOR_ID, noneValidator);
        mValidators.append(Constants.MESSAGE_GIVE_OSD_NAME, noneValidator);
        mValidators.append(Constants.MESSAGE_GIVE_PHYSICAL_ADDRESS, noneValidator);
        mValidators.append(Constants.MESSAGE_GIVE_SYSTEM_AUDIO_MODE_STATUS, noneValidator);
        mValidators.append(Constants.MESSAGE_IMAGE_VIEW_ON, noneValidator);
        mValidators.append(Constants.MESSAGE_INITIATE_ARC, noneValidator);
        mValidators.append(Constants.MESSAGE_RECORD_OFF, noneValidator);
        mValidators.append(Constants.MESSAGE_RECORD_TV_SCREEN, noneValidator);
        mValidators.append(Constants.MESSAGE_REPORT_ARC_INITIATED, noneValidator);
        mValidators.append(Constants.MESSAGE_REPORT_ARC_TERMINATED, noneValidator);
        mValidators.append(Constants.MESSAGE_REQUEST_ARC_INITIATION, noneValidator);
        mValidators.append(Constants.MESSAGE_REQUEST_ARC_TERMINATION, noneValidator);
        mValidators.append(Constants.MESSAGE_REQUEST_ACTIVE_SOURCE, noneValidator);
        mValidators.append(Constants.MESSAGE_STANDBY, noneValidator);
        mValidators.append(Constants.MESSAGE_TERMINATE_ARC, noneValidator);
        mValidators.append(Constants.MESSAGE_TEXT_VIEW_ON, noneValidator);
        mValidators.append(Constants.MESSAGE_TUNER_STEP_DECREMENT, noneValidator);
        mValidators.append(Constants.MESSAGE_TUNER_STEP_INCREMENT, noneValidator);
        mValidators.append(Constants.MESSAGE_USER_CONTROL_RELEASED, noneValidator);
        mValidators.append(Constants.MESSAGE_VENDOR_REMOTE_BUTTON_UP, noneValidator);

        // TODO: Validate more than length for the following messages.

        // Messages for the One Touch Record.
        FixedLengthValidator oneByteValidator = new FixedLengthValidator(1);
        mValidators.append(Constants.MESSAGE_RECORD_ON, new VariableLengthValidator(1, 8));
        mValidators.append(Constants.MESSAGE_RECORD_STATUS, oneByteValidator);

        // TODO: Handle messages for the Timer Programming.

        // Messages for the System Information.
        mValidators.append(Constants.MESSAGE_CEC_VERSION, oneByteValidator);
        mValidators.append(Constants.MESSAGE_SET_MENU_LANGUAGE, new FixedLengthValidator(3));

        // TODO: Handle messages for the Deck Control.

        // TODO: Handle messages for the Tuner Control.

        // Messages for the Vendor Specific Commands.
        VariableLengthValidator maxLengthValidator = new VariableLengthValidator(0, 14);
        mValidators.append(Constants.MESSAGE_DEVICE_VENDOR_ID, new FixedLengthValidator(3));
        mValidators.append(Constants.MESSAGE_VENDOR_COMMAND, maxLengthValidator);
        mValidators.append(Constants.MESSAGE_VENDOR_COMMAND_WITH_ID, maxLengthValidator);
        mValidators.append(Constants.MESSAGE_VENDOR_REMOTE_BUTTON_DOWN, maxLengthValidator);

        // Messages for the OSD.
        mValidators.append(Constants.MESSAGE_SET_OSD_STRING, maxLengthValidator);
        mValidators.append(Constants.MESSAGE_SET_OSD_NAME, maxLengthValidator);

        // TODO: Handle messages for the Device Menu Control.

        // Messages for the Remote Control Passthrough.
        // TODO: Parse the first parameter and determine if it can have the next parameter.
        mValidators.append(Constants.MESSAGE_USER_CONTROL_PRESSED,
                new VariableLengthValidator(1, 2));

        // Messages for the Power Status.
        mValidators.append(Constants.MESSAGE_REPORT_POWER_STATUS, oneByteValidator);

        // Messages for the General Protocol.
        mValidators.append(Constants.MESSAGE_FEATURE_ABORT, new FixedLengthValidator(2));

        // Messages for the System Audio Control.
        mValidators.append(Constants.MESSAGE_REPORT_AUDIO_STATUS, oneByteValidator);
        mValidators.append(Constants.MESSAGE_REPORT_SHORT_AUDIO_DESCRIPTOR,
                new FixedLengthValidator(3));
        mValidators.append(Constants.MESSAGE_REQUEST_SHORT_AUDIO_DESCRIPTOR, oneByteValidator);
        mValidators.append(Constants.MESSAGE_SET_SYSTEM_AUDIO_MODE, oneByteValidator);
        mValidators.append(Constants.MESSAGE_SYSTEM_AUDIO_MODE_STATUS, oneByteValidator);

        // Messages for the Audio Rate Control.
        mValidators.append(Constants.MESSAGE_SET_AUDIO_RATE, oneByteValidator);

        // All Messages for the ARC have no parameters.

        // Messages for the Capability Discovery and Control.
        mValidators.append(Constants.MESSAGE_CDC_MESSAGE, maxLengthValidator);
    }

    boolean isValid(HdmiCecMessage message) {
        int opcode = message.getOpcode();
        ParameterValidator validator = mValidators.get(opcode);
        if (validator == null) {
            Slog.i(TAG, "No validator for the message: " + message);
            return true;
        }
        return validator.isValid(message.getParams());
    }

    private static class FixedLengthValidator implements ParameterValidator {
        private final int mLength;

        public FixedLengthValidator(int length) {
            mLength = length;
        }

        @Override
        public boolean isValid(byte[] params) {
            return params.length == mLength;
        }
    }

    private static class VariableLengthValidator implements ParameterValidator {
        private final int mMinLength;
        private final int mMaxLength;

        public VariableLengthValidator(int minLength, int maxLength) {
            mMinLength = minLength;
            mMaxLength = maxLength;
        }

        @Override
        public boolean isValid(byte[] params) {
            return params.length >= mMinLength && params.length <= mMaxLength;
        }
    }

    private boolean isValidPhysicalAddress(byte[] params, int offset) {
        int path = HdmiUtils.twoBytesToInt(params, offset);
        int portId = mService.pathToPortId(path);
        if (portId == Constants.INVALID_PORT_ID) {
            return false;
        }
        // TODO: Add more logic like validating 1.0.1.0.
        return true;
    }

    /**
     * Check if the given type is valid. A valid type is one of the actual
     * logical device types defined in the standard ({@link #DEVICE_TV},
     * {@link #DEVICE_PLAYBACK}, {@link #DEVICE_TUNER}, {@link #DEVICE_RECORDER},
     * and {@link #DEVICE_AUDIO_SYSTEM}).
     *
     * @param type device type
     * @return true if the given type is valid
     */
    static boolean isValidType(int type) {
        return (HdmiCecDeviceInfo.DEVICE_TV <= type
                && type <= HdmiCecDeviceInfo.DEVICE_VIDEO_PROCESSOR)
                && type != HdmiCecDeviceInfo.DEVICE_RESERVED;
    }

    private class PhysicalAddressValidator implements ParameterValidator {
        @Override
        public boolean isValid(byte[] params) {
            if (params.length != 2) {
                return false;
            }
            return isValidPhysicalAddress(params, 0);
        }
    }

    private class ReportPhysicalAddressValidator implements ParameterValidator {
        @Override
        public boolean isValid(byte[] params) {
            if (params.length != 3) {
                return false;
            }
            return isValidPhysicalAddress(params, 0) && isValidType(params[2]);
        }
    }

    private class RoutingChangeValidator implements ParameterValidator {
        @Override
        public boolean isValid(byte[] params) {
            if (params.length != 4) {
                return false;
            }
            return isValidPhysicalAddress(params, 0) && isValidPhysicalAddress(params, 2);
        }
    }
}
