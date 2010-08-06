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

package com.android.server.sip;

import gov.nist.javax.sip.clientauthutils.AccountManager;
import gov.nist.javax.sip.clientauthutils.UserCredentials;
import gov.nist.javax.sip.header.SIPHeaderNames;
import gov.nist.javax.sip.header.WWWAuthenticate;

import android.net.sip.ISipSession;
import android.net.sip.ISipSessionListener;
import android.net.sip.SessionDescription;
import android.net.sip.SipProfile;
import android.net.sip.SipSessionAdapter;
import android.net.sip.SipSessionState;
import android.text.TextUtils;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramSocket;
import java.text.ParseException;
import java.util.Collection;
import java.util.EventObject;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TooManyListenersException;

import javax.sip.ClientTransaction;
import javax.sip.Dialog;
import javax.sip.DialogTerminatedEvent;
import javax.sip.IOExceptionEvent;
import javax.sip.InvalidArgumentException;
import javax.sip.ListeningPoint;
import javax.sip.RequestEvent;
import javax.sip.ResponseEvent;
import javax.sip.ServerTransaction;
import javax.sip.SipException;
import javax.sip.SipFactory;
import javax.sip.SipListener;
import javax.sip.SipProvider;
import javax.sip.SipStack;
import javax.sip.TimeoutEvent;
import javax.sip.Transaction;
import javax.sip.TransactionState;
import javax.sip.TransactionTerminatedEvent;
import javax.sip.address.Address;
import javax.sip.address.SipURI;
import javax.sip.header.CSeqHeader;
import javax.sip.header.ExpiresHeader;
import javax.sip.header.FromHeader;
import javax.sip.header.MinExpiresHeader;
import javax.sip.header.ViaHeader;
import javax.sip.message.Message;
import javax.sip.message.Request;
import javax.sip.message.Response;

/**
 * Manages {@link ISipSession}'s for a SIP account.
 */
class SipSessionGroup implements SipListener {
    private static final String TAG = "SipSession";
    private static final String ANONYMOUS = "anonymous";
    private static final int EXPIRY_TIME = 3600;

    private static final EventObject DEREGISTER = new EventObject("Deregister");
    private static final EventObject END_CALL = new EventObject("End call");
    private static final EventObject HOLD_CALL = new EventObject("Hold call");
    private static final EventObject CONTINUE_CALL
            = new EventObject("Continue call");

    private final SipProfile mLocalProfile;
    private final String mPassword;

    private SipStack mSipStack;
    private SipHelper mSipHelper;
    private String mLastNonce;
    private int mRPort;

    // session that processes INVITE requests
    private SipSessionImpl mCallReceiverSession;
    private String mLocalIp;

    // call-id-to-SipSession map
    private Map<String, SipSessionImpl> mSessionMap =
            new HashMap<String, SipSessionImpl>();

    /**
     * @param myself the local profile with password crossed out
     * @param password the password of the profile
     * @throws IOException if cannot assign requested address
     */
    public SipSessionGroup(String localIp, SipProfile myself, String password)
            throws SipException, IOException {
        mLocalProfile = myself;
        mPassword = password;
        reset(localIp);
    }

    void reset(String localIp) throws SipException, IOException {
        mLocalIp = localIp;
        if (localIp == null) return;

        SipProfile myself = mLocalProfile;
        SipFactory sipFactory = SipFactory.getInstance();
        Properties properties = new Properties();
        properties.setProperty("javax.sip.STACK_NAME", getStackName());
        String outboundProxy = myself.getProxyAddress();
        if (!TextUtils.isEmpty(outboundProxy)) {
            properties.setProperty("javax.sip.OUTBOUND_PROXY", outboundProxy
                    + ":" + myself.getPort() + "/" + myself.getProtocol());
        }
        SipStack stack = mSipStack = sipFactory.createSipStack(properties);

        try {
            SipProvider provider = stack.createSipProvider(
                    stack.createListeningPoint(localIp, allocateLocalPort(),
                            myself.getProtocol()));
            provider.addSipListener(this);
            mSipHelper = new SipHelper(stack, provider);
        } catch (InvalidArgumentException e) {
            throw new IOException(e.getMessage());
        } catch (TooManyListenersException e) {
            // must never happen
            throw new SipException("SipSessionGroup constructor", e);
        }
        Log.d(TAG, " start stack for " + myself.getUriString());
        stack.start();

        mLastNonce = null;
        mCallReceiverSession = null;
        mSessionMap.clear();
    }

