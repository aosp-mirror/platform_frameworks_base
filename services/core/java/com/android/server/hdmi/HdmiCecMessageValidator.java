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

import android.hardware.hdmi.HdmiDeviceInfo;
import android.util.SparseArray;

/**
 * A helper class to validates {@link HdmiCecMessage}.
 */
public class HdmiCecMessageValidator {
    private static final String TAG = "HdmiCecMessageValidator";

    static final int OK = 0;
    static final int ERROR_SOURCE = 1;
    static final int ERROR_DESTINATION = 2;
    static final int ERROR_PARAMETER = 3;
    static final int ERROR_PARAMETER_SHORT = 4;

    private final HdmiControlService mService;

    interface ParameterValidator {
        /**
         * @return errorCode errorCode can be {@link #OK}, {@link #ERROR_PARAMETER} or
         *         {@link #ERROR_PARAMETER_SHORT}.
         */
        int isValid(byte[] params);
    }

    // Only the direct addressing is allowed.
    private static final int DEST_DIRECT = 1 << 0;
    // Only the broadcast addressing is allowed.
    private static final int DEST_BROADCAST = 1 << 1;
    // Both the direct and the broadcast addressing are allowed.
    private static final int DEST_ALL = DEST_DIRECT | DEST_BROADCAST;
    // True if the messages from address 15 (unregistered) are allowed.
    private static final int SRC_UNREGISTERED = 1 << 2;

    private static class ValidationInfo {
        public final ParameterValidator parameterValidator;
        public final int addressType;

        public ValidationInfo(ParameterValidator validator, int type) {
            parameterValidator = validator;
            addressType = type;
        }
    }

    final SparseArray<ValidationInfo> mValidationInfo = new SparseArray<>();

