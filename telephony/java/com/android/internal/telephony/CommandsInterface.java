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

package com.android.internal.telephony;

import com.android.internal.telephony.gsm.SmsBroadcastConfigInfo;

import android.os.Message;
import android.os.Handler;
import android.util.Log;

/**
 * {@hide}
 */
public interface CommandsInterface {
    enum RadioState {
        RADIO_OFF,         /* Radio explicitly powered off (eg CFUN=0) */
        RADIO_UNAVAILABLE, /* Radio unavailable (eg, resetting or not booted) */
        RADIO_ON;          /* Radio is on */

        public boolean isOn() /* and available...*/ {
            return this == RADIO_ON;
        }

        public boolean isAvailable() {
            return this != RADIO_UNAVAILABLE;
        }
    }

    //***** Constants

    // Used as parameter to dial() and setCLIR() below
    static final int CLIR_DEFAULT = 0;      // "use subscription default value"
    static final int CLIR_INVOCATION = 1;   // (restrict CLI presentation)
    static final int CLIR_SUPPRESSION = 2;  // (allow CLI presentation)


    // Used as parameters for call forward methods below
    static final int CF_ACTION_DISABLE          = 0;
    static final int CF_ACTION_ENABLE           = 1;
//  static final int CF_ACTION_UNUSED           = 2;
    static final int CF_ACTION_REGISTRATION     = 3;
    static final int CF_ACTION_ERASURE          = 4;

    static final int CF_REASON_UNCONDITIONAL    = 0;
    static final int CF_REASON_BUSY             = 1;
    static final int CF_REASON_NO_REPLY         = 2;
    static final int CF_REASON_NOT_REACHABLE    = 3;
    static final int CF_REASON_ALL              = 4;
    static final int CF_REASON_ALL_CONDITIONAL  = 5;

    // Used for call barring methods below
    static final String CB_FACILITY_BAOC         = "AO";
    static final String CB_FACILITY_BAOIC        = "OI";
    static final String CB_FACILITY_BAOICxH      = "OX";
    static final String CB_FACILITY_BAIC         = "AI";
    static final String CB_FACILITY_BAICr        = "IR";
    static final String CB_FACILITY_BA_ALL       = "AB";
    static final String CB_FACILITY_BA_MO        = "AG";
    static final String CB_FACILITY_BA_MT        = "AC";
    static final String CB_FACILITY_BA_SIM       = "SC";
    static final String CB_FACILITY_BA_FD        = "FD";


    // Used for various supp services apis
    // See 27.007 +CCFC or +CLCK
    static final int SERVICE_CLASS_NONE     = 0; // no user input
    static final int SERVICE_CLASS_VOICE    = (1 << 0);
    static final int SERVICE_CLASS_DATA     = (1 << 1); //synonym for 16+32+64+128
    static final int SERVICE_CLASS_FAX      = (1 << 2);
    static final int SERVICE_CLASS_SMS      = (1 << 3);
    static final int SERVICE_CLASS_DATA_SYNC = (1 << 4);
    static final int SERVICE_CLASS_DATA_ASYNC = (1 << 5);
    static final int SERVICE_CLASS_PACKET   = (1 << 6);
    static final int SERVICE_CLASS_PAD      = (1 << 7);
    static final int SERVICE_CLASS_MAX      = (1 << 7); // Max SERVICE_CLASS value

    // Numeric representation of string values returned
    // by messages sent to setOnUSSD handler
    static final int USSD_MODE_NOTIFY       = 0;
    static final int USSD_MODE_REQUEST      = 1;

    // SIM Refresh results, passed up from RIL.
    static final int SIM_REFRESH_FILE_UPDATED   = 0;  // Single file updated
    static final int SIM_REFRESH_INIT           = 1;  // SIM initialized; reload all
    static final int SIM_REFRESH_RESET          = 2;  // SIM reset; may be locked

    // GSM SMS fail cause for acknowledgeLastIncomingSMS. From TS 23.040, 9.2.3.22.
    static final int GSM_SMS_FAIL_CAUSE_MEMORY_CAPACITY_EXCEEDED    = 0xD3;
    static final int GSM_SMS_FAIL_CAUSE_USIM_APP_TOOLKIT_BUSY       = 0xD4;
    static final int GSM_SMS_FAIL_CAUSE_USIM_DATA_DOWNLOAD_ERROR    = 0xD5;
    static final int GSM_SMS_FAIL_CAUSE_UNSPECIFIED_ERROR           = 0xFF;

    // CDMA SMS fail cause for acknowledgeLastIncomingCdmaSms.  From TS N.S0005, 6.5.2.125.
    static final int CDMA_SMS_FAIL_CAUSE_INVALID_TELESERVICE_ID     = 4;
    static final int CDMA_SMS_FAIL_CAUSE_RESOURCE_SHORTAGE          = 35;
    static final int CDMA_SMS_FAIL_CAUSE_OTHER_TERMINAL_PROBLEM     = 39;
    static final int CDMA_SMS_FAIL_CAUSE_ENCODING_PROBLEM           = 96;

    //***** Methods
    RadioState getRadioState();

    void getVoiceRadioTechnology(Message result);

    /**
     * Fires on any RadioState transition
     * Always fires immediately as well
     *
     * do not attempt to calculate transitions by storing getRadioState() values
     * on previous invocations of this notification. Instead, use the other
     * registration methods
     */
    void registerForRadioStateChanged(Handler h, int what, Object obj);
    void unregisterForRadioStateChanged(Handler h);

    void registerForVoiceRadioTechChanged(Handler h, int what, Object obj);
    void unregisterForVoiceRadioTechChanged(Handler h);

    /**
     * Fires on any transition into RadioState.isOn()
     * Fires immediately if currently in that state
     * In general, actions should be idempotent. State may change
     * before event is received.
     */
    void registerForOn(Handler h, int what, Object obj);
    void unregisterForOn(Handler h);

    /**
     * Fires on any transition out of RadioState.isAvailable()
     * Fires immediately if currently in that state
     * In general, actions should be idempotent. State may change
     * before event is received.
     */
    void registerForAvailable(Handler h, int what, Object obj);
    void unregisterForAvailable(Handler h);

