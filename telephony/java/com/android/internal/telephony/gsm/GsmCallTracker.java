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

import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.SystemProperties;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.telephony.gsm.GsmCellLocation;
import android.util.EventLog;
import android.util.Log;

import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.CallTracker;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.DriverCall;
import com.android.internal.telephony.EventLogTags;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.UUSInfo;
import com.android.internal.telephony.gsm.CallFailCause;
import com.android.internal.telephony.gsm.GSMPhone;
import com.android.internal.telephony.gsm.GsmCall;
import com.android.internal.telephony.gsm.GsmConnection;

import java.util.List;
import java.util.ArrayList;

/**
 * {@hide}
 */
public final class GsmCallTracker extends CallTracker {
    static final String LOG_TAG = "GSM";
    private static final boolean REPEAT_POLLING = false;

    private static final boolean DBG_POLL = false;

    //***** Constants

    static final int MAX_CONNECTIONS = 7;   // only 7 connections allowed in GSM
    static final int MAX_CONNECTIONS_PER_CALL = 5; // only 5 connections allowed per call

    //***** Instance Variables
    GsmConnection connections[] = new GsmConnection[MAX_CONNECTIONS];
    RegistrantList voiceCallEndedRegistrants = new RegistrantList();
    RegistrantList voiceCallStartedRegistrants = new RegistrantList();


    // connections dropped during last poll
    ArrayList<GsmConnection> droppedDuringPoll
        = new ArrayList<GsmConnection>(MAX_CONNECTIONS);

    GsmCall ringingCall = new GsmCall(this);
            // A call that is ringing or (call) waiting
    GsmCall foregroundCall = new GsmCall(this);
    GsmCall backgroundCall = new GsmCall(this);

    GsmConnection pendingMO;
    boolean hangupPendingMO;

    GSMPhone phone;

    boolean desiredMute = false;    // false = mute off

    Phone.State state = Phone.State.IDLE;



    //***** Events


    //***** Constructors

    GsmCallTracker (GSMPhone phone) {
        this.phone = phone;
        cm = phone.mCM;

        cm.registerForCallStateChanged(this, EVENT_CALL_STATE_CHANGE, null);

        cm.registerForOn(this, EVENT_RADIO_AVAILABLE, null);
        cm.registerForNotAvailable(this, EVENT_RADIO_NOT_AVAILABLE, null);
    }

    public void dispose() {
        //Unregister for all events
        cm.unregisterForCallStateChanged(this);
        cm.unregisterForOn(this);
        cm.unregisterForNotAvailable(this);

        for(GsmConnection c : connections) {
            try {
                if(c != null) hangup(c);
            } catch (CallStateException ex) {
                Log.e(LOG_TAG, "unexpected error on hangup during dispose");
            }
        }

        try {
            if(pendingMO != null) hangup(pendingMO);
        } catch (CallStateException ex) {
            Log.e(LOG_TAG, "unexpected error on hangup during dispose");
        }

        clearDisconnected();
    }

    protected void finalize() {
        Log.d(LOG_TAG, "GsmCallTracker finalized");
    }

    //***** Instance Methods

    //***** Public Methods
    public void registerForVoiceCallStarted(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        voiceCallStartedRegistrants.add(r);
    }

    public void unregisterForVoiceCallStarted(Handler h) {
        voiceCallStartedRegistrants.remove(h);
    }

    public void registerForVoiceCallEnded(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        voiceCallEndedRegistrants.add(r);
    }

    public void unregisterForVoiceCallEnded(Handler h) {
        voiceCallEndedRegistrants.remove(h);
    }

    private void
    fakeHoldForegroundBeforeDial() {
        List<Connection> connCopy;

        // We need to make a copy here, since fakeHoldBeforeDial()
        // modifies the lists, and we don't want to reverse the order
        connCopy = (List<Connection>) foregroundCall.connections.clone();

        for (int i = 0, s = connCopy.size() ; i < s ; i++) {
            GsmConnection conn = (GsmConnection)connCopy.get(i);

            conn.fakeHoldBeforeDial();
        }
    }

