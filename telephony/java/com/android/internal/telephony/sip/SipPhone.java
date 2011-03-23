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
import android.media.AudioManager;
import android.net.rtp.AudioGroup;
import android.net.sip.SipAudioCall;
import android.net.sip.SipErrorCode;
import android.net.sip.SipException;
import android.net.sip.SipManager;
import android.net.sip.SipProfile;
import android.net.sip.SipSession;
import android.os.AsyncResult;
import android.os.Message;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneNotifier;
import com.android.internal.telephony.UUSInfo;

import java.text.ParseException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * {@hide}
 */
public class SipPhone extends SipPhoneBase {
    private static final String LOG_TAG = "SipPhone";
    private static final boolean DEBUG = true;
    private static final int TIMEOUT_MAKE_CALL = 15; // in seconds
    private static final int TIMEOUT_ANSWER_CALL = 8; // in seconds
    private static final int TIMEOUT_HOLD_CALL = 15; // in seconds

    // A call that is ringing or (call) waiting
    private SipCall ringingCall = new SipCall();
    private SipCall foregroundCall = new SipCall();
    private SipCall backgroundCall = new SipCall();

    private SipManager mSipManager;
    private SipProfile mProfile;

    SipPhone (Context context, PhoneNotifier notifier, SipProfile profile) {
        super(context, notifier);

        if (DEBUG) Log.d(LOG_TAG, "new SipPhone: " + profile.getUriString());
        ringingCall = new SipCall();
        foregroundCall = new SipCall();
        backgroundCall = new SipCall();
        mProfile = profile;
        mSipManager = SipManager.newInstance(context);
    }

    public String getPhoneName() {
        return "SIP:" + getUriString(mProfile);
    }

    public String getSipUri() {
        return mProfile.getUriString();
    }

    public boolean equals(SipPhone phone) {
        return getSipUri().equals(phone.getSipUri());
    }

    public boolean canTake(Object incomingCall) {
        synchronized (SipPhone.class) {
            if (!(incomingCall instanceof SipAudioCall)) return false;
            if (ringingCall.getState().isAlive()) return false;

            // FIXME: is it true that we cannot take any incoming call if
            // both foreground and background are active
            if (foregroundCall.getState().isAlive()
                    && backgroundCall.getState().isAlive()) {
                return false;
            }

            try {
                SipAudioCall sipAudioCall = (SipAudioCall) incomingCall;
                if (DEBUG) Log.d(LOG_TAG, "+++ taking call from: "
                        + sipAudioCall.getPeerProfile().getUriString());
                String localUri = sipAudioCall.getLocalProfile().getUriString();
                if (localUri.equals(mProfile.getUriString())) {
                    boolean makeCallWait = foregroundCall.getState().isAlive();
                    ringingCall.initIncomingCall(sipAudioCall, makeCallWait);
                    if (sipAudioCall.getState()
                            != SipSession.State.INCOMING_CALL) {
                        // Peer cancelled the call!
                        if (DEBUG) Log.d(LOG_TAG, "    call cancelled !!");
                        ringingCall.reset();
                    }
                    return true;
                }
            } catch (Exception e) {
                // Peer may cancel the call at any time during the time we hook
                // up ringingCall with sipAudioCall. Clean up ringingCall when
                // that happens.
                ringingCall.reset();
            }
            return false;
        }
    }

    public void acceptCall() throws CallStateException {
        synchronized (SipPhone.class) {
            if ((ringingCall.getState() == Call.State.INCOMING) ||
                    (ringingCall.getState() == Call.State.WAITING)) {
                if (DEBUG) Log.d(LOG_TAG, "acceptCall");
                // Always unmute when answering a new call
                ringingCall.setMute(false);
                ringingCall.acceptCall();
            } else {
                throw new CallStateException("phone not ringing");
            }
        }
    }

    public void rejectCall() throws CallStateException {
        synchronized (SipPhone.class) {
            if (ringingCall.getState().isRinging()) {
                if (DEBUG) Log.d(LOG_TAG, "rejectCall");
                ringingCall.rejectCall();
            } else {
                throw new CallStateException("phone not ringing");
            }
        }
    }