    /**
     * Fires on any transition into !RadioState.isAvailable()
     * Fires immediately if currently in that state
     * In general, actions should be idempotent. State may change
     * before event is received.
     */
    void registerForNotAvailable(Handler h, int what, Object obj);
    void unregisterForNotAvailable(Handler h);

    /**
     * Fires on any transition into RADIO_OFF or !RadioState.isAvailable()
     * Fires immediately if currently in that state
     * In general, actions should be idempotent. State may change
     * before event is received.
     */
    void registerForOffOrNotAvailable(Handler h, int what, Object obj);
    void unregisterForOffOrNotAvailable(Handler h);

    /**
     * Fires on any change in ICC status
     */
    void registerForIccStatusChanged(Handler h, int what, Object obj);
    void unregisterForIccStatusChanged(Handler h);

    void registerForCallStateChanged(Handler h, int what, Object obj);
    void unregisterForCallStateChanged(Handler h);
    void registerForVoiceNetworkStateChanged(Handler h, int what, Object obj);
    void unregisterForVoiceNetworkStateChanged(Handler h);
    void registerForDataNetworkStateChanged(Handler h, int what, Object obj);
    void unregisterForDataNetworkStateChanged(Handler h);

    /** InCall voice privacy notifications */
    void registerForInCallVoicePrivacyOn(Handler h, int what, Object obj);
    void unregisterForInCallVoicePrivacyOn(Handler h);
    void registerForInCallVoicePrivacyOff(Handler h, int what, Object obj);
    void unregisterForInCallVoicePrivacyOff(Handler h);

    /**
     * unlike the register* methods, there's only one new 3GPP format SMS handler.
     * if you need to unregister, you should also tell the radio to stop
     * sending SMS's to you (via AT+CNMI)
     *
     * AsyncResult.result is a String containing the SMS PDU
     */
    void setOnNewGsmSms(Handler h, int what, Object obj);
    void unSetOnNewGsmSms(Handler h);

    /**
     * unlike the register* methods, there's only one new 3GPP2 format SMS handler.
     * if you need to unregister, you should also tell the radio to stop
     * sending SMS's to you (via AT+CNMI)
     *
     * AsyncResult.result is a String containing the SMS PDU
     */
    void setOnNewCdmaSms(Handler h, int what, Object obj);
    void unSetOnNewCdmaSms(Handler h);

    /**
     * Set the handler for SMS Cell Broadcast messages.
     *
     * AsyncResult.result is a byte array containing the SMS-CB PDU
     */
    void setOnNewGsmBroadcastSms(Handler h, int what, Object obj);
    void unSetOnNewGsmBroadcastSms(Handler h);

    /**
     * Register for NEW_SMS_ON_SIM unsolicited message
     *
     * AsyncResult.result is an int array containing the index of new SMS
     */
    void setOnSmsOnSim(Handler h, int what, Object obj);
    void unSetOnSmsOnSim(Handler h);

    /**
     * Register for NEW_SMS_STATUS_REPORT unsolicited message
     *
     * AsyncResult.result is a String containing the status report PDU
     */
    void setOnSmsStatus(Handler h, int what, Object obj);
    void unSetOnSmsStatus(Handler h);

    /**
     * unlike the register* methods, there's only one NITZ time handler
     *
     * AsyncResult.result is an Object[]
     * ((Object[])AsyncResult.result)[0] is a String containing the NITZ time string
     * ((Object[])AsyncResult.result)[1] is a Long containing the milliseconds since boot as
     *                                   returned by elapsedRealtime() when this NITZ time
     *                                   was posted.
     *
     * Please note that the delivery of this message may be delayed several
     * seconds on system startup
     */
    void setOnNITZTime(Handler h, int what, Object obj);
    void unSetOnNITZTime(Handler h);

    /**
     * unlike the register* methods, there's only one USSD notify handler
     *
     * Represents the arrival of a USSD "notify" message, which may
     * or may not have been triggered by a previous USSD send
     *
     * AsyncResult.result is a String[]
     * ((String[])(AsyncResult.result))[0] contains status code
     *      "0"   USSD-Notify -- text in ((const char **)data)[1]
     *      "1"   USSD-Request -- text in ((const char **)data)[1]
     *      "2"   Session terminated by network
     *      "3"   other local client (eg, SIM Toolkit) has responded
     *      "4"   Operation not supported
     *      "5"   Network timeout
     *
     * ((String[])(AsyncResult.result))[1] contains the USSD message
     * The numeric representations of these are in USSD_MODE_*
     */

    void setOnUSSD(Handler h, int what, Object obj);
    void unSetOnUSSD(Handler h);

    /**
     * unlike the register* methods, there's only one signal strength handler
     * AsyncResult.result is an int[2]
     * response.obj.result[0] is received signal strength (0-31, 99)
     * response.obj.result[1] is  bit error rate (0-7, 99)
     * as defined in TS 27.007 8.5
     */

    void setOnSignalStrengthUpdate(Handler h, int what, Object obj);
    void unSetOnSignalStrengthUpdate(Handler h);

    /**
     * Sets the handler for SIM/RUIM SMS storage full unsolicited message.
     * Unlike the register* methods, there's only one notification handler
     *
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    void setOnIccSmsFull(Handler h, int what, Object obj);
    void unSetOnIccSmsFull(Handler h);

    /**
     * Sets the handler for SIM Refresh notifications.
     *
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    void registerForIccRefresh(Handler h, int what, Object obj);
    void unregisterForIccRefresh(Handler h);

    void setOnIccRefresh(Handler h, int what, Object obj);
    void unsetOnIccRefresh(Handler h);

    /**
     * Sets the handler for RING notifications.
     * Unlike the register* methods, there's only one notification handler
     *
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    void setOnCallRing(Handler h, int what, Object obj);
    void unSetOnCallRing(Handler h);

    /**
     * Sets the handler for RESTRICTED_STATE changed notification,
     * eg, for Domain Specific Access Control
     * unlike the register* methods, there's only one signal strength handler
     *
     * AsyncResult.result is an int[1]
     * response.obj.result[0] is a bitmask of RIL_RESTRICTED_STATE_* values
     */

    void setOnRestrictedStateChanged(Handler h, int what, Object obj);
    void unSetOnRestrictedStateChanged(Handler h);

