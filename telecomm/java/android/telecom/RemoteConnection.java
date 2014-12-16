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

package android.telecom;

import com.android.internal.telecom.IConnectionService;
import com.android.internal.telecom.IVideoCallback;
import com.android.internal.telecom.IVideoProvider;

import android.annotation.SystemApi;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A connection provided to a {@link ConnectionService} by another {@code ConnectionService}
 * running in a different process.
 *
 * @see ConnectionService#createRemoteOutgoingConnection(PhoneAccountHandle, ConnectionRequest)
 * @see ConnectionService#createRemoteIncomingConnection(PhoneAccountHandle, ConnectionRequest)
 * @hide
 */
@SystemApi
public final class RemoteConnection {

    public static abstract class Callback {
        /**
         * Invoked when the state of this {@code RemoteConnection} has changed. See
         * {@link #getState()}.
         *
         * @param connection The {@code RemoteConnection} invoking this method.
         * @param state The new state of the {@code RemoteConnection}.
         */
        public void onStateChanged(RemoteConnection connection, int state) {}

        /**
         * Invoked when this {@code RemoteConnection} is disconnected.
         *
         * @param connection The {@code RemoteConnection} invoking this method.
         * @param disconnectCause The ({@see DisconnectCause}) associated with this failed
         *     connection.
         */
        public void onDisconnected(
                RemoteConnection connection,
                DisconnectCause disconnectCause) {}

        /**
         * Invoked when this {@code RemoteConnection} is requesting ringback. See
         * {@link #isRingbackRequested()}.
         *
         * @param connection The {@code RemoteConnection} invoking this method.
         * @param ringback Whether the {@code RemoteConnection} is requesting ringback.
         */
        public void onRingbackRequested(RemoteConnection connection, boolean ringback) {}

        /** @hide */
        @Deprecated public void onCallCapabilitiesChanged(
                RemoteConnection connection,
                int callCapabilities) {}

        /**
         * Indicates that the call capabilities of this {@code RemoteConnection} have changed.
         * See {@link #getConnectionCapabilities()}.
         *
         * @param connection The {@code RemoteConnection} invoking this method.
         * @param connectionCapabilities The new capabilities of the {@code RemoteConnection}.
         */
        public void onConnectionCapabilitiesChanged(
                RemoteConnection connection,
                int connectionCapabilities) {}

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
         * Invoked when the post-dial sequence in the outgoing {@code Connection} has processed
         * a character.
         *
         * @param connection The {@code RemoteConnection} invoking this method.
         * @param nextChar The character being processed.
         */
        public void onPostDialChar(RemoteConnection connection, char nextChar) {}

        /**
         * Indicates that the VOIP audio status of this {@code RemoteConnection} has changed.
         * See {@link #isVoipAudioMode()}.
         *
         * @param connection The {@code RemoteConnection} invoking this method.
         * @param isVoip Whether the new audio state of the {@code RemoteConnection} is VOIP.
         */
        public void onVoipAudioChanged(RemoteConnection connection, boolean isVoip) {}

        /**
         * Indicates that the status hints of this {@code RemoteConnection} have changed. See
         * {@link #getStatusHints()} ()}.
         *
         * @param connection The {@code RemoteConnection} invoking this method.
         * @param statusHints The new status hints of the {@code RemoteConnection}.
         */
        public void onStatusHintsChanged(RemoteConnection connection, StatusHints statusHints) {}

        /**
         * Indicates that the address (e.g., phone number) of this {@code RemoteConnection} has
         * changed. See {@link #getAddress()} and {@link #getAddressPresentation()}.
         *
         * @param connection The {@code RemoteConnection} invoking this method.
         * @param address The new address of the {@code RemoteConnection}.
         * @param presentation The presentation requirements for the address.
         *        See {@link TelecomManager} for valid values.
         */
        public void onAddressChanged(RemoteConnection connection, Uri address, int presentation) {}

        /**
         * Indicates that the caller display name of this {@code RemoteConnection} has changed.
         * See {@link #getCallerDisplayName()} and {@link #getCallerDisplayNamePresentation()}.
         *
         * @param connection The {@code RemoteConnection} invoking this method.
         * @param callerDisplayName The new caller display name of the {@code RemoteConnection}.
         * @param presentation The presentation requirements for the handle.
         *        See {@link TelecomManager} for valid values.
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
         * Indicates that the {@code VideoProvider} associated with this {@code RemoteConnection}
         * has changed.
         *
         * @param connection The {@code RemoteConnection} invoking this method.
         * @param videoProvider The new {@code VideoProvider} associated with this
         *         {@code RemoteConnection}.
         * @hide
         */
        public void onVideoProviderChanged(
                RemoteConnection connection, VideoProvider videoProvider) {}

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

