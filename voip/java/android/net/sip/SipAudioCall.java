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

import android.net.rtp.AudioGroup;
import android.net.rtp.AudioStream;
import android.os.Message;

import javax.sip.SipException;

/**
 * Interface for making audio calls over SIP.
 * @hide
 */
public interface SipAudioCall {
    /** Listener class for all event callbacks. */
    public interface Listener {
        /**
         * Called when the call object is ready to make another call.
         *
         * @param call the call object that is ready to make another call
         */
        void onReadyToCall(SipAudioCall call);

        /**
         * Called when a request is sent out to initiate a new call.
         *
         * @param call the call object that carries out the audio call
         */
        void onCalling(SipAudioCall call);

        /**
         * Called when a new call comes in.
         *
         * @param call the call object that carries out the audio call
         * @param caller the SIP profile of the caller
         */
        void onRinging(SipAudioCall call, SipProfile caller);

        /**
         * Called when a RINGING response is received for the INVITE request sent
         *
         * @param call the call object that carries out the audio call
         */
        void onRingingBack(SipAudioCall call);

        /**
         * Called when the session is established.
         *
         * @param call the call object that carries out the audio call
         */
        void onCallEstablished(SipAudioCall call);

        /**
         * Called when the session is terminated.
         *
         * @param call the call object that carries out the audio call
         */
        void onCallEnded(SipAudioCall call);

        /**
         * Called when the peer is busy during session initialization.
         *
         * @param call the call object that carries out the audio call
         */
        void onCallBusy(SipAudioCall call);

        /**
         * Called when the call is on hold.
         *
         * @param call the call object that carries out the audio call
         */
        void onCallHeld(SipAudioCall call);

        /**
         * Called when an error occurs.
         *
         * @param call the call object that carries out the audio call
         * @param errorMessage error message
         */
        void onError(SipAudioCall call, String errorMessage);
    }

    /**
     * The adapter class for {@link SipAudioCall#Listener}. The default
     * implementation of all callback methods is no-op.
     */
    public class Adapter implements Listener {
        protected void onChanged(SipAudioCall call) {
        }
        public void onReadyToCall(SipAudioCall call) {
            onChanged(call);
        }
        public void onCalling(SipAudioCall call) {
            onChanged(call);
        }
        public void onRinging(SipAudioCall call, SipProfile caller) {
            onChanged(call);
        }
        public void onRingingBack(SipAudioCall call) {
            onChanged(call);
        }
        public void onCallEstablished(SipAudioCall call) {
            onChanged(call);
        }
        public void onCallEnded(SipAudioCall call) {
            onChanged(call);
        }
        public void onCallBusy(SipAudioCall call) {
            onChanged(call);
        }
        public void onCallHeld(SipAudioCall call) {
            onChanged(call);
        }
        public void onError(SipAudioCall call, String errorMessage) {
            onChanged(call);
        }
    }

    /**
     * Sets the listener to listen to the audio call events. The method calls
     * {@link #setListener(Listener, false)}.
     *
     * @param listener to listen to the audio call events of this object
     * @see #setListener(Listener, boolean)
     */
    void setListener(Listener listener);

    /**
     * Sets the listener to listen to the audio call events. A
     * {@link SipAudioCall} can only hold one listener at a time. Subsequent
     * calls to this method override the previous listener.
     *
     * @param listener to listen to the audio call events of this object
     * @param callbackImmediately set to true if the caller wants to be called
     *      back immediately on the current state
     */
    void setListener(Listener listener, boolean callbackImmediately);

    /**
     * Closes this object. The object is not usable after being closed.
     */
    void close();

    /**
     * Initiates an audio call to the specified profile.
     *
     * @param callee the SIP profile to make the call to
     * @param sipManager the {@link SipManager} object to help make call with
     */
    void makeCall(SipProfile callee, SipManager sipManager) throws SipException;

    /**
     * Attaches an incoming call to this call object.
     *
     * @param session the session that receives the incoming call
     * @param sdp the session description of the incoming call
     */
    void attachCall(ISipSession session, SdpSessionDescription sdp)
            throws SipException;

    /** Ends a call. */
    void endCall() throws SipException;

    /**
     * Puts a call on hold.  When succeeds,
     * {@link #Listener#onCallHeld(SipAudioCall)} is called.
     */
    void holdCall() throws SipException;

    /** Answers a call. */
    void answerCall() throws SipException;

    /**
     * Continues a call that's on hold. When succeeds,
     * {@link #Listener#onCallEstablished(SipAudioCall)} is called.
     */
    void continueCall() throws SipException;

    /** Puts the device to speaker mode. */
    void setSpeakerMode(boolean speakerMode);

    /** Toggles mute. */
    void toggleMute();

    /**
     * Checks if the call is on hold.
     *
     * @return true if the call is on hold
     */
    boolean isOnHold();

    /**
     * Checks if the call is muted.
     *
     * @return true if the call is muted
     */
    boolean isMuted();

    /**
     * Sends a DTMF code.
     *
     * @param code the DTMF code to send
     */
    void sendDtmf(int code);

    /**
     * Sends a DTMF code.
     *
     * @param code the DTMF code to send
     * @param result the result message to send when done
     */
    void sendDtmf(int code, Message result);

    /**
     * Gets the {@link AudioStream} object used in this call. The object
     * represents the RTP stream that carries the audio data to and from the
     * peer. The object may not be created before the call is established. And
     * it is undefined after the call ends or the {@link #close} method is
     * called.
     *
     * @return the {@link AudioStream} object or null if the RTP stream has not
     *      yet been set up
     */
    AudioStream getAudioStream();

    /**
     * Gets the {@link AudioGroup} object which the {@link AudioStream} object
     * joins. The group object may not exist before the call is established.
     * Also, the {@code AudioStream} may change its group during a call (e.g.,
     * after the call is held/un-held). Finally, the {@code AudioGroup} object
     * returned by this method is undefined after the call ends or the
     * {@link #close} method is called.
     *
     * @return the {@link AudioGroup} object or null if the RTP stream has not
     *      yet been set up
     * @see #getAudioStream
     */
    AudioGroup getAudioGroup();

    /**
     * Checks if the call is established.
     *
     * @return true if the call is established
     */
    boolean isInCall();

    /**
     * Gets the local SIP profile.
     *
     * @return the local SIP profile
     */
    SipProfile getLocalProfile();

    /**
     * Gets the peer's SIP profile.
     *
     * @return the peer's SIP profile
     */
    SipProfile getPeerProfile();

    /**
     * Gets the state of the {@link ISipSession} that carries this call.
     *
     * @return the session state
     */
    SipSessionState getState();

    /**
     * Gets the {@link ISipSession} that carries this call.
     *
     * @return the session object that carries this call
     */
    ISipSession getSipSession();

    /**
     * Enables/disables the ring-back tone.
     *
     * @param enabled true to enable; false to disable
     */
    void setRingbackToneEnabled(boolean enabled);

    /**
     * Enables/disables the ring tone.
     *
     * @param enabled true to enable; false to disable
     */
    void setRingtoneEnabled(boolean enabled);
}