    /**
     * Sets the handler for Supplementary Service Notifications.
     * Unlike the register* methods, there's only one notification handler
     *
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    void setOnSuppServiceNotification(Handler h, int what, Object obj);
    void unSetOnSuppServiceNotification(Handler h);

    /**
     * Sets the handler for Session End Notifications for CAT.
     * Unlike the register* methods, there's only one notification handler
     *
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    void setOnCatSessionEnd(Handler h, int what, Object obj);
    void unSetOnCatSessionEnd(Handler h);

    /**
     * Sets the handler for Proactive Commands for CAT.
     * Unlike the register* methods, there's only one notification handler
     *
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    void setOnCatProactiveCmd(Handler h, int what, Object obj);
    void unSetOnCatProactiveCmd(Handler h);

    /**
     * Sets the handler for Event Notifications for CAT.
     * Unlike the register* methods, there's only one notification handler
     *
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    void setOnCatEvent(Handler h, int what, Object obj);
    void unSetOnCatEvent(Handler h);

    /**
     * Sets the handler for Call Set Up Notifications for CAT.
     * Unlike the register* methods, there's only one notification handler
     *
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    void setOnCatCallSetUp(Handler h, int what, Object obj);
    void unSetOnCatCallSetUp(Handler h);

    /**
     * Enables/disbables supplementary service related notifications from
     * the network.
     *
     * @param enable true to enable notifications, false to disable.
     * @param result Message to be posted when command completes.
     */
    void setSuppServiceNotifications(boolean enable, Message result);
    //void unSetSuppServiceNotifications(Handler h);

    /**
     * Sets the handler for Event Notifications for CDMA Display Info.
     * Unlike the register* methods, there's only one notification handler
     *
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    void registerForDisplayInfo(Handler h, int what, Object obj);
    void unregisterForDisplayInfo(Handler h);

    /**
     * Sets the handler for Event Notifications for CallWaiting Info.
     * Unlike the register* methods, there's only one notification handler
     *
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    void registerForCallWaitingInfo(Handler h, int what, Object obj);
    void unregisterForCallWaitingInfo(Handler h);

    /**
     * Sets the handler for Event Notifications for Signal Info.
     * Unlike the register* methods, there's only one notification handler
     *
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    void registerForSignalInfo(Handler h, int what, Object obj);
    void unregisterForSignalInfo(Handler h);

    /**
     * Registers the handler for CDMA number information record
     * Unlike the register* methods, there's only one notification handler
     *
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    void registerForNumberInfo(Handler h, int what, Object obj);
    void unregisterForNumberInfo(Handler h);

    /**
     * Registers the handler for CDMA redirected number Information record
     * Unlike the register* methods, there's only one notification handler
     *
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    void registerForRedirectedNumberInfo(Handler h, int what, Object obj);
    void unregisterForRedirectedNumberInfo(Handler h);

    /**
     * Registers the handler for CDMA line control information record
     * Unlike the register* methods, there's only one notification handler
     *
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    void registerForLineControlInfo(Handler h, int what, Object obj);
    void unregisterForLineControlInfo(Handler h);

    /**
     * Registers the handler for CDMA T53 CLIR information record
     * Unlike the register* methods, there's only one notification handler
     *
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    void registerFoT53ClirlInfo(Handler h, int what, Object obj);
    void unregisterForT53ClirInfo(Handler h);

    /**
     * Registers the handler for CDMA T53 audio control information record
     * Unlike the register* methods, there's only one notification handler
     *
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    void registerForT53AudioControlInfo(Handler h, int what, Object obj);
    void unregisterForT53AudioControlInfo(Handler h);

    /**
     * Fires on if Modem enters Emergency Callback mode
     */
    void setEmergencyCallbackMode(Handler h, int what, Object obj);

     /**
      * Fires on any CDMA OTA provision status change
      */
     void registerForCdmaOtaProvision(Handler h,int what, Object obj);
     void unregisterForCdmaOtaProvision(Handler h);

     /**
      * Registers the handler when out-band ringback tone is needed.<p>
      *
      *  Messages received from this:
      *  Message.obj will be an AsyncResult
      *  AsyncResult.userObj = obj
      *  AsyncResult.result = boolean. <p>
      */
     void registerForRingbackTone(Handler h, int what, Object obj);
     void unregisterForRingbackTone(Handler h);

     /**
      * Registers the handler when mute/unmute need to be resent to get
      * uplink audio during a call.<p>
      *
      * @param h Handler for notification message.
      * @param what User-defined message code.
      * @param obj User object.
      *
      */
     void registerForResendIncallMute(Handler h, int what, Object obj);
     void unregisterForResendIncallMute(Handler h);

     /**
      * Registers the handler for when Cdma subscription changed events
      *
      * @param h Handler for notification message.
      * @param what User-defined message code.
      * @param obj User object.
      *
      */
     void registerForCdmaSubscriptionChanged(Handler h, int what, Object obj);
     void unregisterForCdmaSubscriptionChanged(Handler h);

     /**
      * Registers the handler for when Cdma prl changed events
      *
      * @param h Handler for notification message.
      * @param what User-defined message code.
      * @param obj User object.
      *
      */
     void registerForCdmaPrlChanged(Handler h, int what, Object obj);
     void unregisterForCdmaPrlChanged(Handler h);

     /**
      * Registers the handler for when Cdma prl changed events
      *
      * @param h Handler for notification message.
      * @param what User-defined message code.
      * @param obj User object.
      *
      */
     void registerForExitEmergencyCallbackMode(Handler h, int what, Object obj);
     void unregisterForExitEmergencyCallbackMode(Handler h);

     /**
      * Registers the handler for RIL_UNSOL_RIL_CONNECT events.
      *
      * When ril connects or disconnects a message is sent to the registrant
      * which contains an AsyncResult, ar, in msg.obj. The ar.result is an
      * Integer which is the version of the ril or -1 if the ril disconnected.
      *
      * @param h Handler for notification message.
      * @param what User-defined message code.
      * @param obj User object.
      */
     void registerForRilConnected(Handler h, int what, Object obj);
     void unregisterForRilConnected(Handler h);

    /**
     * Supply the ICC PIN to the ICC card
     *
     *  returned message
     *  retMsg.obj = AsyncResult ar
     *  ar.exception carries exception on failure
     *  This exception is CommandException with an error of PASSWORD_INCORRECT
     *  if the password is incorrect
     *
     * ar.exception and ar.result are null on success
     */

