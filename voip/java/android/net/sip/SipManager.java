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
import android.content.Intent;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;

import java.text.ParseException;
import javax.sip.SipException;

/**
 * The class provides API for various SIP related tasks. Specifically, the API
 * allows the application to:
 * <ul>
 * <li>register a {@link SipProfile} to have the background SIP service listen
 *      to incoming calls and broadcast them with registered command string. See
 *      {@link #open(SipProfile, String, SipRegistrationListener)},
 *      {@link #open(SipProfile)}, {@link #close(String)},
 *      {@link #isOpened(String)} and {@link isRegistered(String)}. It also
 *      facilitates handling of the incoming call broadcast intent. See
 *      {@link #isIncomingCallIntent(Intent)}, {@link #getCallId(Intent)},
 *      {@link #getOfferSessionDescription(Intent)} and
 *      {@link #takeAudioCall(Context, Intent, SipAudioCall.Listener)}.</li>
 * <li>make/take SIP-based audio calls. See
 *      {@link #makeAudioCall(Context, SipProfile, SipProfile, SipAudioCall.Listener)}
 *      and {@link #takeAudioCall(Context, Intent, SipAudioCall.Listener}.</li>
 * <li>register/unregister with a SIP service provider. See
 *      {@link #register(SipProfile, int, ISipSessionListener)} and
 *      {@link #unregister(SipProfile, ISipSessionListener)}.</li>
 * <li>process SIP events directly with a {@link ISipSession} created by
 *      {@link createSipSession(SipProfile, ISipSessionListener)}.</li>
 * </ul>
 * @hide
 */
public class SipManager {
    /** @hide */
    public static final String SIP_INCOMING_CALL_ACTION =
            "com.android.phone.SIP_INCOMING_CALL";
    /** @hide */
    public static final String SIP_ADD_PHONE_ACTION =
            "com.android.phone.SIP_ADD_PHONE";
    /** @hide */
    public static final String SIP_REMOVE_PHONE_ACTION =
            "com.android.phone.SIP_REMOVE_PHONE";
    /** @hide */
    public static final String LOCAL_URI_KEY = "LOCAL SIPURI";

    private static final String CALL_ID_KEY = "CallID";
    private static final String OFFER_SD_KEY = "OfferSD";

    private ISipService mSipService;

    // Will be removed once the SIP service is integrated into framework
    private BinderHelper<ISipService> mBinderHelper;

    /**
     * Creates a manager instance and initializes the background SIP service.
     * Will be removed once the SIP service is integrated into framework.
     *
     * @param context context to start the SIP service
     * @return the manager instance
     */
    public static SipManager getInstance(final Context context) {
        final SipManager manager = new SipManager();
        manager.createSipService(context);
        return manager;
    }

    private SipManager() {
    }

    private void createSipService(Context context) {
        if (mSipService != null) return;
        // TODO: change back to Context.SIP_SERVICE later.
        IBinder b = ServiceManager.getService("sip");
        mSipService = ISipService.Stub.asInterface(b);
    }

    /**
     * Opens the profile for making calls and/or receiving calls. Subsequent
     * SIP calls can be made through the default phone UI. The caller may also
     * make subsequent calls through
     * {@link #makeAudioCall(Context, String, String, SipAudioCall.Listener)}.
     * If the receiving-call option is enabled in the profile, the SIP service
     * will register the profile to the corresponding server periodically in
     * order to receive calls from the server.
     *
     * @param localProfile the SIP profile to make calls from
     * @throws SipException if the profile contains incorrect settings or
     *      calling the SIP service results in an error
     */
    public void open(SipProfile localProfile) throws SipException {
        try {
            mSipService.open(localProfile);
        } catch (RemoteException e) {
            throw new SipException("open()", e);
        }
    }

