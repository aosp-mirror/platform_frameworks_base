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
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.telephony.DisconnectCause;

import com.android.internal.os.SomeArgs;
import com.android.internal.telecomm.IConnectionService;
import com.android.internal.telecomm.IConnectionServiceAdapter;
import com.android.internal.telecomm.RemoteServiceCallback;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A {@link android.app.Service} that provides telephone connections to processes running on an
 * Android device.
 */
public abstract class ConnectionService extends Service {
    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
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
    private static final int MSG_ON_POST_DIAL_CONTINUE = 14;
    private static final int MSG_ON_PHONE_ACCOUNT_CLICKED = 15;
    private static final int MSG_REMOVE_CONNECTION_SERVICE_ADAPTER = 16;

    private final Map<String, Connection> mConnectionById = new HashMap<>();
    private final Map<Connection, String> mIdByConnection = new HashMap<>();
    private final Map<String, Conference> mConferenceById = new HashMap<>();
    private final Map<Conference, String> mIdByConference = new HashMap<>();
    private final RemoteConnectionManager mRemoteConnectionManager = new RemoteConnectionManager();
    private final List<Runnable> mPreInitializationConnectionRequests = new ArrayList<>();
    private final ConnectionServiceAdapter mAdapter = new ConnectionServiceAdapter();

