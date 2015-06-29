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

import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
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
 */
public final class RemoteConnection {

    /**
     * Callback base class for {@link RemoteConnection}.
     */
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

        /**
         * Handles changes to the {@code RemoteConnection} extras.
         *
         * @param connection The {@code RemoteConnection} invoking this method.
         * @param extras The extras containing other information associated with the connection.
         */
        public void onExtrasChanged(RemoteConnection connection, @Nullable Bundle extras) {}
    }

    /**
     * {@link RemoteConnection.VideoProvider} associated with a {@link RemoteConnection}.  Used to
     * receive video related events and control the video associated with a
     * {@link RemoteConnection}.
     *
     * @see Connection.VideoProvider
     */
    public static class VideoProvider {

        /**
         * Callback class used by the {@link RemoteConnection.VideoProvider} to relay events from
         * the {@link Connection.VideoProvider}.
         */
        public abstract static class Callback {
            /**
             * Reports a session modification request received from the
             * {@link Connection.VideoProvider} associated with a {@link RemoteConnection}.
             *
             * @param videoProvider The {@link RemoteConnection.VideoProvider} invoking this method.
             * @param videoProfile The requested video call profile.
             * @see InCallService.VideoCall.Callback#onSessionModifyRequestReceived(VideoProfile)
             * @see Connection.VideoProvider#receiveSessionModifyRequest(VideoProfile)
             */
            public void onSessionModifyRequestReceived(
                    VideoProvider videoProvider,
                    VideoProfile videoProfile) {}

            /**
             * Reports a session modification response received from the
             * {@link Connection.VideoProvider} associated with a {@link RemoteConnection}.
             *
             * @param videoProvider The {@link RemoteConnection.VideoProvider} invoking this method.
             * @param status Status of the session modify request.
             * @param requestedProfile The original request which was sent to the peer device.
             * @param responseProfile The actual profile changes made by the peer device.
             * @see InCallService.VideoCall.Callback#onSessionModifyResponseReceived(int,
             *      VideoProfile, VideoProfile)
             * @see Connection.VideoProvider#receiveSessionModifyResponse(int, VideoProfile,
             *      VideoProfile)
             */
            public void onSessionModifyResponseReceived(
                    VideoProvider videoProvider,
                    int status,
                    VideoProfile requestedProfile,
                    VideoProfile responseProfile) {}

            /**
             * Reports a call session event received from the {@link Connection.VideoProvider}
             * associated with a {@link RemoteConnection}.
             *
             * @param videoProvider The {@link RemoteConnection.VideoProvider} invoking this method.
             * @param event The event.
             * @see InCallService.VideoCall.Callback#onCallSessionEvent(int)
             * @see Connection.VideoProvider#handleCallSessionEvent(int)
             */
            public void onCallSessionEvent(VideoProvider videoProvider, int event) {}

            /**
             * Reports a change in the peer video dimensions received from the
             * {@link Connection.VideoProvider} associated with a {@link RemoteConnection}.
             *
             * @param videoProvider The {@link RemoteConnection.VideoProvider} invoking this method.
             * @param width  The updated peer video width.
             * @param height The updated peer video height.
             * @see InCallService.VideoCall.Callback#onPeerDimensionsChanged(int, int)
             * @see Connection.VideoProvider#changePeerDimensions(int, int)
             */
            public void onPeerDimensionsChanged(VideoProvider videoProvider, int width,
                    int height) {}

            /**
             * Reports a change in the data usage (in bytes) received from the
             * {@link Connection.VideoProvider} associated with a {@link RemoteConnection}.
             *
             * @param videoProvider The {@link RemoteConnection.VideoProvider} invoking this method.
             * @param dataUsage The updated data usage (in bytes).
             * @see InCallService.VideoCall.Callback#onCallDataUsageChanged(long)
             * @see Connection.VideoProvider#setCallDataUsage(long)
             */
            public void onCallDataUsageChanged(VideoProvider videoProvider, long dataUsage) {}

            /**
             * Reports a change in the capabilities of the current camera, received from the
             * {@link Connection.VideoProvider} associated with a {@link RemoteConnection}.
             *
             * @param videoProvider The {@link RemoteConnection.VideoProvider} invoking this method.
             * @param cameraCapabilities The changed camera capabilities.
             * @see InCallService.VideoCall.Callback#onCameraCapabilitiesChanged(
             *      VideoProfile.CameraCapabilities)
             * @see Connection.VideoProvider#changeCameraCapabilities(
             *      VideoProfile.CameraCapabilities)
             */
            public void onCameraCapabilitiesChanged(
                    VideoProvider videoProvider,
                    VideoProfile.CameraCapabilities cameraCapabilities) {}

            /**
             * Reports a change in the video quality received from the
             * {@link Connection.VideoProvider} associated with a {@link RemoteConnection}.
             *
             * @param videoProvider The {@link RemoteConnection.VideoProvider} invoking this method.
             * @param videoQuality  The updated peer video quality.
             * @see InCallService.VideoCall.Callback#onVideoQualityChanged(int)
             * @see Connection.VideoProvider#changeVideoQuality(int)
             */
            public void onVideoQualityChanged(VideoProvider videoProvider, int videoQuality) {}
        }

        private final IVideoCallback mVideoCallbackDelegate = new IVideoCallback() {
            @Override
            public void receiveSessionModifyRequest(VideoProfile videoProfile) {
                for (Callback l : mCallbacks) {
                    l.onSessionModifyRequestReceived(VideoProvider.this, videoProfile);
                }
            }

            @Override
            public void receiveSessionModifyResponse(int status, VideoProfile requestedProfile,
                    VideoProfile responseProfile) {
                for (Callback l : mCallbacks) {
                    l.onSessionModifyResponseReceived(
                            VideoProvider.this,
                            status,
                            requestedProfile,
                            responseProfile);
                }
            }

            @Override
            public void handleCallSessionEvent(int event) {
                for (Callback l : mCallbacks) {
                    l.onCallSessionEvent(VideoProvider.this, event);
                }
            }

            @Override
            public void changePeerDimensions(int width, int height) {
                for (Callback l : mCallbacks) {
                    l.onPeerDimensionsChanged(VideoProvider.this, width, height);
                }
            }

            @Override
            public void changeCallDataUsage(long dataUsage) {
                for (Callback l : mCallbacks) {
                    l.onCallDataUsageChanged(VideoProvider.this, dataUsage);
                }
            }

            @Override
            public void changeCameraCapabilities(
                    VideoProfile.CameraCapabilities cameraCapabilities) {
                for (Callback l : mCallbacks) {
                    l.onCameraCapabilitiesChanged(VideoProvider.this, cameraCapabilities);
                }
            }

            @Override
            public void changeVideoQuality(int videoQuality) {
                for (Callback l : mCallbacks) {
                    l.onVideoQualityChanged(VideoProvider.this, videoQuality);
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
        private final Set<Callback> mCallbacks = Collections.newSetFromMap(
                new ConcurrentHashMap<Callback, Boolean>(8, 0.9f, 1));

        VideoProvider(IVideoProvider videoProviderBinder) {
            mVideoProviderBinder = videoProviderBinder;
            try {
                mVideoProviderBinder.addVideoCallback(mVideoCallbackServant.getStub().asBinder());
            } catch (RemoteException e) {
            }
        }

        /**
         * Registers a callback to receive commands and state changes for video calls.
         *
         * @param l The video call callback.
         */
        public void registerCallback(Callback l) {
            mCallbacks.add(l);
        }

        /**
         * Clears the video call callback set via {@link #registerCallback}.
         *
         * @param l The video call callback to clear.
         */
        public void unregisterCallback(Callback l) {
            mCallbacks.remove(l);
        }

        /**
         * Sets the camera to be used for the outgoing video for the
         * {@link RemoteConnection.VideoProvider}.
         *
         * @param cameraId The id of the camera (use ids as reported by
         * {@link CameraManager#getCameraIdList()}).
         * @see Connection.VideoProvider#onSetCamera(String)
         */
        public void setCamera(String cameraId) {
            try {
                mVideoProviderBinder.setCamera(cameraId);
            } catch (RemoteException e) {
            }
        }

        /**
         * Sets the surface to be used for displaying a preview of what the user's camera is
         * currently capturing for the {@link RemoteConnection.VideoProvider}.
         *
         * @param surface The {@link Surface}.
         * @see Connection.VideoProvider#onSetPreviewSurface(Surface)
         */
        public void setPreviewSurface(Surface surface) {
            try {
                mVideoProviderBinder.setPreviewSurface(surface);
            } catch (RemoteException e) {
            }
        }

        /**
         * Sets the surface to be used for displaying the video received from the remote device for
         * the {@link RemoteConnection.VideoProvider}.
         *
         * @param surface The {@link Surface}.
         * @see Connection.VideoProvider#onSetDisplaySurface(Surface)
         */
        public void setDisplaySurface(Surface surface) {
            try {
                mVideoProviderBinder.setDisplaySurface(surface);
            } catch (RemoteException e) {
            }
        }

        /**
         * Sets the device orientation, in degrees, for the {@link RemoteConnection.VideoProvider}.
         * Assumes that a standard portrait orientation of the device is 0 degrees.
         *
         * @param rotation The device orientation, in degrees.
         * @see Connection.VideoProvider#onSetDeviceOrientation(int)
         */
        public void setDeviceOrientation(int rotation) {
            try {
                mVideoProviderBinder.setDeviceOrientation(rotation);
            } catch (RemoteException e) {
            }
        }

        /**
         * Sets camera zoom ratio for the {@link RemoteConnection.VideoProvider}.
         *
         * @param value The camera zoom ratio.
         * @see Connection.VideoProvider#onSetZoom(float)
         */
        public void setZoom(float value) {
            try {
                mVideoProviderBinder.setZoom(value);
            } catch (RemoteException e) {
            }
        }

        /**
         * Issues a request to modify the properties of the current video session for the
         * {@link RemoteConnection.VideoProvider}.
         *
         * @param fromProfile The video profile prior to the request.
         * @param toProfile The video profile with the requested changes made.
         * @see Connection.VideoProvider#onSendSessionModifyRequest(VideoProfile, VideoProfile)
         */
        public void sendSessionModifyRequest(VideoProfile fromProfile, VideoProfile toProfile) {
            try {
                mVideoProviderBinder.sendSessionModifyRequest(fromProfile, toProfile);
            } catch (RemoteException e) {
            }
        }

        /**
         * Provides a response to a request to change the current call video session
         * properties for the {@link RemoteConnection.VideoProvider}.
         *
         * @param responseProfile The response call video properties.
         * @see Connection.VideoProvider#onSendSessionModifyResponse(VideoProfile)
         */
        public void sendSessionModifyResponse(VideoProfile responseProfile) {
            try {
                mVideoProviderBinder.sendSessionModifyResponse(responseProfile);
            } catch (RemoteException e) {
            }
        }

        /**
         * Issues a request to retrieve the capabilities of the current camera for the
         * {@link RemoteConnection.VideoProvider}.
         *
         * @see Connection.VideoProvider#onRequestCameraCapabilities()
         */
        public void requestCameraCapabilities() {
            try {
                mVideoProviderBinder.requestCameraCapabilities();
            } catch (RemoteException e) {
            }
        }

        /**
         * Issues a request to retrieve the data usage (in bytes) of the video portion of the
         * {@link RemoteConnection} for the {@link RemoteConnection.VideoProvider}.
         *
         * @see Connection.VideoProvider#onRequestConnectionDataUsage()
         */
        public void requestCallDataUsage() {
            try {
                mVideoProviderBinder.requestCallDataUsage();
            } catch (RemoteException e) {
            }
        }

        /**
         * Sets the {@link Uri} of an image to be displayed to the peer device when the video signal
         * is paused, for the {@link RemoteConnection.VideoProvider}.
         *
         * @see Connection.VideoProvider#onSetPauseImage(Uri)
         */
        public void setPauseImage(Uri uri) {
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
    private final Set<CallbackRecord> mCallbackRecords = Collections.newSetFromMap(
            new ConcurrentHashMap<CallbackRecord, Boolean>(8, 0.9f, 1));
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
    private Bundle mExtras;

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
        registerCallback(callback, new Handler());
    }

    /**
     * Adds a callback to this {@code RemoteConnection}.
     *
     * @param callback A {@code Callback}.
     * @param handler A {@code Handler} which command and status changes will be delivered to.
     */
    public void registerCallback(Callback callback, Handler handler) {
        unregisterCallback(callback);
        if (callback != null && handler != null) {
            mCallbackRecords.add(new CallbackRecord(callback, handler));
        }
    }

    /**
     * Removes a callback from this {@code RemoteConnection}.
     *
     * @param callback A {@code Callback}.
     */
    public void unregisterCallback(Callback callback) {
        if (callback != null) {
            for (CallbackRecord record : mCallbackRecords) {
                if (record.getCallback() == callback) {
                    mCallbackRecords.remove(record);
                    break;
                }
            }
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
     * @return The video state of the {@code RemoteConnection}. See {@link VideoProfile}.
     */
    public int getVideoState() {
        return mVideoState;
    }

    /**
     * Obtains the video provider of this {@code RemoteConnection}.
     * @return The video provider associated with this {@code RemoteConnection}.
     */
    public final VideoProvider getVideoProvider() {
        return mVideoProvider;
    }

    /**
     * Obtain the extras associated with this {@code RemoteConnection}.
     *
     * @return The extras for this connection.
     */
    public final Bundle getExtras() {
        return mExtras;
    }

    /**
     * Determines whether this {@code RemoteConnection} is requesting ringback.
     *
     * @return Whether the {@code RemoteConnection} is requesting that the framework play a
     *         ringback tone on its behalf.
     */
    public boolean isRingbackRequested() {
        return mRingbackRequested;
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
     * @hide
     * @deprecated Use {@link #setCallAudioState(CallAudioState) instead.
     */
    @SystemApi
    @Deprecated
    public void setAudioState(AudioState state) {
        setCallAudioState(new CallAudioState(state));
    }

    /**
     * Set the audio state of this {@code RemoteConnection}.
     *
     * @param state The audio state of this {@code RemoteConnection}.
     */
    public void setCallAudioState(CallAudioState state) {
        try {
            if (mConnected) {
                mConnectionService.onCallAudioStateChanged(mConnectionId, state);
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
    void setState(final int state) {
        if (mState != state) {
            mState = state;
            for (CallbackRecord record: mCallbackRecords) {
                final RemoteConnection connection = this;
                final Callback callback = record.getCallback();
                record.getHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onStateChanged(connection, state);
                    }
                });
            }
        }
    }

    /**
     * @hide
     */
    void setDisconnected(final DisconnectCause disconnectCause) {
        if (mState != Connection.STATE_DISCONNECTED) {
            mState = Connection.STATE_DISCONNECTED;
            mDisconnectCause = disconnectCause;

            for (CallbackRecord record : mCallbackRecords) {
                final RemoteConnection connection = this;
                final Callback callback = record.getCallback();
                record.getHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onDisconnected(connection, disconnectCause);
                    }
                });
            }
        }
    }

    /**
     * @hide
     */
    void setRingbackRequested(final boolean ringback) {
        if (mRingbackRequested != ringback) {
            mRingbackRequested = ringback;
            for (CallbackRecord record : mCallbackRecords) {
                final RemoteConnection connection = this;
                final Callback callback = record.getCallback();
                record.getHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onRingbackRequested(connection, ringback);
                    }
                });
            }
        }
    }

    /**
     * @hide
     */
    void setConnectionCapabilities(final int connectionCapabilities) {
        mConnectionCapabilities = connectionCapabilities;
        for (CallbackRecord record : mCallbackRecords) {
            final RemoteConnection connection = this;
            final Callback callback = record.getCallback();
            record.getHandler().post(new Runnable() {
                @Override
                public void run() {
                    callback.onConnectionCapabilitiesChanged(connection, connectionCapabilities);
                }
            });
        }
    }

    /**
     * @hide
     */
    void setDestroyed() {
        if (!mCallbackRecords.isEmpty()) {
            // Make sure that the callbacks are notified that the call is destroyed first.
            if (mState != Connection.STATE_DISCONNECTED) {
                setDisconnected(
                        new DisconnectCause(DisconnectCause.ERROR, "Connection destroyed."));
            }

            for (CallbackRecord record : mCallbackRecords) {
                final RemoteConnection connection = this;
                final Callback callback = record.getCallback();
                record.getHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onDestroyed(connection);
                    }
                });
            }
            mCallbackRecords.clear();

            mConnected = false;
        }
    }

    /**
     * @hide
     */
    void setPostDialWait(final String remainingDigits) {
        for (CallbackRecord record : mCallbackRecords) {
            final RemoteConnection connection = this;
            final Callback callback = record.getCallback();
            record.getHandler().post(new Runnable() {
                @Override
                public void run() {
                    callback.onPostDialWait(connection, remainingDigits);
                }
            });
        }
    }

    /**
     * @hide
     */
    void onPostDialChar(final char nextChar) {
        for (CallbackRecord record : mCallbackRecords) {
            final RemoteConnection connection = this;
            final Callback callback = record.getCallback();
            record.getHandler().post(new Runnable() {
                @Override
                public void run() {
                    callback.onPostDialChar(connection, nextChar);
                }
            });
        }
    }

    /**
     * @hide
     */
    void setVideoState(final int videoState) {
        mVideoState = videoState;
        for (CallbackRecord record : mCallbackRecords) {
            final RemoteConnection connection = this;
            final Callback callback = record.getCallback();
            record.getHandler().post(new Runnable() {
                @Override
                public void run() {
                    callback.onVideoStateChanged(connection, videoState);
                }
            });
        }
    }

    /**
     * @hide
     */
    void setVideoProvider(final VideoProvider videoProvider) {
        mVideoProvider = videoProvider;
        for (CallbackRecord record : mCallbackRecords) {
            final RemoteConnection connection = this;
            final Callback callback = record.getCallback();
            record.getHandler().post(new Runnable() {
                @Override
                public void run() {
                    callback.onVideoProviderChanged(connection, videoProvider);
                }
            });
        }
    }

    /** @hide */
    void setIsVoipAudioMode(final boolean isVoip) {
        mIsVoipAudioMode = isVoip;
        for (CallbackRecord record : mCallbackRecords) {
            final RemoteConnection connection = this;
            final Callback callback = record.getCallback();
            record.getHandler().post(new Runnable() {
                @Override
                public void run() {
                    callback.onVoipAudioChanged(connection, isVoip);
                }
            });
        }
    }

    /** @hide */
    void setStatusHints(final StatusHints statusHints) {
        mStatusHints = statusHints;
        for (CallbackRecord record : mCallbackRecords) {
            final RemoteConnection connection = this;
            final Callback callback = record.getCallback();
            record.getHandler().post(new Runnable() {
                @Override
                public void run() {
                    callback.onStatusHintsChanged(connection, statusHints);
                }
            });
        }
    }

    /** @hide */
    void setAddress(final Uri address, final int presentation) {
        mAddress = address;
        mAddressPresentation = presentation;
        for (CallbackRecord record : mCallbackRecords) {
            final RemoteConnection connection = this;
            final Callback callback = record.getCallback();
            record.getHandler().post(new Runnable() {
                @Override
                public void run() {
                    callback.onAddressChanged(connection, address, presentation);
                }
            });
        }
    }

    /** @hide */
    void setCallerDisplayName(final String callerDisplayName, final int presentation) {
        mCallerDisplayName = callerDisplayName;
        mCallerDisplayNamePresentation = presentation;
        for (CallbackRecord record : mCallbackRecords) {
            final RemoteConnection connection = this;
            final Callback callback = record.getCallback();
            record.getHandler().post(new Runnable() {
                @Override
                public void run() {
                    callback.onCallerDisplayNameChanged(
                            connection, callerDisplayName, presentation);
                }
            });
        }
    }

    /** @hide */
    void setConferenceableConnections(final List<RemoteConnection> conferenceableConnections) {
        mConferenceableConnections.clear();
        mConferenceableConnections.addAll(conferenceableConnections);
        for (CallbackRecord record : mCallbackRecords) {
            final RemoteConnection connection = this;
            final Callback callback = record.getCallback();
            record.getHandler().post(new Runnable() {
                @Override
                public void run() {
                    callback.onConferenceableConnectionsChanged(
                            connection, mUnmodifiableconferenceableConnections);
                }
            });
        }
    }

    /** @hide */
    void setConference(final RemoteConference conference) {
        if (mConference != conference) {
            mConference = conference;
            for (CallbackRecord record : mCallbackRecords) {
                final RemoteConnection connection = this;
                final Callback callback = record.getCallback();
                record.getHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onConferenceChanged(connection, conference);
                    }
                });
            }
        }
    }

    /** @hide */
    void setExtras(final Bundle extras) {
        mExtras = extras;
        for (CallbackRecord record : mCallbackRecords) {
            final RemoteConnection connection = this;
            final Callback callback = record.getCallback();
            record.getHandler().post(new Runnable() {
                @Override
                public void run() {
                    callback.onExtrasChanged(connection, extras);
                }
            });
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

    private static final class CallbackRecord extends Callback {
        private final Callback mCallback;
        private final Handler mHandler;

        public CallbackRecord(Callback callback, Handler handler) {
            mCallback = callback;
            mHandler = handler;
        }

        public Callback getCallback() {
            return mCallback;
        }

        public Handler getHandler() {
            return mHandler;
        }
    }
}
