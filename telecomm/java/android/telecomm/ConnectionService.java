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

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.telephony.DisconnectCause;
import android.os.RemoteException;
import android.view.Surface;

import com.android.internal.os.SomeArgs;
import com.android.internal.telecomm.IConnectionService;
import com.android.internal.telecomm.IConnectionServiceAdapter;
import com.android.internal.telecomm.IVideoCallCallback;
import com.android.internal.telecomm.IVideoCallProvider;
import com.android.internal.telecomm.RemoteServiceCallback;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link android.app.Service} that provides telephone connections to processes running on an
 * Android device.
 */
public abstract class ConnectionService extends Service {
    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    @SdkConstant(SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE = "android.telecomm.ConnectionService";

    // Flag controlling whether PII is emitted into the logs
    private static final boolean PII_DEBUG = Log.isLoggable(android.util.Log.DEBUG);

    private static final int MSG_ADD_CONNECTION_SERVICE_ADAPTER = 1;
    private static final int MSG_CREATE_CONNECTION = 2;
    private static final int MSG_ABORT = 3;
    private static final int MSG_ANSWER = 4;
    private static final int MSG_REJECT = 5;
    private static final int MSG_DISCONNECT = 6;
    private static final int MSG_HOLD = 7;
    private static final int MSG_UNHOLD = 8;
    private static final int MSG_ON_AUDIO_STATE_CHANGED = 9;
    private static final int MSG_PLAY_DTMF_TONE = 10;
    private static final int MSG_STOP_DTMF_TONE = 11;
    private static final int MSG_CONFERENCE = 12;
    private static final int MSG_SPLIT_FROM_CONFERENCE = 13;
    private static final int MSG_SWAP_WITH_BACKGROUND_CALL = 14;
    private static final int MSG_ON_POST_DIAL_CONTINUE = 15;
    private static final int MSG_ON_PHONE_ACCOUNT_CLICKED = 16;
    private static final int MSG_REMOVE_CONNECTION_SERVICE_ADAPTER = 17;

    private static Connection sNullConnection;

    private final Map<String, Connection> mConnectionById = new HashMap<>();
    private final Map<Connection, String> mIdByConnection = new HashMap<>();
    private final RemoteConnectionManager mRemoteConnectionManager = new RemoteConnectionManager();

    private boolean mAreAccountsInitialized = false;
    private final List<Runnable> mPreInitializationConnectionRequests = new ArrayList<>();
    private final ConnectionServiceAdapter mAdapter = new ConnectionServiceAdapter();

    /**
     * A callback for providing the result of creating a connection.
     */
    public interface CreateConnectionResponse<CONNECTION> {
        /**
         * Tells Telecomm that an attempt to create the connection succeeded.
         *
         * @param request The original request.
         * @param connection The connection.
         */
        void onSuccess(ConnectionRequest request, CONNECTION connection);

        /**
         * Tells Telecomm that an attempt to create the connection failed. Telecomm will try a
         * different service until a service cancels the process or completes it successfully.
         *
         * @param request The original request.
         * @param code An integer code indicating the reason for failure.
         * @param msg A message explaining the reason for failure.
         */
        void onFailure(ConnectionRequest request, int code, String msg);

        /**
         * Tells Telecomm to cancel creating the connection. Telecomm will stop trying to create
         * the connection an no more services will be tried.
         *
         * @param request The original request.
         */
        void onCancel(ConnectionRequest request);
    }

    private final IBinder mBinder = new IConnectionService.Stub() {
        @Override
        public void addConnectionServiceAdapter(IConnectionServiceAdapter adapter) {
            mHandler.obtainMessage(MSG_ADD_CONNECTION_SERVICE_ADAPTER, adapter).sendToTarget();
        }

        public void removeConnectionServiceAdapter(IConnectionServiceAdapter adapter) {
            mHandler.obtainMessage(MSG_REMOVE_CONNECTION_SERVICE_ADAPTER, adapter).sendToTarget();
        }

        @Override
        public void createConnection(
                PhoneAccountHandle connectionManagerPhoneAccount,
                ConnectionRequest request,
                boolean isIncoming) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = connectionManagerPhoneAccount;
            args.arg2 = request;
            args.argi1 = isIncoming ? 1 : 0;
            mHandler.obtainMessage(MSG_CREATE_CONNECTION, args).sendToTarget();
        }

        @Override
        public void abort(String callId) {
            mHandler.obtainMessage(MSG_ABORT, callId).sendToTarget();
        }

        @Override
        public void answer(String callId, int videoState) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callId;
            args.argi1 = videoState;
            mHandler.obtainMessage(MSG_ANSWER, args).sendToTarget();
        }

