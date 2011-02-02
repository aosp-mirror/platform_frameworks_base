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

import android.content.Context;
import com.android.internal.telephony.*;

import android.os.*;
import android.telephony.PhoneNumberUtils;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.util.Log;

import static com.android.internal.telephony.CommandsInterface.*;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * The motto for this file is:
 *
 * "NOTE:    By using the # as a separator, most cases are expected to be unambiguous."
 *   -- TS 22.030 6.5.2
 *
 * {@hide}
 *
 */
public final class GsmMmiCode extends Handler implements MmiCode {
    static final String LOG_TAG = "GSM";

    //***** Constants

    // From TS 22.030 6.5.2
    static final String ACTION_ACTIVATE = "*";
    static final String ACTION_DEACTIVATE = "#";
    static final String ACTION_INTERROGATE = "*#";
    static final String ACTION_REGISTER = "**";
    static final String ACTION_ERASURE = "##";

    // Supp Service codes from TS 22.030 Annex B

    //Called line presentation
    static final String SC_CLIP    = "30";
    static final String SC_CLIR    = "31";

    // Call Forwarding
    static final String SC_CFU     = "21";
    static final String SC_CFB     = "67";
    static final String SC_CFNRy   = "61";
    static final String SC_CFNR    = "62";

    static final String SC_CF_All = "002";
    static final String SC_CF_All_Conditional = "004";

    // Call Waiting
    static final String SC_WAIT     = "43";

    // Call Barring
    static final String SC_BAOC         = "33";
    static final String SC_BAOIC        = "331";
    static final String SC_BAOICxH      = "332";
    static final String SC_BAIC         = "35";
    static final String SC_BAICr        = "351";

    static final String SC_BA_ALL       = "330";
    static final String SC_BA_MO        = "333";
    static final String SC_BA_MT        = "353";

    // Supp Service Password registration
    static final String SC_PWD          = "03";

    // PIN/PIN2/PUK/PUK2
    static final String SC_PIN          = "04";
    static final String SC_PIN2         = "042";
    static final String SC_PUK          = "05";
    static final String SC_PUK2         = "052";

    //***** Event Constants

    static final int EVENT_SET_COMPLETE         = 1;
    static final int EVENT_GET_CLIR_COMPLETE    = 2;
    static final int EVENT_QUERY_CF_COMPLETE    = 3;
    static final int EVENT_USSD_COMPLETE        = 4;
    static final int EVENT_QUERY_COMPLETE       = 5;
    static final int EVENT_SET_CFF_COMPLETE     = 6;
    static final int EVENT_USSD_CANCEL_COMPLETE = 7;

    //***** Instance Variables

    GSMPhone phone;
    Context context;

    String action;              // One of ACTION_*
    String sc;                  // Service Code
    String sia, sib, sic;       // Service Info a,b,c
    String poundString;         // Entire MMI string up to and including #
    String dialingNumber;
    String pwd;                 // For password registration

    /** Set to true in processCode, not at newFromDialString time */
    private boolean isPendingUSSD;

    private boolean isUssdRequest;

    State state = State.PENDING;
    CharSequence message;

    //***** Class Variables


    // See TS 22.030 6.5.2 "Structure of the MMI"

    static Pattern sPatternSuppService = Pattern.compile(
        "((\\*|#|\\*#|\\*\\*|##)(\\d{2,3})(\\*([^*#]*)(\\*([^*#]*)(\\*([^*#]*)(\\*([^*#]*))?)?)?)?#)(.*)");
/*       1  2                    3          4  5       6   7         8    9     10  11             12

         1 = Full string up to and including #
         2 = action (activation/interrogation/registration/erasure)
         3 = service code
         5 = SIA
         7 = SIB
         9 = SIC
         10 = dialing number
*/

    static final int MATCH_GROUP_POUND_STRING = 1;

    static final int MATCH_GROUP_ACTION = 2;
                        //(activation/interrogation/registration/erasure)

    static final int MATCH_GROUP_SERVICE_CODE = 3;
    static final int MATCH_GROUP_SIA = 5;
    static final int MATCH_GROUP_SIB = 7;
    static final int MATCH_GROUP_SIC = 9;
    static final int MATCH_GROUP_PWD_CONFIRM = 11;
    static final int MATCH_GROUP_DIALING_NUMBER = 12;
    static private String[] sTwoDigitNumberPattern;

    //***** Public Class methods

    /**
     * Some dial strings in GSM are defined to do non-call setup
     * things, such as modify or query supplementary service settings (eg, call
     * forwarding). These are generally referred to as "MMI codes".
     * We look to see if the dial string contains a valid MMI code (potentially
     * with a dial string at the end as well) and return info here.
     *
     * If the dial string contains no MMI code, we return an instance with
     * only "dialingNumber" set
     *
     * Please see flow chart in TS 22.030 6.5.3.2
     */

