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

import android.annotation.IntDef;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.util.SparseArray;

/**
 * A helper class to validate {@link HdmiCecMessage}.
 *
 * If a message type has its own specific subclass of {@link HdmiCecMessage},
 * validation is performed in that subclass instead.
 */
public class HdmiCecMessageValidator {
    private static final String TAG = "HdmiCecMessageValidator";

    @IntDef({
            OK,
            ERROR_SOURCE,
            ERROR_DESTINATION,
            ERROR_PARAMETER,
            ERROR_PARAMETER_SHORT,
            ERROR_PARAMETER_LONG,
    })
    public @interface ValidationResult {};

    static final int OK = 0;
    static final int ERROR_SOURCE = 1;
    static final int ERROR_DESTINATION = 2;
    static final int ERROR_PARAMETER = 3;
    static final int ERROR_PARAMETER_SHORT = 4;
    static final int ERROR_PARAMETER_LONG = 5;

    interface ParameterValidator {
        /**
         * @return errorCode errorCode can be {@link #OK}, {@link #ERROR_PARAMETER} or
         *         {@link #ERROR_PARAMETER_SHORT}.
         */
        int isValid(byte[] params);
    }

    /**
     * Bitmasks used for source and destination validations.
     */
    static final int ADDR_TV = 1 << 0;
    static final int ADDR_RECORDER_1 = 1 << 1;
    static final int ADDR_RECORDER_2 = 1 << 2;
    static final int ADDR_TUNER_1 = 1 << 3;
    static final int ADDR_PLAYBACK_1 = 1 << 4;
    static final int ADDR_AUDIO_SYSTEM = 1 << 5;
    static final int ADDR_TUNER_2 = 1 << 6;
    static final int ADDR_TUNER_3 = 1 << 7;
    static final int ADDR_PLAYBACK_2 = 1 << 8;
    static final int ADDR_RECORDER_3 = 1 << 9;
    static final int ADDR_TUNER_4 = 1 << 10;
    static final int ADDR_PLAYBACK_3 = 1 << 11;
    static final int ADDR_BACKUP_1 = 1 << 12;
    static final int ADDR_BACKUP_2 = 1 << 13;
    static final int ADDR_SPECIFIC_USE = 1 << 14;
    static final int ADDR_UNREGISTERED = 1 << 15;
    static final int ADDR_BROADCAST = 1 << 15;
    static final int ADDR_ALL = (1 << 16) - 1;
    static final int ADDR_DIRECT = ADDR_ALL ^ ADDR_BROADCAST;
    static final int ADDR_NOT_UNREGISTERED = ADDR_ALL ^ ADDR_UNREGISTERED;

    private static class ValidationInfo {
        public final ParameterValidator parameterValidator;
        public final int validSources;
        public final int validDestinations;

        ValidationInfo(ParameterValidator parameterValidator, int validSources,
                int validDestinations) {
            this.parameterValidator = parameterValidator;
            this.validSources = validSources;
            this.validDestinations = validDestinations;
        }
    }

    private HdmiCecMessageValidator() {}

    private static final SparseArray<ValidationInfo> sValidationInfo = new SparseArray<>();