    public SipProfile getLocalProfile() {
        return mLocalProfile;
    }

    public String getLocalProfileUri() {
        return mLocalProfile.getUriString();
    }

    private String getStackName() {
        return "stack" + System.currentTimeMillis();
    }

    public synchronized void close() {
        Log.d(TAG, " close stack for " + mLocalProfile.getUriString());
        mSessionMap.clear();
        closeToNotReceiveCalls();
        if (mSipStack != null) {
            mSipStack.stop();
            mSipStack = null;
            mSipHelper = null;
        }
    }

    public synchronized boolean isClosed() {
        return (mSipStack == null);
    }

    // For internal use, require listener not to block in callbacks.
    public synchronized void openToReceiveCalls(ISipSessionListener listener) {
        if (mCallReceiverSession == null) {
            mCallReceiverSession = new SipSessionCallReceiverImpl(listener);
        } else {
            mCallReceiverSession.setListener(listener);
        }
    }

    public synchronized void closeToNotReceiveCalls() {
        mCallReceiverSession = null;
    }

    public ISipSession createSession(ISipSessionListener listener) {
        return (isClosed() ? null : new SipSessionImpl(listener));
    }

    private static int allocateLocalPort() throws SipException {
        try {
            DatagramSocket s = new DatagramSocket();
            int localPort = s.getLocalPort();
            s.close();
            return localPort;
        } catch (IOException e) {
            throw new SipException("allocateLocalPort()", e);
        }
    }

    private synchronized SipSessionImpl getSipSession(EventObject event) {
        String key = SipHelper.getCallId(event);
        Log.d(TAG, " sesssion key from event: " + key);
        Log.d(TAG, " active sessions:");
        for (String k : mSessionMap.keySet()) {
            Log.d(TAG, "   .....  '" + k + "': " + mSessionMap.get(k));
        }
        SipSessionImpl session = mSessionMap.get(key);
        return ((session != null) ? session : mCallReceiverSession);
    }

    private synchronized void addSipSession(SipSessionImpl newSession) {
        removeSipSession(newSession);
        String key = newSession.getCallId();
        Log.d(TAG, " +++++  add a session with key:  '" + key + "'");
        mSessionMap.put(key, newSession);
        for (String k : mSessionMap.keySet()) {
            Log.d(TAG, "   .....  " + k + ": " + mSessionMap.get(k));
        }
    }

    private synchronized void removeSipSession(SipSessionImpl session) {
        if (session == mCallReceiverSession) return;
        String key = session.getCallId();
        SipSessionImpl s = mSessionMap.remove(key);
        // sanity check
        if ((s != null) && (s != session)) {
            Log.w(TAG, "session " + session + " is not associated with key '"
                    + key + "'");
            mSessionMap.put(key, s);
            for (Map.Entry<String, SipSessionImpl> entry
                    : mSessionMap.entrySet()) {
                if (entry.getValue() == s) {
                    key = entry.getKey();
                    mSessionMap.remove(key);
                }
            }
        }
        Log.d(TAG, "   remove session " + session + " with key '" + key + "'");

        for (String k : mSessionMap.keySet()) {
            Log.d(TAG, "   .....  " + k + ": " + mSessionMap.get(k));
        }
    }

    public void processRequest(RequestEvent event) {
        process(event);
    }

    public void processResponse(ResponseEvent event) {
        process(event);
    }

    public void processIOException(IOExceptionEvent event) {
        process(event);
    }

    public void processTimeout(TimeoutEvent event) {
        process(event);
    }

    public void processTransactionTerminated(TransactionTerminatedEvent event) {
        process(event);
    }

    public void processDialogTerminated(DialogTerminatedEvent event) {
        process(event);
    }