    static GsmMmiCode
    newFromDialString(String dialString, GSMPhone phone) {
        Matcher m;
        GsmMmiCode ret = null;

        m = sPatternSuppService.matcher(dialString);

        // Is this formatted like a standard supplementary service code?
        if (m.matches()) {
            ret = new GsmMmiCode(phone);
            ret.poundString = makeEmptyNull(m.group(MATCH_GROUP_POUND_STRING));
            ret.action = makeEmptyNull(m.group(MATCH_GROUP_ACTION));
            ret.sc = makeEmptyNull(m.group(MATCH_GROUP_SERVICE_CODE));
            ret.sia = makeEmptyNull(m.group(MATCH_GROUP_SIA));
            ret.sib = makeEmptyNull(m.group(MATCH_GROUP_SIB));
            ret.sic = makeEmptyNull(m.group(MATCH_GROUP_SIC));
            ret.pwd = makeEmptyNull(m.group(MATCH_GROUP_PWD_CONFIRM));
            ret.dialingNumber = makeEmptyNull(m.group(MATCH_GROUP_DIALING_NUMBER));

        } else if (dialString.endsWith("#")) {
            // TS 22.030 sec 6.5.3.2
            // "Entry of any characters defined in the 3GPP TS 23.038 [8] Default Alphabet
            // (up to the maximum defined in 3GPP TS 24.080 [10]), followed by #SEND".

            ret = new GsmMmiCode(phone);
            ret.poundString = dialString;
        } else if (isTwoDigitShortCode(phone.getContext(), dialString)) {
            //Is a country-specific exception to short codes as defined in TS 22.030, 6.5.3.2
            ret = null;
        } else if (isShortCode(dialString, phone)) {
            // this may be a short code, as defined in TS 22.030, 6.5.3.2
            ret = new GsmMmiCode(phone);
            ret.dialingNumber = dialString;
        }

        return ret;
    }

    static GsmMmiCode
    newNetworkInitiatedUssd (String ussdMessage,
                                boolean isUssdRequest, GSMPhone phone) {
        GsmMmiCode ret;

        ret = new GsmMmiCode(phone);

        ret.message = ussdMessage;
        ret.isUssdRequest = isUssdRequest;

        // If it's a request, set to PENDING so that it's cancelable.
        if (isUssdRequest) {
            ret.isPendingUSSD = true;
            ret.state = State.PENDING;
        } else {
            ret.state = State.COMPLETE;
        }

        return ret;
    }

    static GsmMmiCode newFromUssdUserInput(String ussdMessge, GSMPhone phone) {
        GsmMmiCode ret = new GsmMmiCode(phone);

        ret.message = ussdMessge;
        ret.state = State.PENDING;
        ret.isPendingUSSD = true;

        return ret;
    }

    //***** Private Class methods

    /** make empty strings be null.
     *  Regexp returns empty strings for empty groups
     */
    private static String
    makeEmptyNull (String s) {
        if (s != null && s.length() == 0) return null;

        return s;
    }

    /** returns true of the string is empty or null */
    private static boolean
    isEmptyOrNull(CharSequence s) {
        return s == null || (s.length() == 0);
    }


    private static int
    scToCallForwardReason(String sc) {
        if (sc == null) {
            throw new RuntimeException ("invalid call forward sc");
        }

        if (sc.equals(SC_CF_All)) {
           return CommandsInterface.CF_REASON_ALL;
        } else if (sc.equals(SC_CFU)) {
            return CommandsInterface.CF_REASON_UNCONDITIONAL;
        } else if (sc.equals(SC_CFB)) {
            return CommandsInterface.CF_REASON_BUSY;
        } else if (sc.equals(SC_CFNR)) {
            return CommandsInterface.CF_REASON_NOT_REACHABLE;
        } else if (sc.equals(SC_CFNRy)) {
            return CommandsInterface.CF_REASON_NO_REPLY;
        } else if (sc.equals(SC_CF_All_Conditional)) {
           return CommandsInterface.CF_REASON_ALL_CONDITIONAL;
        } else {
            throw new RuntimeException ("invalid call forward sc");
        }
    }

    private static int
    siToServiceClass(String si) {
        if (si == null || si.length() == 0) {
                return  SERVICE_CLASS_NONE;
        } else {
            // NumberFormatException should cause MMI fail
            int serviceCode = Integer.parseInt(si, 10);

            switch (serviceCode) {
                case 10: return SERVICE_CLASS_SMS + SERVICE_CLASS_FAX  + SERVICE_CLASS_VOICE;
                case 11: return SERVICE_CLASS_VOICE;
                case 12: return SERVICE_CLASS_SMS + SERVICE_CLASS_FAX;
                case 13: return SERVICE_CLASS_FAX;

                case 16: return SERVICE_CLASS_SMS;

                case 19: return SERVICE_CLASS_FAX + SERVICE_CLASS_VOICE;
/*
    Note for code 20:
     From TS 22.030 Annex C:
                "All GPRS bearer services" are not included in "All tele and bearer services"
                    and "All bearer services"."
....so SERVICE_CLASS_DATA, which (according to 27.007) includes GPRS
*/
                case 20: return SERVICE_CLASS_DATA_ASYNC + SERVICE_CLASS_DATA_SYNC;

                case 21: return SERVICE_CLASS_PAD + SERVICE_CLASS_DATA_ASYNC;
                case 22: return SERVICE_CLASS_PACKET + SERVICE_CLASS_DATA_SYNC;
                case 24: return SERVICE_CLASS_DATA_SYNC;
                case 25: return SERVICE_CLASS_DATA_ASYNC;
                case 26: return SERVICE_CLASS_DATA_SYNC + SERVICE_CLASS_VOICE;
                case 99: return SERVICE_CLASS_PACKET;

                default:
                    throw new RuntimeException("unsupported MMI service code " + si);
            }
        }
    }

    private static int
    siToTime (String si) {
        if (si == null || si.length() == 0) {
            return 0;
        } else {
            // NumberFormatException should cause MMI fail
            return Integer.parseInt(si, 10);
        }
    }