    void supplyIccPin(String pin, Message result);

    /**
     * Supply the PIN for the app with this AID on the ICC card
     *
     *  AID (Application ID), See ETSI 102.221 8.1 and 101.220 4
     *
     *  returned message
     *  retMsg.obj = AsyncResult ar
     *  ar.exception carries exception on failure
     *  This exception is CommandException with an error of PASSWORD_INCORRECT
     *  if the password is incorrect
     *
     * ar.exception and ar.result are null on success
     */

    void supplyIccPinForApp(String pin, String aid, Message result);

    /**
     * Supply the ICC PUK and newPin to the ICC card
     *
     *  returned message
     *  retMsg.obj = AsyncResult ar
     *  ar.exception carries exception on failure
     *  This exception is CommandException with an error of PASSWORD_INCORRECT
     *  if the password is incorrect
     *
     * ar.exception and ar.result are null on success
     */

    void supplyIccPuk(String puk, String newPin, Message result);

    /**
     * Supply the PUK, new pin for the app with this AID on the ICC card
     *
     *  AID (Application ID), See ETSI 102.221 8.1 and 101.220 4
     *
     *  returned message
     *  retMsg.obj = AsyncResult ar
     *  ar.exception carries exception on failure
     *  This exception is CommandException with an error of PASSWORD_INCORRECT
     *  if the password is incorrect
     *
     * ar.exception and ar.result are null on success
     */

    void supplyIccPukForApp(String puk, String newPin, String aid, Message result);

    /**
     * Supply the ICC PIN2 to the ICC card
     * Only called following operation where ICC_PIN2 was
     * returned as a a failure from a previous operation
     *
     *  returned message
     *  retMsg.obj = AsyncResult ar
     *  ar.exception carries exception on failure
     *  This exception is CommandException with an error of PASSWORD_INCORRECT
     *  if the password is incorrect
     *
     * ar.exception and ar.result are null on success
     */

    void supplyIccPin2(String pin2, Message result);

    /**
     * Supply the PIN2 for the app with this AID on the ICC card
     * Only called following operation where ICC_PIN2 was
     * returned as a a failure from a previous operation
     *
     *  AID (Application ID), See ETSI 102.221 8.1 and 101.220 4
     *
     *  returned message
     *  retMsg.obj = AsyncResult ar
     *  ar.exception carries exception on failure
     *  This exception is CommandException with an error of PASSWORD_INCORRECT
     *  if the password is incorrect
     *
     * ar.exception and ar.result are null on success
     */

    void supplyIccPin2ForApp(String pin2, String aid, Message result);

    /**
     * Supply the SIM PUK2 to the SIM card
     * Only called following operation where SIM_PUK2 was
     * returned as a a failure from a previous operation
     *
     *  returned message
     *  retMsg.obj = AsyncResult ar
     *  ar.exception carries exception on failure
     *  This exception is CommandException with an error of PASSWORD_INCORRECT
     *  if the password is incorrect
     *
     * ar.exception and ar.result are null on success
     */

    void supplyIccPuk2(String puk2, String newPin2, Message result);

    /**
     * Supply the PUK2, newPin2 for the app with this AID on the ICC card
     * Only called following operation where SIM_PUK2 was
     * returned as a a failure from a previous operation
     *
     *  AID (Application ID), See ETSI 102.221 8.1 and 101.220 4
     *
     *  returned message
     *  retMsg.obj = AsyncResult ar
     *  ar.exception carries exception on failure
     *  This exception is CommandException with an error of PASSWORD_INCORRECT
     *  if the password is incorrect
     *
     * ar.exception and ar.result are null on success
     */

    void supplyIccPuk2ForApp(String puk2, String newPin2, String aid, Message result);

    void changeIccPin(String oldPin, String newPin, Message result);
    void changeIccPinForApp(String oldPin, String newPin, String aidPtr, Message result);
    void changeIccPin2(String oldPin2, String newPin2, Message result);
    void changeIccPin2ForApp(String oldPin2, String newPin2, String aidPtr, Message result);

    void changeBarringPassword(String facility, String oldPwd, String newPwd, Message result);

    void supplyNetworkDepersonalization(String netpin, Message result);

    /**
     *  returned message
     *  retMsg.obj = AsyncResult ar
     *  ar.exception carries exception on failure
     *  ar.userObject contains the orignal value of result.obj
     *  ar.result contains a List of DriverCall
     *      The ar.result List is sorted by DriverCall.index
     */
    void getCurrentCalls (Message result);

    /**
     *  returned message
     *  retMsg.obj = AsyncResult ar
     *  ar.exception carries exception on failure
     *  ar.userObject contains the orignal value of result.obj
     *  ar.result contains a List of DataCallState
     *  @deprecated Do not use.
     */
    @Deprecated
    void getPDPContextList(Message result);

    /**
     *  returned message
     *  retMsg.obj = AsyncResult ar
     *  ar.exception carries exception on failure
     *  ar.userObject contains the orignal value of result.obj
     *  ar.result contains a List of DataCallState
     */
    void getDataCallList(Message result);

    /**
     *  returned message
     *  retMsg.obj = AsyncResult ar
     *  ar.exception carries exception on failure
     *  ar.userObject contains the orignal value of result.obj
     *  ar.result is null on success and failure
     *
     * CLIR_DEFAULT     == on "use subscription default value"
     * CLIR_SUPPRESSION == on "CLIR suppression" (allow CLI presentation)
     * CLIR_INVOCATION  == on "CLIR invocation" (restrict CLI presentation)
     */
    void dial (String address, int clirMode, Message result);

    /**
     *  returned message
     *  retMsg.obj = AsyncResult ar
     *  ar.exception carries exception on failure
     *  ar.userObject contains the orignal value of result.obj
     *  ar.result is null on success and failure
     *
     * CLIR_DEFAULT     == on "use subscription default value"
     * CLIR_SUPPRESSION == on "CLIR suppression" (allow CLI presentation)
     * CLIR_INVOCATION  == on "CLIR invocation" (restrict CLI presentation)
     */
    void dial(String address, int clirMode, UUSInfo uusInfo, Message result);

