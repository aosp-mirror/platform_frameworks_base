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

import android.app.PendingIntent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import com.android.internal.os.SomeArgs;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Represents a connection to a remote endpoint that carries voice traffic.
 */
public abstract class Connection {

    private static final int MSG_ADD_CONNECTION_LISTENER = 1;
    private static final int MSG_REMOVE_CONNECTION_LISTENER = 2;
    private static final int MSG_SET_AUDIO_STATE = 3;
    private static final int MSG_SET_PARENT_CONNECTION = 4;
    private static final int MSG_SET_HANDLE = 5;
    private static final int MSG_SET_CALLER_DISPLAY_NAME = 6;
    private static final int MSG_SET_CANCELED = 7;
    private static final int MSG_SET_FAILED = 8;
    private static final int MSG_SET_VIDEO_STATE = 9;
    private static final int MSG_SET_ACTIVE = 10;
    private static final int MSG_SET_RINGING = 11;
    private static final int MSG_SET_INITIALIZING = 12;
    private static final int MSG_SET_INITIALIZED = 13;
    private static final int MSG_SET_DIALING = 14;
    private static final int MSG_SET_ON_HOLD = 15;
    private static final int MSG_SET_VIDEO_CALL_PROVIDER = 16;
    private static final int MSG_SET_DISCONNECTED = 17;
    private static final int MSG_SET_POST_DIAL_WAIT = 18;
    private static final int MSG_SET_REQUESTING_RINGBACK = 19;
    private static final int MSG_SET_CALL_CAPABILITIES = 20;
    private static final int MSG_DESTROY = 21;
    private static final int MSG_SET_SIGNAL = 22;
    private static final int MSG_SET_AUDIO_MODE_IS_VOIP = 23;
    private static final int MSG_SET_STATUS_HINTS = 24;
    private static final int MSG_START_ACTIVITY_FROM_IN_CALL = 25;

    /** @hide */
    public abstract static class Listener {
        public void onStateChanged(Connection c, int state) {}
        public void onHandleChanged(Connection c, Uri newHandle, int presentation) {}
        public void onCallerDisplayNameChanged(
                Connection c, String callerDisplayName, int presentation) {}
        public void onVideoStateChanged(Connection c, int videoState) {}
        public void onSignalChanged(Connection c, Bundle details) {}
        public void onDisconnected(Connection c, int cause, String message) {}
        public void onPostDialWait(Connection c, String remaining) {}
        public void onRequestingRingback(Connection c, boolean ringback) {}
        public void onDestroyed(Connection c) {}
        public void onCallCapabilitiesChanged(Connection c, int callCapabilities) {}
        public void onParentConnectionChanged(Connection c, Connection parent) {}
        public void onVideoCallProviderChanged(
                Connection c, ConnectionService.VideoCallProvider videoCallProvider) {}
        public void onAudioModeIsVoipChanged(Connection c, boolean isVoip) {}
        public void onStatusHintsChanged(Connection c, StatusHints statusHints) {}
        public void onStartActivityFromInCall(Connection c, PendingIntent intent) {}
    }

    public final class State {
        private State() {}

        public static final int INITIALIZING = 0;
        public static final int NEW = 1;
        public static final int RINGING = 2;
        public static final int DIALING = 3;
        public static final int ACTIVE = 4;
        public static final int HOLDING = 5;
        public static final int DISCONNECTED = 6;
        public static final int FAILED = 7;
        public static final int CANCELED = 8;

    }

    private final Set<Listener> mListeners = new HashSet<>();
    private final List<Connection> mChildConnections = new ArrayList<>();