    public HdmiCecMessageValidator(HdmiControlService service) {
        mService = service;

        // Messages related to the physical address.
        PhysicalAddressValidator physicalAddressValidator = new PhysicalAddressValidator();
        addValidationInfo(Constants.MESSAGE_ACTIVE_SOURCE,
                physicalAddressValidator, DEST_BROADCAST | SRC_UNREGISTERED);
        addValidationInfo(Constants.MESSAGE_INACTIVE_SOURCE, physicalAddressValidator, DEST_DIRECT);
        addValidationInfo(Constants.MESSAGE_REPORT_PHYSICAL_ADDRESS,
                new ReportPhysicalAddressValidator(), DEST_BROADCAST | SRC_UNREGISTERED);
        addValidationInfo(Constants.MESSAGE_ROUTING_CHANGE,
                new RoutingChangeValidator(), DEST_BROADCAST | SRC_UNREGISTERED);
        addValidationInfo(Constants.MESSAGE_ROUTING_INFORMATION,
                physicalAddressValidator, DEST_BROADCAST | SRC_UNREGISTERED);
        addValidationInfo(Constants.MESSAGE_SET_STREAM_PATH,
                physicalAddressValidator, DEST_BROADCAST);
        addValidationInfo(Constants.MESSAGE_SYSTEM_AUDIO_MODE_REQUEST,
                new SystemAudioModeRequestValidator(), DEST_DIRECT);

        // Messages have no parameter.
        FixedLengthValidator noneValidator = new FixedLengthValidator(0);
        addValidationInfo(Constants.MESSAGE_ABORT, noneValidator, DEST_DIRECT);
        addValidationInfo(Constants.MESSAGE_GET_CEC_VERSION, noneValidator, DEST_DIRECT);
        addValidationInfo(Constants.MESSAGE_GET_MENU_LANGUAGE,
                noneValidator, DEST_DIRECT | SRC_UNREGISTERED);
        addValidationInfo(Constants.MESSAGE_GIVE_AUDIO_STATUS, noneValidator, DEST_DIRECT);
        addValidationInfo(Constants.MESSAGE_GIVE_DEVICE_POWER_STATUS, noneValidator, DEST_DIRECT);
        addValidationInfo(Constants.MESSAGE_GIVE_DEVICE_VENDOR_ID,
                noneValidator, DEST_DIRECT | SRC_UNREGISTERED);
        addValidationInfo(Constants.MESSAGE_GIVE_OSD_NAME, noneValidator, DEST_DIRECT);
        addValidationInfo(Constants.MESSAGE_GIVE_PHYSICAL_ADDRESS,
                noneValidator, DEST_DIRECT | SRC_UNREGISTERED);
        addValidationInfo(Constants.MESSAGE_GIVE_SYSTEM_AUDIO_MODE_STATUS,
                noneValidator, DEST_DIRECT);
        addValidationInfo(Constants.MESSAGE_IMAGE_VIEW_ON, noneValidator, DEST_DIRECT);
        addValidationInfo(Constants.MESSAGE_INITIATE_ARC, noneValidator, DEST_DIRECT);
        addValidationInfo(Constants.MESSAGE_RECORD_OFF, noneValidator, DEST_DIRECT);
        addValidationInfo(Constants.MESSAGE_RECORD_TV_SCREEN, noneValidator, DEST_DIRECT);
        addValidationInfo(Constants.MESSAGE_REPORT_ARC_INITIATED, noneValidator, DEST_DIRECT);
        addValidationInfo(Constants.MESSAGE_REPORT_ARC_TERMINATED, noneValidator, DEST_DIRECT);
        addValidationInfo(Constants.MESSAGE_REQUEST_ARC_INITIATION, noneValidator, DEST_DIRECT);
        addValidationInfo(Constants.MESSAGE_REQUEST_ARC_TERMINATION, noneValidator, DEST_DIRECT);
        addValidationInfo(Constants.MESSAGE_REQUEST_ACTIVE_SOURCE,
                noneValidator, DEST_BROADCAST | SRC_UNREGISTERED);
        addValidationInfo(Constants.MESSAGE_STANDBY, noneValidator, DEST_ALL | SRC_UNREGISTERED);
        addValidationInfo(Constants.MESSAGE_TERMINATE_ARC, noneValidator, DEST_DIRECT);
        addValidationInfo(Constants.MESSAGE_TEXT_VIEW_ON, noneValidator, DEST_DIRECT);
        addValidationInfo(Constants.MESSAGE_TUNER_STEP_DECREMENT, noneValidator, DEST_DIRECT);
        addValidationInfo(Constants.MESSAGE_TUNER_STEP_INCREMENT, noneValidator, DEST_DIRECT);
        addValidationInfo(Constants.MESSAGE_USER_CONTROL_RELEASED, noneValidator, DEST_DIRECT);
        addValidationInfo(Constants.MESSAGE_VENDOR_REMOTE_BUTTON_UP, noneValidator, DEST_ALL);

        // TODO: Validate more than length for the following messages.

        // Messages for the One Touch Record.
        addValidationInfo(Constants.MESSAGE_RECORD_ON,
                new VariableLengthValidator(1, 8), DEST_DIRECT);
        addValidationInfo(Constants.MESSAGE_RECORD_STATUS,
                new RecordStatusInfoValidator(), DEST_DIRECT);

        addValidationInfo(
                Constants.MESSAGE_CLEAR_ANALOG_TIMER, new AnalogueTimerValidator(), DEST_DIRECT);
        addValidationInfo(
                Constants.MESSAGE_CLEAR_DIGITAL_TIMER, new DigitalTimerValidator(), DEST_DIRECT);
        addValidationInfo(
                Constants.MESSAGE_CLEAR_EXTERNAL_TIMER, new ExternalTimerValidator(), DEST_DIRECT);
        addValidationInfo(
                Constants.MESSAGE_SET_ANALOG_TIMER, new AnalogueTimerValidator(), DEST_DIRECT);
        addValidationInfo(
                Constants.MESSAGE_SET_DIGITAL_TIMER, new DigitalTimerValidator(), DEST_DIRECT);
        addValidationInfo(
                Constants.MESSAGE_SET_EXTERNAL_TIMER, new ExternalTimerValidator(), DEST_DIRECT);
        addValidationInfo(
                Constants.MESSAGE_SET_TIMER_PROGRAM_TITLE, new AsciiValidator(1, 14), DEST_DIRECT);
        addValidationInfo(
                Constants.MESSAGE_TIMER_CLEARED_STATUS,
                new TimerClearedStatusValidator(),
                DEST_DIRECT);
        addValidationInfo(Constants.MESSAGE_TIMER_STATUS, new TimerStatusValidator(), DEST_DIRECT);

        // Messages for the System Information.
        FixedLengthValidator oneByteValidator = new FixedLengthValidator(1);
        addValidationInfo(Constants.MESSAGE_CEC_VERSION, oneByteValidator, DEST_DIRECT);
        addValidationInfo(Constants.MESSAGE_SET_MENU_LANGUAGE,
                new AsciiValidator(3), DEST_BROADCAST);

        // TODO: Handle messages for the Deck Control.
        addValidationInfo(
                Constants.MESSAGE_DECK_CONTROL, new OneByteRangeValidator(0x01, 0x04), DEST_DIRECT);

        // TODO: Handle messages for the Tuner Control.

        // Messages for the Vendor Specific Commands.
        VariableLengthValidator maxLengthValidator = new VariableLengthValidator(0, 14);
        addValidationInfo(Constants.MESSAGE_DEVICE_VENDOR_ID,
                new FixedLengthValidator(3), DEST_BROADCAST);
        // Allow unregistered source for all vendor specific commands, because we don't know
        // how to use the commands at this moment.
        addValidationInfo(Constants.MESSAGE_VENDOR_COMMAND,
                new VariableLengthValidator(1, 14), DEST_DIRECT | SRC_UNREGISTERED);
        addValidationInfo(Constants.MESSAGE_VENDOR_COMMAND_WITH_ID,
                new VariableLengthValidator(4, 14), DEST_ALL | SRC_UNREGISTERED);
        addValidationInfo(Constants.MESSAGE_VENDOR_REMOTE_BUTTON_DOWN,
                maxLengthValidator, DEST_ALL | SRC_UNREGISTERED);

        // Messages for the OSD.
        addValidationInfo(Constants.MESSAGE_SET_OSD_STRING, new OsdStringValidator(), DEST_DIRECT);
        addValidationInfo(Constants.MESSAGE_SET_OSD_NAME, new AsciiValidator(1, 14), DEST_DIRECT);

        // Messages for the Device Menu Control.
        addValidationInfo(
                Constants.MESSAGE_MENU_REQUEST, new OneByteRangeValidator(0x00, 0x02), DEST_DIRECT);
        addValidationInfo(
                Constants.MESSAGE_MENU_STATUS, new OneByteRangeValidator(0x00, 0x01), DEST_DIRECT);

        // Messages for the Remote Control Passthrough.
        // TODO: Parse the first parameter and determine if it can have the next parameter.
        addValidationInfo(Constants.MESSAGE_USER_CONTROL_PRESSED,
                new VariableLengthValidator(1, 2), DEST_DIRECT);

        // Messages for the Power Status.
        addValidationInfo(
                Constants.MESSAGE_REPORT_POWER_STATUS,
                new OneByteRangeValidator(0x00, 0x03),
                DEST_DIRECT);

        // Messages for the General Protocol.
        addValidationInfo(Constants.MESSAGE_FEATURE_ABORT,
                new FixedLengthValidator(2), DEST_DIRECT);

        // Messages for the System Audio Control.
        addValidationInfo(Constants.MESSAGE_REPORT_AUDIO_STATUS, oneByteValidator, DEST_DIRECT);
        addValidationInfo(Constants.MESSAGE_REPORT_SHORT_AUDIO_DESCRIPTOR,
                new FixedLengthValidator(3), DEST_DIRECT);
        addValidationInfo(Constants.MESSAGE_REQUEST_SHORT_AUDIO_DESCRIPTOR,
                oneByteValidator, DEST_DIRECT);
        addValidationInfo(
                Constants.MESSAGE_SET_SYSTEM_AUDIO_MODE,
                new OneByteRangeValidator(0x00, 0x01),
                DEST_ALL);
        addValidationInfo(
                Constants.MESSAGE_SYSTEM_AUDIO_MODE_STATUS,
                new OneByteRangeValidator(0x00, 0x01),
                DEST_DIRECT);

        // Messages for the Audio Rate Control.
        addValidationInfo(
                Constants.MESSAGE_SET_AUDIO_RATE,
                new OneByteRangeValidator(0x00, 0x06),
                DEST_DIRECT);

        // All Messages for the ARC have no parameters.

        // Messages for the Capability Discovery and Control.
        addValidationInfo(Constants.MESSAGE_CDC_MESSAGE, maxLengthValidator,
                DEST_BROADCAST | SRC_UNREGISTERED);
    }