    /**
     *  returned message
     *  retMsg.obj = AsyncResult ar
     *  ar.exception carries exception on failure
     *  ar.userObject contains the orignal value of result.obj
     *  ar.result is String containing IMSI on success
     */
    void getIMSI(Message result);

    /**
     *  returned message
     *  retMsg.obj = AsyncResult ar
     *  ar.exception carries exception on failure
     *  ar.userObject contains the orignal value of result.obj
     *  ar.result is String containing IMEI on success
     */
    void getIMEI(Message result);

    /**
     *  returned message
     *  retMsg.obj = AsyncResult ar
     *  ar.exception carries exception on failure
     *  ar.userObject contains the orignal value of result.obj
     *  ar.result is String containing IMEISV on success
     */
    void getIMEISV(Message result);

    /**
     * Hang up one individual connection.
     *  returned message
     *  retMsg.obj = AsyncResult ar
     *  ar.exception carries exception on failure
     *  ar.userObject contains the orignal value of result.obj
     *  ar.result is null on success and failure
     *
     *  3GPP 22.030 6.5.5
     *  "Releases a specific active call X"
     */
    void hangupConnection (int gsmIndex, Message result);

    /**
     * 3GPP 22.030 6.5.5
     *  "Releases all held calls or sets User Determined User Busy (UDUB)
     *   for a waiting call."
     *  ar.exception carries exception on failure
     *  ar.userObject contains the orignal value of result.obj
     *  ar.result is null on success and failure
     */
    void hangupWaitingOrBackground (Message result);

    /**
     * 3GPP 22.030 6.5.5
     * "Releases all active calls (if any exist) and accepts
     *  the other (held or waiting) call."
     *
     *  ar.exception carries exception on failure
     *  ar.userObject contains the orignal value of result.obj
     *  ar.result is null on success and failure
     */
    void hangupForegroundResumeBackground (Message result);

    /**
     * 3GPP 22.030 6.5.5
     * "Places all active calls (if any exist) on hold and accepts
     *  the other (held or waiting) call."
     *
     *  ar.exception carries exception on failure
     *  ar.userObject contains the orignal value of result.obj
     *  ar.result is null on success and failure
     */
    void switchWaitingOrHoldingAndActive (Message result);

    /**
     * 3GPP 22.030 6.5.5
     * "Adds a held call to the conversation"
     *
     *  ar.exception carries exception on failure
     *  ar.userObject contains the orignal value of result.obj
     *  ar.result is null on success and failure
     */
    void conference (Message result);

    /**
     * Set preferred Voice Privacy (VP).
     *
     * @param enable true is enhanced and false is normal VP
     * @param result is a callback message
     */
    void setPreferredVoicePrivacy(boolean enable, Message result);

    /**
     * Get currently set preferred Voice Privacy (VP) mode.
     *
     * @param result is a callback message
     */
    void getPreferredVoicePrivacy(Message result);

    /**
     * 3GPP 22.030 6.5.5
     * "Places all active calls on hold except call X with which
     *  communication shall be supported."
     */
    void separateConnection (int gsmIndex, Message result);

    /**
     *
     *  ar.exception carries exception on failure
     *  ar.userObject contains the orignal value of result.obj
     *  ar.result is null on success and failure
     */
    void acceptCall (Message result);

    /**
     *  also known as UDUB
     *  ar.exception carries exception on failure
     *  ar.userObject contains the orignal value of result.obj
     *  ar.result is null on success and failure
     */
    void rejectCall (Message result);

    /**
     * 3GPP 22.030 6.5.5
     * "Connects the two calls and disconnects the subscriber from both calls"
     *
     *  ar.exception carries exception on failure
     *  ar.userObject contains the orignal value of result.obj
     *  ar.result is null on success and failure
     */
    void explicitCallTransfer (Message result);

    /**
     * cause code returned as int[0] in Message.obj.response
     * Returns integer cause code defined in TS 24.008
     * Annex H or closest approximation.
     * Most significant codes:
     * - Any defined in 22.001 F.4 (for generating busy/congestion)
     * - Cause 68: ACM >= ACMMax
     */
    void getLastCallFailCause (Message result);


    /**
     * Reason for last PDP context deactivate or failure to activate
     * cause code returned as int[0] in Message.obj.response
     * returns an integer cause code defined in TS 24.008
     * section 6.1.3.1.3 or close approximation
     * @deprecated Do not use.
     */
    @Deprecated
    void getLastPdpFailCause (Message result);

    /**
     * The preferred new alternative to getLastPdpFailCause
     * that is also CDMA-compatible.
     */
    void getLastDataCallFailCause (Message result);

    void setMute (boolean enableMute, Message response);

    void getMute (Message response);

    /**
     * response.obj is an AsyncResult
     * response.obj.result is an int[2]
     * response.obj.result[0] is received signal strength (0-31, 99)
     * response.obj.result[1] is  bit error rate (0-7, 99)
     * as defined in TS 27.007 8.5
     */
    void getSignalStrength (Message response);


    /**
     * response.obj.result is an int[3]
     * response.obj.result[0] is registration state 0-5 from TS 27.007 7.2
     * response.obj.result[1] is LAC if registered or -1 if not
     * response.obj.result[2] is CID if registered or -1 if not
     * valid LAC and CIDs are 0x0000 - 0xffff
     *
     * Please note that registration state 4 ("unknown") is treated
     * as "out of service" above
     */
    void getVoiceRegistrationState (Message response);

    /**
     * response.obj.result is an int[3]
     * response.obj.result[0] is registration state 0-5 from TS 27.007 7.2
     * response.obj.result[1] is LAC if registered or -1 if not
     * response.obj.result[2] is CID if registered or -1 if not
     * valid LAC and CIDs are 0x0000 - 0xffff
     *
     * Please note that registration state 4 ("unknown") is treated
     * as "out of service" above
     */
    void getDataRegistrationState (Message response);

    /**
     * response.obj.result is a String[3]
     * response.obj.result[0] is long alpha or null if unregistered
     * response.obj.result[1] is short alpha or null if unregistered
     * response.obj.result[2] is numeric or null if unregistered
     */
    void getOperator(Message response);

    /**
     *  ar.exception carries exception on failure
     *  ar.userObject contains the orignal value of result.obj
     *  ar.result is null on success and failure
     */
    void sendDtmf(char c, Message result);