    private synchronized void process(EventObject event) {
        SipSessionImpl session = getSipSession(event);
        try {
            if ((session != null) && session.process(event)) {
                Log.d(TAG, " ~~~~~   new state: " + session.mState);
            } else {
                Log.d(TAG, "event not processed: " + event);
            }
        } catch (Throwable e) {
            Log.e(TAG, "event process error: " + event, e);
            session.onError(e);
        }
    }

    private class SipSessionCallReceiverImpl extends SipSessionImpl {
        public SipSessionCallReceiverImpl(ISipSessionListener listener) {
            super(listener);
        }

        public boolean process(EventObject evt) throws SipException {
            Log.d(TAG, " ~~~~~   " + this + ": " + mState + ": processing "
                    + log(evt));
            if (isRequestEvent(Request.INVITE, evt)) {
                RequestEvent event = (RequestEvent) evt;
                SipSessionImpl newSession = new SipSessionImpl(mProxy);
                newSession.mServerTransaction = mSipHelper.sendRinging(event,
                        generateTag());
                newSession.mDialog = newSession.mServerTransaction.getDialog();
                newSession.mInviteReceived = event;
                newSession.mPeerProfile = createPeerProfile(event.getRequest());
                newSession.mState = SipSessionState.INCOMING_CALL;
                newSession.mPeerSessionDescription =
                        event.getRequest().getRawContent();
                addSipSession(newSession);
                mProxy.onRinging(newSession, newSession.mPeerProfile,
                        newSession.mPeerSessionDescription);
                return true;
            } else {
                return false;
            }
        }
    }

    class SipSessionImpl extends ISipSession.Stub {
        SipProfile mPeerProfile;
        SipSessionListenerProxy mProxy = new SipSessionListenerProxy();
        SipSessionState mState = SipSessionState.READY_TO_CALL;
        RequestEvent mInviteReceived;
        Dialog mDialog;
        ServerTransaction mServerTransaction;
        ClientTransaction mClientTransaction;
        byte[] mPeerSessionDescription;
        boolean mInCall;
        boolean mReRegisterFlag = false;

        public SipSessionImpl(ISipSessionListener listener) {
            setListener(listener);
        }

        SipSessionImpl duplicate() {
            return new SipSessionImpl(mProxy.getListener());
        }

        private void reset() {
            mInCall = false;
            removeSipSession(this);
            mPeerProfile = null;
            mState = SipSessionState.READY_TO_CALL;
            mInviteReceived = null;
            mDialog = null;
            mServerTransaction = null;
            mClientTransaction = null;
            mPeerSessionDescription = null;
        }

        public boolean isInCall() {
            return mInCall;
        }

        public String getLocalIp() {
            return mLocalIp;
        }

        public SipProfile getLocalProfile() {
            return mLocalProfile;
        }

        public SipProfile getPeerProfile() {
            return mPeerProfile;
        }

        public String getCallId() {
            return SipHelper.getCallId(getTransaction());
        }

        private Transaction getTransaction() {
            if (mClientTransaction != null) return mClientTransaction;
            if (mServerTransaction != null) return mServerTransaction;
            return null;
        }

        public String getState() {
            return mState.toString();
        }

        public void setListener(ISipSessionListener listener) {
            mProxy.setListener((listener instanceof SipSessionListenerProxy)
                    ? ((SipSessionListenerProxy) listener).getListener()
                    : listener);
        }

        public void makeCall(SipProfile peerProfile,
                SessionDescription sessionDescription) {
            try {
                processCommand(
                        new MakeCallCommand(peerProfile, sessionDescription));
            } catch (SipException e) {
                onError(e);
            }
        }

        public void answerCall(SessionDescription sessionDescription) {
            try {
                processCommand(
                        new MakeCallCommand(mPeerProfile, sessionDescription));
            } catch (SipException e) {
                onError(e);
            }
        }

        public void endCall() {
            try {
                processCommand(END_CALL);
            } catch (SipException e) {
                onError(e);
            }
        }

        public void changeCall(SessionDescription sessionDescription) {
            try {
                processCommand(
                        new MakeCallCommand(mPeerProfile, sessionDescription));
            } catch (SipException e) {
                onError(e);
            }
        }

        public void register(int duration) {
            try {
                processCommand(new RegisterCommand(duration));
            } catch (SipException e) {
                onRegistrationFailed(e);
            }
        }