    /** {@hide} */
    public static class VideoProvider {

        public abstract static class Listener {
            public void onReceiveSessionModifyRequest(
                    VideoProvider videoProvider,
                    VideoProfile videoProfile) {}

            public void onReceiveSessionModifyResponse(
                    VideoProvider videoProvider,
                    int status,
                    VideoProfile requestedProfile,
                    VideoProfile responseProfile) {}

            public void onHandleCallSessionEvent(VideoProvider videoProvider, int event) {}

            public void onPeerDimensionsChanged(VideoProvider videoProvider, int width, int height) {}

            public void onCallDataUsageChanged(VideoProvider videoProvider, int dataUsage) {}

            public void onCameraCapabilitiesChanged(
                    VideoProvider videoProvider,
                    CameraCapabilities cameraCapabilities) {}
        }

        private final IVideoCallback mVideoCallbackDelegate = new IVideoCallback() {
            @Override
            public void receiveSessionModifyRequest(VideoProfile videoProfile) {
                for (Listener l : mListeners) {
                    l.onReceiveSessionModifyRequest(VideoProvider.this, videoProfile);
                }
            }

            @Override
            public void receiveSessionModifyResponse(int status, VideoProfile requestedProfile,
                    VideoProfile responseProfile) {
                for (Listener l : mListeners) {
                    l.onReceiveSessionModifyResponse(
                            VideoProvider.this,
                            status,
                            requestedProfile,
                            responseProfile);
                }
            }

            @Override
            public void handleCallSessionEvent(int event) {
                for (Listener l : mListeners) {
                    l.onHandleCallSessionEvent(VideoProvider.this, event);
                }
            }

            @Override
            public void changePeerDimensions(int width, int height) {
                for (Listener l : mListeners) {
                    l.onPeerDimensionsChanged(VideoProvider.this, width, height);
                }
            }

            @Override
            public void changeCallDataUsage(int dataUsage) {
                for (Listener l : mListeners) {
                    l.onCallDataUsageChanged(VideoProvider.this, dataUsage);
                }
            }

            @Override
            public void changeCameraCapabilities(CameraCapabilities cameraCapabilities) {
                for (Listener l : mListeners) {
                    l.onCameraCapabilitiesChanged(VideoProvider.this, cameraCapabilities);
                }
            }

            @Override
            public IBinder asBinder() {
                return null;
            }
        };

        private final VideoCallbackServant mVideoCallbackServant =
                new VideoCallbackServant(mVideoCallbackDelegate);

        private final IVideoProvider mVideoProviderBinder;

        /**
         * ConcurrentHashMap constructor params: 8 is initial table size, 0.9f is
         * load factor before resizing, 1 means we only expect a single thread to
         * access the map so make only a single shard
         */
        private final Set<Listener> mListeners = Collections.newSetFromMap(
                new ConcurrentHashMap<Listener, Boolean>(8, 0.9f, 1));

        public VideoProvider(IVideoProvider videoProviderBinder) {
            mVideoProviderBinder = videoProviderBinder;
            try {
                mVideoProviderBinder.setVideoCallback(mVideoCallbackServant.getStub().asBinder());
            } catch (RemoteException e) {
            }
        }

        public void addListener(Listener l) {
            mListeners.add(l);
        }

        public void removeListener(Listener l) {
            mListeners.remove(l);
        }

        public void setCamera(String cameraId) {
            try {
                mVideoProviderBinder.setCamera(cameraId);
            } catch (RemoteException e) {
            }
        }

        public void setPreviewSurface(Surface surface) {
            try {
                mVideoProviderBinder.setPreviewSurface(surface);
            } catch (RemoteException e) {
            }
        }

        public void setDisplaySurface(Surface surface) {
            try {
                mVideoProviderBinder.setDisplaySurface(surface);
            } catch (RemoteException e) {
            }
        }