    public Connection dial(String dialString, UUSInfo uusinfo) throws CallStateException {
        return dial(dialString);
    }

    public Connection dial(String dialString) throws CallStateException {
        synchronized (SipPhone.class) {
            return dialInternal(dialString);
        }
    }

    private Connection dialInternal(String dialString)
            throws CallStateException {
        clearDisconnected();

        if (!canDial()) {
            throw new CallStateException("cannot dial in current state");
        }
        if (foregroundCall.getState() == SipCall.State.ACTIVE) {
            switchHoldingAndActive();
        }
        if (foregroundCall.getState() != SipCall.State.IDLE) {
            //we should have failed in !canDial() above before we get here
            throw new CallStateException("cannot dial in current state");
        }

        foregroundCall.setMute(false);
        try {
            Connection c = foregroundCall.dial(dialString);
            return c;
        } catch (SipException e) {
            Log.e(LOG_TAG, "dial()", e);
            throw new CallStateException("dial error: " + e);
        }
    }

    public void switchHoldingAndActive() throws CallStateException {
        if (DEBUG) Log.d(LOG_TAG, " ~~~~~~  switch fg and bg");
        synchronized (SipPhone.class) {
            foregroundCall.switchWith(backgroundCall);
            if (backgroundCall.getState().isAlive()) backgroundCall.hold();
            if (foregroundCall.getState().isAlive()) foregroundCall.unhold();
        }
    }

    public boolean canConference() {
        return true;
    }

    public void conference() throws CallStateException {
        synchronized (SipPhone.class) {
            if ((foregroundCall.getState() != SipCall.State.ACTIVE)
                    || (foregroundCall.getState() != SipCall.State.ACTIVE)) {
                throw new CallStateException("wrong state to merge calls: fg="
                        + foregroundCall.getState() + ", bg="
                        + backgroundCall.getState());
            }
            foregroundCall.merge(backgroundCall);
        }
    }

    public void conference(Call that) throws CallStateException {
        synchronized (SipPhone.class) {
            if (!(that instanceof SipCall)) {
                throw new CallStateException("expect " + SipCall.class
                        + ", cannot merge with " + that.getClass());
            }
            foregroundCall.merge((SipCall) that);
        }
    }

    public boolean canTransfer() {
        return false;
    }

    public void explicitCallTransfer() throws CallStateException {
        //mCT.explicitCallTransfer();
    }

    public void clearDisconnected() {
        synchronized (SipPhone.class) {
            ringingCall.clearDisconnected();
            foregroundCall.clearDisconnected();
            backgroundCall.clearDisconnected();

            updatePhoneState();
            notifyPreciseCallStateChanged();
        }
    }

    public void sendDtmf(char c) {
        if (!PhoneNumberUtils.is12Key(c)) {
            Log.e(LOG_TAG,
                    "sendDtmf called with invalid character '" + c + "'");
        } else if (foregroundCall.getState().isAlive()) {
            synchronized (SipPhone.class) {
                foregroundCall.sendDtmf(c);
            }
        }
    }

    public void startDtmf(char c) {
        if (!PhoneNumberUtils.is12Key(c)) {
            Log.e(LOG_TAG,
                "startDtmf called with invalid character '" + c + "'");
        } else {
            sendDtmf(c);
        }
    }

    public void stopDtmf() {
        // no op
    }

    public void sendBurstDtmf(String dtmfString) {
        Log.e(LOG_TAG, "[SipPhone] sendBurstDtmf() is a CDMA method");
    }

    public void getOutgoingCallerIdDisplay(Message onComplete) {
        // FIXME: what to reply?
        AsyncResult.forMessage(onComplete, null, null);
        onComplete.sendToTarget();
    }

    public void setOutgoingCallerIdDisplay(int commandInterfaceCLIRMode,
                                           Message onComplete) {
        // FIXME: what's this for SIP?
        AsyncResult.forMessage(onComplete, null, null);
        onComplete.sendToTarget();
    }

    public void getCallWaiting(Message onComplete) {
        // FIXME: what to reply?
        AsyncResult.forMessage(onComplete, null, null);
        onComplete.sendToTarget();
    }