        public void unregister() {
            try {
                processCommand(DEREGISTER);
            } catch (SipException e) {
                onRegistrationFailed(e);
            }
        }

        public boolean isReRegisterRequired() {
            return mReRegisterFlag;
        }

        public void clearReRegisterRequired() {
            mReRegisterFlag = false;
        }

        public void sendKeepAlive() {
            mState = SipSessionState.PINGING;
            try {
                processCommand(new OptionsCommand());
                while (SipSessionState.PINGING.equals(mState)) {
                    Thread.sleep(1000);
                }
            } catch (SipException e) {
                Log.e(TAG, "sendKeepAlive failed", e);
            } catch (InterruptedException e) {
                Log.e(TAG, "sendKeepAlive interrupted", e);
            }
        }

        private void processCommand(EventObject command) throws SipException {
            if (!process(command)) {
                throw new SipException("wrong state to execute: " + command);
            }
        }

        protected String generateTag() {
            // 32-bit randomness
            return String.valueOf((long) (Math.random() * 0x100000000L));
        }

        public String toString() {
            try {
                String s = super.toString();
                return s.substring(s.indexOf("@")) + ":" + mState;
            } catch (Throwable e) {
                return super.toString();
            }
        }

        public boolean process(EventObject evt) throws SipException {
            Log.d(TAG, " ~~~~~   " + this + ": " + mState + ": processing "
                    + log(evt));
            synchronized (SipSessionGroup.this) {
                if (isClosed()) return false;

                Dialog dialog = null;
                if (evt instanceof RequestEvent) {
                    dialog = ((RequestEvent) evt).getDialog();
                } else if (evt instanceof ResponseEvent) {
                    dialog = ((ResponseEvent) evt).getDialog();
                }
                if (dialog != null) mDialog = dialog;

                boolean processed;

                switch (mState) {
                case REGISTERING:
                case DEREGISTERING:
                    processed = registeringToReady(evt);
                    break;
                case PINGING:
                    processed = keepAliveProcess(evt);
                    break;
                case READY_TO_CALL:
                    processed = readyForCall(evt);
                    break;
                case INCOMING_CALL:
                    processed = incomingCall(evt);
                    break;
                case INCOMING_CALL_ANSWERING:
                    processed = incomingCallToInCall(evt);
                    break;
                case OUTGOING_CALL:
                case OUTGOING_CALL_RING_BACK:
                    processed = outgoingCall(evt);
                    break;
                case OUTGOING_CALL_CANCELING:
                    processed = outgoingCallToReady(evt);
                    break;
                case IN_CALL:
                    processed = inCall(evt);
                    break;
                default:
                    processed = false;
                }
                return (processed || processExceptions(evt));
            }
        }

        private boolean processExceptions(EventObject evt) throws SipException {
            if (isRequestEvent(Request.BYE, evt)) {
                // terminate the call whenever a BYE is received
                mSipHelper.sendResponse((RequestEvent) evt, Response.OK);
                endCallNormally();
                return true;
            } else if (isRequestEvent(Request.CANCEL, evt)) {
                mSipHelper.sendResponse((RequestEvent) evt,
                        Response.CALL_OR_TRANSACTION_DOES_NOT_EXIST);
                return true;
            } else if (evt instanceof TransactionTerminatedEvent) {
                if (evt instanceof TimeoutEvent) {
                    processTimeout((TimeoutEvent) evt);
                } else {
                    Log.d(TAG, "Transaction terminated:" + this);
                    if (!SipSessionState.IN_CALL.equals(mState)) {
                        removeSipSession(this);
                    }
                    return true;
                }
                return true;
            } else if (evt instanceof DialogTerminatedEvent) {
                processDialogTerminated((DialogTerminatedEvent) evt);
                return true;
            }
            return false;
        }

        private void processDialogTerminated(DialogTerminatedEvent event) {
            if (mDialog == event.getDialog()) {
                onError(new SipException("dialog terminated"));
            } else {
                Log.d(TAG, "not the current dialog; current=" + mDialog
                        + ", terminated=" + event.getDialog());
            }
        }