        public void setDeviceOrientation(int rotation) {
            try {
                mVideoProviderBinder.setDeviceOrientation(rotation);
            } catch (RemoteException e) {
            }
        }

        public void setZoom(float value) {
            try {
                mVideoProviderBinder.setZoom(value);
            } catch (RemoteException e) {
            }
        }

        public void sendSessionModifyRequest(VideoProfile reqProfile) {
            try {
                mVideoProviderBinder.sendSessionModifyRequest(reqProfile);
            } catch (RemoteException e) {
            }
        }

        public void sendSessionModifyResponse(VideoProfile responseProfile) {
            try {
                mVideoProviderBinder.sendSessionModifyResponse(responseProfile);
            } catch (RemoteException e) {
            }
        }

        public void requestCameraCapabilities() {
            try {
                mVideoProviderBinder.requestCameraCapabilities();
            } catch (RemoteException e) {
            }
        }

        public void requestCallDataUsage() {
            try {
                mVideoProviderBinder.requestCallDataUsage();
            } catch (RemoteException e) {
            }
        }

        public void setPauseImage(String uri) {
            try {
                mVideoProviderBinder.setPauseImage(uri);
            } catch (RemoteException e) {
            }
        }
    }

    private IConnectionService mConnectionService;
    private final String mConnectionId;
    /**
     * ConcurrentHashMap constructor params: 8 is initial table size, 0.9f is
     * load factor before resizing, 1 means we only expect a single thread to
     * access the map so make only a single shard
     */
    private final Set<Callback> mCallbacks = Collections.newSetFromMap(
            new ConcurrentHashMap<Callback, Boolean>(8, 0.9f, 1));
    private final List<RemoteConnection> mConferenceableConnections = new ArrayList<>();
    private final List<RemoteConnection> mUnmodifiableconferenceableConnections =
            Collections.unmodifiableList(mConferenceableConnections);

    private int mState = Connection.STATE_NEW;
    private DisconnectCause mDisconnectCause;
    private boolean mRingbackRequested;
    private boolean mConnected;
    private int mConnectionCapabilities;
    private int mVideoState;
    private VideoProvider mVideoProvider;
    private boolean mIsVoipAudioMode;
    private StatusHints mStatusHints;
    private Uri mAddress;
    private int mAddressPresentation;
    private String mCallerDisplayName;
    private int mCallerDisplayNamePresentation;
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
     * @hide
     */
    RemoteConnection(String callId, IConnectionService connectionService,
            ParcelableConnection connection) {
        mConnectionId = callId;
        mConnectionService = connectionService;
        mConnected = true;
        mState = connection.getState();
        mDisconnectCause = connection.getDisconnectCause();
        mRingbackRequested = connection.isRingbackRequested();
        mConnectionCapabilities = connection.getConnectionCapabilities();
        mVideoState = connection.getVideoState();
        mVideoProvider = new RemoteConnection.VideoProvider(connection.getVideoProvider());
        mIsVoipAudioMode = connection.getIsVoipAudioMode();
        mStatusHints = connection.getStatusHints();
        mAddress = connection.getHandle();
        mAddressPresentation = connection.getHandlePresentation();
        mCallerDisplayName = connection.getCallerDisplayName();
        mCallerDisplayNamePresentation = connection.getCallerDisplayNamePresentation();
        mConference = null;
    }

    /**
     * Create a RemoteConnection which is used for failed connections. Note that using it for any
     * "real" purpose will almost certainly fail. Callers should note the failure and act
     * accordingly (moving on to another RemoteConnection, for example)
     *
     * @param disconnectCause The reason for the failed connection.
     * @hide
     */
    RemoteConnection(DisconnectCause disconnectCause) {
        mConnectionId = "NULL";
        mConnected = false;
        mState = Connection.STATE_DISCONNECTED;
        mDisconnectCause = disconnectCause;
    }

    /**
     * Adds a callback to this {@code RemoteConnection}.
     *
     * @param callback A {@code Callback}.
     */
    public void registerCallback(Callback callback) {
        mCallbacks.add(callback);
    }

    /**
     * Removes a callback from this {@code RemoteConnection}.
     *
     * @param callback A {@code Callback}.
     */
    public void unregisterCallback(Callback callback) {
        if (callback != null) {
            mCallbacks.remove(callback);
        }
    }

