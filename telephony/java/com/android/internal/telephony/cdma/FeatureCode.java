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
import android.os.*;
import android.util.Log;

import com.android.internal.telephony.*;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *
 * {@hide}
 *
 */
public final class FeatureCode  extends Handler implements MmiCode {
    static final String LOG_TAG = "CDMA";

    //***** Constants

    // Call Forwarding
    static final String FC_CF_ACTIVATE            = "72";
    static final String FC_CF_DEACTIVATE          = "73";
    static final String FC_CF_FORWARD_TO_NUMBER   = "56";

    // Call Forwarding Busy Line
    static final String FC_CFBL_ACTIVATE          = "90";
    static final String FC_CFBL_DEACTIVATE        = "91";
    static final String FC_CFBL_FORWARD_TO_NUMBER = "40";

    // Call Forwarding Don't Answer
    static final String FC_CFDA_ACTIVATE          = "92";
    static final String FC_CFDA_DEACTIVATE        = "93";
    static final String FC_CFDA_FORWARD_TO_NUMBER = "42";

    // Cancel Call Waiting
    static final String FC_CCW                    = "70";

    // Usage Sensitive Three-way Calling
    static final String FC_3WC                    = "71";

    // Do Not Disturb
    static final String FC_DND_ACTIVATE           = "78";
    static final String FC_DND_DEACTIVATE         = "79";

    // Who Called Me?
    static final String FC_WHO                    = "51";

    // Rejection of Undesired Annoying Calls
    static final String FC_RUAC_ACTIVATE          = "60";
    static final String FC_RUAC_DEACTIVATE        = "80";

    // Calling Number Delivery
    // Calling Number Identification Presentation
    static final String FC_CNIP                   = "65";
    // Calling Number Identification Restriction
    static final String FC_CNIR                   = "85";


    //***** Event Constants

    static final int EVENT_SET_COMPLETE         = 1;
    static final int EVENT_CDMA_FLASH_COMPLETED = 2;


    //***** Instance Variables

    CDMAPhone phone;
    Context context;
    String action;              // '*' in CDMA
    String sc;                  // Service Code
    String poundString;         // Entire Flash string
    String dialingNumber;

    /** Set to true in processCode, not at newFromDialString time */

    State state = State.PENDING;
    CharSequence message;

    //***** Class Variables


    // Flash Code Pattern

    static Pattern sPatternSuppService = Pattern.compile(
        "((\\*)(\\d{2,3})(#?)([^*#]*)?)(.*)");
/*       1  2    3       4   5         6

         1 = Full string up to and including #
         2 = action
         3 = service code
         4 = separator
         5 = dialing number
*/

    static final int MATCH_GROUP_POUND_STRING   = 1;
    static final int MATCH_GROUP_ACTION_STRING  = 2;
    static final int MATCH_GROUP_SERVICE_CODE   = 3;
    static final int MATCH_GROUP_DIALING_NUMBER = 5;


    //***** Public Class methods

    /**
     * Some dial strings in CDMA are defined to do non-call setup
     * things, such as set supplementary service settings (eg, call
     * forwarding). These are generally referred to as "Feature Codes".
     * We look to see if the dial string contains a valid Feature code (potentially
     * with a dial string at the end as well) and return info here.
     *
     * If the dial string contains no Feature code, we return an instance with
     * only "dialingNumber" set
     *
     * Please see also S.R0006-000-A v2.0 "Wireless Features Description"
     */

    static FeatureCode newFromDialString(String dialString, CDMAPhone phone) {
        Matcher m;
        FeatureCode ret = null;

        m = sPatternSuppService.matcher(dialString);

        // Is this formatted like a standard supplementary service code?
        if (m.matches()) {
            ret = new FeatureCode(phone);
            ret.poundString = makeEmptyNull(m.group(MATCH_GROUP_POUND_STRING));
            ret.action = makeEmptyNull(m.group(MATCH_GROUP_ACTION_STRING));
            ret.sc = makeEmptyNull(m.group(MATCH_GROUP_SERVICE_CODE));
            ret.dialingNumber = makeEmptyNull(m.group(MATCH_GROUP_DIALING_NUMBER));
        }

        return ret;
    }

    //***** Private Class methods

    /** make empty strings be null.
     *  Java regexp returns empty strings for empty groups
     */
    private static String makeEmptyNull (String s) {
        if (s != null && s.length() == 0) return null;

        return s;
    }

    /** returns true of the string is empty or null */
    private static boolean isEmptyOrNull(CharSequence s) {
        return s == null || (s.length() == 0);
    }