    public void setCallWaiting(boolean enable, Message onComplete) {
        // FIXME: what to reply?
        Log.e(LOG_TAG, "call waiting not supported");
    }

    @Override
    public void setEchoSuppressionEnabled(boolean enabled) {
        // TODO: Remove the enabled argument. We should check the speakerphone
        // state with AudioManager instead of keeping a state here so the
        // method with a state argument is redundant. Also rename the method
        // to something like onSpeaerphoneStateChanged(). Echo suppression may
        // not be available on every device.
        synchronized (SipPhone.class) {
            foregroundCall.setAudioGroupMode();
        }
    }

    public void setMute(boolean muted) {
        synchronized (SipPhone.class) {
            foregroundCall.setMute(muted);
        }
    }

    public boolean getMute() {
        return (foregroundCall.getState().isAlive()
                ? foregroundCall.getMute()
                : backgroundCall.getMute());
    }

    public Call getForegroundCall() {
        return foregroundCall;
    }

    public Call getBackgroundCall() {
        return backgroundCall;
    }

    public Call getRingingCall() {
        return ringingCall;
    }

    public ServiceState getServiceState() {
        // FIXME: we may need to provide this when data connectivity is lost
        // or when server is down
        return super.getServiceState();
    }

    private String getUriString(SipProfile p) {
        // SipProfile.getUriString() may contain "SIP:" and port
        return p.getUserName() + "@" + getSipDomain(p);
    }

    private String getSipDomain(SipProfile p) {
        String domain = p.getSipDomain();
        // TODO: move this to SipProfile
        if (domain.endsWith(":5060")) {
            return domain.substring(0, domain.length() - 5);
        } else {
            return domain;
        }
    }

    private class SipCall extends SipCallBase {
        void reset() {
            connections.clear();
            setState(Call.State.IDLE);
        }

        void switchWith(SipCall that) {
            synchronized (SipPhone.class) {
                SipCall tmp = new SipCall();
                tmp.takeOver(this);
                this.takeOver(that);
                that.takeOver(tmp);
            }
        }

        private void takeOver(SipCall that) {
            connections = that.connections;
            state = that.state;
            for (Connection c : connections) {
                ((SipConnection) c).changeOwner(this);
            }
        }

        @Override
        public Phone getPhone() {
            return SipPhone.this;
        }

        @Override
        public List<Connection> getConnections() {
            synchronized (SipPhone.class) {
                // FIXME should return Collections.unmodifiableList();
                return connections;
            }
        }

        Connection dial(String originalNumber) throws SipException {
            String calleeSipUri = originalNumber;
            if (!calleeSipUri.contains("@")) {
                String replaceStr = Pattern.quote(mProfile.getUserName() + "@");
                calleeSipUri = mProfile.getUriString().replaceFirst(replaceStr,
                        calleeSipUri + "@");
            }
            try {
                SipProfile callee =
                        new SipProfile.Builder(calleeSipUri).build();
                SipConnection c = new SipConnection(this, callee,
                        originalNumber);
                c.dial();
                connections.add(c);
                setState(Call.State.DIALING);
                return c;
            } catch (ParseException e) {
                throw new SipException("dial", e);
            }
        }

        @Override
        public void hangup() throws CallStateException {
            synchronized (SipPhone.class) {
                if (state.isAlive()) {
                    if (DEBUG) Log.d(LOG_TAG, "hang up call: " + getState()
                            + ": " + this + " on phone " + getPhone());
                    setState(State.DISCONNECTING);
                    CallStateException excp = null;
                    for (Connection c : connections) {
                        try {
                            c.hangup();
                        } catch (CallStateException e) {
                            excp = e;
                        }
                    }
                    if (excp != null) throw excp;
                } else {
                    if (DEBUG) Log.d(LOG_TAG, "hang up dead call: " + getState()
                            + ": " + this + " on phone " + getPhone());
                }
            }
        }

        void initIncomingCall(SipAudioCall sipAudioCall, boolean makeCallWait) {
            SipProfile callee = sipAudioCall.getPeerProfile();
            SipConnection c = new SipConnection(this, callee);
            connections.add(c);

            Call.State newState = makeCallWait ? State.WAITING : State.INCOMING;
            c.initIncomingCall(sipAudioCall, newState);

            setState(newState);
            notifyNewRingingConnectionP(c);
        }