    static boolean
    isServiceCodeCallForwarding(String sc) {
        return sc != null &&
                (sc.equals(SC_CFU)
                || sc.equals(SC_CFB) || sc.equals(SC_CFNRy)
                || sc.equals(SC_CFNR) || sc.equals(SC_CF_All)
                || sc.equals(SC_CF_All_Conditional));
    }

    static boolean
    isServiceCodeCallBarring(String sc) {
        return sc != null &&
                (sc.equals(SC_BAOC)
                || sc.equals(SC_BAOIC)
                || sc.equals(SC_BAOICxH)
                || sc.equals(SC_BAIC)
                || sc.equals(SC_BAICr)
                || sc.equals(SC_BA_ALL)
                || sc.equals(SC_BA_MO)
                || sc.equals(SC_BA_MT));
    }

    static String
    scToBarringFacility(String sc) {
        if (sc == null) {
            throw new RuntimeException ("invalid call barring sc");
        }

        if (sc.equals(SC_BAOC)) {
            return CommandsInterface.CB_FACILITY_BAOC;
        } else if (sc.equals(SC_BAOIC)) {
            return CommandsInterface.CB_FACILITY_BAOIC;
        } else if (sc.equals(SC_BAOICxH)) {
            return CommandsInterface.CB_FACILITY_BAOICxH;
        } else if (sc.equals(SC_BAIC)) {
            return CommandsInterface.CB_FACILITY_BAIC;
        } else if (sc.equals(SC_BAICr)) {
            return CommandsInterface.CB_FACILITY_BAICr;
        } else if (sc.equals(SC_BA_ALL)) {
            return CommandsInterface.CB_FACILITY_BA_ALL;
        } else if (sc.equals(SC_BA_MO)) {
            return CommandsInterface.CB_FACILITY_BA_MO;
        } else if (sc.equals(SC_BA_MT)) {
            return CommandsInterface.CB_FACILITY_BA_MT;
        } else {
            throw new RuntimeException ("invalid call barring sc");
        }
    }

    //***** Constructor

    GsmMmiCode (GSMPhone phone) {
        // The telephony unit-test cases may create GsmMmiCode's
        // in secondary threads
        super(phone.getHandler().getLooper());
        this.phone = phone;
        this.context = phone.getContext();
    }

    //***** MmiCode implementation

    public State
    getState() {
        return state;
    }

    public CharSequence
    getMessage() {
        return message;
    }

    // inherited javadoc suffices
    public void
    cancel() {
        // Complete or failed cannot be cancelled
        if (state == State.COMPLETE || state == State.FAILED) {
            return;
        }

        state = State.CANCELLED;

        if (isPendingUSSD) {
            /*
             * There can only be one pending USSD session, so tell the radio to
             * cancel it.
             */
            phone.mCM.cancelPendingUssd(obtainMessage(EVENT_USSD_CANCEL_COMPLETE, this));

            /*
             * Don't call phone.onMMIDone here; wait for CANCEL_COMPLETE notice
             * from RIL.
             */
        } else {
            // TODO in cases other than USSD, it would be nice to cancel
            // the pending radio operation. This requires RIL cancellation
            // support, which does not presently exist.

            phone.onMMIDone (this);
        }

    }

    public boolean isCancelable() {
        /* Can only cancel pending USSD sessions. */
        return isPendingUSSD;
    }

    //***** Instance Methods

    /** Does this dial string contain a structured or unstructured MMI code? */
    boolean
    isMMI() {
        return poundString != null;
    }

    /* Is this a 1 or 2 digit "short code" as defined in TS 22.030 sec 6.5.3.2? */
    boolean
    isShortCode() {
        return poundString == null
                    && dialingNumber != null && dialingNumber.length() <= 2;

    }

    static private boolean
    isTwoDigitShortCode(Context context, String dialString) {
        Log.d(LOG_TAG, "isTwoDigitShortCode");

        if (dialString == null || dialString.length() != 2) return false;

        if (sTwoDigitNumberPattern == null) {
            sTwoDigitNumberPattern = context.getResources().getStringArray(
                    com.android.internal.R.array.config_twoDigitNumberPattern);
        }

        for (String dialnumber : sTwoDigitNumberPattern) {
            Log.d(LOG_TAG, "Two Digit Number Pattern " + dialnumber);
            if (dialString.equals(dialnumber)) {
                Log.d(LOG_TAG, "Two Digit Number Pattern -true");
                return true;
            }
        }
        Log.d(LOG_TAG, "Two Digit Number Pattern -false");
        return false;
    }

    /**
     * Helper function for newFromDialString.  Returns true if dialString appears to be a short code
     * AND conditions are correct for it to be treated as such.
     */
    static private boolean isShortCode(String dialString, GSMPhone phone) {
        // Refer to TS 22.030 Figure 3.5.3.2:
        // A 1 or 2 digit "short code" is treated as USSD if it is entered while on a call or
        // does not satisfy the condition (exactly 2 digits && starts with '1').
        return ((dialString != null && dialString.length() <= 2)
                && !PhoneNumberUtils.isEmergencyNumber(dialString)
                && (phone.isInCall()
                    || !((dialString.length() == 2 && dialString.charAt(0) == '1')
                         /* While contrary to TS 22.030, there is strong precedence
                          * for treating "0" and "00" as call setup strings.
                          */
                         || dialString.equals("0")
                         || dialString.equals("00"))));
    }

    /**
     * @return true if the Service Code is PIN/PIN2/PUK/PUK2-related
     */
    boolean isPinCommand() {
        return sc != null && (sc.equals(SC_PIN) || sc.equals(SC_PIN2)
                              || sc.equals(SC_PUK) || sc.equals(SC_PUK2));
     }

