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
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.net.rtp.AudioCodec;
import android.net.rtp.AudioGroup;
import android.net.rtp.AudioStream;
import android.net.rtp.RtpStream;
import android.net.sip.SimpleSessionDescription.Media;
import android.net.wifi.WifiManager;
import android.os.Message;
import android.os.RemoteException;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Class that handles an audio call over SIP.
 */
/** @hide */
public class SipAudioCallImpl extends SipSessionAdapter
        implements SipAudioCall {
    private static final String TAG = SipAudioCallImpl.class.getSimpleName();
    private static final boolean RELEASE_SOCKET = true;
    private static final boolean DONT_RELEASE_SOCKET = false;
    private static final int SESSION_TIMEOUT = 5; // in seconds

    private Context mContext;
    private SipProfile mLocalProfile;
    private SipAudioCall.Listener mListener;
    private ISipSession mSipSession;

    private long mSessionId = System.currentTimeMillis();
    private String mPeerSd;

    private AudioStream mAudioStream;
    private AudioGroup mAudioGroup;

    private boolean mInCall = false;
    private boolean mMuted = false;
    private boolean mHold = false;

    private boolean mRingbackToneEnabled = true;
    private boolean mRingtoneEnabled = true;
    private Ringtone mRingtone;
    private ToneGenerator mRingbackTone;

    private SipProfile mPendingCallRequest;

    private int mErrorCode = SipErrorCode.NO_ERROR;
    private String mErrorMessage;

    public SipAudioCallImpl(Context context, SipProfile localProfile) {
        mContext = context;
        mLocalProfile = localProfile;
    }

    public void setListener(SipAudioCall.Listener listener) {
        setListener(listener, false);
    }

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
                    case SipSessionState.READY_TO_CALL:
                        listener.onReadyToCall(this);
                        break;
                    case SipSessionState.INCOMING_CALL:
                        listener.onRinging(this, getPeerProfile(mSipSession));
                        break;
                    case SipSessionState.OUTGOING_CALL:
                        listener.onCalling(this);
                        break;
                    case SipSessionState.OUTGOING_CALL_RING_BACK:
                        listener.onRingingBack(this);
                        break;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "setListener()", t);
        }
    }

    public synchronized boolean isInCall() {
        return mInCall;
    }

    public synchronized boolean isOnHold() {
        return mHold;
    }

    public void close() {
        close(true);
    }

    private synchronized void close(boolean closeRtp) {
        if (closeRtp) stopCall(RELEASE_SOCKET);
        stopRingbackTone();
        stopRinging();

        mInCall = false;
        mHold = false;
        mSessionId = System.currentTimeMillis();
        mErrorCode = SipErrorCode.NO_ERROR;
        mErrorMessage = null;

        if (mSipSession != null) {
            try {
                mSipSession.setListener(null);
            } catch (RemoteException e) {
                // don't care
            }
            mSipSession = null;
        }
    }

    public synchronized SipProfile getLocalProfile() {
        return mLocalProfile;
    }

    public synchronized SipProfile getPeerProfile() {
        try {
            return (mSipSession == null) ? null : mSipSession.getPeerProfile();
        } catch (RemoteException e) {
            return null;
        }
    }

    public synchronized int getState() {
        if (mSipSession == null) return SipSessionState.READY_TO_CALL;
        try {
            return mSipSession.getState();
        } catch (RemoteException e) {
            return SipSessionState.REMOTE_ERROR;
        }
    }


    public synchronized ISipSession getSipSession() {
        return mSipSession;
    }

    @Override
    public void onCalling(ISipSession session) {
        Log.d(TAG, "calling... " + session);
        Listener listener = mListener;
        if (listener != null) {
            try {
                listener.onCalling(this);
            } catch (Throwable t) {
                Log.e(TAG, "onCalling()", t);
            }
        }
    }

    @Override
    public void onRingingBack(ISipSession session) {
        Log.d(TAG, "sip call ringing back: " + session);
        if (!mInCall) startRingbackTone();
        Listener listener = mListener;
        if (listener != null) {
            try {
                listener.onRingingBack(this);
            } catch (Throwable t) {
                Log.e(TAG, "onRingingBack()", t);
            }
        }
    }

    @Override
    public synchronized void onRinging(ISipSession session,
            SipProfile peerProfile, String sessionDescription) {
        try {
            if ((mSipSession == null) || !mInCall
                    || !session.getCallId().equals(mSipSession.getCallId())) {
                // should not happen
                session.endCall();
                return;
            }

            // session changing request
            try {
                String answer = createAnswer(sessionDescription).encode();
                mSipSession.answerCall(answer, SESSION_TIMEOUT);
            } catch (Throwable e) {
                Log.e(TAG, "onRinging()", e);
                session.endCall();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "onRinging()", e);
        }
    }

    @Override
    public void onCallEstablished(ISipSession session,
            String sessionDescription) {
        stopRingbackTone();
        stopRinging();
        mPeerSd = sessionDescription;
        Log.v(TAG, "onCallEstablished()" + mPeerSd);

        Listener listener = mListener;
        if (listener != null) {
            try {
                if (mHold) {
                    listener.onCallHeld(this);
                } else {
                    listener.onCallEstablished(this);
                }
            } catch (Throwable t) {
                Log.e(TAG, "onCallEstablished()", t);
            }
        }
    }

    @Override
    public void onCallEnded(ISipSession session) {
        Log.d(TAG, "sip call ended: " + session);
        Listener listener = mListener;
        if (listener != null) {
            try {
                listener.onCallEnded(this);
            } catch (Throwable t) {
                Log.e(TAG, "onCallEnded()", t);
            }
        }
        close();
    }

    @Override
    public void onCallBusy(ISipSession session) {
        Log.d(TAG, "sip call busy: " + session);
        Listener listener = mListener;
        if (listener != null) {
            try {
                listener.onCallBusy(this);
            } catch (Throwable t) {
                Log.e(TAG, "onCallBusy()", t);
            }
        }
        close(false);
    }

    @Override
    public void onCallChangeFailed(ISipSession session, int errorCode,
            String message) {
        Log.d(TAG, "sip call change failed: " + message);
        mErrorCode = errorCode;
        mErrorMessage = message;
        Listener listener = mListener;
        if (listener != null) {
            try {
                listener.onError(this, mErrorCode, message);
            } catch (Throwable t) {
                Log.e(TAG, "onCallBusy()", t);
            }
        }
    }

    @Override
    public void onError(ISipSession session, int errorCode, String message) {
        Log.d(TAG, "sip session error: " + SipErrorCode.toString(errorCode)
                + ": " + message);
        mErrorCode = errorCode;
        mErrorMessage = message;
        Listener listener = mListener;
        if (listener != null) {
            try {
                listener.onError(this, errorCode, message);
            } catch (Throwable t) {
                Log.e(TAG, "onError()", t);
            }
        }
        synchronized (this) {
            if ((errorCode == SipErrorCode.DATA_CONNECTION_LOST)
                    || !isInCall()) {
                close(true);
            }
        }
    }

    public synchronized void attachCall(ISipSession session,
            String sessionDescription) throws SipException {
        mSipSession = session;
        mPeerSd = sessionDescription;
        Log.v(TAG, "attachCall()" + mPeerSd);
        try {
            session.setListener(this);
            if (getState() == SipSessionState.INCOMING_CALL) startRinging();
        } catch (Throwable e) {
            Log.e(TAG, "attachCall()", e);
            throwSipException(e);
        }
    }

    public synchronized void makeCall(SipProfile peerProfile,
            SipManager sipManager, int timeout) throws SipException {
        try {
            mSipSession = sipManager.createSipSession(mLocalProfile, this);
            if (mSipSession == null) {
                throw new SipException(
                        "Failed to create SipSession; network available?");
            }
            mAudioStream = new AudioStream(InetAddress.getByName(getLocalIp()));
            mSipSession.makeCall(peerProfile, createOffer().encode(), timeout);
        } catch (Throwable e) {
            if (e instanceof SipException) {
                throw (SipException) e;
            } else {
                throwSipException(e);
            }
        }
    }

    public synchronized void endCall() throws SipException {
        try {
            stopRinging();
            stopCall(RELEASE_SOCKET);
            mInCall = false;

            // perform the above local ops first and then network op
            if (mSipSession != null) mSipSession.endCall();
        } catch (Throwable e) {
            throwSipException(e);
        }
    }

    public synchronized void answerCall(int timeout) throws SipException {
        try {
            stopRinging();
            mAudioStream = new AudioStream(InetAddress.getByName(getLocalIp()));
            mSipSession.answerCall(createAnswer(mPeerSd).encode(), timeout);
        } catch (Throwable e) {
            Log.e(TAG, "answerCall()", e);
            throwSipException(e);
        }
    }

    public synchronized void holdCall(int timeout) throws SipException {
        if (mHold) return;
        try {
            mSipSession.changeCall(createHoldOffer().encode(), timeout);
        } catch (Throwable e) {
            throwSipException(e);
        }
        mHold = true;
        AudioGroup audioGroup = getAudioGroup();
        if (audioGroup != null) audioGroup.setMode(AudioGroup.MODE_ON_HOLD);
    }

    public synchronized void continueCall(int timeout) throws SipException {
        if (!mHold) return;
        try {
            mSipSession.changeCall(createContinueOffer().encode(), timeout);
        } catch (Throwable e) {
            throwSipException(e);
        }
        mHold = false;
        AudioGroup audioGroup = getAudioGroup();
        if (audioGroup != null) audioGroup.setMode(AudioGroup.MODE_NORMAL);
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
        return offer;
    }

    private SimpleSessionDescription createAnswer(String offerSd) {
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
            throw new IllegalStateException("Reject SDP: no suitable codecs");
        }
        return answer;
    }

    private SimpleSessionDescription createHoldOffer() {
        SimpleSessionDescription offer = createContinueOffer();
        offer.setAttribute("sendonly", "");
        return offer;
    }

    private SimpleSessionDescription createContinueOffer() {
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

    public synchronized void toggleMute() {
        AudioGroup audioGroup = getAudioGroup();
        if (audioGroup != null) {
            audioGroup.setMode(
                    mMuted ? AudioGroup.MODE_NORMAL : AudioGroup.MODE_MUTED);
            mMuted = !mMuted;
        }
    }

    public synchronized boolean isMuted() {
        return mMuted;
    }

    public synchronized void setSpeakerMode(boolean speakerMode) {
        ((AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE))
                .setSpeakerphoneOn(speakerMode);
    }

    public void sendDtmf(int code) {
        sendDtmf(code, null);
    }

    public synchronized void sendDtmf(int code, Message result) {
        AudioGroup audioGroup = getAudioGroup();
        if ((audioGroup != null) && (mSipSession != null)
                && (SipSessionState.IN_CALL == getState())) {
            Log.v(TAG, "send DTMF: " + code);
            audioGroup.sendDtmf(code);
        }
        if (result != null) result.sendToTarget();
    }

    public synchronized AudioStream getAudioStream() {
        return mAudioStream;
    }

    public synchronized AudioGroup getAudioGroup() {
        if (mAudioGroup != null) return mAudioGroup;
        return ((mAudioStream == null) ? null : mAudioStream.getGroup());
    }

    public synchronized void setAudioGroup(AudioGroup group) {
        if ((mAudioStream != null) && (mAudioStream.getGroup() != null)) {
            mAudioStream.join(group);
        }
        mAudioGroup = group;
    }

    public void startAudio() {
        try {
            startAudioInternal();
        } catch (UnknownHostException e) {
            onError(mSipSession, SipErrorCode.PEER_NOT_REACHABLE,
                    e.getMessage());
        } catch (Throwable e) {
            onError(mSipSession, SipErrorCode.CLIENT_ERROR,
                    e.getMessage());
        }
    }

    private synchronized void startAudioInternal() throws UnknownHostException {
        if (mPeerSd == null) {
            Log.v(TAG, "startAudioInternal() mPeerSd = null");
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

        if (!mHold) {
            /* The recorder volume will be very low if the device is in
             * IN_CALL mode. Therefore, we have to set the mode to NORMAL
             * in order to have the normal microphone level.
             */
            ((AudioManager) mContext.getSystemService
                    (Context.AUDIO_SERVICE))
                    .setMode(AudioManager.MODE_NORMAL);
        }

        // AudioGroup logic:
        AudioGroup audioGroup = getAudioGroup();
        if (mHold) {
            if (audioGroup != null) {
                audioGroup.setMode(AudioGroup.MODE_ON_HOLD);
            }
            // don't create an AudioGroup here; doing so will fail if
            // there's another AudioGroup out there that's active
        } else {
            if (audioGroup == null) audioGroup = new AudioGroup();
            mAudioStream.join(audioGroup);
            if (mMuted) {
                audioGroup.setMode(AudioGroup.MODE_MUTED);
            } else {
                audioGroup.setMode(AudioGroup.MODE_NORMAL);
            }
        }
    }

    private void stopCall(boolean releaseSocket) {
        Log.d(TAG, "stop audiocall");
        if (mAudioStream != null) {
            mAudioStream.join(null);

            if (releaseSocket) {
                mAudioStream.release();
                mAudioStream = null;
            }
        }
    }

    private String getLocalIp() {
        try {
            return mSipSession.getLocalIp();
        } catch (RemoteException e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized void setRingbackToneEnabled(boolean enabled) {
        mRingbackToneEnabled = enabled;
    }

    public synchronized void setRingtoneEnabled(boolean enabled) {
        mRingtoneEnabled = enabled;
    }

    private void startRingbackTone() {
        if (!mRingbackToneEnabled) return;
        if (mRingbackTone == null) {
            // The volume relative to other sounds in the stream
            int toneVolume = 80;
            mRingbackTone = new ToneGenerator(
                    AudioManager.STREAM_VOICE_CALL, toneVolume);
        }
        mRingbackTone.startTone(ToneGenerator.TONE_CDMA_LOW_PBX_L);
    }

    private void stopRingbackTone() {
        if (mRingbackTone != null) {
            mRingbackTone.stopTone();
            mRingbackTone.release();
            mRingbackTone = null;
        }
    }

    private void startRinging() {
        if (!mRingtoneEnabled) return;
        ((Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE))
                .vibrate(new long[] {0, 1000, 1000}, 1);
        AudioManager am = (AudioManager)
                mContext.getSystemService(Context.AUDIO_SERVICE);
        if (am.getStreamVolume(AudioManager.STREAM_RING) > 0) {
            String ringtoneUri =
                    Settings.System.DEFAULT_RINGTONE_URI.toString();
            mRingtone = RingtoneManager.getRingtone(mContext,
                    Uri.parse(ringtoneUri));
            mRingtone.play();
        }
    }

    private void stopRinging() {
        ((Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE))
                .cancel();
        if (mRingtone != null) mRingtone.stop();
    }

    private void throwSipException(Throwable throwable) throws SipException {
        if (throwable instanceof SipException) {
            throw (SipException) throwable;
        } else {
            throw new SipException("", throwable);
        }
    }

    private SipProfile getPeerProfile(ISipSession session) {
        try {
            return session.getPeerProfile();
        } catch (RemoteException e) {
            return null;
        }
    }
}
