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
import gov.nist.javax.sip.header.ProxyAuthenticate;
import gov.nist.javax.sip.header.ReferTo;
import gov.nist.javax.sip.header.SIPHeaderNames;
import gov.nist.javax.sip.header.StatusLine;
import gov.nist.javax.sip.header.WWWAuthenticate;
import gov.nist.javax.sip.header.extensions.ReferredByHeader;
import gov.nist.javax.sip.header.extensions.ReplacesHeader;
import gov.nist.javax.sip.message.SIPMessage;
import gov.nist.javax.sip.message.SIPResponse;

import android.net.sip.ISipSession;
import android.net.sip.ISipSessionListener;
import android.net.sip.SipErrorCode;
import android.net.sip.SipProfile;
import android.net.sip.SipSession;
import android.net.sip.SipSessionAdapter;
import android.text.TextUtils;
import android.util.Log;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.DatagramSocket;
import java.net.UnknownHostException;
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
import javax.sip.ObjectInUseException;
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
import javax.sip.TransactionUnavailableException;
import javax.sip.address.Address;
import javax.sip.address.SipURI;
import javax.sip.header.CSeqHeader;
import javax.sip.header.ExpiresHeader;
import javax.sip.header.FromHeader;
import javax.sip.header.HeaderAddress;
import javax.sip.header.MinExpiresHeader;
import javax.sip.header.ReferToHeader;
import javax.sip.header.ViaHeader;
import javax.sip.message.Message;
import javax.sip.message.Request;
import javax.sip.message.Response;


/**
 * Manages {@link ISipSession}'s for a SIP account.
 */
class SipSessionGroup implements SipListener {
    private static final String TAG = "SipSession";
    private static final boolean DEBUG = true;
    private static final boolean DEBUG_PING = DEBUG && false;
    private static final String ANONYMOUS = "anonymous";
    // Limit the size of thread pool to 1 for the order issue when the phone is
    // waken up from sleep and there are many packets to be processed in the SIP
    // stack. Note: The default thread pool size in NIST SIP stack is -1 which is
    // unlimited.
    private static final String THREAD_POOL_SIZE = "1";
    private static final int EXPIRY_TIME = 3600; // in seconds
    private static final int CANCEL_CALL_TIMER = 3; // in seconds
    private static final int END_CALL_TIMER = 3; // in seconds
    private static final int KEEPALIVE_TIMEOUT = 3; // in seconds
    private static final int INCALL_KEEPALIVE_INTERVAL = 10; // in seconds
    private static final long WAKE_LOCK_HOLDING_TIME = 500; // in milliseconds

    private static final EventObject DEREGISTER = new EventObject("Deregister");
    private static final EventObject END_CALL = new EventObject("End call");
    private static final EventObject HOLD_CALL = new EventObject("Hold call");
    private static final EventObject CONTINUE_CALL
            = new EventObject("Continue call");

    private final SipProfile mLocalProfile;
    private final String mPassword;

    private SipStack mSipStack;
    private SipHelper mSipHelper;

    // session that processes INVITE requests
    private SipSessionImpl mCallReceiverSession;
    private String mLocalIp;

    private SipWakeupTimer mWakeupTimer;
    private SipWakeLock mWakeLock;

    // call-id-to-SipSession map
    private Map<String, SipSessionImpl> mSessionMap =
            new HashMap<String, SipSessionImpl>();

    // external address observed from any response
    private String mExternalIp;
    private int mExternalPort;

    /**
     * @param myself the local profile with password crossed out
     * @param password the password of the profile
     * @throws IOException if cannot assign requested address
     */
    public SipSessionGroup(String localIp, SipProfile myself, String password,
            SipWakeupTimer timer, SipWakeLock wakeLock) throws SipException,
            IOException {
        mLocalProfile = myself;
        mPassword = password;
        mWakeupTimer = timer;
        mWakeLock = wakeLock;
        reset(localIp);
    }

    // TODO: remove this method once SipWakeupTimer can better handle variety
    // of timeout values
    void setWakeupTimer(SipWakeupTimer timer) {
        mWakeupTimer = timer;
    }

