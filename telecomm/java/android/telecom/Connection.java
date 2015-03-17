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

import com.android.internal.telecom.IVideoCallback;
import com.android.internal.telecom.IVideoProvider;

import android.annotation.SystemApi;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a connection to a remote endpoint that carries voice traffic.
 * <p>
 * Implementations create a custom subclass of {@code Connection} and return it to the framework
 * as the return value of
 * {@link ConnectionService#onCreateIncomingConnection(PhoneAccountHandle, ConnectionRequest)}
 * or
 * {@link ConnectionService#onCreateOutgoingConnection(PhoneAccountHandle, ConnectionRequest)}.
 * Implementations are then responsible for updating the state of the {@code Connection}, and
 * must call {@link #destroy()} to signal to the framework that the {@code Connection} is no
 * longer used and associated resources may be recovered.
 * @hide
 */
@SystemApi
public abstract class Connection implements IConferenceable {

    public static final int STATE_INITIALIZING = 0;

    public static final int STATE_NEW = 1;

    public static final int STATE_RINGING = 2;

    public static final int STATE_DIALING = 3;

    public static final int STATE_ACTIVE = 4;

    public static final int STATE_HOLDING = 5;

    public static final int STATE_DISCONNECTED = 6;

    /** Connection can currently be put on hold or unheld. */
    public static final int CAPABILITY_HOLD = 0x00000001;

    /** Connection supports the hold feature. */
    public static final int CAPABILITY_SUPPORT_HOLD = 0x00000002;

    /**
     * Connections within a conference can be merged. A {@link ConnectionService} has the option to
     * add a {@link Conference} before the child {@link Connection}s are merged. This is how
     * CDMA-based {@link Connection}s are implemented. For these unmerged {@link Conference}s, this
     * capability allows a merge button to be shown while the conference is in the foreground
     * of the in-call UI.
     * <p>
     * This is only intended for use by a {@link Conference}.
     */
    public static final int CAPABILITY_MERGE_CONFERENCE = 0x00000004;

    /**
     * Connections within a conference can be swapped between foreground and background.
     * See {@link #CAPABILITY_MERGE_CONFERENCE} for additional information.
     * <p>
     * This is only intended for use by a {@link Conference}.
     */
    public static final int CAPABILITY_SWAP_CONFERENCE = 0x00000008;

    /**
     * @hide
     */
    public static final int CAPABILITY_UNUSED = 0x00000010;

    /** Connection supports responding via text option. */
    public static final int CAPABILITY_RESPOND_VIA_TEXT = 0x00000020;

    /** Connection can be muted. */
    public static final int CAPABILITY_MUTE = 0x00000040;

    /**
     * Connection supports conference management. This capability only applies to
     * {@link Conference}s which can have {@link Connection}s as children.
     */
    public static final int CAPABILITY_MANAGE_CONFERENCE = 0x00000080;

    /**
     * Local device supports video telephony.
     * @hide
     */
    public static final int CAPABILITY_SUPPORTS_VT_LOCAL = 0x00000100;

    /**
     * Remote device supports video telephony.
     * @hide
     */
    public static final int CAPABILITY_SUPPORTS_VT_REMOTE = 0x00000200;

    /**
     * Connection is using high definition audio.
     * @hide
     */
    public static final int CAPABILITY_HIGH_DEF_AUDIO = 0x00000400;

    /**
     * Connection is using WIFI.
     * @hide
     */
    public static final int CAPABILITY_WIFI = 0x00000800;

    /**
     * Connection is able to be separated from its parent {@code Conference}, if any.
     */
    public static final int CAPABILITY_SEPARATE_FROM_CONFERENCE = 0x00001000;

    /**
     * Connection is able to be individually disconnected when in a {@code Conference}.
     */
    public static final int CAPABILITY_DISCONNECT_FROM_CONFERENCE = 0x00002000;

    /**
     * Whether the call is a generic conference, where we do not know the precise state of
     * participants in the conference (eg. on CDMA).
     *
     * @hide
     */
    public static final int CAPABILITY_GENERIC_CONFERENCE = 0x00004000;

    /**
     * Speed up audio setup for MT call.
     * @hide
    */
    public static final int CAPABILITY_SPEED_UP_MT_AUDIO = 0x00008000;

    // Flag controlling whether PII is emitted into the logs
    private static final boolean PII_DEBUG = Log.isLoggable(android.util.Log.DEBUG);

    /**
     * Whether the given capabilities support the specified capability.
     *
     * @param capabilities A capability bit field.
     * @param capability The capability to check capabilities for.
     * @return Whether the specified capability is supported.
     * @hide
     */
    public static boolean can(int capabilities, int capability) {
        return (capabilities & capability) != 0;
    }

    /**
     * Whether the capabilities of this {@code Connection} supports the specified capability.
     *
     * @param capability The capability to check capabilities for.
     * @return Whether the specified capability is supported.
     * @hide
     */
    public boolean can(int capability) {
        return can(mConnectionCapabilities, capability);
    }

    /**
     * Removes the specified capability from the set of capabilities of this {@code Connection}.
     *
     * @param capability The capability to remove from the set.
     * @hide
     */
    public void removeCapability(int capability) {
        mConnectionCapabilities &= ~capability;
    }

    /**
     * Adds the specified capability to the set of capabilities of this {@code Connection}.
     *
     * @param capability The capability to add to the set.
     * @hide
     */
    public void addCapability(int capability) {
        mConnectionCapabilities |= capability;
    }


    public static String capabilitiesToString(int capabilities) {
        StringBuilder builder = new StringBuilder();
        builder.append("[Capabilities:");
        if (can(capabilities, CAPABILITY_HOLD)) {
            builder.append(" CAPABILITY_HOLD");
        }
        if (can(capabilities, CAPABILITY_SUPPORT_HOLD)) {
            builder.append(" CAPABILITY_SUPPORT_HOLD");
        }
        if (can(capabilities, CAPABILITY_MERGE_CONFERENCE)) {
            builder.append(" CAPABILITY_MERGE_CONFERENCE");
        }
        if (can(capabilities, CAPABILITY_SWAP_CONFERENCE)) {
            builder.append(" CAPABILITY_SWAP_CONFERENCE");
        }
        if (can(capabilities, CAPABILITY_RESPOND_VIA_TEXT)) {
            builder.append(" CAPABILITY_RESPOND_VIA_TEXT");
        }
        if (can(capabilities, CAPABILITY_MUTE)) {
            builder.append(" CAPABILITY_MUTE");
        }
        if (can(capabilities, CAPABILITY_MANAGE_CONFERENCE)) {
            builder.append(" CAPABILITY_MANAGE_CONFERENCE");
        }
        if (can(capabilities, CAPABILITY_SUPPORTS_VT_LOCAL)) {
            builder.append(" CAPABILITY_SUPPORTS_VT_LOCAL");
        }
        if (can(capabilities, CAPABILITY_SUPPORTS_VT_REMOTE)) {
            builder.append(" CAPABILITY_SUPPORTS_VT_REMOTE");
        }
        if (can(capabilities, CAPABILITY_HIGH_DEF_AUDIO)) {
            builder.append(" CAPABILITY_HIGH_DEF_AUDIO");
        }
        if (can(capabilities, CAPABILITY_WIFI)) {
            builder.append(" CAPABILITY_WIFI");
        }
        if (can(capabilities, CAPABILITY_GENERIC_CONFERENCE)) {
            builder.append(" CAPABILITY_GENERIC_CONFERENCE");
        }
        if (can(capabilities, CAPABILITY_SPEED_UP_MT_AUDIO)) {
            builder.append(" CAPABILITY_SPEED_UP_IMS_MT_AUDIO");
        }
        builder.append("]");
        return builder.toString();
    }

    /** @hide */
    public abstract static class Listener {
        public void onStateChanged(Connection c, int state) {}
        public void onAddressChanged(Connection c, Uri newAddress, int presentation) {}
        public void onCallerDisplayNameChanged(
                Connection c, String callerDisplayName, int presentation) {}
        public void onVideoStateChanged(Connection c, int videoState) {}
        public void onDisconnected(Connection c, DisconnectCause disconnectCause) {}
        public void onPostDialWait(Connection c, String remaining) {}
        public void onPostDialChar(Connection c, char nextChar) {}
        public void onRingbackRequested(Connection c, boolean ringback) {}
        public void onDestroyed(Connection c) {}
        public void onConnectionCapabilitiesChanged(Connection c, int capabilities) {}
        public void onVideoProviderChanged(
                Connection c, VideoProvider videoProvider) {}
        public void onAudioModeIsVoipChanged(Connection c, boolean isVoip) {}
        public void onStatusHintsChanged(Connection c, StatusHints statusHints) {}
        public void onConferenceablesChanged(
                Connection c, List<IConferenceable> conferenceables) {}
        public void onConferenceChanged(Connection c, Conference conference) {}
        /** @hide */
        public void onConferenceParticipantsChanged(Connection c,
                List<ConferenceParticipant> participants) {}
        public void onConferenceStarted() {}
    }

    /** @hide */
    public static abstract class VideoProvider {

        /**
         * Video is not being received (no protocol pause was issued).
         */
        public static final int SESSION_EVENT_RX_PAUSE = 1;

        /**
         * Video reception has resumed after a SESSION_EVENT_RX_PAUSE.
         */
        public static final int SESSION_EVENT_RX_RESUME = 2;

        /**
         * Video transmission has begun. This occurs after a negotiated start of video transmission
         * when the underlying protocol has actually begun transmitting video to the remote party.
         */
        public static final int SESSION_EVENT_TX_START = 3;

        /**
         * Video transmission has stopped. This occurs after a negotiated stop of video transmission
         * when the underlying protocol has actually stopped transmitting video to the remote party.
         */
        public static final int SESSION_EVENT_TX_STOP = 4;

        /**
         * A camera failure has occurred for the selected camera.  The In-Call UI can use this as a
         * cue to inform the user the camera is not available.
         */
        public static final int SESSION_EVENT_CAMERA_FAILURE = 5;

        /**
         * Issued after {@code SESSION_EVENT_CAMERA_FAILURE} when the camera is once again ready for
         * operation.  The In-Call UI can use this as a cue to inform the user that the camera has
         * become available again.
         */
        public static final int SESSION_EVENT_CAMERA_READY = 6;

        /**
         * Session modify request was successful.
         */
        public static final int SESSION_MODIFY_REQUEST_SUCCESS = 1;

        /**
         * Session modify request failed.
         */
        public static final int SESSION_MODIFY_REQUEST_FAIL = 2;

        /**
         * Session modify request ignored due to invalid parameters.
         */
        public static final int SESSION_MODIFY_REQUEST_INVALID = 3;

        private static final int MSG_SET_VIDEO_CALLBACK = 1;
        private static final int MSG_SET_CAMERA = 2;
        private static final int MSG_SET_PREVIEW_SURFACE = 3;
        private static final int MSG_SET_DISPLAY_SURFACE = 4;
        private static final int MSG_SET_DEVICE_ORIENTATION = 5;
        private static final int MSG_SET_ZOOM = 6;
        private static final int MSG_SEND_SESSION_MODIFY_REQUEST = 7;
        private static final int MSG_SEND_SESSION_MODIFY_RESPONSE = 8;
        private static final int MSG_REQUEST_CAMERA_CAPABILITIES = 9;
        private static final int MSG_REQUEST_CONNECTION_DATA_USAGE = 10;
        private static final int MSG_SET_PAUSE_IMAGE = 11;

        private final VideoProvider.VideoProviderHandler
                mMessageHandler = new VideoProvider.VideoProviderHandler();
        private final VideoProvider.VideoProviderBinder mBinder;
        private IVideoCallback mVideoCallback;

        /**
         * Default handler used to consolidate binder method calls onto a single thread.
         */
        private final class VideoProviderHandler extends Handler {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_SET_VIDEO_CALLBACK:
                        mVideoCallback = IVideoCallback.Stub.asInterface((IBinder) msg.obj);
                        break;
                    case MSG_SET_CAMERA:
                        onSetCamera((String) msg.obj);
                        break;
                    case MSG_SET_PREVIEW_SURFACE:
                        onSetPreviewSurface((Surface) msg.obj);
                        break;
                    case MSG_SET_DISPLAY_SURFACE:
                        onSetDisplaySurface((Surface) msg.obj);
                        break;
                    case MSG_SET_DEVICE_ORIENTATION:
                        onSetDeviceOrientation(msg.arg1);
                        break;
                    case MSG_SET_ZOOM:
                        onSetZoom((Float) msg.obj);
                        break;
                    case MSG_SEND_SESSION_MODIFY_REQUEST:
                        onSendSessionModifyRequest((VideoProfile) msg.obj);
                        break;
                    case MSG_SEND_SESSION_MODIFY_RESPONSE:
                        onSendSessionModifyResponse((VideoProfile) msg.obj);
                        break;
                    case MSG_REQUEST_CAMERA_CAPABILITIES:
                        onRequestCameraCapabilities();
                        break;
                    case MSG_REQUEST_CONNECTION_DATA_USAGE:
                        onRequestConnectionDataUsage();
                        break;
                    case MSG_SET_PAUSE_IMAGE:
                        onSetPauseImage((String) msg.obj);
                        break;
                    default:
                        break;
                }
            }
        }

        /**
         * IVideoProvider stub implementation.
         */
        private final class VideoProviderBinder extends IVideoProvider.Stub {
            public void setVideoCallback(IBinder videoCallbackBinder) {
                mMessageHandler.obtainMessage(
                        MSG_SET_VIDEO_CALLBACK, videoCallbackBinder).sendToTarget();
            }

            public void setCamera(String cameraId) {
                mMessageHandler.obtainMessage(MSG_SET_CAMERA, cameraId).sendToTarget();
            }

            public void setPreviewSurface(Surface surface) {
                mMessageHandler.obtainMessage(MSG_SET_PREVIEW_SURFACE, surface).sendToTarget();
            }

            public void setDisplaySurface(Surface surface) {
                mMessageHandler.obtainMessage(MSG_SET_DISPLAY_SURFACE, surface).sendToTarget();
            }

            public void setDeviceOrientation(int rotation) {
                mMessageHandler.obtainMessage(MSG_SET_DEVICE_ORIENTATION, rotation).sendToTarget();
            }

            public void setZoom(float value) {
                mMessageHandler.obtainMessage(MSG_SET_ZOOM, value).sendToTarget();
            }

            public void sendSessionModifyRequest(VideoProfile requestProfile) {
                mMessageHandler.obtainMessage(
                        MSG_SEND_SESSION_MODIFY_REQUEST, requestProfile).sendToTarget();
            }

            public void sendSessionModifyResponse(VideoProfile responseProfile) {
                mMessageHandler.obtainMessage(
                        MSG_SEND_SESSION_MODIFY_RESPONSE, responseProfile).sendToTarget();
            }

            public void requestCameraCapabilities() {
                mMessageHandler.obtainMessage(MSG_REQUEST_CAMERA_CAPABILITIES).sendToTarget();
            }

            public void requestCallDataUsage() {
                mMessageHandler.obtainMessage(MSG_REQUEST_CONNECTION_DATA_USAGE).sendToTarget();
            }

            public void setPauseImage(String uri) {
                mMessageHandler.obtainMessage(MSG_SET_PAUSE_IMAGE, uri).sendToTarget();
            }
        }

        public VideoProvider() {
            mBinder = new VideoProvider.VideoProviderBinder();
        }

        /**
         * Returns binder object which can be used across IPC methods.
         * @hide
         */
        public final IVideoProvider getInterface() {
            return mBinder;
        }

        /**
         * Sets the camera to be used for video recording in a video connection.
         *
         * @param cameraId The id of the camera.
         */
        public abstract void onSetCamera(String cameraId);

        /**
         * Sets the surface to be used for displaying a preview of what the user's camera is
         * currently capturing.  When video transmission is enabled, this is the video signal which
         * is sent to the remote device.
         *
         * @param surface The surface.
         */
        public abstract void onSetPreviewSurface(Surface surface);

        /**
         * Sets the surface to be used for displaying the video received from the remote device.
         *
         * @param surface The surface.
         */
        public abstract void onSetDisplaySurface(Surface surface);

        /**
         * Sets the device orientation, in degrees.  Assumes that a standard portrait orientation of
         * the device is 0 degrees.
         *
         * @param rotation The device orientation, in degrees.
         */
        public abstract void onSetDeviceOrientation(int rotation);

        /**
         * Sets camera zoom ratio.
         *
         * @param value The camera zoom ratio.
         */
        public abstract void onSetZoom(float value);

        /**
         * Issues a request to modify the properties of the current session.  The request is
         * sent to the remote device where it it handled by the In-Call UI.
         * Some examples of session modification requests: upgrade connection from audio to video,
         * downgrade connection from video to audio, pause video.
         *
         * @param requestProfile The requested connection video properties.
         */
        public abstract void onSendSessionModifyRequest(VideoProfile requestProfile);

        /**te
         * Provides a response to a request to change the current connection session video
         * properties.
         * This is in response to a request the InCall UI has received via the InCall UI.
         *
         * @param responseProfile The response connection video properties.
         */
        public abstract void onSendSessionModifyResponse(VideoProfile responseProfile);

        /**
         * Issues a request to the video provider to retrieve the camera capabilities.
         * Camera capabilities are reported back to the caller via the In-Call UI.
         */
        public abstract void onRequestCameraCapabilities();

        /**
         * Issues a request to the video telephony framework to retrieve the cumulative data usage
         * for the current connection.  Data usage is reported back to the caller via the
         * InCall UI.
         */
        public abstract void onRequestConnectionDataUsage();

        /**
         * Provides the video telephony framework with the URI of an image to be displayed to remote
         * devices when the video signal is paused.
         *
         * @param uri URI of image to display.
         */
        public abstract void onSetPauseImage(String uri);

        /**
         * Invokes callback method defined in In-Call UI.
         *
         * @param videoProfile The requested video connection profile.
         */
        public void receiveSessionModifyRequest(VideoProfile videoProfile) {
            if (mVideoCallback != null) {
                try {
                    mVideoCallback.receiveSessionModifyRequest(videoProfile);
                } catch (RemoteException ignored) {
                }
            }
        }

        /**
         * Invokes callback method defined in In-Call UI.
         *
         * @param status Status of the session modify request.  Valid values are
         *               {@link VideoProvider#SESSION_MODIFY_REQUEST_SUCCESS},
         *               {@link VideoProvider#SESSION_MODIFY_REQUEST_FAIL},
         *               {@link VideoProvider#SESSION_MODIFY_REQUEST_INVALID}
         * @param requestedProfile The original request which was sent to the remote device.
         * @param responseProfile The actual profile changes made by the remote device.
         */
        public void receiveSessionModifyResponse(int status,
                VideoProfile requestedProfile, VideoProfile responseProfile) {
            if (mVideoCallback != null) {
                try {
                    mVideoCallback.receiveSessionModifyResponse(
                            status, requestedProfile, responseProfile);
                } catch (RemoteException ignored) {
                }
            }
        }

        /**
         * Invokes callback method defined in In-Call UI.
         *
         * Valid values are: {@link VideoProvider#SESSION_EVENT_RX_PAUSE},
         * {@link VideoProvider#SESSION_EVENT_RX_RESUME},
         * {@link VideoProvider#SESSION_EVENT_TX_START},
         * {@link VideoProvider#SESSION_EVENT_TX_STOP}
         *
         * @param event The event.
         */
        public void handleCallSessionEvent(int event) {
            if (mVideoCallback != null) {
                try {
                    mVideoCallback.handleCallSessionEvent(event);
                } catch (RemoteException ignored) {
                }
            }
        }

        /**
         * Invokes callback method defined in In-Call UI.
         *
         * @param width  The updated peer video width.
         * @param height The updated peer video height.
         */
        public void changePeerDimensions(int width, int height) {
            if (mVideoCallback != null) {
                try {
                    mVideoCallback.changePeerDimensions(width, height);
                } catch (RemoteException ignored) {
                }
            }
        }

        /**
         * Invokes callback method defined in In-Call UI.
         *
         * @param dataUsage The updated data usage.
         */
        public void changeCallDataUsage(int dataUsage) {
            if (mVideoCallback != null) {
                try {
                    mVideoCallback.changeCallDataUsage(dataUsage);
                } catch (RemoteException ignored) {
                }
            }
        }

        /**
         * Invokes callback method defined in In-Call UI.
         *
         * @param cameraCapabilities The changed camera capabilities.
         */
        public void changeCameraCapabilities(CameraCapabilities cameraCapabilities) {
            if (mVideoCallback != null) {
                try {
                    mVideoCallback.changeCameraCapabilities(cameraCapabilities);
                } catch (RemoteException ignored) {
                }
            }
        }
    }

    private final Listener mConnectionDeathListener = new Listener() {
        @Override
        public void onDestroyed(Connection c) {
            if (mConferenceables.remove(c)) {
                fireOnConferenceableConnectionsChanged();
            }
        }
    };

    private final Conference.Listener mConferenceDeathListener = new Conference.Listener() {
        @Override
        public void onDestroyed(Conference c) {
            if (mConferenceables.remove(c)) {
                fireOnConferenceableConnectionsChanged();
            }
        }
    };

    /**
     * ConcurrentHashMap constructor params: 8 is initial table size, 0.9f is
     * load factor before resizing, 1 means we only expect a single thread to
     * access the map so make only a single shard
     */
    private final Set<Listener> mListeners = Collections.newSetFromMap(
            new ConcurrentHashMap<Listener, Boolean>(8, 0.9f, 1));
    private final List<IConferenceable> mConferenceables = new ArrayList<>();
    private final List<IConferenceable> mUnmodifiableConferenceables =
            Collections.unmodifiableList(mConferenceables);

    private int mState = STATE_NEW;
    private AudioState mAudioState;
    private Uri mAddress;
    private int mAddressPresentation;
    private String mCallerDisplayName;
    private int mCallerDisplayNamePresentation;
    private boolean mRingbackRequested = false;
    private int mConnectionCapabilities;
    private VideoProvider mVideoProvider;
    private boolean mAudioModeIsVoip;
    private StatusHints mStatusHints;
    private int mVideoState;
    private DisconnectCause mDisconnectCause;
    private Conference mConference;
    private ConnectionService mConnectionService;

    /**
     * Create a new Connection.
     */
    public Connection() {}

    /**
     * @return The address (e.g., phone number) to which this Connection is currently communicating.
     */
    public final Uri getAddress() {
        return mAddress;
    }

    /**
     * @return The presentation requirements for the address.
     *         See {@link TelecomManager} for valid values.
     */
    public final int getAddressPresentation() {
        return mAddressPresentation;
    }

    /**
     * @return The caller display name (CNAP).
     */
    public final String getCallerDisplayName() {
        return mCallerDisplayName;
    }

    /**
     * @return The presentation requirements for the handle.
     *         See {@link TelecomManager} for valid values.
     */
    public final int getCallerDisplayNamePresentation() {
        return mCallerDisplayNamePresentation;
    }

    /**
     * @return The state of this Connection.
     */
    public final int getState() {
        return mState;
    }

    /**
     * Returns the video state of the connection.
     * Valid values: {@link VideoProfile.VideoState#AUDIO_ONLY},
     * {@link VideoProfile.VideoState#BIDIRECTIONAL},
     * {@link VideoProfile.VideoState#TX_ENABLED},
     * {@link VideoProfile.VideoState#RX_ENABLED}.
     *
     * @return The video state of the connection.
     * @hide
     */
    public final int getVideoState() {
        return mVideoState;
    }

    /**
     * @return The audio state of the connection, describing how its audio is currently
     *         being routed by the system. This is {@code null} if this Connection
     *         does not directly know about its audio state.
     */
    public final AudioState getAudioState() {
        return mAudioState;
    }

    /**
     * @return The conference that this connection is a part of.  Null if it is not part of any
     *         conference.
     */
    public final Conference getConference() {
        return mConference;
    }

    /**
     * Returns whether this connection is requesting that the system play a ringback tone
     * on its behalf.
     */
    public final boolean isRingbackRequested() {
        return mRingbackRequested;
    }

    /**
     * @return True if the connection's audio mode is VOIP.
     */
    public final boolean getAudioModeIsVoip() {
        return mAudioModeIsVoip;
    }

    /**
     * @return The status hints for this connection.
     */
    public final StatusHints getStatusHints() {
        return mStatusHints;
    }

    /**
     * Assign a listener to be notified of state changes.
     *
     * @param l A listener.
     * @return This Connection.
     *
     * @hide
     */
    public final Connection addConnectionListener(Listener l) {
        mListeners.add(l);
        return this;
    }

    /**
     * Remove a previously assigned listener that was being notified of state changes.
     *
     * @param l A Listener.
     * @return This Connection.
     *
     * @hide
     */
    public final Connection removeConnectionListener(Listener l) {
        if (l != null) {
            mListeners.remove(l);
        }
        return this;
    }

    /**
     * @return The {@link DisconnectCause} for this connection.
     */
    public final DisconnectCause getDisconnectCause() {
        return mDisconnectCause;
    }

    /**
     * Inform this Connection that the state of its audio output has been changed externally.
     *
     * @param state The new audio state.
     * @hide
     */
    final void setAudioState(AudioState state) {
        checkImmutable();
        Log.d(this, "setAudioState %s", state);
        mAudioState = state;
        onAudioStateChanged(state);
    }

    /**
     * @param state An integer value of a {@code STATE_*} constant.
     * @return A string representation of the value.
     */
    public static String stateToString(int state) {
        switch (state) {
            case STATE_INITIALIZING:
                return "STATE_INITIALIZING";
            case STATE_NEW:
                return "STATE_NEW";
            case STATE_RINGING:
                return "STATE_RINGING";
            case STATE_DIALING:
                return "STATE_DIALING";
            case STATE_ACTIVE:
                return "STATE_ACTIVE";
            case STATE_HOLDING:
                return "STATE_HOLDING";
            case STATE_DISCONNECTED:
                return "DISCONNECTED";
            default:
                Log.wtf(Connection.class, "Unknown state %d", state);
                return "UNKNOWN";
        }
    }

    /**
     * Returns the connection's capabilities, as a bit mask of the {@code CAPABILITY_*} constants.
     */
    public final int getConnectionCapabilities() {
        return mConnectionCapabilities;
    }

    /** @hide */
    @SystemApi @Deprecated public final int getCallCapabilities() {
        return getConnectionCapabilities();
    }

    /**
     * Sets the value of the {@link #getAddress()} property.
     *
     * @param address The new address.
     * @param presentation The presentation requirements for the address.
     *        See {@link TelecomManager} for valid values.
     */
    public final void setAddress(Uri address, int presentation) {
        checkImmutable();
        Log.d(this, "setAddress %s", address);
        mAddress = address;
        mAddressPresentation = presentation;
        for (Listener l : mListeners) {
            l.onAddressChanged(this, address, presentation);
        }
    }

    /**
     * Sets the caller display name (CNAP).
     *
     * @param callerDisplayName The new display name.
     * @param presentation The presentation requirements for the handle.
     *        See {@link TelecomManager} for valid values.
     */
    public final void setCallerDisplayName(String callerDisplayName, int presentation) {
        checkImmutable();
        Log.d(this, "setCallerDisplayName %s", callerDisplayName);
        mCallerDisplayName = callerDisplayName;
        mCallerDisplayNamePresentation = presentation;
        for (Listener l : mListeners) {
            l.onCallerDisplayNameChanged(this, callerDisplayName, presentation);
        }
    }

    /**
     * Set the video state for the connection.
     * Valid values: {@link VideoProfile.VideoState#AUDIO_ONLY},
     * {@link VideoProfile.VideoState#BIDIRECTIONAL},
     * {@link VideoProfile.VideoState#TX_ENABLED},
     * {@link VideoProfile.VideoState#RX_ENABLED}.
     *
     * @param videoState The new video state.
     * @hide
     */
    public final void setVideoState(int videoState) {
        checkImmutable();
        Log.d(this, "setVideoState %d", videoState);
        mVideoState = videoState;
        for (Listener l : mListeners) {
            l.onVideoStateChanged(this, mVideoState);
        }
    }

    /**
     * Sets state to active (e.g., an ongoing connection where two or more parties can actively
     * communicate).
     */
    public final void setActive() {
        checkImmutable();
        setRingbackRequested(false);
        setState(STATE_ACTIVE);
    }

    /**
     * Sets state to ringing (e.g., an inbound ringing connection).
     */
    public final void setRinging() {
        checkImmutable();
        setState(STATE_RINGING);
    }

    /**
     * Sets state to initializing (this Connection is not yet ready to be used).
     */
    public final void setInitializing() {
        checkImmutable();
        setState(STATE_INITIALIZING);
    }

    /**
     * Sets state to initialized (the Connection has been set up and is now ready to be used).
     */
    public final void setInitialized() {
        checkImmutable();
        setState(STATE_NEW);
    }

    /**
     * Sets state to dialing (e.g., dialing an outbound connection).
     */
    public final void setDialing() {
        checkImmutable();
        setState(STATE_DIALING);
    }

    /**
     * Sets state to be on hold.
     */
    public final void setOnHold() {
        checkImmutable();
        setState(STATE_HOLDING);
    }

    /**
     * Sets the video connection provider.
     * @param videoProvider The video provider.
     * @hide
     */
    public final void setVideoProvider(VideoProvider videoProvider) {
        checkImmutable();
        mVideoProvider = videoProvider;
        for (Listener l : mListeners) {
            l.onVideoProviderChanged(this, videoProvider);
        }
    }

    /** @hide */
    public final VideoProvider getVideoProvider() {
        return mVideoProvider;
    }

    /**
     * Sets state to disconnected.
     *
     * @param disconnectCause The reason for the disconnection, as specified by
     *         {@link DisconnectCause}.
     */
    public final void setDisconnected(DisconnectCause disconnectCause) {
        checkImmutable();
        mDisconnectCause = disconnectCause;
        setState(STATE_DISCONNECTED);
        Log.d(this, "Disconnected with cause %s", disconnectCause);
        for (Listener l : mListeners) {
            l.onDisconnected(this, disconnectCause);
        }
    }

    /**
     * Informs listeners that this {@code Connection} is in a post-dial wait state. This is done
     * when (a) the {@code Connection} is issuing a DTMF sequence; (b) it has encountered a "wait"
     * character; and (c) it wishes to inform the In-Call app that it is waiting for the end-user
     * to send an {@link #onPostDialContinue(boolean)} signal.
     *
     * @param remaining The DTMF character sequence remaining to be emitted once the
     *         {@link #onPostDialContinue(boolean)} is received, including any "wait" characters
     *         that remaining sequence may contain.
     */
    public final void setPostDialWait(String remaining) {
        checkImmutable();
        for (Listener l : mListeners) {
            l.onPostDialWait(this, remaining);
        }
    }

    /**
     * Informs listeners that this {@code Connection} has processed a character in the post-dial
     * started state. This is done when (a) the {@code Connection} is issuing a DTMF sequence;
     * (b) it has encountered a "wait" character; and (c) it wishes to signal Telecom to play
     * the corresponding DTMF tone locally.
     *
     * @param nextChar The DTMF character that was just processed by the {@code Connection}.
     *
     * @hide
     */
    public final void setNextPostDialWaitChar(char nextChar) {
        checkImmutable();
        for (Listener l : mListeners) {
            l.onPostDialChar(this, nextChar);
        }
    }

    /**
     * Requests that the framework play a ringback tone. This is to be invoked by implementations
     * that do not play a ringback tone themselves in the connection's audio stream.
     *
     * @param ringback Whether the ringback tone is to be played.
     */
    public final void setRingbackRequested(boolean ringback) {
        checkImmutable();
        if (mRingbackRequested != ringback) {
            mRingbackRequested = ringback;
            for (Listener l : mListeners) {
                l.onRingbackRequested(this, ringback);
            }
        }
    }

    /** @hide */
    @SystemApi @Deprecated public final void setCallCapabilities(int connectionCapabilities) {
        setConnectionCapabilities(connectionCapabilities);
    }

    /**
     * Sets the connection's capabilities as a bit mask of the {@code CAPABILITY_*} constants.
     *
     * @param connectionCapabilities The new connection capabilities.
     */
    public final void setConnectionCapabilities(int connectionCapabilities) {
        checkImmutable();
        if (mConnectionCapabilities != connectionCapabilities) {
            mConnectionCapabilities = connectionCapabilities;
            for (Listener l : mListeners) {
                l.onConnectionCapabilitiesChanged(this, mConnectionCapabilities);
            }
        }
    }

    /**
     * Tears down the Connection object.
     */
    public final void destroy() {
        for (Listener l : mListeners) {
            l.onDestroyed(this);
        }
    }

    /**
     * Requests that the framework use VOIP audio mode for this connection.
     *
     * @param isVoip True if the audio mode is VOIP.
     */
    public final void setAudioModeIsVoip(boolean isVoip) {
        checkImmutable();
        mAudioModeIsVoip = isVoip;
        for (Listener l : mListeners) {
            l.onAudioModeIsVoipChanged(this, isVoip);
        }
    }

    /**
     * Sets the label and icon status to display in the in-call UI.
     *
     * @param statusHints The status label and icon to set.
     */
    public final void setStatusHints(StatusHints statusHints) {
        checkImmutable();
        mStatusHints = statusHints;
        for (Listener l : mListeners) {
            l.onStatusHintsChanged(this, statusHints);
        }
    }

    /**
     * Sets the connections with which this connection can be conferenced.
     *
     * @param conferenceableConnections The set of connections this connection can conference with.
     */
    public final void setConferenceableConnections(List<Connection> conferenceableConnections) {
        checkImmutable();
        clearConferenceableList();
        for (Connection c : conferenceableConnections) {
            // If statement checks for duplicates in input. It makes it N^2 but we're dealing with a
            // small amount of items here.
            if (!mConferenceables.contains(c)) {
                c.addConnectionListener(mConnectionDeathListener);
                mConferenceables.add(c);
            }
        }
        fireOnConferenceableConnectionsChanged();
    }

    /**
     * Similar to {@link #setConferenceableConnections(java.util.List)}, sets a list of connections
     * or conferences with which this connection can be conferenced.
     *
     * @param conferenceables The conferenceables.
     */
    public final void setConferenceables(List<IConferenceable> conferenceables) {
        clearConferenceableList();
        for (IConferenceable c : conferenceables) {
            // If statement checks for duplicates in input. It makes it N^2 but we're dealing with a
            // small amount of items here.
            if (!mConferenceables.contains(c)) {
                if (c instanceof Connection) {
                    Connection connection = (Connection) c;
                    connection.addConnectionListener(mConnectionDeathListener);
                } else if (c instanceof Conference) {
                    Conference conference = (Conference) c;
                    conference.addListener(mConferenceDeathListener);
                }
                mConferenceables.add(c);
            }
        }
        fireOnConferenceableConnectionsChanged();
    }

    /**
     * Returns the connections or conferences with which this connection can be conferenced.
     */
    public final List<IConferenceable> getConferenceables() {
        return mUnmodifiableConferenceables;
    }

    /*
     * @hide
     */
    public final void setConnectionService(ConnectionService connectionService) {
        checkImmutable();
        if (mConnectionService != null) {
            Log.e(this, new Exception(), "Trying to set ConnectionService on a connection " +
                    "which is already associated with another ConnectionService.");
        } else {
            mConnectionService = connectionService;
        }
    }

    /**
     * @hide
     */
    public final void unsetConnectionService(ConnectionService connectionService) {
        if (mConnectionService != connectionService) {
            Log.e(this, new Exception(), "Trying to remove ConnectionService from a Connection " +
                    "that does not belong to the ConnectionService.");
        } else {
            mConnectionService = null;
        }
    }

    /**
     * @hide
     */
    public final ConnectionService getConnectionService() {
        return mConnectionService;
    }

    /**
     * Sets the conference that this connection is a part of. This will fail if the connection is
     * already part of a conference. {@link #resetConference} to un-set the conference first.
     *
     * @param conference The conference.
     * @return {@code true} if the conference was successfully set.
     * @hide
     */
    public final boolean setConference(Conference conference) {
        checkImmutable();
        // We check to see if it is already part of another conference.
        if (mConference == null) {
            mConference = conference;
            if (mConnectionService != null && mConnectionService.containsConference(conference)) {
                fireConferenceChanged();
            }
            return true;
        }
        return false;
    }

    /**
     * Resets the conference that this connection is a part of.
     * @hide
     */
    public final void resetConference() {
        if (mConference != null) {
            Log.d(this, "Conference reset");
            mConference = null;
            fireConferenceChanged();
        }
    }

    /**
     * Notifies this Connection that the {@link #getAudioState()} property has a new value.
     *
     * @param state The new connection audio state.
     */
    public void onAudioStateChanged(AudioState state) {}

    /**
     * Notifies this Connection of an internal state change. This method is called after the
     * state is changed.
     *
     * @param state The new state, one of the {@code STATE_*} constants.
     */
    public void onStateChanged(int state) {}

    /**
     * Notifies this Connection of a request to play a DTMF tone.
     *
     * @param c A DTMF character.
     */
    public void onPlayDtmfTone(char c) {}

    /**
     * Notifies this Connection of a request to stop any currently playing DTMF tones.
     */
    public void onStopDtmfTone() {}

    /**
     * Notifies this Connection of a request to disconnect.
     */
    public void onDisconnect() {}

    /**
     * Notifies this Connection of a request to disconnect a participant of the conference managed
     * by the connection.
     *
     * @param endpoint the {@link Uri} of the participant to disconnect.
     * @hide
     */
    public void onDisconnectConferenceParticipant(Uri endpoint) {}

    /**
     * Notifies this Connection of a request to separate from its parent conference.
     */
    public void onSeparate() {}

    /**
     * Notifies this Connection of a request to abort.
     */
    public void onAbort() {}

    /**
     * Notifies this Connection of a request to hold.
     */
    public void onHold() {}

    /**
     * Notifies this Connection of a request to exit a hold state.
     */
    public void onUnhold() {}

    /**
     * Notifies this Connection, which is in {@link #STATE_RINGING}, of
     * a request to accept.
     *
     * @param videoState The video state in which to answer the connection.
     * @hide
     */
    public void onAnswer(int videoState) {}

    /**
     * Notifies this Connection, which is in {@link #STATE_RINGING}, of
     * a request to accept.
     */
    public void onAnswer() {
        onAnswer(VideoProfile.VideoState.AUDIO_ONLY);
    }

    /**
     * Notifies this Connection, which is in {@link #STATE_RINGING}, of
     * a request to reject.
     */
    public void onReject() {}

    /**
     * Notifies this Connection whether the user wishes to proceed with the post-dial DTMF codes.
     */
    public void onPostDialContinue(boolean proceed) {}

    static String toLogSafePhoneNumber(String number) {
        // For unknown number, log empty string.
        if (number == null) {
            return "";
        }

        if (PII_DEBUG) {
            // When PII_DEBUG is true we emit PII.
            return number;
        }

        // Do exactly same thing as Uri#toSafeString() does, which will enable us to compare
        // sanitized phone numbers.
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < number.length(); i++) {
            char c = number.charAt(i);
            if (c == '-' || c == '@' || c == '.') {
                builder.append(c);
            } else {
                builder.append('x');
            }
        }
        return builder.toString();
    }

    private void setState(int state) {
        checkImmutable();
        if (mState == STATE_DISCONNECTED && mState != state) {
            Log.d(this, "Connection already DISCONNECTED; cannot transition out of this state.");
            return;
        }
        if (mState != state) {
            Log.d(this, "setState: %s", stateToString(state));
            mState = state;
            onStateChanged(state);
            for (Listener l : mListeners) {
                l.onStateChanged(this, state);
            }
        }
    }

    private static class FailureSignalingConnection extends Connection {
        private boolean mImmutable = false;
        public FailureSignalingConnection(DisconnectCause disconnectCause) {
            setDisconnected(disconnectCause);
            mImmutable = true;
        }

        public void checkImmutable() {
            if (mImmutable) {
                throw new UnsupportedOperationException("Connection is immutable");
            }
        }
    }

    /**
     * Return a {@code Connection} which represents a failed connection attempt. The returned
     * {@code Connection} will have a {@link android.telecom.DisconnectCause} and as specified,
     * and a {@link #getState()} of {@link #STATE_DISCONNECTED}.
     * <p>
     * The returned {@code Connection} can be assumed to {@link #destroy()} itself when appropriate,
     * so users of this method need not maintain a reference to its return value to destroy it.
     *
     * @param disconnectCause The disconnect cause, ({@see android.telecomm.DisconnectCause}).
     * @return A {@code Connection} which indicates failure.
     */
    public static Connection createFailedConnection(DisconnectCause disconnectCause) {
        return new FailureSignalingConnection(disconnectCause);
    }

    /**
     * Override to throw an {@link UnsupportedOperationException} if this {@code Connection} is
     * not intended to be mutated, e.g., if it is a marker for failure. Only for framework use;
     * this should never be un-@hide-den.
     *
     * @hide
     */
    public void checkImmutable() {}

    /**
     * Return a {@code Connection} which represents a canceled connection attempt. The returned
     * {@code Connection} will have state {@link #STATE_DISCONNECTED}, and cannot be moved out of
     * that state. This connection should not be used for anything, and no other
     * {@code Connection}s should be attempted.
     * <p>
     * so users of this method need not maintain a reference to its return value to destroy it.
     *
     * @return A {@code Connection} which indicates that the underlying connection should
     * be canceled.
     */
    public static Connection createCanceledConnection() {
        return new FailureSignalingConnection(new DisconnectCause(DisconnectCause.CANCELED));
    }

    private final void fireOnConferenceableConnectionsChanged() {
        for (Listener l : mListeners) {
            l.onConferenceablesChanged(this, getConferenceables());
        }
    }

    private final void fireConferenceChanged() {
        for (Listener l : mListeners) {
            l.onConferenceChanged(this, mConference);
        }
    }

    private final void clearConferenceableList() {
        for (IConferenceable c : mConferenceables) {
            if (c instanceof Connection) {
                Connection connection = (Connection) c;
                connection.removeConnectionListener(mConnectionDeathListener);
            } else if (c instanceof Conference) {
                Conference conference = (Conference) c;
                conference.removeListener(mConferenceDeathListener);
            }
        }
        mConferenceables.clear();
    }

    /**
     * Notifies listeners of a change to conference participant(s).
     *
     * @param conferenceParticipants The participants.
     * @hide
     */
    protected final void updateConferenceParticipants(
            List<ConferenceParticipant> conferenceParticipants) {
        for (Listener l : mListeners) {
            l.onConferenceParticipantsChanged(this, conferenceParticipants);
        }
    }

    /**
     * Notifies listeners that a conference call has been started.
     */
    protected void notifyConferenceStarted() {
        for (Listener l : mListeners) {
            l.onConferenceStarted();
        }
    }
}
