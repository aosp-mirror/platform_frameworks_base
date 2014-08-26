/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.telecomm;

import com.android.internal.telecomm.IConnectionService;

import android.app.PendingIntent;
import android.net.Uri;
import android.os.RemoteException;
import android.telephony.DisconnectCause;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A connection provided to a {@link ConnectionService} by another {@code ConnectionService}
 * running in a different process.
 *
 * @see ConnectionService#createRemoteOutgoingConnection(PhoneAccountHandle, ConnectionRequest)
 * @see ConnectionService#createRemoteIncomingConnection(PhoneAccountHandle, ConnectionRequest)
 */
public final class RemoteConnection {

    public static abstract class Listener {
        /**
         * Invoked when the state of this {@code RemoteConnection} has changed. See
         * {@link #getState()}.
         *
         * @param connection The {@code RemoteConnection} invoking this method.
         * @param state The new state of the {@code RemoteConnection}.
         */
        public void onStateChanged(RemoteConnection connection, int state) {}

        /**
         * Invoked when the parent of this {@code RemoteConnection} has changed. See
         * {@link #getParent()}.
         *
         * @param connection The {@code RemoteConnection} invoking this method.
         * @param parent The new parent of the {@code RemoteConnection}.
         */
        public void onParentChanged(RemoteConnection connection, RemoteConnection parent) {}

        /**
         * Invoked when the children of this {@code RemoteConnection} have changed. See
         * {@link #getChildren()}.
         *
         * @param connection The {@code RemoteConnection} invoking this method.
         * @param children The new children of the {@code RemoteConnection}.
         */
        public void onChildrenChanged(
                RemoteConnection connection, List<RemoteConnection> children) {}

        /**
         * Invoked when this {@code RemoteConnection} is disconnected.
         *
         * @param connection The {@code RemoteConnection} invoking this method.
         * @param disconnectCauseCode The failure code ({@see DisconnectCause}) associated with this
         *         failed connection.
         * @param disconnectCauseMessage The reason for the connection failure. This will not be
         *         displayed to the user.
         */
        public void onDisconnected(
                RemoteConnection connection,
                int disconnectCauseCode,
                String disconnectCauseMessage) {}

        /**
         * Invoked when this {@code RemoteConnection} is requesting ringback. See
         * {@link #isRequestingRingback()}.
         *
         * @param connection The {@code RemoteConnection} invoking this method.
         * @param ringback Whether the {@code RemoteConnection} is requesting ringback.
         */
        public void onRequestingRingback(RemoteConnection connection, boolean ringback) {}

        /**
         * Indicates that the call capabilities of this {@code RemoteConnection} have changed.
         * See {@link #getCallCapabilities()}.
         *
         * @param connection The {@code RemoteConnection} invoking this method.
         * @param callCapabilities The new call capabilities of the {@code RemoteConnection}.
         */
        public void onCallCapabilitiesChanged(RemoteConnection connection, int callCapabilities) {}

        /**
         * Invoked when the post-dial sequence in the outgoing {@code Connection} has reached a
         * pause character. This causes the post-dial signals to stop pending user confirmation. An
         * implementation should present this choice to the user and invoke
         * {@link RemoteConnection#postDialContinue(boolean)} when the user makes the choice.
         *
         * @param connection The {@code RemoteConnection} invoking this method.
         * @param remainingPostDialSequence The post-dial characters that remain to be sent.
         */
        public void onPostDialWait(RemoteConnection connection, String remainingPostDialSequence) {}

        /**
         * Indicates that the VOIP audio status of this {@code RemoteConnection} has changed.
         * See {@link #getAudioModeIsVoip()}.
         *
         * @param connection The {@code RemoteConnection} invoking this method.
         * @param isVoip Whether the new audio state of the {@code RemoteConnection} is VOIP.
         */
        public void onAudioModeIsVoipChanged(RemoteConnection connection, boolean isVoip) {}

        /**
         * Indicates that the status hints of this {@code RemoteConnection} have changed. See
         * {@link #getStatusHints()} ()}.
         *
         * @param connection The {@code RemoteConnection} invoking this method.
         * @param statusHints The new status hints of the {@code RemoteConnection}.
         */
        public void onStatusHintsChanged(RemoteConnection connection, StatusHints statusHints) {}

        /**
         * Indicates that the handle (e.g., phone number) of this {@code RemoteConnection} has
         * changed. See {@link #getHandle()} and {@link #getHandlePresentation()}.
         *
         * @param connection The {@code RemoteConnection} invoking this method.
         * @param handle The new handle of the {@code RemoteConnection}.
         * @param presentation The {@link PropertyPresentation} which controls how the
         *         handle is shown.
         */
        public void onHandleChanged(RemoteConnection connection, Uri handle, int presentation) {}