    /**
     *  ar.exception carries exception on failure
     *  ar.userObject contains the orignal value of result.obj
     *  ar.result is null on success and failure
     */
    void startDtmf(char c, Message result);

    /**
     *  ar.exception carries exception on failure
     *  ar.userObject contains the orignal value of result.obj
     *  ar.result is null on success and failure
     */
    void stopDtmf(Message result);

    /**
     *  ar.exception carries exception on failure
     *  ar.userObject contains the orignal value of result.obj
     *  ar.result is null on success and failure
     */
    void sendBurstDtmf(String dtmfString, int on, int off, Message result);

    /**
     * smscPDU is smsc address in PDU form GSM BCD format prefixed
     *      by a length byte (as expected by TS 27.005) or NULL for default SMSC
     * pdu is SMS in PDU format as an ASCII hex string
     *      less the SMSC address
     */
    void sendSMS (String smscPDU, String pdu, Message response);

    /**
     * @param pdu is CDMA-SMS in internal pseudo-PDU format
     * @param response sent when operation completes
     */
    void sendCdmaSms(byte[] pdu, Message response);

    /**
     * Deletes the specified SMS record from SIM memory (EF_SMS).
     *
     * @param index index of the SMS record to delete
     * @param response sent when operation completes
     */
    void deleteSmsOnSim(int index, Message response);

    /**
     * Deletes the specified SMS record from RUIM memory (EF_SMS in DF_CDMA).
     *
     * @param index index of the SMS record to delete
     * @param response sent when operation completes
     */
    void deleteSmsOnRuim(int index, Message response);

    /**
     * Writes an SMS message to SIM memory (EF_SMS).
     *
     * @param status status of message on SIM.  One of:
     *                  SmsManger.STATUS_ON_ICC_READ
     *                  SmsManger.STATUS_ON_ICC_UNREAD
     *                  SmsManger.STATUS_ON_ICC_SENT
     *                  SmsManger.STATUS_ON_ICC_UNSENT
     * @param pdu message PDU, as hex string
     * @param response sent when operation completes.
     *                  response.obj will be an AsyncResult, and will indicate
     *                  any error that may have occurred (eg, out of memory).
     */
    void writeSmsToSim(int status, String smsc, String pdu, Message response);

    void writeSmsToRuim(int status, String pdu, Message response);

    void setRadioPower(boolean on, Message response);

    void acknowledgeLastIncomingGsmSms(boolean success, int cause, Message response);

    void acknowledgeLastIncomingCdmaSms(boolean success, int cause, Message response);

    /**
     * Acknowledge successful or failed receipt of last incoming SMS,
     * including acknowledgement TPDU to send as the RP-User-Data element
     * of the RP-ACK or RP-ERROR PDU.
     *
     * @param success true to send RP-ACK, false to send RP-ERROR
     * @param ackPdu the acknowledgement TPDU in hexadecimal format
     * @param response sent when operation completes.
     */
    void acknowledgeIncomingGsmSmsWithPdu(boolean success, String ackPdu, Message response);

    /**
     * parameters equivalent to 27.007 AT+CRSM command
     * response.obj will be an AsyncResult
     * response.obj.result will be an IccIoResult on success
     */
    void iccIO (int command, int fileid, String path, int p1, int p2, int p3,
            String data, String pin2, Message response);

    /**
     * (AsyncResult)response.obj).result is an int[] with element [0] set to
     * 1 for "CLIP is provisioned", and 0 for "CLIP is not provisioned".
     *
     * @param response is callback message
     */

    void queryCLIP(Message response);

    /**
     * response.obj will be a an int[2]
     *
     * response.obj[0] will be TS 27.007 +CLIR parameter 'n'
     *  0 presentation indicator is used according to the subscription of the CLIR service
     *  1 CLIR invocation
     *  2 CLIR suppression
     *
     * response.obj[1] will be TS 27.007 +CLIR parameter 'm'
     *  0 CLIR not provisioned
     *  1 CLIR provisioned in permanent mode
     *  2 unknown (e.g. no network, etc.)
     *  3 CLIR temporary mode presentation restricted
     *  4 CLIR temporary mode presentation allowed
     */

    void getCLIR(Message response);

    /**
     * clirMode is one of the CLIR_* constants above
     *
     * response.obj is null
     */

    void setCLIR(int clirMode, Message response);

    /**
     * (AsyncResult)response.obj).result is an int[] with element [0] set to
     * 0 for disabled, 1 for enabled.
     *
     * @param serviceClass is a sum of SERVICE_CLASS_*
     * @param response is callback message
     */

    void queryCallWaiting(int serviceClass, Message response);

    /**
     * @param enable is true to enable, false to disable
     * @param serviceClass is a sum of SERVICE_CLASS_*
     * @param response is callback message
     */

    void setCallWaiting(boolean enable, int serviceClass, Message response);

    /**
     * @param action is one of CF_ACTION_*
     * @param cfReason is one of CF_REASON_*
     * @param serviceClass is a sum of SERVICE_CLASSS_*
     */
    void setCallForward(int action, int cfReason, int serviceClass,
                String number, int timeSeconds, Message response);

    /**
     * cfReason is one of CF_REASON_*
     *
     * ((AsyncResult)response.obj).result will be an array of
     * CallForwardInfo's
     *
     * An array of length 0 means "disabled for all codes"
     */
    void queryCallForwardStatus(int cfReason, int serviceClass,
            String number, Message response);

    void setNetworkSelectionModeAutomatic(Message response);

    void setNetworkSelectionModeManual(String operatorNumeric, Message response);

    /**
     * Queries whether the current network selection mode is automatic
     * or manual
     *
     * ((AsyncResult)response.obj).result  is an int[] with element [0] being
     * a 0 for automatic selection and a 1 for manual selection
     */

    void getNetworkSelectionMode(Message response);

    /**
     * Queries the currently available networks
     *
     * ((AsyncResult)response.obj).result  is a List of NetworkInfo objects
     */
    void getAvailableNetworks(Message response);

    void getBasebandVersion (Message response);


    /**
     * (AsyncResult)response.obj).result will be an Integer representing
     * the sum of enabled service classes (sum of SERVICE_CLASS_*)
     *
     * @param facility one of CB_FACILTY_*
     * @param password password or "" if not required
     * @param serviceClass is a sum of SERVICE_CLASS_*
     * @param response is callback message
     */