    static boolean isServiceCodeCallForwarding(String sc) {
        return sc != null &&
                (sc.equals(FC_CF_ACTIVATE)
                || sc.equals(FC_CF_DEACTIVATE) || sc.equals(FC_CF_FORWARD_TO_NUMBER)
                || sc.equals(FC_CFBL_ACTIVATE) || sc.equals(FC_CFBL_DEACTIVATE)
                || sc.equals(FC_CFBL_FORWARD_TO_NUMBER) || sc.equals(FC_CFDA_ACTIVATE)
                || sc.equals(FC_CFDA_DEACTIVATE) || sc.equals(FC_CFDA_FORWARD_TO_NUMBER));
    }

    static boolean isServiceCodeCallWaiting(String sc) {
        return sc != null && sc.equals(FC_CCW);
    }

    static boolean isServiceCodeThreeWayCalling(String sc) {
        return sc != null && sc.equals(FC_3WC);
    }

    static boolean isServiceCodeAnnoyingCalls(String sc) {
        return sc != null &&
                (sc.equals(FC_RUAC_ACTIVATE)
                || sc.equals(FC_RUAC_DEACTIVATE));
    }

    static boolean isServiceCodeCallingNumberDelivery(String sc) {
        return sc != null &&
                (sc.equals(FC_CNIP)
                || sc.equals(FC_CNIR));
    }

    static boolean isServiceCodeDoNotDisturb(String sc) {
        return sc != null &&
                (sc.equals(FC_DND_ACTIVATE)
                || sc.equals(FC_DND_DEACTIVATE));
    }


    //***** Constructor

    FeatureCode (CDMAPhone phone) {
        super(phone.getHandler().getLooper());
        this.phone = phone;
        this.context = phone.getContext();
    }


    //***** MmiCode implementation

    public State getState() {
        return state;
    }

    public CharSequence getMessage() {
        return message;
    }

    // inherited javadoc suffices
    public void cancel() {
        //Not used here
    }

    public boolean isCancelable() {
        Log.e(LOG_TAG, "isCancelable: not used in CDMA");
        return false;
    }

    public boolean isUssdRequest() {
        Log.e(LOG_TAG, "isUssdRequest: not used in CDMA");
        return false;
    }

    /** Process a Flash Code...anything that isn't a dialing number */
    void processCode() {
        Log.d(LOG_TAG, "send feature code...");
        phone.mCM.sendCDMAFeatureCode(this.poundString, obtainMessage(EVENT_CDMA_FLASH_COMPLETED));
    }

    /** Called from CDMAPhone.handleMessage; not a Handler subclass */
    public void handleMessage (Message msg) {
        AsyncResult ar;

        switch (msg.what) {
        case EVENT_SET_COMPLETE:
            ar = (AsyncResult) (msg.obj);
            onSetComplete(ar);
            break;
        case EVENT_CDMA_FLASH_COMPLETED:
            ar = (AsyncResult) (msg.obj);

            if (ar.exception != null) {
                state = State.FAILED;
                message = context.getText(com.android.internal.R.string.fcError);
            } else {
                state = State.COMPLETE;
                message = context.getText(com.android.internal.R.string.fcComplete);
            }
            phone.onFeatureCodeDone(this);
            break;
        }
    }


    //***** Private instance methods

    private CharSequence getScString() {
        if (sc != null) {
            if (isServiceCodeCallForwarding(sc)) {
                return context.getText(com.android.internal.R.string.CfMmi);
            } else if (isServiceCodeCallWaiting(sc)) {
                return context.getText(com.android.internal.R.string.CwMmi);
            } else if (sc.equals(FC_CNIP)) {
                return context.getText(com.android.internal.R.string.CnipMmi);
            } else if (sc.equals(FC_CNIR)) {
                return context.getText(com.android.internal.R.string.CnirMmi);
            } else if (isServiceCodeThreeWayCalling(sc)) {
                return context.getText(com.android.internal.R.string.ThreeWCMmi);
            } else if (isServiceCodeAnnoyingCalls(sc)) {
                return context.getText(com.android.internal.R.string.RuacMmi);
            } else if (isServiceCodeCallingNumberDelivery(sc)) {
                return context.getText(com.android.internal.R.string.CndMmi);
            } else if (isServiceCodeDoNotDisturb(sc)) {
                return context.getText(com.android.internal.R.string.DndMmi);
            }
        }

        return "";
    }

    private void onSetComplete(AsyncResult ar){
        StringBuilder sb = new StringBuilder(getScString());
        sb.append("\n");

        if (ar.exception != null) {
            state = State.FAILED;
            sb.append(context.getText(com.android.internal.R.string.mmiError));
        } else {
            state = State.FAILED;
            sb.append(context.getText(com.android.internal.R.string.mmiError));
        }

        message = sb;
        phone.onFeatureCodeDone(this);
    }
}