        /**
         * Indicates that the caller display name of this {@code RemoteConnection} has changed.
         * See {@link #getCallerDisplayName()} and {@link #getCallerDisplayNamePresentation()}.
         *
         * @param connection The {@code RemoteConnection} invoking this method.
         * @param callerDisplayName The new caller display name of the {@code RemoteConnection}.
         * @param presentation The {@link PropertyPresentation} which controls how the
         *         caller display name is shown.
         */
        public void onCallerDisplayNameChanged(
                RemoteConnection connection, String callerDisplayName, int presentation) {}

        /**
         * Indicates that the video state of this {@code RemoteConnection} has changed.
         * See {@link #getVideoState()}.
         *
         * @param connection The {@code RemoteConnection} invoking this method.
         * @param videoState The new video state of the {@code RemoteConnection}.
         * @hide
         */
        public void onVideoStateChanged(RemoteConnection connection, int videoState) {}

        /**
         * Indicates that this {@code RemoteConnection} is requesting that the in-call UI
         * launch the specified activity.
         *
         * @param connection The {@code RemoteConnection} invoking this method.
         * @param intent A {@link PendingIntent} that the {@code RemoteConnection} wishes to
         *         have launched on its behalf.
         */
        public void onStartActivityFromInCall(RemoteConnection connection, PendingIntent intent) {}

        /**
         * Indicates that this {@code RemoteConnection} has been destroyed. No further requests
         * should be made to the {@code RemoteConnection}, and references to it should be cleared.
         *
         * @param connection The {@code RemoteConnection} invoking this method.
         */
        public void onDestroyed(RemoteConnection connection) {}

        /**
         * Indicates that the {@code RemoteConnection}s with which this {@code RemoteConnection}
         * may be asked to create a conference has changed.
         *
         * @param connection The {@code RemoteConnection} invoking this method.
         * @param conferenceableConnections The {@code RemoteConnection}s with which this
         *         {@code RemoteConnection} may be asked to create a conference.
         */
        public void onConferenceableConnectionsChanged(
                RemoteConnection connection,
                List<RemoteConnection> conferenceableConnections) {}

        /**
         * Indicates that the {@code RemoteConference} that this {@code RemoteConnection} is a part
         * of has changed.
         *
         * @param connection The {@code RemoteConnection} invoking this method.
         * @param conference The {@code RemoteConference} of which this {@code RemoteConnection} is
         *         a part, which may be {@code null}.
         */
        public void onConferenceChanged(
                RemoteConnection connection,
                RemoteConference conference) {}
    }

    private IConnectionService mConnectionService;
    private final String mConnectionId;
    /**
     * ConcurrentHashMap constructor params: 8 is initial table size, 0.9f is
     * load factor before resizing, 1 means we only expect a single thread to
     * access the map so make only a single shard
     */
    private final Set<Listener> mListeners = Collections.newSetFromMap(
            new ConcurrentHashMap<Listener, Boolean>(8, 0.9f, 1));
    private final List<RemoteConnection> mConferenceableConnections = new ArrayList<>();
    private final List<RemoteConnection> mUnmodifiableconferenceableConnections =
            Collections.unmodifiableList(mConferenceableConnections);

    private int mState = Connection.STATE_NEW;
    private int mDisconnectCauseCode = DisconnectCause.NOT_VALID;
    private String mDisconnectCauseMessage;
    private boolean mRequestingRingback;
    private boolean mConnected;
    private int mCallCapabilities;
    private int mVideoState;
    private boolean mAudioModeIsVoip;
    private StatusHints mStatusHints;
    private Uri mHandle;
    private int mHandlePresentation;
    private String mCallerDisplayName;
    private int mCallerDisplayNamePresentation;
    private int mFailureCode;
    private String mFailureMessage;
    private RemoteConference mConference;

    /**
     * @hide
     */
    RemoteConnection(
            String id,
            IConnectionService connectionService,
            ConnectionRequest request) {
        mConnectionId = id;
        mConnectionService = connectionService;
        mConnected = true;
        mState = Connection.STATE_INITIALIZING;
    }

    /**
     * Create a RemoteConnection which is used for failed connections. Note that using it for any
     * "real" purpose will almost certainly fail. Callers should note the failure and act
     * accordingly (moving on to another RemoteConnection, for example)
     *
     * @param failureCode
     * @param failureMessage
     */
    RemoteConnection(int failureCode, String failureMessage) {
        this("NULL", null, null);
        mConnected = false;
        mState = Connection.STATE_DISCONNECTED;
        mFailureCode = DisconnectCause.OUTGOING_FAILURE;
        mFailureMessage = failureMessage + " original code = " + failureCode;
    }