    /**
     * See TS 22.030 Annex B.
     * In temporary mode, to suppress CLIR for a single call, enter:
     *      " * 31 # [called number] SEND "
     *  In temporary mode, to invoke CLIR for a single call enter:
     *       " # 31 # [called number] SEND "
     */
    boolean
    isTemporaryModeCLIR() {
        return sc != null && sc.equals(SC_CLIR) && dialingNumber != null
                && (isActivate() || isDeactivate());
    }

    /**
     * returns CommandsInterface.CLIR_*
     * See also isTemporaryModeCLIR()
     */
    int
    getCLIRMode() {
        if (sc != null && sc.equals(SC_CLIR)) {
            if (isActivate()) {
                return CommandsInterface.CLIR_SUPPRESSION;
            } else if (isDeactivate()) {
                return CommandsInterface.CLIR_INVOCATION;
            }
        }

        return CommandsInterface.CLIR_DEFAULT;
    }

    boolean isActivate() {
        return action != null && action.equals(ACTION_ACTIVATE);
    }

    boolean isDeactivate() {
        return action != null && action.equals(ACTION_DEACTIVATE);
    }

    boolean isInterrogate() {
        return action != null && action.equals(ACTION_INTERROGATE);
    }

    boolean isRegister() {
        return action != null && action.equals(ACTION_REGISTER);
    }

    boolean isErasure() {
        return action != null && action.equals(ACTION_ERASURE);
    }

    /**
     * Returns true if this is a USSD code that's been submitted to the
     * network...eg, after processCode() is called
     */
    public boolean isPendingUSSD() {
        return isPendingUSSD;
    }

    public boolean isUssdRequest() {
        return isUssdRequest;
    }