        private void processTimeout(TimeoutEvent event) {
            Log.d(TAG, "processing Timeout..." + event);
            Transaction current = event.isServerTransaction()
                    ? mServerTransaction
                    : mClientTransaction;
            Transaction target = event.isServerTransaction()
                    ? event.getServerTransaction()
                    : event.getClientTransaction();

            if ((current != target) && (mState != SipSessionState.PINGING)) {
                Log.d(TAG, "not the current transaction; current=" + current
                        + ", timed out=" + target);
                return;
            }
            switch (mState) {
            case REGISTERING:
            case DEREGISTERING:
                reset();
                mProxy.onRegistrationTimeout(this);
                break;
            case INCOMING_CALL:
            case INCOMING_CALL_ANSWERING:
            case OUTGOING_CALL_CANCELING:
                endCallOnError(new SipException("timed out"));
                break;
            case PINGING:
                reset();
                mReRegisterFlag = true;
                mState = SipSessionState.READY_TO_CALL;
                break;

            default:
                // do nothing
                break;
            }
        }

        private int getExpiryTime(Response response) {
            int expires = EXPIRY_TIME;
            ExpiresHeader expiresHeader = (ExpiresHeader)
                    response.getHeader(ExpiresHeader.NAME);
            if (expiresHeader != null) expires = expiresHeader.getExpires();
            expiresHeader = (ExpiresHeader)
                    response.getHeader(MinExpiresHeader.NAME);
            if (expiresHeader != null) {
                expires = Math.max(expires, expiresHeader.getExpires());
            }
            return expires;
        }

        private boolean keepAliveProcess(EventObject evt) throws SipException {
            if (evt instanceof OptionsCommand) {
                mClientTransaction = mSipHelper.sendKeepAlive(mLocalProfile,
                        generateTag());
                mDialog = mClientTransaction.getDialog();
                addSipSession(this);
                return true;
            } else if (evt instanceof ResponseEvent) {
                return parseOptionsResult(evt);
            }
            return false;
        }

        private boolean parseOptionsResult(EventObject evt) {
            if (expectResponse(Request.OPTIONS, evt)) {
                ResponseEvent event = (ResponseEvent) evt;
                int rPort = getRPortFromResponse(event.getResponse());
                if (rPort != -1) {
                    if (mRPort == 0) mRPort = rPort;
                    if (mRPort != rPort) {
                        mReRegisterFlag = true;
                        Log.w(TAG, String.format("rport is changed: %d <> %d",
                                mRPort, rPort));
                        mRPort = rPort;
                    } else {
                        Log.w(TAG, "rport is the same: " + rPort);
                    }
                } else {
                    Log.w(TAG, "peer did not respect our rport request");
                }
                mState = SipSessionState.READY_TO_CALL;
                return true;
            }
            return false;
        }

        private int getRPortFromResponse(Response response) {
            ViaHeader viaHeader = (ViaHeader)(response.getHeader(
                    SIPHeaderNames.VIA));
            return (viaHeader == null) ? -1 : viaHeader.getRPort();
        }

        private boolean registeringToReady(EventObject evt)
                throws SipException {
            if (expectResponse(Request.REGISTER, evt)) {
                ResponseEvent event = (ResponseEvent) evt;
                Response response = event.getResponse();

                int statusCode = response.getStatusCode();
                switch (statusCode) {
                case Response.OK:
                    SipSessionState state = mState;
                    reset();
                    onRegistrationDone((state == SipSessionState.REGISTERING)
                            ? getExpiryTime(((ResponseEvent) evt).getResponse())
                            : -1);
                    mLastNonce = null;
                    mRPort = 0;
                    return true;
                case Response.UNAUTHORIZED:
                case Response.PROXY_AUTHENTICATION_REQUIRED:
                    String nonce = getNonceFromResponse(response);
                    if (((nonce != null) && nonce.equals(mLastNonce)) ||
                            (nonce == mLastNonce)) {
                        Log.v(TAG, "Incorrect username/password");
                        reset();
                        onRegistrationFailed(createCallbackException(response));
                    } else {
                        mSipHelper.handleChallenge(event, getAccountManager());
                        mLastNonce = nonce;
                    }
                    return true;
                default:
                    if (statusCode >= 500) {
                        reset();
                        onRegistrationFailed(createCallbackException(response));
                        return true;
                    }
                }
            }
            return false;
        }