    /**
     * Adds a listener to this {@code RemoteConnection}.
     *
     * @param listener A {@code Listener}.
     */
    public void addListener(Listener listener) {
        mListeners.add(listener);
    }

    /**
     * Removes a listener from this {@code RemoteConnection}.
     *
     * @param listener A {@code Listener}.
     */
    public void removeListener(Listener listener) {
        if (listener != null) {
            mListeners.remove(listener);
        }
    }

    /**
     * Obtains the parent of this {@code RemoteConnection} in a conference, if any.
     *
     * @return The parent {@code RemoteConnection}, or {@code null} if this {@code RemoteConnection}
     * is not a child of any conference {@code RemoteConnection}s.
     */
    public RemoteConnection getParent() { return null; }

    /**
     * Obtains the children of this conference {@code RemoteConnection}, if any.
     *
     * @return The children of this {@code RemoteConnection} if this {@code RemoteConnection} is
     * a conference, or an empty {@code List} otherwise.
     */
    public List<RemoteConnection> getChildren() { return null; }

    /**
     * Obtains the state of this {@code RemoteConnection}.
     *
     * @return A state value, chosen from the {@code STATE_*} constants.
     */
    public int getState() {
        return mState;
    }

    /**
     * @return For a {@link Connection#STATE_DISCONNECTED} {@code RemoteConnection}, the
     * disconnect cause expressed as a code chosen from among those declared in
     * {@link DisconnectCause}.
     */
    public int getDisconnectCauseCode() {
        return mDisconnectCauseCode;
    }

    /**
     * @return For a {@link Connection#STATE_DISCONNECTED} {@code RemoteConnection}, an optional
     * reason for disconnection expressed as a free text message.
     */
    public String getDisconnectCauseMessage() {
        return mDisconnectCauseMessage;
    }

    /**
     * @return A bitmask of the capabilities of the {@code RemoteConnection}, as defined in
     *         {@link PhoneCapabilities}.
     */
    public int getCallCapabilities() {
        return mCallCapabilities;
    }

    /**
     * @return {@code true} if the {@code RemoteConnection}'s current audio mode is VOIP.
     */
    public boolean getAudioModeIsVoip() {
        return mAudioModeIsVoip;
    }

    /**
     * @return The current {@link StatusHints} of this {@code RemoteConnection},
     * or {@code null} if none have been set.
     */
    public StatusHints getStatusHints() {
        return mStatusHints;
    }

    /**
     * @return The handle (e.g., phone number) to which the {@code RemoteConnection} is currently
     * connected.
     */
    public Uri getHandle() {
        return mHandle;
    }

    /**
     * @return The presentation requirements for the handle. See
     * {@link PropertyPresentation} for valid values.
     */
    public int getHandlePresentation() {
        return mHandlePresentation;
    }

    /**
     * @return The display name for the caller.
     */
    public String getCallerDisplayName() {
        return mCallerDisplayName;
    }

    /**
     * @return The presentation requirements for the caller display name. See
     * {@link PropertyPresentation} for valid values.
     */
    public int getCallerDisplayNamePresentation() {
        return mCallerDisplayNamePresentation;
    }

    /**
     * @return The video state of the {@code RemoteConnection}. See
     * {@link VideoProfile.VideoState}.
     * @hide
     */
    public int getVideoState() {
        return mVideoState;
    }

    /**
     * @return The failure code ({@see DisconnectCause}) associated with this failed
     * {@code RemoteConnection}.
     */
    public int getFailureCode() {
        return mFailureCode;
    }

    /**
     * @return The reason for the connection failure. This will not be displayed to the user.
     */
    public String getFailureMessage() {
        return mFailureMessage;
    }

    /**
     * @return Whether the {@code RemoteConnection} is requesting that the framework play a
     * ringback tone on its behalf.
     */
    public boolean isRequestingRingback() {
        return false;
    }

    /**
     * Instructs this {@code RemoteConnection} to abort.
     */
    public void abort() {
        try {
            if (mConnected) {
                mConnectionService.abort(mConnectionId);
            }
        } catch (RemoteException ignored) {
        }
    }

    /**
     * Instructs this {@link Connection#STATE_RINGING} {@code RemoteConnection} to answer.
     * @param videoState The video state in which to answer the call.
     */
    public void answer(int videoState) {
        try {
            if (mConnected) {
                mConnectionService.answer(mConnectionId, videoState);
            }
        } catch (RemoteException ignored) {
        }
    }