    synchronized void reset(String localIp) throws SipException, IOException {
        mLocalIp = localIp;
        if (localIp == null) return;

        SipProfile myself = mLocalProfile;
        SipFactory sipFactory = SipFactory.getInstance();
        Properties properties = new Properties();
        properties.setProperty("javax.sip.STACK_NAME", getStackName());
        properties.setProperty(
                "gov.nist.javax.sip.THREAD_POOL_SIZE", THREAD_POOL_SIZE);
        String outboundProxy = myself.getProxyAddress();
        if (!TextUtils.isEmpty(outboundProxy)) {
            Log.v(TAG, "outboundProxy is " + outboundProxy);
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

        mCallReceiverSession = null;
        mSessionMap.clear();

        resetExternalAddress();
    }

    synchronized void onConnectivityChanged() {
        SipSessionImpl[] ss = mSessionMap.values().toArray(
                    new SipSessionImpl[mSessionMap.size()]);
        // Iterate on the copied array instead of directly on mSessionMap to
        // avoid ConcurrentModificationException being thrown when
        // SipSessionImpl removes itself from mSessionMap in onError() in the
        // following loop.
        for (SipSessionImpl s : ss) {
            s.onError(SipErrorCode.DATA_CONNECTION_LOST,
                    "data connection lost");
        }
    }

    synchronized void resetExternalAddress() {
        Log.d(TAG, " reset external addr on " + mSipStack);
        mExternalIp = null;
        mExternalPort = 0;
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
        onConnectivityChanged();
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

    synchronized boolean containsSession(String callId) {
        return mSessionMap.containsKey(callId);
    }

    private synchronized SipSessionImpl getSipSession(EventObject event) {
        String key = SipHelper.getCallId(event);
        SipSessionImpl session = mSessionMap.get(key);
        if ((session != null) && isLoggable(session)) {
            Log.d(TAG, "session key from event: " + key);
            Log.d(TAG, "active sessions:");
            for (String k : mSessionMap.keySet()) {
                Log.d(TAG, " ..." + k + ": " + mSessionMap.get(k));
            }
        }
        return ((session != null) ? session : mCallReceiverSession);
    }

    private synchronized void addSipSession(SipSessionImpl newSession) {
        removeSipSession(newSession);
        String key = newSession.getCallId();
        mSessionMap.put(key, newSession);
        if (isLoggable(newSession)) {
            Log.d(TAG, "+++  add a session with key:  '" + key + "'");
            for (String k : mSessionMap.keySet()) {
                Log.d(TAG, "  " + k + ": " + mSessionMap.get(k));
            }
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

        if ((s != null) && isLoggable(s)) {
            Log.d(TAG, "remove session " + session + " @key '" + key + "'");
            for (String k : mSessionMap.keySet()) {
                Log.d(TAG, "  " + k + ": " + mSessionMap.get(k));
            }
        }
    }

    public void processRequest(final RequestEvent event) {
        if (isRequestEvent(Request.INVITE, event)) {
            if (DEBUG) Log.d(TAG, "<<<<< got INVITE, thread:"
                    + Thread.currentThread());
            // Acquire a wake lock and keep it for WAKE_LOCK_HOLDING_TIME;
            // should be large enough to bring up the app.
            mWakeLock.acquire(WAKE_LOCK_HOLDING_TIME);
        }
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
            boolean isLoggable = isLoggable(session, event);
            boolean processed = (session != null) && session.process(event);
            if (isLoggable && processed) {
                Log.d(TAG, "new state after: "
                        + SipSession.State.toString(session.mState));
            }
        } catch (Throwable e) {
            Log.w(TAG, "event process error: " + event, e);
            session.onError(e);
        }
    }

    private String extractContent(Message message) {
        // Currently we do not support secure MIME bodies.
        byte[] bytes = message.getRawContent();
        if (bytes != null) {
            try {
                if (message instanceof SIPMessage) {
                    return ((SIPMessage) message).getMessageContent();
                } else {
                    return new String(bytes, "UTF-8");
                }
            } catch (UnsupportedEncodingException e) {
            }
        }
        return null;
    }

    private void extractExternalAddress(ResponseEvent evt) {
        Response response = evt.getResponse();
        ViaHeader viaHeader = (ViaHeader)(response.getHeader(
                SIPHeaderNames.VIA));
        if (viaHeader == null) return;
        int rport = viaHeader.getRPort();
        String externalIp = viaHeader.getReceived();
        if ((rport > 0) && (externalIp != null)) {
            mExternalIp = externalIp;
            mExternalPort = rport;
            Log.d(TAG, " got external addr " + externalIp + ":" + rport
                    + " on " + mSipStack);
        }
    }

    private SipSessionImpl createNewSession(RequestEvent event,
            ISipSessionListener listener, ServerTransaction transaction,
            int newState) throws SipException {
        SipSessionImpl newSession = new SipSessionImpl(listener);
        newSession.mServerTransaction = transaction;
        newSession.mState = newState;
        newSession.mDialog = newSession.mServerTransaction.getDialog();
        newSession.mInviteReceived = event;
        newSession.mPeerProfile = createPeerProfile((HeaderAddress)
                event.getRequest().getHeader(FromHeader.NAME));
        newSession.mPeerSessionDescription =
                extractContent(event.getRequest());
        return newSession;
    }

    private class SipSessionCallReceiverImpl extends SipSessionImpl {
        public SipSessionCallReceiverImpl(ISipSessionListener listener) {
            super(listener);
        }

        private int processInviteWithReplaces(RequestEvent event,
                ReplacesHeader replaces) {
            String callId = replaces.getCallId();
            SipSessionImpl session = mSessionMap.get(callId);
            if (session == null) {
                return Response.CALL_OR_TRANSACTION_DOES_NOT_EXIST;
            }

            Dialog dialog = session.mDialog;
            if (dialog == null) return Response.DECLINE;

            if (!dialog.getLocalTag().equals(replaces.getToTag()) ||
                    !dialog.getRemoteTag().equals(replaces.getFromTag())) {
                // No match is found, returns 481.
                return Response.CALL_OR_TRANSACTION_DOES_NOT_EXIST;
            }

            ReferredByHeader referredBy = (ReferredByHeader) event.getRequest()
                    .getHeader(ReferredByHeader.NAME);
            if ((referredBy == null) ||
                    !dialog.getRemoteParty().equals(referredBy.getAddress())) {
                return Response.CALL_OR_TRANSACTION_DOES_NOT_EXIST;
            }
            return Response.OK;
        }

        private void processNewInviteRequest(RequestEvent event)
                throws SipException {
            ReplacesHeader replaces = (ReplacesHeader) event.getRequest()
                    .getHeader(ReplacesHeader.NAME);
            SipSessionImpl newSession = null;
            if (replaces != null) {
                int response = processInviteWithReplaces(event, replaces);
                if (DEBUG) {
                    Log.v(TAG, "ReplacesHeader: " + replaces
                            + " response=" + response);
                }
                if (response == Response.OK) {
                    SipSessionImpl replacedSession =
                            mSessionMap.get(replaces.getCallId());
                    // got INVITE w/ replaces request.
                    newSession = createNewSession(event,
                            replacedSession.mProxy.getListener(),
                            mSipHelper.getServerTransaction(event),
                            SipSession.State.INCOMING_CALL);
                    newSession.mProxy.onCallTransferring(newSession,
                            newSession.mPeerSessionDescription);
                } else {
                    mSipHelper.sendResponse(event, response);
                }
            } else {
                // New Incoming call.
                newSession = createNewSession(event, mProxy,
                        mSipHelper.sendRinging(event, generateTag()),
                        SipSession.State.INCOMING_CALL);
                mProxy.onRinging(newSession, newSession.mPeerProfile,
                        newSession.mPeerSessionDescription);
            }
            if (newSession != null) addSipSession(newSession);
        }

        public boolean process(EventObject evt) throws SipException {
            if (isLoggable(this, evt)) Log.d(TAG, " ~~~~~   " + this + ": "
                    + SipSession.State.toString(mState) + ": processing "
                    + log(evt));
            if (isRequestEvent(Request.INVITE, evt)) {
                processNewInviteRequest((RequestEvent) evt);
                return true;
            } else if (isRequestEvent(Request.OPTIONS, evt)) {
                mSipHelper.sendResponse((RequestEvent) evt, Response.OK);
                return true;
            } else {
                return false;
            }
        }
    }

    static interface KeepAliveProcessCallback {
        /** Invoked when the response of keeping alive comes back. */
        void onResponse(boolean portChanged);
        void onError(int errorCode, String description);
    }

    class SipSessionImpl extends ISipSession.Stub {
        SipProfile mPeerProfile;
        SipSessionListenerProxy mProxy = new SipSessionListenerProxy();
        int mState = SipSession.State.READY_TO_CALL;
        RequestEvent mInviteReceived;
        Dialog mDialog;
        ServerTransaction mServerTransaction;
        ClientTransaction mClientTransaction;
        String mPeerSessionDescription;
        boolean mInCall;
        SessionTimer mSessionTimer;
        int mAuthenticationRetryCount;

        private KeepAliveProcess mKeepAliveProcess;

        private SipSessionImpl mKeepAliveSession;

        // the following three members are used for handling refer request.
        SipSessionImpl mReferSession;
        ReferredByHeader mReferredBy;
        String mReplaces;

        // lightweight timer
        class SessionTimer {
            private boolean mRunning = true;

            void start(final int timeout) {
                new Thread(new Runnable() {
                    public void run() {
                        sleep(timeout);
                        if (mRunning) timeout();
                    }
                }, "SipSessionTimerThread").start();
            }

            synchronized void cancel() {
                mRunning = false;
                this.notify();
            }

            private void timeout() {
                synchronized (SipSessionGroup.this) {
                    onError(SipErrorCode.TIME_OUT, "Session timed out!");
                }
            }

            private synchronized void sleep(int timeout) {
                try {
                    this.wait(timeout * 1000);
                } catch (InterruptedException e) {
                    Log.e(TAG, "session timer interrupted!");
                }
            }
        }

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
            mState = SipSession.State.READY_TO_CALL;
            mInviteReceived = null;
            mPeerSessionDescription = null;
            mAuthenticationRetryCount = 0;
            mReferSession = null;
            mReferredBy = null;
            mReplaces = null;

            if (mDialog != null) mDialog.delete();
            mDialog = null;

            try {
                if (mServerTransaction != null) mServerTransaction.terminate();
            } catch (ObjectInUseException e) {
                // ignored
            }
            mServerTransaction = null;

            try {
                if (mClientTransaction != null) mClientTransaction.terminate();
            } catch (ObjectInUseException e) {
                // ignored
            }
            mClientTransaction = null;

            cancelSessionTimer();

            if (mKeepAliveSession != null) {
                mKeepAliveSession.stopKeepAliveProcess();
                mKeepAliveSession = null;
            }
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

        public int getState() {
            return mState;
        }

        public void setListener(ISipSessionListener listener) {
            mProxy.setListener((listener instanceof SipSessionListenerProxy)
                    ? ((SipSessionListenerProxy) listener).getListener()
                    : listener);
        }

        // process the command in a new thread
        private void doCommandAsync(final EventObject command) {
            new Thread(new Runnable() {
                    public void run() {
                        try {
                            processCommand(command);
                        } catch (Throwable e) {
                            Log.w(TAG, "command error: " + command + ": "
                                    + mLocalProfile.getUriString(),
                                    getRootCause(e));
                            onError(e);
                        }
                    }
            }, "SipSessionAsyncCmdThread").start();
        }

        public void makeCall(SipProfile peerProfile, String sessionDescription,
                int timeout) {
            doCommandAsync(new MakeCallCommand(peerProfile, sessionDescription,
                    timeout));
        }

        public void answerCall(String sessionDescription, int timeout) {
            synchronized (SipSessionGroup.this) {
                if (mPeerProfile == null) return;
                doCommandAsync(new MakeCallCommand(mPeerProfile,
                        sessionDescription, timeout));
            }
        }

        public void endCall() {
            doCommandAsync(END_CALL);
        }

        public void changeCall(String sessionDescription, int timeout) {
            synchronized (SipSessionGroup.this) {
                if (mPeerProfile == null) return;
                doCommandAsync(new MakeCallCommand(mPeerProfile,
                        sessionDescription, timeout));
            }
        }

        public void register(int duration) {
            doCommandAsync(new RegisterCommand(duration));
        }

        public void unregister() {
            doCommandAsync(DEREGISTER);
        }

        private void processCommand(EventObject command) throws SipException {
            if (isLoggable(command)) Log.d(TAG, "process cmd: " + command);
            if (!process(command)) {
                onError(SipErrorCode.IN_PROGRESS,
                        "cannot initiate a new transaction to execute: "
                        + command);
            }
        }

        protected String generateTag() {
            // 32-bit randomness
            return String.valueOf((long) (Math.random() * 0x100000000L));
        }

        public String toString() {
            try {
                String s = super.toString();
                return s.substring(s.indexOf("@")) + ":"
                        + SipSession.State.toString(mState);
            } catch (Throwable e) {
                return super.toString();
            }
        }

        public boolean process(EventObject evt) throws SipException {
            if (isLoggable(this, evt)) Log.d(TAG, " ~~~~~   " + this + ": "
                    + SipSession.State.toString(mState) + ": processing "
                    + log(evt));
            synchronized (SipSessionGroup.this) {
                if (isClosed()) return false;

                if (mKeepAliveProcess != null) {
                    // event consumed by keepalive process
                    if (mKeepAliveProcess.process(evt)) return true;
                }

                Dialog dialog = null;
                if (evt instanceof RequestEvent) {
                    dialog = ((RequestEvent) evt).getDialog();
                } else if (evt instanceof ResponseEvent) {
                    dialog = ((ResponseEvent) evt).getDialog();
                    extractExternalAddress((ResponseEvent) evt);
                }
                if (dialog != null) mDialog = dialog;

                boolean processed;

                switch (mState) {
                case SipSession.State.REGISTERING:
                case SipSession.State.DEREGISTERING:
                    processed = registeringToReady(evt);
                    break;
                case SipSession.State.READY_TO_CALL:
                    processed = readyForCall(evt);
                    break;
                case SipSession.State.INCOMING_CALL:
                    processed = incomingCall(evt);
                    break;
                case SipSession.State.INCOMING_CALL_ANSWERING:
                    processed = incomingCallToInCall(evt);
                    break;
                case SipSession.State.OUTGOING_CALL:
                case SipSession.State.OUTGOING_CALL_RING_BACK:
                    processed = outgoingCall(evt);
                    break;
                case SipSession.State.OUTGOING_CALL_CANCELING:
                    processed = outgoingCallToReady(evt);
                    break;
                case SipSession.State.IN_CALL:
                    processed = inCall(evt);
                    break;
                case SipSession.State.ENDING_CALL:
                    processed = endingCall(evt);
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
                if (isCurrentTransaction((TransactionTerminatedEvent) evt)) {
                    if (evt instanceof TimeoutEvent) {
                        processTimeout((TimeoutEvent) evt);
                    } else {
                        processTransactionTerminated(
                                (TransactionTerminatedEvent) evt);
                    }
                    return true;
                }
            } else if (isRequestEvent(Request.OPTIONS, evt)) {
                mSipHelper.sendResponse((RequestEvent) evt, Response.OK);
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

        private boolean isCurrentTransaction(TransactionTerminatedEvent event) {
            Transaction current = event.isServerTransaction()
                    ? mServerTransaction
                    : mClientTransaction;
            Transaction target = event.isServerTransaction()
                    ? event.getServerTransaction()
                    : event.getClientTransaction();

            if ((current != target) && (mState != SipSession.State.PINGING)) {
                Log.d(TAG, "not the current transaction; current="
                        + toString(current) + ", target=" + toString(target));
                return false;
            } else if (current != null) {
                Log.d(TAG, "transaction terminated: " + toString(current));
                return true;
            } else {
                // no transaction; shouldn't be here; ignored
                return true;
            }
        }

        private String toString(Transaction transaction) {
            if (transaction == null) return "null";
            Request request = transaction.getRequest();
            Dialog dialog = transaction.getDialog();
            CSeqHeader cseq = (CSeqHeader) request.getHeader(CSeqHeader.NAME);
            return String.format("req=%s,%s,s=%s,ds=%s,", request.getMethod(),
                    cseq.getSeqNumber(), transaction.getState(),
                    ((dialog == null) ? "-" : dialog.getState()));
        }

        private void processTransactionTerminated(
                TransactionTerminatedEvent event) {
            switch (mState) {
                case SipSession.State.IN_CALL:
                case SipSession.State.READY_TO_CALL:
                    Log.d(TAG, "Transaction terminated; do nothing");
                    break;
                default:
                    Log.d(TAG, "Transaction terminated early: " + this);
                    onError(SipErrorCode.TRANSACTION_TERMINTED,
                            "transaction terminated");
            }
        }

        private void processTimeout(TimeoutEvent event) {
            Log.d(TAG, "processing Timeout...");
            switch (mState) {
                case SipSession.State.REGISTERING:
                case SipSession.State.DEREGISTERING:
                    reset();
                    mProxy.onRegistrationTimeout(this);
                    break;
                case SipSession.State.INCOMING_CALL:
                case SipSession.State.INCOMING_CALL_ANSWERING:
                case SipSession.State.OUTGOING_CALL:
                case SipSession.State.OUTGOING_CALL_CANCELING:
                    onError(SipErrorCode.TIME_OUT, event.toString());
                    break;

                default:
                    Log.d(TAG, "   do nothing");
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

        private boolean registeringToReady(EventObject evt)
                throws SipException {
            if (expectResponse(Request.REGISTER, evt)) {
                ResponseEvent event = (ResponseEvent) evt;
                Response response = event.getResponse();

                int statusCode = response.getStatusCode();
                switch (statusCode) {
                case Response.OK:
                    int state = mState;
                    onRegistrationDone((state == SipSession.State.REGISTERING)
                            ? getExpiryTime(((ResponseEvent) evt).getResponse())
                            : -1);
                    return true;
                case Response.UNAUTHORIZED:
                case Response.PROXY_AUTHENTICATION_REQUIRED:
                    handleAuthentication(event);
                    return true;
                default:
                    if (statusCode >= 500) {
                        onRegistrationFailed(response);
                        return true;
                    }
                }
            }
            return false;
        }

        private boolean handleAuthentication(ResponseEvent event)
                throws SipException {
            Response response = event.getResponse();
            String nonce = getNonceFromResponse(response);
            if (nonce == null) {
                onError(SipErrorCode.SERVER_ERROR,
                        "server does not provide challenge");
                return false;
            } else if (mAuthenticationRetryCount < 2) {
                mClientTransaction = mSipHelper.handleChallenge(
                        event, getAccountManager());
                mDialog = mClientTransaction.getDialog();
                mAuthenticationRetryCount++;
                if (isLoggable(this, event)) {
                    Log.d(TAG, "   authentication retry count="
                            + mAuthenticationRetryCount);
                }
                return true;
            } else {
                if (crossDomainAuthenticationRequired(response)) {
                    onError(SipErrorCode.CROSS_DOMAIN_AUTHENTICATION,
                            getRealmFromResponse(response));
                } else {
                    onError(SipErrorCode.INVALID_CREDENTIALS,
                            "incorrect username or password");
                }
                return false;
            }
        }

        private boolean crossDomainAuthenticationRequired(Response response) {
            String realm = getRealmFromResponse(response);
            if (realm == null) realm = "";
            return !mLocalProfile.getSipDomain().trim().equals(realm.trim());
        }

        private AccountManager getAccountManager() {
            return new AccountManager() {
                public UserCredentials getCredentials(ClientTransaction
                        challengedTransaction, String realm) {
                    return new UserCredentials() {
                        public String getUserName() {
                            String username = mLocalProfile.getAuthUserName();
                            return (!TextUtils.isEmpty(username) ? username :
                                    mLocalProfile.getUserName());
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

        private String getRealmFromResponse(Response response) {
            WWWAuthenticate wwwAuth = (WWWAuthenticate)response.getHeader(
                    SIPHeaderNames.WWW_AUTHENTICATE);
            if (wwwAuth != null) return wwwAuth.getRealm();
            ProxyAuthenticate proxyAuth = (ProxyAuthenticate)response.getHeader(
                    SIPHeaderNames.PROXY_AUTHENTICATE);
            return (proxyAuth == null) ? null : proxyAuth.getRealm();
        }

        private String getNonceFromResponse(Response response) {
            WWWAuthenticate wwwAuth = (WWWAuthenticate)response.getHeader(
                    SIPHeaderNames.WWW_AUTHENTICATE);
            if (wwwAuth != null) return wwwAuth.getNonce();
            ProxyAuthenticate proxyAuth = (ProxyAuthenticate)response.getHeader(
                    SIPHeaderNames.PROXY_AUTHENTICATE);
            return (proxyAuth == null) ? null : proxyAuth.getNonce();
        }

        private String getResponseString(int statusCode) {
            StatusLine statusLine = new StatusLine();
            statusLine.setStatusCode(statusCode);
            statusLine.setReasonPhrase(SIPResponse.getReasonPhrase(statusCode));
            return statusLine.encode();
        }

        private boolean readyForCall(EventObject evt) throws SipException {
            // expect MakeCallCommand, RegisterCommand, DEREGISTER
            if (evt instanceof MakeCallCommand) {
                mState = SipSession.State.OUTGOING_CALL;
                MakeCallCommand cmd = (MakeCallCommand) evt;
                mPeerProfile = cmd.getPeerProfile();
                if (mReferSession != null) {
                    mSipHelper.sendReferNotify(mReferSession.mDialog,
                            getResponseString(Response.TRYING));
                }
                mClientTransaction = mSipHelper.sendInvite(
                        mLocalProfile, mPeerProfile, cmd.getSessionDescription(),
                        generateTag(), mReferredBy, mReplaces);
                mDialog = mClientTransaction.getDialog();
                addSipSession(this);
                startSessionTimer(cmd.getTimeout());
                mProxy.onCalling(this);
                return true;
            } else if (evt instanceof RegisterCommand) {
                mState = SipSession.State.REGISTERING;
                int duration = ((RegisterCommand) evt).getDuration();
                mClientTransaction = mSipHelper.sendRegister(mLocalProfile,
                        generateTag(), duration);
                mDialog = mClientTransaction.getDialog();
                addSipSession(this);
                mProxy.onRegistering(this);
                return true;
            } else if (DEREGISTER == evt) {
                mState = SipSession.State.DEREGISTERING;
                mClientTransaction = mSipHelper.sendRegister(mLocalProfile,
                        generateTag(), 0);
                mDialog = mClientTransaction.getDialog();
                addSipSession(this);
                mProxy.onRegistering(this);
                return true;
            }
            return false;
        }

        private boolean incomingCall(EventObject evt) throws SipException {
            // expect MakeCallCommand(answering) , END_CALL cmd , Cancel
            if (evt instanceof MakeCallCommand) {
                // answer call
                mState = SipSession.State.INCOMING_CALL_ANSWERING;
                mServerTransaction = mSipHelper.sendInviteOk(mInviteReceived,
                        mLocalProfile,
                        ((MakeCallCommand) evt).getSessionDescription(),
                        mServerTransaction,
                        mExternalIp, mExternalPort);
                startSessionTimer(((MakeCallCommand) evt).getTimeout());
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
                String sdp = extractContent(((RequestEvent) evt).getRequest());
                if (sdp != null) mPeerSessionDescription = sdp;
                if (mPeerSessionDescription == null) {
                    onError(SipErrorCode.CLIENT_ERROR, "peer sdp is empty");
                } else {
                    establishCall(false);
                }
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
                case Response.CALL_IS_BEING_FORWARDED:
                case Response.QUEUED:
                case Response.SESSION_PROGRESS:
                    // feedback any provisional responses (except TRYING) as
                    // ring back for better UX
                    if (mState == SipSession.State.OUTGOING_CALL) {
                        mState = SipSession.State.OUTGOING_CALL_RING_BACK;
                        cancelSessionTimer();
                        mProxy.onRingingBack(this);
                    }
                    return true;
                case Response.OK:
                    if (mReferSession != null) {
                        mSipHelper.sendReferNotify(mReferSession.mDialog,
                                getResponseString(Response.OK));
                        // since we don't need to remember the session anymore.
                        mReferSession = null;
                    }
                    mSipHelper.sendInviteAck(event, mDialog);
                    mPeerSessionDescription = extractContent(response);
                    establishCall(true);
                    return true;
                case Response.UNAUTHORIZED:
                case Response.PROXY_AUTHENTICATION_REQUIRED:
                    if (handleAuthentication(event)) {
                        addSipSession(this);
                    }
                    return true;
                case Response.REQUEST_PENDING:
                    // TODO:
                    // rfc3261#section-14.1; re-schedule invite
                    return true;
                default:
                    if (mReferSession != null) {
                        mSipHelper.sendReferNotify(mReferSession.mDialog,
                                getResponseString(Response.SERVICE_UNAVAILABLE));
                    }
                    if (statusCode >= 400) {
                        // error: an ack is sent automatically by the stack
                        onError(response);
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
                mState = SipSession.State.OUTGOING_CALL_CANCELING;
                mSipHelper.sendCancel(mClientTransaction);
                startSessionTimer(CANCEL_CALL_TIMER);
                return true;
            } else if (isRequestEvent(Request.INVITE, evt)) {
                // Call self? Send BUSY HERE so server may redirect the call to
                // voice mailbox.
                RequestEvent event = (RequestEvent) evt;
                mSipHelper.sendInviteBusyHere(event,
                        event.getServerTransaction());
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
                    switch (statusCode) {
                        case Response.OK:
                            outgoingCall(evt); // abort Cancel
                            return true;
                        case Response.REQUEST_TERMINATED:
                            endCallNormally();
                            return true;
                    }
                } else {
                    return false;
                }

                if (statusCode >= 400) {
                    onError(response);
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

        private boolean processReferRequest(RequestEvent event)
                throws SipException {
            try {
                ReferToHeader referto = (ReferToHeader) event.getRequest()
                        .getHeader(ReferTo.NAME);
                Address address = referto.getAddress();
                SipURI uri = (SipURI) address.getURI();
                String replacesHeader = uri.getHeader(ReplacesHeader.NAME);
                String username = uri.getUser();
                if (username == null) {
                    mSipHelper.sendResponse(event, Response.BAD_REQUEST);
                    return false;
                }
                // send notify accepted
                mSipHelper.sendResponse(event, Response.ACCEPTED);
                SipSessionImpl newSession = createNewSession(event,
                        this.mProxy.getListener(),
                        mSipHelper.getServerTransaction(event),
                        SipSession.State.READY_TO_CALL);
                newSession.mReferSession = this;
                newSession.mReferredBy = (ReferredByHeader) event.getRequest()
                        .getHeader(ReferredByHeader.NAME);
                newSession.mReplaces = replacesHeader;
                newSession.mPeerProfile = createPeerProfile(referto);
                newSession.mProxy.onCallTransferring(newSession,
                        null);
                return true;
            } catch (IllegalArgumentException e) {
                throw new SipException("createPeerProfile()", e);
            }
        }

        private boolean inCall(EventObject evt) throws SipException {
            // expect END_CALL cmd, BYE request, hold call (MakeCallCommand)
            // OK retransmission is handled in SipStack
            if (END_CALL == evt) {
                // rfc3261#section-15.1.1
                mState = SipSession.State.ENDING_CALL;
                mSipHelper.sendBye(mDialog);
                mProxy.onCallEnded(this);
                startSessionTimer(END_CALL_TIMER);
                return true;
            } else if (isRequestEvent(Request.INVITE, evt)) {
                // got Re-INVITE
                mState = SipSession.State.INCOMING_CALL;
                RequestEvent event = mInviteReceived = (RequestEvent) evt;
                mPeerSessionDescription = extractContent(event.getRequest());
                mServerTransaction = null;
                mProxy.onRinging(this, mPeerProfile, mPeerSessionDescription);
                return true;
            } else if (isRequestEvent(Request.BYE, evt)) {
                mSipHelper.sendResponse((RequestEvent) evt, Response.OK);
                endCallNormally();
                return true;
            } else if (isRequestEvent(Request.REFER, evt)) {
                return processReferRequest((RequestEvent) evt);
            } else if (evt instanceof MakeCallCommand) {
                // to change call
                mState = SipSession.State.OUTGOING_CALL;
                mClientTransaction = mSipHelper.sendReinvite(mDialog,
                        ((MakeCallCommand) evt).getSessionDescription());
                startSessionTimer(((MakeCallCommand) evt).getTimeout());
                return true;
            } else if (evt instanceof ResponseEvent) {
                if (expectResponse(Request.NOTIFY, evt)) return true;
            }
            return false;
        }

        private boolean endingCall(EventObject evt) throws SipException {
            if (expectResponse(Request.BYE, evt)) {
                ResponseEvent event = (ResponseEvent) evt;
                Response response = event.getResponse();

                int statusCode = response.getStatusCode();
                switch (statusCode) {
                    case Response.UNAUTHORIZED:
                    case Response.PROXY_AUTHENTICATION_REQUIRED:
                        if (handleAuthentication(event)) {
                            return true;
                        } else {
                            // can't authenticate; pass through to end session
                        }
                }
                cancelSessionTimer();
                reset();
                return true;
            }
            return false;
        }

        // timeout in seconds
        private void startSessionTimer(int timeout) {
            if (timeout > 0) {
                mSessionTimer = new SessionTimer();
                mSessionTimer.start(timeout);
            }
        }

        private void cancelSessionTimer() {
            if (mSessionTimer != null) {
                mSessionTimer.cancel();
                mSessionTimer = null;
            }
        }

        private String createErrorMessage(Response response) {
            return String.format("%s (%d)", response.getReasonPhrase(),
                    response.getStatusCode());
        }

        private void enableKeepAlive() {
            if (mKeepAliveSession != null) {
                mKeepAliveSession.stopKeepAliveProcess();
            } else {
                mKeepAliveSession = duplicate();
            }
            try {
                mKeepAliveSession.startKeepAliveProcess(
                        INCALL_KEEPALIVE_INTERVAL, mPeerProfile, null);
            } catch (SipException e) {
                Log.w(TAG, "keepalive cannot be enabled; ignored", e);
                mKeepAliveSession.stopKeepAliveProcess();
            }
        }

        private void establishCall(boolean enableKeepAlive) {
            mState = SipSession.State.IN_CALL;
            cancelSessionTimer();
            if (!mInCall && enableKeepAlive) enableKeepAlive();
            mInCall = true;
            mProxy.onCallEstablished(this, mPeerSessionDescription);
        }

        private void endCallNormally() {
            reset();
            mProxy.onCallEnded(this);
        }

        private void endCallOnError(int errorCode, String message) {
            reset();
            mProxy.onError(this, errorCode, message);
        }

        private void endCallOnBusy() {
            reset();
            mProxy.onCallBusy(this);
        }

        private void onError(int errorCode, String message) {
            cancelSessionTimer();
            switch (mState) {
                case SipSession.State.REGISTERING:
                case SipSession.State.DEREGISTERING:
                    onRegistrationFailed(errorCode, message);
                    break;
                default:
                    endCallOnError(errorCode, message);
            }
        }


        private void onError(Throwable exception) {
            exception = getRootCause(exception);
            onError(getErrorCode(exception), exception.toString());
        }

        private void onError(Response response) {
            int statusCode = response.getStatusCode();
            if (!mInCall && (statusCode == Response.BUSY_HERE)) {
                endCallOnBusy();
            } else {
                onError(getErrorCode(statusCode), createErrorMessage(response));
            }
        }

        private int getErrorCode(int responseStatusCode) {
            switch (responseStatusCode) {
                case Response.TEMPORARILY_UNAVAILABLE:
                case Response.FORBIDDEN:
                case Response.GONE:
                case Response.NOT_FOUND:
                case Response.NOT_ACCEPTABLE:
                case Response.NOT_ACCEPTABLE_HERE:
                    return SipErrorCode.PEER_NOT_REACHABLE;

                case Response.REQUEST_URI_TOO_LONG:
                case Response.ADDRESS_INCOMPLETE:
                case Response.AMBIGUOUS:
                    return SipErrorCode.INVALID_REMOTE_URI;

                case Response.REQUEST_TIMEOUT:
                    return SipErrorCode.TIME_OUT;

                default:
                    if (responseStatusCode < 500) {
                        return SipErrorCode.CLIENT_ERROR;
                    } else {
                        return SipErrorCode.SERVER_ERROR;
                    }
            }
        }

        private Throwable getRootCause(Throwable exception) {
            Throwable cause = exception.getCause();
            while (cause != null) {
                exception = cause;
                cause = exception.getCause();
            }
            return exception;
        }

        private int getErrorCode(Throwable exception) {
            String message = exception.getMessage();
            if (exception instanceof UnknownHostException) {
                return SipErrorCode.SERVER_UNREACHABLE;
            } else if (exception instanceof IOException) {
                return SipErrorCode.SOCKET_ERROR;
            } else {
                return SipErrorCode.CLIENT_ERROR;
            }
        }

        private void onRegistrationDone(int duration) {
            reset();
            mProxy.onRegistrationDone(this, duration);
        }

        private void onRegistrationFailed(int errorCode, String message) {
            reset();
            mProxy.onRegistrationFailed(this, errorCode, message);
        }

        private void onRegistrationFailed(Throwable exception) {
            exception = getRootCause(exception);
            onRegistrationFailed(getErrorCode(exception),
                    exception.toString());
        }

        private void onRegistrationFailed(Response response) {
            int statusCode = response.getStatusCode();
            onRegistrationFailed(getErrorCode(statusCode),
                    createErrorMessage(response));
        }

        // Notes: SipSessionListener will be replaced by the keepalive process
        // @param interval in seconds
        public void startKeepAliveProcess(int interval,
                KeepAliveProcessCallback callback) throws SipException {
            synchronized (SipSessionGroup.this) {
                startKeepAliveProcess(interval, mLocalProfile, callback);
            }
        }

        // Notes: SipSessionListener will be replaced by the keepalive process
        // @param interval in seconds
        public void startKeepAliveProcess(int interval, SipProfile peerProfile,
                KeepAliveProcessCallback callback) throws SipException {
            synchronized (SipSessionGroup.this) {
                if (mKeepAliveProcess != null) {
                    throw new SipException("Cannot create more than one "
                            + "keepalive process in a SipSession");
                }
                mPeerProfile = peerProfile;
                mKeepAliveProcess = new KeepAliveProcess();
                mProxy.setListener(mKeepAliveProcess);
                mKeepAliveProcess.start(interval, callback);
            }
        }

        public void stopKeepAliveProcess() {
            synchronized (SipSessionGroup.this) {
                if (mKeepAliveProcess != null) {
                    mKeepAliveProcess.stop();
                    mKeepAliveProcess = null;
                }
            }
        }

        class KeepAliveProcess extends SipSessionAdapter implements Runnable {
            private static final String TAG = "SipKeepAlive";
            private boolean mRunning = false;
            private KeepAliveProcessCallback mCallback;

            private boolean mPortChanged = false;
            private int mRPort = 0;
            private int mInterval; // just for debugging

            // @param interval in seconds
            void start(int interval, KeepAliveProcessCallback callback) {
                if (mRunning) return;
                mRunning = true;
                mInterval = interval;
                mCallback = new KeepAliveProcessCallbackProxy(callback);
                mWakeupTimer.set(interval * 1000, this);
                if (DEBUG) {
                    Log.d(TAG, "start keepalive:"
                            + mLocalProfile.getUriString());
                }

                // No need to run the first time in a separate thread for now
                run();
            }

            // return true if the event is consumed
            boolean process(EventObject evt) throws SipException {
                if (mRunning && (mState == SipSession.State.PINGING)) {
                    if (evt instanceof ResponseEvent) {
                        if (parseOptionsResult(evt)) {
                            if (mPortChanged) {
                                resetExternalAddress();
                                stop();
                            } else {
                                cancelSessionTimer();
                                removeSipSession(SipSessionImpl.this);
                            }
                            mCallback.onResponse(mPortChanged);
                            return true;
                        }
                    }
                }
                return false;
            }

            // SipSessionAdapter
            // To react to the session timeout event and network error.
            @Override
            public void onError(ISipSession session, int errorCode, String message) {
                stop();
                mCallback.onError(errorCode, message);
            }

            // SipWakeupTimer timeout handler
            // To send out keepalive message.
            @Override
            public void run() {
                synchronized (SipSessionGroup.this) {
                    if (!mRunning) return;

                    if (DEBUG_PING) {
                        String peerUri = (mPeerProfile == null)
                                ? "null"
                                : mPeerProfile.getUriString();
                        Log.d(TAG, "keepalive: " + mLocalProfile.getUriString()
                                + " --> " + peerUri + ", interval=" + mInterval);
                    }
                    try {
                        sendKeepAlive();
                    } catch (Throwable t) {
                        Log.w(TAG, "keepalive error: " + ": "
                                + mLocalProfile.getUriString(), getRootCause(t));
                        // It's possible that the keepalive process is being stopped
                        // during session.sendKeepAlive() so need to check mRunning
                        // again here.
                        if (mRunning) SipSessionImpl.this.onError(t);
                    }
                }
            }

            void stop() {
                synchronized (SipSessionGroup.this) {
                    if (DEBUG) {
                        Log.d(TAG, "stop keepalive:" + mLocalProfile.getUriString()
                                + ",RPort=" + mRPort);
                    }
                    mRunning = false;
                    mWakeupTimer.cancel(this);
                    reset();
                }
            }

            private void sendKeepAlive() throws SipException, InterruptedException {
                synchronized (SipSessionGroup.this) {
                    mState = SipSession.State.PINGING;
                    mClientTransaction = mSipHelper.sendOptions(
                            mLocalProfile, mPeerProfile, generateTag());
                    mDialog = mClientTransaction.getDialog();
                    addSipSession(SipSessionImpl.this);

                    startSessionTimer(KEEPALIVE_TIMEOUT);
                    // when timed out, onError() will be called with SipErrorCode.TIME_OUT
                }
            }

            private boolean parseOptionsResult(EventObject evt) {
                if (expectResponse(Request.OPTIONS, evt)) {
                    ResponseEvent event = (ResponseEvent) evt;
                    int rPort = getRPortFromResponse(event.getResponse());
                    if (rPort != -1) {
                        if (mRPort == 0) mRPort = rPort;
                        if (mRPort != rPort) {
                            mPortChanged = true;
                            if (DEBUG) Log.d(TAG, String.format(
                                    "rport is changed: %d <> %d", mRPort, rPort));
                            mRPort = rPort;
                        } else {
                            if (DEBUG) Log.d(TAG, "rport is the same: " + rPort);
                        }
                    } else {
                        if (DEBUG) Log.w(TAG, "peer did not respond rport");
                    }
                    return true;
                }
                return false;
            }

            private int getRPortFromResponse(Response response) {
                ViaHeader viaHeader = (ViaHeader)(response.getHeader(
                        SIPHeaderNames.VIA));
                return (viaHeader == null) ? -1 : viaHeader.getRPort();
            }
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

    private static SipProfile createPeerProfile(HeaderAddress header)
            throws SipException {
        try {
            Address address = header.getAddress();
            SipURI uri = (SipURI) address.getURI();
            String username = uri.getUser();
            if (username == null) username = ANONYMOUS;
            int port = uri.getPort();
            SipProfile.Builder builder =
                    new SipProfile.Builder(username, uri.getHost())
                    .setDisplayName(address.getDisplayName());
            if (port > 0) builder.setPort(port);
            return builder.build();
        } catch (IllegalArgumentException e) {
            throw new SipException("createPeerProfile()", e);
        } catch (ParseException e) {
            throw new SipException("createPeerProfile()", e);
        }
    }

    private static boolean isLoggable(SipSessionImpl s) {
        if (s != null) {
            switch (s.mState) {
                case SipSession.State.PINGING:
                    return DEBUG_PING;
            }
        }
        return DEBUG;
    }

    private static boolean isLoggable(EventObject evt) {
        return isLoggable(null, evt);
    }

    private static boolean isLoggable(SipSessionImpl s, EventObject evt) {
        if (!isLoggable(s)) return false;
        if (evt == null) return false;

        if (evt instanceof ResponseEvent) {
            Response response = ((ResponseEvent) evt).getResponse();
            if (Request.OPTIONS.equals(response.getHeader(CSeqHeader.NAME))) {
                return DEBUG_PING;
            }
            return DEBUG;
        } else if (evt instanceof RequestEvent) {
            if (isRequestEvent(Request.OPTIONS, evt)) {
                return DEBUG_PING;
            }
            return DEBUG;
        }
        return false;
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
        private String mSessionDescription;
        private int mTimeout; // in seconds

        public MakeCallCommand(SipProfile peerProfile,
                String sessionDescription) {
            this(peerProfile, sessionDescription, -1);
        }

        public MakeCallCommand(SipProfile peerProfile,
                String sessionDescription, int timeout) {
            super(peerProfile);
            mSessionDescription = sessionDescription;
            mTimeout = timeout;
        }

        public SipProfile getPeerProfile() {
            return (SipProfile) getSource();
        }

        public String getSessionDescription() {
            return mSessionDescription;
        }

        public int getTimeout() {
            return mTimeout;
        }
    }

    /** Class to help safely run KeepAliveProcessCallback in a different thread. */
    static class KeepAliveProcessCallbackProxy implements KeepAliveProcessCallback {
        private KeepAliveProcessCallback mCallback;

        KeepAliveProcessCallbackProxy(KeepAliveProcessCallback callback) {
            mCallback = callback;
        }

        private void proxy(Runnable runnable) {
            // One thread for each calling back.
            // Note: Guarantee ordering if the issue becomes important. Currently,
            // the chance of handling two callback events at a time is none.
            new Thread(runnable, "SIP-KeepAliveProcessCallbackThread").start();
        }

        public void onResponse(final boolean portChanged) {
            if (mCallback == null) return;
            proxy(new Runnable() {
                public void run() {
                    try {
                        mCallback.onResponse(portChanged);
                    } catch (Throwable t) {
                        Log.w(TAG, "onResponse", t);
                    }
                }
            });
        }

        public void onError(final int errorCode, final String description) {
            if (mCallback == null) return;
            proxy(new Runnable() {
                public void run() {
                    try {
                        mCallback.onError(errorCode, description);
                    } catch (Throwable t) {
                        Log.w(TAG, "onError", t);
                    }
                }
            });
        }
    }
}
