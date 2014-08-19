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

import com.android.internal.telecomm.IVideoCallback;
import com.android.internal.telecomm.IVideoProvider;

import android.app.PendingIntent;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.telephony.DisconnectCause;
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
 */
public abstract class Connection {

    public static final int STATE_INITIALIZING = 0;

    public static final int STATE_NEW = 1;

    public static final int STATE_RINGING = 2;

    public static final int STATE_DIALING = 3;

    public static final int STATE_ACTIVE = 4;

    public static final int STATE_HOLDING = 5;

    public static final int STATE_DISCONNECTED = 6;

    // Flag controlling whether PII is emitted into the logs
    private static final boolean PII_DEBUG = Log.isLoggable(android.util.Log.DEBUG);

    private static Connection sNullConnection;

    /** @hide */
    public abstract static class Listener {
        public void onStateChanged(Connection c, int state) {}
        public void onHandleChanged(Connection c, Uri newHandle, int presentation) {}
        public void onCallerDisplayNameChanged(
                Connection c, String callerDisplayName, int presentation) {}
        public void onVideoStateChanged(Connection c, int videoState) {}
        public void onDisconnected(Connection c, int cause, String message) {}
        public void onPostDialWait(Connection c, String remaining) {}
        public void onRequestingRingback(Connection c, boolean ringback) {}
        public void onDestroyed(Connection c) {}
        public void onCallCapabilitiesChanged(Connection c, int callCapabilities) {}
        public void onVideoProviderChanged(
                Connection c, VideoProvider videoProvider) {}
        public void onAudioModeIsVoipChanged(Connection c, boolean isVoip) {}
        public void onStatusHintsChanged(Connection c, StatusHints statusHints) {}
        public void onStartActivityFromInCall(Connection c, PendingIntent intent) {}
        public void onConferenceableConnectionsChanged(
                Connection c, List<Connection> conferenceableConnections) {}
        public void onConferenceChanged(Connection c, Conference conference) {}
    }

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

        private static final int MSG_SET_VIDEO_LISTENER = 1;
        private static final int MSG_SET_CAMERA = 2;
        private static final int MSG_SET_PREVIEW_SURFACE = 3;
        private static final int MSG_SET_DISPLAY_SURFACE = 4;
        private static final int MSG_SET_DEVICE_ORIENTATION = 5;
        private static final int MSG_SET_ZOOM = 6;
        private static final int MSG_SEND_SESSION_MODIFY_REQUEST = 7;
        private static final int MSG_SEND_SESSION_MODIFY_RESPONSE = 8;
        private static final int MSG_REQUEST_CAMERA_CAPABILITIES = 9;
        private static final int MSG_REQUEST_CALL_DATA_USAGE = 10;
        private static final int MSG_SET_PAUSE_IMAGE = 11;

        private final VideoProvider.VideoProviderHandler
                mMessageHandler = new VideoProvider.VideoProviderHandler();
        private final VideoProvider.VideoProviderBinder mBinder;
        private IVideoCallback mVideoListener;

