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

import com.android.server.hdmi.Constants.FeatureOpcode;

import libcore.util.EmptyArray;

import java.util.Arrays;
import java.util.Objects;

/**
 * A class to encapsulate HDMI-CEC message used for the devices connected via
 * HDMI cable to communicate with one another. A message is defined by its
 * source and destination address, command (or opcode), and optional parameters.
 */
public final class HdmiCecMessage {
    public static final byte[] EMPTY_PARAM = EmptyArray.BYTE;

    private final int mSource;
    private final int mDestination;

    private final int mOpcode;
    private final byte[] mParams;

    /**
     * Constructor.
     */
    public HdmiCecMessage(int source, int destination, int opcode, byte[] params) {
        mSource = source;
        mDestination = destination;
        mOpcode = opcode & 0xFF;
        mParams = Arrays.copyOf(params, params.length);
    }

    @Override
    public boolean equals(@Nullable Object message) {
        if (message instanceof HdmiCecMessage) {
            HdmiCecMessage that = (HdmiCecMessage) message;
            return this.mSource == that.getSource() &&
                this.mDestination == that.getDestination() &&
                this.mOpcode == that.getOpcode() &&
                Arrays.equals(this.mParams, that.getParams());
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            mSource,
            mDestination,
            mOpcode,
            Arrays.hashCode(mParams));
    }

    /**
     * Return the source address field of the message. It is the logical address
     * of the device which generated the message.
     *
     * @return source address
     */
    public int getSource() {
        return mSource;
    }

    /**
     * Return the destination address field of the message. It is the logical address
     * of the device to which the message is sent.
     *
     * @return destination address
     */
    public int getDestination() {
        return mDestination;
    }

    /**
     * Return the opcode field of the message. It is the type of the message that
     * tells the destination device what to do.
     *
     * @return opcode
     */
    public int getOpcode() {
        return mOpcode;
    }

    /**
     * Return the parameter field of the message. The contents of parameter varies
     * from opcode to opcode, and is used together with opcode to describe
     * the action for the destination device to take.
     *
     * @return parameter
     */
    public byte[] getParams() {
        return mParams;
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append(String.format("<%s> %X%X:%02X",
                opcodeToString(mOpcode), mSource, mDestination, mOpcode));
        if (mParams.length > 0) {
            if (filterMessageParameters(mOpcode)) {
                s.append(String.format(" <Redacted len=%d>", mParams.length));
            } else if (isUserControlPressedMessage(mOpcode)) {
                s.append(
                        String.format(
                                " <Keycode type = %s>", HdmiCecKeycode.getKeycodeType(mParams[0])));
            } else {
                for (byte data : mParams) {
                    s.append(String.format(":%02X", data));
                }
            }
        }
        return s.toString();
    }