    /** Process a MMI code or short code...anything that isn't a dialing number */
    void
    processCode () {
        try {
            if (isShortCode()) {
                Log.d(LOG_TAG, "isShortCode");
                // These just get treated as USSD.
                sendUssd(dialingNumber);
            } else if (dialingNumber != null) {
                // We should have no dialing numbers here
                throw new RuntimeException ("Invalid or Unsupported MMI Code");
            } else if (sc != null && sc.equals(SC_CLIP)) {
                Log.d(LOG_TAG, "is CLIP");
                if (isInterrogate()) {
                    phone.mCM.queryCLIP(
                            obtainMessage(EVENT_QUERY_COMPLETE, this));
                } else {
                    throw new RuntimeException ("Invalid or Unsupported MMI Code");
                }
            } else if (sc != null && sc.equals(SC_CLIR)) {
                Log.d(LOG_TAG, "is CLIR");
                if (isActivate()) {
                    phone.mCM.setCLIR(CommandsInterface.CLIR_INVOCATION,
                        obtainMessage(EVENT_SET_COMPLETE, this));
                } else if (isDeactivate()) {
                    phone.mCM.setCLIR(CommandsInterface.CLIR_SUPPRESSION,
                        obtainMessage(EVENT_SET_COMPLETE, this));
                } else if (isInterrogate()) {
                    phone.mCM.getCLIR(
                        obtainMessage(EVENT_GET_CLIR_COMPLETE, this));
                } else {
                    throw new RuntimeException ("Invalid or Unsupported MMI Code");
                }
            } else if (isServiceCodeCallForwarding(sc)) {
                Log.d(LOG_TAG, "is CF");

                String dialingNumber = sia;
                int serviceClass = siToServiceClass(sib);
                int reason = scToCallForwardReason(sc);
                int time = siToTime(sic);

                if (isInterrogate()) {
                    phone.mCM.queryCallForwardStatus(
                            reason, serviceClass,  dialingNumber,
                                obtainMessage(EVENT_QUERY_CF_COMPLETE, this));
                } else {
                    int cfAction;

                    if (isActivate()) {
                        cfAction = CommandsInterface.CF_ACTION_ENABLE;
                    } else if (isDeactivate()) {
                        cfAction = CommandsInterface.CF_ACTION_DISABLE;
                    } else if (isRegister()) {
                        cfAction = CommandsInterface.CF_ACTION_REGISTRATION;
                    } else if (isErasure()) {
                        cfAction = CommandsInterface.CF_ACTION_ERASURE;
                    } else {
                        throw new RuntimeException ("invalid action");
                    }

                    int isSettingUnconditionalVoice =
                        (((reason == CommandsInterface.CF_REASON_UNCONDITIONAL) ||
                                (reason == CommandsInterface.CF_REASON_ALL)) &&
                                (((serviceClass & CommandsInterface.SERVICE_CLASS_VOICE) != 0) ||
                                 (serviceClass == CommandsInterface.SERVICE_CLASS_NONE))) ? 1 : 0;

                    int isEnableDesired =
                        ((cfAction == CommandsInterface.CF_ACTION_ENABLE) ||
                                (cfAction == CommandsInterface.CF_ACTION_REGISTRATION)) ? 1 : 0;

                    Log.d(LOG_TAG, "is CF setCallForward");
                    phone.mCM.setCallForward(cfAction, reason, serviceClass,
                            dialingNumber, time, obtainMessage(
                                    EVENT_SET_CFF_COMPLETE,
                                    isSettingUnconditionalVoice,
                                    isEnableDesired, this));
                }
            } else if (isServiceCodeCallBarring(sc)) {
                // sia = password
                // sib = basic service group

                String password = sia;
                int serviceClass = siToServiceClass(sib);
                String facility = scToBarringFacility(sc);

                if (isInterrogate()) {
                    phone.mCM.queryFacilityLock(facility, password,
                            serviceClass, obtainMessage(EVENT_QUERY_COMPLETE, this));
                } else if (isActivate() || isDeactivate()) {
                    phone.mCM.setFacilityLock(facility, isActivate(), password,
                            serviceClass, obtainMessage(EVENT_SET_COMPLETE, this));
                } else {
                    throw new RuntimeException ("Invalid or Unsupported MMI Code");
                }

            } else if (sc != null && sc.equals(SC_PWD)) {
                // sia = fac
                // sib = old pwd
                // sic = new pwd
                // pwd = new pwd
                String facility;
                String oldPwd = sib;
                String newPwd = sic;
                if (isActivate() || isRegister()) {
                    // Even though ACTIVATE is acceptable, this is really termed a REGISTER
                    action = ACTION_REGISTER;

                    if (sia == null) {
                        // If sc was not specified, treat it as BA_ALL.
                        facility = CommandsInterface.CB_FACILITY_BA_ALL;
                    } else {
                        facility = scToBarringFacility(sia);
                    }
                    if (newPwd.equals(pwd)) {
                        phone.mCM.changeBarringPassword(facility, oldPwd,
                                newPwd, obtainMessage(EVENT_SET_COMPLETE, this));
                    } else {
                        // password mismatch; return error
                        handlePasswordError(com.android.internal.R.string.passwordIncorrect);
                    }
                } else {
                    throw new RuntimeException ("Invalid or Unsupported MMI Code");
                }

            } else if (sc != null && sc.equals(SC_WAIT)) {
                // sia = basic service group
                int serviceClass = siToServiceClass(sia);

                if (isActivate() || isDeactivate()) {
                    phone.mCM.setCallWaiting(isActivate(), serviceClass,
                            obtainMessage(EVENT_SET_COMPLETE, this));
                } else if (isInterrogate()) {
                    phone.mCM.queryCallWaiting(serviceClass,
                            obtainMessage(EVENT_QUERY_COMPLETE, this));
                } else {
                    throw new RuntimeException ("Invalid or Unsupported MMI Code");
                }
            } else if (isPinCommand()) {
                // sia = old PIN or PUK
                // sib = new PIN
                // sic = new PIN
                String oldPinOrPuk = sia;
                String newPin = sib;
                int pinLen = newPin.length();
                if (isRegister()) {
                    if (!newPin.equals(sic)) {
                        // password mismatch; return error
                        handlePasswordError(com.android.internal.R.string.mismatchPin);
                    } else if (pinLen < 4 || pinLen > 8 ) {
                        // invalid length
                        handlePasswordError(com.android.internal.R.string.invalidPin);
                    } else if (sc.equals(SC_PIN) &&
                               phone.mSimCard.getState() == SimCard.State.PUK_REQUIRED ) {
                        // Sim is puk-locked
                        handlePasswordError(com.android.internal.R.string.needPuk);
                    } else {
                        // pre-checks OK
                        if (sc.equals(SC_PIN)) {
                            phone.mCM.changeIccPin(oldPinOrPuk, newPin,
                                    obtainMessage(EVENT_SET_COMPLETE, this));
                        } else if (sc.equals(SC_PIN2)) {
                            phone.mCM.changeIccPin2(oldPinOrPuk, newPin,
                                    obtainMessage(EVENT_SET_COMPLETE, this));
                        } else if (sc.equals(SC_PUK)) {
                            phone.mCM.supplyIccPuk(oldPinOrPuk, newPin,
                                    obtainMessage(EVENT_SET_COMPLETE, this));
                        } else if (sc.equals(SC_PUK2)) {
                            phone.mCM.supplyIccPuk2(oldPinOrPuk, newPin,
                                    obtainMessage(EVENT_SET_COMPLETE, this));
                        }
                    }
                } else {
                    throw new RuntimeException ("Invalid or Unsupported MMI Code");
                }
            } else if (poundString != null) {
                sendUssd(poundString);
            } else {
                throw new RuntimeException ("Invalid or Unsupported MMI Code");
            }
        } catch (RuntimeException exc) {
            state = State.FAILED;
            message = context.getText(com.android.internal.R.string.mmiError);
            phone.onMMIDone(this);
        }
    }

    private void handlePasswordError(int res) {
        state = State.FAILED;
        StringBuilder sb = new StringBuilder(getScString());
        sb.append("\n");
        sb.append(context.getText(res));
        message = sb;
        phone.onMMIDone(this);
    }

    /**
     * Called from GSMPhone
     *
     * An unsolicited USSD NOTIFY or REQUEST has come in matching
     * up with this pending USSD request
     *
     * Note: If REQUEST, this exchange is complete, but the session remains
     *       active (ie, the network expects user input).
     */
    void
    onUssdFinished(String ussdMessage, boolean isUssdRequest) {
        if (state == State.PENDING) {
            if (ussdMessage == null) {
                message = context.getText(com.android.internal.R.string.mmiComplete);
            } else {
                message = ussdMessage;
            }
            this.isUssdRequest = isUssdRequest;
            // If it's a request, leave it PENDING so that it's cancelable.
            if (!isUssdRequest) {
                state = State.COMPLETE;
            }

            phone.onMMIDone(this);
        }
    }

    /**
     * Called from GSMPhone
     *
     * The radio has reset, and this is still pending
     */