        void rejectCall() throws CallStateException {
            hangup();
        }

        void acceptCall() throws CallStateException {
            if (this != ringingCall) {
                throw new CallStateException("acceptCall() in a non-ringing call");
            }
            if (connections.size() != 1) {
                throw new CallStateException("acceptCall() in a conf call");
            }
            ((SipConnection) connections.get(0)).acceptCall();
        }

        private boolean isSpeakerOn() {
            return ((AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE))
                    .isSpeakerphoneOn();
        }

        void setAudioGroupMode() {
            AudioGroup audioGroup = getAudioGroup();
            if (audioGroup == null) return;
            int mode = audioGroup.getMode();
            if (state == State.HOLDING) {
                audioGroup.setMode(AudioGroup.MODE_ON_HOLD);
            } else if (getMute()) {
                audioGroup.setMode(AudioGroup.MODE_MUTED);
            } else if (isSpeakerOn()) {
                audioGroup.setMode(AudioGroup.MODE_ECHO_SUPPRESSION);
            } else {
                audioGroup.setMode(AudioGroup.MODE_NORMAL);
            }
            if (DEBUG) Log.d(LOG_TAG, String.format(
                    "audioGroup mode change: %d --> %d", mode,
                    audioGroup.getMode()));
        }

        void hold() throws CallStateException {
            setState(State.HOLDING);
            for (Connection c : connections) ((SipConnection) c).hold();
            setAudioGroupMode();
        }

        void unhold() throws CallStateException {
            setState(State.ACTIVE);
            AudioGroup audioGroup = new AudioGroup();
            for (Connection c : connections) {
                ((SipConnection) c).unhold(audioGroup);
            }
            setAudioGroupMode();
        }

        void setMute(boolean muted) {
            for (Connection c : connections) {
                ((SipConnection) c).setMute(muted);
            }
        }

        boolean getMute() {
            return connections.isEmpty()
                    ? false
                    : ((SipConnection) connections.get(0)).getMute();
        }

        void merge(SipCall that) throws CallStateException {
            AudioGroup audioGroup = getAudioGroup();

            // copy to an array to avoid concurrent modification as connections
            // in that.connections will be removed in add(SipConnection).
            Connection[] cc = that.connections.toArray(
                    new Connection[that.connections.size()]);
            for (Connection c : cc) {
                SipConnection conn = (SipConnection) c;
                add(conn);
                if (conn.getState() == Call.State.HOLDING) {
                    conn.unhold(audioGroup);
                }
            }
            that.setState(Call.State.IDLE);
        }

        private void add(SipConnection conn) {
            SipCall call = conn.getCall();
            if (call == this) return;
            if (call != null) call.connections.remove(conn);

            connections.add(conn);
            conn.changeOwner(this);
        }

        void sendDtmf(char c) {
            AudioGroup audioGroup = getAudioGroup();
            if (audioGroup == null) return;
            audioGroup.sendDtmf(convertDtmf(c));
        }

        private int convertDtmf(char c) {
            int code = c - '0';
            if ((code < 0) || (code > 9)) {
                switch (c) {
                    case '*': return 10;
                    case '#': return 11;
                    case 'A': return 12;
                    case 'B': return 13;
                    case 'C': return 14;
                    case 'D': return 15;
                    default:
                        throw new IllegalArgumentException(
                                "invalid DTMF char: " + (int) c);
                }
            }
            return code;
        }

        @Override
        protected void setState(State newState) {
            if (state != newState) {
                if (DEBUG) Log.v(LOG_TAG, "+***+ call state changed: " + state
                        + " --> " + newState + ": " + this + ": on phone "
                        + getPhone() + " " + connections.size());

                if (newState == Call.State.ALERTING) {
                    state = newState; // need in ALERTING to enable ringback
                    SipPhone.this.startRingbackTone();
                } else if (state == Call.State.ALERTING) {
                    SipPhone.this.stopRingbackTone();
                }
                state = newState;
                updatePhoneState();
                notifyPreciseCallStateChanged();
            }
        }