    /**
     * clirMode is one of the CLIR_ constants
     */
    Connection
    dial (String dialString, int clirMode, UUSInfo uusInfo) throws CallStateException {
        // note that this triggers call state changed notif
        clearDisconnected();

        if (!canDial()) {
            throw new CallStateException("cannot dial in current state");
        }

        // The new call must be assigned to the foreground call.
        // That call must be idle, so place anything that's
        // there on hold
        if (foregroundCall.getState() == GsmCall.State.ACTIVE) {
            // this will probably be done by the radio anyway
            // but the dial might fail before this happens
            // and we need to make sure the foreground call is clear
            // for the newly dialed connection
            switchWaitingOrHoldingAndActive();

            // Fake local state so that
            // a) foregroundCall is empty for the newly dialed connection
            // b) hasNonHangupStateChanged remains false in the
            // next poll, so that we don't clear a failed dialing call
            fakeHoldForegroundBeforeDial();
        }

        if (foregroundCall.getState() != GsmCall.State.IDLE) {
            //we should have failed in !canDial() above before we get here
            throw new CallStateException("cannot dial in current state");
        }

        pendingMO = new GsmConnection(phone.getContext(), checkForTestEmergencyNumber(dialString),
                this, foregroundCall);
        hangupPendingMO = false;

        if (pendingMO.address == null || pendingMO.address.length() == 0
            || pendingMO.address.indexOf(PhoneNumberUtils.WILD) >= 0
        ) {
            // Phone number is invalid
            pendingMO.cause = Connection.DisconnectCause.INVALID_NUMBER;

            // handlePollCalls() will notice this call not present
            // and will mark it as dropped.
            pollCallsWhenSafe();
        } else {
            // Always unmute when initiating a new call
            setMute(false);

            cm.dial(pendingMO.address, clirMode, uusInfo, obtainCompleteMessage());
        }

        updatePhoneState();
        phone.notifyPreciseCallStateChanged();

        return pendingMO;
    }

    Connection
    dial(String dialString) throws CallStateException {
        return dial(dialString, CommandsInterface.CLIR_DEFAULT, null);
    }

    Connection
    dial(String dialString, UUSInfo uusInfo) throws CallStateException {
        return dial(dialString, CommandsInterface.CLIR_DEFAULT, uusInfo);
    }

    Connection
    dial(String dialString, int clirMode) throws CallStateException {
        return dial(dialString, clirMode, null);
    }

    void
    acceptCall () throws CallStateException {
        // FIXME if SWITCH fails, should retry with ANSWER
        // in case the active/holding call disappeared and this
        // is no longer call waiting

        if (ringingCall.getState() == GsmCall.State.INCOMING) {
            Log.i("phone", "acceptCall: incoming...");
            // Always unmute when answering a new call
            setMute(false);
            cm.acceptCall(obtainCompleteMessage());
        } else if (ringingCall.getState() == GsmCall.State.WAITING) {
            setMute(false);
            switchWaitingOrHoldingAndActive();
        } else {
            throw new CallStateException("phone not ringing");
        }
    }

    void
    rejectCall () throws CallStateException {
        // AT+CHLD=0 means "release held or UDUB"
        // so if the phone isn't ringing, this could hang up held
        if (ringingCall.getState().isRinging()) {
            cm.rejectCall(obtainCompleteMessage());
        } else {
            throw new CallStateException("phone not ringing");
        }
    }

    void
    switchWaitingOrHoldingAndActive() throws CallStateException {
        // Should we bother with this check?
        if (ringingCall.getState() == GsmCall.State.INCOMING) {
            throw new CallStateException("cannot be in the incoming state");
        } else {
            cm.switchWaitingOrHoldingAndActive(
                    obtainCompleteMessage(EVENT_SWITCH_RESULT));
        }
    }

    void
    conference() throws CallStateException {
        cm.conference(obtainCompleteMessage(EVENT_CONFERENCE_RESULT));
    }

    void
    explicitCallTransfer() throws CallStateException {
        cm.explicitCallTransfer(obtainCompleteMessage(EVENT_ECT_RESULT));
    }

