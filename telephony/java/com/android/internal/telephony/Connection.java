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
import android.util.Log;

/**
 * {@hide}
 */
public abstract class Connection {

    // Number presentation type for caller id display
    public static int PRESENTATION_ALLOWED = 1;    // normal
    public static int PRESENTATION_RESTRICTED = 2; // block by user
    public static int PRESENTATION_UNKNOWN = 3;    // no specified or unknown by network
    public static int PRESENTATION_PAYPHONE = 4;   // show pay phone info

    private static String LOG_TAG = "TelephonyConnection";

    public enum DisconnectCause {
        NOT_DISCONNECTED,               /* has not yet disconnected */
        INCOMING_MISSED,                /* an incoming call that was missed and never answered */
        NORMAL,                         /* normal; remote */
        LOCAL,                          /* normal; local hangup */
        BUSY,                           /* outgoing call to busy line */
        CONGESTION,                     /* outgoing call to congested network */
        MMI,                            /* not presently used; dial() returns null */
        INVALID_NUMBER,                 /* invalid dial string */
        NUMBER_UNREACHABLE,             /* cannot reach the peer */
        SERVER_UNREACHABLE,             /* cannot reach the server */
        INVALID_CREDENTIALS,            /* invalid credentials */
        OUT_OF_NETWORK,                 /* calling from out of network is not allowed */
        SERVER_ERROR,                   /* server error */
        TIMED_OUT,                      /* client timed out */
        LOST_SIGNAL,
        LIMIT_EXCEEDED,                 /* eg GSM ACM limit exceeded */
        INCOMING_REJECTED,              /* an incoming call that was rejected */
        POWER_OFF,                      /* radio is turned off explicitly */
        OUT_OF_SERVICE,                 /* out of service */
        ICC_ERROR,                      /* No ICC, ICC locked, or other ICC error */
        CALL_BARRED,                    /* call was blocked by call barring */
        FDN_BLOCKED,                    /* call was blocked by fixed dial number */
        CS_RESTRICTED,                  /* call was blocked by restricted all voice access */
        CS_RESTRICTED_NORMAL,           /* call was blocked by restricted normal voice access */
        CS_RESTRICTED_EMERGENCY,        /* call was blocked by restricted emergency voice access */
        UNOBTAINABLE_NUMBER,            /* Unassigned number (3GPP TS 24.008 table 10.5.123) */
        CDMA_LOCKED_UNTIL_POWER_CYCLE,  /* MS is locked until next power cycle */
        CDMA_DROP,
        CDMA_INTERCEPT,                 /* INTERCEPT order received, MS state idle entered */
        CDMA_REORDER,                   /* MS has been redirected, call is cancelled */
        CDMA_SO_REJECT,                 /* service option rejection */
        CDMA_RETRY_ORDER,               /* requested service is rejected, retry delay is set */
        CDMA_ACCESS_FAILURE,
        CDMA_PREEMPTED,
        CDMA_NOT_EMERGENCY,              /* not an emergency call */
        CDMA_ACCESS_BLOCKED,            /* Access Blocked by CDMA network */
        ERROR_UNSPECIFIED
    }

    Object userData;

    /* Instance Methods */

    /**
     * Gets address (e.g. phone number) associated with connection.
     * TODO: distinguish reasons for unavailability
     *
     * @return address or null if unavailable
     */

    public abstract String getAddress();

    /**
     * Gets CDMA CNAP name associated with connection.
     * @return cnap name or null if unavailable
     */
    public String getCnapName() {
        return null;
    }

    /**
     * Get original dial string.
     * @return original dial string or null if unavailable
     */
    public String getOrigDialString(){
        return null;
    }

    /**
     * Gets CDMA CNAP presentation associated with connection.
     * @return cnap name or null if unavailable
     */

    public int getCnapNamePresentation() {
       return 0;
    };

    /**
     * @return Call that owns this Connection, or null if none
     */
    public abstract Call getCall();

    /**
     * Connection create time in currentTimeMillis() format
     * Basically, set when object is created.
     * Effectively, when an incoming call starts ringing or an
     * outgoing call starts dialing
     */
    public abstract long getCreateTime();

    /**
     * Connection connect time in currentTimeMillis() format.
     * For outgoing calls: Begins at (DIALING|ALERTING) -> ACTIVE transition.
     * For incoming calls: Begins at (INCOMING|WAITING) -> ACTIVE transition.
     * Returns 0 before then.
     */
    public abstract long getConnectTime();