    private static String opcodeToString(@FeatureOpcode int opcode) {
        switch (opcode) {
            case Constants.MESSAGE_FEATURE_ABORT:
                return "Feature Abort";
            case Constants.MESSAGE_IMAGE_VIEW_ON:
                return "Image View On";
            case Constants.MESSAGE_TUNER_STEP_INCREMENT:
                return "Tuner Step Increment";
            case Constants.MESSAGE_TUNER_STEP_DECREMENT:
                return "Tuner Step Decrement";
            case Constants.MESSAGE_TUNER_DEVICE_STATUS:
                return "Tuner Device Status";
            case Constants.MESSAGE_GIVE_TUNER_DEVICE_STATUS:
                return "Give Tuner Device Status";
            case Constants.MESSAGE_RECORD_ON:
                return "Record On";
            case Constants.MESSAGE_RECORD_STATUS:
                return "Record Status";
            case Constants.MESSAGE_RECORD_OFF:
                return "Record Off";
            case Constants.MESSAGE_TEXT_VIEW_ON:
                return "Text View On";
            case Constants.MESSAGE_RECORD_TV_SCREEN:
                return "Record Tv Screen";
            case Constants.MESSAGE_GIVE_DECK_STATUS:
                return "Give Deck Status";
            case Constants.MESSAGE_DECK_STATUS:
                return "Deck Status";
            case Constants.MESSAGE_SET_MENU_LANGUAGE:
                return "Set Menu Language";
            case Constants.MESSAGE_CLEAR_ANALOG_TIMER:
                return "Clear Analog Timer";
            case Constants.MESSAGE_SET_ANALOG_TIMER:
                return "Set Analog Timer";
            case Constants.MESSAGE_TIMER_STATUS:
                return "Timer Status";
            case Constants.MESSAGE_STANDBY:
                return "Standby";
            case Constants.MESSAGE_PLAY:
                return "Play";
            case Constants.MESSAGE_DECK_CONTROL:
                return "Deck Control";
            case Constants.MESSAGE_TIMER_CLEARED_STATUS:
                return "Timer Cleared Status";
            case Constants.MESSAGE_USER_CONTROL_PRESSED:
                return "User Control Pressed";
            case Constants.MESSAGE_USER_CONTROL_RELEASED:
                return "User Control Release";
            case Constants.MESSAGE_GIVE_OSD_NAME:
                return "Give Osd Name";
            case Constants.MESSAGE_SET_OSD_NAME:
                return "Set Osd Name";
            case Constants.MESSAGE_SET_OSD_STRING:
                return "Set Osd String";
            case Constants.MESSAGE_SET_TIMER_PROGRAM_TITLE:
                return "Set Timer Program Title";
            case Constants.MESSAGE_SYSTEM_AUDIO_MODE_REQUEST:
                return "System Audio Mode Request";
            case Constants.MESSAGE_GIVE_AUDIO_STATUS:
                return "Give Audio Status";
            case Constants.MESSAGE_SET_SYSTEM_AUDIO_MODE:
                return "Set System Audio Mode";
            case Constants.MESSAGE_REPORT_AUDIO_STATUS:
                return "Report Audio Status";
            case Constants.MESSAGE_GIVE_SYSTEM_AUDIO_MODE_STATUS:
                return "Give System Audio Mode Status";
            case Constants.MESSAGE_SYSTEM_AUDIO_MODE_STATUS:
                return "System Audio Mode Status";
            case Constants.MESSAGE_ROUTING_CHANGE:
                return "Routing Change";
            case Constants.MESSAGE_ROUTING_INFORMATION:
                return "Routing Information";
            case Constants.MESSAGE_ACTIVE_SOURCE:
                return "Active Source";
            case Constants.MESSAGE_GIVE_PHYSICAL_ADDRESS:
                return "Give Physical Address";
            case Constants.MESSAGE_REPORT_PHYSICAL_ADDRESS:
                return "Report Physical Address";
            case Constants.MESSAGE_REQUEST_ACTIVE_SOURCE:
                return "Request Active Source";
            case Constants.MESSAGE_SET_STREAM_PATH:
                return "Set Stream Path";
            case Constants.MESSAGE_DEVICE_VENDOR_ID:
                return "Device Vendor Id";
            case Constants.MESSAGE_VENDOR_COMMAND:
                return "Vendor Command";
            case Constants.MESSAGE_VENDOR_REMOTE_BUTTON_DOWN:
                return "Vendor Remote Button Down";
            case Constants.MESSAGE_VENDOR_REMOTE_BUTTON_UP:
                return "Vendor Remote Button Up";
            case Constants.MESSAGE_GIVE_DEVICE_VENDOR_ID:
                return "Give Device Vendor Id";
            case Constants.MESSAGE_MENU_REQUEST:
                return "Menu Request";
            case Constants.MESSAGE_MENU_STATUS:
                return "Menu Status";
            case Constants.MESSAGE_GIVE_DEVICE_POWER_STATUS:
                return "Give Device Power Status";
            case Constants.MESSAGE_REPORT_POWER_STATUS:
                return "Report Power Status";
            case Constants.MESSAGE_GET_MENU_LANGUAGE:
                return "Get Menu Language";
            case Constants.MESSAGE_SELECT_ANALOG_SERVICE:
                return "Select Analog Service";
            case Constants.MESSAGE_SELECT_DIGITAL_SERVICE:
                return "Select Digital Service";
            case Constants.MESSAGE_SET_DIGITAL_TIMER:
                return "Set Digital Timer";
            case Constants.MESSAGE_CLEAR_DIGITAL_TIMER:
                return "Clear Digital Timer";
            case Constants.MESSAGE_SET_AUDIO_RATE:
                return "Set Audio Rate";
            case Constants.MESSAGE_INACTIVE_SOURCE:
                return "InActive Source";
            case Constants.MESSAGE_CEC_VERSION:
                return "Cec Version";
            case Constants.MESSAGE_GET_CEC_VERSION:
                return "Get Cec Version";
            case Constants.MESSAGE_VENDOR_COMMAND_WITH_ID:
                return "Vendor Command With Id";
            case Constants.MESSAGE_CLEAR_EXTERNAL_TIMER:
                return "Clear External Timer";
            case Constants.MESSAGE_SET_EXTERNAL_TIMER:
                return "Set External Timer";
            case Constants.MESSAGE_REPORT_SHORT_AUDIO_DESCRIPTOR:
                return "Report Short Audio Descriptor";
            case Constants.MESSAGE_REQUEST_SHORT_AUDIO_DESCRIPTOR:
                return "Request Short Audio Descriptor";
            case Constants.MESSAGE_INITIATE_ARC:
                return "Initiate ARC";
            case Constants.MESSAGE_REPORT_ARC_INITIATED:
                return "Report ARC Initiated";
            case Constants.MESSAGE_REPORT_ARC_TERMINATED:
                return "Report ARC Terminated";
            case Constants.MESSAGE_REQUEST_ARC_INITIATION:
                return "Request ARC Initiation";
            case Constants.MESSAGE_REQUEST_ARC_TERMINATION:
                return "Request ARC Termination";
            case Constants.MESSAGE_GIVE_FEATURES:
                return "Give Features";
            case Constants.MESSAGE_REPORT_FEATURES:
                return "Report Features";
            case Constants.MESSAGE_REQUEST_CURRENT_LATENCY:
                return "Request Current Latency";
            case Constants.MESSAGE_REPORT_CURRENT_LATENCY:
                return "Report Current Latency";
            case Constants.MESSAGE_TERMINATE_ARC:
                return "Terminate ARC";
            case Constants.MESSAGE_CDC_MESSAGE:
                return "Cdc Message";
            case Constants.MESSAGE_ABORT:
                return "Abort";
            default:
                return String.format("Opcode: %02X", opcode);
        }
    }

    private static boolean filterMessageParameters(int opcode) {
        switch (opcode) {
            case Constants.MESSAGE_USER_CONTROL_RELEASED:
            case Constants.MESSAGE_SET_OSD_NAME:
            case Constants.MESSAGE_SET_OSD_STRING:
            case Constants.MESSAGE_VENDOR_COMMAND:
            case Constants.MESSAGE_VENDOR_REMOTE_BUTTON_DOWN:
            case Constants.MESSAGE_VENDOR_REMOTE_BUTTON_UP:
            case Constants.MESSAGE_VENDOR_COMMAND_WITH_ID:
                return true;
            default:
                return false;
        }
    }

    private static boolean isUserControlPressedMessage(int opcode) {
        return Constants.MESSAGE_USER_CONTROL_PRESSED == opcode;
    }

    static boolean isCecTransportMessage(int opcode) {
        switch (opcode) {
            case Constants.MESSAGE_REQUEST_CURRENT_LATENCY:
            case Constants.MESSAGE_REPORT_CURRENT_LATENCY:
            case Constants.MESSAGE_CDC_MESSAGE:
                return true;
            default:
                return false;
        }
    }
}