        void onConnectionStateChanged(SipConnection conn) {
            // this can be called back when a conf call is formed
            if (state != State.ACTIVE) {
                setState(conn.getState());
            }
        }

        void onConnectionEnded(SipConnection conn) {
            // set state to DISCONNECTED only when all conns are disconnected
            if (state != State.DISCONNECTED) {
                boolean allConnectionsDisconnected = true;
                if (DEBUG) Log.d(LOG_TAG, "---check connections: "
                        + connections.size());
                for (Connection c : connections) {
                    if (DEBUG) Log.d(LOG_TAG, "   state=" + c.getState() + ": "
                            + c);
                    if (c.getState() != State.DISCONNECTED) {
                        allConnectionsDisconnected = false;
                        break;
                    }
                }
                if (allConnectionsDisconnected) setState(State.DISCONNECTED);
            }
            notifyDisconnectP(conn);
        }

        private AudioGroup getAudioGroup() {
            if (connections.isEmpty()) return null;
            return ((SipConnection) connections.get(0)).getAudioGroup();
        }
    }

    private class SipConnection extends SipConnectionBase {
        private SipCall mOwner;
        private SipAudioCall mSipAudioCall;
        private Call.State mState = Call.State.IDLE;
        private SipProfile mPeer;
        private String mOriginalNumber; // may be a PSTN number
        private boolean mIncoming = false;

        private SipAudioCallAdapter mAdapter = new SipAudioCallAdapter() {
            @Override
            protected void onCallEnded(DisconnectCause cause) {
                if (getDisconnectCause() != DisconnectCause.LOCAL) {
                    setDisconnectCause(cause);
                }
                synchronized (SipPhone.class) {
                    setState(Call.State.DISCONNECTED);
                    SipAudioCall sipAudioCall = mSipAudioCall;
                    mSipAudioCall = null;
                    String sessionState = (sipAudioCall == null)
                            ? ""
                            : (sipAudioCall.getState() + ", ");
                    if (DEBUG) Log.d(LOG_TAG, "--- connection ended: "
                            + mPeer.getUriString() + ": " + sessionState
                            + "cause: " + getDisconnectCause() + ", on phone "
                            + getPhone());
                    if (sipAudioCall != null) {
                        sipAudioCall.setListener(null);
                        sipAudioCall.close();
                    }
                    mOwner.onConnectionEnded(SipConnection.this);
                }
            }

            @Override
            public void onCallEstablished(SipAudioCall call) {
                onChanged(call);
                if (mState == Call.State.ACTIVE) call.startAudio();
            }

            @Override
            public void onCallHeld(SipAudioCall call) {
                onChanged(call);
                if (mState == Call.State.HOLDING) call.startAudio();
            }

            @Override
            public void onChanged(SipAudioCall call) {
                synchronized (SipPhone.class) {
                    Call.State newState = getCallStateFrom(call);
                    if (mState == newState) return;
                    if (newState == Call.State.INCOMING) {
                        setState(mOwner.getState()); // INCOMING or WAITING
                    } else {
                        if (mOwner == ringingCall) {
                            if (ringingCall.getState() == Call.State.WAITING) {
                                try {
                                    switchHoldingAndActive();
                                } catch (CallStateException e) {
                                    // disconnect the call.
                                    onCallEnded(DisconnectCause.LOCAL);
                                    return;
                                }
                            }
                            foregroundCall.switchWith(ringingCall);
                        }
                        setState(newState);
                    }
                    mOwner.onConnectionStateChanged(SipConnection.this);
                    if (DEBUG) Log.v(LOG_TAG, "+***+ connection state changed: "
                            + mPeer.getUriString() + ": " + mState
                            + " on phone " + getPhone());
                }
            }

            @Override
            protected void onError(DisconnectCause cause) {
                if (DEBUG) Log.d(LOG_TAG, "SIP error: " + cause);
                onCallEnded(cause);
            }
        };

        public SipConnection(SipCall owner, SipProfile callee,
                String originalNumber) {
            super(originalNumber);
            mOwner = owner;
            mPeer = callee;
            mOriginalNumber = originalNumber;
        }

        public SipConnection(SipCall owner, SipProfile callee) {
            this(owner, callee, getUriString(callee));
        }

