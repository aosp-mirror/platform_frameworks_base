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

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.text.ParseException;

/**
 * Provides APIs for SIP tasks, such as initiating SIP connections, and provides access to related
 * SIP services. This class is the starting point for any SIP actions. You can acquire an instance
 * of it with {@link #newInstance newInstance()}.</p>
 * <p>The APIs in this class allows you to:</p>
 * <ul>
 * <li>Create a {@link SipSession} to get ready for making calls or listen for incoming calls. See
 * {@link #createSipSession createSipSession()} and {@link #getSessionFor getSessionFor()}.</li>
 * <li>Initiate and receive generic SIP calls or audio-only SIP calls. Generic SIP calls may
 * be video, audio, or other, and are initiated with {@link #open open()}. Audio-only SIP calls
 * should be handled with a {@link SipAudioCall}, which you can acquire with {@link
 * #makeAudioCall makeAudioCall()} and {@link #takeAudioCall takeAudioCall()}.</li>
 * <li>Register and unregister with a SIP service provider, with
 *      {@link #register register()} and {@link #unregister unregister()}.</li>
 * <li>Verify session connectivity, with {@link #isOpened isOpened()} and
 *      {@link #isRegistered isRegistered()}.</li>
 * </ul>
 * <p class="note"><strong>Note:</strong> Not all Android-powered devices support VOIP calls using
 * SIP. You should always call {@link android.net.sip.SipManager#isVoipSupported
 * isVoipSupported()} to verify that the device supports VOIP calling and {@link
 * android.net.sip.SipManager#isApiSupported isApiSupported()} to verify that the device supports
 * the SIP APIs.<br/><br/>Your application must also request the {@link
 * android.Manifest.permission#INTERNET} and {@link android.Manifest.permission#USE_SIP}
 * permissions.</p>
 */
public class SipManager {
    /**
     * The result code to be sent back with the incoming call
     * {@link PendingIntent}.
     * @see #open(SipProfile, PendingIntent, SipRegistrationListener)
     */
    public static final int INCOMING_CALL_RESULT_CODE = 101;

    /**
     * Key to retrieve the call ID from an incoming call intent.
     * @see #open(SipProfile, PendingIntent, SipRegistrationListener)
     */
    public static final String EXTRA_CALL_ID = "android:sipCallID";

    /**
     * Key to retrieve the offered session description from an incoming call
     * intent.
     * @see #open(SipProfile, PendingIntent, SipRegistrationListener)
     */
    public static final String EXTRA_OFFER_SD = "android:sipOfferSD";

    /**
     * Action to broadcast when SipService is up.
     * Internal use only.
     * @hide
     */
    public static final String ACTION_SIP_SERVICE_UP =
            "android.net.sip.SIP_SERVICE_UP";
    /**
     * Action string for the incoming call intent for the Phone app.
     * Internal use only.
     * @hide
     */
    public static final String ACTION_SIP_INCOMING_CALL =
            "com.android.phone.SIP_INCOMING_CALL";
    /**
     * Action string for the add-phone intent.
     * Internal use only.
     * @hide
     */
    public static final String ACTION_SIP_ADD_PHONE =
            "com.android.phone.SIP_ADD_PHONE";
    /**
     * Action string for the remove-phone intent.
     * Internal use only.
     * @hide
     */
    public static final String ACTION_SIP_REMOVE_PHONE =
            "com.android.phone.SIP_REMOVE_PHONE";
    /**
     * Part of the ACTION_SIP_ADD_PHONE and ACTION_SIP_REMOVE_PHONE intents.
     * Internal use only.
     * @hide
     */
    public static final String EXTRA_LOCAL_URI = "android:localSipUri";

    private static final String TAG = "SipManager";

    private ISipService mSipService;
    private Context mContext;

    /**
     * Creates a manager instance. Returns null if SIP API is not supported.
     *
     * @param context application context for creating the manager object
     * @return the manager instance or null if SIP API is not supported
     */
    public static SipManager newInstance(Context context) {
        return (isApiSupported(context) ? new SipManager(context) : null);
    }

