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

import com.android.internal.telephony.Call;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.UUSInfo;

import android.net.sip.SipAudioCall;
import android.os.SystemClock;
import android.util.Log;
import android.telephony.PhoneNumberUtils;

abstract class SipConnectionBase extends Connection {
    private static final String LOG_TAG = "SIP_CONN";

    private SipAudioCall mSipAudioCall;

    private String dialString;          // outgoing calls only
    private String postDialString;      // outgoing calls only
    private int nextPostDialChar;       // index into postDialString
    private boolean isIncoming;

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
    private long duration = -1L;
    private long holdingStartTime;  // The time when the Connection last transitioned
                            // into HOLDING

    private DisconnectCause mCause = DisconnectCause.NOT_DISCONNECTED;
    private PostDialState postDialState = PostDialState.NOT_STARTED;

    SipConnectionBase(String dialString) {
        this.dialString = dialString;

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
                duration = getDurationMillis();
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
        } else if (duration < 0) {
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