    void
    onUssdFinishedError() {
        if (state == State.PENDING) {
            state = State.FAILED;
            message = context.getText(com.android.internal.R.string.mmiError);

            phone.onMMIDone(this);
        }
    }

    void sendUssd(String ussdMessage) {
        // Treat this as a USSD string
        isPendingUSSD = true;

        // Note that unlike most everything else, the USSD complete
        // response does not complete this MMI code...we wait for
        // an unsolicited USSD "Notify" or "Request".
        // The matching up of this is done in GSMPhone.

        phone.mCM.sendUSSD(ussdMessage,
            obtainMessage(EVENT_USSD_COMPLETE, this));
    }

    /** Called from GSMPhone.handleMessage; not a Handler subclass */
    public void
    handleMessage (Message msg) {
        AsyncResult ar;

        switch (msg.what) {
            case EVENT_SET_COMPLETE:
                ar = (AsyncResult) (msg.obj);

                onSetComplete(ar);
                break;

            case EVENT_SET_CFF_COMPLETE:
                ar = (AsyncResult) (msg.obj);

                /*
                * msg.arg1 = 1 means to set unconditional voice call forwarding
                * msg.arg2 = 1 means to enable voice call forwarding
                */
                if ((ar.exception == null) && (msg.arg1 == 1)) {
                    boolean cffEnabled = (msg.arg2 == 1);
                    phone.mSIMRecords.setVoiceCallForwardingFlag(1, cffEnabled);
                }

                onSetComplete(ar);
                break;

            case EVENT_GET_CLIR_COMPLETE:
                ar = (AsyncResult) (msg.obj);
                onGetClirComplete(ar);
            break;

            case EVENT_QUERY_CF_COMPLETE:
                ar = (AsyncResult) (msg.obj);
                onQueryCfComplete(ar);
            break;

            case EVENT_QUERY_COMPLETE:
                ar = (AsyncResult) (msg.obj);
                onQueryComplete(ar);
            break;

            case EVENT_USSD_COMPLETE:
                ar = (AsyncResult) (msg.obj);

                if (ar.exception != null) {
                    state = State.FAILED;
                    message = getErrorMessage(ar);

                    phone.onMMIDone(this);
                }

                // Note that unlike most everything else, the USSD complete
                // response does not complete this MMI code...we wait for
                // an unsolicited USSD "Notify" or "Request".
                // The matching up of this is done in GSMPhone.

            break;

            case EVENT_USSD_CANCEL_COMPLETE:
                phone.onMMIDone(this);
            break;
        }
    }
    //***** Private instance methods

    private CharSequence getErrorMessage(AsyncResult ar) {

        if (ar.exception instanceof CommandException) {
            CommandException.Error err = ((CommandException)(ar.exception)).getCommandError();
            if (err == CommandException.Error.FDN_CHECK_FAILURE) {
                Log.i(LOG_TAG, "FDN_CHECK_FAILURE");
                return context.getText(com.android.internal.R.string.mmiFdnError);
            }
        }

        return context.getText(com.android.internal.R.string.mmiError);
    }

    private CharSequence getScString() {
        if (sc != null) {
            if (isServiceCodeCallBarring(sc)) {
                return context.getText(com.android.internal.R.string.BaMmi);
            } else if (isServiceCodeCallForwarding(sc)) {
                return context.getText(com.android.internal.R.string.CfMmi);
            } else if (sc.equals(SC_CLIP)) {
                return context.getText(com.android.internal.R.string.ClipMmi);
            } else if (sc.equals(SC_CLIR)) {
                return context.getText(com.android.internal.R.string.ClirMmi);
            } else if (sc.equals(SC_PWD)) {
                return context.getText(com.android.internal.R.string.PwdMmi);
            } else if (sc.equals(SC_WAIT)) {
                return context.getText(com.android.internal.R.string.CwMmi);
            } else if (isPinCommand()) {
                return context.getText(com.android.internal.R.string.PinMmi);
            }
        }

        return "";
    }

    private void
    onSetComplete(AsyncResult ar){
        StringBuilder sb = new StringBuilder(getScString());
        sb.append("\n");

        if (ar.exception != null) {
            state = State.FAILED;
            if (ar.exception instanceof CommandException) {
                CommandException.Error err = ((CommandException)(ar.exception)).getCommandError();
                if (err == CommandException.Error.PASSWORD_INCORRECT) {
                    if (isPinCommand()) {
                        // look specifically for the PUK commands and adjust
                        // the message accordingly.
                        if (sc.equals(SC_PUK) || sc.equals(SC_PUK2)) {
                            sb.append(context.getText(
                                    com.android.internal.R.string.badPuk));
                        } else {
                            sb.append(context.getText(
                                    com.android.internal.R.string.badPin));
                        }
                    } else {
                        sb.append(context.getText(
                                com.android.internal.R.string.passwordIncorrect));
                    }
                } else if (err == CommandException.Error.SIM_PUK2) {
                    sb.append(context.getText(
                            com.android.internal.R.string.badPin));
                    sb.append("\n");
                    sb.append(context.getText(
                            com.android.internal.R.string.needPuk2));
                } else if (err == CommandException.Error.FDN_CHECK_FAILURE) {
                    Log.i(LOG_TAG, "FDN_CHECK_FAILURE");
                    sb.append(context.getText(com.android.internal.R.string.mmiFdnError));
                } else {
                    sb.append(context.getText(
                            com.android.internal.R.string.mmiError));
                }
            } else {
                sb.append(context.getText(
                        com.android.internal.R.string.mmiError));
            }
        } else if (isActivate()) {
            state = State.COMPLETE;
            sb.append(context.getText(
                    com.android.internal.R.string.serviceEnabled));
            // Record CLIR setting
            if (sc.equals(SC_CLIR)) {
                phone.saveClirSetting(CommandsInterface.CLIR_INVOCATION);
            }
        } else if (isDeactivate()) {
            state = State.COMPLETE;
            sb.append(context.getText(
                    com.android.internal.R.string.serviceDisabled));
            // Record CLIR setting
            if (sc.equals(SC_CLIR)) {
                phone.saveClirSetting(CommandsInterface.CLIR_SUPPRESSION);
            }
        } else if (isRegister()) {
            state = State.COMPLETE;
            sb.append(context.getText(
                    com.android.internal.R.string.serviceRegistered));
        } else if (isErasure()) {
            state = State.COMPLETE;
            sb.append(context.getText(
                    com.android.internal.R.string.serviceErased));
        } else {
            state = State.FAILED;
            sb.append(context.getText(
                    com.android.internal.R.string.mmiError));
        }

        message = sb;
        phone.onMMIDone(this);
    }