    /**
     * Opens the profile for making calls and/or receiving calls. Subsequent
     * SIP calls can be made through the default phone UI. The caller may also
     * make subsequent calls through
     * {@link #makeAudioCall(Context, String, String, SipAudioCall.Listener)}.
     * If the receiving-call option is enabled in the profile, the SIP service
     * will register the profile to the corresponding server periodically in
     * order to receive calls from the server.
     *
     * @param localProfile the SIP profile to receive incoming calls for
     * @param incomingCallBroadcastAction the action to be broadcast when an
     *      incoming call is received
     * @param listener to listen to registration events; can be null
     * @throws SipException if the profile contains incorrect settings or
     *      calling the SIP service results in an error
     */
    public void open(SipProfile localProfile,
            String incomingCallBroadcastAction,
            SipRegistrationListener listener) throws SipException {
        try {
            mSipService.open3(localProfile, incomingCallBroadcastAction,
                    createRelay(listener));
        } catch (RemoteException e) {
            throw new SipException("open()", e);
        }
    }

    /**
     * Sets the listener to listen to registration events. No effect if the
     * profile has not been opened to receive calls
     * (see {@link #open(SipProfile, String, SipRegistrationListener)} and
     * {@link #open(SipProfile)}).
     *
     * @param localProfileUri the URI of the profile
     * @param listener to listen to registration events; can be null
     * @throws SipException if calling the SIP service results in an error
     */
    public void setRegistrationListener(String localProfileUri,
            SipRegistrationListener listener) throws SipException {
        try {
            mSipService.setRegistrationListener(
                    localProfileUri, createRelay(listener));
        } catch (RemoteException e) {
            throw new SipException("setRegistrationListener()", e);
        }
    }

    /**
     * Closes the specified profile to not make/receive calls. All the resources
     * that were allocated to the profile are also released.
     *
     * @param localProfileUri the URI of the profile to close
     * @throws SipException if calling the SIP service results in an error
     */
    public void close(String localProfileUri) throws SipException {
        try {
            mSipService.close(localProfileUri);
        } catch (RemoteException e) {
            throw new SipException("close()", e);
        }
    }

    /**
     * Checks if the specified profile is enabled to receive calls.
     *
     * @param localProfileUri the URI of the profile in question
     * @return true if the profile is enabled to receive calls
     * @throws SipException if calling the SIP service results in an error
     */
    public boolean isOpened(String localProfileUri) throws SipException {
        try {
            return mSipService.isOpened(localProfileUri);
        } catch (RemoteException e) {
            throw new SipException("isOpened()", e);
        }
    }

    /**
     * Checks if the specified profile is registered to the server for
     * receiving calls.
     *
     * @param localProfileUri the URI of the profile in question
     * @return true if the profile is registered to the server
     * @throws SipException if calling the SIP service results in an error
     */
    public boolean isRegistered(String localProfileUri) throws SipException {
        try {
            return mSipService.isRegistered(localProfileUri);
        } catch (RemoteException e) {
            throw new SipException("isRegistered()", e);
        }
    }

    /**
     * Creates a {@link SipAudioCall} to make a call.
     *
     * @param context context to create a {@link SipAudioCall} object
     * @param localProfile the SIP profile to make the call from
     * @param peerProfile the SIP profile to make the call to
     * @param listener to listen to the call events from {@link SipAudioCall};
     *      can be null
     * @return a {@link SipAudioCall} object
     * @throws SipException if calling the SIP service results in an error
     */
    public SipAudioCall makeAudioCall(Context context, SipProfile localProfile,
            SipProfile peerProfile, SipAudioCall.Listener listener)
            throws SipException {
        SipAudioCall call = new SipAudioCallImpl(context, localProfile);
        call.setListener(listener);
        call.makeCall(peerProfile, this);
        return call;
    }

    /**
     * Creates a {@link SipAudioCall} to make a call. To use this method, one
     * must call {@link #open(SipProfile)} first.
     *
     * @param context context to create a {@link SipAudioCall} object
     * @param localProfileUri URI of the SIP profile to make the call from
     * @param peerProfileUri URI of the SIP profile to make the call to
     * @param listener to listen to the call events from {@link SipAudioCall};
     *      can be null
     * @return a {@link SipAudioCall} object
     * @throws SipException if calling the SIP service results in an error
     */
    public SipAudioCall makeAudioCall(Context context, String localProfileUri,
            String peerProfileUri, SipAudioCall.Listener listener)
            throws SipException {
        try {
            return makeAudioCall(context,
                    new SipProfile.Builder(localProfileUri).build(),
                    new SipProfile.Builder(peerProfileUri).build(), listener);
        } catch (ParseException e) {
            throw new SipException("build SipProfile", e);
        }
    }