    /**
     * Obtains the state of this {@code RemoteConnection}.
     *
     * @return A state value, chosen from the {@code STATE_*} constants.
     */
    public int getState() {
        return mState;
    }

    /**
     * Obtains the reason why this {@code RemoteConnection} may have been disconnected.
     *
     * @return For a {@link Connection#STATE_DISCONNECTED} {@code RemoteConnection}, the
     *         disconnect cause expressed as a code chosen from among those declared in
     *         {@link DisconnectCause}.
     */
    public DisconnectCause getDisconnectCause() {
        return mDisconnectCause;
    }

    /**
     * Obtains the capabilities of this {@code RemoteConnection}.
     *
     * @return A bitmask of the capabilities of the {@code RemoteConnection}, as defined in
     *         the {@code CAPABILITY_*} constants in class {@link Connection}.
     */
    public int getConnectionCapabilities() {
        return mConnectionCapabilities;
    }

    /**
     * Determines if the audio mode of this {@code RemoteConnection} is VOIP.
     *
     * @return {@code true} if the {@code RemoteConnection}'s current audio mode is VOIP.
     */
    public boolean isVoipAudioMode() {
        return mIsVoipAudioMode;
    }

    /**
     * Obtains status hints pertaining to this {@code RemoteConnection}.
     *
     * @return The current {@link StatusHints} of this {@code RemoteConnection},
     *         or {@code null} if none have been set.
     */
    public StatusHints getStatusHints() {
        return mStatusHints;
    }

    /**
     * Obtains the address of this {@code RemoteConnection}.
     *
     * @return The address (e.g., phone number) to which the {@code RemoteConnection}
     *         is currently connected.
     */
    public Uri getAddress() {
        return mAddress;
    }

    /**
     * Obtains the presentation requirements for the address of this {@code RemoteConnection}.
     *
     * @return The presentation requirements for the address. See
     *         {@link TelecomManager} for valid values.
     */
    public int getAddressPresentation() {
        return mAddressPresentation;
    }

    /**
     * Obtains the display name for this {@code RemoteConnection}'s caller.
     *
     * @return The display name for the caller.
     */
    public CharSequence getCallerDisplayName() {
        return mCallerDisplayName;
    }

    /**
     * Obtains the presentation requirements for this {@code RemoteConnection}'s
     * caller's display name.
     *
     * @return The presentation requirements for the caller display name. See
     *         {@link TelecomManager} for valid values.
     */
    public int getCallerDisplayNamePresentation() {
        return mCallerDisplayNamePresentation;
    }

    /**
     * Obtains the video state of this {@code RemoteConnection}.
     *
     * @return The video state of the {@code RemoteConnection}. See {@link VideoProfile.VideoState}.
     * @hide
     */
    public int getVideoState() {
        return mVideoState;
    }

    /**
     * Obtains the video provider of this {@code RemoteConnection}.
     *
     * @return The video provider associated with this {@code RemoteConnection}.
     * @hide
     */
    public final VideoProvider getVideoProvider() {
        return mVideoProvider;
    }