    private void
    onGetClirComplete(AsyncResult ar) {
        StringBuilder sb = new StringBuilder(getScString());
        sb.append("\n");

        if (ar.exception != null) {
            state = State.FAILED;
            sb.append(getErrorMessage(ar));
        } else {
            int clirArgs[];

            clirArgs = (int[])ar.result;

            // the 'm' parameter from TS 27.007 7.7
            switch (clirArgs[1]) {
                case 0: // CLIR not provisioned
                    sb.append(context.getText(
                                com.android.internal.R.string.serviceNotProvisioned));
                    state = State.COMPLETE;
                break;

                case 1: // CLIR provisioned in permanent mode
                    sb.append(context.getText(
                                com.android.internal.R.string.CLIRPermanent));
                    state = State.COMPLETE;
                break;

                case 2: // unknown (e.g. no network, etc.)
                    sb.append(context.getText(
                                com.android.internal.R.string.mmiError));
                    state = State.FAILED;
                break;

                case 3: // CLIR temporary mode presentation restricted

                    // the 'n' parameter from TS 27.007 7.7
                    switch (clirArgs[0]) {
                        default:
                        case 0: // Default
                            sb.append(context.getText(
                                    com.android.internal.R.string.CLIRDefaultOnNextCallOn));
                        break;
                        case 1: // CLIR invocation
                            sb.append(context.getText(
                                    com.android.internal.R.string.CLIRDefaultOnNextCallOn));
                        break;
                        case 2: // CLIR suppression
                            sb.append(context.getText(
                                    com.android.internal.R.string.CLIRDefaultOnNextCallOff));
                        break;
                    }
                    state = State.COMPLETE;
                break;

                case 4: // CLIR temporary mode presentation allowed
                    // the 'n' parameter from TS 27.007 7.7
                    switch (clirArgs[0]) {
                        default:
                        case 0: // Default
                            sb.append(context.getText(
                                    com.android.internal.R.string.CLIRDefaultOffNextCallOff));
                        break;
                        case 1: // CLIR invocation
                            sb.append(context.getText(
                                    com.android.internal.R.string.CLIRDefaultOffNextCallOn));
                        break;
                        case 2: // CLIR suppression
                            sb.append(context.getText(
                                    com.android.internal.R.string.CLIRDefaultOffNextCallOff));
                        break;
                    }

                    state = State.COMPLETE;
                break;
            }
        }

        message = sb;
        phone.onMMIDone(this);
    }

    /**
     * @param serviceClass 1 bit of the service class bit vectory
     * @return String to be used for call forward query MMI response text.
     *        Returns null if unrecognized
     */

    private CharSequence
    serviceClassToCFString (int serviceClass) {
        switch (serviceClass) {
            case SERVICE_CLASS_VOICE:
                return context.getText(com.android.internal.R.string.serviceClassVoice);
            case SERVICE_CLASS_DATA:
                return context.getText(com.android.internal.R.string.serviceClassData);
            case SERVICE_CLASS_FAX:
                return context.getText(com.android.internal.R.string.serviceClassFAX);
            case SERVICE_CLASS_SMS:
                return context.getText(com.android.internal.R.string.serviceClassSMS);
            case SERVICE_CLASS_DATA_SYNC:
                return context.getText(com.android.internal.R.string.serviceClassDataSync);
            case SERVICE_CLASS_DATA_ASYNC:
                return context.getText(com.android.internal.R.string.serviceClassDataAsync);
            case SERVICE_CLASS_PACKET:
                return context.getText(com.android.internal.R.string.serviceClassPacket);
            case SERVICE_CLASS_PAD:
                return context.getText(com.android.internal.R.string.serviceClassPAD);
            default:
                return null;
        }
    }