    /**
     * Disconnect time in currentTimeMillis() format.
     * The time when this Connection makes a transition into ENDED or FAIL.
     * Returns 0 before then.
     */
    public abstract long getDisconnectTime();

    /**
     * Returns the number of milliseconds the call has been connected,
     * or 0 if the call has never connected.
     * If the call is still connected, then returns the elapsed
     * time since connect.
     */
    public abstract long getDurationMillis();

    /**
     * If this connection is HOLDING, return the number of milliseconds
     * that it has been on hold for (approximately).
     * If this connection is in any other state, return 0.
     */

    public abstract long getHoldDurationMillis();

    /**
     * Returns "NOT_DISCONNECTED" if not yet disconnected.
     */
    public abstract DisconnectCause getDisconnectCause();

    /**
     * Returns true of this connection originated elsewhere
     * ("MT" or mobile terminated; another party called this terminal)
     * or false if this call originated here (MO or mobile originated).
     */
    public abstract boolean isIncoming();

    /**
     * If this Connection is connected, then it is associated with
     * a Call.
     *
     * Returns getCall().getState() or Call.State.IDLE if not
     * connected
     */
    public Call.State getState() {
        Call c;

        c = getCall();

        if (c == null) {
            return Call.State.IDLE;
        } else {
            return c.getState();
        }
    }

    /**
     * isAlive()
     *
     * @return true if the connection isn't disconnected
     * (could be active, holding, ringing, dialing, etc)
     */
    public boolean
    isAlive() {
        return getState().isAlive();
    }

    /**
     * Returns true if Connection is connected and is INCOMING or WAITING
     */
    public boolean
    isRinging() {
        return getState().isRinging();
    }

    /**
     *
     * @return the userdata set in setUserData()
     */
    public Object getUserData() {
        return userData;
    }

    /**
     *
     * @param userdata user can store an any userdata in the Connection object.
     */
    public void setUserData(Object userdata) {
        this.userData = userdata;
    }

    /**
     * Hangup individual Connection
     */
    public abstract void hangup() throws CallStateException;

    /**
     * Separate this call from its owner Call and assigns it to a new Call
     * (eg if it is currently part of a Conference call
     * TODO: Throw exception? Does GSM require error display on failure here?
     */
    public abstract void separate() throws CallStateException;

    public enum PostDialState {
        NOT_STARTED,    /* The post dial string playback hasn't
                           been started, or this call is not yet
                           connected, or this is an incoming call */
        STARTED,        /* The post dial string playback has begun */
        WAIT,           /* The post dial string playback is waiting for a
                           call to proceedAfterWaitChar() */
        WILD,           /* The post dial string playback is waiting for a
                           call to proceedAfterWildChar() */
        COMPLETE,       /* The post dial string playback is complete */
        CANCELLED,       /* The post dial string playback was cancelled
                           with cancelPostDial() */
        PAUSE           /* The post dial string playback is pausing for a
                           call to processNextPostDialChar*/
    }

    public void clearUserData(){
        userData = null;
    }

    public abstract PostDialState getPostDialState();

    /**
     * Returns the portion of the post dial string that has not
     * yet been dialed, or "" if none
     */
    public abstract String getRemainingPostDialString();

    /**
     * See Phone.setOnPostDialWaitCharacter()
     */

    public abstract void proceedAfterWaitChar();

    /**
     * See Phone.setOnPostDialWildCharacter()
     */
    public abstract void proceedAfterWildChar(String str);
    /**
     * Cancel any post
     */
    public abstract void cancelPostDial();

    /**
     * Returns the caller id presentation type for incoming and waiting calls
     * @return one of PRESENTATION_*
     */
    public abstract int getNumberPresentation();

    /**
     * Returns the User to User Signaling (UUS) information associated with
     * incoming and waiting calls
     * @return UUSInfo containing the UUS userdata.
     */
    public abstract UUSInfo getUUSInfo();

    /**
     * Build a human representation of a connection instance, suitable for debugging.
     * Don't log personal stuff unless in debug mode.
     * @return a string representing the internal state of this connection.
     */
    public String toString() {
        StringBuilder str = new StringBuilder(128);

        if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
            str.append("addr: " + getAddress())
                    .append(" pres.: " + getNumberPresentation())
                    .append(" dial: " + getOrigDialString())
                    .append(" postdial: " + getRemainingPostDialString())
                    .append(" cnap name: " + getCnapName())
                    .append("(" + getCnapNamePresentation() + ")");
        }
        str.append(" incoming: " + isIncoming())
                .append(" state: " + getState())
                .append(" post dial state: " + getPostDialState());
        return str.toString();
    }
}