    void queryFacilityLock (String facility, String password, int serviceClass,
        Message response);

    /**
     * (AsyncResult)response.obj).result will be an Integer representing
     * the sum of enabled service classes (sum of SERVICE_CLASS_*) for the
     * application with appId.
     *
     * @param facility one of CB_FACILTY_*
     * @param password password or "" if not required
     * @param serviceClass is a sum of SERVICE_CLASS_*
     * @param appId is application Id or null if none
     * @param response is callback message
     */

    void queryFacilityLockForApp(String facility, String password, int serviceClass, String appId,
        Message response);

    /**
     * @param facility one of CB_FACILTY_*
     * @param lockState true means lock, false means unlock
     * @param password password or "" if not required
     * @param serviceClass is a sum of SERVICE_CLASS_*
     * @param response is callback message
     */
    void setFacilityLock (String facility, boolean lockState, String password,
        int serviceClass, Message response);

    /**
     * Set the facility lock for the app with this AID on the ICC card.
     *
     * @param facility one of CB_FACILTY_*
     * @param lockState true means lock, false means unlock
     * @param password password or "" if not required
     * @param serviceClass is a sum of SERVICE_CLASS_*
     * @param appId is application Id or null if none
     * @param response is callback message
     */
    void setFacilityLockForApp(String facility, boolean lockState, String password,
        int serviceClass, String appId, Message response);

    void sendUSSD (String ussdString, Message response);

    /**
     * Cancels a pending USSD session if one exists.
     * @param response callback message
     */
    void cancelPendingUssd (Message response);

    void resetRadio(Message result);

    /**
     * Assign a specified band for RF configuration.
     *
     * @param bandMode one of BM_*_BAND
     * @param response is callback message
     */
    void setBandMode (int bandMode, Message response);

    /**
     * Query the list of band mode supported by RF.
     *
     * @param response is callback message
     *        ((AsyncResult)response.obj).result  is an int[] with every
     *        element representing one avialable BM_*_BAND
     */
    void queryAvailableBandMode (Message response);

    /**
     * Set the current preferred network type. This will be the last
     * networkType that was passed to setPreferredNetworkType.
     */
    void setCurrentPreferredNetworkType();

    /**
     *  Requests to set the preferred network type for searching and registering
     * (CS/PS domain, RAT, and operation mode)
     * @param networkType one of  NT_*_TYPE
     * @param response is callback message
     */
    void setPreferredNetworkType(int networkType , Message response);

     /**
     *  Query the preferred network type setting
     *
     * @param response is callback message to report one of  NT_*_TYPE
     */
    void getPreferredNetworkType(Message response);

    /**
     * Query neighboring cell ids
     *
     * @param response s callback message to cell ids
     */
    void getNeighboringCids(Message response);

    /**
     * Request to enable/disable network state change notifications when
     * location information (lac and/or cid) has changed.
     *
     * @param enable true to enable, false to disable
     * @param response callback message
     */
    void setLocationUpdates(boolean enable, Message response);

    /**
     * Gets the default SMSC address.
     *
     * @param result Callback message contains the SMSC address.
     */
    void getSmscAddress(Message result);

    /**
     * Sets the default SMSC address.
     *
     * @param address new SMSC address
     * @param result Callback message is empty on completion
     */
    void setSmscAddress(String address, Message result);

    /**
     * Indicates whether there is storage available for new SMS messages.
     * @param available true if storage is available
     * @param result callback message
     */
    void reportSmsMemoryStatus(boolean available, Message result);

    /**
     * Indicates to the vendor ril that StkService is running
     * and is ready to receive RIL_UNSOL_STK_XXXX commands.
     *
     * @param result callback message
     */
    void reportStkServiceIsRunning(Message result);

    void invokeOemRilRequestRaw(byte[] data, Message response);

    void invokeOemRilRequestStrings(String[] strings, Message response);


    /**
     * Send TERMINAL RESPONSE to the SIM, after processing a proactive command
     * sent by the SIM.
     *
     * @param contents  String containing SAT/USAT response in hexadecimal
     *                  format starting with first byte of response data. See
     *                  TS 102 223 for details.
     * @param response  Callback message
     */
    public void sendTerminalResponse(String contents, Message response);

    /**
     * Send ENVELOPE to the SIM, after processing a proactive command sent by
     * the SIM.
     *
     * @param contents  String containing SAT/USAT response in hexadecimal
     *                  format starting with command tag. See TS 102 223 for
     *                  details.
     * @param response  Callback message
     */
    public void sendEnvelope(String contents, Message response);

    /**
     * Send ENVELOPE to the SIM, such as an SMS-PP data download envelope
     * for a SIM data download message. This method has one difference
     * from {@link #sendEnvelope}: The SW1 and SW2 status bytes from the UICC response
     * are returned along with the response data.
     *
     * response.obj will be an AsyncResult
     * response.obj.result will be an IccIoResult on success
     *
     * @param contents  String containing SAT/USAT response in hexadecimal
     *                  format starting with command tag. See TS 102 223 for
     *                  details.
     * @param response  Callback message
     */
    public void sendEnvelopeWithStatus(String contents, Message response);

    /**
     * Accept or reject the call setup request from SIM.
     *
     * @param accept   true if the call is to be accepted, false otherwise.
     * @param response Callback message
     */
    public void handleCallSetupRequestFromSim(boolean accept, Message response);

    /**
     * Activate or deactivate cell broadcast SMS for GSM.
     *
     * @param activate
     *            true = activate, false = deactivate
     * @param result Callback message is empty on completion
     */
    public void setGsmBroadcastActivation(boolean activate, Message result);

    /**
     * Configure cell broadcast SMS for GSM.
     *
     * @param response Callback message is empty on completion
     */
    public void setGsmBroadcastConfig(SmsBroadcastConfigInfo[] config, Message response);

    /**
     * Query the current configuration of cell broadcast SMS of GSM.
     *
     * @param response
     *        Callback message contains the configuration from the modem
     *        on completion
     */
    public void getGsmBroadcastConfig(Message response);

    //***** new Methods for CDMA support