        @Override
        public String getCnapName() {
            String displayName = mPeer.getDisplayName();
            return TextUtils.isEmpty(displayName) ? null
                                                  : displayName;
        }

        @Override
        public int getNumberPresentation() {
            return Connection.PRESENTATION_ALLOWED;
        }

        void initIncomingCall(SipAudioCall sipAudioCall, Call.State newState) {
            setState(newState);
            mSipAudioCall = sipAudioCall;
            sipAudioCall.setListener(mAdapter); // call back to set state
            mIncoming = true;
        }

        void acceptCall() throws CallStateException {
            try {
                mSipAudioCall.answerCall(TIMEOUT_ANSWER_CALL);
            } catch (SipException e) {
                throw new CallStateException("acceptCall(): " + e);
            }
        }

        void changeOwner(SipCall owner) {
            mOwner = owner;
        }

        AudioGroup getAudioGroup() {
            if (mSipAudioCall == null) return null;
            return mSipAudioCall.getAudioGroup();
        }

        void dial() throws SipException {
            setState(Call.State.DIALING);
            mSipAudioCall = mSipManager.makeAudioCall(mProfile, mPeer, null,
                    TIMEOUT_MAKE_CALL);
            mSipAudioCall.setListener(mAdapter);
        }

        void hold() throws CallStateException {
            setState(Call.State.HOLDING);
            try {
                mSipAudioCall.holdCall(TIMEOUT_HOLD_CALL);
            } catch (SipException e) {
                throw new CallStateException("hold(): " + e);
            }
        }

        void unhold(AudioGroup audioGroup) throws CallStateException {
            mSipAudioCall.setAudioGroup(audioGroup);
            setState(Call.State.ACTIVE);
            try {
                mSipAudioCall.continueCall(TIMEOUT_HOLD_CALL);
            } catch (SipException e) {
                throw new CallStateException("unhold(): " + e);
            }
        }

        void setMute(boolean muted) {
            if ((mSipAudioCall != null) && (muted != mSipAudioCall.isMuted())) {
                mSipAudioCall.toggleMute();
            }
        }

        boolean getMute() {
            return (mSipAudioCall == null) ? false
                                           : mSipAudioCall.isMuted();
        }

        @Override
        protected void setState(Call.State state) {
            if (state == mState) return;
            super.setState(state);
            mState = state;
        }

        @Override
        public Call.State getState() {
            return mState;
        }

        @Override
        public boolean isIncoming() {
            return mIncoming;
        }

        @Override
        public String getAddress() {
            // Phone app uses this to query caller ID. Return the original dial
            // number (which may be a PSTN number) instead of the peer's SIP
            // URI.
            return mOriginalNumber;
        }

        @Override
        public SipCall getCall() {
            return mOwner;
        }

        @Override
        protected Phone getPhone() {
            return mOwner.getPhone();
        }

        @Override
        public void hangup() throws CallStateException {
            synchronized (SipPhone.class) {
                if (DEBUG) Log.d(LOG_TAG, "hangup conn: " + mPeer.getUriString()
                        + ": " + mState + ": on phone "
                        + getPhone().getPhoneName());
                if (!mState.isAlive()) return;
                try {
                    SipAudioCall sipAudioCall = mSipAudioCall;
                    if (sipAudioCall != null) {
                        sipAudioCall.setListener(null);
                        sipAudioCall.endCall();
                    }
                } catch (SipException e) {
                    throw new CallStateException("hangup(): " + e);
                } finally {
                    mAdapter.onCallEnded(((mState == Call.State.INCOMING)
                            || (mState == Call.State.WAITING))
                            ? DisconnectCause.INCOMING_REJECTED
                            : DisconnectCause.LOCAL);
                }
            }
        }

