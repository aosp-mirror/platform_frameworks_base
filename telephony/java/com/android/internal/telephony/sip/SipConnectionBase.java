/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.internal.telephony.sip;

import android.content.Context;
import android.net.sip.SipAudioCall;
import android.os.Message;
import android.os.Registrant;
import android.os.SystemClock;
import android.util.Log;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;

import com.android.internal.telephony.*;

abstract class SipConnectionBase extends Connection {
    //***** Event Constants
    private static final int EVENT_DTMF_DONE = 1;
    private static final int EVENT_PAUSE_DONE = 2;
    private static final int EVENT_NEXT_POST_DIAL = 3;
    private static final int EVENT_WAKE_LOCK_TIMEOUT = 4;

    //***** Constants
    private static final int PAUSE_DELAY_FIRST_MILLIS = 100;
    private static final int PAUSE_DELAY_MILLIS = 3 * 1000;
    private static final int WAKE_LOCK_TIMEOUT_MILLIS = 60*1000;

    private static final String LOG_TAG = "SIP_CONN";

    private SipAudioCall mSipAudioCall;

    private String dialString;          // outgoing calls only
    private String postDialString;      // outgoing calls only
    private int nextPostDialChar;       // index into postDialString
    private boolean isIncoming;
    private boolean disconnected;

    int index;          // index in SipCallTracker.connections[], -1 if unassigned
                        // The Sip index is 1 + this

    /*
     * These time/timespan values are based on System.currentTimeMillis(),
     * i.e., "wall clock" time.
     */
    private long createTime;
    private long connectTime;
    private long disconnectTime;

    /*
     * These time/timespan values are based on SystemClock.elapsedRealTime(),
     * i.e., time since boot.  They are appropriate for comparison and
     * calculating deltas.
     */
    private long connectTimeReal;
    private long duration;
    private long holdingStartTime;  // The time when the Connection last transitioned
                            // into HOLDING

    private DisconnectCause mCause = DisconnectCause.NOT_DISCONNECTED;
    private PostDialState postDialState = PostDialState.NOT_STARTED;

    SipConnectionBase(String calleeSipUri) {
        dialString = calleeSipUri;

        postDialString = PhoneNumberUtils.extractPostDialPortion(dialString);

        isIncoming = false;
        createTime = System.currentTimeMillis();
    }

    protected void setState(Call.State state) {
        switch (state) {
            case ACTIVE:
                if (connectTime == 0) {
                    connectTimeReal = SystemClock.elapsedRealtime();
                    connectTime = System.currentTimeMillis();
                }
                break;
            case DISCONNECTED:
                duration = SystemClock.elapsedRealtime() - connectTimeReal;
                disconnectTime = System.currentTimeMillis();
                break;
            case HOLDING:
                holdingStartTime = SystemClock.elapsedRealtime();
                break;
        }
    }

    @Override
    public long getCreateTime() {
        return createTime;
    }

    @Override
    public long getConnectTime() {
        return connectTime;
    }

    @Override
    public long getDisconnectTime() {
        return disconnectTime;
    }

    @Override
    public long getDurationMillis() {
        if (connectTimeReal == 0) {
            return 0;
        } else if (duration == 0) {
            return SystemClock.elapsedRealtime() - connectTimeReal;
        } else {
            return duration;
        }
    }

    @Override
    public long getHoldDurationMillis() {
        if (getState() != Call.State.HOLDING) {
            // If not holding, return 0
            return 0;
        } else {
            return SystemClock.elapsedRealtime() - holdingStartTime;
        }
    }

    @Override
    public DisconnectCause getDisconnectCause() {
        return mCause;
    }

    void setDisconnectCause(DisconnectCause cause) {
        mCause = cause;
    }

    @Override
    public PostDialState getPostDialState() {
        return postDialState;
    }

    @Override
    public void proceedAfterWaitChar() {
        // TODO
    }

    @Override
    public void proceedAfterWildChar(String str) {
        // TODO
    }

    @Override
    public void cancelPostDial() {
        // TODO
    }

    protected abstract Phone getPhone();

    DisconnectCause disconnectCauseFromCode(int causeCode) {
        /**
         * See 22.001 Annex F.4 for mapping of cause codes
         * to local tones
         */

        switch (causeCode) {
            case CallFailCause.USER_BUSY:
                return DisconnectCause.BUSY;

            case CallFailCause.NO_CIRCUIT_AVAIL:
            case CallFailCause.TEMPORARY_FAILURE:
            case CallFailCause.SWITCHING_CONGESTION:
            case CallFailCause.CHANNEL_NOT_AVAIL:
            case CallFailCause.QOS_NOT_AVAIL:
            case CallFailCause.BEARER_NOT_AVAIL:
                return DisconnectCause.CONGESTION;

            case CallFailCause.ACM_LIMIT_EXCEEDED:
                return DisconnectCause.LIMIT_EXCEEDED;

            case CallFailCause.CALL_BARRED:
                return DisconnectCause.CALL_BARRED;

            case CallFailCause.FDN_BLOCKED:
                return DisconnectCause.FDN_BLOCKED;

            case CallFailCause.ERROR_UNSPECIFIED:
            case CallFailCause.NORMAL_CLEARING:
            default:
                Phone phone = getPhone();
                int serviceState = phone.getServiceState().getState();
                if (serviceState == ServiceState.STATE_POWER_OFF) {
                    return DisconnectCause.POWER_OFF;
                } else if (serviceState == ServiceState.STATE_OUT_OF_SERVICE
                        || serviceState == ServiceState.STATE_EMERGENCY_ONLY ) {
                    return DisconnectCause.OUT_OF_SERVICE;
                } else if (causeCode == CallFailCause.ERROR_UNSPECIFIED) {
                    return DisconnectCause.ERROR_UNSPECIFIED;
                } else if (causeCode == CallFailCause.NORMAL_CLEARING) {
                    return DisconnectCause.NORMAL;
                } else {
                    // If nothing else matches, report unknown call drop reason
                    // to app, not NORMAL call end.
                    return DisconnectCause.ERROR_UNSPECIFIED;
                }
        }
    }

    @Override
    public String getRemainingPostDialString() {
        if (postDialState == PostDialState.CANCELLED
            || postDialState == PostDialState.COMPLETE
            || postDialString == null
            || postDialString.length() <= nextPostDialChar) {
            return "";
        }

        return postDialString.substring(nextPostDialChar);
    }

    private void log(String msg) {
        Log.d(LOG_TAG, "[SipConn] " + msg);
    }

    @Override
    public int getNumberPresentation() {
        // TODO: add PRESENTATION_URL
        return Connection.PRESENTATION_ALLOWED;
    }

    @Override
    public UUSInfo getUUSInfo() {
        // FIXME: what's this for SIP?
        return null;
    }
}
