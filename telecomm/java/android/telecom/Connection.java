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

import com.android.internal.os.SomeArgs;
import com.android.internal.telecom.IVideoCallback;
import com.android.internal.telecom.IVideoProvider;

import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a phone call or connection to a remote endpoint that carries voice and/or video
 * traffic.
 * <p>
 * Implementations create a custom subclass of {@code Connection} and return it to the framework
 * as the return value of
 * {@link ConnectionService#onCreateIncomingConnection(PhoneAccountHandle, ConnectionRequest)}
 * or
 * {@link ConnectionService#onCreateOutgoingConnection(PhoneAccountHandle, ConnectionRequest)}.
 * Implementations are then responsible for updating the state of the {@code Connection}, and
 * must call {@link #destroy()} to signal to the framework that the {@code Connection} is no
 * longer used and associated resources may be recovered.
 */
public abstract class Connection extends Conferenceable {

    /**
     * The connection is initializing. This is generally the first state for a {@code Connection}
     * returned by a {@link ConnectionService}.
     */
    public static final int STATE_INITIALIZING = 0;

    /**
     * The connection is new and not connected.
     */
    public static final int STATE_NEW = 1;

    /**
     * An incoming connection is in the ringing state. During this state, the user's ringer or
     * vibration feature will be activated.
     */
    public static final int STATE_RINGING = 2;

    /**
     * An outgoing connection is in the dialing state. In this state the other party has not yet
     * answered the call and the user traditionally hears a ringback tone.
     */
    public static final int STATE_DIALING = 3;

    /**
     * A connection is active. Both parties are connected to the call and can actively communicate.
     */
    public static final int STATE_ACTIVE = 4;

    /**
     * A connection is on hold.
     */
    public static final int STATE_HOLDING = 5;

    /**
     * A connection has been disconnected. This is the final state once the user has been
     * disconnected from a call either locally, remotely or by an error in the service.
     */
    public static final int STATE_DISCONNECTED = 6;

    /**
     * Connection can currently be put on hold or unheld. This is distinct from
     * {@link #CAPABILITY_SUPPORT_HOLD} in that although a connection may support 'hold' most times,
     * it does not at the moment support the function. This can be true while the call is in the
     * state {@link #STATE_DIALING}, for example. During this condition, an in-call UI may
     * display a disabled 'hold' button.
     */
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
     * Local device supports receiving video.
     */
    public static final int CAPABILITY_SUPPORTS_VT_LOCAL_RX = 0x00000100;

    /**
     * Local device supports transmitting video.
     */
    public static final int CAPABILITY_SUPPORTS_VT_LOCAL_TX = 0x00000200;

    /**
     * Local device supports bidirectional video calling.
     */
    public static final int CAPABILITY_SUPPORTS_VT_LOCAL_BIDIRECTIONAL =
            CAPABILITY_SUPPORTS_VT_LOCAL_RX | CAPABILITY_SUPPORTS_VT_LOCAL_TX;

    /**
     * Remote device supports receiving video.
     */
    public static final int CAPABILITY_SUPPORTS_VT_REMOTE_RX = 0x00000400;

    /**
     * Remote device supports transmitting video.
     */
    public static final int CAPABILITY_SUPPORTS_VT_REMOTE_TX = 0x00000800;

    /**
     * Remote device supports bidirectional video calling.
     */
    public static final int CAPABILITY_SUPPORTS_VT_REMOTE_BIDIRECTIONAL =
            CAPABILITY_SUPPORTS_VT_REMOTE_RX | CAPABILITY_SUPPORTS_VT_REMOTE_TX;

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
     * Connection is using high definition audio.
     * @hide
     */
    public static final int CAPABILITY_HIGH_DEF_AUDIO = 0x00008000;

    /**
     * Connection is using WIFI.
     * @hide
     */
    public static final int CAPABILITY_WIFI = 0x00010000;

    /**
     * Indicates that the current device callback number should be shown.
     *
     * @hide
     */
    public static final int CAPABILITY_SHOW_CALLBACK_NUMBER = 0x00020000;

    /**
     * Speed up audio setup for MT call.
     * @hide
     */
    public static final int CAPABILITY_SPEED_UP_MT_AUDIO = 0x00040000;

    /**
     * Call can be upgraded to a video call.
     */
    public static final int CAPABILITY_CAN_UPGRADE_TO_VIDEO = 0x00080000;

    /**
     * For video calls, indicates whether the outgoing video for the call can be paused using
     * the {@link android.telecom.VideoProfile#STATE_PAUSED} VideoState.
     */
    public static final int CAPABILITY_CAN_PAUSE_VIDEO = 0x00100000;

    /**
     * For a conference, indicates the conference will not have child connections.
     * <p>
     * An example of a conference with child connections is a GSM conference call, where the radio
     * retains connections to the individual participants of the conference.  Another example is an
     * IMS conference call where conference event package functionality is supported; in this case
     * the conference server ensures the radio is aware of the participants in the conference, which
     * are represented by child connections.
     * <p>
     * An example of a conference with no child connections is an IMS conference call with no
     * conference event package support.  Such a conference is represented by the radio as a single
     * connection to the IMS conference server.
     * <p>
     * Indicating whether a conference has children or not is important to help user interfaces
     * visually represent a conference.  A conference with no children, for example, will have the
     * conference connection shown in the list of calls on a Bluetooth device, where if the
     * conference has children, only the children will be shown in the list of calls on a Bluetooth
     * device.
     * @hide
     */
    public static final int CAPABILITY_CONFERENCE_HAS_NO_CHILDREN = 0x00200000;

    /**
     * Indicates that the connection itself wants to handle any sort of reply response, rather than
     * relying on SMS.
     * @hide
     */
    public static final int CAPABILITY_CAN_SEND_RESPONSE_VIA_CONNECTION = 0x00400000;

    //**********************************************************************************************
    // Next CAPABILITY value: 0x00800000
    //**********************************************************************************************

    /**
     * Connection extra key used to store the last forwarded number associated with the current
     * connection.  Used to communicate to the user interface that the connection was forwarded via
     * the specified number.
     */
    public static final String EXTRA_LAST_FORWARDED_NUMBER =
            "android.telecom.extra.LAST_FORWARDED_NUMBER";

    /**
     * Connection extra key used to store a child number associated with the current connection.
     * Used to communicate to the user interface that the connection was received via
     * a child address (i.e. phone number) associated with the {@link PhoneAccount}'s primary
     * address.
     */
    public static final String EXTRA_CHILD_ADDRESS = "android.telecom.extra.CHILD_ADDRESS";

    /**
     * Connection extra key used to store the subject for an incoming call.  The user interface can
     * query this extra and display its contents for incoming calls.  Will only be used if the
     * {@link PhoneAccount} supports the capability {@link PhoneAccount#CAPABILITY_CALL_SUBJECT}.
     */
    public static final String EXTRA_CALL_SUBJECT = "android.telecom.extra.CALL_SUBJECT";

    /**
     * Connection event used to inform Telecom that it should play the on hold tone.  This is used
     * to play a tone when the peer puts the current call on hold.  Sent to Telecom via
     * {@link #sendConnectionEvent(String)}.
     * @hide
     */
    public static final String EVENT_ON_HOLD_TONE_START =
            "android.telecom.event.ON_HOLD_TONE_START";

    /**
     * Connection event used to inform Telecom that it should stop the on hold tone.  This is used
     * to stop a tone when the peer puts the current call on hold.  Sent to Telecom via
     * {@link #sendConnectionEvent(String)}.
     * @hide
     */
    public static final String EVENT_ON_HOLD_TONE_END =
            "android.telecom.event.ON_HOLD_TONE_END";

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
        if (can(capabilities, CAPABILITY_SUPPORTS_VT_LOCAL_RX)) {
            builder.append(" CAPABILITY_SUPPORTS_VT_LOCAL_RX");
        }
        if (can(capabilities, CAPABILITY_SUPPORTS_VT_LOCAL_TX)) {
            builder.append(" CAPABILITY_SUPPORTS_VT_LOCAL_TX");
        }
        if (can(capabilities, CAPABILITY_SUPPORTS_VT_LOCAL_BIDIRECTIONAL)) {
            builder.append(" CAPABILITY_SUPPORTS_VT_LOCAL_BIDIRECTIONAL");
        }
        if (can(capabilities, CAPABILITY_SUPPORTS_VT_REMOTE_RX)) {
            builder.append(" CAPABILITY_SUPPORTS_VT_REMOTE_RX");
        }
        if (can(capabilities, CAPABILITY_SUPPORTS_VT_REMOTE_TX)) {
            builder.append(" CAPABILITY_SUPPORTS_VT_REMOTE_TX");
        }
        if (can(capabilities, CAPABILITY_SUPPORTS_VT_REMOTE_BIDIRECTIONAL)) {
            builder.append(" CAPABILITY_SUPPORTS_VT_REMOTE_BIDIRECTIONAL");
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
        if (can(capabilities, CAPABILITY_SHOW_CALLBACK_NUMBER)) {
            builder.append(" CAPABILITY_SHOW_CALLBACK_NUMBER");
        }
        if (can(capabilities, CAPABILITY_SPEED_UP_MT_AUDIO)) {
            builder.append(" CAPABILITY_SPEED_UP_MT_AUDIO");
        }
        if (can(capabilities, CAPABILITY_CAN_UPGRADE_TO_VIDEO)) {
            builder.append(" CAPABILITY_CAN_UPGRADE_TO_VIDEO");
        }
        if (can(capabilities, CAPABILITY_CAN_PAUSE_VIDEO)) {
            builder.append(" CAPABILITY_CAN_PAUSE_VIDEO");
        }
        if (can(capabilities, CAPABILITY_CONFERENCE_HAS_NO_CHILDREN)) {
            builder.append(" CAPABILITY_SINGLE_PARTY_CONFERENCE");
        }
        if (can(capabilities, CAPABILITY_CAN_SEND_RESPONSE_VIA_CONNECTION)) {
            builder.append(" CAPABILITY_CAN_SEND_RESPONSE_VIA_CONNECTION");
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
                Connection c, List<Conferenceable> conferenceables) {}
        public void onConferenceChanged(Connection c, Conference conference) {}
        /** @hide */
        public void onConferenceParticipantsChanged(Connection c,
                List<ConferenceParticipant> participants) {}
        public void onConferenceStarted() {}
        public void onConferenceMergeFailed(Connection c) {}
        public void onExtrasChanged(Connection c, Bundle extras) {}
        /** @hide */
        public void onConnectionEvent(Connection c, String event) {}
    }

    /**
     * Provides a means of controlling the video session associated with a {@link Connection}.
     * <p>
     * Implementations create a custom subclass of {@link VideoProvider} and the
     * {@link ConnectionService} creates an instance sets it on the {@link Connection} using
     * {@link Connection#setVideoProvider(VideoProvider)}.  Any connection which supports video
     * should set the {@link VideoProvider}.
     * <p>
     * The {@link VideoProvider} serves two primary purposes: it provides a means for Telecom and
     * {@link InCallService} implementations to issue requests related to the video session;
     * it provides a means for the {@link ConnectionService} to report events and information
     * related to the video session to Telecom and the {@link InCallService} implementations.
     * <p>
     * {@link InCallService} implementations interact with the {@link VideoProvider} via
     * {@link android.telecom.InCallService.VideoCall}.
     */
    public static abstract class VideoProvider {

        /**
         * Video is not being received (no protocol pause was issued).
         * @see #handleCallSessionEvent(int)
         */
        public static final int SESSION_EVENT_RX_PAUSE = 1;

        /**
         * Video reception has resumed after a {@link #SESSION_EVENT_RX_PAUSE}.
         * @see #handleCallSessionEvent(int)
         */
        public static final int SESSION_EVENT_RX_RESUME = 2;

        /**
         * Video transmission has begun. This occurs after a negotiated start of video transmission
         * when the underlying protocol has actually begun transmitting video to the remote party.
         * @see #handleCallSessionEvent(int)
         */
        public static final int SESSION_EVENT_TX_START = 3;

        /**
         * Video transmission has stopped. This occurs after a negotiated stop of video transmission
         * when the underlying protocol has actually stopped transmitting video to the remote party.
         * @see #handleCallSessionEvent(int)
         */
        public static final int SESSION_EVENT_TX_STOP = 4;

        /**
         * A camera failure has occurred for the selected camera.  The {@link InCallService} can use
         * this as a cue to inform the user the camera is not available.
         * @see #handleCallSessionEvent(int)
         */
        public static final int SESSION_EVENT_CAMERA_FAILURE = 5;

        /**
         * Issued after {@link #SESSION_EVENT_CAMERA_FAILURE} when the camera is once again ready
         * for operation.  The {@link InCallService} can use this as a cue to inform the user that
         * the camera has become available again.
         * @see #handleCallSessionEvent(int)
         */
        public static final int SESSION_EVENT_CAMERA_READY = 6;

        /**
         * Session modify request was successful.
         * @see #receiveSessionModifyResponse(int, VideoProfile, VideoProfile)
         */
        public static final int SESSION_MODIFY_REQUEST_SUCCESS = 1;

        /**
         * Session modify request failed.
         * @see #receiveSessionModifyResponse(int, VideoProfile, VideoProfile)
         */
        public static final int SESSION_MODIFY_REQUEST_FAIL = 2;

        /**
         * Session modify request ignored due to invalid parameters.
         * @see #receiveSessionModifyResponse(int, VideoProfile, VideoProfile)
         */
        public static final int SESSION_MODIFY_REQUEST_INVALID = 3;

        /**
         * Session modify request timed out.
         * @see #receiveSessionModifyResponse(int, VideoProfile, VideoProfile)
         */
        public static final int SESSION_MODIFY_REQUEST_TIMED_OUT = 4;

        /**
         * Session modify request rejected by remote user.
         * @see #receiveSessionModifyResponse(int, VideoProfile, VideoProfile)
         */
        public static final int SESSION_MODIFY_REQUEST_REJECTED_BY_REMOTE = 5;

        private static final int MSG_ADD_VIDEO_CALLBACK = 1;
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
        private static final int MSG_REMOVE_VIDEO_CALLBACK = 12;

        private VideoProvider.VideoProviderHandler mMessageHandler;
        private final VideoProvider.VideoProviderBinder mBinder;

        /**
         * Stores a list of the video callbacks, keyed by IBinder.
         *
         * ConcurrentHashMap constructor params: 8 is initial table size, 0.9f is
         * load factor before resizing, 1 means we only expect a single thread to
         * access the map so make only a single shard
         */
        private ConcurrentHashMap<IBinder, IVideoCallback> mVideoCallbacks =
                new ConcurrentHashMap<IBinder, IVideoCallback>(8, 0.9f, 1);

        /**
         * Default handler used to consolidate binder method calls onto a single thread.
         */
        private final class VideoProviderHandler extends Handler {
            public VideoProviderHandler() {
                super();
            }

            public VideoProviderHandler(Looper looper) {
                super(looper);
            }

            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_ADD_VIDEO_CALLBACK: {
                        IBinder binder = (IBinder) msg.obj;
                        IVideoCallback callback = IVideoCallback.Stub
                                .asInterface((IBinder) msg.obj);
                        if (callback == null) {
                            Log.w(this, "addVideoProvider - skipped; callback is null.");
                            break;
                        }

                        if (mVideoCallbacks.containsKey(binder)) {
                            Log.i(this, "addVideoProvider - skipped; already present.");
                            break;
                        }
                        mVideoCallbacks.put(binder, callback);
                        break;
                    }
                    case MSG_REMOVE_VIDEO_CALLBACK: {
                        IBinder binder = (IBinder) msg.obj;
                        IVideoCallback callback = IVideoCallback.Stub
                                .asInterface((IBinder) msg.obj);
                        if (!mVideoCallbacks.containsKey(binder)) {
                            Log.i(this, "removeVideoProvider - skipped; not present.");
                            break;
                        }
                        mVideoCallbacks.remove(binder);
                        break;
                    }
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
                    case MSG_SEND_SESSION_MODIFY_REQUEST: {
                        SomeArgs args = (SomeArgs) msg.obj;
                        try {
                            onSendSessionModifyRequest((VideoProfile) args.arg1,
                                    (VideoProfile) args.arg2);
                        } finally {
                            args.recycle();
                        }
                        break;
                    }
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
                        onSetPauseImage((Uri) msg.obj);
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
            public void addVideoCallback(IBinder videoCallbackBinder) {
                mMessageHandler.obtainMessage(
                        MSG_ADD_VIDEO_CALLBACK, videoCallbackBinder).sendToTarget();
            }

            public void removeVideoCallback(IBinder videoCallbackBinder) {
                mMessageHandler.obtainMessage(
                        MSG_REMOVE_VIDEO_CALLBACK, videoCallbackBinder).sendToTarget();
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
                mMessageHandler.obtainMessage(
                        MSG_SET_DEVICE_ORIENTATION, rotation, 0).sendToTarget();
            }

            public void setZoom(float value) {
                mMessageHandler.obtainMessage(MSG_SET_ZOOM, value).sendToTarget();
            }

            public void sendSessionModifyRequest(VideoProfile fromProfile, VideoProfile toProfile) {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = fromProfile;
                args.arg2 = toProfile;
                mMessageHandler.obtainMessage(MSG_SEND_SESSION_MODIFY_REQUEST, args).sendToTarget();
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

            public void setPauseImage(Uri uri) {
                mMessageHandler.obtainMessage(MSG_SET_PAUSE_IMAGE, uri).sendToTarget();
            }
        }

        public VideoProvider() {
            mBinder = new VideoProvider.VideoProviderBinder();
            mMessageHandler = new VideoProvider.VideoProviderHandler(Looper.getMainLooper());
        }

        /**
         * Creates an instance of the {@link VideoProvider}, specifying the looper to use.
         *
         * @param looper The looper.
         * @hide
         */
        public VideoProvider(Looper looper) {
            mBinder = new VideoProvider.VideoProviderBinder();
            mMessageHandler = new VideoProvider.VideoProviderHandler(looper);
        }

        /**
         * Returns binder object which can be used across IPC methods.
         * @hide
         */
        public final IVideoProvider getInterface() {
            return mBinder;
        }

        /**
         * Sets the camera to be used for the outgoing video.
         * <p>
         * The {@link VideoProvider} should respond by communicating the capabilities of the chosen
         * camera via
         * {@link VideoProvider#changeCameraCapabilities(VideoProfile.CameraCapabilities)}.
         * <p>
         * Sent from the {@link InCallService} via
         * {@link InCallService.VideoCall#setCamera(String)}.
         *
         * @param cameraId The id of the camera (use ids as reported by
         * {@link CameraManager#getCameraIdList()}).
         */
        public abstract void onSetCamera(String cameraId);

        /**
         * Sets the surface to be used for displaying a preview of what the user's camera is
         * currently capturing.  When video transmission is enabled, this is the video signal which
         * is sent to the remote device.
         * <p>
         * Sent from the {@link InCallService} via
         * {@link InCallService.VideoCall#setPreviewSurface(Surface)}.
         *
         * @param surface The {@link Surface}.
         */
        public abstract void onSetPreviewSurface(Surface surface);

        /**
         * Sets the surface to be used for displaying the video received from the remote device.
         * <p>
         * Sent from the {@link InCallService} via
         * {@link InCallService.VideoCall#setDisplaySurface(Surface)}.
         *
         * @param surface The {@link Surface}.
         */
        public abstract void onSetDisplaySurface(Surface surface);

        /**
         * Sets the device orientation, in degrees.  Assumes that a standard portrait orientation of
         * the device is 0 degrees.
         * <p>
         * Sent from the {@link InCallService} via
         * {@link InCallService.VideoCall#setDeviceOrientation(int)}.
         *
         * @param rotation The device orientation, in degrees.
         */
        public abstract void onSetDeviceOrientation(int rotation);

        /**
         * Sets camera zoom ratio.
         * <p>
         * Sent from the {@link InCallService} via {@link InCallService.VideoCall#setZoom(float)}.
         *
         * @param value The camera zoom ratio.
         */
        public abstract void onSetZoom(float value);

        /**
         * Issues a request to modify the properties of the current video session.
         * <p>
         * Example scenarios include: requesting an audio-only call to be upgraded to a
         * bi-directional video call, turning on or off the user's camera, sending a pause signal
         * when the {@link InCallService} is no longer the foreground application.
         * <p>
         * If the {@link VideoProvider} determines a request to be invalid, it should call
         * {@link #receiveSessionModifyResponse(int, VideoProfile, VideoProfile)} to report the
         * invalid request back to the {@link InCallService}.
         * <p>
         * Where a request requires confirmation from the user of the peer device, the
         * {@link VideoProvider} must communicate the request to the peer device and handle the
         * user's response.  {@link #receiveSessionModifyResponse(int, VideoProfile, VideoProfile)}
         * is used to inform the {@link InCallService} of the result of the request.
         * <p>
         * Sent from the {@link InCallService} via
         * {@link InCallService.VideoCall#sendSessionModifyRequest(VideoProfile)}.
         *
         * @param fromProfile The video profile prior to the request.
         * @param toProfile The video profile with the requested changes made.
         */
        public abstract void onSendSessionModifyRequest(VideoProfile fromProfile,
                VideoProfile toProfile);

        /**
         * Provides a response to a request to change the current video session properties.
         * <p>
         * For example, if the peer requests and upgrade from an audio-only call to a bi-directional
         * video call, could decline the request and keep the call as audio-only.
         * In such a scenario, the {@code responseProfile} would have a video state of
         * {@link VideoProfile#STATE_AUDIO_ONLY}.  If the user had decided to accept the request,
         * the video state would be {@link VideoProfile#STATE_BIDIRECTIONAL}.
         * <p>
         * Sent from the {@link InCallService} via
         * {@link InCallService.VideoCall#sendSessionModifyResponse(VideoProfile)} in response to
         * a {@link InCallService.VideoCall.Callback#onSessionModifyRequestReceived(VideoProfile)}
         * callback.
         *
         * @param responseProfile The response video profile.
         */
        public abstract void onSendSessionModifyResponse(VideoProfile responseProfile);

        /**
         * Issues a request to the {@link VideoProvider} to retrieve the camera capabilities.
         * <p>
         * The {@link VideoProvider} should respond by communicating the capabilities of the chosen
         * camera via
         * {@link VideoProvider#changeCameraCapabilities(VideoProfile.CameraCapabilities)}.
         * <p>
         * Sent from the {@link InCallService} via
         * {@link InCallService.VideoCall#requestCameraCapabilities()}.
         */
        public abstract void onRequestCameraCapabilities();

        /**
         * Issues a request to the {@link VideoProvider} to retrieve the current data usage for the
         * video component of the current {@link Connection}.
         * <p>
         * The {@link VideoProvider} should respond by communicating current data usage, in bytes,
         * via {@link VideoProvider#setCallDataUsage(long)}.
         * <p>
         * Sent from the {@link InCallService} via
         * {@link InCallService.VideoCall#requestCallDataUsage()}.
         */
        public abstract void onRequestConnectionDataUsage();

        /**
         * Provides the {@link VideoProvider} with the {@link Uri} of an image to be displayed to
         * the peer device when the video signal is paused.
         * <p>
         * Sent from the {@link InCallService} via
         * {@link InCallService.VideoCall#setPauseImage(Uri)}.
         *
         * @param uri URI of image to display.
         */
        public abstract void onSetPauseImage(Uri uri);

        /**
         * Used to inform listening {@link InCallService} implementations when the
         * {@link VideoProvider} receives a session modification request.
         * <p>
         * Received by the {@link InCallService} via
         * {@link InCallService.VideoCall.Callback#onSessionModifyRequestReceived(VideoProfile)},
         *
         * @param videoProfile The requested video profile.
         * @see #onSendSessionModifyRequest(VideoProfile, VideoProfile)
         */
        public void receiveSessionModifyRequest(VideoProfile videoProfile) {
            if (mVideoCallbacks != null) {
                for (IVideoCallback callback : mVideoCallbacks.values()) {
                    try {
                        callback.receiveSessionModifyRequest(videoProfile);
                    } catch (RemoteException ignored) {
                        Log.w(this, "receiveSessionModifyRequest callback failed", ignored);
                    }
                }
            }
        }

        /**
         * Used to inform listening {@link InCallService} implementations when the
         * {@link VideoProvider} receives a response to a session modification request.
         * <p>
         * Received by the {@link InCallService} via
         * {@link InCallService.VideoCall.Callback#onSessionModifyResponseReceived(int,
         * VideoProfile, VideoProfile)}.
         *
         * @param status Status of the session modify request.  Valid values are
         *               {@link VideoProvider#SESSION_MODIFY_REQUEST_SUCCESS},
         *               {@link VideoProvider#SESSION_MODIFY_REQUEST_FAIL},
         *               {@link VideoProvider#SESSION_MODIFY_REQUEST_INVALID},
         *               {@link VideoProvider#SESSION_MODIFY_REQUEST_TIMED_OUT},
         *               {@link VideoProvider#SESSION_MODIFY_REQUEST_REJECTED_BY_REMOTE}
         * @param requestedProfile The original request which was sent to the peer device.
         * @param responseProfile The actual profile changes agreed to by the peer device.
         * @see #onSendSessionModifyRequest(VideoProfile, VideoProfile)
         */
        public void receiveSessionModifyResponse(int status,
                VideoProfile requestedProfile, VideoProfile responseProfile) {
            if (mVideoCallbacks != null) {
                for (IVideoCallback callback : mVideoCallbacks.values()) {
                    try {
                        callback.receiveSessionModifyResponse(status, requestedProfile,
                                responseProfile);
                    } catch (RemoteException ignored) {
                        Log.w(this, "receiveSessionModifyResponse callback failed", ignored);
                    }
                }
            }
        }

        /**
         * Used to inform listening {@link InCallService} implementations when the
         * {@link VideoProvider} reports a call session event.
         * <p>
         * Received by the {@link InCallService} via
         * {@link InCallService.VideoCall.Callback#onCallSessionEvent(int)}.
         *
         * @param event The event.  Valid values are: {@link VideoProvider#SESSION_EVENT_RX_PAUSE},
         *      {@link VideoProvider#SESSION_EVENT_RX_RESUME},
         *      {@link VideoProvider#SESSION_EVENT_TX_START},
         *      {@link VideoProvider#SESSION_EVENT_TX_STOP},
         *      {@link VideoProvider#SESSION_EVENT_CAMERA_FAILURE},
         *      {@link VideoProvider#SESSION_EVENT_CAMERA_READY}.
         */
        public void handleCallSessionEvent(int event) {
            if (mVideoCallbacks != null) {
                for (IVideoCallback callback : mVideoCallbacks.values()) {
                    try {
                        callback.handleCallSessionEvent(event);
                    } catch (RemoteException ignored) {
                        Log.w(this, "handleCallSessionEvent callback failed", ignored);
                    }
                }
            }
        }

        /**
         * Used to inform listening {@link InCallService} implementations when the dimensions of the
         * peer's video have changed.
         * <p>
         * This could occur if, for example, the peer rotates their device, changing the aspect
         * ratio of the video, or if the user switches between the back and front cameras.
         * <p>
         * Received by the {@link InCallService} via
         * {@link InCallService.VideoCall.Callback#onPeerDimensionsChanged(int, int)}.
         *
         * @param width  The updated peer video width.
         * @param height The updated peer video height.
         */
        public void changePeerDimensions(int width, int height) {
            if (mVideoCallbacks != null) {
                for (IVideoCallback callback : mVideoCallbacks.values()) {
                    try {
                        callback.changePeerDimensions(width, height);
                    } catch (RemoteException ignored) {
                        Log.w(this, "changePeerDimensions callback failed", ignored);
                    }
                }
            }
        }

        /**
         * Used to inform listening {@link InCallService} implementations when the data usage of the
         * video associated with the current {@link Connection} has changed.
         * <p>
         * This could be in response to a preview request via
         * {@link #onRequestConnectionDataUsage()}, or as a periodic update by the
         * {@link VideoProvider}.  Where periodic updates of data usage are provided, they should be
         * provided at most for every 1 MB of data transferred and no more than once every 10 sec.
         * <p>
         * Received by the {@link InCallService} via
         * {@link InCallService.VideoCall.Callback#onCallDataUsageChanged(long)}.
         *
         * @param dataUsage The updated data usage (in bytes).  Reported as the cumulative bytes
         *                  used since the start of the call.
         */
        public void setCallDataUsage(long dataUsage) {
            if (mVideoCallbacks != null) {
                for (IVideoCallback callback : mVideoCallbacks.values()) {
                    try {
                        callback.changeCallDataUsage(dataUsage);
                    } catch (RemoteException ignored) {
                        Log.w(this, "setCallDataUsage callback failed", ignored);
                    }
                }
            }
        }

        /**
         * @see #setCallDataUsage(long)
         *
         * @param dataUsage The updated data usage (in byes).
         * @deprecated - Use {@link #setCallDataUsage(long)} instead.
         * @hide
         */
        public void changeCallDataUsage(long dataUsage) {
            setCallDataUsage(dataUsage);
        }

        /**
         * Used to inform listening {@link InCallService} implementations when the capabilities of
         * the current camera have changed.
         * <p>
         * The {@link VideoProvider} should call this in response to
         * {@link VideoProvider#onRequestCameraCapabilities()}, or when the current camera is
         * changed via {@link VideoProvider#onSetCamera(String)}.
         * <p>
         * Received by the {@link InCallService} via
         * {@link InCallService.VideoCall.Callback#onCameraCapabilitiesChanged(
         * VideoProfile.CameraCapabilities)}.
         *
         * @param cameraCapabilities The new camera capabilities.
         */
        public void changeCameraCapabilities(VideoProfile.CameraCapabilities cameraCapabilities) {
            if (mVideoCallbacks != null) {
                for (IVideoCallback callback : mVideoCallbacks.values()) {
                    try {
                        callback.changeCameraCapabilities(cameraCapabilities);
                    } catch (RemoteException ignored) {
                        Log.w(this, "changeCameraCapabilities callback failed", ignored);
                    }
                }
            }
        }

        /**
         * Used to inform listening {@link InCallService} implementations when the video quality
         * of the call has changed.
         * <p>
         * Received by the {@link InCallService} via
         * {@link InCallService.VideoCall.Callback#onVideoQualityChanged(int)}.
         *
         * @param videoQuality The updated video quality.  Valid values:
         *      {@link VideoProfile#QUALITY_HIGH},
         *      {@link VideoProfile#QUALITY_MEDIUM},
         *      {@link VideoProfile#QUALITY_LOW},
         *      {@link VideoProfile#QUALITY_DEFAULT}.
         */
        public void changeVideoQuality(int videoQuality) {
            if (mVideoCallbacks != null) {
                for (IVideoCallback callback : mVideoCallbacks.values()) {
                    try {
                        callback.changeVideoQuality(videoQuality);
                    } catch (RemoteException ignored) {
                        Log.w(this, "changeVideoQuality callback failed", ignored);
                    }
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
    private final List<Conferenceable> mConferenceables = new ArrayList<>();
    private final List<Conferenceable> mUnmodifiableConferenceables =
            Collections.unmodifiableList(mConferenceables);

    private int mState = STATE_NEW;
    private CallAudioState mCallAudioState;
    private Uri mAddress;
    private int mAddressPresentation;
    private String mCallerDisplayName;
    private int mCallerDisplayNamePresentation;
    private boolean mRingbackRequested = false;
    private int mConnectionCapabilities;
    private VideoProvider mVideoProvider;
    private boolean mAudioModeIsVoip;
    private long mConnectTimeMillis = Conference.CONNECT_TIME_NOT_SPECIFIED;
    private StatusHints mStatusHints;
    private int mVideoState;
    private DisconnectCause mDisconnectCause;
    private Conference mConference;
    private ConnectionService mConnectionService;
    private Bundle mExtras;

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
     * Valid values: {@link VideoProfile#STATE_AUDIO_ONLY},
     * {@link VideoProfile#STATE_BIDIRECTIONAL},
     * {@link VideoProfile#STATE_TX_ENABLED},
     * {@link VideoProfile#STATE_RX_ENABLED}.
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
     * @deprecated Use {@link #getCallAudioState()} instead.
     * @hide
     */
    @SystemApi
    @Deprecated
    public final AudioState getAudioState() {
        if (mCallAudioState == null) {
          return null;
        }
        return new AudioState(mCallAudioState);
    }

    /**
     * @return The audio state of the connection, describing how its audio is currently
     *         being routed by the system. This is {@code null} if this Connection
     *         does not directly know about its audio state.
     */
    public final CallAudioState getCallAudioState() {
        return mCallAudioState;
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
     * Retrieves the connection start time of the {@code Connnection}, if specified.  A value of
     * {@link Conference#CONNECT_TIME_NOT_SPECIFIED} indicates that Telecom should determine the
     * start time of the conference.
     *
     * @return The time at which the {@code Connnection} was connected.
     *
     * @hide
     */
    public final long getConnectTimeMillis() {
        return mConnectTimeMillis;
    }

    /**
     * @return The status hints for this connection.
     */
    public final StatusHints getStatusHints() {
        return mStatusHints;
    }

    /**
     * @return The extras associated with this connection.
     */
    public final Bundle getExtras() {
        return mExtras;
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
    final void setCallAudioState(CallAudioState state) {
        checkImmutable();
        Log.d(this, "setAudioState %s", state);
        mCallAudioState = state;
        onAudioStateChanged(getAudioState());
        onCallAudioStateChanged(state);
    }

    /**
     * @param state An integer value of a {@code STATE_*} constant.
     * @return A string representation of the value.
     */
    public static String stateToString(int state) {
        switch (state) {
            case STATE_INITIALIZING:
                return "INITIALIZING";
            case STATE_NEW:
                return "NEW";
            case STATE_RINGING:
                return "RINGING";
            case STATE_DIALING:
                return "DIALING";
            case STATE_ACTIVE:
                return "ACTIVE";
            case STATE_HOLDING:
                return "HOLDING";
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
     * Valid values: {@link VideoProfile#STATE_AUDIO_ONLY},
     * {@link VideoProfile#STATE_BIDIRECTIONAL},
     * {@link VideoProfile#STATE_TX_ENABLED},
     * {@link VideoProfile#STATE_RX_ENABLED}.
     *
     * @param videoState The new video state.
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
     */
    public final void setVideoProvider(VideoProvider videoProvider) {
        checkImmutable();
        mVideoProvider = videoProvider;
        for (Listener l : mListeners) {
            l.onVideoProviderChanged(this, videoProvider);
        }
    }

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
     * and (b) it wishes to signal Telecom to play the corresponding DTMF tone locally.
     *
     * @param nextChar The DTMF character that was just processed by the {@code Connection}.
     */
    public final void setNextPostDialChar(char nextChar) {
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
     * Sets the time at which a call became active on this Connection. This is set only
     * when a conference call becomes active on this connection.
     *
     * @param connectionTimeMillis The connection time, in milliseconds.
     *
     * @hide
     */
    public final void setConnectTimeMillis(long connectTimeMillis) {
        mConnectTimeMillis = connectTimeMillis;
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
    public final void setConferenceables(List<Conferenceable> conferenceables) {
        clearConferenceableList();
        for (Conferenceable c : conferenceables) {
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
    public final List<Conferenceable> getConferenceables() {
        return mUnmodifiableConferenceables;
    }

    /**
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
     * Set some extras that can be associated with this {@code Connection}. No assumptions should
     * be made as to how an In-Call UI or service will handle these extras.
     * Keys should be fully qualified (e.g., com.example.MY_EXTRA) to avoid conflicts.
     *
     * @param extras The extras associated with this {@code Connection}.
     */
    public final void setExtras(@Nullable Bundle extras) {
        checkImmutable();
        mExtras = extras;
        for (Listener l : mListeners) {
            l.onExtrasChanged(this, extras);
        }
    }

    /**
     * Notifies this Connection that the {@link #getAudioState()} property has a new value.
     *
     * @param state The new connection audio state.
     * @deprecated Use {@link #onCallAudioStateChanged(CallAudioState)} instead.
     * @hide
     */
    @SystemApi
    @Deprecated
    public void onAudioStateChanged(AudioState state) {}

    /**
     * Notifies this Connection that the {@link #getCallAudioState()} property has a new value.
     *
     * @param state The new connection audio state.
     */
    public void onCallAudioStateChanged(CallAudioState state) {}

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
     */
    public void onAnswer(int videoState) {}

    /**
     * Notifies this Connection, which is in {@link #STATE_RINGING}, of
     * a request to accept.
     */
    public void onAnswer() {
        onAnswer(VideoProfile.STATE_AUDIO_ONLY);
    }

    /**
     * Notifies this Connection, which is in {@link #STATE_RINGING}, of
     * a request to reject.
     */
    public void onReject() {}

    /**
     * Notifies ths Connection of a request reject with a message.
     *
     * @hide
     */
    public void onReject(String replyMessage) {}

    /**
     * Notifies the Connection of a request to silence the ringer.
     *
     * @hide
     */
    public void onSilence() {}

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
        for (Conferenceable c : mConferenceables) {
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
     * Notifies listeners that the merge request failed.
     *
     * @hide
     */
    protected final void notifyConferenceMergeFailed() {
        for (Listener l : mListeners) {
            l.onConferenceMergeFailed(this);
        }
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
     * @hide
     */
    protected void notifyConferenceStarted() {
        for (Listener l : mListeners) {
            l.onConferenceStarted();
        }
    }

    /**
     * Sends a connection event to Telecom.
     *
     * @param event The connection event.
     * @hide
     */
    protected void sendConnectionEvent(String event) {
        for (Listener l : mListeners) {
            l.onConnectionEvent(this, event);
        }
    }
}