        private AccountManager getAccountManager() {
            return new AccountManager() {
                public UserCredentials getCredentials(ClientTransaction
                        challengedTransaction, String realm) {
                    return new UserCredentials() {
                        public String getUserName() {
                            return mLocalProfile.getUserName();
                        }

                        public String getPassword() {
                            return mPassword;
                        }

                        public String getSipDomain() {
                            return mLocalProfile.getSipDomain();
                        }
                    };
                }
            };
        }

        private String getNonceFromResponse(Response response) {
            WWWAuthenticate authHeader = (WWWAuthenticate)(response.getHeader(
                    SIPHeaderNames.WWW_AUTHENTICATE));
            return (authHeader == null) ? null : authHeader.getNonce();
        }

        private boolean readyForCall(EventObject evt) throws SipException {
            // expect MakeCallCommand, RegisterCommand, DEREGISTER
            if (evt instanceof MakeCallCommand) {
                MakeCallCommand cmd = (MakeCallCommand) evt;
                mPeerProfile = cmd.getPeerProfile();
                SessionDescription sessionDescription =
                        cmd.getSessionDescription();
                mClientTransaction = mSipHelper.sendInvite(mLocalProfile,
                        mPeerProfile, sessionDescription, generateTag());
                mDialog = mClientTransaction.getDialog();
                addSipSession(this);
                mState = SipSessionState.OUTGOING_CALL;
                mProxy.onCalling(this);
                return true;
            } else if (evt instanceof RegisterCommand) {
                int duration = ((RegisterCommand) evt).getDuration();
                mClientTransaction = mSipHelper.sendRegister(mLocalProfile,
                        generateTag(), duration);
                mDialog = mClientTransaction.getDialog();
                addSipSession(this);
                mState = SipSessionState.REGISTERING;
                mProxy.onRegistering(this);
                return true;
            } else if (DEREGISTER == evt) {
                mClientTransaction = mSipHelper.sendRegister(mLocalProfile,
                        generateTag(), 0);
                mDialog = mClientTransaction.getDialog();
                addSipSession(this);
                mState = SipSessionState.DEREGISTERING;
                mProxy.onRegistering(this);
                return true;
            }
            return false;
        }

        private boolean incomingCall(EventObject evt) throws SipException {
            // expect MakeCallCommand(answering) , END_CALL cmd , Cancel
            if (evt instanceof MakeCallCommand) {
                // answer call
                mServerTransaction = mSipHelper.sendInviteOk(mInviteReceived,
                        mLocalProfile,
                        ((MakeCallCommand) evt).getSessionDescription(),
                        mServerTransaction);
                mState = SipSessionState.INCOMING_CALL_ANSWERING;
                return true;
            } else if (END_CALL == evt) {
                mSipHelper.sendInviteBusyHere(mInviteReceived,
                        mServerTransaction);
                endCallNormally();
                return true;
            } else if (isRequestEvent(Request.CANCEL, evt)) {
                RequestEvent event = (RequestEvent) evt;
                mSipHelper.sendResponse(event, Response.OK);
                mSipHelper.sendInviteRequestTerminated(
                        mInviteReceived.getRequest(), mServerTransaction);
                endCallNormally();
                return true;
            }
            return false;
        }

        private boolean incomingCallToInCall(EventObject evt)
                throws SipException {
            // expect ACK, CANCEL request
            if (isRequestEvent(Request.ACK, evt)) {
                establishCall();
                return true;
            } else if (isRequestEvent(Request.CANCEL, evt)) {
                // http://tools.ietf.org/html/rfc3261#section-9.2
                // Final response has been sent; do nothing here.
                return true;
            }
            return false;
        }

