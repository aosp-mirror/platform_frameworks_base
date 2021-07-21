/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.stats.hdmi.HdmiStatsEnums;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;

/**
 * Provides methods for writing HDMI-CEC statsd atoms.
 */
@VisibleForTesting
public class HdmiCecAtomWriter {

    private static final int FEATURE_ABORT_OPCODE_UNKNOWN = 0x100;
    private static final int ERROR_CODE_UNKNOWN = -1;

    /**
     * Writes a HdmiCecMessageReported atom representing an HDMI CEC message.
     * Should only be directly used for sent messages; for received messages,
     * use the overloaded version with the errorCode argument omitted.
     *
     * @param message      The HDMI CEC message
     * @param direction    Whether the message is incoming, outgoing, or neither
     * @param errorCode    The error code from the final attempt to send the message
     * @param callingUid   The calling uid of the app that triggered this message
     */
    public void messageReported(
            HdmiCecMessage message, int direction, int callingUid, int errorCode) {
        MessageReportedGenericArgs genericArgs = createMessageReportedGenericArgs(
                message, direction, errorCode, callingUid);
        MessageReportedSpecialArgs specialArgs = createMessageReportedSpecialArgs(message);
        messageReportedBase(genericArgs, specialArgs);
    }

    /**
     * Version of messageReported for received messages, where no error code is present.
     *
     * @param message      The HDMI CEC message
     * @param direction    Whether the message is incoming, outgoing, or neither
     * @param callingUid   The calling uid of the app that triggered this message
     */
    public void messageReported(HdmiCecMessage message, int direction, int callingUid) {
        messageReported(message, direction, callingUid, ERROR_CODE_UNKNOWN);
    }

    /**
     * Constructs the generic arguments for logging a HDMI CEC message.
     *
     * @param message      The HDMI CEC message
     * @param direction    Whether the message is incoming, outgoing, or neither
     * @param errorCode    The error code of the message if it's outgoing;
     *                     otherwise, ERROR_CODE_UNKNOWN
     */
    private MessageReportedGenericArgs createMessageReportedGenericArgs(
            HdmiCecMessage message, int direction, int errorCode, int callingUid) {
        int sendMessageResult = errorCode == ERROR_CODE_UNKNOWN
                ? HdmiStatsEnums.SEND_MESSAGE_RESULT_UNKNOWN
                : errorCode + 10;
        return new MessageReportedGenericArgs(callingUid, direction, message.getSource(),
                message.getDestination(), message.getOpcode(), sendMessageResult);
    }

    /**
     * Constructs the special arguments for logging an HDMI CEC message.
     *
     * @param message The HDMI CEC message to log
     * @return An object containing the special arguments for the message
     */
    private MessageReportedSpecialArgs createMessageReportedSpecialArgs(HdmiCecMessage message) {
        // Special arguments depend on message opcode
        switch (message.getOpcode()) {
            case Constants.MESSAGE_USER_CONTROL_PRESSED:
                return createUserControlPressedSpecialArgs(message);
            case Constants.MESSAGE_FEATURE_ABORT:
                return createFeatureAbortSpecialArgs(message);
            default:
                return new MessageReportedSpecialArgs();
        }
    }

    /**
     * Constructs the special arguments for a <User Control Pressed> message.
     *
     * @param message The HDMI CEC message to log
     */
    private MessageReportedSpecialArgs createUserControlPressedSpecialArgs(
            HdmiCecMessage message) {
        MessageReportedSpecialArgs specialArgs = new MessageReportedSpecialArgs();

        int keycode = message.getParams()[0];
        if (keycode >= 0x1E && keycode <= 0x29) {
            specialArgs.mUserControlPressedCommand = HdmiStatsEnums.NUMBER;
        } else {
            specialArgs.mUserControlPressedCommand = keycode + 0x100;
        }

        return specialArgs;
    }

    /**
     * Constructs method for constructing the special arguments for a <Feature Abort> message.
     *
     * @param message The HDMI CEC message to log
     */
    private MessageReportedSpecialArgs createFeatureAbortSpecialArgs(HdmiCecMessage message) {
        MessageReportedSpecialArgs specialArgs = new MessageReportedSpecialArgs();

        specialArgs.mFeatureAbortOpcode = message.getParams()[0] & 0xFF; // Unsigned byte
        specialArgs.mFeatureAbortReason = message.getParams()[1] + 10;

        return specialArgs;
    }

    /**
     * Writes a HdmiCecMessageReported atom.
     *
     * @param genericArgs Generic arguments; shared by all HdmiCecMessageReported atoms
     * @param specialArgs Special arguments; depends on the opcode of the message
     */
    private void messageReportedBase(MessageReportedGenericArgs genericArgs,
            MessageReportedSpecialArgs specialArgs) {
        FrameworkStatsLog.write(
                FrameworkStatsLog.HDMI_CEC_MESSAGE_REPORTED,
                genericArgs.mUid,
                genericArgs.mDirection,
                genericArgs.mInitiatorLogicalAddress,
                genericArgs.mDestinationLogicalAddress,
                genericArgs.mOpcode,
                genericArgs.mSendMessageResult,
                specialArgs.mUserControlPressedCommand,
                specialArgs.mFeatureAbortOpcode,
                specialArgs.mFeatureAbortReason);
    }


    /**
     * Writes a HdmiCecActiveSourceChanged atom representing a change in the active source.
     *
     * @param logicalAddress             The Logical Address of the new active source
     * @param physicalAddress            The Physical Address of the new active source
     * @param relationshipToActiveSource The relationship between this device and the active source
     */
    public void activeSourceChanged(int logicalAddress, int physicalAddress,
            @Constants.PathRelationship int relationshipToActiveSource) {
        FrameworkStatsLog.write(
                FrameworkStatsLog.HDMI_CEC_ACTIVE_SOURCE_CHANGED,
                logicalAddress,
                physicalAddress,
                relationshipToActiveSource
        );
    }

    /**
     * Contains the required arguments for creating any HdmiCecMessageReported atom
     */
    private class MessageReportedGenericArgs {
        final int mUid;
        final int mDirection;
        final int mInitiatorLogicalAddress;
        final int mDestinationLogicalAddress;
        final int mOpcode;
        final int mSendMessageResult;

        MessageReportedGenericArgs(int uid, int direction, int initiatorLogicalAddress,
                int destinationLogicalAddress, int opcode, int sendMessageResult) {
            this.mUid = uid;
            this.mDirection = direction;
            this.mInitiatorLogicalAddress = initiatorLogicalAddress;
            this.mDestinationLogicalAddress = destinationLogicalAddress;
            this.mOpcode = opcode;
            this.mSendMessageResult = sendMessageResult;
        }
    }

    /**
     * Contains the opcode-dependent arguments for creating a HdmiCecMessageReported atom. Each
     * field is initialized to a null-like value by default. Therefore, a freshly constructed
     * instance of this object represents a HDMI CEC message whose type does not require any
     * additional arguments.
     */
    private class MessageReportedSpecialArgs {
        int mUserControlPressedCommand = HdmiStatsEnums.USER_CONTROL_PRESSED_COMMAND_UNKNOWN;
        int mFeatureAbortOpcode = FEATURE_ABORT_OPCODE_UNKNOWN;
        int mFeatureAbortReason = HdmiStatsEnums.FEATURE_ABORT_REASON_UNKNOWN;
    }
}