        @Override
        public void separate() throws CallStateException {
            synchronized (SipPhone.class) {
                SipCall call = (getPhone() == SipPhone.this)
                        ? (SipCall) SipPhone.this.getBackgroundCall()
                        : (SipCall) SipPhone.this.getForegroundCall();
                if (call.getState() != Call.State.IDLE) {
                    throw new CallStateException(
                            "cannot put conn back to a call in non-idle state: "
                            + call.getState());
                }
                if (DEBUG) Log.d(LOG_TAG, "separate conn: "
                        + mPeer.getUriString() + " from " + mOwner + " back to "
                        + call);

                // separate the AudioGroup and connection from the original call
                Phone originalPhone = getPhone();
                AudioGroup audioGroup = call.getAudioGroup(); // may be null
                call.add(this);
                mSipAudioCall.setAudioGroup(audioGroup);

                // put the original call to bg; and the separated call becomes
                // fg if it was in bg
                originalPhone.switchHoldingAndActive();

                // start audio and notify the phone app of the state change
                call = (SipCall) SipPhone.this.getForegroundCall();
                mSipAudioCall.startAudio();
                call.onConnectionStateChanged(this);
            }
        }

        @Override
        public UUSInfo getUUSInfo() {
            return null;
        }
    }

    private static Call.State getCallStateFrom(SipAudioCall sipAudioCall) {
        if (sipAudioCall.isOnHold()) return Call.State.HOLDING;
        int sessionState = sipAudioCall.getState();
        switch (sessionState) {
            case SipSession.State.READY_TO_CALL:            return Call.State.IDLE;
            case SipSession.State.INCOMING_CALL:
            case SipSession.State.INCOMING_CALL_ANSWERING:  return Call.State.INCOMING;
            case SipSession.State.OUTGOING_CALL:            return Call.State.DIALING;
            case SipSession.State.OUTGOING_CALL_RING_BACK:  return Call.State.ALERTING;
            case SipSession.State.OUTGOING_CALL_CANCELING:  return Call.State.DISCONNECTING;
            case SipSession.State.IN_CALL:                  return Call.State.ACTIVE;
            default:
                Log.w(LOG_TAG, "illegal connection state: " + sessionState);
                return Call.State.DISCONNECTED;
        }
    }

    private abstract class SipAudioCallAdapter extends SipAudioCall.Listener {
        protected abstract void onCallEnded(Connection.DisconnectCause cause);
        protected abstract void onError(Connection.DisconnectCause cause);

        @Override
        public void onCallEnded(SipAudioCall call) {
            onCallEnded(call.isInCall()
                    ? Connection.DisconnectCause.NORMAL
                    : Connection.DisconnectCause.INCOMING_MISSED);
        }

        @Override
        public void onCallBusy(SipAudioCall call) {
            onCallEnded(Connection.DisconnectCause.BUSY);
        }

        @Override
        public void onError(SipAudioCall call, int errorCode,
                String errorMessage) {
            switch (errorCode) {
                case SipErrorCode.SERVER_UNREACHABLE:
                    onError(Connection.DisconnectCause.SERVER_UNREACHABLE);
                    break;
                case SipErrorCode.PEER_NOT_REACHABLE:
                    onError(Connection.DisconnectCause.NUMBER_UNREACHABLE);
                    break;
                case SipErrorCode.INVALID_REMOTE_URI:
                    onError(Connection.DisconnectCause.INVALID_NUMBER);
                    break;
                case SipErrorCode.TIME_OUT:
                case SipErrorCode.TRANSACTION_TERMINTED:
                    onError(Connection.DisconnectCause.TIMED_OUT);
                    break;
                case SipErrorCode.DATA_CONNECTION_LOST:
                    onError(Connection.DisconnectCause.LOST_SIGNAL);
                    break;
                case SipErrorCode.INVALID_CREDENTIALS:
                    onError(Connection.DisconnectCause.INVALID_CREDENTIALS);
                    break;
                case SipErrorCode.CROSS_DOMAIN_AUTHENTICATION:
                    onError(Connection.DisconnectCause.OUT_OF_NETWORK);
                    break;
                case SipErrorCode.SERVER_ERROR:
                    onError(Connection.DisconnectCause.SERVER_ERROR);
                    break;
                case SipErrorCode.SOCKET_ERROR:
                case SipErrorCode.CLIENT_ERROR:
                default:
                    Log.w(LOG_TAG, "error: " + SipErrorCode.toString(errorCode)
                            + ": " + errorMessage);
                    onError(Connection.DisconnectCause.ERROR_UNSPECIFIED);
            }
        }
    }
}