    void
    clearDisconnected() {
        internalClearDisconnected();

        updatePhoneState();
        phone.notifyPreciseCallStateChanged();
    }

    boolean
    canConference() {
        return foregroundCall.getState() == GsmCall.State.ACTIVE
                && backgroundCall.getState() == GsmCall.State.HOLDING
                && !backgroundCall.isFull()
                && !foregroundCall.isFull();
    }

    boolean
    canDial() {
        boolean ret;
        int serviceState = phone.getServiceState().getState();
        String disableCall = SystemProperties.get(
                TelephonyProperties.PROPERTY_DISABLE_CALL, "false");

        ret = (serviceState != ServiceState.STATE_POWER_OFF)
                && pendingMO == null
                && !ringingCall.isRinging()
                && !disableCall.equals("true")
                && (!foregroundCall.getState().isAlive()
                    || !backgroundCall.getState().isAlive());

        return ret;
    }

    boolean
    canTransfer() {
        return foregroundCall.getState() == GsmCall.State.ACTIVE
                && backgroundCall.getState() == GsmCall.State.HOLDING;
    }

    //***** Private Instance Methods

    private void
    internalClearDisconnected() {
        ringingCall.clearDisconnected();
        foregroundCall.clearDisconnected();
        backgroundCall.clearDisconnected();
    }

    /**
     * Obtain a message to use for signalling "invoke getCurrentCalls() when
     * this operation and all other pending operations are complete
     */
    private Message
    obtainCompleteMessage() {
        return obtainCompleteMessage(EVENT_OPERATION_COMPLETE);
    }

    /**
     * Obtain a message to use for signalling "invoke getCurrentCalls() when
     * this operation and all other pending operations are complete
     */
    private Message
    obtainCompleteMessage(int what) {
        pendingOperations++;
        lastRelevantPoll = null;
        needsPoll = true;

        if (DBG_POLL) log("obtainCompleteMessage: pendingOperations=" +
                pendingOperations + ", needsPoll=" + needsPoll);

        return obtainMessage(what);
    }

    private void
    operationComplete() {
        pendingOperations--;

        if (DBG_POLL) log("operationComplete: pendingOperations=" +
                pendingOperations + ", needsPoll=" + needsPoll);

        if (pendingOperations == 0 && needsPoll) {
            lastRelevantPoll = obtainMessage(EVENT_POLL_CALLS_RESULT);
            cm.getCurrentCalls(lastRelevantPoll);
        } else if (pendingOperations < 0) {
            // this should never happen
            Log.e(LOG_TAG,"GsmCallTracker.pendingOperations < 0");
            pendingOperations = 0;
        }
    }

    private void
    updatePhoneState() {
        Phone.State oldState = state;

        if (ringingCall.isRinging()) {
            state = Phone.State.RINGING;
        } else if (pendingMO != null ||
                !(foregroundCall.isIdle() && backgroundCall.isIdle())) {
            state = Phone.State.OFFHOOK;
        } else {
            state = Phone.State.IDLE;
        }

        if (state == Phone.State.IDLE && oldState != state) {
            voiceCallEndedRegistrants.notifyRegistrants(
                new AsyncResult(null, null, null));
        } else if (oldState == Phone.State.IDLE && oldState != state) {
            voiceCallStartedRegistrants.notifyRegistrants (
                    new AsyncResult(null, null, null));
        }

        if (state != oldState) {
            phone.notifyPhoneStateChanged();
        }
    }