    /**
     * Instructs this {@link Connection#STATE_RINGING} {@code RemoteConnection} to reject.
     */
    public void reject() {
        try {
            if (mConnected) {
                mConnectionService.reject(mConnectionId);
            }
        } catch (RemoteException ignored) {
        }
    }

    /**
     * Instructs this {@code RemoteConnection} to go on hold.
     */
    public void hold() {
        try {
            if (mConnected) {
                mConnectionService.hold(mConnectionId);
            }
        } catch (RemoteException ignored) {
        }
    }

    /**
     * Instructs this {@link Connection#STATE_HOLDING} call to release from hold.
     */
    public void unhold() {
        try {
            if (mConnected) {
                mConnectionService.unhold(mConnectionId);
            }
        } catch (RemoteException ignored) {
        }
    }

    /**
     * Instructs this {@code RemoteConnection} to disconnect.
     */
    public void disconnect() {
        try {
            if (mConnected) {
                mConnectionService.disconnect(mConnectionId);
            }
        } catch (RemoteException ignored) {
        }
    }

    /**
     * Instructs this {@code RemoteConnection} to play a dual-tone multi-frequency signaling
     * (DTMF) tone.
     *
     * Any other currently playing DTMF tone in the specified call is immediately stopped.
     *
     * @param digit A character representing the DTMF digit for which to play the tone. This
     *         value must be one of {@code '0'} through {@code '9'}, {@code '*'} or {@code '#'}.
     */
    public void playDtmfTone(char digit) {
        try {
            if (mConnected) {
                mConnectionService.playDtmfTone(mConnectionId, digit);
            }
        } catch (RemoteException ignored) {
        }
    }

    /**
     * Instructs this {@code RemoteConnection} to stop any dual-tone multi-frequency signaling
     * (DTMF) tone currently playing.
     *
     * DTMF tones are played by calling {@link #playDtmfTone(char)}. If no DTMF tone is
     * currently playing, this method will do nothing.
     */
    public void stopDtmfTone() {
        try {
            if (mConnected) {
                mConnectionService.stopDtmfTone(mConnectionId);
            }
        } catch (RemoteException ignored) {
        }
    }

    /**
     * Instructs this {@code RemoteConnection} to continue playing a post-dial DTMF string.
     *
     * A post-dial DTMF string is a string of digits following the first instance of either
     * {@link TelecommManager#DTMF_CHARACTER_WAIT} or {@link TelecommManager#DTMF_CHARACTER_PAUSE}.
     * These digits are immediately sent as DTMF tones to the recipient as soon as the
     * connection is made.
     *
     * If the DTMF string contains a {@link TelecommManager#DTMF_CHARACTER_PAUSE} symbol, this
     * {@code RemoteConnection} will temporarily pause playing the tones for a pre-defined period
     * of time.
     *
     * If the DTMF string contains a {@link TelecommManager#DTMF_CHARACTER_WAIT} symbol, this
     * {@code RemoteConnection} will pause playing the tones and notify listeners via
     * {@link Listener#onPostDialWait(RemoteConnection, String)}. At this point, the in-call app
     * should display to the user an indication of this state and an affordance to continue
     * the postdial sequence. When the user decides to continue the postdial sequence, the in-call
     * app should invoke the {@link #postDialContinue(boolean)} method.
     *
     * @param proceed Whether or not to continue with the post-dial sequence.
     */
    public void postDialContinue(boolean proceed) {
        try {
            if (mConnected) {
                mConnectionService.onPostDialContinue(mConnectionId, proceed);
            }
        } catch (RemoteException ignored) {
        }
    }

    /**
     * Set the audio state of this {@code RemoteConnection}.
     *
     * @param state The audio state of this {@code RemoteConnection}.
     */
    public void setAudioState(AudioState state) {
        try {
            if (mConnected) {
                mConnectionService.onAudioStateChanged(mConnectionId, state);
            }
        } catch (RemoteException ignored) {
        }
    }

    /**
     * Obtain the {@code RemoteConnection}s with which this {@code RemoteConnection} may be
     * successfully asked to create a conference with.
     *
     * @return The {@code RemoteConnection}s with which this {@code RemoteConnection} may be
     *         merged into a {@link RemoteConference}.
     */
    public List<RemoteConnection> getConferenceableConnections() {
        return mUnmodifiableconferenceableConnections;
    }