        private boolean outgoingCall(EventObject evt) throws SipException {
            if (expectResponse(Request.INVITE, evt)) {
                ResponseEvent event = (ResponseEvent) evt;
                Response response = event.getResponse();

                int statusCode = response.getStatusCode();
                switch (statusCode) {
                case Response.RINGING:
                    if (mState == SipSessionState.OUTGOING_CALL) {
                        mState = SipSessionState.OUTGOING_CALL_RING_BACK;
                        mProxy.onRingingBack(this);
                    }
                    return true;
                case Response.OK:
                    mSipHelper.sendInviteAck(event, mDialog);
                    mPeerSessionDescription = response.getRawContent();
                    establishCall();
                    return true;
                case Response.PROXY_AUTHENTICATION_REQUIRED:
                    mClientTransaction = mSipHelper.handleChallenge(
                            (ResponseEvent) evt, getAccountManager());
                    mDialog = mClientTransaction.getDialog();
                    addSipSession(this);
                    return true;
                case Response.BUSY_HERE:
                    reset();
                    mProxy.onCallBusy(this);
                    return true;
                case Response.REQUEST_PENDING:
                    // TODO:
                    // rfc3261#section-14.1; re-schedule invite
                    return true;
                default:
                    if (statusCode >= 400) {
                        // error: an ack is sent automatically by the stack
                        onError(createCallbackException(response));
                        return true;
                    } else if (statusCode >= 300) {
                        // TODO: handle 3xx (redirect)
                    } else {
                        return true;
                    }
                }
                return false;
            } else if (END_CALL == evt) {
                // RFC says that UA should not send out cancel when no
                // response comes back yet. We are cheating for not checking
                // response.
                mSipHelper.sendCancel(mClientTransaction);
                mState = SipSessionState.OUTGOING_CALL_CANCELING;
                return true;
            }
            return false;
        }

        private boolean outgoingCallToReady(EventObject evt)
                throws SipException {
            if (evt instanceof ResponseEvent) {
                ResponseEvent event = (ResponseEvent) evt;
                Response response = event.getResponse();
                int statusCode = response.getStatusCode();
                if (expectResponse(Request.CANCEL, evt)) {
                    if (statusCode == Response.OK) {
                        // do nothing; wait for REQUEST_TERMINATED
                        return true;
                    }
                } else if (expectResponse(Request.INVITE, evt)) {
                    if (statusCode == Response.OK) {
                        outgoingCall(evt); // abort Cancel
                        return true;
                    }
                } else {
                    return false;
                }

                if (statusCode >= 400) {
                    onError(createCallbackException(response));
                    return true;
                }
            } else if (evt instanceof TransactionTerminatedEvent) {
                // rfc3261#section-14.1:
                // if re-invite gets timed out, terminate the dialog; but
                // re-invite is not reliable, just let it go and pretend
                // nothing happened.
                onError(new SipException("timed out"));
            }
            return false;
        }

        private boolean inCall(EventObject evt) throws SipException {
            // expect END_CALL cmd, BYE request, hold call (MakeCallCommand)
            // OK retransmission is handled in SipStack
            if (END_CALL == evt) {
                // rfc3261#section-15.1.1
                mSipHelper.sendBye(mDialog);
                endCallNormally();
                return true;
            } else if (isRequestEvent(Request.INVITE, evt)) {
                // got Re-INVITE
                RequestEvent event = mInviteReceived = (RequestEvent) evt;
                mState = SipSessionState.INCOMING_CALL;
                mPeerSessionDescription = event.getRequest().getRawContent();
                mServerTransaction = null;
                mProxy.onRinging(this, mPeerProfile, mPeerSessionDescription);
                return true;
            } else if (isRequestEvent(Request.BYE, evt)) {
                mSipHelper.sendResponse((RequestEvent) evt, Response.OK);
                endCallNormally();
                return true;
            } else if (evt instanceof MakeCallCommand) {
                // to change call
                mClientTransaction = mSipHelper.sendReinvite(mDialog,
                        ((MakeCallCommand) evt).getSessionDescription());
                mState = SipSessionState.OUTGOING_CALL;
                return true;
            }
            return false;
        }

        private Exception createCallbackException(Response response) {
            return new SipException(String.format("Response: %s (%d)",
                    response.getReasonPhrase(), response.getStatusCode()));
        }

        private void establishCall() {
            mState = SipSessionState.IN_CALL;
            mInCall = true;
            mProxy.onCallEstablished(this, mPeerSessionDescription);
        }