    private void addValidationInfo(int opcode, ParameterValidator validator, int addrType) {
        mValidationInfo.append(opcode, new ValidationInfo(validator, addrType));
    }

    int isValid(HdmiCecMessage message) {
        int opcode = message.getOpcode();
        ValidationInfo info = mValidationInfo.get(opcode);
        if (info == null) {
            HdmiLogger.warning("No validation information for the message: " + message);
            return OK;
        }

        // Check the source field.
        if (message.getSource() == Constants.ADDR_UNREGISTERED &&
                (info.addressType & SRC_UNREGISTERED) == 0) {
            HdmiLogger.warning("Unexpected source: " + message);
            return ERROR_SOURCE;
        }
        // Check the destination field.
        if (message.getDestination() == Constants.ADDR_BROADCAST) {
            if ((info.addressType & DEST_BROADCAST) == 0) {
                HdmiLogger.warning("Unexpected broadcast message: " + message);
                return ERROR_DESTINATION;
            }
        } else {  // Direct addressing.
            if ((info.addressType & DEST_DIRECT) == 0) {
                HdmiLogger.warning("Unexpected direct message: " + message);
                return ERROR_DESTINATION;
            }
        }

        // Check the parameter type.
        int errorCode = info.parameterValidator.isValid(message.getParams());
        if (errorCode != OK) {
            HdmiLogger.warning("Unexpected parameters: " + message);
            return errorCode;
        }
        return OK;
    }