        /**
         * Default handler used to consolidate binder method calls onto a single thread.
         */
        private final class VideoProviderHandler extends Handler {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_SET_VIDEO_LISTENER:
                        mVideoListener = IVideoCallback.Stub.asInterface((IBinder) msg.obj);
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
                    case MSG_REQUEST_CALL_DATA_USAGE:
                        onRequestCallDataUsage();
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
            public void setVideoListener(IBinder videoListenerBinder) {
                mMessageHandler.obtainMessage(
                        MSG_SET_VIDEO_LISTENER, videoListenerBinder).sendToTarget();
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
                mMessageHandler.obtainMessage(MSG_REQUEST_CALL_DATA_USAGE).sendToTarget();
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
         * Sets the camera to be used for video recording in a video call.
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
         * Some examples of session modification requests: upgrade call from audio to video,
         * downgrade call from video to audio, pause video.
         *
         * @param requestProfile The requested call video properties.
         */
        public abstract void onSendSessionModifyRequest(VideoProfile requestProfile);

        /**te
         * Provides a response to a request to change the current call session video
         * properties.
         * This is in response to a request the InCall UI has received via the InCall UI.
         *
         * @param responseProfile The response call video properties.
         */
        public abstract void onSendSessionModifyResponse(VideoProfile responseProfile);

        /**
         * Issues a request to the video provider to retrieve the camera capabilities.
         * Camera capabilities are reported back to the caller via the In-Call UI.
         */
        public abstract void onRequestCameraCapabilities();

        /**
         * Issues a request to the video telephony framework to retrieve the cumulative data usage
         * for the current call.  Data usage is reported back to the caller via the
         * InCall UI.
         */
        public abstract void onRequestCallDataUsage();

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
         * @param videoProfile The requested video call profile.
         */
        public void receiveSessionModifyRequest(VideoProfile videoProfile) {
            if (mVideoListener != null) {
                try {
                    mVideoListener.receiveSessionModifyRequest(videoProfile);
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
            if (mVideoListener != null) {
                try {
                    mVideoListener.receiveSessionModifyResponse(
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
            if (mVideoListener != null) {
                try {
                    mVideoListener.handleCallSessionEvent(event);
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
            if (mVideoListener != null) {
                try {
                    mVideoListener.changePeerDimensions(width, height);
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
            if (mVideoListener != null) {
                try {
                    mVideoListener.changeCallDataUsage(dataUsage);
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
            if (mVideoListener != null) {
                try {
                    mVideoListener.changeCameraCapabilities(cameraCapabilities);
                } catch (RemoteException ignored) {
                }
            }
        }
    }

    private final Listener mConnectionDeathListener = new Listener() {
        @Override
        public void onDestroyed(Connection c) {
            if (mConferenceableConnections.remove(c)) {
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
    private final List<Connection> mConferenceableConnections = new ArrayList<>();
    private final List<Connection> mUnmodifiableConferenceableConnections =
            Collections.unmodifiableList(mConferenceableConnections);

    private int mState = STATE_NEW;
    private AudioState mAudioState;
    private Uri mHandle;
    private int mHandlePresentation;
    private String mCallerDisplayName;
    private int mCallerDisplayNamePresentation;
    private boolean mRequestingRingback = false;
    private int mCallCapabilities;
    private VideoProvider mVideoProvider;
    private boolean mAudioModeIsVoip;
    private StatusHints mStatusHints;
    private int mVideoState;
    private int mFailureCode;
    private String mFailureMessage;
    private boolean mIsCanceled;
    private Conference mConference;
    private ConnectionService mConnectionService;

    /**
     * Create a new Connection.
     */
    public Connection() {}

    /**
     * @return The handle (e.g., phone number) to which this Connection is currently communicating.
     */
    public final Uri getHandle() {
        return mHandle;
    }

    /**
     * @return The {@link PropertyPresentation} which controls how the handle is shown.
     */
    public final int getHandlePresentation() {
        return mHandlePresentation;
    }

    /**
     * @return The caller display name (CNAP).
     */
    public final String getCallerDisplayName() {
        return mCallerDisplayName;
    }

    /**
     * @return The {@link PropertyPresentation} which controls how the caller display name is
     *         shown.
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
     * Returns the video state of the call.
     * Valid values: {@link VideoProfile.VideoState#AUDIO_ONLY},
     * {@link VideoProfile.VideoState#BIDIRECTIONAL},
     * {@link VideoProfile.VideoState#TX_ENABLED},
     * {@link VideoProfile.VideoState#RX_ENABLED}.
     *
     * @return The video state of the call.
     */
    public final int getVideoState() {
        return mVideoState;
    }

    /**
     * @return The audio state of the call, describing how its audio is currently
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
    public final boolean isRequestingRingback() {
        return mRequestingRingback;
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
     * @return The failure code ({@see DisconnectCause}) associated with this failed connection.
     */
    public final int getFailureCode() {
        return mFailureCode;
    }

    /**
     * @return The reason for the connection failure. This will not be displayed to the user.
     */
    public final String getFailureMessage() {
        return mFailureMessage;
    }

    /**
     * Inform this Connection that the state of its audio output has been changed externally.
     *
     * @param state The new audio state.
     * @hide
     */
    final void setAudioState(AudioState state) {
        Log.d(this, "setAudioState %s", state);
        mAudioState = state;
        onSetAudioState(state);
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
     * Returns the connection's {@link PhoneCapabilities}
     */
    public final int getCallCapabilities() {
        return mCallCapabilities;
    }

    /**
     * Sets the value of the {@link #getHandle()} property.
     *
     * @param handle The new handle.
     * @param presentation The {@link PropertyPresentation} which controls how the handle is
     *         shown.
     */
    public final void setHandle(Uri handle, int presentation) {
        Log.d(this, "setHandle %s", handle);
        mHandle = handle;
        mHandlePresentation = presentation;
        for (Listener l : mListeners) {
            l.onHandleChanged(this, handle, presentation);
        }
    }

    /**
     * Sets the caller display name (CNAP).
     *
     * @param callerDisplayName The new display name.
     * @param presentation The {@link PropertyPresentation} which controls how the name is
     *         shown.
     */
    public final void setCallerDisplayName(String callerDisplayName, int presentation) {
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
     */
    public final void setVideoState(int videoState) {
        Log.d(this, "setVideoState %d", videoState);
        mVideoState = videoState;
        for (Listener l : mListeners) {
            l.onVideoStateChanged(this, mVideoState);
        }
    }

    /**
     * Sets state to active (e.g., an ongoing call where two or more parties can actively
     * communicate).
     */
    public final void setActive() {
        setRequestingRingback(false);
        setState(STATE_ACTIVE);
    }

    /**
     * Sets state to ringing (e.g., an inbound ringing call).
     */
    public final void setRinging() {
        setState(STATE_RINGING);
    }

    /**
     * Sets state to initializing (this Connection is not yet ready to be used).
     */
    public final void setInitializing() {
        setState(STATE_INITIALIZING);
    }

    /**
     * Sets state to initialized (the Connection has been set up and is now ready to be used).
     */
    public final void setInitialized() {
        setState(STATE_NEW);
    }

    /**
     * Sets state to dialing (e.g., dialing an outbound call).
     */
    public final void setDialing() {
        setState(STATE_DIALING);
    }

    /**
     * Sets state to be on hold.
     */
    public final void setOnHold() {
        setState(STATE_HOLDING);
    }

    /**
     * Sets the video call provider.
     * @param videoProvider The video provider.
     */
    public final void setVideoProvider(VideoProvider videoProvider) {
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
     * @param cause The reason for the disconnection, any of
     *         {@link DisconnectCause}.
     * @param message Optional call-service-provided message about the disconnect.
     */
    public final void setDisconnected(int cause, String message) {
        setState(STATE_DISCONNECTED);
        Log.d(this, "Disconnected with cause %d message %s", cause, message);
        for (Listener l : mListeners) {
            l.onDisconnected(this, cause, message);
        }
    }

    /**
     * TODO: Needs documentation.
     */
    public final void setPostDialWait(String remaining) {
        for (Listener l : mListeners) {
            l.onPostDialWait(this, remaining);
        }
    }

    /**
     * Requests that the framework play a ringback tone. This is to be invoked by implementations
     * that do not play a ringback tone themselves in the call's audio stream.
     *
     * @param ringback Whether the ringback tone is to be played.
     */
    public final void setRequestingRingback(boolean ringback) {
        if (mRequestingRingback != ringback) {
            mRequestingRingback = ringback;
            for (Listener l : mListeners) {
                l.onRequestingRingback(this, ringback);
            }
        }
    }

    /**
     * Sets the connection's {@link PhoneCapabilities}.
     *
     * @param callCapabilities The new call capabilities.
     */
    public final void setCallCapabilities(int callCapabilities) {
        if (mCallCapabilities != callCapabilities) {
            mCallCapabilities = callCapabilities;
            for (Listener l : mListeners) {
                l.onCallCapabilitiesChanged(this, mCallCapabilities);
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
        clearConferenceableList();
        for (Connection c : conferenceableConnections) {
            // If statement checks for duplicates in input. It makes it N^2 but we're dealing with a
            // small amount of items here.
            if (!mConferenceableConnections.contains(c)) {
                c.addConnectionListener(mConnectionDeathListener);
                mConferenceableConnections.add(c);
            }
        }
        fireOnConferenceableConnectionsChanged();
    }

    /**
     * Obtains the connections with which this connection can be conferenced.
     */
    public final List<Connection> getConferenceableConnections() {
        return mUnmodifiableConferenceableConnections;
    }

    /*
     * @hide
     */
    public final void setConnectionService(ConnectionService connectionService) {
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
     * Sets the conference that this connection is a part of. This will fail if the connection is
     * already part of a conference call. {@link #resetConference} to un-set the conference first.
     *
     * @param conference The conference.
     * @return {@code true} if the conference was successfully set.
     * @hide
     */
    public final boolean setConference(Conference conference) {
        // We check to see if it is already part of another conference.
        if (mConference == null && mConnectionService != null &&
                mConnectionService.containsConference(conference)) {
            mConference = conference;
            fireConferenceChanged();
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
            mConference = null;
            fireConferenceChanged();
        }
    }

    /**
     * Launches an activity for this connection on top of the in-call UI.
     *
     * @param intent The intent to use to start the activity.
     */
    public final void startActivityFromInCall(PendingIntent intent) {
        if (!intent.isActivity()) {
            throw new IllegalArgumentException("Activity intent required.");
        }
        for (Listener l : mListeners) {
            l.onStartActivityFromInCall(this, intent);
        }
    }

    /**
     * Notifies this Connection that the {@link #getAudioState()} property has a new value.
     *
     * @param state The new call audio state.
     */
    public void onSetAudioState(AudioState state) {}

    /**
     * Notifies this Connection of an internal state change. This method is called after the
     * state is changed.
     *
     * @param state The new state, one of the {@code STATE_*} constants.
     */
    public void onSetState(int state) {}

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
     * @param videoState The video state in which to answer the call.
     */
    public void onAnswer(int videoState) {}

    /**
     * Notifies this Connection, which is in {@link #STATE_RINGING}, of
     * a request to reject.
     */
    public void onReject() {}

    /**
     * Notifies this Connection whether the user wishes to proceed with the post-dial DTMF codes.
     */
    public void onPostDialContinue(boolean proceed) {}

    /**
     * Called when the phone account UI was clicked.
     */
    public void onPhoneAccountClicked() {}

    /**
     * Merge this connection and the specified connection into a conference call.  Once the
     * connections are merged, the calls should be added to the an existing or new
     * {@code Conference} instance. For new {@code Conference} instances, use
     * {@code ConnectionService#addConference}.
     *
     * @param otherConnection The connection with which this connection should be conferenced.
     */
    public void onConferenceWith(Connection otherConnection) {}

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

    static synchronized Connection getNullConnection() {
        if (sNullConnection == null) {
            sNullConnection = new Connection() {};
        }
        return sNullConnection;
    }

    private void setState(int state) {
        if (mState == STATE_DISCONNECTED && mState != state) {
            Log.d(this, "Connection already DISCONNECTED; cannot transition out of this state.");
            return;
        }
        if (mState != state) {
            Log.d(this, "setState: %s", stateToString(state));
            mState = state;
            for (Listener l : mListeners) {
                l.onStateChanged(this, state);
            }
            onSetState(state);
        }
    }

    static class FailureSignalingConnection extends Connection {
        public FailureSignalingConnection(int code, String message) {
            setDisconnected(code, message);
        }
    }

    /**
     * Return a {@code Connection} which represents a failed connection attempt. The returned
     * {@code Connection} will have a {@link #getFailureCode()} and {@link #getFailureMessage()}
     * as specified, a {@link #getState()} of {@link #STATE_DISCONNECTED}.
     * <p>
     * The returned {@code Connection} can be assumed to {@link #destroy()} itself when appropriate,
     * so users of this method need not maintain a reference to its return value to destroy it.
     *
     * @param code The failure code ({@see DisconnectCause}).
     * @param message A reason for why the connection failed (not intended to be shown to the user).
     * @return A {@code Connection} which indicates failure.
     */
    public static Connection createFailedConnection(final int code, final String message) {
        return new FailureSignalingConnection(code, message);
    }

    private static final Connection CANCELED_CONNECTION =
            new FailureSignalingConnection(DisconnectCause.OUTGOING_CANCELED, null);

    /**
     * Return a {@code Connection} which represents a canceled connection attempt. The returned
     * {@code Connection} will have state {@link #STATE_DISCONNECTED}, and cannot be moved out of
     * that state. This connection should not be used for anything, and no other
     * {@code Connection}s should be attempted.
     * <p>
     * The returned {@code Connection} can be assumed to {@link #destroy()} itself when appropriate,
     * so users of this method need not maintain a reference to its return value to destroy it.
     *
     * @return A {@code Connection} which indicates that the underlying call should be canceled.
     */
    public static Connection createCanceledConnection() {
        return CANCELED_CONNECTION;
    }

    private final void  fireOnConferenceableConnectionsChanged() {
        for (Listener l : mListeners) {
            l.onConferenceableConnectionsChanged(this, mConferenceableConnections);
        }
    }

    private final void fireConferenceChanged() {
        for (Listener l : mListeners) {
            l.onConferenceChanged(this, mConference);
        }
    }

    private final void clearConferenceableList() {
        for (Connection c : mConferenceableConnections) {
            c.removeConnectionListener(mConnectionDeathListener);
        }
        mConferenceableConnections.clear();
    }
}