    /**
     * Determines whether this {@code RemoteConnection} is requesting ringback.
     *
     * @return Whether the {@code RemoteConnection} is requesting that the framework play a
     *         ringback tone on its behalf.
     */
    public boolean isRingbackRequested() {
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
     */
    public void answer() {
        try {
            if (mConnected) {
                mConnectionService.answer(mConnectionId);
            }
        } catch (RemoteException ignored) {
        }
    }

    /**
     * Instructs this {@link Connection#STATE_RINGING} {@code RemoteConnection} to answer.
     * @param videoState The video state in which to answer the call.
     * @hide
     */
    public void answer(int videoState) {
        try {
            if (mConnected) {
                mConnectionService.answerVideo(mConnectionId, videoState);
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
     * {@link TelecomManager#DTMF_CHARACTER_WAIT} or {@link TelecomManager#DTMF_CHARACTER_PAUSE}.
     * These digits are immediately sent as DTMF tones to the recipient as soon as the
     * connection is made.
     *
     * If the DTMF string contains a {@link TelecomManager#DTMF_CHARACTER_PAUSE} symbol, this
     * {@code RemoteConnection} will temporarily pause playing the tones for a pre-defined period
     * of time.
     *
     * If the DTMF string contains a {@link TelecomManager#DTMF_CHARACTER_WAIT} symbol, this
     * {@code RemoteConnection} will pause playing the tones and notify callbacks via
     * {@link Callback#onPostDialWait(RemoteConnection, String)}. At this point, the in-call app
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
            for (Callback c: mCallbacks) {
                c.onStateChanged(this, state);
            }
        }
    }

    /**
     * @hide
     */
    void setDisconnected(DisconnectCause disconnectCause) {
        if (mState != Connection.STATE_DISCONNECTED) {
            mState = Connection.STATE_DISCONNECTED;
            mDisconnectCause = disconnectCause;

            for (Callback c : mCallbacks) {
                c.onDisconnected(this, mDisconnectCause);
            }
        }
    }

    /**
     * @hide
     */
    void setRingbackRequested(boolean ringback) {
        if (mRingbackRequested != ringback) {
            mRingbackRequested = ringback;
            for (Callback c : mCallbacks) {
                c.onRingbackRequested(this, ringback);
            }
        }
    }

    /**
     * @hide
     */
    void setConnectionCapabilities(int connectionCapabilities) {
        mConnectionCapabilities = connectionCapabilities;
        for (Callback c : mCallbacks) {
            c.onConnectionCapabilitiesChanged(this, connectionCapabilities);
            c.onCallCapabilitiesChanged(this, connectionCapabilities);
        }
    }

    /**
     * @hide
     */
    void setDestroyed() {
        if (!mCallbacks.isEmpty()) {
            // Make sure that the callbacks are notified that the call is destroyed first.
            if (mState != Connection.STATE_DISCONNECTED) {
                setDisconnected(
                        new DisconnectCause(DisconnectCause.ERROR, "Connection destroyed."));
            }

            for (Callback c : mCallbacks) {
                c.onDestroyed(this);
            }
            mCallbacks.clear();

            mConnected = false;
        }
    }

    /**
     * @hide
     */
    void setPostDialWait(String remainingDigits) {
        for (Callback c : mCallbacks) {
            c.onPostDialWait(this, remainingDigits);
        }
    }

    /**
     * @hide
     */
    void onPostDialChar(char nextChar) {
        for (Callback c : mCallbacks) {
            c.onPostDialChar(this, nextChar);
        }
    }

    /**
     * @hide
     */
    void setVideoState(int videoState) {
        mVideoState = videoState;
        for (Callback c : mCallbacks) {
            c.onVideoStateChanged(this, videoState);
        }
    }

    /**
     * @hide
     */
    void setVideoProvider(VideoProvider videoProvider) {
        mVideoProvider = videoProvider;
        for (Callback c : mCallbacks) {
            c.onVideoProviderChanged(this, videoProvider);
        }
    }

    /** @hide */
    void setIsVoipAudioMode(boolean isVoip) {
        mIsVoipAudioMode = isVoip;
        for (Callback c : mCallbacks) {
            c.onVoipAudioChanged(this, isVoip);
        }
    }

    /** @hide */
    void setStatusHints(StatusHints statusHints) {
        mStatusHints = statusHints;
        for (Callback c : mCallbacks) {
            c.onStatusHintsChanged(this, statusHints);
        }
    }

    /** @hide */
    void setAddress(Uri address, int presentation) {
        mAddress = address;
        mAddressPresentation = presentation;
        for (Callback c : mCallbacks) {
            c.onAddressChanged(this, address, presentation);
        }
    }

    /** @hide */
    void setCallerDisplayName(String callerDisplayName, int presentation) {
        mCallerDisplayName = callerDisplayName;
        mCallerDisplayNamePresentation = presentation;
        for (Callback c : mCallbacks) {
            c.onCallerDisplayNameChanged(this, callerDisplayName, presentation);
        }
    }

    /** @hide */
    void setConferenceableConnections(List<RemoteConnection> conferenceableConnections) {
        mConferenceableConnections.clear();
        mConferenceableConnections.addAll(conferenceableConnections);
        for (Callback c : mCallbacks) {
            c.onConferenceableConnectionsChanged(this, mUnmodifiableconferenceableConnections);
        }
    }

    /** @hide */
    void setConference(RemoteConference conference) {
        if (mConference != conference) {
            mConference = conference;
            for (Callback c : mCallbacks) {
                c.onConferenceChanged(this, conference);
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
    public static RemoteConnection failure(DisconnectCause disconnectCause) {
        return new RemoteConnection(disconnectCause);
    }
}