    private static class FixedLengthValidator implements ParameterValidator {
        private final int mLength;

        public FixedLengthValidator(int length) {
            mLength = length;
        }

        @Override
        public int isValid(byte[] params) {
            // If the length is longer than expected, we assume it's OK since the parameter can be
            // extended in the future version.
            return params.length < mLength ? ERROR_PARAMETER_SHORT : OK;
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
        public int isValid(byte[] params) {
            return params.length < mMinLength ? ERROR_PARAMETER_SHORT : OK;
        }
    }

    private boolean isValidPhysicalAddress(byte[] params, int offset) {
        // TODO: Add more logic like validating 1.0.1.0.

        if (!mService.isTvDevice()) {
            // If the device is not TV, we can't convert path to port-id, so stop here.
            return true;
        }
        int path = HdmiUtils.twoBytesToInt(params, offset);
        if (path != Constants.INVALID_PHYSICAL_ADDRESS && path == mService.getPhysicalAddress()) {
            return true;
        }
        int portId = mService.pathToPortId(path);
        if (portId == Constants.INVALID_PORT_ID) {
            return false;
        }
        return true;
    }

    /**
     * Check if the given type is valid. A valid type is one of the actual logical device types
     * defined in the standard ({@link HdmiDeviceInfo#DEVICE_TV},
     * {@link HdmiDeviceInfo#DEVICE_PLAYBACK}, {@link HdmiDeviceInfo#DEVICE_TUNER},
     * {@link HdmiDeviceInfo#DEVICE_RECORDER}, and {@link HdmiDeviceInfo#DEVICE_AUDIO_SYSTEM}).
     *
     * @param type device type
     * @return true if the given type is valid
     */
    static boolean isValidType(int type) {
        return (HdmiDeviceInfo.DEVICE_TV <= type
                && type <= HdmiDeviceInfo.DEVICE_VIDEO_PROCESSOR)
                && type != HdmiDeviceInfo.DEVICE_RESERVED;
    }

    private static int toErrorCode(boolean success) {
        return success ? OK : ERROR_PARAMETER;
    }

    private boolean isWithinRange(int value, int min, int max) {
        value = value & 0xFF;
        return (value >= min && value <= max);
    }

    /**
     * Check if the given value is a valid Display Control. A valid value is one which falls within
     * the range description defined in CEC 1.4 Specification : Operand Descriptions (Section 17)
     *
     * @param value Display Control
     * @return true if the Display Control is valid
     */
    private boolean isValidDisplayControl(int value) {
        value = value & 0xFF;
        return (value == 0x00 || value == 0x40 || value == 0x80 || value == 0xC0);
    }

