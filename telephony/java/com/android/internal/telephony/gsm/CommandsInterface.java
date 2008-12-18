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

package com.android.internal.telephony.gsm;
import com.android.internal.telephony.*;
import android.os.Message;
import android.os.Handler;

/**
 * {@hide}
 */
public interface CommandsInterface
{
    enum RadioState {
        RADIO_OFF,         /* Radio explictly powered off (eg CFUN=0) */
        RADIO_UNAVAILABLE, /* Radio unavailable (eg, resetting or not booted) */
        SIM_NOT_READY,     /* Radio is on, but the SIM interface is not ready */
        SIM_LOCKED_OR_ABSENT,  /* SIM PIN locked, PUK required, network 
                               personalization, or SIM absent */
        SIM_READY;         /* Radio is on and SIM interface is available */

        boolean isOn() /* and available...*/
        {
            return this == SIM_NOT_READY
                    || this == SIM_LOCKED_OR_ABSENT
                    || this == SIM_READY;
        }

        boolean isAvailable()
        {
            return this != RADIO_UNAVAILABLE;
        }

        boolean isSIMReady()
        {
            // if you add new states after SIM_READY, include them too
            return this == SIM_READY;
        }
    }

    enum SimStatus {
        SIM_ABSENT,
        SIM_NOT_READY,
        SIM_READY,
        SIM_PIN,
        SIM_PUK,
        SIM_NETWORK_PERSONALIZATION
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
    static final int SERVICE_CLASS_DATA     = (1 << 1); //synoym for 16+32+64+128
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

    //***** Methods

    RadioState getRadioState();

    /** 
     * Fires on any RadioState transition 
     * Always fires immediately as well
     *
     * do not attempt to calculate transitions by storing getRadioState() values
     * on previous invocations of this notification. Instead, use the other
     * registration methods
     */
    void registerForRadioStateChanged(Handler h, int what, Object obj);

    /** 
     * Fires on any transition into RadioState.isOn() 
     * Fires immediately if currently in that state
     * In general, actions should be idempotent. State may change
     * before event is received.
     */
    void registerForOn(Handler h, int what, Object obj);

    /** 
     * Fires on any transition out of RadioState.isAvailable() 
     * Fires immediately if currently in that state
     * In general, actions should be idempotent. State may change
     * before event is received.
     */
    void registerForAvailable(Handler h, int what, Object obj);
    //void unregisterForAvailable(Handler h);
    /** 
     * Fires on any transition into !RadioState.isAvailable()
     * Fires immediately if currently in that state
     * In general, actions should be idempotent. State may change
     * before event is received.
     */
    void registerForNotAvailable(Handler h, int what, Object obj);
    //void unregisterForNotAvailable(Handler h);
    /** 
     * Fires on any transition into RADIO_OFF or !RadioState.isAvailable()
     * Fires immediately if currently in that state
     * In general, actions should be idempotent. State may change
     * before event is received.
     */
    void registerForOffOrNotAvailable(Handler h, int what, Object obj);
    //void unregisterForNotAvailable(Handler h);

    /** 
     * Fires on any transition into SIM_READY
     * Fires immediately if if currently in that state
     * In general, actions should be idempotent. State may change
     * before event is received.
     */
    void registerForSIMReady(Handler h, int what, Object obj);
    //void unregisterForSIMReady(Handler h);
    /** Any transition into SIM_LOCKED_OR_ABSENT */
    void registerForSIMLockedOrAbsent(Handler h, int what, Object obj);
    //void unregisterForSIMLockedOrAbsent(Handler h);

    void registerForCallStateChanged(Handler h, int what, Object obj);
    //void unregisterForCallStateChanged(Handler h);
    void registerForNetworkStateChanged(Handler h, int what, Object obj);
    //void unregisterForNetworkStateChanged(Handler h);
    void registerForPDPStateChanged(Handler h, int what, Object obj);
    //void unregisterForPDPStateChanged(Handler h);

    /**
     * unlike the register* methods, there's only one new SMS handler
     * if you need to unregister, you should also tell the radio to stop
     * sending SMS's to you (via AT+CNMI)
     *
     * AsyncResult.result is a String containing the SMS PDU
     */
    void setOnNewSMS(Handler h, int what, Object obj);

   /**
     * Register for NEW_SMS_ON_SIM unsolicited message 
     *
     * AsyncResult.result is an int array containing the index of new SMS
     */
    void setOnSmsOnSim(Handler h, int what, Object obj);

    /**
     * Register for NEW_SMS_STATUS_REPORT unsolicited message 
     *
     * AsyncResult.result is a String containing the status report PDU
     */
    void setOnSmsStatus(Handler h, int what, Object obj);
    
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

    /**
     * unlike the register* methods, there's only one signal strength handler
     * AsyncResult.result is an int[2]     
     * response.obj.result[0] is received signal strength (0-31, 99) 
     * response.obj.result[1] is  bit error rate (0-7, 99) 
     * as defined in TS 27.007 8.5
     */

    void setOnSignalStrengthUpdate(Handler h, int what, Object obj);