        private void fallbackToPreviousInCall(Throwable exception) {
            mState = SipSessionState.IN_CALL;
            mProxy.onCallChangeFailed(this, exception.getClass().getName(),
                    exception.getMessage());
        }

        private void endCallNormally() {
            reset();
            mProxy.onCallEnded(this);
        }

        private void endCallOnError(Throwable exception) {
            reset();
            mProxy.onError(this, exception.getClass().getName(),
                    exception.getMessage());
        }

        private void onError(Throwable exception) {
            if (mInCall) {
                fallbackToPreviousInCall(exception);
            } else {
                endCallOnError(exception);
            }
        }

        private void onRegistrationDone(int duration) {
            mProxy.onRegistrationDone(this, duration);
        }

        private void onRegistrationFailed(Throwable exception) {
            mProxy.onRegistrationFailed(this, exception.getClass().getName(),
                    exception.getMessage());
        }
    }

    /**
     * @return true if the event is a request event matching the specified
     *      method; false otherwise
     */
    private static boolean isRequestEvent(String method, EventObject event) {
        try {
            if (event instanceof RequestEvent) {
                RequestEvent requestEvent = (RequestEvent) event;
                return method.equals(requestEvent.getRequest().getMethod());
            }
        } catch (Throwable e) {
        }
        return false;
    }

    private static String getCseqMethod(Message message) {
        return ((CSeqHeader) message.getHeader(CSeqHeader.NAME)).getMethod();
    }

    /**
     * @return true if the event is a response event and the CSeqHeader method
     * match the given arguments; false otherwise
     */
    private static boolean expectResponse(
            String expectedMethod, EventObject evt) {
        if (evt instanceof ResponseEvent) {
            ResponseEvent event = (ResponseEvent) evt;
            Response response = event.getResponse();
            return expectedMethod.equalsIgnoreCase(getCseqMethod(response));
        }
        return false;
    }

    /**
     * @return true if the event is a response event and the response code and
     *      CSeqHeader method match the given arguments; false otherwise
     */
    private static boolean expectResponse(
            int responseCode, String expectedMethod, EventObject evt) {
        if (evt instanceof ResponseEvent) {
            ResponseEvent event = (ResponseEvent) evt;
            Response response = event.getResponse();
            if (response.getStatusCode() == responseCode) {
                return expectedMethod.equalsIgnoreCase(getCseqMethod(response));
            }
        }
        return false;
    }

    private static SipProfile createPeerProfile(Request request)
            throws SipException {
        try {
            FromHeader fromHeader =
                    (FromHeader) request.getHeader(FromHeader.NAME);
            Address address = fromHeader.getAddress();
            SipURI uri = (SipURI) address.getURI();
            String username = uri.getUser();
            if (username == null) username = ANONYMOUS;
            return new SipProfile.Builder(username, uri.getHost())
                    .setPort(uri.getPort())
                    .setDisplayName(address.getDisplayName())
                    .build();
        } catch (InvalidArgumentException e) {
            throw new SipException("createPeerProfile()", e);
        } catch (ParseException e) {
            throw new SipException("createPeerProfile()", e);
        }
    }

    private static String log(EventObject evt) {
        if (evt instanceof RequestEvent) {
            return ((RequestEvent) evt).getRequest().toString();
        } else if (evt instanceof ResponseEvent) {
            return ((ResponseEvent) evt).getResponse().toString();
        } else {
            return evt.toString();
        }
    }

    private class OptionsCommand extends EventObject {
        public OptionsCommand() {
            super(SipSessionGroup.this);
        }
    }

    private class RegisterCommand extends EventObject {
        private int mDuration;

        public RegisterCommand(int duration) {
            super(SipSessionGroup.this);
            mDuration = duration;
        }

        public int getDuration() {
            return mDuration;
        }
    }

    private class MakeCallCommand extends EventObject {
        private SessionDescription mSessionDescription;

        public MakeCallCommand(SipProfile peerProfile,
                SessionDescription sessionDescription) {
            super(peerProfile);
            mSessionDescription = sessionDescription;
        }

        public SipProfile getPeerProfile() {
            return (SipProfile) getSource();
        }

        public SessionDescription getSessionDescription() {
            return mSessionDescription;
        }
    }

}