    /**
     * Check if the given params has valid ASCII characters.
     * A valid ASCII character is a printable character. It should fall within range description
     * defined in CEC 1.4 Specification : Operand Descriptions (Section 17)
     *
     * @param params parameter consisting of string
     * @param offset Start offset of string
     * @param maxLength Maximum length of string to be evaluated
     * @return true if the given type is valid
     */
    private boolean isValidAsciiString(byte[] params, int offset, int maxLength) {
        for (int i = offset; i < params.length && i < maxLength; i++) {
            if (!isWithinRange(params[i], 0x20, 0x7E)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Check if the given value is a valid day of month. A valid value is one which falls within the
     * range description defined in CEC 1.4 Specification : Operand Descriptions (Section 17)
     *
     * @param value day of month
     * @return true if the day of month is valid
     */
    private boolean isValidDayOfMonth(int value) {
        return isWithinRange(value, 1, 31);
    }

    /**
     * Check if the given value is a valid month of year. A valid value is one which falls within
     * the range description defined in CEC 1.4 Specification : Operand Descriptions (Section 17)
     *
     * @param value month of year
     * @return true if the month of year is valid
     */
    private boolean isValidMonthOfYear(int value) {
        return isWithinRange(value, 1, 12);
    }

    /**
     * Check if the given value is a valid hour. A valid value is one which falls within the range
     * description defined in CEC 1.4 Specification : Operand Descriptions (Section 17)
     *
     * @param value hour
     * @return true if the hour is valid
     */
    private boolean isValidHour(int value) {
        return isWithinRange(value, 0, 23);
    }

    /**
     * Check if the given value is a valid minute. A valid value is one which falls within the range
     * description defined in CEC 1.4 Specification : Operand Descriptions (Section 17)
     *
     * @param value minute
     * @return true if the minute is valid
     */
    private boolean isValidMinute(int value) {
        return isWithinRange(value, 0, 59);
    }

    /**
     * Check if the given value is a valid duration hours. A valid value is one which falls within
     * the range description defined in CEC 1.4 Specification : Operand Descriptions (Section 17)
     *
     * @param value duration hours
     * @return true if the duration hours is valid
     */
    private boolean isValidDurationHours(int value) {
        return isWithinRange(value, 0, 99);
    }

    /**
     * Check if the given value is a valid recording sequence. A valid value is adheres to range
     * description defined in CEC 1.4 Specification : Operand Descriptions (Section 17)
     *
     * @param value recording sequence
     * @return true if the given recording sequence is valid
     */
    private boolean isValidRecordingSequence(int value) {
        value = value & 0xFF;
        // Validate bit 7 is set to zero
        if ((value & 0x80) != 0x00) {
            return false;
        }
        // Validate than not more than one bit is set
        return (Integer.bitCount(value) <= 1);
    }

    /**
     * Check if the given value is a valid analogue broadcast type. A valid value is one which falls
     * within the range description defined in CEC 1.4 Specification : Operand Descriptions (Section
     * 17)
     *
     * @param value analogue broadcast type
     * @return true if the analogue broadcast type is valid
     */
    private boolean isValidAnalogueBroadcastType(int value) {
        return isWithinRange(value, 0x00, 0x02);
    }

    /**
     * Check if the given value is a valid analogue frequency. A valid value is one which falls
     * within the range description defined in CEC 1.4 Specification : Operand Descriptions (Section
     * 17)
     *
     * @param value analogue frequency
     * @return true if the analogue frequency is valid
     */
    private boolean isValidAnalogueFrequency(int value) {
        value = value & 0xFFFF;
        return (value != 0x000 && value != 0xFFFF);
    }

    /**
     * Check if the given value is a valid broadcast system. A valid value is one which falls within
     * the range description defined in CEC 1.4 Specification : Operand Descriptions (Section 17)
     *
     * @param value broadcast system
     * @return true if the broadcast system is valid
     */
    private boolean isValidBroadcastSystem(int value) {
        return isWithinRange(value, 0, 31);
    }

    /**
     * Check if the given value is a ARIB type. A valid value is one which falls within the range
     * description defined in CEC 1.4 Specification : Operand Descriptions (Section 17)
     *
     * @param value Digital Broadcast System
     * @return true if the Digital Broadcast System is ARIB type
     */
    private boolean isAribDbs(int value) {
        return (value == 0x00 || isWithinRange(value, 0x08, 0x0A));
    }

    /**
     * Check if the given value is a ATSC type. A valid value is one which falls within the range
     * description defined in CEC 1.4 Specification : Operand Descriptions (Section 17)
     *
     * @param value Digital Broadcast System
     * @return true if the Digital Broadcast System is ATSC type
     */
    private boolean isAtscDbs(int value) {
        return (value == 0x01 || isWithinRange(value, 0x10, 0x12));
    }

    /**
     * Check if the given value is a DVB type. A valid value is one which falls within the range
     * description defined in CEC 1.4 Specification : Operand Descriptions (Section 17)
     *
     * @param value Digital Broadcast System
     * @return true if the Digital Broadcast System is DVB type
     */
    private boolean isDvbDbs(int value) {
        return (value == 0x02 || isWithinRange(value, 0x18, 0x1B));
    }

    /**
     * Check if the given value is a valid Digital Broadcast System. A valid value is one which
     * falls within the range description defined in CEC 1.4 Specification : Operand Descriptions
     * (Section 17)
     *
     * @param value Digital Broadcast System
     * @return true if the Digital Broadcast System is valid
     */
    private boolean isValidDigitalBroadcastSystem(int value) {
        return (isAribDbs(value) || isAtscDbs(value) || isDvbDbs(value));
    }

    /**
     * Check if the given value is a valid Digital Service Identification. A valid value is one
     * which falls within the range description defined in CEC 1.4 Specification : Operand
     * Descriptions (Section 17)
     *
     * @param params Digital Timer Message parameters
     * @param offset start offset of Digital Service Identification
     * @return true if the Digital Service Identification is valid
     */
    private boolean isValidDigitalServiceIdentification(byte[] params, int offset) {
        // MSB contains Service Identification Method
        int serviceIdentificationMethod = params[offset] & 0x80;
        // Last 7 bits contains Digital Broadcast System
        int digitalBroadcastSystem = params[offset] & 0x7F;
        offset = offset + 1;
        if (serviceIdentificationMethod == 0x00) {
            // Services identified by Digital IDs
            if (isAribDbs(digitalBroadcastSystem)) {
                // Validate ARIB type have 6 byte data
                return params.length - offset >= 6;
            } else if (isAtscDbs(digitalBroadcastSystem)) {
                // Validate ATSC type have 4 byte data
                return params.length - offset >= 4;
            } else if (isDvbDbs(digitalBroadcastSystem)) {
                // Validate DVB type have 6 byte data
                return params.length - offset >= 6;
            }
        } else if (serviceIdentificationMethod == 0x80) {
            // Services identified by Channel
            if (isValidDigitalBroadcastSystem(digitalBroadcastSystem)) {
                // First 6 bits contain Channel Number Format
                int channelNumberFormat = params[offset] & 0xFC;
                if (channelNumberFormat == 0x04) {
                    // Validate it contains 1-part Channel Number data (16 bits)
                    return params.length - offset >= 3;
                } else if (channelNumberFormat == 0x08) {
                    // Validate it contains Major Channel Number and Minor Channel Number (26 bits)
                    return params.length - offset >= 4;
                }
            }
        }
        return false;
    }

    /**
     * Check if the given value is a valid External Plug. A valid value is one which falls within
     * the range description defined in CEC 1.4 Specification : Operand Descriptions (Section 17)
     *
     * @param value External Plug
     * @return true if the External Plug is valid
     */
    private boolean isValidExternalPlug(int value) {
        return isWithinRange(value, 1, 255);
    }

    /**
     * Check if the given value is a valid External Source. A valid value is one which falls within
     * the range description defined in CEC 1.4 Specification : Operand Descriptions (Section 17)
     *
     * @param value External Source Specifier
     * @return true if the External Source is valid
     */
    private boolean isValidExternalSource(byte[] params, int offset) {
        int externalSourceSpecifier = params[offset];
        offset = offset + 1;
        if (externalSourceSpecifier == 0x04) {
            // External Plug
            return isValidExternalPlug(params[offset]);
        } else if (externalSourceSpecifier == 0x05) {
            // External Physical Address
            // Validate it contains 2 bytes Physical Address
            if (params.length - offset >= 2) {
                return isValidPhysicalAddress(params, offset);
            }
        }
        return false;
    }

    private boolean isValidProgrammedInfo(int programedInfo) {
        return (isWithinRange(programedInfo, 0x00, 0x0B));
    }

    private boolean isValidNotProgrammedErrorInfo(int nonProgramedErrorInfo) {
        return (isWithinRange(nonProgramedErrorInfo, 0x00, 0x0E));
    }

    private boolean isValidTimerStatusData(byte[] params, int offset) {
        int programedIndicator = params[offset] & 0x10;
        boolean durationAvailable = false;
        if (programedIndicator == 0x10) {
            // Programmed
            int programedInfo = params[offset] & 0x0F;
            if (isValidProgrammedInfo(programedInfo)) {
                if (programedInfo == 0x09 || programedInfo == 0x0B) {
                    durationAvailable = true;
                } else {
                    return true;
                }
            }
        } else {
            // Non programmed
            int nonProgramedErrorInfo = params[offset] & 0x0F;
            if (isValidNotProgrammedErrorInfo(nonProgramedErrorInfo)) {
                if (nonProgramedErrorInfo == 0x0E) {
                    durationAvailable = true;
                } else {
                    return true;
                }
            }
        }
        offset = offset + 1;
        // Duration Available (2 bytes)
        if (durationAvailable && params.length - offset >= 2) {
            return (isValidDurationHours(params[offset]) && isValidMinute(params[offset + 1]));
        }
        return false;
    }

    private class PhysicalAddressValidator implements ParameterValidator {
        @Override
        public int isValid(byte[] params) {
            if (params.length < 2) {
                return ERROR_PARAMETER_SHORT;
            }
            return toErrorCode(isValidPhysicalAddress(params, 0));
        }
    }

    private class SystemAudioModeRequestValidator extends PhysicalAddressValidator {
        @Override
        public int isValid(byte[] params) {
            // TV can send <System Audio Mode Request> with no parameters to terminate system audio.
            if (params.length == 0) {
                return OK;
            }
            return super.isValid(params);
        }
    }

    private class ReportPhysicalAddressValidator implements ParameterValidator {
        @Override
        public int isValid(byte[] params) {
            if (params.length < 3) {
                return ERROR_PARAMETER_SHORT;
            }
            return toErrorCode(isValidPhysicalAddress(params, 0) && isValidType(params[2]));
        }
    }

    private class RoutingChangeValidator implements ParameterValidator {
        @Override
        public int isValid(byte[] params) {
            if (params.length < 4) {
                return ERROR_PARAMETER_SHORT;
            }
            return toErrorCode(
                    isValidPhysicalAddress(params, 0) && isValidPhysicalAddress(params, 2));
        }
    }

    /**
     * Check if the given record status message parameter is valid.
     * A valid parameter should lie within the range description of Record Status Info defined in
     * CEC 1.4 Specification : Operand Descriptions (Section 17)
     */
    private class RecordStatusInfoValidator implements ParameterValidator {
        @Override
        public int isValid(byte[] params) {
            if (params.length < 1) {
                return ERROR_PARAMETER_SHORT;
            }
            return toErrorCode(isWithinRange(params[0], 0x01, 0x07)
                            || isWithinRange(params[0], 0x09, 0x0E)
                            || isWithinRange(params[0], 0x10, 0x17)
                            || isWithinRange(params[0], 0x1A, 0x1B)
                            || params[0] == 0x1F);
        }
    }

    /**
     * Check if the given parameters represents printable characters.
     * A valid parameter should lie within the range description of ASCII defined in CEC 1.4
     * Specification : Operand Descriptions (Section 17)
     */
    private class AsciiValidator implements ParameterValidator {
        private final int mMinLength;
        private final int mMaxLength;

        AsciiValidator(int length) {
            mMinLength = length;
            mMaxLength = length;
        }

        AsciiValidator(int minLength, int maxLength) {
            mMinLength = minLength;
            mMaxLength = maxLength;
        }

        @Override
        public int isValid(byte[] params) {
            // If the length is longer than expected, we assume it's OK since the parameter can be
            // extended in the future version.
            if (params.length < mMinLength) {
                return ERROR_PARAMETER_SHORT;
            }
            return toErrorCode(isValidAsciiString(params, 0, mMaxLength));
        }
    }

    /**
     * Check if the given parameters is valid OSD String.
     * A valid parameter should lie within the range description of ASCII defined in CEC 1.4
     * Specification : Operand Descriptions (Section 17)
     */
    private class OsdStringValidator implements ParameterValidator {
        @Override
        public int isValid(byte[] params) {
            // If the length is longer than expected, we assume it's OK since the parameter can be
            // extended in the future version.
            if (params.length < 2) {
                return ERROR_PARAMETER_SHORT;
            }
            return toErrorCode(
                    // Display Control
                    isValidDisplayControl(params[0])
                    // OSD String
                    && isValidAsciiString(params, 1, 14));
        }
    }

    /** Check if the given parameters are one byte parameters and within range. */
    private class OneByteRangeValidator implements ParameterValidator {
        private final int mMinValue, mMaxValue;

        OneByteRangeValidator(int minValue, int maxValue) {
            mMinValue = minValue;
            mMaxValue = maxValue;
        }

        @Override
        public int isValid(byte[] params) {
            if (params.length < 1) {
                return ERROR_PARAMETER_SHORT;
            }
            return toErrorCode(isWithinRange(params[0], mMinValue, mMaxValue));
        }
    }

    /**
     * Check if the given Analogue Timer message parameters are valid. Valid parameters should
     * adhere to message description of Analogue Timer defined in CEC 1.4 Specification : Message
     * Descriptions for Timer Programming Feature (CEC Table 12)
     */
    private class AnalogueTimerValidator implements ParameterValidator {
        @Override
        public int isValid(byte[] params) {
            if (params.length < 11) {
                return ERROR_PARAMETER_SHORT;
            }
            return toErrorCode(
                    isValidDayOfMonth(params[0]) // Day of Month
                            && isValidMonthOfYear(params[1]) // Month of Year
                            && isValidHour(params[2]) // Start Time - Hour
                            && isValidMinute(params[3]) // Start Time - Minute
                            && isValidDurationHours(params[4]) // Duration - Duration Hours
                            && isValidMinute(params[5]) // Duration - Minute
                            && isValidRecordingSequence(params[6]) // Recording Sequence
                            && isValidAnalogueBroadcastType(params[7]) // Analogue Broadcast Type
                            && isValidAnalogueFrequency(
                                    HdmiUtils.twoBytesToInt(params, 8)) // Analogue Frequency
                            && isValidBroadcastSystem(params[10])); // Broadcast System
        }
    }

    /**
     * Check if the given Digital Timer message parameters are valid. Valid parameters should adhere
     * to message description of Digital Timer defined in CEC 1.4 Specification : Message
     * Descriptions for Timer Programming Feature (CEC Table 12)
     */
    private class DigitalTimerValidator implements ParameterValidator {
        @Override
        public int isValid(byte[] params) {
            if (params.length < 11) {
                return ERROR_PARAMETER_SHORT;
            }
            return toErrorCode(
                    isValidDayOfMonth(params[0]) // Day of Month
                            && isValidMonthOfYear(params[1]) // Month of Year
                            && isValidHour(params[2]) // Start Time - Hour
                            && isValidMinute(params[3]) // Start Time - Minute
                            && isValidDurationHours(params[4]) // Duration - Duration Hours
                            && isValidMinute(params[5]) // Duration - Minute
                            && isValidRecordingSequence(params[6]) // Recording Sequence
                            && isValidDigitalServiceIdentification(
                                    params, 7)); // Digital Service Identification
        }
    }

    /**
     * Check if the given External Timer message parameters are valid. Valid parameters should
     * adhere to message description of External Timer defined in CEC 1.4 Specification : Message
     * Descriptions for Timer Programming Feature (CEC Table 12)
     */
    private class ExternalTimerValidator implements ParameterValidator {
        @Override
        public int isValid(byte[] params) {
            if (params.length < 9) {
                return ERROR_PARAMETER_SHORT;
            }
            return toErrorCode(
                    isValidDayOfMonth(params[0]) // Day of Month
                            && isValidMonthOfYear(params[1]) // Month of Year
                            && isValidHour(params[2]) // Start Time - Hour
                            && isValidMinute(params[3]) // Start Time - Minute
                            && isValidDurationHours(params[4]) // Duration - Duration Hours
                            && isValidMinute(params[5]) // Duration - Minute
                            && isValidRecordingSequence(params[6]) // Recording Sequence
                            && isValidExternalSource(params, 7)); // External Source
        }
    }

    /**
     * Check if the given timer cleared status parameter is valid. A valid parameter should lie
     * within the range description defined in CEC 1.4 Specification : Operand Descriptions
     * (Section 17)
     */
    private class TimerClearedStatusValidator implements ParameterValidator {
        @Override
        public int isValid(byte[] params) {
            if (params.length < 1) {
                return ERROR_PARAMETER_SHORT;
            }
            return toErrorCode(isWithinRange(params[0], 0x00, 0x02) || (params[0] & 0xFF) == 0x80);
        }
    }

    /**
     * Check if the given timer status data parameter is valid. A valid parameter should lie within
     * the range description defined in CEC 1.4 Specification : Operand Descriptions (Section 17)
     */
    private class TimerStatusValidator implements ParameterValidator {
        @Override
        public int isValid(byte[] params) {
            if (params.length < 1) {
                return ERROR_PARAMETER_SHORT;
            }
            return toErrorCode(isValidTimerStatusData(params, 0));
        }
    }
}