    /**
     * Sets the handler for SIM SMS storage full unsolicited message.
     * Unlike the register* methods, there's only one notification handler
     *
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    void setOnSimSmsFull(Handler h, int what, Object obj);

    /**
     * Sets the handler for SIM Refresh notifications.
     * Unlike the register* methods, there's only one notification handler
     *
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    void setOnSimRefresh(Handler h, int what, Object obj);
    
    /**
     * Sets the handler for RING notifications.
     * Unlike the register* methods, there's only one notification handler
     *
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    void setOnCallRing(Handler h, int what, Object obj);
    
    /**
     * Sets the handler for Supplementary Service Notifications.
     * Unlike the register* methods, there's only one notification handler
     *
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    void setOnSuppServiceNotification(Handler h, int what, Object obj);

    /**
     * Sets the handler for Session End Notifications for STK.
     * Unlike the register* methods, there's only one notification handler
     *
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    void setOnStkSessionEnd(Handler h, int what, Object obj);

    /**
     * Sets the handler for Proactive Commands for STK.
     * Unlike the register* methods, there's only one notification handler
     *
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    void setOnStkProactiveCmd(Handler h, int what, Object obj);

    /**
     * Sets the handler for Event Notifications for STK.
     * Unlike the register* methods, there's only one notification handler
     *
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    void setOnStkEvent(Handler h, int what, Object obj);

    /**
     * Sets the handler for Call Set Up Notifications for STK.
     * Unlike the register* methods, there's only one notification handler
     *
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    void setOnStkCallSetUp(Handler h, int what, Object obj);

    /**
     * Enables/disbables supplementary service related notifications from
     * the network.
     *
     * @param enable true to enable notifications, false to disable.
     * @param result Message to be posted when command completes.
     */
    void setSuppServiceNotifications(boolean enable, Message result);

    /**
     * Returns current SIM status.
     *
     * AsyncResult.result is SimStatus
     * 
     */

    void getSimStatus(Message result);    

    /**
     * Supply the SIM PIN to the SIM card
     * 
     *  returned message
     *  retMsg.obj = AsyncResult ar
     *  ar.exception carries exception on failure
     *  This exception is CommandException with an error of PASSWORD_INCORRECT
     *  if the password is incorrect
     *
     * ar.exception and ar.result are null on success
     */

    void supplySimPin(String pin, Message result);

    /**
     * Supply the SIM PUK to the SIM card
     * 
     *  returned message
     *  retMsg.obj = AsyncResult ar
     *  ar.exception carries exception on failure
     *  This exception is CommandException with an error of PASSWORD_INCORRECT
     *  if the password is incorrect
     *
     * ar.exception and ar.result are null on success
     */

    void supplySimPuk(String puk, String newPin, Message result);

    /**
     * Supply the SIM PIN2 to the SIM card
     * Only called following operation where SIM_PIN2 was
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

    void supplySimPin2(String pin2, Message result);

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

    void supplySimPuk2(String puk2, String newPin2, Message result);

    void changeSimPin(String oldPin, String newPin, Message result);
    void changeSimPin2(String oldPin2, String newPin2, Message result);

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
     *  ar.result contains a List of PDPContextState
     */
    void getPDPContextList(Message result);

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
     */
    void getLastPdpFailCause (Message result);

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
    void getRegistrationState (Message response);
                                                                                                            
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
    void getGPRSRegistrationState (Message response);

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
     * smscPDU is smsc address in PDU form GSM BCD format prefixed
     *      by a length byte (as expected by TS 27.005) or NULL for default SMSC
     * pdu is SMS in PDU format as an ASCII hex string
     *      less the SMSC address     
     */
    void sendSMS (String smscPDU, String pdu, Message response);

    /**
     * Deletes the specified SMS record from SIM memory (EF_SMS).
     * 
     * @param index index of the SMS record to delete
     * @param response sent when operation completes
     */
    void deleteSmsOnSim(int index, Message response);

    /**
     * Writes an SMS message to SIM memory (EF_SMS).
     * 
     * @param status status of message on SIM.  One of:
     *                  SmsManger.STATUS_ON_SIM_READ
     *                  SmsManger.STATUS_ON_SIM_UNREAD
     *                  SmsManger.STATUS_ON_SIM_SENT
     *                  SmsManger.STATUS_ON_SIM_UNSENT
     * @param pdu message PDU, as hex string
     * @param response sent when operation completes.
     *                  response.obj will be an AsyncResult, and will indicate
     *                  any error that may have occurred (eg, out of memory).
     */
    void writeSmsToSim(int status, String smsc, String pdu, Message response);

    void setupDefaultPDP(String apn, String user, String password, Message response);

    void deactivateDefaultPDP(int cid, Message response);

    void setRadioPower(boolean on, Message response);

    void acknowledgeLastIncomingSMS(boolean success, Message response);

    /** 
     * parameters equivilient to 27.007 AT+CRSM command 
     * response.obj will be an AsyncResult
     * response.obj.userObj will be a SimIoResult on success
     */
    void simIO (int command, int fileid, String path, int p1, int p2, int p3, 
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
     * the sum of enabled serivice classes (sum of SERVICE_CLASS_*)
     *
     * @param facility one of CB_FACILTY_*
     * @param password password or "" if not required
     * @param serviceClass is a sum of SERVICE_CLASS_*
     * @param response is callback message
     */
    
    void queryFacilityLock (String facility, String password, int serviceClass,
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
     * location informateion (lac and/or cid) has changed.
     *
     * @param enable true to enable, false to disable
     * @param response callback message
     */
    void setLocationUpdates(boolean enable, Message response);


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
     * Accept or reject the call setup request from SIM.
     *
     * @param accept   true if the call is to be accepted, false otherwise.
     * @param response Callback message
     */
    public void handleCallSetupRequestFromSim(boolean accept, Message response);
}