    /**
     * Returns true if the SIP API is supported by the system.
     */
    public static boolean isApiSupported(Context context) {
        return context.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_SIP);
    }

    /**
     * Returns true if the system supports SIP-based VOIP API.
     */
    public static boolean isVoipSupported(Context context) {
        return context.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_SIP_VOIP) && isApiSupported(context);
    }

    /**
     * Returns true if SIP is only available on WIFI.
     */
    public static boolean isSipWifiOnly(Context context) {
        return context.getResources().getBoolean(
                com.android.internal.R.bool.config_sip_wifi_only);
    }

    private SipManager(Context context) {
        mContext = context;
        createSipService();
    }

    private void createSipService() {
        IBinder b = ServiceManager.getService(Context.SIP_SERVICE);
        mSipService = ISipService.Stub.asInterface(b);
    }

    /**
     * Opens the profile for making generic SIP calls. The caller may make subsequent calls
     * through {@link #makeAudioCall}. If one also wants to receive calls on the
     * profile, use
     * {@link #open(SipProfile, PendingIntent, SipRegistrationListener)}
     * instead.
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
     * Opens the profile for making calls and/or receiving generic SIP calls. The caller may
     * make subsequent calls through {@link #makeAudioCall}. If the
     * auto-registration option is enabled in the profile, the SIP service
     * will register the profile to the corresponding SIP provider periodically
     * in order to receive calls from the provider. When the SIP service
     * receives a new call, it will send out an intent with the provided action
     * string. The intent contains a call ID extra and an offer session
     * description string extra. Use {@link #getCallId} and
     * {@link #getOfferSessionDescription} to retrieve those extras.
     *
     * @param localProfile the SIP profile to receive incoming calls for
     * @param incomingCallPendingIntent When an incoming call is received, the
     *      SIP service will call
     *      {@link PendingIntent#send(Context, int, Intent)} to send back the
     *      intent to the caller with {@link #INCOMING_CALL_RESULT_CODE} as the
     *      result code and the intent to fill in the call ID and session
     *      description information. It cannot be null.
     * @param listener to listen to registration events; can be null
     * @see #getCallId
     * @see #getOfferSessionDescription
     * @see #takeAudioCall
     * @throws NullPointerException if {@code incomingCallPendingIntent} is null
     * @throws SipException if the profile contains incorrect settings or
     *      calling the SIP service results in an error
     * @see #isIncomingCallIntent
     * @see #getCallId
     * @see #getOfferSessionDescription
     */
    public void open(SipProfile localProfile,
            PendingIntent incomingCallPendingIntent,
            SipRegistrationListener listener) throws SipException {
        if (incomingCallPendingIntent == null) {
            throw new NullPointerException(
                    "incomingCallPendingIntent cannot be null");
        }
        try {
            mSipService.open3(localProfile, incomingCallPendingIntent,
                    createRelay(listener, localProfile.getUriString()));
        } catch (RemoteException e) {
            throw new SipException("open()", e);
        }
    }

    /**
     * Sets the listener to listen to registration events. No effect if the
     * profile has not been opened to receive calls (see
     * {@link #open(SipProfile, PendingIntent, SipRegistrationListener)}).
     *
     * @param localProfileUri the URI of the profile
     * @param listener to listen to registration events; can be null
     * @throws SipException if calling the SIP service results in an error
     */
    public void setRegistrationListener(String localProfileUri,
            SipRegistrationListener listener) throws SipException {
        try {
            mSipService.setRegistrationListener(
                    localProfileUri, createRelay(listener, localProfileUri));
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
     * Checks if the specified profile is opened in the SIP service for
     * making and/or receiving calls.
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
     * Checks if the SIP service has successfully registered the profile to the
     * SIP provider (specified in the profile) for receiving calls. Returning
     * true from this method also implies the profile is opened
     * ({@link #isOpened}).
     *
     * @param localProfileUri the URI of the profile in question
     * @return true if the profile is registered to the SIP provider; false if
     *        the profile has not been opened in the SIP service or the SIP
     *        service has not yet successfully registered the profile to the SIP
     *        provider
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
     * Creates a {@link SipAudioCall} to make a call. The attempt will be timed
     * out if the call is not established within {@code timeout} seconds and
     * {@link SipAudioCall.Listener#onError onError(SipAudioCall, SipErrorCode.TIME_OUT, String)}
     * will be called.
     *
     * @param localProfile the SIP profile to make the call from
     * @param peerProfile the SIP profile to make the call to
     * @param listener to listen to the call events from {@link SipAudioCall};
     *      can be null
     * @param timeout the timeout value in seconds. Default value (defined by
     *        SIP protocol) is used if {@code timeout} is zero or negative.
     * @return a {@link SipAudioCall} object
     * @throws SipException if calling the SIP service results in an error or
     *      VOIP API is not supported by the device
     * @see SipAudioCall.Listener#onError
     * @see #isVoipSupported
     */
    public SipAudioCall makeAudioCall(SipProfile localProfile,
            SipProfile peerProfile, SipAudioCall.Listener listener, int timeout)
            throws SipException {
        if (!isVoipSupported(mContext)) {
            throw new SipException("VOIP API is not supported");
        }
        SipAudioCall call = new SipAudioCall(mContext, localProfile);
        call.setListener(listener);
        SipSession s = createSipSession(localProfile, null);
        call.makeCall(peerProfile, s, timeout);
        return call;
    }

    /**
     * Creates a {@link SipAudioCall} to make an audio call. The attempt will be
     * timed out if the call is not established within {@code timeout} seconds
     * and
     * {@link SipAudioCall.Listener#onError onError(SipAudioCall, SipErrorCode.TIME_OUT, String)}
     * will be called.
     *
     * @param localProfileUri URI of the SIP profile to make the call from
     * @param peerProfileUri URI of the SIP profile to make the call to
     * @param listener to listen to the call events from {@link SipAudioCall};
     *      can be null
     * @param timeout the timeout value in seconds. Default value (defined by
     *        SIP protocol) is used if {@code timeout} is zero or negative.
     * @return a {@link SipAudioCall} object
     * @throws SipException if calling the SIP service results in an error or
     *      VOIP API is not supported by the device
     * @see SipAudioCall.Listener#onError
     * @see #isVoipSupported
     */
    public SipAudioCall makeAudioCall(String localProfileUri,
            String peerProfileUri, SipAudioCall.Listener listener, int timeout)
            throws SipException {
        if (!isVoipSupported(mContext)) {
            throw new SipException("VOIP API is not supported");
        }
        try {
            return makeAudioCall(
                    new SipProfile.Builder(localProfileUri).build(),
                    new SipProfile.Builder(peerProfileUri).build(), listener,
                    timeout);
        } catch (ParseException e) {
            throw new SipException("build SipProfile", e);
        }
    }

    /**
     * Creates a {@link SipAudioCall} to take an incoming call. Before the call
     * is returned, the listener will receive a
     * {@link SipAudioCall.Listener#onRinging}
     * callback.
     *
     * @param incomingCallIntent the incoming call broadcast intent
     * @param listener to listen to the call events from {@link SipAudioCall};
     *      can be null
     * @return a {@link SipAudioCall} object
     * @throws SipException if calling the SIP service results in an error
     */
    public SipAudioCall takeAudioCall(Intent incomingCallIntent,
            SipAudioCall.Listener listener) throws SipException {
        if (incomingCallIntent == null) {
            throw new SipException("Cannot retrieve session with null intent");
        }

        String callId = getCallId(incomingCallIntent);
        if (callId == null) {
            throw new SipException("Call ID missing in incoming call intent");
        }

        String offerSd = getOfferSessionDescription(incomingCallIntent);
        if (offerSd == null) {
            throw new SipException("Session description missing in incoming "
                    + "call intent");
        }

        try {
            ISipSession session = mSipService.getPendingSession(callId);
            if (session == null) {
                throw new SipException("No pending session for the call");
            }
            SipAudioCall call = new SipAudioCall(
                    mContext, session.getLocalProfile());
            call.attachCall(new SipSession(session), offerSd);
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
        String offerSd = getOfferSessionDescription(intent);
        return ((callId != null) && (offerSd != null));
    }

    /**
     * Gets the call ID from the specified incoming call broadcast intent.
     *
     * @param incomingCallIntent the incoming call broadcast intent
     * @return the call ID or null if the intent does not contain it
     */
    public static String getCallId(Intent incomingCallIntent) {
        return incomingCallIntent.getStringExtra(EXTRA_CALL_ID);
    }

    /**
     * Gets the offer session description from the specified incoming call
     * broadcast intent.
     *
     * @param incomingCallIntent the incoming call broadcast intent
     * @return the offer session description or null if the intent does not
     *      have it
     */
    public static String getOfferSessionDescription(Intent incomingCallIntent) {
        return incomingCallIntent.getStringExtra(EXTRA_OFFER_SD);
    }

    /**
     * Creates an incoming call broadcast intent.
     *
     * @param callId the call ID of the incoming call
     * @param sessionDescription the session description of the incoming call
     * @return the incoming call intent
     * @hide
     */
    public static Intent createIncomingCallBroadcast(String callId,
            String sessionDescription) {
        Intent intent = new Intent();
        intent.putExtra(EXTRA_CALL_ID, callId);
        intent.putExtra(EXTRA_OFFER_SD, sessionDescription);
        return intent;
    }

    /**
     * Manually registers the profile to the corresponding SIP provider for
     * receiving calls.
     * {@link #open(SipProfile, PendingIntent, SipRegistrationListener)} is
     * still needed to be called at least once in order for the SIP service to
     * notify the caller with the {@link android.app.PendingIntent} when an incoming call is
     * received.
     *
     * @param localProfile the SIP profile to register with
     * @param expiryTime registration expiration time (in seconds)
     * @param listener to listen to the registration events
     * @throws SipException if calling the SIP service results in an error
     */
    public void register(SipProfile localProfile, int expiryTime,
            SipRegistrationListener listener) throws SipException {
        try {
            ISipSession session = mSipService.createSession(localProfile,
                    createRelay(listener, localProfile.getUriString()));
            session.register(expiryTime);
        } catch (RemoteException e) {
            throw new SipException("register()", e);
        }
    }

    /**
     * Manually unregisters the profile from the corresponding SIP provider for
     * stop receiving further calls. This may interference with the auto
     * registration process in the SIP service if the auto-registration option
     * in the profile is enabled.
     *
     * @param localProfile the SIP profile to register with
     * @param listener to listen to the registration events
     * @throws SipException if calling the SIP service results in an error
     */
    public void unregister(SipProfile localProfile,
            SipRegistrationListener listener) throws SipException {
        try {
            ISipSession session = mSipService.createSession(localProfile,
                    createRelay(listener, localProfile.getUriString()));
            session.unregister();
        } catch (RemoteException e) {
            throw new SipException("unregister()", e);
        }
    }

    /**
     * Gets the {@link SipSession} that handles the incoming call. For audio
     * calls, consider to use {@link SipAudioCall} to handle the incoming call.
     * See {@link #takeAudioCall}. Note that the method may be called only once
     * for the same intent. For subsequent calls on the same intent, the method
     * returns null.
     *
     * @param incomingCallIntent the incoming call broadcast intent
     * @return the session object that handles the incoming call
     */
    public SipSession getSessionFor(Intent incomingCallIntent)
            throws SipException {
        try {
            String callId = getCallId(incomingCallIntent);
            ISipSession s = mSipService.getPendingSession(callId);
            return new SipSession(s);
        } catch (RemoteException e) {
            throw new SipException("getSessionFor()", e);
        }
    }

    private static ISipSessionListener createRelay(
            SipRegistrationListener listener, String uri) {
        return ((listener == null) ? null : new ListenerRelay(listener, uri));
    }

    /**
     * Creates a {@link SipSession} with the specified profile. Use other
     * methods, if applicable, instead of interacting with {@link SipSession}
     * directly.
     *
     * @param localProfile the SIP profile the session is associated with
     * @param listener to listen to SIP session events
     */
    public SipSession createSipSession(SipProfile localProfile,
            SipSession.Listener listener) throws SipException {
        try {
            ISipSession s = mSipService.createSession(localProfile, null);
            if (s == null) {
                throw new SipException(
                        "Failed to create SipSession; network unavailable?");
            }
            return new SipSession(s, listener);
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
            return new SipProfile[0];
        }
    }

    private static class ListenerRelay extends SipSessionAdapter {
        private SipRegistrationListener mListener;
        private String mUri;

        // listener must not be null
        public ListenerRelay(SipRegistrationListener listener, String uri) {
            mListener = listener;
            mUri = uri;
        }

        private String getUri(ISipSession session) {
            try {
                return ((session == null)
                        ? mUri
                        : session.getLocalProfile().getUriString());
            } catch (Throwable e) {
                // SipService died? SIP stack died?
                Log.w(TAG, "getUri(): " + e);
                return null;
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
        public void onRegistrationFailed(ISipSession session, int errorCode,
                String message) {
            mListener.onRegistrationFailed(getUri(session), errorCode, message);
        }

        @Override
        public void onRegistrationTimeout(ISipSession session) {
            mListener.onRegistrationFailed(getUri(session),
                    SipErrorCode.TIME_OUT, "registration timed out");
        }
    }
}