    static {
        // Messages related to the physical address.
        PhysicalAddressValidator physicalAddressValidator = new PhysicalAddressValidator();
        addValidationInfo(Constants.MESSAGE_ACTIVE_SOURCE,
                physicalAddressValidator, ADDR_ALL ^ (ADDR_RECORDER_1 | ADDR_RECORDER_2
                        | ADDR_AUDIO_SYSTEM | ADDR_RECORDER_3), ADDR_BROADCAST);
        addValidationInfo(Constants.MESSAGE_INACTIVE_SOURCE,
                physicalAddressValidator, ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_REPORT_PHYSICAL_ADDRESS,
                new ReportPhysicalAddressValidator(), ADDR_ALL, ADDR_BROADCAST);
        addValidationInfo(Constants.MESSAGE_ROUTING_CHANGE,
                new RoutingChangeValidator(), ADDR_ALL, ADDR_BROADCAST);
        addValidationInfo(Constants.MESSAGE_ROUTING_INFORMATION,
                physicalAddressValidator, ADDR_ALL, ADDR_BROADCAST);
        addValidationInfo(Constants.MESSAGE_SET_STREAM_PATH,
                physicalAddressValidator, ADDR_NOT_UNREGISTERED, ADDR_BROADCAST);
        addValidationInfo(Constants.MESSAGE_SYSTEM_AUDIO_MODE_REQUEST,
                new SystemAudioModeRequestValidator(), ADDR_NOT_UNREGISTERED, ADDR_DIRECT);

        // Messages have no parameter.
        FixedLengthValidator noneValidator = new FixedLengthValidator(0);
        addValidationInfo(Constants.MESSAGE_ABORT,
                noneValidator, ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_GET_CEC_VERSION,
                noneValidator, ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_GET_MENU_LANGUAGE,
                noneValidator, ADDR_ALL, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_GIVE_AUDIO_STATUS,
                noneValidator, ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_GIVE_DEVICE_POWER_STATUS,
                noneValidator, ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_GIVE_DEVICE_VENDOR_ID,
                noneValidator, ADDR_ALL, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_GIVE_OSD_NAME,
                noneValidator, ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_GIVE_PHYSICAL_ADDRESS,
                noneValidator, ADDR_ALL, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_GIVE_SYSTEM_AUDIO_MODE_STATUS,
                noneValidator, ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_IMAGE_VIEW_ON,
                noneValidator, ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_INITIATE_ARC,
                noneValidator, ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_RECORD_OFF,
                noneValidator, ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_RECORD_TV_SCREEN,
                noneValidator, ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_REPORT_ARC_INITIATED,
                noneValidator, ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_REPORT_ARC_TERMINATED,
                noneValidator, ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_REQUEST_ARC_INITIATION,
                noneValidator, ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_REQUEST_ARC_TERMINATION,
                noneValidator, ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_REQUEST_ACTIVE_SOURCE,
                noneValidator, ADDR_ALL, ADDR_BROADCAST);
        addValidationInfo(Constants.MESSAGE_STANDBY,
                noneValidator, ADDR_ALL, ADDR_ALL);
        addValidationInfo(Constants.MESSAGE_TERMINATE_ARC,
                noneValidator, ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_TEXT_VIEW_ON,
                noneValidator, ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_TUNER_STEP_DECREMENT,
                noneValidator, ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_TUNER_STEP_INCREMENT,
                noneValidator, ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_USER_CONTROL_RELEASED,
                noneValidator, ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_VENDOR_REMOTE_BUTTON_UP,
                noneValidator, ADDR_NOT_UNREGISTERED, ADDR_ALL);

        // TODO: Validate more than length for the following messages.

        // Messages for the One Touch Record.
        addValidationInfo(Constants.MESSAGE_RECORD_ON,
                new VariableLengthValidator(1, 8), ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_RECORD_STATUS,
                new RecordStatusInfoValidator(), ADDR_NOT_UNREGISTERED, ADDR_DIRECT);

        addValidationInfo(Constants.MESSAGE_CLEAR_ANALOG_TIMER,
                new AnalogueTimerValidator(), ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_CLEAR_DIGITAL_TIMER,
                new DigitalTimerValidator(), ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_CLEAR_EXTERNAL_TIMER,
                new ExternalTimerValidator(), ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_SET_ANALOG_TIMER,
                new AnalogueTimerValidator(), ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_SET_DIGITAL_TIMER,
                new DigitalTimerValidator(), ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_SET_EXTERNAL_TIMER,
                new ExternalTimerValidator(), ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_SET_TIMER_PROGRAM_TITLE,
                new AsciiValidator(1, 14), ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_TIMER_CLEARED_STATUS,
                new TimerClearedStatusValidator(), ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_TIMER_STATUS,
                new TimerStatusValidator(), ADDR_NOT_UNREGISTERED, ADDR_DIRECT);

        // Messages for the System Information.
        FixedLengthValidator oneByteValidator = new FixedLengthValidator(1);
        addValidationInfo(Constants.MESSAGE_CEC_VERSION,
                oneByteValidator, ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_SET_MENU_LANGUAGE,
                new AsciiValidator(3), ADDR_NOT_UNREGISTERED, ADDR_BROADCAST);

        ParameterValidator statusRequestValidator = new MinimumOneByteRangeValidator(0x01, 0x03);
        addValidationInfo(Constants.MESSAGE_DECK_CONTROL,
                new MinimumOneByteRangeValidator(0x01, 0x04), ADDR_NOT_UNREGISTERED,
                ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_DECK_STATUS,
                new MinimumOneByteRangeValidator(0x11, 0x1F), ADDR_NOT_UNREGISTERED,
                ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_GIVE_DECK_STATUS,
                statusRequestValidator, ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_PLAY,
                new PlayModeValidator(), ADDR_NOT_UNREGISTERED, ADDR_DIRECT);

        addValidationInfo(Constants.MESSAGE_GIVE_TUNER_DEVICE_STATUS,
                statusRequestValidator, ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_SELECT_ANALOG_SERVICE,
                new SelectAnalogueServiceValidator(), ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_SELECT_DIGITAL_SERVICE,
                new SelectDigitalServiceValidator(), ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_TUNER_DEVICE_STATUS,
                new TunerDeviceStatusValidator(), ADDR_NOT_UNREGISTERED, ADDR_DIRECT);

        // Messages for the Vendor Specific Commands.
        VariableLengthValidator maxLengthValidator = new VariableLengthValidator(0, 14);
        addValidationInfo(Constants.MESSAGE_DEVICE_VENDOR_ID,
                new FixedLengthValidator(3), ADDR_NOT_UNREGISTERED, ADDR_BROADCAST);
        // Allow unregistered source for all vendor specific commands, because we don't know
        // how to use the commands at this moment.
        addValidationInfo(Constants.MESSAGE_VENDOR_COMMAND,
                new VariableLengthValidator(1, 14), ADDR_ALL, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_VENDOR_COMMAND_WITH_ID,
                new VariableLengthValidator(4, 14), ADDR_ALL, ADDR_ALL);
        addValidationInfo(Constants.MESSAGE_VENDOR_REMOTE_BUTTON_DOWN,
                maxLengthValidator, ADDR_ALL, ADDR_ALL);

        // Messages for the OSD.
        addValidationInfo(Constants.MESSAGE_SET_OSD_STRING,
                new OsdStringValidator(), ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_SET_OSD_NAME,
                new AsciiValidator(1, 14), ADDR_NOT_UNREGISTERED, ADDR_DIRECT);

        // Messages for the Device Menu Control.
        addValidationInfo(Constants.MESSAGE_MENU_REQUEST,
                new MinimumOneByteRangeValidator(0x00, 0x02), ADDR_NOT_UNREGISTERED,
                ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_MENU_STATUS,
                new MinimumOneByteRangeValidator(0x00, 0x01), ADDR_NOT_UNREGISTERED,
                ADDR_DIRECT);

        // Messages for the Remote Control Passthrough.
        addValidationInfo(Constants.MESSAGE_USER_CONTROL_PRESSED,
                new UserControlPressedValidator(), ADDR_NOT_UNREGISTERED, ADDR_DIRECT);

        // Messages for the Power Status.
        addValidationInfo(Constants.MESSAGE_REPORT_POWER_STATUS,
                new MinimumOneByteRangeValidator(0x00, 0x03),
                ADDR_NOT_UNREGISTERED, ADDR_ALL);

        // Messages for the General Protocol.
        addValidationInfo(Constants.MESSAGE_FEATURE_ABORT,
                new FixedLengthValidator(2), ADDR_NOT_UNREGISTERED, ADDR_DIRECT);

        // Messages for the System Audio Control.
        addValidationInfo(Constants.MESSAGE_REPORT_AUDIO_STATUS,
                oneByteValidator, ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_REPORT_SHORT_AUDIO_DESCRIPTOR,
                new FixedLengthValidator(3), ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_REQUEST_SHORT_AUDIO_DESCRIPTOR,
                oneByteValidator, ADDR_NOT_UNREGISTERED, ADDR_DIRECT);
        addValidationInfo(Constants.MESSAGE_SET_SYSTEM_AUDIO_MODE,
                new MinimumOneByteRangeValidator(0x00, 0x01),
                ADDR_NOT_UNREGISTERED, ADDR_ALL);
        addValidationInfo(Constants.MESSAGE_SYSTEM_AUDIO_MODE_STATUS,
                new SingleByteRangeValidator(0x00, 0x01), ADDR_NOT_UNREGISTERED,
                ADDR_DIRECT);

        // Messages for the Audio Rate Control.
        addValidationInfo(Constants.MESSAGE_SET_AUDIO_RATE,
                new MinimumOneByteRangeValidator(0x00, 0x06), ADDR_NOT_UNREGISTERED,
                ADDR_DIRECT);

        // Messages for Feature Discovery.
        addValidationInfo(Constants.MESSAGE_GIVE_FEATURES,
                noneValidator, ADDR_ALL, ADDR_DIRECT);

        // Messages for Dynamic Auto Lipsync
        addValidationInfo(Constants.MESSAGE_REQUEST_CURRENT_LATENCY,
                physicalAddressValidator, ADDR_NOT_UNREGISTERED, ADDR_BROADCAST);
        addValidationInfo(Constants.MESSAGE_REPORT_CURRENT_LATENCY,
                new VariableLengthValidator(4, 14), ADDR_NOT_UNREGISTERED, ADDR_BROADCAST);

        // All Messages for the ARC have no parameters.

        // Messages for the Capability Discovery and Control.
        addValidationInfo(Constants.MESSAGE_CDC_MESSAGE,
                maxLengthValidator, ADDR_ALL, ADDR_BROADCAST);
    }