        @Override
        public void reject(String callId) {
            mHandler.obtainMessage(MSG_REJECT, callId).sendToTarget();
        }

        @Override
        public void disconnect(String callId) {
            mHandler.obtainMessage(MSG_DISCONNECT, callId).sendToTarget();
        }

        @Override
        public void hold(String callId) {
            mHandler.obtainMessage(MSG_HOLD, callId).sendToTarget();
        }

        @Override
        public void unhold(String callId) {
            mHandler.obtainMessage(MSG_UNHOLD, callId).sendToTarget();
        }

        @Override
        public void onAudioStateChanged(String callId, CallAudioState audioState) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callId;
            args.arg2 = audioState;
            mHandler.obtainMessage(MSG_ON_AUDIO_STATE_CHANGED, args).sendToTarget();
        }

        @Override
        public void playDtmfTone(String callId, char digit) {
            mHandler.obtainMessage(MSG_PLAY_DTMF_TONE, digit, 0, callId).sendToTarget();
        }

        @Override
        public void stopDtmfTone(String callId) {
            mHandler.obtainMessage(MSG_STOP_DTMF_TONE, callId).sendToTarget();
        }

        @Override
        public void conference(String conferenceCallId, String callId) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = conferenceCallId;
            args.arg2 = callId;
            mHandler.obtainMessage(MSG_CONFERENCE, args).sendToTarget();
        }

        @Override
        public void splitFromConference(String callId) {
            mHandler.obtainMessage(MSG_SPLIT_FROM_CONFERENCE, callId).sendToTarget();
        }

        @Override
        public void swapWithBackgroundCall(String callId) {
            mHandler.obtainMessage(MSG_SWAP_WITH_BACKGROUND_CALL, callId).sendToTarget();
        }

        @Override
        public void onPostDialContinue(String callId, boolean proceed) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callId;
            args.argi1 = proceed ? 1 : 0;
            mHandler.obtainMessage(MSG_ON_POST_DIAL_CONTINUE, args).sendToTarget();
        }

        @Override
        public void onPhoneAccountClicked(String callId) {
            mHandler.obtainMessage(MSG_ON_PHONE_ACCOUNT_CLICKED, callId).sendToTarget();
        }
    };

    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_ADD_CONNECTION_SERVICE_ADAPTER:
                    mAdapter.addAdapter((IConnectionServiceAdapter) msg.obj);
                    onAdapterAttached();
                    break;
                case MSG_REMOVE_CONNECTION_SERVICE_ADAPTER:
                    mAdapter.removeAdapter((IConnectionServiceAdapter) msg.obj);
                    break;
                case MSG_CREATE_CONNECTION: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        final PhoneAccountHandle connectionManagerPhoneAccount =
                                (PhoneAccountHandle) args.arg1;
                        final ConnectionRequest request = (ConnectionRequest) args.arg2;
                        final boolean isIncoming = args.argi1 == 1;
                        if (!mAreAccountsInitialized) {
                            Log.d(this, "Enqueueing pre-init request %s", request.getCallId());
                            mPreInitializationConnectionRequests.add(new Runnable() {
                                @Override
                                public void run() {
                                    createConnection(
                                            connectionManagerPhoneAccount,
                                            request,
                                            isIncoming);
                                }
                            });
                        } else {
                            Log.d(this, "Immediately processing request %s", request.getCallId());
                            createConnection(connectionManagerPhoneAccount, request, isIncoming);
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_ABORT:
                    abort((String) msg.obj);
                    break;
                case MSG_ANSWER: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        String callId = (String) args.arg1;
                        int videoState = args.argi1;
                        answer(callId, videoState);
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_REJECT:
                    reject((String) msg.obj);
                    break;
                case MSG_DISCONNECT:
                    disconnect((String) msg.obj);
                    break;
                case MSG_HOLD:
                    hold((String) msg.obj);
                    break;
                case MSG_UNHOLD:
                    unhold((String) msg.obj);
                    break;
                case MSG_ON_AUDIO_STATE_CHANGED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        String callId = (String) args.arg1;
                        CallAudioState audioState = (CallAudioState) args.arg2;
                        onAudioStateChanged(callId, audioState);
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_PLAY_DTMF_TONE:
                    playDtmfTone((String) msg.obj, (char) msg.arg1);
                    break;
                case MSG_STOP_DTMF_TONE:
                    stopDtmfTone((String) msg.obj);
                    break;
                case MSG_CONFERENCE: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        String conferenceCallId = (String) args.arg1;
                        String callId = (String) args.arg2;
                        conference(conferenceCallId, callId);
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_SPLIT_FROM_CONFERENCE:
                    splitFromConference((String) msg.obj);
                    break;
                case MSG_SWAP_WITH_BACKGROUND_CALL:
                    swapWithBackgroundCall((String) msg.obj);
                    break;
                case MSG_ON_POST_DIAL_CONTINUE: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        String callId = (String) args.arg1;
                        boolean proceed = (args.argi1 == 1);
                        onPostDialContinue(callId, proceed);
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_ON_PHONE_ACCOUNT_CLICKED:
                    onPhoneAccountHandleClicked((String) msg.obj);
                    break;
                default:
                    break;
            }
        }
    };

    private final Connection.Listener mConnectionListener = new Connection.Listener() {
        @Override
        public void onStateChanged(Connection c, int state) {
            String id = mIdByConnection.get(c);
            Log.d(this, "Adapter set state %s %s", id, Connection.stateToString(state));
            switch (state) {
                case Connection.State.ACTIVE:
                    mAdapter.setActive(id);
                    break;
                case Connection.State.DIALING:
                    mAdapter.setDialing(id);
                    break;
                case Connection.State.DISCONNECTED:
                    // Handled in onDisconnected()
                    break;
                case Connection.State.HOLDING:
                    mAdapter.setOnHold(id);
                    break;
                case Connection.State.NEW:
                    // Nothing to tell Telecomm
                    break;
                case Connection.State.RINGING:
                    mAdapter.setRinging(id);
                    break;
            }
        }

        @Override
        public void onDisconnected(Connection c, int cause, String message) {
            String id = mIdByConnection.get(c);
            Log.d(this, "Adapter set disconnected %d %s", cause, message);
            mAdapter.setDisconnected(id, cause, message);
        }

        @Override
        public void onVideoStateChanged(Connection c, int videoState) {
            String id = mIdByConnection.get(c);
            Log.d(this, "Adapter set video state %d", videoState);
            mAdapter.setVideoState(id, videoState);
        }

        @Override
        public void onHandleChanged(Connection c, Uri handle, int presentation) {
            String id = mIdByConnection.get(c);
            mAdapter.setHandle(id, handle, presentation);
        }

        @Override
        public void onCallerDisplayNameChanged(
                Connection c, String callerDisplayName, int presentation) {
            String id = mIdByConnection.get(c);
            mAdapter.setCallerDisplayName(id, callerDisplayName, presentation);
        }

        @Override
        public void onSignalChanged(Connection c, Bundle details) {
            // TODO: Unsupported yet
        }

        @Override
        public void onDestroyed(Connection c) {
            removeConnection(c);
        }

        @Override
        public void onPostDialWait(Connection c, String remaining) {
            String id = mIdByConnection.get(c);
            Log.d(this, "Adapter onPostDialWait %s, %s", c, remaining);
            mAdapter.onPostDialWait(id, remaining);
        }

        @Override
        public void onRequestingRingback(Connection c, boolean ringback) {
            String id = mIdByConnection.get(c);
            Log.d(this, "Adapter onRingback %b", ringback);
            mAdapter.setRequestingRingback(id, ringback);
        }

        @Override
        public void onCallCapabilitiesChanged(Connection c, int callCapabilities) {
            String id = mIdByConnection.get(c);
            Log.d(this, "call capabilities: parcelableconnection: %s",
                    CallCapabilities.toString(callCapabilities));
            mAdapter.setCallCapabilities(id, callCapabilities);
        }

        @Override
        public void onParentConnectionChanged(Connection c, Connection parent) {
            String id = mIdByConnection.get(c);
            String parentId = parent == null ? null : mIdByConnection.get(parent);
            mAdapter.setIsConferenced(id, parentId);
        }

        @Override
        public void onVideoCallProviderChanged(Connection c, VideoCallProvider videoCallProvider) {
            String id = mIdByConnection.get(c);
            mAdapter.setVideoCallProvider(id, videoCallProvider);
        }

        @Override
        public void onAudioModeIsVoipChanged(Connection c, boolean isVoip) {
            String id = mIdByConnection.get(c);
            mAdapter.setAudioModeIsVoip(id, isVoip);
        }

        @Override
        public void onStatusHintsChanged(Connection c, StatusHints statusHints) {
            String id = mIdByConnection.get(c);
            mAdapter.setStatusHints(id, statusHints);
        }

        @Override
        public void onStartActivityFromInCall(Connection c, PendingIntent intent) {
            String id = mIdByConnection.get(c);
            mAdapter.startActivityFromInCall(id, intent);
        }

        @Override
        public void onConferenceableConnectionsChanged(
                Connection connection, List<Connection> conferenceableConnections) {
            String id = mIdByConnection.get(connection);
            List<String> conferenceableCallIds = new ArrayList<>(conferenceableConnections.size());
            for (Connection c : conferenceableConnections) {
                if (mIdByConnection.containsKey(c)) {
                    conferenceableCallIds.add(mIdByConnection.get(c));
                }
            }
            Collections.sort(conferenceableCallIds);
            mAdapter.setConferenceableConnections(id, conferenceableCallIds);
        }
    };

    /** {@inheritDoc} */
    @Override
    public final IBinder onBind(Intent intent) {
        return mBinder;
    }

    /**
     * This can be used by telecomm to either create a new outgoing call or attach to an existing
     * incoming call. In either case, telecomm will cycle through a set of services and call
     * createConnection util a connection service cancels the process or completes it successfully.
     */
    private void createConnection(
            final PhoneAccountHandle callManagerAccount,
            final ConnectionRequest request,
            boolean isIncoming) {
        Log.d(this, "call %s", request);

        final Connection createdConnection;
        if (isIncoming) {
            createdConnection = onCreateIncomingConnection(callManagerAccount, request);
        } else {
            createdConnection = onCreateOutgoingConnection(callManagerAccount, request);
        }

        if (createdConnection != null) {
            if (createdConnection.getState() == Connection.State.INITIALIZING) {
                // Wait for the connection to become initialized.
                createdConnection.addConnectionListener(new Connection.Listener() {
                    @Override
                    public void onStateChanged(Connection c, int state) {
                        switch (state) {
                            case Connection.State.FAILED:
                                Log.d(this, "Connection (%s) failed (%d: %s)", request,
                                        c.getFailureCode(), c.getFailureMessage());
                                Log.d(this, "adapter handleCreateConnectionFailed %s",
                                        request.getCallId());
                                mAdapter.handleCreateConnectionFailed(request, c.getFailureCode(),
                                        c.getFailureMessage());
                                break;
                            case Connection.State.CANCELED:
                                Log.d(this, "adapter handleCreateConnectionCanceled %s",
                                        request.getCallId());
                                mAdapter.handleCreateConnectionCancelled(request);
                                break;
                            case Connection.State.INITIALIZING:
                                Log.d(this, "State changed to INITIALIZING; ignoring");
                                return; // Don't want to stop listening on this state transition.
                            default:
                                Log.d(this, "Connection created in state %s",
                                        Connection.stateToString(state));
                                connectionCreated(request, createdConnection);
                                break;
                        }
                        c.removeConnectionListener(this);
                    }
                });
            } else if (createdConnection.getState() == Connection.State.CANCELED) {
                // Tell telecomm not to attempt any more services.
                mAdapter.handleCreateConnectionCancelled(request);
            } else if (createdConnection.getState() == Connection.State.FAILED) {
                mAdapter.handleCreateConnectionFailed(request, createdConnection.getFailureCode(),
                        createdConnection.getFailureMessage());
            } else {
                connectionCreated(request, createdConnection);
            }
        } else {
            // Tell telecomm to try a different service.
            Log.d(this, "adapter handleCreateConnectionFailed %s", request.getCallId());
            mAdapter.handleCreateConnectionFailed(request, DisconnectCause.ERROR_UNSPECIFIED, null);
        }
    }

    private void connectionCreated(ConnectionRequest request, Connection connection) {
        addConnection(request.getCallId(), connection);
        Uri handle = connection.getHandle();
        String number = handle == null ? "null" : handle.getSchemeSpecificPart();
        Log.v(this, "connectionCreated, parcelableconnection: %s, %d, %s",
                toLogSafePhoneNumber(number),
                connection.getState(),
                CallCapabilities.toString(connection.getCallCapabilities()));

        Log.d(this, "adapter handleCreateConnectionSuccessful %s", request.getCallId());
        mAdapter.handleCreateConnectionSuccessful(
                request,
                new ParcelableConnection(
                        request.getAccountHandle(),
                        connection.getState(),
                        connection.getCallCapabilities(),
                        connection.getHandle(),
                        connection.getHandlePresentation(),
                        connection.getCallerDisplayName(),
                        connection.getCallerDisplayNamePresentation(),
                        connection.getVideoCallProvider() == null ?
                                null : connection.getVideoCallProvider().getInterface(),
                        connection.getVideoState()));
    }

    private void abort(String callId) {
        Log.d(this, "abort %s", callId);
        findConnectionForAction(callId, "abort").onAbort();
    }

    private void answer(String callId, int videoState) {
        Log.d(this, "answer %s", callId);
        findConnectionForAction(callId, "answer").onAnswer(videoState);
    }

    private void reject(String callId) {
        Log.d(this, "reject %s", callId);
        findConnectionForAction(callId, "reject").onReject();
    }

    private void disconnect(String callId) {
        Log.d(this, "disconnect %s", callId);
        findConnectionForAction(callId, "disconnect").onDisconnect();
    }

    private void hold(String callId) {
        Log.d(this, "hold %s", callId);
        findConnectionForAction(callId, "hold").onHold();
    }

    private void unhold(String callId) {
        Log.d(this, "unhold %s", callId);
        findConnectionForAction(callId, "unhold").onUnhold();
    }

    private void onAudioStateChanged(String callId, CallAudioState audioState) {
        Log.d(this, "onAudioStateChanged %s %s", callId, audioState);
        findConnectionForAction(callId, "onAudioStateChanged").setAudioState(audioState);
    }

    private void playDtmfTone(String callId, char digit) {
        Log.d(this, "playDtmfTone %s %c", callId, digit);
        findConnectionForAction(callId, "playDtmfTone").onPlayDtmfTone(digit);
    }

    private void stopDtmfTone(String callId) {
        Log.d(this, "stopDtmfTone %s", callId);
        findConnectionForAction(callId, "stopDtmfTone").onStopDtmfTone();
    }

    private void conference(final String conferenceCallId, String callId) {
        Log.d(this, "conference %s, %s", conferenceCallId, callId);

        Connection connection = findConnectionForAction(callId, "conference");
        if (connection == getNullConnection()) {
            Log.w(this, "Connection missing in conference request %s.", callId);
            return;
        }

        onCreateConferenceConnection(conferenceCallId, connection,
                new Response<String, Connection>() {
                    /** ${inheritDoc} */
                    @Override
                    public void onResult(String ignored, Connection... result) {
                        Log.d(this, "onCreateConference.Response %s", (Object[]) result);
                        if (result != null && result.length == 1) {
                            Connection conferenceConnection = result[0];
                            if (!mIdByConnection.containsKey(conferenceConnection)) {
                                Log.v(this, "sending new conference call %s", conferenceCallId);
                                mAdapter.addConferenceCall(conferenceCallId);
                                addConnection(conferenceCallId, conferenceConnection);
                            }
                        }
                    }

                    /** ${inheritDoc} */
                    @Override
                    public void onError(String request, int code, String reason) {
                        // no-op
                    }
                });
    }

    private void splitFromConference(String callId) {
        Log.d(this, "splitFromConference(%s)", callId);

        Connection connection = findConnectionForAction(callId, "splitFromConference");
        if (connection == getNullConnection()) {
            Log.w(this, "Connection missing in conference request %s.", callId);
            return;
        }

        // TODO: Find existing conference call and invoke split(connection).
    }

    private void swapWithBackgroundCall(String callId) {
        Log.d(this, "swapWithBackgroundCall(%s)", callId);
        findConnectionForAction(callId, "swapWithBackgroundCall").onSwapWithBackgroundCall();
    }

    private void onPostDialContinue(String callId, boolean proceed) {
        Log.d(this, "onPostDialContinue(%s)", callId);
        findConnectionForAction(callId, "stopDtmfTone").onPostDialContinue(proceed);
    }

    private void onPhoneAccountHandleClicked(String callId) {
        Log.d(this, "onPhoneAccountClicked %s", callId);
        findConnectionForAction(callId, "onPhoneAccountClicked").onPhoneAccountClicked();
    }

    private void onAdapterAttached() {
        if (mAreAccountsInitialized) {
            // No need to query again if we already did it.
            return;
        }

        mAdapter.queryRemoteConnectionServices(new RemoteServiceCallback.Stub() {
            @Override
            public void onResult(
                    final List<ComponentName> componentNames,
                    final List<IBinder> services) {
                mHandler.post(new Runnable() {
                    @Override public void run() {
                        for (int i = 0; i < componentNames.size() && i < services.size(); i++) {
                            mRemoteConnectionManager.addConnectionService(
                                    componentNames.get(i),
                                    IConnectionService.Stub.asInterface(services.get(i)));
                        }
                        onAccountsInitialized();
                        Log.d(this, "remote connection services found: " + services);
                    }
                });
            }

            @Override
            public void onError() {
                mHandler.post(new Runnable() {
                    @Override public void run() {
                        mAreAccountsInitialized = true;
                    }
                });
            }
        });
    }

    /**
     * Ask some other {@code ConnectionService} to create a {@code RemoteConnection} given an
     * incoming request. This is used to attach to existing incoming calls.
     *
     * @param connectionManagerPhoneAccount See description at
     *         {@link #onCreateOutgoingConnection(PhoneAccountHandle, ConnectionRequest)}.
     * @param request Details about the incoming call.
     * @return The {@code Connection} object to satisfy this call, or {@code null} to
     *         not handle the call.
     */
    public final RemoteConnection createRemoteIncomingConnection(
            PhoneAccountHandle connectionManagerPhoneAccount,
            ConnectionRequest request) {
        return mRemoteConnectionManager.createRemoteConnection(
                connectionManagerPhoneAccount, request, true);
    }

    /**
     * Ask some other {@code ConnectionService} to create a {@code RemoteConnection} given an
     * outgoing request. This is used to initiate new outgoing calls.
     *
     * @param connectionManagerPhoneAccount See description at
     *         {@link #onCreateOutgoingConnection(PhoneAccountHandle, ConnectionRequest)}.
     * @param request Details about the incoming call.
     * @return The {@code Connection} object to satisfy this call, or {@code null} to
     *         not handle the call.
     */
    public final RemoteConnection createRemoteOutgoingConnection(
            PhoneAccountHandle connectionManagerPhoneAccount,
            ConnectionRequest request) {
        return mRemoteConnectionManager.createRemoteConnection(
                connectionManagerPhoneAccount, request, false);
    }

    /**
     * Returns all the active {@code Connection}s for which this {@code ConnectionService}
     * has taken responsibility.
     *
     * @return A collection of {@code Connection}s created by this {@code ConnectionService}.
     */
    public final Collection<Connection> getAllConnections() {
        return mConnectionById.values();
    }

    /**
     * Create a {@code Connection} given an incoming request. This is used to attach to existing
     * incoming calls.
     *
     * @param connectionManagerPhoneAccount See description at
     *         {@link #onCreateOutgoingConnection(PhoneAccountHandle, ConnectionRequest)}.
     * @param request Details about the incoming call.
     * @return The {@code Connection} object to satisfy this call, or {@code null} to
     *         not handle the call.
     */
    public Connection onCreateIncomingConnection(
            PhoneAccountHandle connectionManagerPhoneAccount,
            ConnectionRequest request) {
        return null;
    }

    /**
     * Create a {@code Connection} given an outgoing request. This is used to initiate new
     * outgoing calls.
     *
     * @param connectionManagerPhoneAccount The connection manager account to use for managing
     *         this call.
     *         <p>
     *         If this parameter is not {@code null}, it means that this {@code ConnectionService}
     *         has registered one or more {@code PhoneAccount}s having
     *         {@link PhoneAccount#CAPABILITY_CONNECTION_MANAGER}. This parameter will contain
     *         one of these {@code PhoneAccount}s, while the {@code request} will contain another
     *         (usually but not always distinct) {@code PhoneAccount} to be used for actually
     *         making the connection.
     *         <p>
     *         If this parameter is {@code null}, it means that this {@code ConnectionService} is
     *         being asked to make a direct connection. The
     *         {@link ConnectionRequest#getAccountHandle()} of parameter {@code request} will be
     *         a {@code PhoneAccount} registered by this {@code ConnectionService} to use for
     *         making the connection.
     * @param request Details about the outgoing call.
     * @return The {@code Connection} object to satisfy this call, or the result of an invocation
     *         of {@link Connection#getFailedConnection(int, String)} to not handle the call.
     */
    public Connection onCreateOutgoingConnection(
            PhoneAccountHandle connectionManagerPhoneAccount,
            ConnectionRequest request) {
        return null;
    }

    /**
     * Returns a new or existing conference connection when the the user elects to convert the
     * specified connection into a conference call. The specified connection can be any connection
     * which had previously specified itself as conference-capable including both simple connections
     * and connections previously returned from this method.
     *
     * @param connection The connection from which the user opted to start a conference call.
     * @param token The token to be passed into the response callback.
     * @param callback The callback for providing the potentially-new conference connection.
     */
    public void onCreateConferenceConnection(
            String token,
            Connection connection,
            Response<String, Connection> callback) {}

    /**
     * Notifies that a connection has been added to this connection service and sent to Telecomm.
     *
     * @param connection The connection which was added.
     */
    public void onConnectionAdded(Connection connection) {}

    /**
     * Notified that a connection has been removed from this connection service.
     *
     * @param connection The connection which was removed.
     */
    public void onConnectionRemoved(Connection connection) {}

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

    private void onAccountsInitialized() {
        mAreAccountsInitialized = true;
        for (Runnable r : mPreInitializationConnectionRequests) {
            r.run();
        }
        mPreInitializationConnectionRequests.clear();
    }

    private void addConnection(String callId, Connection connection) {
        mConnectionById.put(callId, connection);
        mIdByConnection.put(connection, callId);
        connection.addConnectionListener(mConnectionListener);
        onConnectionAdded(connection);
    }

    private void removeConnection(Connection connection) {
        String id = mIdByConnection.get(connection);
        connection.removeConnectionListener(mConnectionListener);
        mConnectionById.remove(mIdByConnection.get(connection));
        mIdByConnection.remove(connection);
        onConnectionRemoved(connection);
        mAdapter.removeCall(id);
    }

    private Connection findConnectionForAction(String callId, String action) {
        if (mConnectionById.containsKey(callId)) {
            return mConnectionById.get(callId);
        }
        Log.w(this, "%s - Cannot find Connection %s", action, callId);
        return getNullConnection();
    }

    private final static synchronized Connection getNullConnection() {
        if (sNullConnection == null) {
            sNullConnection = new Connection() {};
        }
        return sNullConnection;
    }

    /**
     * Abstraction for a class which provides video call functionality. This class contains no base
     * implementation for its methods. It is expected that subclasses will override these
     * functions to provide the desired behavior if it is supported.
     */
    public static abstract class VideoCallProvider {
        private static final int MSG_SET_VIDEO_CALL_LISTENER = 1;
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

        private final VideoCallProviderHandler mMessageHandler = new VideoCallProviderHandler();
        private final VideoCallProviderBinder mBinder;
        private IVideoCallCallback mVideoCallListener;

        /**
         * Default handler used to consolidate binder method calls onto a single thread.
         */
        private final class VideoCallProviderHandler extends Handler {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_SET_VIDEO_CALL_LISTENER:
                        mVideoCallListener = IVideoCallCallback.Stub.asInterface((IBinder) msg.obj);
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
                        onSendSessionModifyRequest((VideoCallProfile) msg.obj);
                        break;
                    case MSG_SEND_SESSION_MODIFY_RESPONSE:
                        onSendSessionModifyResponse((VideoCallProfile) msg.obj);
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
         * IVideoCallProvider stub implementation.
         */
        private final class VideoCallProviderBinder extends IVideoCallProvider.Stub {
            public void setVideoCallListener(IBinder videoCallListenerBinder) {
                mMessageHandler.obtainMessage(
                        MSG_SET_VIDEO_CALL_LISTENER, videoCallListenerBinder).sendToTarget();
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

            public void sendSessionModifyRequest(VideoCallProfile requestProfile) {
                mMessageHandler.obtainMessage(
                        MSG_SEND_SESSION_MODIFY_REQUEST, requestProfile).sendToTarget();
            }

            public void sendSessionModifyResponse(VideoCallProfile responseProfile) {
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

        public VideoCallProvider() {
            mBinder = new VideoCallProviderBinder();
        }

        /**
         * Returns binder object which can be used across IPC methods.
         * @hide
         */
        public final IVideoCallProvider getInterface() {
            return mBinder;
        }

        /**
         * Sets the camera to be used for video recording in a video call.
         *
         * @param cameraId The id of the camera.
         */
        public void onSetCamera(String cameraId) {
            // To be implemented by subclass.
        }

        /**
         * Sets the surface to be used for displaying a preview of what the user's camera is
         * currently capturing.  When video transmission is enabled, this is the video signal which
         * is sent to the remote device.
         *
         * @param surface The surface.
         */
        public void onSetPreviewSurface(Surface surface) {
            // To be implemented by subclass.
        }

        /**
         * Sets the surface to be used for displaying the video received from the remote device.
         *
         * @param surface The surface.
         */
        public void onSetDisplaySurface(Surface surface) {
            // To be implemented by subclass.
        }

        /**
         * Sets the device orientation, in degrees.  Assumes that a standard portrait orientation of
         * the device is 0 degrees.
         *
         * @param rotation The device orientation, in degrees.
         */
        public void onSetDeviceOrientation(int rotation) {
            // To be implemented by subclass.
        }

        /**
         * Sets camera zoom ratio.
         *
         * @param value The camera zoom ratio.
         */
        public void onSetZoom(float value) {
            // To be implemented by subclass.
        }

        /**
         * Issues a request to modify the properties of the current session.  The request is sent to
         * the remote device where it it handled by
         * {@link InCallService.VideoCall.Listener#onSessionModifyRequestReceived}.
         * Some examples of session modification requests: upgrade call from audio to video, downgrade
         * call from video to audio, pause video.
         *
         * @param requestProfile The requested call video properties.
         */
        public void onSendSessionModifyRequest(VideoCallProfile requestProfile) {
            // To be implemented by subclass.
        }

        /**
         * Provides a response to a request to change the current call session video
         * properties.
         * This is in response to a request the InCall UI has received via
         * {@link InCallService.VideoCall.Listener#onSessionModifyRequestReceived}.
         * The response is handled on the remove device by
         * {@link InCallService.VideoCall.Listener#onSessionModifyResponseReceived}.
         *
         * @param responseProfile The response call video properties.
         */
        public void onSendSessionModifyResponse(VideoCallProfile responseProfile) {
            // To be implemented by subclass.
        }

        /**
         * Issues a request to the video provider to retrieve the camera capabilities.
         * Camera capabilities are reported back to the caller via
         * {@link InCallService.VideoCall.Listener#onCameraCapabilitiesChanged(CallCameraCapabilities)}.
         */
        public void onRequestCameraCapabilities() {
            // To be implemented by subclass.
        }

        /**
         * Issues a request to the video telephony framework to retrieve the cumulative data usage for
         * the current call.  Data usage is reported back to the caller via
         * {@link InCallService.VideoCall.Listener#onCallDataUsageChanged}.
         */
        public void onRequestCallDataUsage() {
            // To be implemented by subclass.
        }

        /**
         * Provides the video telephony framework with the URI of an image to be displayed to remote
         * devices when the video signal is paused.
         *
         * @param uri URI of image to display.
         */
        public void onSetPauseImage(String uri) {
            // To be implemented by subclass.
        }

        /**
         * Invokes callback method defined in {@link InCallService.VideoCall.Listener}.
         *
         * @param videoCallProfile The requested video call profile.
         */
        public void receiveSessionModifyRequest(VideoCallProfile videoCallProfile) {
            if (mVideoCallListener != null) {
                try {
                    mVideoCallListener.receiveSessionModifyRequest(videoCallProfile);
                } catch (RemoteException ignored) {
                }
            }
        }

        /**
         * Invokes callback method defined in {@link InCallService.VideoCall.Listener}.
         *
         * @param status Status of the session modify request.  Valid values are
         *               {@link InCallService.VideoCall#SESSION_MODIFY_REQUEST_SUCCESS},
         *               {@link InCallService.VideoCall#SESSION_MODIFY_REQUEST_FAIL},
         *               {@link InCallService.VideoCall#SESSION_MODIFY_REQUEST_INVALID}
         * @param requestedProfile The original request which was sent to the remote device.
         * @param responseProfile The actual profile changes made by the remote device.
         */
        public void receiveSessionModifyResponse(
                int status, VideoCallProfile requestedProfile, VideoCallProfile responseProfile) {
            if (mVideoCallListener != null) {
                try {
                    mVideoCallListener.receiveSessionModifyResponse(
                            status, requestedProfile, responseProfile);
                } catch (RemoteException ignored) {
                }
            }
        }

        /**
         * Invokes callback method defined in {@link InCallService.VideoCall.Listener}.
         *
         * Valid values are: {@link InCallService.VideoCall#SESSION_EVENT_RX_PAUSE},
         * {@link InCallService.VideoCall#SESSION_EVENT_RX_RESUME},
         * {@link InCallService.VideoCall#SESSION_EVENT_TX_START},
         * {@link InCallService.VideoCall#SESSION_EVENT_TX_STOP}
         *
         * @param event The event.
         */
        public void handleCallSessionEvent(int event) {
            if (mVideoCallListener != null) {
                try {
                    mVideoCallListener.handleCallSessionEvent(event);
                } catch (RemoteException ignored) {
                }
            }
        }

        /**
         * Invokes callback method defined in {@link InCallService.VideoCall.Listener}.
         *
         * @param width  The updated peer video width.
         * @param height The updated peer video height.
         */
        public void changePeerDimensions(int width, int height) {
            if (mVideoCallListener != null) {
                try {
                    mVideoCallListener.changePeerDimensions(width, height);
                } catch (RemoteException ignored) {
                }
            }
        }

        /**
         * Invokes callback method defined in {@link InCallService.VideoCall.Listener}.
         *
         * @param dataUsage The updated data usage.
         */
        public void changeCallDataUsage(int dataUsage) {
            if (mVideoCallListener != null) {
                try {
                    mVideoCallListener.changeCallDataUsage(dataUsage);
                } catch (RemoteException ignored) {
                }
            }
        }

        /**
         * Invokes callback method defined in {@link InCallService.VideoCall.Listener}.
         *
         * @param callCameraCapabilities The changed camera capabilities.
         */
        public void changeCameraCapabilities(CallCameraCapabilities callCameraCapabilities) {
            if (mVideoCallListener != null) {
                try {
                    mVideoCallListener.changeCameraCapabilities(callCameraCapabilities);
                } catch (RemoteException ignored) {
                }
            }
        }
    }
}