    /**
     * Obtain the {@code RemoteConference} that this {@code RemoteConnection} may be a part
     * of, or {@code null} if there is no such {@code RemoteConference}.
     *
     * @return A {@code RemoteConference} or {@code null};
     */
    public RemoteConference getConference() {
        return mConference;
    }

    /** {@hide} */
    String getId() {
        return mConnectionId;
    }

    /** {@hide} */
    IConnectionService getConnectionService() {
        return mConnectionService;
    }

    /**
     * @hide
     */
    void setState(int state) {
        if (mState != state) {
            mState = state;
            for (Listener l: mListeners) {
                l.onStateChanged(this, state);
            }
        }
    }

    /**
     * @hide
     */
    void setDisconnected(int cause, String message) {
        if (mState != Connection.STATE_DISCONNECTED) {
            mState = Connection.STATE_DISCONNECTED;
            mDisconnectCauseCode = cause;
            mDisconnectCauseMessage = message;

            for (Listener l : mListeners) {
                l.onDisconnected(this, cause, message);
            }
        }
    }

    /**
     * @hide
     */
    void setRequestingRingback(boolean ringback) {
        if (mRequestingRingback != ringback) {
            mRequestingRingback = ringback;
            for (Listener l : mListeners) {
                l.onRequestingRingback(this, ringback);
            }
        }
    }

    /**
     * @hide
     */
    void setCallCapabilities(int callCapabilities) {
        mCallCapabilities = callCapabilities;
        for (Listener l : mListeners) {
            l.onCallCapabilitiesChanged(this, callCapabilities);
        }
    }

    /**
     * @hide
     */
    void setDestroyed() {
        if (!mListeners.isEmpty()) {
            // Make sure that the listeners are notified that the call is destroyed first.
            if (mState != Connection.STATE_DISCONNECTED) {
                setDisconnected(DisconnectCause.ERROR_UNSPECIFIED, "Connection destroyed.");
            }

            for (Listener l : mListeners) {
                l.onDestroyed(this);
            }
            mListeners.clear();

            mConnected = false;
        }
    }

    /**
     * @hide
     */
    void setPostDialWait(String remainingDigits) {
        for (Listener l : mListeners) {
            l.onPostDialWait(this, remainingDigits);
        }
    }

    /**
     * @hide
     */
    void setVideoState(int videoState) {
        mVideoState = videoState;
        for (Listener l : mListeners) {
            l.onVideoStateChanged(this, videoState);
        }
    }

    /** @hide */
    void setAudioModeIsVoip(boolean isVoip) {
        mAudioModeIsVoip = isVoip;
        for (Listener l : mListeners) {
            l.onAudioModeIsVoipChanged(this, isVoip);
        }
    }

    /** @hide */
    void setStatusHints(StatusHints statusHints) {
        mStatusHints = statusHints;
        for (Listener l : mListeners) {
            l.onStatusHintsChanged(this, statusHints);
        }
    }

    /** @hide */
    void setHandle(Uri handle, int presentation) {
        mHandle = handle;
        mHandlePresentation = presentation;
        for (Listener l : mListeners) {
            l.onHandleChanged(this, handle, presentation);
        }
    }

    /** @hide */
    void setCallerDisplayName(String callerDisplayName, int presentation) {
        mCallerDisplayName = callerDisplayName;
        mCallerDisplayNamePresentation = presentation;
        for (Listener l : mListeners) {
            l.onCallerDisplayNameChanged(this, callerDisplayName, presentation);
        }
    }

    /** @hide */
    void startActivityFromInCall(PendingIntent intent) {
        for (Listener l : mListeners) {
            l.onStartActivityFromInCall(this, intent);
        }
    }

    /** @hide */
    void setConferenceableConnections(List<RemoteConnection> conferenceableConnections) {
        mConferenceableConnections.clear();
        mConferenceableConnections.addAll(conferenceableConnections);
        for (Listener l : mListeners) {
            l.onConferenceableConnectionsChanged(this, mUnmodifiableconferenceableConnections);
        }
    }

    /** @hide */
    void setConference(RemoteConference conference) {
        if (mConference != conference) {
            mConference = conference;
            for (Listener l : mListeners) {
                l.onConferenceChanged(this, conference);
            }
        }
    }

    /**
     * Create a RemoteConnection represents a failure, and which will be in
     * {@link Connection#STATE_DISCONNECTED}. Attempting to use it for anything will almost
     * certainly result in bad things happening. Do not do this.
     *
     * @return a failed {@link RemoteConnection}
     *
     * @hide
     */
    public static RemoteConnection failure(int failureCode, String failureMessage) {
        return new RemoteConnection(failureCode, failureMessage);
    }
}