    /**
     * validSources and validDestinations are bitmasks that represent the sources and destinations
     * that are allowed for a message.
     */
    private static void addValidationInfo(int opcode, ParameterValidator validator,
            int validSources, int validDestinations) {
        sValidationInfo.append(opcode, new ValidationInfo(validator, validSources,
                validDestinations));
    }

    /**
     * Validates all parameters of a HDMI-CEC message using static information stored in this class.
     */
    @ValidationResult
    static int validate(int source, int destination, int opcode, byte[] params) {
        ValidationInfo info = sValidationInfo.get(opcode);

        if (info == null) {
            HdmiLogger.warning("No validation information for the opcode: " + opcode);
            return OK;
        }

        int addressValidationResult = validateAddress(source, destination, info.validSources,
                info.validDestinations);
        if (addressValidationResult != OK) {
            return addressValidationResult;
        }

        // Validate parameters
        int errorCode = info.parameterValidator.isValid(params);
        if (errorCode != OK) {
            return errorCode;
        }

        return OK;
    }

    /**
     * Validates the source and destination addresses of a HDMI-CEC message according to input
     * address type. Allows address validation logic to be expressed concisely without depending
     * on static information in this class.
     * @param source Source address to validate
     * @param destination Destination address to validate
     * @param validSources Bitmask used to validate the source address
     *                     - e.g. {@link #ADDR_SOURCE_DEVICES}
     * @param validDestinations Bitmask used to validate the destination address
     *                        - e.g. {@link #ADDR_DIRECT}
     */
    @ValidationResult
    static int validateAddress(int source, int destination, int validSources,
            int validDestinations) {
        // Check the source field.
        if ((validSources & (1 << source)) == 0) {
            return ERROR_SOURCE;
        }
        // Check the destination field.
        if ((validDestinations & (1 << destination)) == 0) {
            return ERROR_DESTINATION;
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

    private static boolean isValidPhysicalAddress(byte[] params, int offset) {
        int physicalAddress = HdmiUtils.twoBytesToInt(params, offset);
        while (physicalAddress != 0) {
            int maskedAddress = physicalAddress & 0xF000;
            physicalAddress = (physicalAddress << 4) & 0xFFFF;
            if (maskedAddress == 0 && physicalAddress != 0) {
                return false;
            }
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

    private static boolean isWithinRange(int value, int min, int max) {
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
    private static boolean isValidDisplayControl(int value) {
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
    private static boolean isValidAsciiString(byte[] params, int offset, int maxLength) {
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
    private static boolean isValidDayOfMonth(int value) {
        return isWithinRange(value, 1, 31);
    }

    /**
     * Check if the given value is a valid month of year. A valid value is one which falls within
     * the range description defined in CEC 1.4 Specification : Operand Descriptions (Section 17)
     *
     * @param value month of year
     * @return true if the month of year is valid
     */
    private static boolean isValidMonthOfYear(int value) {
        return isWithinRange(value, 1, 12);
    }

    /**
     * Check if the given value is a valid hour. A valid value is one which falls within the range
     * description defined in CEC 1.4 Specification : Operand Descriptions (Section 17)
     *
     * @param value hour
     * @return true if the hour is valid
     */
    private static boolean isValidHour(int value) {
        return isWithinRange(value, 0, 23);
    }

    /**
     * Check if the given value is a valid minute. A valid value is one which falls within the range
     * description defined in CEC 1.4 Specification : Operand Descriptions (Section 17)
     *
     * @param value minute
     * @return true if the minute is valid
     */
    private static boolean isValidMinute(int value) {
        return isWithinRange(value, 0, 59);
    }

    /**
     * Check if the given value is a valid duration hours. A valid value is one which falls within
     * the range description defined in CEC 1.4 Specification : Operand Descriptions (Section 17)
     *
     * @param value duration hours
     * @return true if the duration hours is valid
     */
    private static boolean isValidDurationHours(int value) {
        return isWithinRange(value, 0, 99);
    }

    /**
     * Check if the given value is a valid recording sequence. A valid value is adheres to range
     * description defined in CEC 1.4 Specification : Operand Descriptions (Section 17)
     *
     * @param value recording sequence
     * @return true if the given recording sequence is valid
     */
    private static boolean isValidRecordingSequence(int value) {
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
    private static boolean isValidAnalogueBroadcastType(int value) {
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
    private static boolean isValidAnalogueFrequency(int value) {
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
    private static boolean isValidBroadcastSystem(int value) {
        return isWithinRange(value, 0, 31);
    }

    /**
     * Check if the given value is a ARIB type. A valid value is one which falls within the range
     * description defined in CEC 1.4 Specification : Operand Descriptions (Section 17)
     *
     * @param value Digital Broadcast System
     * @return true if the Digital Broadcast System is ARIB type
     */
    private static boolean isAribDbs(int value) {
        return (value == 0x00 || isWithinRange(value, 0x08, 0x0A));
    }

    /**
     * Check if the given value is a ATSC type. A valid value is one which falls within the range
     * description defined in CEC 1.4 Specification : Operand Descriptions (Section 17)
     *
     * @param value Digital Broadcast System
     * @return true if the Digital Broadcast System is ATSC type
     */
    private static boolean isAtscDbs(int value) {
        return (value == 0x01 || isWithinRange(value, 0x10, 0x12));
    }

    /**
     * Check if the given value is a DVB type. A valid value is one which falls within the range
     * description defined in CEC 1.4 Specification : Operand Descriptions (Section 17)
     *
     * @param value Digital Broadcast System
     * @return true if the Digital Broadcast System is DVB type
     */
    private static boolean isDvbDbs(int value) {
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
    private static boolean isValidDigitalBroadcastSystem(int value) {
        return (isAribDbs(value) || isAtscDbs(value) || isDvbDbs(value));
    }

    /**
     * Check if the given value is a valid Channel Identifier. A valid value is one which falls
     * within the range description defined in CEC 1.4 Specification : Operand Descriptions (Section
     * 17)
     *
     * @param params Channel Identifier parameters
     * @param offset start offset of Channel Identifier
     * @return true if the Channel Identifier is valid
     */
    private static boolean isValidChannelIdentifier(byte[] params, int offset) {
        // First 6 bits contain Channel Number Format
        int channelNumberFormat = params[offset] & 0xFC;
        if (channelNumberFormat == 0x04) {
            // Validate it contains 1-part Channel Number data (16 bits)
            return params.length - offset >= 3;
        } else if (channelNumberFormat == 0x08) {
            // Validate it contains Major Channel Number and Minor Channel Number (26 bits)
            return params.length - offset >= 4;
        }
        return false;
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
    private static boolean isValidDigitalServiceIdentification(byte[] params, int offset) {
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
                return isValidChannelIdentifier(params, offset);
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
    private static boolean isValidExternalPlug(int value) {
        return isWithinRange(value, 1, 255);
    }

    /**
     * Check if the given value is a valid External Source. A valid value is one which falls within
     * the range description defined in CEC 1.4 Specification : Operand Descriptions (Section 17)
     *
     * @param value External Source Specifier
     * @return true if the External Source is valid
     */
    private static boolean isValidExternalSource(byte[] params, int offset) {
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

    private static boolean isValidProgrammedInfo(int programedInfo) {
        return (isWithinRange(programedInfo, 0x00, 0x0B));
    }

    private static boolean isValidNotProgrammedErrorInfo(int nonProgramedErrorInfo) {
        return (isWithinRange(nonProgramedErrorInfo, 0x00, 0x0E));
    }

    private static boolean isValidTimerStatusData(byte[] params, int offset) {
        int programedIndicator = params[offset] & 0x10;
        boolean durationAvailable = false;
        if (programedIndicator == 0x10) {
            // Programmed
            int programedInfo = params[offset] & 0x0F;
            if (isValidProgrammedInfo(programedInfo)) {
                offset = offset + 1;
                // Duration Available (2 bytes)
                if ((programedInfo == 0x09 || programedInfo == 0x0B)
                        && params.length - offset >= 2) {
                    durationAvailable = true;
                } else {
                    return true;
                }
            }
        } else {
            // Non programmed
            int nonProgramedErrorInfo = params[offset] & 0x0F;
            if (isValidNotProgrammedErrorInfo(nonProgramedErrorInfo)) {
                offset = offset + 1;
                // Duration Available (2 bytes)
                if (nonProgramedErrorInfo == 0x0E && params.length - offset >= 2) {
                    durationAvailable = true;
                } else {
                    return true;
                }
            }
        }
        // Duration Available (2 bytes)
        if (durationAvailable) {
            return (isValidDurationHours(params[offset]) && isValidMinute(params[offset + 1]));
        }
        return false;
    }

    /**
     * Check if the given value is a valid Play mode. A valid value is one which falls within the
     * range description defined in CEC 1.4 Specification : Operand Descriptions (Section 17)
     *
     * @param value Play mode
     * @return true if the Play mode is valid
     */
    private static boolean isValidPlayMode(int value) {
        return (isWithinRange(value, 0x05, 0x07)
                || isWithinRange(value, 0x09, 0x0B)
                || isWithinRange(value, 0x15, 0x17)
                || isWithinRange(value, 0x19, 0x1B)
                || isWithinRange(value, 0x24, 0x25)
                || (value == 0x20));
    }

    /**
     * Check if the given value is a valid UI Broadcast type. A valid value is one which falls
     * within the range description defined in CEC 1.4 Specification : Operand Descriptions (Section
     * 17)
     *
     * @param value UI Broadcast type
     * @return true if the UI Broadcast type is valid
     */
    private static boolean isValidUiBroadcastType(int value) {
        return ((value == 0x00)
                || (value == 0x01)
                || (value == 0x10)
                || (value == 0x20)
                || (value == 0x30)
                || (value == 0x40)
                || (value == 0x50)
                || (value == 0x60)
                || (value == 0x70)
                || (value == 0x80)
                || (value == 0x90)
                || (value == 0x91)
                || (value == 0xA0));
    }

    /**
     * Check if the given value is a valid UI Sound Presenation Control. A valid value is one which
     * falls within the range description defined in CEC 1.4 Specification : Operand Descriptions
     * (Section 17)
     *
     * @param value UI Sound Presenation Control
     * @return true if the UI Sound Presenation Control is valid
     */
    private static boolean isValidUiSoundPresenationControl(int value) {
        value = value & 0xFF;
        return ((value == 0x20)
                || (value == 0x30)
                || (value == 0x80)
                || (value == 0x90)
                || (value == 0xA0)
                || (isWithinRange(value, 0xB1, 0xB3))
                || (isWithinRange(value, 0xC1, 0xC3)));
    }

    /*
     * Check if the given value is a valid Tuner Device info. A valid value is one which falls
     * within the range description defined in CEC 1.4 Specification : Operand Descriptions
     * (Section 17)
     *
     * @param params Tuner device info
     * @return true if the Tuner device info is valid
     */
    private static boolean isValidTunerDeviceInfo(byte[] params) {
        int tunerDisplayInfo = params[0] & 0x7F;
        if (tunerDisplayInfo == 0x00) {
            // Displaying digital tuner
            if (params.length >= 5) {
                return isValidDigitalServiceIdentification(params, 1);
            }
        } else if (tunerDisplayInfo == 0x01) {
            // Not displaying Tuner
            return true;
        } else if (tunerDisplayInfo == 0x02) {
            // Displaying Analogue tuner
            if (params.length >= 5) {
                return (isValidAnalogueBroadcastType(params[1])
                        && isValidAnalogueFrequency(HdmiUtils.twoBytesToInt(params, 2))
                        && isValidBroadcastSystem(params[4]));
            }
        }
        return false;
    }

    private static class PhysicalAddressValidator implements ParameterValidator {
        @Override
        public int isValid(byte[] params) {
            if (params.length < 2) {
                return ERROR_PARAMETER_SHORT;
            }
            return toErrorCode(isValidPhysicalAddress(params, 0));
        }
    }

    private static class SystemAudioModeRequestValidator extends PhysicalAddressValidator {
        @Override
        public int isValid(byte[] params) {
            // TV can send <System Audio Mode Request> with no parameters to terminate system audio.
            if (params.length == 0) {
                return OK;
            }
            return super.isValid(params);
        }
    }

    private static class ReportPhysicalAddressValidator implements ParameterValidator {
        @Override
        public int isValid(byte[] params) {
            if (params.length < 3) {
                return ERROR_PARAMETER_SHORT;
            }
            return toErrorCode(isValidPhysicalAddress(params, 0) && isValidType(params[2]));
        }
    }

    private static class RoutingChangeValidator implements ParameterValidator {
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
    private static class RecordStatusInfoValidator implements ParameterValidator {
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
    private static class AsciiValidator implements ParameterValidator {
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
    private static class OsdStringValidator implements ParameterValidator {
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

    /**
     * Check if the given parameters are at least one byte parameters
     * and the first byte is within range.
     */
    private static class MinimumOneByteRangeValidator implements ParameterValidator {
        private final int mMinValue, mMaxValue;

        MinimumOneByteRangeValidator(int minValue, int maxValue) {
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

    /** Check if the given parameters are exactly one byte parameters and within range. */
    private static class SingleByteRangeValidator implements ParameterValidator {
        private final int mMinValue, mMaxValue;

        SingleByteRangeValidator(int minValue, int maxValue) {
            mMinValue = minValue;
            mMaxValue = maxValue;
        }

        @Override
        public int isValid(byte[] params) {
            if (params.length < 1) {
                return ERROR_PARAMETER_SHORT;
            } else if (params.length > 1) {
                return ERROR_PARAMETER_LONG;
            }
            return toErrorCode(isWithinRange(params[0], mMinValue, mMaxValue));
        }
    }

    /**
     * Check if the given Analogue Timer message parameters are valid. Valid parameters should
     * adhere to message description of Analogue Timer defined in CEC 1.4 Specification : Message
     * Descriptions for Timer Programming Feature (CEC Table 12)
     */
    private static class AnalogueTimerValidator implements ParameterValidator {
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
    private static class DigitalTimerValidator implements ParameterValidator {
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
    private static class ExternalTimerValidator implements ParameterValidator {
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
    private static class TimerClearedStatusValidator implements ParameterValidator {
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
    private static class TimerStatusValidator implements ParameterValidator {
        @Override
        public int isValid(byte[] params) {
            if (params.length < 1) {
                return ERROR_PARAMETER_SHORT;
            }
            return toErrorCode(isValidTimerStatusData(params, 0));
        }
    }

    /**
     * Check if the given play mode parameter is valid. A valid parameter should lie within the
     * range description defined in CEC 1.4 Specification : Operand Descriptions (Section 17)
     */
    private static class PlayModeValidator implements ParameterValidator {
        @Override
        public int isValid(byte[] params) {
            if (params.length < 1) {
                return ERROR_PARAMETER_SHORT;
            }
            return toErrorCode(isValidPlayMode(params[0]));
        }
    }

    /**
     * Check if the given select analogue service parameter is valid. A valid parameter should lie
     * within the range description defined in CEC 1.4 Specification : Operand Descriptions
     * (Section 17)
     */
    private static class SelectAnalogueServiceValidator implements ParameterValidator {
        @Override
        public int isValid(byte[] params) {
            if (params.length < 4) {
                return ERROR_PARAMETER_SHORT;
            }
            return toErrorCode(isValidAnalogueBroadcastType(params[0])
                    && isValidAnalogueFrequency(HdmiUtils.twoBytesToInt(params, 1))
                    && isValidBroadcastSystem(params[3]));
        }
    }

    /**
     * Check if the given select digital service parameter is valid. A valid parameter should lie
     * within the range description defined in CEC 1.4 Specification : Operand Descriptions
     * (Section 17)
     */
    private static class SelectDigitalServiceValidator implements ParameterValidator {
        @Override
        public int isValid(byte[] params) {
            if (params.length < 4) {
                return ERROR_PARAMETER_SHORT;
            }
            return toErrorCode(isValidDigitalServiceIdentification(params, 0));
        }
    }

    /**
     * Check if the given tuner device status parameter is valid. A valid parameter should lie
     * within the range description defined in CEC 1.4 Specification : Operand Descriptions (Section
     * 17)
     */
    private static class TunerDeviceStatusValidator implements ParameterValidator {
        @Override
        public int isValid(byte[] params) {
            if (params.length < 1) {
                return ERROR_PARAMETER_SHORT;
            }
            return toErrorCode(isValidTunerDeviceInfo(params));
        }
    }

    /** Check if the given user control press parameter is valid. */
    private static class UserControlPressedValidator implements ParameterValidator {
        @Override
        public int isValid(byte[] params) {
            if (params.length < 1) {
                return ERROR_PARAMETER_SHORT;
            }
            if (params.length == 1) {
                return OK;
            }
            int uiCommand = params[0];
            switch (uiCommand) {
                case HdmiCecKeycode.CEC_KEYCODE_PLAY_FUNCTION:
                    return toErrorCode(isValidPlayMode(params[1]));
                case HdmiCecKeycode.CEC_KEYCODE_TUNE_FUNCTION:
                    return (params.length >= 4
                            ? toErrorCode(isValidChannelIdentifier(params, 1))
                            : ERROR_PARAMETER_SHORT);
                case HdmiCecKeycode.CEC_KEYCODE_SELECT_BROADCAST_TYPE:
                    return toErrorCode(isValidUiBroadcastType(params[1]));
                case HdmiCecKeycode.CEC_KEYCODE_SELECT_SOUND_PRESENTATION:
                    return toErrorCode(isValidUiSoundPresenationControl(params[1]));
                default:
                    return OK;
            }
        }
    }
}
