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

import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.util.Log;
import android.os.SystemProperties;

import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.CallTracker;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.DriverCall;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.TelephonyProperties;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;


/**
 * {@hide}
 */
public final class CdmaCallTracker extends CallTracker {
    static final String LOG_TAG = "CDMA";

    private static final boolean REPEAT_POLLING = false;

    private static final boolean DBG_POLL = false;

    //***** Constants

    static final int MAX_CONNECTIONS = 1;   // only 1 connection allowed in CDMA
    static final int MAX_CONNECTIONS_PER_CALL = 1; // only 1 connection allowed per call

    //***** Instance Variables

    CdmaConnection connections[] = new CdmaConnection[MAX_CONNECTIONS];
    RegistrantList voiceCallEndedRegistrants = new RegistrantList();
    RegistrantList voiceCallStartedRegistrants = new RegistrantList();
    RegistrantList callWaitingRegistrants =  new RegistrantList();


    // connections dropped during last poll
    ArrayList<CdmaConnection> droppedDuringPoll
        = new ArrayList<CdmaConnection>(MAX_CONNECTIONS);

    CdmaCall ringingCall = new CdmaCall(this);
    // A call that is ringing or (call) waiting
    CdmaCall foregroundCall = new CdmaCall(this);
    CdmaCall backgroundCall = new CdmaCall(this);

    CdmaConnection pendingMO;
    boolean hangupPendingMO;
    boolean pendingCallInEcm=false;
    boolean mIsInEmergencyCall = false;
    CDMAPhone phone;

    boolean desiredMute = false;    // false = mute off

    int pendingCallClirMode;
    Phone.State state = Phone.State.IDLE;

    private boolean mIsEcmTimerCanceled = false;

//    boolean needsPoll;



    //***** Events

    //***** Constructors
    CdmaCallTracker(CDMAPhone phone) {
        this.phone = phone;
        cm = phone.mCM;
        cm.registerForCallStateChanged(this, EVENT_CALL_STATE_CHANGE, null);
        cm.registerForOn(this, EVENT_RADIO_AVAILABLE, null);
        cm.registerForNotAvailable(this, EVENT_RADIO_NOT_AVAILABLE, null);
        cm.registerForCallWaitingInfo(this, EVENT_CALL_WAITING_INFO_CDMA, null);
        foregroundCall.setGeneric(false);
    }

