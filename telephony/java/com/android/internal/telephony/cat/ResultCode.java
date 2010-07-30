/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony.gsm.stk;


/**
 * Enumeration for the return code in TERMINAL RESPONSE.
 * To get the actual return code for each enum value, call {@link #code() code}
 * method.
 *
 * {@hide}
 */
public enum ResultCode {

    /*
     * Results '0X' and '1X' indicate that the command has been performed.
     */

    /** Command performed successfully */
    OK(0x00),

    /** Command performed with partial comprehension */
    PRFRMD_WITH_PARTIAL_COMPREHENSION(0x01),

    /** Command performed, with missing information */
    PRFRMD_WITH_MISSING_INFO(0x02),

    /** REFRESH performed with additional EFs read */
    PRFRMD_WITH_ADDITIONAL_EFS_READ(0x03),

    /**
     * Command performed successfully, but requested icon could not be
     * displayed
     */
    PRFRMD_ICON_NOT_DISPLAYED(0x04),

    /** Command performed, but modified by call control by NAA */
    PRFRMD_MODIFIED_BY_NAA(0x05),

    /** Command performed successfully, limited service */
    PRFRMD_LIMITED_SERVICE(0x06),

    /** Command performed with modification */
    PRFRMD_WITH_MODIFICATION(0x07),

    /** REFRESH performed but indicated NAA was not active */
    PRFRMD_NAA_NOT_ACTIVE(0x08),

    /** Command performed successfully, tone not played */
    PRFRMD_TONE_NOT_PLAYED(0x09),

    /** Proactive UICC session terminated by the user */
    UICC_SESSION_TERM_BY_USER(0x10),

    /** Backward move in the proactive UICC session requested by the user */
    BACKWARD_MOVE_BY_USER(0x11),

    /** No response from user */
    NO_RESPONSE_FROM_USER(0x12),

    /** Help information required by the user */
    HELP_INFO_REQUIRED(0x13),

    /** USSD or SS transaction terminated by the user */
    USSD_SS_SESSION_TERM_BY_USER(0x14),


    /*
     * Results '2X' indicate to the UICC that it may be worth re-trying the
     * command at a later opportunity.
     */

    /** Terminal currently unable to process command */
    TERMINAL_CRNTLY_UNABLE_TO_PROCESS(0x20),

    /** Network currently unable to process command */
    NETWORK_CRNTLY_UNABLE_TO_PROCESS(0x21),

    /** User did not accept the proactive command */
    USER_NOT_ACCEPT(0x22),

    /** User cleared down call before connection or network release */
    USER_CLEAR_DOWN_CALL(0x23),

    /** Action in contradiction with the current timer state */
    CONTRADICTION_WITH_TIMER(0x24),

    /** Interaction with call control by NAA, temporary problem */
    NAA_CALL_CONTROL_TEMPORARY(0x25),

    /** Launch browser generic error code */
    LAUNCH_BROWSER_ERROR(0x26),

    /** MMS temporary problem. */
    MMS_TEMPORARY(0x27),


    /*
     * Results '3X' indicate that it is not worth the UICC re-trying with an
     * identical command, as it will only get the same response. However, the
     * decision to retry lies with the application.
     */

    /** Command beyond terminal's capabilities */
    BEYOND_TERMINAL_CAPABILITY(0x30),

    /** Command type not understood by terminal */
    CMD_TYPE_NOT_UNDERSTOOD(0x31),

    /** Command data not understood by terminal */
    CMD_DATA_NOT_UNDERSTOOD(0x32),

    /** Command number not known by terminal */
    CMD_NUM_NOT_KNOWN(0x33),

    /** SS Return Error */
    SS_RETURN_ERROR(0x34),

    /** SMS RP-ERROR */
    SMS_RP_ERROR(0x35),

    /** Error, required values are missing */
    REQUIRED_VALUES_MISSING(0x36),

    /** USSD Return Error */
    USSD_RETURN_ERROR(0x37),

    /** MultipleCard commands error */
    MULTI_CARDS_CMD_ERROR(0x38),

    /**
     * Interaction with call control by USIM or MO short message control by
     * USIM, permanent problem
     */
    USIM_CALL_CONTROL_PERMANENT(0x39),

    /** Bearer Independent Protocol error */
    BIP_ERROR(0x3a),

    /** Access Technology unable to process command */
    ACCESS_TECH_UNABLE_TO_PROCESS(0x3b),

    /** Frames error */
    FRAMES_ERROR(0x3c),

    /** MMS Error */
    MMS_ERROR(0x3d);


    private int mCode;

    ResultCode(int code) {
        mCode = code;
    }

    /**
     * Retrieves the actual result code that this object represents.
     * @return Actual result code
     */
    public int value() {
        return mCode;
    }

    public static ResultCode fromInt(int value) {
        for (ResultCode r : ResultCode.values()) {
            if (r.mCode == value) {
                return r;
            }
        }
        return null;
    }
}