    protected void
    handlePollCalls(AsyncResult ar) {
        List polledCalls;

        if (ar.exception == null) {
            polledCalls = (List)ar.result;
        } else if (isCommandExceptionRadioNotAvailable(ar.exception)) {
            // just a dummy empty ArrayList to cause the loop
            // to hang up all the calls
            polledCalls = new ArrayList();
        } else {
            // Radio probably wasn't ready--try again in a bit
            // But don't keep polling if the channel is closed
            pollCallsAfterDelay();
            return;
        }

        Connection newRinging = null; //or waiting
        boolean hasNonHangupStateChanged = false;   // Any change besides
                                                    // a dropped connection
        boolean needsPollDelay = false;
        boolean unknownConnectionAppeared = false;

        for (int i = 0, curDC = 0, dcSize = polledCalls.size()
                ; i < connections.length; i++) {
            GsmConnection conn = connections[i];
            DriverCall dc = null;

            // polledCall list is sparse
            if (curDC < dcSize) {
                dc = (DriverCall) polledCalls.get(curDC);

                if (dc.index == i+1) {
                    curDC++;
                } else {
                    dc = null;
                }
            }

            if (DBG_POLL) log("poll: conn[i=" + i + "]=" +
                    conn+", dc=" + dc);

            if (conn == null && dc != null) {
                // Connection appeared in CLCC response that we don't know about
                if (pendingMO != null && pendingMO.compareTo(dc)) {

                    if (DBG_POLL) log("poll: pendingMO=" + pendingMO);

                    // It's our pending mobile originating call
                    connections[i] = pendingMO;
                    pendingMO.index = i;
                    pendingMO.update(dc);
                    pendingMO = null;

                    // Someone has already asked to hangup this call
                    if (hangupPendingMO) {
                        hangupPendingMO = false;
                        try {
                            if (Phone.DEBUG_PHONE) log(
                                    "poll: hangupPendingMO, hangup conn " + i);
                            hangup(connections[i]);
                        } catch (CallStateException ex) {
                            Log.e(LOG_TAG, "unexpected error on hangup");
                        }

                        // Do not continue processing this poll
                        // Wait for hangup and repoll
                        return;
                    }
                } else {
                    connections[i] = new GsmConnection(phone.getContext(), dc, this, i);

                    // it's a ringing call
                    if (connections[i].getCall() == ringingCall) {
                        newRinging = connections[i];
                    } else {
                        // Something strange happened: a call appeared
                        // which is neither a ringing call or one we created.
                        // Either we've crashed and re-attached to an existing
                        // call, or something else (eg, SIM) initiated the call.

                        Log.i(LOG_TAG,"Phantom call appeared " + dc);

                        // If it's a connected call, set the connect time so that
                        // it's non-zero.  It may not be accurate, but at least
                        // it won't appear as a Missed Call.
                        if (dc.state != DriverCall.State.ALERTING
                                && dc.state != DriverCall.State.DIALING) {
                            connections[i].connectTime = System.currentTimeMillis();
                        }

                        unknownConnectionAppeared = true;
                    }
                }
                hasNonHangupStateChanged = true;
            } else if (conn != null && dc == null) {
                // Connection missing in CLCC response that we were
                // tracking.
                droppedDuringPoll.add(conn);
                // Dropped connections are removed from the CallTracker
                // list but kept in the GsmCall list
                connections[i] = null;
            } else if (conn != null && dc != null && !conn.compareTo(dc)) {
                // Connection in CLCC response does not match what
                // we were tracking. Assume dropped call and new call

                droppedDuringPoll.add(conn);
                connections[i] = new GsmConnection (phone.getContext(), dc, this, i);

                if (connections[i].getCall() == ringingCall) {
                    newRinging = connections[i];
                } // else something strange happened
                hasNonHangupStateChanged = true;
            } else if (conn != null && dc != null) { /* implicit conn.compareTo(dc) */
                boolean changed;
                changed = conn.update(dc);
                hasNonHangupStateChanged = hasNonHangupStateChanged || changed;
            }

            if (REPEAT_POLLING) {
                if (dc != null) {
                    // FIXME with RIL, we should not need this anymore
                    if ((dc.state == DriverCall.State.DIALING
                            /*&& cm.getOption(cm.OPTION_POLL_DIALING)*/)
                        || (dc.state == DriverCall.State.ALERTING
                            /*&& cm.getOption(cm.OPTION_POLL_ALERTING)*/)
                        || (dc.state == DriverCall.State.INCOMING
                            /*&& cm.getOption(cm.OPTION_POLL_INCOMING)*/)
                        || (dc.state == DriverCall.State.WAITING
                            /*&& cm.getOption(cm.OPTION_POLL_WAITING)*/)
                    ) {
                        // Sometimes there's no unsolicited notification
                        // for state transitions
                        needsPollDelay = true;
                    }
                }
            }
        }

        // This is the first poll after an ATD.
        // We expect the pending call to appear in the list
        // If it does not, we land here
        if (pendingMO != null) {
            Log.d(LOG_TAG,"Pending MO dropped before poll fg state:"
                            + foregroundCall.getState());

            droppedDuringPoll.add(pendingMO);
            pendingMO = null;
            hangupPendingMO = false;
        }

        if (newRinging != null) {
            phone.notifyNewRingingConnection(newRinging);
        }

        // clear the "local hangup" and "missed/rejected call"
        // cases from the "dropped during poll" list
        // These cases need no "last call fail" reason
        for (int i = droppedDuringPoll.size() - 1; i >= 0 ; i--) {
            GsmConnection conn = droppedDuringPoll.get(i);

            if (conn.isIncoming() && conn.getConnectTime() == 0) {
                // Missed or rejected call
                Connection.DisconnectCause cause;
                if (conn.cause == Connection.DisconnectCause.LOCAL) {
                    cause = Connection.DisconnectCause.INCOMING_REJECTED;
                } else {
                    cause = Connection.DisconnectCause.INCOMING_MISSED;
                }

                if (Phone.DEBUG_PHONE) {
                    log("missed/rejected call, conn.cause=" + conn.cause);
                    log("setting cause to " + cause);
                }
                droppedDuringPoll.remove(i);
                conn.onDisconnect(cause);
            } else if (conn.cause == Connection.DisconnectCause.LOCAL) {
                // Local hangup
                droppedDuringPoll.remove(i);
                conn.onDisconnect(Connection.DisconnectCause.LOCAL);
            } else if (conn.cause ==
                Connection.DisconnectCause.INVALID_NUMBER) {
                droppedDuringPoll.remove(i);
                conn.onDisconnect(Connection.DisconnectCause.INVALID_NUMBER);
            }
        }

        // Any non-local disconnects: determine cause
        if (droppedDuringPoll.size() > 0) {
            cm.getLastCallFailCause(
                obtainNoPollCompleteMessage(EVENT_GET_LAST_CALL_FAIL_CAUSE));
        }

        if (needsPollDelay) {
            pollCallsAfterDelay();
        }

        // Cases when we can no longer keep disconnected Connection's
        // with their previous calls
        // 1) the phone has started to ring
        // 2) A Call/Connection object has changed state...
        //    we may have switched or held or answered (but not hung up)
        if (newRinging != null || hasNonHangupStateChanged) {
            internalClearDisconnected();
        }

        updatePhoneState();

        if (unknownConnectionAppeared) {
            phone.notifyUnknownConnection();
        }

        if (hasNonHangupStateChanged || newRinging != null) {
            phone.notifyPreciseCallStateChanged();
        }

        //dumpState();
    }