    /**
     * Request the device ESN / MEID / IMEI / IMEISV.
     * "response" is const char **
     *   [0] is IMEI if GSM subscription is available
     *   [1] is IMEISV if GSM subscription is available
     *   [2] is ESN if CDMA subscription is available
     *   [3] is MEID if CDMA subscription is available
     */
    public void getDeviceIdentity(Message response);

    /**
     * Request the device MDN / H_SID / H_NID / MIN.
     * "response" is const char **
     *   [0] is MDN if CDMA subscription is available
     *   [1] is a comma separated list of H_SID (Home SID) in decimal format
     *       if CDMA subscription is available
     *   [2] is a comma separated list of H_NID (Home NID) in decimal format
     *       if CDMA subscription is available
     *   [3] is MIN (10 digits, MIN2+MIN1) if CDMA subscription is available
     */
    public void getCDMASubscription(Message response);

    /**
     * Send Flash Code.
     * "response" is is NULL
     *   [0] is a FLASH string
     */
    public void sendCDMAFeatureCode(String FeatureCode, Message response);

    /** Set the Phone type created */
    void setPhoneType(int phoneType);

    /**
     *  Query the CDMA roaming preference setting
     *
     * @param response is callback message to report one of  CDMA_RM_*
     */
    void queryCdmaRoamingPreference(Message response);

    /**
     *  Requests to set the CDMA roaming preference
     * @param cdmaRoamingType one of  CDMA_RM_*
     * @param response is callback message
     */
    void setCdmaRoamingPreference(int cdmaRoamingType, Message response);

    /**
     *  Requests to set the CDMA subscription mode
     * @param cdmaSubscriptionType one of  CDMA_SUBSCRIPTION_*
     * @param response is callback message
     */
    void setCdmaSubscriptionSource(int cdmaSubscriptionType, Message response);

    /**
     *  Requests to get the CDMA subscription srouce
     * @param response is callback message
     */
    void getCdmaSubscriptionSource(Message response);

    /**
     *  Set the TTY mode
     *
     * @param ttyMode one of the following:
     * - {@link com.android.internal.telephony.Phone#TTY_MODE_OFF}
     * - {@link com.android.internal.telephony.Phone#TTY_MODE_FULL}
     * - {@link com.android.internal.telephony.Phone#TTY_MODE_HCO}
     * - {@link com.android.internal.telephony.Phone#TTY_MODE_VCO}
     * @param response is callback message
     */
    void setTTYMode(int ttyMode, Message response);

    /**
     *  Query the TTY mode
     * (AsyncResult)response.obj).result is an int[] with element [0] set to
     * tty mode:
     * - {@link com.android.internal.telephony.Phone#TTY_MODE_OFF}
     * - {@link com.android.internal.telephony.Phone#TTY_MODE_FULL}
     * - {@link com.android.internal.telephony.Phone#TTY_MODE_HCO}
     * - {@link com.android.internal.telephony.Phone#TTY_MODE_VCO}
     * @param response is callback message
     */
    void queryTTYMode(Message response);

    /**
     * Setup a packet data connection On successful completion, the result
     * message will return a {@link DataCallState} object containing the connection
     * information.
     *
     * @param radioTechnology
     *            indicates whether to setup connection on radio technology CDMA
     *            (0) or GSM/UMTS (1)
     * @param profile
     *            Profile Number or NULL to indicate default profile
     * @param apn
     *            the APN to connect to if radio technology is GSM/UMTS.
     *            Otherwise null for CDMA.
     * @param user
     *            the username for APN, or NULL
     * @param password
     *            the password for APN, or NULL
     * @param authType
     *            the PAP / CHAP auth type. Values is one of SETUP_DATA_AUTH_*
     * @param protocol
     *            one of the PDP_type values in TS 27.007 section 10.1.1.
     *            For example, "IP", "IPV6", "IPV4V6", or "PPP".
     * @param result
     *            Callback message
     */
    public void setupDataCall(String radioTechnology, String profile,
            String apn, String user, String password, String authType,
            String protocol, Message result);

    /**
     * Deactivate packet data connection
     *
     * @param cid
     *            The connection ID
     * @param reason
     *            Data disconnect reason.
     * @param result
     *            Callback message is empty on completion
     */
    public void deactivateDataCall(int cid, int reason, Message result);

    /**
     * Activate or deactivate cell broadcast SMS for CDMA.
     *
     * @param activate
     *            true = activate, false = deactivate
     * @param result
     *            Callback message is empty on completion
     */
    public void setCdmaBroadcastActivation(boolean activate, Message result);

    /**
     * Configure cdma cell broadcast SMS.
     *
     * @param result
     *            Callback message is empty on completion
     */
    // TODO: Change the configValuesArray to a RIL_BroadcastSMSConfig
    public void setCdmaBroadcastConfig(int[] configValuesArray, Message result);

    /**
     * Query the current configuration of cdma cell broadcast SMS.
     *
     * @param result
     *            Callback message contains the configuration from the modem on completion
     */
    public void getCdmaBroadcastConfig(Message result);

    /**
     *  Requests the radio's system selection module to exit emergency callback mode.
     *  This function should only be called from CDMAPHone.java.
     *
     * @param response callback message
     */
    public void exitEmergencyCallbackMode(Message response);

    /**
     * Request the status of the ICC and UICC cards.
     *
     * @param result
     *          Callback message containing {@link IccCardStatus} structure for the card.
     */
    public void getIccCardStatus(Message result);

    /**
     * Return if the current radio is LTE on CDMA. This
     * is a tri-state return value as for a period of time
     * the mode may be unknown.
     *
     * @return {@link Phone#LTE_ON_CDMA_UNKNOWN}, {@link Phone#LTE_ON_CDMA_FALSE}
     * or {@link Phone#LTE_ON_CDMA_TRUE}
     */
    public int getLteOnCdmaMode();

    /**
     * Request the ISIM application on the UICC to perform the AKA
     * challenge/response algorithm for IMS authentication. The nonce string
     * and challenge response are Base64 encoded Strings.
     *
     * @param nonce the nonce string to pass with the ISIM authentication request
     * @param response a callback message with the String response in the obj field
     */
    public void requestIsimAuthentication(String nonce, Message response);

    /**
     * Notifiy that we are testing an emergency call
     */
    public void testingEmergencyCall();
}