    public void dispose() {
        cm.unregisterForCallStateChanged(this);
        cm.unregisterForOn(this);
        cm.unregisterForNotAvailable(this);
        cm.unregisterForCallWaitingInfo(this);
        for(CdmaConnection c : connections) {
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

    @Override
    protected void finalize() {
        Log.d(LOG_TAG, "CdmaCallTracker finalized");
    }

    //***** Instance Methods

    //***** Public Methods
    public void registerForVoiceCallStarted(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        voiceCallStartedRegistrants.add(r);
        // Notify if in call when registering
        if (state != Phone.State.IDLE) {
            r.notifyRegistrant(new AsyncResult(null, null, null));
        }
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

    public void registerForCallWaiting(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        callWaitingRegistrants.add(r);
    }

    public void unregisterForCallWaiting(Handler h) {
        callWaitingRegistrants.remove(h);
    }

    private void
    fakeHoldForegroundBeforeDial() {
        List<Connection> connCopy;

        // We need to make a copy here, since fakeHoldBeforeDial()
        // modifies the lists, and we don't want to reverse the order
        connCopy = (List<Connection>) foregroundCall.connections.clone();

        for (int i = 0, s = connCopy.size() ; i < s ; i++) {
            CdmaConnection conn = (CdmaConnection)connCopy.get(i);

            conn.fakeHoldBeforeDial();
        }
    }

    /**
     * clirMode is one of the CLIR_ constants
     */
    Connection
    dial (String dialString, int clirMode) throws CallStateException {
        // note that this triggers call state changed notif
        clearDisconnected();

        if (!canDial()) {
            throw new CallStateException("cannot dial in current state");
        }

        String inEcm=SystemProperties.get(TelephonyProperties.PROPERTY_INECM_MODE, "false");
        boolean isPhoneInEcmMode = inEcm.equals("true");
        boolean isEmergencyCall =
                PhoneNumberUtils.isLocalEmergencyNumber(dialString, phone.getContext());

        // Cancel Ecm timer if a second emergency call is originating in Ecm mode
        if (isPhoneInEcmMode && isEmergencyCall) {
            handleEcmTimer(phone.CANCEL_ECM_TIMER);
        }

        // We are initiating a call therefore even if we previously
        // didn't know the state (i.e. Generic was true) we now know
        // and therefore can set Generic to false.
        foregroundCall.setGeneric(false);

        // The new call must be assigned to the foreground call.
        // That call must be idle, so place anything that's
        // there on hold
        if (foregroundCall.getState() == CdmaCall.State.ACTIVE) {
            return dialThreeWay(dialString);
        }

        pendingMO = new CdmaConnection(phone.getContext(), checkForTestEmergencyNumber(dialString),
                this, foregroundCall);
        hangupPendingMO = false;

        if (pendingMO.address == null || pendingMO.address.length() == 0
            || pendingMO.address.indexOf(PhoneNumberUtils.WILD) >= 0) {
            // Phone number is invalid
            pendingMO.cause = Connection.DisconnectCause.INVALID_NUMBER;

            // handlePollCalls() will notice this call not present
            // and will mark it as dropped.
            pollCallsWhenSafe();
        } else {
            // Always unmute when initiating a new call
            setMute(false);

            // Check data call
            disableDataCallInEmergencyCall(dialString);

            // In Ecm mode, if another emergency call is dialed, Ecm mode will not exit.
            if(!isPhoneInEcmMode || (isPhoneInEcmMode && isEmergencyCall)) {
                cm.dial(pendingMO.address, clirMode, obtainCompleteMessage());
            } else {
                phone.exitEmergencyCallbackMode();
                phone.setOnEcbModeExitResponse(this,EVENT_EXIT_ECM_RESPONSE_CDMA, null);
                pendingCallClirMode=clirMode;
                pendingCallInEcm=true;
            }
        }

        updatePhoneState();
        phone.notifyPreciseCallStateChanged();

        return pendingMO;
    }


    Connection
    dial (String dialString) throws CallStateException {
        return dial(dialString, CommandsInterface.CLIR_DEFAULT);
    }

    private Connection
    dialThreeWay (String dialString) {
        if (!foregroundCall.isIdle()) {
            // Check data call
            disableDataCallInEmergencyCall(dialString);

            // Attach the new connection to foregroundCall
            pendingMO = new CdmaConnection(phone.getContext(),
                                checkForTestEmergencyNumber(dialString), this, foregroundCall);
            cm.sendCDMAFeatureCode(pendingMO.address,
                obtainMessage(EVENT_THREE_WAY_DIAL_L2_RESULT_CDMA));
            return pendingMO;
        }
        return null;
    }

    void
    acceptCall() throws CallStateException {
        if (ringingCall.getState() == CdmaCall.State.INCOMING) {
            Log.i("phone", "acceptCall: incoming...");
            // Always unmute when answering a new call
            setMute(false);
            cm.acceptCall(obtainCompleteMessage());
        } else if (ringingCall.getState() == CdmaCall.State.WAITING) {
            CdmaConnection cwConn = (CdmaConnection)(ringingCall.getLatestConnection());

            // Since there is no network response for supplimentary
            // service for CDMA, we assume call waiting is answered.
            // ringing Call state change to idle is in CdmaCall.detach
            // triggered by updateParent.
            cwConn.updateParent(ringingCall, foregroundCall);
            cwConn.onConnectedInOrOut();
            updatePhoneState();
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
        if (ringingCall.getState() == CdmaCall.State.INCOMING) {
            throw new CallStateException("cannot be in the incoming state");
        } else if (foregroundCall.getConnections().size() > 1) {
            flashAndSetGenericTrue();
        } else {
            // Send a flash command to CDMA network for putting the other party on hold.
            // For CDMA networks which do not support this the user would just hear a beep
            // from the network. For CDMA networks which do support it will put the other
            // party on hold.
            cm.sendCDMAFeatureCode("", obtainMessage(EVENT_SWITCH_RESULT));
        }
    }

    void
    conference() throws CallStateException {
        // Should we be checking state?
        flashAndSetGenericTrue();
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
        return foregroundCall.getState() == CdmaCall.State.ACTIVE
                && backgroundCall.getState() == CdmaCall.State.HOLDING
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
                    || (foregroundCall.getState() == CdmaCall.State.ACTIVE)
                    || !backgroundCall.getState().isAlive());

        if (!ret) {
            log(String.format("canDial is false\n" +
                              "((serviceState=%d) != ServiceState.STATE_POWER_OFF)::=%s\n" +
                              "&& pendingMO == null::=%s\n" +
                              "&& !ringingCall.isRinging()::=%s\n" +
                              "&& !disableCall.equals(\"true\")::=%s\n" +
                              "&& (!foregroundCall.getState().isAlive()::=%s\n" +
                              "   || foregroundCall.getState() == CdmaCall.State.ACTIVE::=%s\n" +
                              "   ||!backgroundCall.getState().isAlive())::=%s)",
                    serviceState,
                    serviceState != ServiceState.STATE_POWER_OFF,
                    pendingMO == null,
                    !ringingCall.isRinging(),
                    !disableCall.equals("true"),
                    !foregroundCall.getState().isAlive(),
                    foregroundCall.getState() == CdmaCall.State.ACTIVE,
                    !backgroundCall.getState().isAlive()));
        }
        return ret;
    }

    boolean
    canTransfer() {
        Log.e(LOG_TAG, "canTransfer: not possible in CDMA");
        return false;
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
            Log.e(LOG_TAG,"CdmaCallTracker.pendingOperations < 0");
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
        if (Phone.DEBUG_PHONE) {
            log("update phone state, old=" + oldState + " new="+ state);
        }
        if (state != oldState) {
            phone.notifyPhoneStateChanged();
        }
    }

    // ***** Overwritten from CallTracker

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
            CdmaConnection conn = connections[i];
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
                        // Re-start Ecm timer when an uncompleted emergency call ends
                        if (mIsEcmTimerCanceled) {
                            handleEcmTimer(phone.RESTART_ECM_TIMER);
                        }

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
                    if (Phone.DEBUG_PHONE) {
                        log("pendingMo=" + pendingMO + ", dc=" + dc);
                    }
                    // find if the MT call is a new ring or unknown connection
                    newRinging = checkMtFindNewRinging(dc,i);
                    if (newRinging == null) {
                        unknownConnectionAppeared = true;
                    }
                    checkAndEnableDataCallAfterEmergencyCallDropped();
                }
                hasNonHangupStateChanged = true;
            } else if (conn != null && dc == null) {
                // This case means the RIL has no more active call anymore and
                // we need to clean up the foregroundCall and ringingCall.
                // Loop through foreground call connections as
                // it contains the known logical connections.
                int count = foregroundCall.connections.size();
                for (int n = 0; n < count; n++) {
                    if (Phone.DEBUG_PHONE) log("adding fgCall cn " + n + " to droppedDuringPoll");
                    CdmaConnection cn = (CdmaConnection)foregroundCall.connections.get(n);
                    droppedDuringPoll.add(cn);
                }
                count = ringingCall.connections.size();
                // Loop through ringing call connections as
                // it may contain the known logical connections.
                for (int n = 0; n < count; n++) {
                    if (Phone.DEBUG_PHONE) log("adding rgCall cn " + n + " to droppedDuringPoll");
                    CdmaConnection cn = (CdmaConnection)ringingCall.connections.get(n);
                    droppedDuringPoll.add(cn);
                }
                foregroundCall.setGeneric(false);
                ringingCall.setGeneric(false);

                // Re-start Ecm timer when the connected emergency call ends
                if (mIsEcmTimerCanceled) {
                    handleEcmTimer(phone.RESTART_ECM_TIMER);
                }
                // If emergency call is not going through while dialing
                checkAndEnableDataCallAfterEmergencyCallDropped();

                // Dropped connections are removed from the CallTracker
                // list but kept in the Call list
                connections[i] = null;
            } else if (conn != null && dc != null) { /* implicit conn.compareTo(dc) */
                // Call collision case
                if (conn.isIncoming != dc.isMT) {
                    if (dc.isMT == true){
                        // Mt call takes precedence than Mo,drops Mo
                        droppedDuringPoll.add(conn);
                        // find if the MT call is a new ring or unknown connection
                        newRinging = checkMtFindNewRinging(dc,i);
                        if (newRinging == null) {
                            unknownConnectionAppeared = true;
                        }
                        checkAndEnableDataCallAfterEmergencyCallDropped();
                    } else {
                        // Call info stored in conn is not consistent with the call info from dc.
                        // We should follow the rule of MT calls taking precedence over MO calls
                        // when there is conflict, so here we drop the call info from dc and
                        // continue to use the call info from conn, and only take a log.
                        Log.e(LOG_TAG,"Error in RIL, Phantom call appeared " + dc);
                    }
                } else {
                    boolean changed;
                    changed = conn.update(dc);
                    hasNonHangupStateChanged = hasNonHangupStateChanged || changed;
                }
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
            if( pendingCallInEcm) {
                pendingCallInEcm = false;
            }
        }

        if (newRinging != null) {
            phone.notifyNewRingingConnection(newRinging);
        }

        // clear the "local hangup" and "missed/rejected call"
        // cases from the "dropped during poll" list
        // These cases need no "last call fail" reason
        for (int i = droppedDuringPoll.size() - 1; i >= 0 ; i--) {
            CdmaConnection conn = droppedDuringPoll.get(i);

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
            } else if (conn.cause == Connection.DisconnectCause.INVALID_NUMBER) {
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

    //***** Called from CdmaConnection
    /*package*/ void
    hangup (CdmaConnection conn) throws CallStateException {
        if (conn.owner != this) {
            throw new CallStateException ("CdmaConnection " + conn
                                    + "does not belong to CdmaCallTracker " + this);
        }

        if (conn == pendingMO) {
            // We're hanging up an outgoing call that doesn't have it's
            // GSM index assigned yet

            if (Phone.DEBUG_PHONE) log("hangup: set hangupPendingMO to true");
            hangupPendingMO = true;
        } else if ((conn.getCall() == ringingCall)
                && (ringingCall.getState() == CdmaCall.State.WAITING)) {
            // Handle call waiting hang up case.
            //
            // The ringingCall state will change to IDLE in CdmaCall.detach
            // if the ringing call connection size is 0. We don't specifically
            // set the ringing call state to IDLE here to avoid a race condition
            // where a new call waiting could get a hang up from an old call
            // waiting ringingCall.
            //
            // PhoneApp does the call log itself since only PhoneApp knows
            // the hangup reason is user ignoring or timing out. So conn.onDisconnect()
            // is not called here. Instead, conn.onLocalDisconnect() is called.
            conn.onLocalDisconnect();
            updatePhoneState();
            phone.notifyPreciseCallStateChanged();
            return;
        } else {
            try {
                cm.hangupConnection (conn.getCDMAIndex(), obtainCompleteMessage());
            } catch (CallStateException ex) {
                // Ignore "connection not found"
                // Call may have hung up already
                Log.w(LOG_TAG,"CdmaCallTracker WARN: hangup() on absent connection "
                                + conn);
            }
        }

        conn.onHangupLocal();
    }

    /*package*/ void
    separate (CdmaConnection conn) throws CallStateException {
        if (conn.owner != this) {
            throw new CallStateException ("CdmaConnection " + conn
                                    + "does not belong to CdmaCallTracker " + this);
        }
        try {
            cm.separateConnection (conn.getCDMAIndex(),
                obtainCompleteMessage(EVENT_SEPARATE_RESULT));
        } catch (CallStateException ex) {
            // Ignore "connection not found"
            // Call may have hung up already
            Log.w(LOG_TAG,"CdmaCallTracker WARN: separate() on absent connection "
                          + conn);
        }
    }

    //***** Called from CDMAPhone

    /*package*/ void
    setMute(boolean mute) {
        desiredMute = mute;
        cm.setMute(desiredMute, null);
    }

    /*package*/ boolean
    getMute() {
        return desiredMute;
    }


    //***** Called from CdmaCall

    /* package */ void
    hangup (CdmaCall call) throws CallStateException {
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
                hangup((CdmaConnection)(call.getConnections().get(0)));
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
            throw new RuntimeException ("CdmaCall " + call +
                    "does not belong to CdmaCallTracker " + this);
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

    void hangupConnectionByIndex(CdmaCall call, int index)
            throws CallStateException {
        int count = call.connections.size();
        for (int i = 0; i < count; i++) {
            CdmaConnection cn = (CdmaConnection)call.connections.get(i);
            if (cn.getCDMAIndex() == index) {
                cm.hangupConnection(index, obtainCompleteMessage());
                return;
            }
        }

        throw new CallStateException("no gsm index found");
    }

    void hangupAllConnections(CdmaCall call) throws CallStateException{
        try {
            int count = call.connections.size();
            for (int i = 0; i < count; i++) {
                CdmaConnection cn = (CdmaConnection)call.connections.get(i);
                cm.hangupConnection(cn.getCDMAIndex(), obtainCompleteMessage());
            }
        } catch (CallStateException ex) {
            Log.e(LOG_TAG, "hangupConnectionByIndex caught " + ex);
        }
    }

    /* package */
    CdmaConnection getConnectionByIndex(CdmaCall call, int index)
            throws CallStateException {
        int count = call.connections.size();
        for (int i = 0; i < count; i++) {
            CdmaConnection cn = (CdmaConnection)call.connections.get(i);
            if (cn.getCDMAIndex() == index) {
                return cn;
            }
        }

        return null;
    }

    private void flashAndSetGenericTrue() throws CallStateException {
        cm.sendCDMAFeatureCode("", obtainMessage(EVENT_SWITCH_RESULT));

        // Set generic to true because in CDMA it is not known what
        // the status of the call is after a call waiting is answered,
        // 3 way call merged or a switch between calls.
        foregroundCall.setGeneric(true);
        phone.notifyPreciseCallStateChanged();
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

    private void handleRadioNotAvailable() {
        // handlePollCalls will clear out its
        // call list when it gets the CommandException
        // error result from this
        pollCallsWhenSafe();
    }

    private void notifyCallWaitingInfo(CdmaCallWaitingNotification obj) {
        if (callWaitingRegistrants != null) {
            callWaitingRegistrants.notifyRegistrants(new AsyncResult(null, obj, null));
        }
    }

    private void handleCallWaitingInfo (CdmaCallWaitingNotification cw) {
        // Check how many connections in foregroundCall.
        // If the connection in foregroundCall is more
        // than one, then the connection information is
        // not reliable anymore since it means either
        // call waiting is connected or 3 way call is
        // dialed before, so set generic.
        if (foregroundCall.connections.size() > 1 ) {
            foregroundCall.setGeneric(true);
        }

        // Create a new CdmaConnection which attaches itself to ringingCall.
        ringingCall.setGeneric(false);
        new CdmaConnection(phone.getContext(), cw, this, ringingCall);
        updatePhoneState();

        // Finally notify application
        notifyCallWaitingInfo(cw);
    }
    //****** Overridden from Handler

    public void
    handleMessage (Message msg) {
        AsyncResult ar;

        switch (msg.what) {
            case EVENT_POLL_CALLS_RESULT:{
                Log.d(LOG_TAG, "Event EVENT_POLL_CALLS_RESULT Received");
                ar = (AsyncResult)msg.obj;

                if(msg == lastRelevantPoll) {
                    if(DBG_POLL) log(
                            "handle EVENT_POLL_CALL_RESULT: set needsPoll=F");
                    needsPoll = false;
                    lastRelevantPoll = null;
                    handlePollCalls((AsyncResult)msg.obj);
                }
            }
            break;

            case EVENT_OPERATION_COMPLETE:
                operationComplete();
            break;

            case EVENT_SWITCH_RESULT:
                 // In GSM call operationComplete() here which gets the
                 // current call list. But in CDMA there is no list so
                 // there is nothing to do.
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

                for (int i = 0, s =  droppedDuringPoll.size()
                        ; i < s ; i++
                ) {
                    CdmaConnection conn = droppedDuringPoll.get(i);

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

            case EVENT_EXIT_ECM_RESPONSE_CDMA:
               //no matter the result, we still do the same here
               if (pendingCallInEcm) {
                   cm.dial(pendingMO.address, pendingCallClirMode, obtainCompleteMessage());
                   pendingCallInEcm = false;
               }
               phone.unsetOnEcbModeExitResponse(this);
            break;

            case EVENT_CALL_WAITING_INFO_CDMA:
               ar = (AsyncResult)msg.obj;
               if (ar.exception == null) {
                   handleCallWaitingInfo((CdmaCallWaitingNotification)ar.result);
                   Log.d(LOG_TAG, "Event EVENT_CALL_WAITING_INFO_CDMA Received");
               }
            break;

            case EVENT_THREE_WAY_DIAL_L2_RESULT_CDMA:
                ar = (AsyncResult)msg.obj;
                if (ar.exception == null) {
                    // Assume 3 way call is connected
                    pendingMO.onConnectedInOrOut();
                    pendingMO = null;
                }
            break;

            default:{
               throw new RuntimeException("unexpected event not handled");
            }
        }
    }

    /**
     * Handle Ecm timer to be canceled or re-started
     */
    private void handleEcmTimer(int action) {
        phone.handleTimerInEmergencyCallbackMode(action);
        switch(action) {
        case CDMAPhone.CANCEL_ECM_TIMER: mIsEcmTimerCanceled = true; break;
        case CDMAPhone.RESTART_ECM_TIMER: mIsEcmTimerCanceled = false; break;
        default:
            Log.e(LOG_TAG, "handleEcmTimer, unsupported action " + action);
        }
    }

    /**
     * Disable data call when emergency call is connected
     */
    private void disableDataCallInEmergencyCall(String dialString) {
        if (PhoneNumberUtils.isLocalEmergencyNumber(dialString, phone.getContext())) {
            if (Phone.DEBUG_PHONE) log("disableDataCallInEmergencyCall");
            mIsInEmergencyCall = true;
            phone.mDataConnectionTracker.setInternalDataEnabled(false);
        }
    }

    /**
     * Check and enable data call after an emergency call is dropped if it's
     * not in ECM
     */
    private void checkAndEnableDataCallAfterEmergencyCallDropped() {
        if (mIsInEmergencyCall) {
            mIsInEmergencyCall = false;
            String inEcm=SystemProperties.get(TelephonyProperties.PROPERTY_INECM_MODE, "false");
            if (Phone.DEBUG_PHONE) {
                log("checkAndEnableDataCallAfterEmergencyCallDropped,inEcm=" + inEcm);
            }
            if (inEcm.compareTo("false") == 0) {
                // Re-initiate data connection
                phone.mDataConnectionTracker.setInternalDataEnabled(true);
            }
        }
    }

    /**
     * Check the MT call to see if it's a new ring or
     * a unknown connection.
     */
    private Connection checkMtFindNewRinging(DriverCall dc, int i) {

        Connection newRinging = null;

        connections[i] = new CdmaConnection(phone.getContext(), dc, this, i);
        // it's a ringing call
        if (connections[i].getCall() == ringingCall) {
            newRinging = connections[i];
            if (Phone.DEBUG_PHONE) log("Notify new ring " + dc);
        } else {
            // Something strange happened: a call which is neither
            // a ringing call nor the one we created. It could be the
            // call collision result from RIL
            Log.e(LOG_TAG,"Phantom call appeared " + dc);
            // If it's a connected call, set the connect time so that
            // it's non-zero.  It may not be accurate, but at least
            // it won't appear as a Missed Call.
            if (dc.state != DriverCall.State.ALERTING
                && dc.state != DriverCall.State.DIALING) {
                connections[i].connectTime = System.currentTimeMillis();
            }
        }
        return newRinging;
    }

    /**
     * Check if current call is in emergency call
     *
     * @return true if it is in emergency call
     *         false if it is not in emergency call
     */
    boolean isInEmergencyCall() {
        return mIsInEmergencyCall;
    }

    protected void log(String msg) {
        Log.d(LOG_TAG, "[CdmaCallTracker] " + msg);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("GsmCallTracker extends:");
        super.dump(fd, pw, args);
        pw.println("droppedDuringPoll: length=" + connections.length);
        for(int i=0; i < connections.length; i++) {
            pw.printf(" connections[%d]=%s\n", i, connections[i]);
        }
        pw.println(" voiceCallEndedRegistrants=" + voiceCallEndedRegistrants);
        pw.println(" voiceCallStartedRegistrants=" + voiceCallStartedRegistrants);
        pw.println(" callWaitingRegistrants=" + callWaitingRegistrants);
        pw.println("droppedDuringPoll: size=" + droppedDuringPoll.size());
        for(int i = 0; i < droppedDuringPoll.size(); i++) {
            pw.printf( " droppedDuringPoll[%d]=%s\n", i, droppedDuringPoll.get(i));
        }
        pw.println(" ringingCall=" + ringingCall);
        pw.println(" foregroundCall=" + foregroundCall);
        pw.println(" backgroundCall=" + backgroundCall);
        pw.println(" pendingMO=" + pendingMO);
        pw.println(" hangupPendingMO=" + hangupPendingMO);
        pw.println(" pendingCallInEcm=" + pendingCallInEcm);
        pw.println(" mIsInEmergencyCall=" + mIsInEmergencyCall);
        pw.println(" phone=" + phone);
        pw.println(" desiredMute=" + desiredMute);
        pw.println(" pendingCallClirMode=" + pendingCallClirMode);
        pw.println(" state=" + state);
        pw.println(" mIsEcmTimerCanceled=" + mIsEcmTimerCanceled);
    }
}