    private boolean mAreAccountsInitialized = false;

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
                String id,
                ConnectionRequest request,
                boolean isIncoming) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = connectionManagerPhoneAccount;
            args.arg2 = id;
            args.arg3 = request;
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
        public void onAudioStateChanged(String callId, AudioState audioState) {
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
        public void conference(String callId1, String callId2) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callId1;
            args.arg2 = callId2;
            mHandler.obtainMessage(MSG_CONFERENCE, args).sendToTarget();
        }

        @Override
        public void splitFromConference(String callId) {
            mHandler.obtainMessage(MSG_SPLIT_FROM_CONFERENCE, callId).sendToTarget();
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
                        final String id = (String) args.arg2;
                        final ConnectionRequest request = (ConnectionRequest) args.arg3;
                        final boolean isIncoming = args.argi1 == 1;
                        if (!mAreAccountsInitialized) {
                            Log.d(this, "Enqueueing pre-init request %s", id);
                            mPreInitializationConnectionRequests.add(new Runnable() {
                                @Override
                                public void run() {
                                    createConnection(
                                            connectionManagerPhoneAccount,
                                            id,
                                            request,
                                            isIncoming);
                                }
                            });
                        } else {
                            createConnection(
                                    connectionManagerPhoneAccount,
                                    id,
                                    request,
                                    isIncoming);
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
                        AudioState audioState = (AudioState) args.arg2;
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
                        String callId1 = (String) args.arg1;
                        String callId2 = (String) args.arg2;
                        conference(callId1, callId2);
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_SPLIT_FROM_CONFERENCE:
                    splitFromConference((String) msg.obj);
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

    private final Conference.Listener mConferenceListener = new Conference.Listener() {
        @Override
        public void onStateChanged(Conference conference, int oldState, int newState) {
            String id = mIdByConference.get(conference);
            switch (newState) {
                case Connection.STATE_ACTIVE:
                    mAdapter.setActive(id);
                    break;
                case Connection.STATE_HOLDING:
                    mAdapter.setOnHold(id);
                    break;
                case Connection.STATE_DISCONNECTED:
                    // handled by onDisconnected
                    break;
            }
        }

        @Override
        public void onDisconnected(Conference conference, int cause, String message) {
            String id = mIdByConference.get(conference);
            mAdapter.setDisconnected(id, cause, message);
        }

        @Override
        public void onConnectionAdded(Conference conference, Connection connection) {
        }

        @Override
        public void onConnectionRemoved(Conference conference, Connection connection) {
        }

        @Override
        public void onDestroyed(Conference conference) {
            removeConference(conference);
        }

        @Override
        public void onCapabilitiesChanged(Conference conference, int capabilities) {
            String id = mIdByConference.get(conference);
            Log.d(this, "call capabilities: conference: %s",
                    PhoneCapabilities.toString(capabilities));
            mAdapter.setCallCapabilities(id, capabilities);
        }
    };

    private final Connection.Listener mConnectionListener = new Connection.Listener() {
        @Override
        public void onStateChanged(Connection c, int state) {
            String id = mIdByConnection.get(c);
            Log.d(this, "Adapter set state %s %s", id, Connection.stateToString(state));
            switch (state) {
                case Connection.STATE_ACTIVE:
                    mAdapter.setActive(id);
                    break;
                case Connection.STATE_DIALING:
                    mAdapter.setDialing(id);
                    break;
                case Connection.STATE_DISCONNECTED:
                    // Handled in onDisconnected()
                    break;
                case Connection.STATE_HOLDING:
                    mAdapter.setOnHold(id);
                    break;
                case Connection.STATE_NEW:
                    // Nothing to tell Telecomm
                    break;
                case Connection.STATE_RINGING:
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
        public void onCallCapabilitiesChanged(Connection c, int capabilities) {
            String id = mIdByConnection.get(c);
            Log.d(this, "capabilities: parcelableconnection: %s",
                    PhoneCapabilities.toString(capabilities));
            mAdapter.setCallCapabilities(id, capabilities);
        }

        @Override
        public void onVideoProviderChanged(Connection c, Connection.VideoProvider videoProvider) {
            String id = mIdByConnection.get(c);
            mAdapter.setVideoProvider(id, videoProvider);
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

        @Override
        public void onConferenceChanged(Connection connection, Conference conference) {
            String id = mIdByConnection.get(connection);
            if (id != null) {
                String conferenceId = null;
                if (conference != null) {
                    conferenceId = mIdByConference.get(conference);
                }
                mAdapter.setIsConferenced(id, conferenceId);
            }
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
            final String callId,
            final ConnectionRequest request,
            boolean isIncoming) {
        Log.d(this, "call %s", request);

        Connection createdConnection = isIncoming
                ? onCreateIncomingConnection(callManagerAccount, request)
                : onCreateOutgoingConnection(callManagerAccount, request);

        if (createdConnection == null) {
            Log.d(this, "adapter handleCreateConnectionComplete CANCELED %s", callId);
            // Tell telecomm to try a different service.
            createdConnection = Connection.createCanceledConnection();
        }
        connectionCreated(callId, request, createdConnection);
    }

    private void connectionCreated(
            String callId,
            ConnectionRequest request,
            Connection connection) {
        if (!(connection instanceof Connection.FailureSignalingConnection)) {
            addConnection(callId, connection);
        }

        Uri handle = connection.getHandle();
        String number = handle == null ? "null" : handle.getSchemeSpecificPart();
        Log.v(this, "connectionCreated, parcelableconnection: %s, %d, %s",
                Connection.toLogSafePhoneNumber(number),
                connection.getState(),
                PhoneCapabilities.toString(connection.getCallCapabilities()));

        Log.d(this, "adapter handleCreateConnectionSuccessful %s", callId);
        mAdapter.handleCreateConnectionComplete(
                callId,
                request,
                new ParcelableConnection(
                        request.getAccountHandle(),
                        connection.getState(),
                        connection.getCallCapabilities(),
                        connection.getHandle(),
                        connection.getHandlePresentation(),
                        connection.getCallerDisplayName(),
                        connection.getCallerDisplayNamePresentation(),
                        connection.getVideoProvider() == null ?
                                null : connection.getVideoProvider().getInterface(),
                        connection.getVideoState(),
                        connection.isRequestingRingback(),
                        connection.getAudioModeIsVoip(),
                        connection.getStatusHints(),
                        connection.getFailureCode(),
                        connection.getFailureMessage()));
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

    private void onAudioStateChanged(String callId, AudioState audioState) {
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

    private void conference(String callId1, String callId2) {
        Log.d(this, "conference %s, %s", callId1, callId2);

        Connection connection1 = findConnectionForAction(callId1, "conference");
        if (connection1 == Connection.getNullConnection()) {
            Log.w(this, "Connection1 missing in conference request %s.", callId1);
            return;
        }

        Connection connection2 = findConnectionForAction(callId2, "conference");
        if (connection2 == Connection.getNullConnection()) {
            Log.w(this, "Connection2 missing in conference request %s.", callId2);
            return;
        }

        onConference(connection1, connection2);
    }

    private void splitFromConference(String callId) {
        Log.d(this, "splitFromConference(%s)", callId);

        Connection connection = findConnectionForAction(callId, "splitFromConference");
        if (connection == Connection.getNullConnection()) {
            Log.w(this, "Connection missing in conference request %s.", callId);
            return;
        }

        // TODO: Find existing conference call and invoke split(connection).
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
                    @Override
                    public void run() {
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
                    @Override
                    public void run() {
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
     * Adds a new conference call. When a conference call is created either as a result of an
     * explicit request via {@link #onConference} or otherwise, the connection service should supply
     * an instance of {@link Conference} by invoking this method. A conference call provided by this
     * method will persist until {@link Conference#destroy} is invoked on the conference instance.
     *
     * @param conference The new conference object.
     */
    public final void addConference(Conference conference) {
        String id = addConferenceInternal(conference);
        if (id != null) {
            List<String> connectionIds = new ArrayList<>(2);
            for (Connection connection : conference.getConnections()) {
                if (mIdByConnection.containsKey(connection)) {
                    connectionIds.add(mIdByConnection.get(connection));
                }
            }
            ParcelableConference parcelableConference = new ParcelableConference(
                    conference.getPhoneAccount(),
                    conference.getState(),
                    conference.getCapabilities(),
                    connectionIds);
            mAdapter.addConferenceCall(id, parcelableConference);

            // Go through any child calls and set the parent.
            for (Connection connection : conference.getConnections()) {
                String connectionId = mIdByConnection.get(connection);
                if (connectionId != null) {
                    mAdapter.setIsConferenced(connectionId, id);
                }
            }
        }
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
     *         of {@link Connection#createFailedConnection(int, String)} to not handle the call.
     */
    public Connection onCreateOutgoingConnection(
            PhoneAccountHandle connectionManagerPhoneAccount,
            ConnectionRequest request) {
        return null;
    }

    /**
     * Conference two specified connections. Invoked when the user has made a request to merge the
     * specified connections into a conference call. In response, the connection service should
     * create an instance of {@link Conference} and pass it into {@link #addConference}.
     *
     * @param connection1 A connection to merge into a conference call.
     * @param connection2 A connection to merge into a conference call.
     */
    public void onConference(Connection connection1, Connection connection2) {}

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

    /**
     * @hide
     */
    public boolean containsConference(Conference conference) {
        return mIdByConference.containsKey(conference);
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
        connection.setConnectionService(this);
        onConnectionAdded(connection);
    }

    private void removeConnection(Connection connection) {
        String id = mIdByConnection.get(connection);
        connection.unsetConnectionService(this);
        connection.removeConnectionListener(mConnectionListener);
        mConnectionById.remove(mIdByConnection.get(connection));
        mIdByConnection.remove(connection);
        onConnectionRemoved(connection);
        mAdapter.removeCall(id);
    }

    private String addConferenceInternal(Conference conference) {
        if (mIdByConference.containsKey(conference)) {
            Log.w(this, "Re-adding an existing conference: %s.", conference);
        } else if (conference != null) {
            String id = UUID.randomUUID().toString();
            mConferenceById.put(id, conference);
            mIdByConference.put(conference, id);
            conference.addListener(mConferenceListener);
            return id;
        }

        return null;
    }

    private void removeConference(Conference conference) {
        if (mIdByConference.containsKey(conference)) {
            conference.removeListener(mConferenceListener);

            String id = mIdByConference.get(conference);
            mConferenceById.remove(id);
            mIdByConference.remove(conference);
            mAdapter.removeCall(id);
        }
    }

    private Connection findConnectionForAction(String callId, String action) {
        if (mConnectionById.containsKey(callId)) {
            return mConnectionById.get(callId);
        }
        Log.w(this, "%s - Cannot find Connection %s", action, callId);
        return Connection.getNullConnection();
    }
}