    private void
    handleRadioNotAvailable() {
        // handlePollCalls will clear out its
        // call list when it gets the CommandException
        // error result from this
        pollCallsWhenSafe();
    }

    private void
    dumpState() {
        List l;

        Log.i(LOG_TAG,"Phone State:" + state);

        Log.i(LOG_TAG,"Ringing call: " + ringingCall.toString());

        l = ringingCall.getConnections();
        for (int i = 0, s = l.size(); i < s; i++) {
            Log.i(LOG_TAG,l.get(i).toString());
        }

        Log.i(LOG_TAG,"Foreground call: " + foregroundCall.toString());

        l = foregroundCall.getConnections();
        for (int i = 0, s = l.size(); i < s; i++) {
            Log.i(LOG_TAG,l.get(i).toString());
        }

        Log.i(LOG_TAG,"Background call: " + backgroundCall.toString());

        l = backgroundCall.getConnections();
        for (int i = 0, s = l.size(); i < s; i++) {
            Log.i(LOG_TAG,l.get(i).toString());
        }

    }

    //***** Called from GsmConnection

    /*package*/ void
    hangup (GsmConnection conn) throws CallStateException {
        if (conn.owner != this) {
            throw new CallStateException ("GsmConnection " + conn
                                    + "does not belong to GsmCallTracker " + this);
        }

        if (conn == pendingMO) {
            // We're hanging up an outgoing call that doesn't have it's
            // GSM index assigned yet

            if (Phone.DEBUG_PHONE) log("hangup: set hangupPendingMO to true");
            hangupPendingMO = true;
        } else {
            try {
                cm.hangupConnection (conn.getGSMIndex(), obtainCompleteMessage());
            } catch (CallStateException ex) {
                // Ignore "connection not found"
                // Call may have hung up already
                Log.w(LOG_TAG,"GsmCallTracker WARN: hangup() on absent connection "
                                + conn);
            }
        }

        conn.onHangupLocal();
    }

