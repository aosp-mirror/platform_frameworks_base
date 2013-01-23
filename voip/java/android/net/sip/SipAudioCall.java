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

package android.net.sip;

import android.content.Context;
import android.media.AudioManager;
import android.net.rtp.AudioCodec;
import android.net.rtp.AudioGroup;
import android.net.rtp.AudioStream;
import android.net.rtp.RtpStream;
import android.net.sip.SimpleSessionDescription.Media;
import android.net.wifi.WifiManager;
import android.os.Message;
import android.telephony.Rlog;
import android.text.TextUtils;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Handles an Internet audio call over SIP. You can instantiate this class with {@link SipManager},
 * using {@link SipManager#makeAudioCall makeAudioCall()} and  {@link SipManager#takeAudioCall
 * takeAudioCall()}.
 *
 * <p class="note"><strong>Note:</strong> Using this class require the
 *   {@link android.Manifest.permission#INTERNET} and
 *   {@link android.Manifest.permission#USE_SIP} permissions. In addition, {@link
 *   #startAudio} requires the
 *   {@link android.Manifest.permission#RECORD_AUDIO},
 *   {@link android.Manifest.permission#ACCESS_WIFI_STATE}, and
 *   {@link android.Manifest.permission#WAKE_LOCK} permissions; and {@link #setSpeakerMode
 *   setSpeakerMode()} requires the
 *   {@link android.Manifest.permission#MODIFY_AUDIO_SETTINGS} permission.</p>
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about using SIP, read the
 * <a href="{@docRoot}guide/topics/network/sip.html">Session Initiation Protocol</a>
 * developer guide.</p>
 * </div>
 */
public class SipAudioCall {
    private static final String LOG_TAG = SipAudioCall.class.getSimpleName();
    private static final boolean DBG = true;
    private static final boolean RELEASE_SOCKET = true;
    private static final boolean DONT_RELEASE_SOCKET = false;
    private static final int SESSION_TIMEOUT = 5; // in seconds
    private static final int TRANSFER_TIMEOUT = 15; // in seconds

    /** Listener for events relating to a SIP call, such as when a call is being
     * recieved ("on ringing") or a call is outgoing ("on calling").
     * <p>Many of these events are also received by {@link SipSession.Listener}.</p>
     */
    public static class Listener {
        /**
         * Called when the call object is ready to make another call.
         * The default implementation calls {@link #onChanged}.
         *
         * @param call the call object that is ready to make another call
         */
        public void onReadyToCall(SipAudioCall call) {
            onChanged(call);
        }

        /**
         * Called when a request is sent out to initiate a new call.
         * The default implementation calls {@link #onChanged}.
         *
         * @param call the call object that carries out the audio call
         */
        public void onCalling(SipAudioCall call) {
            onChanged(call);
        }

        /**
         * Called when a new call comes in.
         * The default implementation calls {@link #onChanged}.
         *
         * @param call the call object that carries out the audio call
         * @param caller the SIP profile of the caller
         */
        public void onRinging(SipAudioCall call, SipProfile caller) {
            onChanged(call);
        }

        /**
         * Called when a RINGING response is received for the INVITE request
         * sent. The default implementation calls {@link #onChanged}.
         *
         * @param call the call object that carries out the audio call
         */
        public void onRingingBack(SipAudioCall call) {
            onChanged(call);
        }

        /**
         * Called when the session is established.
         * The default implementation calls {@link #onChanged}.
         *
         * @param call the call object that carries out the audio call
         */
        public void onCallEstablished(SipAudioCall call) {
            onChanged(call);
        }

        /**
         * Called when the session is terminated.
         * The default implementation calls {@link #onChanged}.
         *
         * @param call the call object that carries out the audio call
         */
        public void onCallEnded(SipAudioCall call) {
            onChanged(call);
        }

        /**
         * Called when the peer is busy during session initialization.
         * The default implementation calls {@link #onChanged}.
         *
         * @param call the call object that carries out the audio call
         */
        public void onCallBusy(SipAudioCall call) {
            onChanged(call);
        }

        /**
         * Called when the call is on hold.
         * The default implementation calls {@link #onChanged}.
         *
         * @param call the call object that carries out the audio call
         */
        public void onCallHeld(SipAudioCall call) {
            onChanged(call);
        }

        /**
         * Called when an error occurs. The default implementation is no op.
         *
         * @param call the call object that carries out the audio call
         * @param errorCode error code of this error
         * @param errorMessage error message
         * @see SipErrorCode
         */
        public void onError(SipAudioCall call, int errorCode,
                String errorMessage) {
            // no-op
        }

        /**
         * Called when an event occurs and the corresponding callback is not
         * overridden. The default implementation is no op. Error events are
         * not re-directed to this callback and are handled in {@link #onError}.
         */
        public void onChanged(SipAudioCall call) {
            // no-op
        }
    }

    private Context mContext;
    private SipProfile mLocalProfile;
    private SipAudioCall.Listener mListener;
    private SipSession mSipSession;
    private SipSession mTransferringSession;

    private long mSessionId = System.currentTimeMillis();
    private String mPeerSd;

    private AudioStream mAudioStream;
    private AudioGroup mAudioGroup;

    private boolean mInCall = false;
    private boolean mMuted = false;
    private boolean mHold = false;

    private WifiManager mWm;
    private WifiManager.WifiLock mWifiHighPerfLock;

    private int mErrorCode = SipErrorCode.NO_ERROR;
    private String mErrorMessage;

    /**
     * Creates a call object with the local SIP profile.
     * @param context the context for accessing system services such as
     *        ringtone, audio, WIFI etc
     */
    public SipAudioCall(Context context, SipProfile localProfile) {
        mContext = context;
        mLocalProfile = localProfile;
        mWm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
    }

    /**
     * Sets the listener to listen to the audio call events. The method calls
     * {@link #setListener setListener(listener, false)}.
     *
     * @param listener to listen to the audio call events of this object
     * @see #setListener(Listener, boolean)
     */
    public void setListener(SipAudioCall.Listener listener) {
        setListener(listener, false);
    }

    /**
     * Sets the listener to listen to the audio call events. A
     * {@link SipAudioCall} can only hold one listener at a time. Subsequent
     * calls to this method override the previous listener.
     *
     * @param listener to listen to the audio call events of this object
     * @param callbackImmediately set to true if the caller wants to be called
     *      back immediately on the current state
     */
    public void setListener(SipAudioCall.Listener listener,
            boolean callbackImmediately) {
        mListener = listener;
        try {
            if ((listener == null) || !callbackImmediately) {
                // do nothing
            } else if (mErrorCode != SipErrorCode.NO_ERROR) {
                listener.onError(this, mErrorCode, mErrorMessage);
            } else if (mInCall) {
                if (mHold) {
                    listener.onCallHeld(this);
                } else {
                    listener.onCallEstablished(this);
                }
            } else {
                int state = getState();
                switch (state) {
                    case SipSession.State.READY_TO_CALL:
                        listener.onReadyToCall(this);
                        break;
                    case SipSession.State.INCOMING_CALL:
                        listener.onRinging(this, getPeerProfile());
                        break;
                    case SipSession.State.OUTGOING_CALL:
                        listener.onCalling(this);
                        break;
                    case SipSession.State.OUTGOING_CALL_RING_BACK:
                        listener.onRingingBack(this);
                        break;
                }
            }
        } catch (Throwable t) {
            loge("setListener()", t);
        }
    }

    /**
     * Checks if the call is established.
     *
     * @return true if the call is established
     */
    public boolean isInCall() {
        synchronized (this) {
            return mInCall;
        }
    }

    /**
     * Checks if the call is on hold.
     *
     * @return true if the call is on hold
     */
    public boolean isOnHold() {
        synchronized (this) {
            return mHold;
        }
    }

    /**
     * Closes this object. This object is not usable after being closed.
     */
    public void close() {
        close(true);
    }

    private synchronized void close(boolean closeRtp) {
        if (closeRtp) stopCall(RELEASE_SOCKET);

        mInCall = false;
        mHold = false;
        mSessionId = System.currentTimeMillis();
        mErrorCode = SipErrorCode.NO_ERROR;
        mErrorMessage = null;

        if (mSipSession != null) {
            mSipSession.setListener(null);
            mSipSession = null;
        }
    }

    /**
     * Gets the local SIP profile.
     *
     * @return the local SIP profile
     */
    public SipProfile getLocalProfile() {
        synchronized (this) {
            return mLocalProfile;
        }
    }

    /**
     * Gets the peer's SIP profile.
     *
     * @return the peer's SIP profile
     */
    public SipProfile getPeerProfile() {
        synchronized (this) {
            return (mSipSession == null) ? null : mSipSession.getPeerProfile();
        }
    }

    /**
     * Gets the state of the {@link SipSession} that carries this call.
     * The value returned must be one of the states in {@link SipSession.State}.
     *
     * @return the session state
     */
    public int getState() {
        synchronized (this) {
            if (mSipSession == null) return SipSession.State.READY_TO_CALL;
            return mSipSession.getState();
        }
    }


    /**
     * Gets the {@link SipSession} that carries this call.
     *
     * @return the session object that carries this call
     * @hide
     */
    public SipSession getSipSession() {
        synchronized (this) {
            return mSipSession;
        }
    }

    private synchronized void transferToNewSession() {
        if (mTransferringSession == null) return;
        SipSession origin = mSipSession;
        mSipSession = mTransferringSession;
        mTransferringSession = null;

        // stop the replaced call.
        if (mAudioStream != null) {
            mAudioStream.join(null);
        } else {
            try {
                mAudioStream = new AudioStream(InetAddress.getByName(
                        getLocalIp()));
            } catch (Throwable t) {
                loge("transferToNewSession():", t);
            }
        }
        if (origin != null) origin.endCall();
        startAudio();
    }

    private SipSession.Listener createListener() {
        return new SipSession.Listener() {
            @Override
            public void onCalling(SipSession session) {
                if (DBG) log("onCalling: session=" + session);
                Listener listener = mListener;
                if (listener != null) {
                    try {
                        listener.onCalling(SipAudioCall.this);
                    } catch (Throwable t) {
                        loge("onCalling():", t);
                    }
                }
            }

            @Override
            public void onRingingBack(SipSession session) {
                if (DBG) log("onRingingBackk: " + session);
                Listener listener = mListener;
                if (listener != null) {
                    try {
                        listener.onRingingBack(SipAudioCall.this);
                    } catch (Throwable t) {
                        loge("onRingingBack():", t);
                    }
                }
            }

            @Override
            public void onRinging(SipSession session,
                    SipProfile peerProfile, String sessionDescription) {
                // this callback is triggered only for reinvite.
                synchronized (SipAudioCall.this) {
                    if ((mSipSession == null) || !mInCall
                            || !session.getCallId().equals(
                                    mSipSession.getCallId())) {
                        // should not happen
                        session.endCall();
                        return;
                    }

                    // session changing request
                    try {
                        String answer = createAnswer(sessionDescription).encode();
                        mSipSession.answerCall(answer, SESSION_TIMEOUT);
                    } catch (Throwable e) {
                        loge("onRinging():", e);
                        session.endCall();
                    }
                }
            }

            @Override
            public void onCallEstablished(SipSession session,
                    String sessionDescription) {
                mPeerSd = sessionDescription;
                if (DBG) log("onCallEstablished(): " + mPeerSd);

                // TODO: how to notify the UI that the remote party is changed
                if ((mTransferringSession != null)
                        && (session == mTransferringSession)) {
                    transferToNewSession();
                    return;
                }

                Listener listener = mListener;
                if (listener != null) {
                    try {
                        if (mHold) {
                            listener.onCallHeld(SipAudioCall.this);
                        } else {
                            listener.onCallEstablished(SipAudioCall.this);
                        }
                    } catch (Throwable t) {
                        loge("onCallEstablished(): ", t);
                    }
                }
            }

            @Override
            public void onCallEnded(SipSession session) {
                if (DBG) log("onCallEnded: " + session + " mSipSession:" + mSipSession);
                // reset the trasnferring session if it is the one.
                if (session == mTransferringSession) {
                    mTransferringSession = null;
                    return;
                }
                // or ignore the event if the original session is being
                // transferred to the new one.
                if ((mTransferringSession != null) ||
                    (session != mSipSession)) return;

                Listener listener = mListener;
                if (listener != null) {
                    try {
                        listener.onCallEnded(SipAudioCall.this);
                    } catch (Throwable t) {
                        loge("onCallEnded(): ", t);
                    }
                }
                close();
            }

            @Override
            public void onCallBusy(SipSession session) {
                if (DBG) log("onCallBusy: " + session);
                Listener listener = mListener;
                if (listener != null) {
                    try {
                        listener.onCallBusy(SipAudioCall.this);
                    } catch (Throwable t) {
                        loge("onCallBusy(): ", t);
                    }
                }
                close(false);
            }

            @Override
            public void onCallChangeFailed(SipSession session, int errorCode,
                    String message) {
                if (DBG) log("onCallChangedFailed: " + message);
                mErrorCode = errorCode;
                mErrorMessage = message;
                Listener listener = mListener;
                if (listener != null) {
                    try {
                        listener.onError(SipAudioCall.this, mErrorCode,
                                message);
                    } catch (Throwable t) {
                        loge("onCallBusy():", t);
                    }
                }
            }

            @Override
            public void onError(SipSession session, int errorCode,
                    String message) {
                SipAudioCall.this.onError(errorCode, message);
            }

            @Override
            public void onRegistering(SipSession session) {
                // irrelevant
            }

            @Override
            public void onRegistrationTimeout(SipSession session) {
                // irrelevant
            }

            @Override
            public void onRegistrationFailed(SipSession session, int errorCode,
                    String message) {
                // irrelevant
            }

            @Override
            public void onRegistrationDone(SipSession session, int duration) {
                // irrelevant
            }

            @Override
            public void onCallTransferring(SipSession newSession,
                    String sessionDescription) {
                if (DBG) log("onCallTransferring: mSipSession="
                        + mSipSession + " newSession=" + newSession);
                mTransferringSession = newSession;
                try {
                    if (sessionDescription == null) {
                        newSession.makeCall(newSession.getPeerProfile(),
                                createOffer().encode(), TRANSFER_TIMEOUT);
                    } else {
                        String answer = createAnswer(sessionDescription).encode();
                        newSession.answerCall(answer, SESSION_TIMEOUT);
                    }
                } catch (Throwable e) {
                    loge("onCallTransferring()", e);
                    newSession.endCall();
                }
            }
        };
    }

    private void onError(int errorCode, String message) {
        if (DBG) log("onError: "
                + SipErrorCode.toString(errorCode) + ": " + message);
        mErrorCode = errorCode;
        mErrorMessage = message;
        Listener listener = mListener;
        if (listener != null) {
            try {
                listener.onError(this, errorCode, message);
            } catch (Throwable t) {
                loge("onError():", t);
            }
        }
        synchronized (this) {
            if ((errorCode == SipErrorCode.DATA_CONNECTION_LOST)
                    || !isInCall()) {
                close(true);
            }
        }
    }

    /**
     * Attaches an incoming call to this call object.
     *
     * @param session the session that receives the incoming call
     * @param sessionDescription the session description of the incoming call
     * @throws SipException if the SIP service fails to attach this object to
     *        the session or VOIP API is not supported by the device
     * @see SipManager#isVoipSupported
     */
    public void attachCall(SipSession session, String sessionDescription)
            throws SipException {
        if (!SipManager.isVoipSupported(mContext)) {
            throw new SipException("VOIP API is not supported");
        }

        synchronized (this) {
            mSipSession = session;
            mPeerSd = sessionDescription;
            if (DBG) log("attachCall(): " + mPeerSd);
            try {
                session.setListener(createListener());
            } catch (Throwable e) {
                loge("attachCall()", e);
                throwSipException(e);
            }
        }
    }

    /**
     * Initiates an audio call to the specified profile. The attempt will be
     * timed out if the call is not established within {@code timeout} seconds
     * and {@link Listener#onError onError(SipAudioCall, SipErrorCode.TIME_OUT, String)}
     * will be called.
     *
     * @param peerProfile the SIP profile to make the call to
     * @param sipSession the {@link SipSession} for carrying out the call
     * @param timeout the timeout value in seconds. Default value (defined by
     *        SIP protocol) is used if {@code timeout} is zero or negative.
     * @see Listener#onError
     * @throws SipException if the SIP service fails to create a session for the
     *        call or VOIP API is not supported by the device
     * @see SipManager#isVoipSupported
     */
    public void makeCall(SipProfile peerProfile, SipSession sipSession,
            int timeout) throws SipException {
        if (DBG) log("makeCall: " + peerProfile + " session=" + sipSession + " timeout=" + timeout);
        if (!SipManager.isVoipSupported(mContext)) {
            throw new SipException("VOIP API is not supported");
        }

        synchronized (this) {
            mSipSession = sipSession;
            try {
                mAudioStream = new AudioStream(InetAddress.getByName(
                        getLocalIp()));
                sipSession.setListener(createListener());
                sipSession.makeCall(peerProfile, createOffer().encode(),
                        timeout);
            } catch (IOException e) {
                loge("makeCall:", e);
                throw new SipException("makeCall()", e);
            }
        }
    }

    /**
     * Ends a call.
     * @throws SipException if the SIP service fails to end the call
     */
    public void endCall() throws SipException {
        if (DBG) log("endCall: mSipSession" + mSipSession);
        synchronized (this) {
            stopCall(RELEASE_SOCKET);
            mInCall = false;

            // perform the above local ops first and then network op
            if (mSipSession != null) mSipSession.endCall();
        }
    }

    /**
     * Puts a call on hold.  When succeeds, {@link Listener#onCallHeld} is
     * called. The attempt will be timed out if the call is not established
     * within {@code timeout} seconds and
     * {@link Listener#onError onError(SipAudioCall, SipErrorCode.TIME_OUT, String)}
     * will be called.
     *
     * @param timeout the timeout value in seconds. Default value (defined by
     *        SIP protocol) is used if {@code timeout} is zero or negative.
     * @see Listener#onError
     * @throws SipException if the SIP service fails to hold the call
     */
    public void holdCall(int timeout) throws SipException {
        if (DBG) log("holdCall: mSipSession" + mSipSession + " timeout=" + timeout);
        synchronized (this) {
            if (mHold) return;
            if (mSipSession == null) {
                loge("holdCall:");
                throw new SipException("Not in a call to hold call");
            }
            mSipSession.changeCall(createHoldOffer().encode(), timeout);
            mHold = true;
            setAudioGroupMode();
        }
    }

    /**
     * Answers a call. The attempt will be timed out if the call is not
     * established within {@code timeout} seconds and
     * {@link Listener#onError onError(SipAudioCall, SipErrorCode.TIME_OUT, String)}
     * will be called.
     *
     * @param timeout the timeout value in seconds. Default value (defined by
     *        SIP protocol) is used if {@code timeout} is zero or negative.
     * @see Listener#onError
     * @throws SipException if the SIP service fails to answer the call
     */
    public void answerCall(int timeout) throws SipException {
        if (DBG) log("answerCall: mSipSession" + mSipSession + " timeout=" + timeout);
        synchronized (this) {
            if (mSipSession == null) {
                throw new SipException("No call to answer");
            }
            try {
                mAudioStream = new AudioStream(InetAddress.getByName(
                        getLocalIp()));
                mSipSession.answerCall(createAnswer(mPeerSd).encode(), timeout);
            } catch (IOException e) {
                loge("answerCall:", e);
                throw new SipException("answerCall()", e);
            }
        }
    }

    /**
     * Continues a call that's on hold. When succeeds,
     * {@link Listener#onCallEstablished} is called. The attempt will be timed
     * out if the call is not established within {@code timeout} seconds and
     * {@link Listener#onError onError(SipAudioCall, SipErrorCode.TIME_OUT, String)}
     * will be called.
     *
     * @param timeout the timeout value in seconds. Default value (defined by
     *        SIP protocol) is used if {@code timeout} is zero or negative.
     * @see Listener#onError
     * @throws SipException if the SIP service fails to unhold the call
     */
    public void continueCall(int timeout) throws SipException {
        if (DBG) log("continueCall: mSipSession" + mSipSession + " timeout=" + timeout);
        synchronized (this) {
            if (!mHold) return;
            mSipSession.changeCall(createContinueOffer().encode(), timeout);
            mHold = false;
            setAudioGroupMode();
        }
    }

    private SimpleSessionDescription createOffer() {
        SimpleSessionDescription offer =
                new SimpleSessionDescription(mSessionId, getLocalIp());
        AudioCodec[] codecs = AudioCodec.getCodecs();
        Media media = offer.newMedia(
                "audio", mAudioStream.getLocalPort(), 1, "RTP/AVP");
        for (AudioCodec codec : AudioCodec.getCodecs()) {
            media.setRtpPayload(codec.type, codec.rtpmap, codec.fmtp);
        }
        media.setRtpPayload(127, "telephone-event/8000", "0-15");
        if (DBG) log("createOffer: offer=" + offer);
        return offer;
    }

    private SimpleSessionDescription createAnswer(String offerSd) {
        if (TextUtils.isEmpty(offerSd)) return createOffer();
        SimpleSessionDescription offer =
                new SimpleSessionDescription(offerSd);
        SimpleSessionDescription answer =
                new SimpleSessionDescription(mSessionId, getLocalIp());
        AudioCodec codec = null;
        for (Media media : offer.getMedia()) {
            if ((codec == null) && (media.getPort() > 0)
                    && "audio".equals(media.getType())
                    && "RTP/AVP".equals(media.getProtocol())) {
                // Find the first audio codec we supported.
                for (int type : media.getRtpPayloadTypes()) {
                    codec = AudioCodec.getCodec(type, media.getRtpmap(type),
                            media.getFmtp(type));
                    if (codec != null) {
                        break;
                    }
                }
                if (codec != null) {
                    Media reply = answer.newMedia(
                            "audio", mAudioStream.getLocalPort(), 1, "RTP/AVP");
                    reply.setRtpPayload(codec.type, codec.rtpmap, codec.fmtp);

                    // Check if DTMF is supported in the same media.
                    for (int type : media.getRtpPayloadTypes()) {
                        String rtpmap = media.getRtpmap(type);
                        if ((type != codec.type) && (rtpmap != null)
                                && rtpmap.startsWith("telephone-event")) {
                            reply.setRtpPayload(
                                    type, rtpmap, media.getFmtp(type));
                        }
                    }

                    // Handle recvonly and sendonly.
                    if (media.getAttribute("recvonly") != null) {
                        answer.setAttribute("sendonly", "");
                    } else if(media.getAttribute("sendonly") != null) {
                        answer.setAttribute("recvonly", "");
                    } else if(offer.getAttribute("recvonly") != null) {
                        answer.setAttribute("sendonly", "");
                    } else if(offer.getAttribute("sendonly") != null) {
                        answer.setAttribute("recvonly", "");
                    }
                    continue;
                }
            }
            // Reject the media.
            Media reply = answer.newMedia(
                    media.getType(), 0, 1, media.getProtocol());
            for (String format : media.getFormats()) {
                reply.setFormat(format, null);
            }
        }
        if (codec == null) {
            loge("createAnswer: no suitable codes");
            throw new IllegalStateException("Reject SDP: no suitable codecs");
        }
        if (DBG) log("createAnswer: answer=" + answer);
        return answer;
    }

    private SimpleSessionDescription createHoldOffer() {
        SimpleSessionDescription offer = createContinueOffer();
        offer.setAttribute("sendonly", "");
        if (DBG) log("createHoldOffer: offer=" + offer);
        return offer;
    }

    private SimpleSessionDescription createContinueOffer() {
        if (DBG) log("createContinueOffer");
        SimpleSessionDescription offer =
                new SimpleSessionDescription(mSessionId, getLocalIp());
        Media media = offer.newMedia(
                "audio", mAudioStream.getLocalPort(), 1, "RTP/AVP");
        AudioCodec codec = mAudioStream.getCodec();
        media.setRtpPayload(codec.type, codec.rtpmap, codec.fmtp);
        int dtmfType = mAudioStream.getDtmfType();
        if (dtmfType != -1) {
            media.setRtpPayload(dtmfType, "telephone-event/8000", "0-15");
        }
        return offer;
    }

    private void grabWifiHighPerfLock() {
        if (mWifiHighPerfLock == null) {
            if (DBG) log("grabWifiHighPerfLock:");
            mWifiHighPerfLock = ((WifiManager)
                    mContext.getSystemService(Context.WIFI_SERVICE))
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, LOG_TAG);
            mWifiHighPerfLock.acquire();
        }
    }

    private void releaseWifiHighPerfLock() {
        if (mWifiHighPerfLock != null) {
            if (DBG) log("releaseWifiHighPerfLock:");
            mWifiHighPerfLock.release();
            mWifiHighPerfLock = null;
        }
    }

    private boolean isWifiOn() {
        return (mWm.getConnectionInfo().getBSSID() == null) ? false : true;
    }

    /** Toggles mute. */
    public void toggleMute() {
        synchronized (this) {
            mMuted = !mMuted;
            setAudioGroupMode();
        }
    }

    /**
     * Checks if the call is muted.
     *
     * @return true if the call is muted
     */
    public boolean isMuted() {
        synchronized (this) {
            return mMuted;
        }
    }

    /**
     * Puts the device to speaker mode.
     * <p class="note"><strong>Note:</strong> Requires the
     *   {@link android.Manifest.permission#MODIFY_AUDIO_SETTINGS} permission.</p>
     *
     * @param speakerMode set true to enable speaker mode; false to disable
     */
    public void setSpeakerMode(boolean speakerMode) {
        synchronized (this) {
            ((AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE))
                    .setSpeakerphoneOn(speakerMode);
            setAudioGroupMode();
        }
    }

    private boolean isSpeakerOn() {
        return ((AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE))
                .isSpeakerphoneOn();
    }

    /**
     * Sends a DTMF code. According to <a href="http://tools.ietf.org/html/rfc2833">RFC 2883</a>,
     * event 0--9 maps to decimal
     * value 0--9, '*' to 10, '#' to 11, event 'A'--'D' to 12--15, and event
     * flash to 16. Currently, event flash is not supported.
     *
     * @param code the DTMF code to send. Value 0 to 15 (inclusive) are valid
     *        inputs.
     */
    public void sendDtmf(int code) {
        sendDtmf(code, null);
    }

    /**
     * Sends a DTMF code. According to <a href="http://tools.ietf.org/html/rfc2833">RFC 2883</a>,
     * event 0--9 maps to decimal
     * value 0--9, '*' to 10, '#' to 11, event 'A'--'D' to 12--15, and event
     * flash to 16. Currently, event flash is not supported.
     *
     * @param code the DTMF code to send. Value 0 to 15 (inclusive) are valid
     *        inputs.
     * @param result the result message to send when done
     */
    public void sendDtmf(int code, Message result) {
        synchronized (this) {
            AudioGroup audioGroup = getAudioGroup();
            if ((audioGroup != null) && (mSipSession != null)
                    && (SipSession.State.IN_CALL == getState())) {
                if (DBG) log("sendDtmf: code=" + code + " result=" + result);
                audioGroup.sendDtmf(code);
            }
            if (result != null) result.sendToTarget();
        }
    }

    /**
     * Gets the {@link AudioStream} object used in this call. The object
     * represents the RTP stream that carries the audio data to and from the
     * peer. The object may not be created before the call is established. And
     * it is undefined after the call ends or the {@link #close} method is
     * called.
     *
     * @return the {@link AudioStream} object or null if the RTP stream has not
     *      yet been set up
     * @hide
     */
    public AudioStream getAudioStream() {
        synchronized (this) {
            return mAudioStream;
        }
    }

    /**
     * Gets the {@link AudioGroup} object which the {@link AudioStream} object
     * joins. The group object may not exist before the call is established.
     * Also, the {@code AudioStream} may change its group during a call (e.g.,
     * after the call is held/un-held). Finally, the {@code AudioGroup} object
     * returned by this method is undefined after the call ends or the
     * {@link #close} method is called. If a group object is set by
     * {@link #setAudioGroup(AudioGroup)}, then this method returns that object.
     *
     * @return the {@link AudioGroup} object or null if the RTP stream has not
     *      yet been set up
     * @see #getAudioStream
     * @hide
     */
    public AudioGroup getAudioGroup() {
        synchronized (this) {
            if (mAudioGroup != null) return mAudioGroup;
            return ((mAudioStream == null) ? null : mAudioStream.getGroup());
        }
    }

    /**
     * Sets the {@link AudioGroup} object which the {@link AudioStream} object
     * joins. If {@code audioGroup} is null, then the {@code AudioGroup} object
     * will be dynamically created when needed. Note that the mode of the
     * {@code AudioGroup} is not changed according to the audio settings (i.e.,
     * hold, mute, speaker phone) of this object. This is mainly used to merge
     * multiple {@code SipAudioCall} objects to form a conference call. The
     * settings of the first object (that merges others) override others'.
     *
     * @see #getAudioStream
     * @hide
     */
    public void setAudioGroup(AudioGroup group) {
        synchronized (this) {
            if (DBG) log("setAudioGroup: group=" + group);
            if ((mAudioStream != null) && (mAudioStream.getGroup() != null)) {
                mAudioStream.join(group);
            }
            mAudioGroup = group;
        }
    }

    /**
     * Starts the audio for the established call. This method should be called
     * after {@link Listener#onCallEstablished} is called.
     * <p class="note"><strong>Note:</strong> Requires the
     *   {@link android.Manifest.permission#RECORD_AUDIO},
     *   {@link android.Manifest.permission#ACCESS_WIFI_STATE} and
     *   {@link android.Manifest.permission#WAKE_LOCK} permissions.</p>
     */
    public void startAudio() {
        try {
            startAudioInternal();
        } catch (UnknownHostException e) {
            onError(SipErrorCode.PEER_NOT_REACHABLE, e.getMessage());
        } catch (Throwable e) {
            onError(SipErrorCode.CLIENT_ERROR, e.getMessage());
        }
    }

    private synchronized void startAudioInternal() throws UnknownHostException {
        if (DBG) loge("startAudioInternal: mPeerSd=" + mPeerSd);
        if (mPeerSd == null) {
            throw new IllegalStateException("mPeerSd = null");
        }

        stopCall(DONT_RELEASE_SOCKET);
        mInCall = true;

        // Run exact the same logic in createAnswer() to setup mAudioStream.
        SimpleSessionDescription offer =
                new SimpleSessionDescription(mPeerSd);
        AudioStream stream = mAudioStream;
        AudioCodec codec = null;
        for (Media media : offer.getMedia()) {
            if ((codec == null) && (media.getPort() > 0)
                    && "audio".equals(media.getType())
                    && "RTP/AVP".equals(media.getProtocol())) {
                // Find the first audio codec we supported.
                for (int type : media.getRtpPayloadTypes()) {
                    codec = AudioCodec.getCodec(
                            type, media.getRtpmap(type), media.getFmtp(type));
                    if (codec != null) {
                        break;
                    }
                }

                if (codec != null) {
                    // Associate with the remote host.
                    String address = media.getAddress();
                    if (address == null) {
                        address = offer.getAddress();
                    }
                    stream.associate(InetAddress.getByName(address),
                            media.getPort());

                    stream.setDtmfType(-1);
                    stream.setCodec(codec);
                    // Check if DTMF is supported in the same media.
                    for (int type : media.getRtpPayloadTypes()) {
                        String rtpmap = media.getRtpmap(type);
                        if ((type != codec.type) && (rtpmap != null)
                                && rtpmap.startsWith("telephone-event")) {
                            stream.setDtmfType(type);
                        }
                    }

                    // Handle recvonly and sendonly.
                    if (mHold) {
                        stream.setMode(RtpStream.MODE_NORMAL);
                    } else if (media.getAttribute("recvonly") != null) {
                        stream.setMode(RtpStream.MODE_SEND_ONLY);
                    } else if(media.getAttribute("sendonly") != null) {
                        stream.setMode(RtpStream.MODE_RECEIVE_ONLY);
                    } else if(offer.getAttribute("recvonly") != null) {
                        stream.setMode(RtpStream.MODE_SEND_ONLY);
                    } else if(offer.getAttribute("sendonly") != null) {
                        stream.setMode(RtpStream.MODE_RECEIVE_ONLY);
                    } else {
                        stream.setMode(RtpStream.MODE_NORMAL);
                    }
                    break;
                }
            }
        }
        if (codec == null) {
            throw new IllegalStateException("Reject SDP: no suitable codecs");
        }

        if (isWifiOn()) grabWifiHighPerfLock();

        // AudioGroup logic:
        AudioGroup audioGroup = getAudioGroup();
        if (mHold) {
            // don't create an AudioGroup here; doing so will fail if
            // there's another AudioGroup out there that's active
        } else {
            if (audioGroup == null) audioGroup = new AudioGroup();
            stream.join(audioGroup);
        }
        setAudioGroupMode();
    }

    // set audio group mode based on current audio configuration
    private void setAudioGroupMode() {
        AudioGroup audioGroup = getAudioGroup();
        if (DBG) log("setAudioGroupMode: audioGroup=" + audioGroup);
        if (audioGroup != null) {
            if (mHold) {
                audioGroup.setMode(AudioGroup.MODE_ON_HOLD);
            } else if (mMuted) {
                audioGroup.setMode(AudioGroup.MODE_MUTED);
            } else if (isSpeakerOn()) {
                audioGroup.setMode(AudioGroup.MODE_ECHO_SUPPRESSION);
            } else {
                audioGroup.setMode(AudioGroup.MODE_NORMAL);
            }
        }
    }

    private void stopCall(boolean releaseSocket) {
        if (DBG) log("stopCall: releaseSocket=" + releaseSocket);
        releaseWifiHighPerfLock();
        if (mAudioStream != null) {
            mAudioStream.join(null);

            if (releaseSocket) {
                mAudioStream.release();
                mAudioStream = null;
            }
        }
    }

    private String getLocalIp() {
        return mSipSession.getLocalIp();
    }

    private void throwSipException(Throwable throwable) throws SipException {
        if (throwable instanceof SipException) {
            throw (SipException) throwable;
        } else {
            throw new SipException("", throwable);
        }
    }

    private void log(String s) {
        Rlog.d(LOG_TAG, s);
    }

    private void loge(String s) {
        Rlog.e(LOG_TAG, s);
    }

    private void loge(String s, Throwable t) {
        Rlog.e(LOG_TAG, s, t);
    }
}