    /** one CallForwardInfo + serviceClassMask -> one line of text */
    private CharSequence
    makeCFQueryResultMessage(CallForwardInfo info, int serviceClassMask) {
        CharSequence template;
        String sources[] = {"{0}", "{1}", "{2}"};
        CharSequence destinations[] = new CharSequence[3];
        boolean needTimeTemplate;

        // CF_REASON_NO_REPLY also has a time value associated with
        // it. All others don't.

        needTimeTemplate =
            (info.reason == CommandsInterface.CF_REASON_NO_REPLY);

        if (info.status == 1) {
            if (needTimeTemplate) {
                template = context.getText(
                        com.android.internal.R.string.cfTemplateForwardedTime);
            } else {
                template = context.getText(
                        com.android.internal.R.string.cfTemplateForwarded);
            }
        } else if (info.status == 0 && isEmptyOrNull(info.number)) {
            template = context.getText(
                        com.android.internal.R.string.cfTemplateNotForwarded);
        } else { /* (info.status == 0) && !isEmptyOrNull(info.number) */
            // A call forward record that is not active but contains
            // a phone number is considered "registered"

            if (needTimeTemplate) {
                template = context.getText(
                        com.android.internal.R.string.cfTemplateRegisteredTime);
            } else {
                template = context.getText(
                        com.android.internal.R.string.cfTemplateRegistered);
            }
        }

        // In the template (from strings.xmls)
        //         {0} is one of "bearerServiceCode*"
        //        {1} is dialing number
        //      {2} is time in seconds

        destinations[0] = serviceClassToCFString(info.serviceClass & serviceClassMask);
        destinations[1] = PhoneNumberUtils.stringFromStringAndTOA(info.number, info.toa);
        destinations[2] = Integer.toString(info.timeSeconds);

        if (info.reason == CommandsInterface.CF_REASON_UNCONDITIONAL &&
                (info.serviceClass & serviceClassMask)
                        == CommandsInterface.SERVICE_CLASS_VOICE) {
            boolean cffEnabled = (info.status == 1);
            phone.mSIMRecords.setVoiceCallForwardingFlag(1, cffEnabled);
        }

        return TextUtils.replace(template, sources, destinations);
    }


    private void
    onQueryCfComplete(AsyncResult ar) {
        StringBuilder sb = new StringBuilder(getScString());
        sb.append("\n");

        if (ar.exception != null) {
            state = State.FAILED;
            sb.append(getErrorMessage(ar));
        } else {
            CallForwardInfo infos[];

            infos = (CallForwardInfo[]) ar.result;

            if (infos.length == 0) {
                // Assume the default is not active
                sb.append(context.getText(com.android.internal.R.string.serviceDisabled));

                // Set unconditional CFF in SIM to false
                phone.mSIMRecords.setVoiceCallForwardingFlag(1, false);
            } else {

                SpannableStringBuilder tb = new SpannableStringBuilder();

                // Each bit in the service class gets its own result line
                // The service classes may be split up over multiple
                // CallForwardInfos. So, for each service class, find out
                // which CallForwardInfo represents it and then build
                // the response text based on that

                for (int serviceClassMask = 1
                            ; serviceClassMask <= SERVICE_CLASS_MAX
                            ; serviceClassMask <<= 1
                ) {
                    for (int i = 0, s = infos.length; i < s ; i++) {
                        if ((serviceClassMask & infos[i].serviceClass) != 0) {
                            tb.append(makeCFQueryResultMessage(infos[i],
                                            serviceClassMask));
                            tb.append("\n");
                        }
                    }
                }
                sb.append(tb);
            }

            state = State.COMPLETE;
        }

        message = sb;
        phone.onMMIDone(this);

    }

    private void
    onQueryComplete(AsyncResult ar) {
        StringBuilder sb = new StringBuilder(getScString());
        sb.append("\n");

        if (ar.exception != null) {
            state = State.FAILED;
            sb.append(getErrorMessage(ar));
        } else {
            int[] ints = (int[])ar.result;

            if (ints.length != 0) {
                if (ints[0] == 0) {
                    sb.append(context.getText(com.android.internal.R.string.serviceDisabled));
                } else if (sc.equals(SC_WAIT)) {
                    // Call Waiting includes additional data in the response.
                    sb.append(createQueryCallWaitingResultMessage(ints[1]));
                } else if (isServiceCodeCallBarring(sc)) {
                    // ints[0] for Call Barring is a bit vector of services
                    sb.append(createQueryCallBarringResultMessage(ints[0]));
                } else if (ints[0] == 1) {
                    // for all other services, treat it as a boolean
                    sb.append(context.getText(com.android.internal.R.string.serviceEnabled));
                } else {
                    sb.append(context.getText(com.android.internal.R.string.mmiError));
                }
            } else {
                sb.append(context.getText(com.android.internal.R.string.mmiError));
            }
            state = State.COMPLETE;
        }

        message = sb;
        phone.onMMIDone(this);
    }

    private CharSequence
    createQueryCallWaitingResultMessage(int serviceClass) {
        StringBuilder sb =
                new StringBuilder(context.getText(com.android.internal.R.string.serviceEnabledFor));

        for (int classMask = 1
                    ; classMask <= SERVICE_CLASS_MAX
                    ; classMask <<= 1
        ) {
            if ((classMask & serviceClass) != 0) {
                sb.append("\n");
                sb.append(serviceClassToCFString(classMask & serviceClass));
            }
        }
        return sb;
    }
    private CharSequence
    createQueryCallBarringResultMessage(int serviceClass)
    {
        StringBuilder sb = new StringBuilder(context.getText(com.android.internal.R.string.serviceEnabledFor));

        for (int classMask = 1
                    ; classMask <= SERVICE_CLASS_MAX
                    ; classMask <<= 1
        ) {
            if ((classMask & serviceClass) != 0) {
                sb.append("\n");
                sb.append(serviceClassToCFString(classMask & serviceClass));
            }
        }
        return sb;
    }

    /***
     * TODO: It would be nice to have a method here that can take in a dialstring and
     * figure out if there is an MMI code embedded within it.  This code would replace
     * some of the string parsing functionality in the Phone App's
     * SpecialCharSequenceMgr class.
     */

}