    /*package*/ void
    separate (GsmConnection conn) throws CallStateException {
        if (conn.owner != this) {
            throw new CallStateException ("GsmConnection " + conn
                                    + "does not belong to GsmCallTracker " + this);
        }
        try {
            cm.separateConnection (conn.getGSMIndex(),
                obtainCompleteMessage(EVENT_SEPARATE_RESULT));
        } catch (CallStateException ex) {
            // Ignore "connection not found"
            // Call may have hung up already
            Log.w(LOG_TAG,"GsmCallTracker WARN: separate() on absent connection "
                          + conn);
        }
    }

    //***** Called from GSMPhone

    /*package*/ void
    setMute(boolean mute) {
        desiredMute = mute;
        cm.setMute(desiredMute, null);
    }

    /*package*/ boolean
    getMute() {
        return desiredMute;
    }


    //***** Called from GsmCall

    /* package */ void
    hangup (GsmCall call) throws CallStateException {
        if (call.getConnections().size() == 0) {
            throw new CallStateException("no connections in call");
        }

        if (call == ringingCall) {
            if (Phone.DEBUG_PHONE) log("(ringing) hangup waiting or background");
            cm.hangupWaitingOrBackground(obtainCompleteMessage());
        } else if (call == foregroundCall) {
            if (call.isDialingOrAlerting()) {
                if (Phone.DEBUG_PHONE) {
                    log("(foregnd) hangup dialing or alerting...");
                }
                hangup((GsmConnection)(call.getConnections().get(0)));
            } else {
                hangupForegroundResumeBackground();
            }
        } else if (call == backgroundCall) {
            if (ringingCall.isRinging()) {
                if (Phone.DEBUG_PHONE) {
                    log("hangup all conns in background call");
                }
                hangupAllConnections(call);
            } else {
                hangupWaitingOrBackground();
            }
        } else {
            throw new RuntimeException ("GsmCall " + call +
                    "does not belong to GsmCallTracker " + this);
        }

        call.onHangupLocal();
        phone.notifyPreciseCallStateChanged();
    }

    /* package */
    void hangupWaitingOrBackground() {
        if (Phone.DEBUG_PHONE) log("hangupWaitingOrBackground");
        cm.hangupWaitingOrBackground(obtainCompleteMessage());
    }

    /* package */
    void hangupForegroundResumeBackground() {
        if (Phone.DEBUG_PHONE) log("hangupForegroundResumeBackground");
        cm.hangupForegroundResumeBackground(obtainCompleteMessage());
    }

    void hangupConnectionByIndex(GsmCall call, int index)
            throws CallStateException {
        int count = call.connections.size();
        for (int i = 0; i < count; i++) {
            GsmConnection cn = (GsmConnection)call.connections.get(i);
            if (cn.getGSMIndex() == index) {
                cm.hangupConnection(index, obtainCompleteMessage());
                return;
            }
        }

        throw new CallStateException("no gsm index found");
    }

    void hangupAllConnections(GsmCall call) throws CallStateException{
        try {
            int count = call.connections.size();
            for (int i = 0; i < count; i++) {
                GsmConnection cn = (GsmConnection)call.connections.get(i);
                cm.hangupConnection(cn.getGSMIndex(), obtainCompleteMessage());
            }
        } catch (CallStateException ex) {
            Log.e(LOG_TAG, "hangupConnectionByIndex caught " + ex);
        }
    }