    private int mState = State.NEW;
    private CallAudioState mCallAudioState;
    private Uri mHandle;
    private int mHandlePresentation;
    private String mCallerDisplayName;
    private int mCallerDisplayNamePresentation;
    private boolean mRequestingRingback = false;
    private int mCallCapabilities;
    private Connection mParentConnection;
    private ConnectionService.VideoCallProvider mVideoCallProvider;
    private boolean mAudioModeIsVoip;
    private StatusHints mStatusHints;
    private int mVideoState;
    private int mFailureCode;
    private String mFailureMessage;
    private boolean mIsCanceled;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_ADD_CONNECTION_LISTENER: {
                    Listener listener = (Listener) msg.obj;
                    mListeners.add(listener);
                }
                break;
                case MSG_REMOVE_CONNECTION_LISTENER: {
                    Listener listener = (Listener) msg.obj;
                    mListeners.remove(listener);
                }
                break;
                case MSG_SET_AUDIO_STATE: {
                    CallAudioState state = (CallAudioState) msg.obj;
                    mCallAudioState = state;
                    onSetAudioState(state);
                }
                break;
                case MSG_SET_PARENT_CONNECTION: {
                    Connection parentConnection = (Connection) msg.obj;
                    if (mParentConnection != parentConnection) {
                        if (mParentConnection != null) {
                            mParentConnection.removeChild(Connection.this);
                        }
                        mParentConnection = parentConnection;
                        if (mParentConnection != null) {
                            mParentConnection.addChild(Connection.this);
                            // do something if the child connections goes down to ZERO.
                        }
                        for (Listener l : mListeners) {
                            l.onParentConnectionChanged(Connection.this, mParentConnection);
                        }
                    }
                }
                break;
                case MSG_SET_HANDLE: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        Uri handle = (Uri) args.arg1;
                        int presentation = args.argi1;
                        mHandle = handle;
                        mHandlePresentation = presentation;
                        for (Listener l : mListeners) {
                            l.onHandleChanged(Connection.this, handle, presentation);
                        }
                    } finally {
                        args.recycle();
                    }
                }
                break;
                case MSG_SET_CALLER_DISPLAY_NAME: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        String callerDisplayName = (String) args.arg1;
                        int presentation = args.argi1;
                        mCallerDisplayName = callerDisplayName;
                        mCallerDisplayNamePresentation = presentation;
                        for (Listener l : mListeners) {
                            l.onCallerDisplayNameChanged(Connection.this, callerDisplayName,
                                    presentation);
                        }
                    } finally {
                        args.recycle();
                    }
                }
                break;
                case MSG_SET_CANCELED: {
                    setState(State.CANCELED);
                }
                break;
                case MSG_SET_FAILED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        int code = args.argi1;
                        String message = (String) args.arg1;
                        mFailureCode = code;
                        mFailureMessage = message;
                        setState(State.FAILED);
                    } finally {
                        args.recycle();
                    }
                }
                break;
                case MSG_SET_VIDEO_STATE: {
                    int videoState = ((Integer) msg.obj).intValue();
                    mVideoState = videoState;
                    for (Listener l : mListeners) {
                        l.onVideoStateChanged(Connection.this, mVideoState);
                    }
                }
                break;
                case MSG_SET_ACTIVE: {
                    setRequestingRingback(false);
                    setState(State.ACTIVE);
                }
                break;
                case MSG_SET_RINGING: {
                    setState(State.RINGING);
                }
                break;
                case MSG_SET_INITIALIZING: {
                    setState(State.INITIALIZING);
                }
                break;
                case MSG_SET_INITIALIZED: {
                    setState(State.NEW);
                }
                break;
                case MSG_SET_DIALING: {
                    setState(State.DIALING);
                }
                break;
                case MSG_SET_ON_HOLD: {
                    setState(State.HOLDING);
                }
                break;
                case MSG_SET_VIDEO_CALL_PROVIDER: {
                    ConnectionService.VideoCallProvider videoCallProvider =
                            (ConnectionService.VideoCallProvider) msg.obj;
                    mVideoCallProvider = videoCallProvider;
                    for (Listener l : mListeners) {
                        l.onVideoCallProviderChanged(Connection.this, videoCallProvider);
                    }
                }
                break;
                case MSG_SET_DISCONNECTED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        int cause = args.argi1;
                        String message = (String) args.arg1;
                        setState(State.DISCONNECTED);
                        Log.d(this, "Disconnected with cause %d message %s", cause, message);
                        for (Listener l : mListeners) {
                            l.onDisconnected(Connection.this, cause, message);
                        }
                    } finally {
                        args.recycle();
                    }
                }
                break;
                case MSG_SET_POST_DIAL_WAIT: {
                    String remaining = (String) msg.obj;
                    for (Listener l : mListeners) {
                        l.onPostDialWait(Connection.this, remaining);
                    }
                }
                break;
                case MSG_SET_REQUESTING_RINGBACK: {
                    boolean ringback = ((Boolean) msg.obj).booleanValue();
                    if (mRequestingRingback != ringback) {
                        mRequestingRingback = ringback;
                        for (Listener l : mListeners) {
                            l.onRequestingRingback(Connection.this, ringback);
                        }
                    }
                } break;
                case MSG_SET_CALL_CAPABILITIES: {
                    int callCapabilities = ((Integer) msg.obj).intValue();
                    if (mCallCapabilities != callCapabilities) {
                        mCallCapabilities = callCapabilities;
                        for (Listener l : mListeners) {
                            l.onCallCapabilitiesChanged(Connection.this, mCallCapabilities);
                        }
                    }
                }
                break;
                case MSG_DESTROY: {
                    // TODO: Is this still relevant because everything is on the main thread now.
                    // It is possible that onDestroy() will trigger the listener to remove itself
                    // which will result in a concurrent modification exception. To counteract
                    // this we make a copy of the listeners and iterate on that.
                    for (Listener l : new ArrayList<>(mListeners)) {
                        if (mListeners.contains(l)) {
                            l.onDestroyed(Connection.this);
                        }
                    }
                }
                break;
                case MSG_SET_SIGNAL: {
                    Bundle details = (Bundle) msg.obj;
                    for (Listener l : mListeners) {
                        l.onSignalChanged(Connection.this, details);
                    }
                }
                break;
                case MSG_SET_AUDIO_MODE_IS_VOIP: {
                    boolean isVoip = ((Boolean) msg.obj).booleanValue();
                    mAudioModeIsVoip = isVoip;
                    for (Listener l : mListeners) {
                        l.onAudioModeIsVoipChanged(Connection.this, isVoip);
                    }
                }
                break;
                case MSG_SET_STATUS_HINTS: {
                    StatusHints statusHints = (StatusHints) msg.obj;
                    mStatusHints = statusHints;
                    for (Listener l : mListeners) {
                        l.onStatusHintsChanged(Connection.this, statusHints);
                    }
                }
                break;
                case MSG_START_ACTIVITY_FROM_IN_CALL: {
                    PendingIntent intent = (PendingIntent) msg.obj;
                    for (Listener l : mListeners) {
                        l.onStartActivityFromInCall(Connection.this, intent);
                    }
                }
                break;
            }
        }
    };

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
     * @return The {@link CallPropertyPresentation} which controls how the handle is shown.
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
     * @return The {@link CallPropertyPresentation} which controls how the caller display name is
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
     * Valid values: {@link android.telecomm.VideoCallProfile.VideoState#AUDIO_ONLY},
     * {@link android.telecomm.VideoCallProfile.VideoState#BIDIRECTIONAL},
     * {@link android.telecomm.VideoCallProfile.VideoState#TX_ENABLED},
     * {@link android.telecomm.VideoCallProfile.VideoState#RX_ENABLED}.
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
    public final CallAudioState getCallAudioState() {
        return mCallAudioState;
    }

    /**
     * Returns whether this connection is requesting that the system play a ringback tone
     * on its behalf.
     */
    public final boolean isRequestingRingback() {
        return mRequestingRingback;
    }

    /**
     * Returns whether this connection is a conference connection (has child connections).
     */
    public final boolean isConferenceConnection() {
        return !mChildConnections.isEmpty();
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
        mHandler.obtainMessage(MSG_ADD_CONNECTION_LISTENER, l).sendToTarget();
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
        mHandler.obtainMessage(MSG_REMOVE_CONNECTION_LISTENER, l).sendToTarget();
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
    final void setAudioState(CallAudioState state) {
        Log.d(this, "setAudioState %s", state);
        mHandler.obtainMessage(MSG_SET_AUDIO_STATE, state).sendToTarget();
    }

    /**
     * @param state An integer value from {@link State}.
     * @return A string representation of the value.
     */
    public static String stateToString(int state) {
        switch (state) {
            case State.INITIALIZING:
                return "INITIALIZING";
            case State.NEW:
                return "NEW";
            case State.RINGING:
                return "RINGING";
            case State.DIALING:
                return "DIALING";
            case State.ACTIVE:
                return "ACTIVE";
            case State.HOLDING:
                return "HOLDING";
            case State.DISCONNECTED:
                return "DISCONNECTED";
            case State.FAILED:
                return "FAILED";
            case State.CANCELED:
                return "CANCELED";
            default:
                Log.wtf(Connection.class, "Unknown state %d", state);
                return "UNKNOWN";
        }
    }

    /**
     * TODO(santoscordon): Needs documentation.
     */
    public final void setParentConnection(Connection parentConnection) {
        Log.d(this, "parenting %s to %s", this, parentConnection);
        mHandler.obtainMessage(MSG_SET_PARENT_CONNECTION, parentConnection).sendToTarget();
    }

    public final Connection getParentConnection() {
        return mParentConnection;
    }

    public final List<Connection> getChildConnections() {
        return mChildConnections;
    }

    /**
     * Returns the connection's {@link CallCapabilities}
     */
    public final int getCallCapabilities() {
        return mCallCapabilities;
    }

    /**
     * Sets the value of the {@link #getHandle()} property.
     *
     * @param handle The new handle.
     * @param presentation The {@link CallPropertyPresentation} which controls how the handle is
     *         shown.
     */
    public final void setHandle(Uri handle, int presentation) {
        Log.d(this, "setHandle %s", handle);
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = handle;
        args.argi1 = presentation;
        mHandler.obtainMessage(MSG_SET_HANDLE, args).sendToTarget();
    }

    /**
     * Sets the caller display name (CNAP).
     *
     * @param callerDisplayName The new display name.
     * @param presentation The {@link CallPropertyPresentation} which controls how the name is
     *         shown.
     */
    public final void setCallerDisplayName(String callerDisplayName, int presentation) {
        Log.d(this, "setCallerDisplayName %s", callerDisplayName);
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = callerDisplayName;
        args.argi1 = presentation;
        mHandler.obtainMessage(MSG_SET_CALLER_DISPLAY_NAME, args).sendToTarget();
    }

    /**
     * Cancel the {@link Connection}. Once this is called, the {@link Connection} will not be used,
     * and no subsequent {@link Connection}s will be attempted.
     */
    public final void setCanceled() {
        Log.d(this, "setCanceled");
        mHandler.obtainMessage(MSG_SET_CANCELED).sendToTarget();
    }

    /**
     * Move the {@link Connection} to the {@link State#FAILED} state, with the given code
     * ({@see DisconnectCause}) and message. This message is not shown to the user, but is useful
     * for logging and debugging purposes.
     * <p>
     * After calling this, the {@link Connection} will not be used.
     *
     * @param code The {@link android.telephony.DisconnectCause} indicating why the connection
     *             failed.
     * @param message A message explaining why the {@link Connection} failed.
     */
    public final void setFailed(int code, String message) {
        Log.d(this, "setFailed (%d: %s)", code, message);
        SomeArgs args = SomeArgs.obtain();
        args.argi1 = code;
        args.arg1 = message;
        mHandler.obtainMessage(MSG_SET_FAILED, args).sendToTarget();
    }

    /**
     * Set the video state for the connection.
     * Valid values: {@link android.telecomm.VideoCallProfile.VideoState#AUDIO_ONLY},
     * {@link android.telecomm.VideoCallProfile.VideoState#BIDIRECTIONAL},
     * {@link android.telecomm.VideoCallProfile.VideoState#TX_ENABLED},
     * {@link android.telecomm.VideoCallProfile.VideoState#RX_ENABLED}.
     *
     * @param videoState The new video state.
     */
    public final void setVideoState(int videoState) {
        Log.d(this, "setVideoState %d", videoState);
        mHandler.obtainMessage(MSG_SET_VIDEO_STATE, Integer.valueOf(videoState)).sendToTarget();
    }

    /**
     * Sets state to active (e.g., an ongoing call where two or more parties can actively
     * communicate).
     */
    public final void setActive() {
        mHandler.obtainMessage(MSG_SET_ACTIVE).sendToTarget();
    }

    /**
     * Sets state to ringing (e.g., an inbound ringing call).
     */
    public final void setRinging() {
        mHandler.obtainMessage(MSG_SET_RINGING).sendToTarget();
    }

    /**
     * Sets state to initializing (this Connection is not yet ready to be used).
     */
    public final void setInitializing() {
        mHandler.obtainMessage(MSG_SET_INITIALIZING).sendToTarget();
    }

    /**
     * Sets state to initialized (the Connection has been set up and is now ready to be used).
     */
    public final void setInitialized() {
        mHandler.obtainMessage(MSG_SET_INITIALIZED).sendToTarget();
        setState(State.NEW);
    }

    /**
     * Sets state to dialing (e.g., dialing an outbound call).
     */
    public final void setDialing() {
        mHandler.obtainMessage(MSG_SET_DIALING).sendToTarget();
    }

    /**
     * Sets state to be on hold.
     */
    public final void setOnHold() {
        mHandler.obtainMessage(MSG_SET_ON_HOLD).sendToTarget();
    }

    /**
     * Sets the video call provider.
     * @param videoCallProvider The video call provider.
     */
    public final void setVideoCallProvider(ConnectionService.VideoCallProvider videoCallProvider) {
        mHandler.obtainMessage(MSG_SET_VIDEO_CALL_PROVIDER, videoCallProvider).sendToTarget();
    }

    public final ConnectionService.VideoCallProvider getVideoCallProvider() {
        return mVideoCallProvider;
    }

    /**
     * Sets state to disconnected.
     *
     * @param cause The reason for the disconnection, any of
     *         {@link android.telephony.DisconnectCause}.
     * @param message Optional call-service-provided message about the disconnect.
     */
    public final void setDisconnected(int cause, String message) {
        SomeArgs args = SomeArgs.obtain();
        args.argi1 = cause;
        args.arg1 = message;
        mHandler.obtainMessage(MSG_SET_DISCONNECTED, args).sendToTarget();
    }

    /**
     * TODO(santoscordon): Needs documentation.
     */
    public final void setPostDialWait(String remaining) {
        mHandler.obtainMessage(MSG_SET_POST_DIAL_WAIT, remaining).sendToTarget();
    }

    /**
     * Requests that the framework play a ringback tone. This is to be invoked by implementations
     * that do not play a ringback tone themselves in the call's audio stream.
     *
     * @param ringback Whether the ringback tone is to be played.
     */
    public final void setRequestingRingback(boolean ringback) {
        mHandler.obtainMessage(MSG_SET_REQUESTING_RINGBACK, Boolean.valueOf(ringback))
                .sendToTarget();
    }

    /**
     * Sets the connection's {@link CallCapabilities}.
     *
     * @param callCapabilities The new call capabilities.
     */
    public final void setCallCapabilities(int callCapabilities) {
        mHandler.obtainMessage(MSG_SET_CALL_CAPABILITIES, Integer.valueOf(callCapabilities))
                .sendToTarget();
    }

    /**
     * TODO(santoscordon): Needs documentation.
     */
    public final void destroy() {
        mHandler.obtainMessage(MSG_DESTROY).sendToTarget();
    }

    /**
     * Sets the current signal levels for the underlying data transport.
     *
     * @param details A {@link android.os.Bundle} containing details of the current level.
     */
    public final void setSignal(Bundle details) {
        mHandler.obtainMessage(MSG_SET_SIGNAL, details).sendToTarget();
    }

    /**
     * Requests that the framework use VOIP audio mode for this connection.
     *
     * @param isVoip True if the audio mode is VOIP.
     */
    public final void setAudioModeIsVoip(boolean isVoip) {
        mHandler.obtainMessage(MSG_SET_AUDIO_MODE_IS_VOIP, Boolean.valueOf(isVoip)).sendToTarget();
    }

    /**
     * Sets the label and icon status to display in the in-call UI.
     *
     * @param statusHints The status label and icon to set.
     */
    public final void setStatusHints(StatusHints statusHints) {
        mHandler.obtainMessage(MSG_SET_STATUS_HINTS, statusHints).sendToTarget();
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
        mHandler.obtainMessage(MSG_START_ACTIVITY_FROM_IN_CALL, intent).sendToTarget();
    }

    /**
     * Notifies this Connection that the {@link #getCallAudioState()} property has a new value.
     * <p>
     * This callback will happen on the main thread.
     *
     * @param state The new call audio state.
     */
    public void onSetAudioState(CallAudioState state) {}

    /**
     * Notifies this Connection of an internal state change. This method is called after the
     * state is changed.
     * <p>
     * This callback will happen on the main thread.
     *
     * @param state The new state, a {@link Connection.State} member.
     */
    public void onSetState(int state) {}

    /**
     * Notifies this Connection of a request to play a DTMF tone.
     * <p>
     * This callback will happen on the main thread.
     *
     * @param c A DTMF character.
     */
    public void onPlayDtmfTone(char c) {}

    /**
     * Notifies this Connection of a request to stop any currently playing DTMF tones.
     * <p>
     * This callback will happen on the main thread.
     */
    public void onStopDtmfTone() {}

    /**
     * Notifies this Connection of a request to disconnect.
     * <p>
     * This callback will happen on the main thread.
     */
    public void onDisconnect() {}

    /**
     * Notifies this Connection of a request to disconnect.
     * <p>
     * This callback will happen on the main thread.
     */
    public void onSeparate() {}

    /**
     * Notifies this Connection of a request to abort.
     * <p>
     * This callback will happen on the main thread.
     */
    public void onAbort() {}

    /**
     * Notifies this Connection of a request to hold.
     * <p>
     * This callback will happen on the main thread.
     */
    public void onHold() {}

    /**
     * Notifies this Connection of a request to exit a hold state.
     * <p>
     * This callback will happen on the main thread.
     */
    public void onUnhold() {}

    /**
     * Notifies this Connection, which is in {@link State#RINGING}, of a request to accept.
     * <p>
     * This callback will happen on the main thread.
     *
     * @param videoState The video state in which to answer the call.
     */
    public void onAnswer(int videoState) {}

    /**
     * Notifies this Connection, which is in {@link State#RINGING}, of a request to reject.
     * <p>
     * This callback will happen on the main thread.
     */
    public void onReject() {}

    /**
     * Notifies this Connection whether the user wishes to proceed with the post-dial DTMF codes.
     * <p>
     * This callback will happen on the main thread.
     */
    public void onPostDialContinue(boolean proceed) {}

    /**
     * Swap this call with a background call. This is used for calls that don't support hold,
     * e.g. CDMA.
     * <p>
     * This callback will happen on the main thread.
     */
    public void onSwapWithBackgroundCall() {}

    /**
     * TODO(santoscordon): Needs documentation.
     * <p>
     * This callback will happen on the main thread.
     */
    public void onChildrenChanged(List<Connection> children) {}

    /**
     * Called when the phone account UI was clicked.
     */
    public void onPhoneAccountClicked() {}

    /** This must be called from the main thread. */
    private void addChild(Connection connection) {
        Log.d(this, "adding child %s", connection);
        mChildConnections.add(connection);
        onChildrenChanged(mChildConnections);
    }

    /** This must be called from the main thread. */
    private void removeChild(Connection connection) {
        Log.d(this, "removing child %s", connection);
        mChildConnections.remove(connection);
        onChildrenChanged(mChildConnections);
    }

    private void setState(int state) {
        if (mState == State.FAILED || mState == State.CANCELED) {
            Log.d(this, "Connection already %s; cannot transition out of this state.",
                    stateToString(mState));
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

    /**
     * Return a {@link Connection} which represents a failed connection attempt. The returned
     * {@link Connection} will have {@link #getFailureCode()}, {@link #getFailureMessage()}, and
     * {@link #getState()} set appropriately, but the {@link Connection} itself should not be used
     * for anything.
     *
     * @param code The failure code ({@see DisconnectCause}).
     * @param message A reason for why the connection failed (not intended to be shown to the user).
     * @return A {@link Connection} which indicates failure.
     */
    public static Connection getFailedConnection(final int code, final String message) {
        return new Connection() {{
            setFailed(code, message);
        }};
    }

    private static final Connection CANCELED_CONNECTION = new Connection() {{
        setCanceled();
    }};

    /**
     * Return a {@link Connection} which represents a canceled a connection attempt. The returned
     * {@link Connection} will have state {@link State#CANCELED}, and cannot be moved out of that
     * state. This connection should not be used for anything, and no other {@link Connection}s
     * should be attempted.
     *
     * @return A {@link Connection} which indicates that the underlying call should be canceled.
     */
    public static Connection getCanceledConnection() {
        return CANCELED_CONNECTION;
    }
}