    /**
     * The method calls {@code takeAudioCall(context, incomingCallIntent,
     * listener, true}.
     *
     * @see #takeAudioCall(Context, Intent, SipAudioCall.Listener, boolean)
     */
    public SipAudioCall takeAudioCall(Context context,
            Intent incomingCallIntent, SipAudioCall.Listener listener)
            throws SipException {
        return takeAudioCall(context, incomingCallIntent, listener, true);
    }

    /**
     * Creates a {@link SipAudioCall} to take an incoming call. Before the call
     * is returned, the listener will receive a
     * {@link SipAudioCall#Listener.onRinging(SipAudioCall, SipProfile)}
     * callback.
     *
     * @param context context to create a {@link SipAudioCall} object
     * @param incomingCallIntent the incoming call broadcast intent
     * @param listener to listen to the call events from {@link SipAudioCall};
     *      can be null
     * @return a {@link SipAudioCall} object
     * @throws SipException if calling the SIP service results in an error
     */
    public SipAudioCall takeAudioCall(Context context,
            Intent incomingCallIntent, SipAudioCall.Listener listener,
            boolean ringtoneEnabled) throws SipException {
        if (incomingCallIntent == null) return null;

        String callId = getCallId(incomingCallIntent);
        if (callId == null) {
            throw new SipException("Call ID missing in incoming call intent");
        }

        byte[] offerSd = getOfferSessionDescription(incomingCallIntent);
        if (offerSd == null) {
            throw new SipException("Session description missing in incoming "
                    + "call intent");
        }

        try {
            SdpSessionDescription sdp = new SdpSessionDescription(offerSd);

            ISipSession session = mSipService.getPendingSession(callId);
            if (session == null) return null;
            SipAudioCall call = new SipAudioCallImpl(
                    context, session.getLocalProfile());
            call.setRingtoneEnabled(ringtoneEnabled);
            call.attachCall(session, sdp);
            call.setListener(listener);
            return call;
        } catch (Throwable t) {
            throw new SipException("takeAudioCall()", t);
        }
    }

    /**
     * Checks if the intent is an incoming call broadcast intent.
     *
     * @param intent the intent in question
     * @return true if the intent is an incoming call broadcast intent
     */
    public static boolean isIncomingCallIntent(Intent intent) {
        if (intent == null) return false;
        String callId = getCallId(intent);
        byte[] offerSd = getOfferSessionDescription(intent);
        return ((callId != null) && (offerSd != null));
    }

    /**
     * Gets the call ID from the specified incoming call broadcast intent.
     *
     * @param incomingCallIntent the incoming call broadcast intent
     * @return the call ID or null if the intent does not contain it
     */
    public static String getCallId(Intent incomingCallIntent) {
        return incomingCallIntent.getStringExtra(CALL_ID_KEY);
    }

    /**
     * Gets the offer session description from the specified incoming call
     * broadcast intent.
     *
     * @param incomingCallIntent the incoming call broadcast intent
     * @return the offer session description or null if the intent does not
     *      have it
     */
    public static byte[] getOfferSessionDescription(Intent incomingCallIntent) {
        return incomingCallIntent.getByteArrayExtra(OFFER_SD_KEY);
    }

    /**
     * Creates an incoming call broadcast intent.
     *
     * @param action the action string to broadcast
     * @param callId the call ID of the incoming call
     * @param sessionDescription the session description of the incoming call
     * @return the incoming call intent
     * @hide
     */
    public static Intent createIncomingCallBroadcast(String action,
            String callId, byte[] sessionDescription) {
        Intent intent = new Intent(action);
        intent.putExtra(CALL_ID_KEY, callId);
        intent.putExtra(OFFER_SD_KEY, sessionDescription);
        return intent;
    }