    /* package */
    GsmConnection getConnectionByIndex(GsmCall call, int index)
            throws CallStateException {
        int count = call.connections.size();
        for (int i = 0; i < count; i++) {
            GsmConnection cn = (GsmConnection)call.connections.get(i);
            if (cn.getGSMIndex() == index) {
                return cn;
            }
        }

        return null;
    }

    private Phone.SuppService getFailedService(int what) {
        switch (what) {
            case EVENT_SWITCH_RESULT:
                return Phone.SuppService.SWITCH;
            case EVENT_CONFERENCE_RESULT:
                return Phone.SuppService.CONFERENCE;
            case EVENT_SEPARATE_RESULT:
                return Phone.SuppService.SEPARATE;
            case EVENT_ECT_RESULT:
                return Phone.SuppService.TRANSFER;
        }
        return Phone.SuppService.UNKNOWN;
    }

    //****** Overridden from Handler

    public void
    handleMessage (Message msg) {
        AsyncResult ar;

        switch (msg.what) {
            case EVENT_POLL_CALLS_RESULT:
                ar = (AsyncResult)msg.obj;

                if (msg == lastRelevantPoll) {
                    if (DBG_POLL) log(
                            "handle EVENT_POLL_CALL_RESULT: set needsPoll=F");
                    needsPoll = false;
                    lastRelevantPoll = null;
                    handlePollCalls((AsyncResult)msg.obj);
                }
            break;

            case EVENT_OPERATION_COMPLETE:
                ar = (AsyncResult)msg.obj;
                operationComplete();
            break;

            case EVENT_SWITCH_RESULT:
            case EVENT_CONFERENCE_RESULT:
            case EVENT_SEPARATE_RESULT:
            case EVENT_ECT_RESULT:
                ar = (AsyncResult)msg.obj;
                if (ar.exception != null) {
                    phone.notifySuppServiceFailed(getFailedService(msg.what));
                }
                operationComplete();
            break;

            case EVENT_GET_LAST_CALL_FAIL_CAUSE:
                int causeCode;
                ar = (AsyncResult)msg.obj;

                operationComplete();

                if (ar.exception != null) {
                    // An exception occurred...just treat the disconnect
                    // cause as "normal"
                    causeCode = CallFailCause.NORMAL_CLEARING;
                    Log.i(LOG_TAG,
                            "Exception during getLastCallFailCause, assuming normal disconnect");
                } else {
                    causeCode = ((int[])ar.result)[0];
                }
                // Log the causeCode if its not normal
                if (causeCode == CallFailCause.NO_CIRCUIT_AVAIL ||
                    causeCode == CallFailCause.TEMPORARY_FAILURE ||
                    causeCode == CallFailCause.SWITCHING_CONGESTION ||
                    causeCode == CallFailCause.CHANNEL_NOT_AVAIL ||
                    causeCode == CallFailCause.QOS_NOT_AVAIL ||
                    causeCode == CallFailCause.BEARER_NOT_AVAIL ||
                    causeCode == CallFailCause.ERROR_UNSPECIFIED) {
                    GsmCellLocation loc = ((GsmCellLocation)phone.getCellLocation());
                    EventLog.writeEvent(EventLogTags.CALL_DROP,
                            causeCode, loc != null ? loc.getCid() : -1,
                            TelephonyManager.getDefault().getNetworkType());
                }

                for (int i = 0, s =  droppedDuringPoll.size()
                        ; i < s ; i++
                ) {
                    GsmConnection conn = droppedDuringPoll.get(i);

                    conn.onRemoteDisconnect(causeCode);
                }

                updatePhoneState();

                phone.notifyPreciseCallStateChanged();
                droppedDuringPoll.clear();
            break;

            case EVENT_REPOLL_AFTER_DELAY:
            case EVENT_CALL_STATE_CHANGE:
                pollCallsWhenSafe();
            break;

            case EVENT_RADIO_AVAILABLE:
                handleRadioAvailable();
            break;

            case EVENT_RADIO_NOT_AVAILABLE:
                handleRadioNotAvailable();
            break;
        }
    }

    protected void log(String msg) {
        Log.d(LOG_TAG, "[GsmCallTracker] " + msg);
    }
}
