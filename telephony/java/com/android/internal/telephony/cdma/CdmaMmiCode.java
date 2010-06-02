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

package com.android.internal.telephony.cdma;

import android.content.Context;

import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.MmiCode;

import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * This class can handle Puk code Mmi
 *
 * {@hide}
 *
 */
public final class CdmaMmiCode  extends Handler implements MmiCode {
    static final String LOG_TAG = "CDMA_MMI";

    // Constants

    // From TS 22.030 6.5.2
    static final String ACTION_REGISTER = "**";

    // Supp Service codes from TS 22.030 Annex B
    static final String SC_PUK          = "05";

    // Event Constant

    static final int EVENT_SET_COMPLETE = 1;

    // Instance Variables

    CDMAPhone phone;
    Context context;

    String action;              // ACTION_REGISTER
    String sc;                  // Service Code
    String sia, sib, sic;       // Service Info a,b,c
    String poundString;         // Entire MMI string up to and including #
    String dialingNumber;
    String pwd;                 // For password registration

    State state = State.PENDING;
    CharSequence message;

    // Class Variables

    static Pattern sPatternSuppService = Pattern.compile(
        "((\\*|#|\\*#|\\*\\*|##)(\\d{2,3})(\\*([^*#]*)(\\*([^*#]*)(\\*([^*#]*)(\\*([^*#]*))?)?)?)?#)(.*)");
/*       1  2                    3          4  5       6   7         8    9     10  11             12

         1 = Full string up to and including #
         2 = action
         3 = service code
         5 = SIA
         7 = SIB
         9 = SIC
         10 = dialing number
*/

    static final int MATCH_GROUP_POUND_STRING = 1;
    static final int MATCH_GROUP_ACTION = 2;
    static final int MATCH_GROUP_SERVICE_CODE = 3;
    static final int MATCH_GROUP_SIA = 5;
    static final int MATCH_GROUP_SIB = 7;
    static final int MATCH_GROUP_SIC = 9;
    static final int MATCH_GROUP_PWD_CONFIRM = 11;
    static final int MATCH_GROUP_DIALING_NUMBER = 12;


    // Public Class methods

    /**
     * Check if provided string contains Mmi code in it and create corresponding
     * Mmi if it does
     */

    public static CdmaMmiCode
    newFromDialString(String dialString, CDMAPhone phone) {
        Matcher m;
        CdmaMmiCode ret = null;

        m = sPatternSuppService.matcher(dialString);

        // Is this formatted like a standard supplementary service code?
        if (m.matches()) {
            ret = new CdmaMmiCode(phone);
            ret.poundString = makeEmptyNull(m.group(MATCH_GROUP_POUND_STRING));
            ret.action = makeEmptyNull(m.group(MATCH_GROUP_ACTION));
            ret.sc = makeEmptyNull(m.group(MATCH_GROUP_SERVICE_CODE));
            ret.sia = makeEmptyNull(m.group(MATCH_GROUP_SIA));
            ret.sib = makeEmptyNull(m.group(MATCH_GROUP_SIB));
            ret.sic = makeEmptyNull(m.group(MATCH_GROUP_SIC));
            ret.pwd = makeEmptyNull(m.group(MATCH_GROUP_PWD_CONFIRM));
            ret.dialingNumber = makeEmptyNull(m.group(MATCH_GROUP_DIALING_NUMBER));

        }

        return ret;
    }

    // Private Class methods

    /** make empty strings be null.
     *  Regexp returns empty strings for empty groups
     */
    private static String
    makeEmptyNull (String s) {
        if (s != null && s.length() == 0) return null;

        return s;
    }

    // Constructor

    CdmaMmiCode (CDMAPhone phone) {
        super(phone.getHandler().getLooper());
        this.phone = phone;
        this.context = phone.getContext();
    }

    // MmiCode implementation

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
        phone.onMMIDone (this);
    }

    public boolean isCancelable() {
        return false;
    }

    // Instance Methods

    /**
     * @return true if the Service Code is PIN/PIN2/PUK/PUK2-related
     */
    boolean isPukCommand() {
        return sc != null && sc.equals(SC_PUK);
     }

    boolean isRegister() {
        return action != null && action.equals(ACTION_REGISTER);
    }

    public boolean isUssdRequest() {
        Log.w(LOG_TAG, "isUssdRequest is not implemented in CdmaMmiCode");
        return false;
    }

    /** Process a MMI PUK code */
    void
    processCode () {
        try {
            if (isPukCommand()) {
                // sia = old PUK
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
                    } else {
                        phone.mCM.supplyIccPuk(oldPinOrPuk, newPin,
                                obtainMessage(EVENT_SET_COMPLETE, this));
                    }
                } else {
                    throw new RuntimeException ("Invalid or Unsupported MMI Code");
                }
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

    public void
    handleMessage (Message msg) {
        AsyncResult ar;

        if (msg.what == EVENT_SET_COMPLETE) {
            ar = (AsyncResult) (msg.obj);
            onSetComplete(ar);
        } else {
            Log.e(LOG_TAG, "Unexpected reply");
        }
    }
    // Private instance methods

    private CharSequence getScString() {
        if (sc != null) {
            if (isPukCommand()) {
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
                    if (isPukCommand()) {
                        sb.append(context.getText(
                                com.android.internal.R.string.badPuk));
                    } else {
                        sb.append(context.getText(
                                com.android.internal.R.string.passwordIncorrect));
                    }
                } else {
                    sb.append(context.getText(
                            com.android.internal.R.string.mmiError));
                }
            } else {
                sb.append(context.getText(
                        com.android.internal.R.string.mmiError));
            }
        } else if (isRegister()) {
            state = State.COMPLETE;
            sb.append(context.getText(
                    com.android.internal.R.string.serviceRegistered));
        } else {
            state = State.FAILED;
            sb.append(context.getText(
                    com.android.internal.R.string.mmiError));
        }

        message = sb;
        phone.onMMIDone(this);
    }

}