    /**
     * Registers the profile to the corresponding server for receiving calls.
     * {@link #open(SipProfile, String, SipRegistrationListener)} is still
     * needed to be called at least once in order for the SIP service to
     * broadcast an intent when an incoming call is received.
     *
     * @param localProfile the SIP profile to register with
     * @param expiryTime registration expiration time (in second)
     * @param listener to listen to the registration events
     * @throws SipException if calling the SIP service results in an error
     */
    public void register(SipProfile localProfile, int expiryTime,
            SipRegistrationListener listener) throws SipException {
        try {
            ISipSession session = mSipService.createSession(
                    localProfile, createRelay(listener));
            session.register(expiryTime);
        } catch (RemoteException e) {
            throw new SipException("register()", e);
        }
    }

    /**
     * Unregisters the profile from the corresponding server for not receiving
     * further calls.
     *
     * @param localProfile the SIP profile to register with
     * @param listener to listen to the registration events
     * @throws SipException if calling the SIP service results in an error
     */
    public void unregister(SipProfile localProfile,
            SipRegistrationListener listener) throws SipException {
        try {
            ISipSession session = mSipService.createSession(
                    localProfile, createRelay(listener));
            session.unregister();
        } catch (RemoteException e) {
            throw new SipException("unregister()", e);
        }
    }

    /**
     * Gets the {@link ISipSession} that handles the incoming call. For audio
     * calls, consider to use {@link SipAudioCall} to handle the incoming call.
     * See {@link #takeAudioCall(Context, Intent, SipAudioCall.Listener)}.
     * Note that the method may be called only once for the same intent. For
     * subsequent calls on the same intent, the method returns null.
     *
     * @param incomingCallIntent the incoming call broadcast intent
     * @return the session object that handles the incoming call
     */
    public ISipSession getSessionFor(Intent incomingCallIntent)
            throws SipException {
        try {
            String callId = getCallId(incomingCallIntent);
            return mSipService.getPendingSession(callId);
        } catch (RemoteException e) {
            throw new SipException("getSessionFor()", e);
        }
    }

    private static ISipSessionListener createRelay(
            SipRegistrationListener listener) {
        return ((listener == null) ? null : new ListenerRelay(listener));
    }

    /**
     * Creates a {@link ISipSession} with the specified profile. Use other
     * methods, if applicable, instead of interacting with {@link ISipSession}
     * directly.
     *
     * @param localProfile the SIP profile the session is associated with
     * @param listener to listen to SIP session events
     */
    public ISipSession createSipSession(SipProfile localProfile,
            ISipSessionListener listener) throws SipException {
        try {
            return mSipService.createSession(localProfile, listener);
        } catch (RemoteException e) {
            throw new SipException("createSipSession()", e);
        }
    }

    /**
     * Gets the list of profiles hosted by the SIP service. The user information
     * (username, password and display name) are crossed out.
     * @hide
     */
    public SipProfile[] getListOfProfiles() {
        try {
            return mSipService.getListOfProfiles();
        } catch (RemoteException e) {
            return null;
        }
    }

    private static class ListenerRelay extends SipSessionAdapter {
        private SipRegistrationListener mListener;

        // listener must not be null
        public ListenerRelay(SipRegistrationListener listener) {
            mListener = listener;
        }

        private String getUri(ISipSession session) {
            try {
                return session.getLocalProfile().getUriString();
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void onRegistering(ISipSession session) {
            mListener.onRegistering(getUri(session));
        }

        @Override
        public void onRegistrationDone(ISipSession session, int duration) {
            long expiryTime = duration;
            if (duration > 0) expiryTime += System.currentTimeMillis();
            mListener.onRegistrationDone(getUri(session), expiryTime);
        }

        @Override
        public void onRegistrationFailed(ISipSession session, String className,
                String message) {
            mListener.onRegistrationFailed(getUri(session), className, message);
        }

        @Override
        public void onRegistrationTimeout(ISipSession session) {
            mListener.onRegistrationFailed(getUri(session),
                    SipException.class.getName(), "registration timed out");
        }
    }
}
