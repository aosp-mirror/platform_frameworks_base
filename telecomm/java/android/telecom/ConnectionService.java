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

import android.annotation.SdkConstant;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;

import com.android.internal.os.SomeArgs;
import com.android.internal.telecom.IConnectionService;
import com.android.internal.telecom.IConnectionServiceAdapter;
import com.android.internal.telecom.RemoteServiceCallback;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An abstract service that should be implemented by any apps which can make phone calls (VoIP or
 * otherwise) and want those calls to be integrated into the built-in phone app.
 * Once implemented, the {@code ConnectionService} needs two additional steps before it will be
 * integrated into the phone app:
 * <p>
 * 1. <i>Registration in AndroidManifest.xml</i>
 * <br/>
 * <pre>
 * &lt;service android:name="com.example.package.MyConnectionService"
 *    android:label="@string/some_label_for_my_connection_service"
 *    android:permission="android.permission.BIND_TELECOM_CONNECTION_SERVICE"&gt;
 *  &lt;intent-filter&gt;
 *   &lt;action android:name="android.telecom.ConnectionService" /&gt;
 *  &lt;/intent-filter&gt;
 * &lt;/service&gt;
 * </pre>
 * <p>
 * 2. <i> Registration of {@link PhoneAccount} with {@link TelecomManager}.</i>
 * <br/>
 * See {@link PhoneAccount} and {@link TelecomManager#registerPhoneAccount} for more information.
 * <p>
 * Once registered and enabled by the user in the phone app settings, telecom will bind to a
 * {@code ConnectionService} implementation when it wants that {@code ConnectionService} to place
 * a call or the service has indicated that is has an incoming call through
 * {@link TelecomManager#addNewIncomingCall}. The {@code ConnectionService} can then expect a call
 * to {@link #onCreateIncomingConnection} or {@link #onCreateOutgoingConnection} wherein it
 * should provide a new instance of a {@link Connection} object.  It is through this
 * {@link Connection} object that telecom receives state updates and the {@code ConnectionService}
 * receives call-commands such as answer, reject, hold and disconnect.
 * <p>
 * When there are no more live calls, telecom will unbind from the {@code ConnectionService}.
 */
public abstract class ConnectionService extends Service {
    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE = "android.telecom.ConnectionService";

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
    private static final int MSG_ON_CALL_AUDIO_STATE_CHANGED = 9;
    private static final int MSG_PLAY_DTMF_TONE = 10;
    private static final int MSG_STOP_DTMF_TONE = 11;
    private static final int MSG_CONFERENCE = 12;
    private static final int MSG_SPLIT_FROM_CONFERENCE = 13;
    private static final int MSG_ON_POST_DIAL_CONTINUE = 14;
    private static final int MSG_REMOVE_CONNECTION_SERVICE_ADAPTER = 16;
    private static final int MSG_ANSWER_VIDEO = 17;
    private static final int MSG_MERGE_CONFERENCE = 18;
    private static final int MSG_SWAP_CONFERENCE = 19;

    private static Connection sNullConnection;

    private final Map<String, Connection> mConnectionById = new ConcurrentHashMap<>();
    private final Map<Connection, String> mIdByConnection = new ConcurrentHashMap<>();
    private final Map<String, Conference> mConferenceById = new ConcurrentHashMap<>();
    private final Map<Conference, String> mIdByConference = new ConcurrentHashMap<>();
    private final RemoteConnectionManager mRemoteConnectionManager =
            new RemoteConnectionManager(this);
    private final List<Runnable> mPreInitializationConnectionRequests = new ArrayList<>();
    private final ConnectionServiceAdapter mAdapter = new ConnectionServiceAdapter();

    private boolean mAreAccountsInitialized = false;
    private Conference sNullConference;

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
                boolean isIncoming,
                boolean isUnknown) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = connectionManagerPhoneAccount;
            args.arg2 = id;
            args.arg3 = request;
            args.argi1 = isIncoming ? 1 : 0;
            args.argi2 = isUnknown ? 1 : 0;
            mHandler.obtainMessage(MSG_CREATE_CONNECTION, args).sendToTarget();
        }

        @Override
        public void abort(String callId) {
            mHandler.obtainMessage(MSG_ABORT, callId).sendToTarget();
        }

        @Override
        public void answerVideo(String callId, int videoState) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callId;
            args.argi1 = videoState;
            mHandler.obtainMessage(MSG_ANSWER_VIDEO, args).sendToTarget();
        }

        @Override
        public void answer(String callId) {
            mHandler.obtainMessage(MSG_ANSWER, callId).sendToTarget();
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
        public void onCallAudioStateChanged(String callId, CallAudioState callAudioState) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callId;
            args.arg2 = callAudioState;
            mHandler.obtainMessage(MSG_ON_CALL_AUDIO_STATE_CHANGED, args).sendToTarget();
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
        public void mergeConference(String callId) {
            mHandler.obtainMessage(MSG_MERGE_CONFERENCE, callId).sendToTarget();
        }

        @Override
        public void swapConference(String callId) {
            mHandler.obtainMessage(MSG_SWAP_CONFERENCE, callId).sendToTarget();
        }

        @Override
        public void onPostDialContinue(String callId, boolean proceed) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callId;
            args.argi1 = proceed ? 1 : 0;
            mHandler.obtainMessage(MSG_ON_POST_DIAL_CONTINUE, args).sendToTarget();
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
                        final boolean isUnknown = args.argi2 == 1;
                        if (!mAreAccountsInitialized) {
                            Log.d(this, "Enqueueing pre-init request %s", id);
                            mPreInitializationConnectionRequests.add(new Runnable() {
                                @Override
                                public void run() {
                                    createConnection(
                                            connectionManagerPhoneAccount,
                                            id,
                                            request,
                                            isIncoming,
                                            isUnknown);
                                }
                            });
                        } else {
                            createConnection(
                                    connectionManagerPhoneAccount,
                                    id,
                                    request,
                                    isIncoming,
                                    isUnknown);
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_ABORT:
                    abort((String) msg.obj);
                    break;
                case MSG_ANSWER:
                    answer((String) msg.obj);
                    break;
                case MSG_ANSWER_VIDEO: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        String callId = (String) args.arg1;
                        int videoState = args.argi1;
                        answerVideo(callId, videoState);
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
                case MSG_ON_CALL_AUDIO_STATE_CHANGED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        String callId = (String) args.arg1;
                        CallAudioState audioState = (CallAudioState) args.arg2;
                        onCallAudioStateChanged(callId, new CallAudioState(audioState));
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
                case MSG_MERGE_CONFERENCE:
                    mergeConference((String) msg.obj);
                    break;
                case MSG_SWAP_CONFERENCE:
                    swapConference((String) msg.obj);
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
        public void onDisconnected(Conference conference, DisconnectCause disconnectCause) {
            String id = mIdByConference.get(conference);
            mAdapter.setDisconnected(id, disconnectCause);
        }

        @Override
        public void onConnectionAdded(Conference conference, Connection connection) {
        }

        @Override
        public void onConnectionRemoved(Conference conference, Connection connection) {
        }

        @Override
        public void onConferenceableConnectionsChanged(
                Conference conference, List<Connection> conferenceableConnections) {
            mAdapter.setConferenceableConnections(
                    mIdByConference.get(conference),
                    createConnectionIdList(conferenceableConnections));
        }

        @Override
        public void onDestroyed(Conference conference) {
            removeConference(conference);
        }

        @Override
        public void onConnectionCapabilitiesChanged(
                Conference conference,
                int connectionCapabilities) {
            String id = mIdByConference.get(conference);
            Log.d(this, "call capabilities: conference: %s",
                    Connection.capabilitiesToString(connectionCapabilities));
            mAdapter.setConnectionCapabilities(id, connectionCapabilities);
        }

        @Override
        public void onVideoStateChanged(Conference c, int videoState) {
            String id = mIdByConference.get(c);
            Log.d(this, "onVideoStateChanged set video state %d", videoState);
            mAdapter.setVideoState(id, videoState);
        }

        @Override
        public void onVideoProviderChanged(Conference c, Connection.VideoProvider videoProvider) {
            String id = mIdByConference.get(c);
            Log.d(this, "onVideoProviderChanged: Connection: %s, VideoProvider: %s", c,
                    videoProvider);
            mAdapter.setVideoProvider(id, videoProvider);
        }

        @Override
        public void onStatusHintsChanged(Conference conference, StatusHints statusHints) {
            String id = mIdByConference.get(conference);
            mAdapter.setStatusHints(id, statusHints);
        }

        @Override
        public void onExtrasChanged(Conference conference, Bundle extras) {
            String id = mIdByConference.get(conference);
            mAdapter.setExtras(id, extras);
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
                    // Nothing to tell Telecom
                    break;
                case Connection.STATE_RINGING:
                    mAdapter.setRinging(id);
                    break;
            }
        }

        @Override
        public void onDisconnected(Connection c, DisconnectCause disconnectCause) {
            String id = mIdByConnection.get(c);
            Log.d(this, "Adapter set disconnected %s", disconnectCause);
            mAdapter.setDisconnected(id, disconnectCause);
        }

        @Override
        public void onVideoStateChanged(Connection c, int videoState) {
            String id = mIdByConnection.get(c);
            Log.d(this, "Adapter set video state %d", videoState);
            mAdapter.setVideoState(id, videoState);
        }

        @Override
        public void onAddressChanged(Connection c, Uri address, int presentation) {
            String id = mIdByConnection.get(c);
            mAdapter.setAddress(id, address, presentation);
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
        public void onPostDialChar(Connection c, char nextChar) {
            String id = mIdByConnection.get(c);
            Log.d(this, "Adapter onPostDialChar %s, %s", c, nextChar);
            mAdapter.onPostDialChar(id, nextChar);
        }

        @Override
        public void onRingbackRequested(Connection c, boolean ringback) {
            String id = mIdByConnection.get(c);
            Log.d(this, "Adapter onRingback %b", ringback);
            mAdapter.setRingbackRequested(id, ringback);
        }

        @Override
        public void onConnectionCapabilitiesChanged(Connection c, int capabilities) {
            String id = mIdByConnection.get(c);
            Log.d(this, "capabilities: parcelableconnection: %s",
                    Connection.capabilitiesToString(capabilities));
            mAdapter.setConnectionCapabilities(id, capabilities);
        }

        @Override
        public void onVideoProviderChanged(Connection c, Connection.VideoProvider videoProvider) {
            String id = mIdByConnection.get(c);
            Log.d(this, "onVideoProviderChanged: Connection: %s, VideoProvider: %s", c,
                    videoProvider);
            mAdapter.setVideoProvider(id, videoProvider);
        }

        @Override
        public void onAudioModeIsVoipChanged(Connection c, boolean isVoip) {
            String id = mIdByConnection.get(c);
            mAdapter.setIsVoipAudioMode(id, isVoip);
        }

        @Override
        public void onStatusHintsChanged(Connection c, StatusHints statusHints) {
            String id = mIdByConnection.get(c);
            mAdapter.setStatusHints(id, statusHints);
        }

        @Override
        public void onConferenceablesChanged(
                Connection connection, List<Conferenceable> conferenceables) {
            mAdapter.setConferenceableConnections(
                    mIdByConnection.get(connection),
                    createIdList(conferenceables));
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

        @Override
        public void onConferenceMergeFailed(Connection connection) {
            String id = mIdByConnection.get(connection);
            if (id != null) {
                mAdapter.onConferenceMergeFailed(id);
            }
        }

        @Override
        public void onExtrasChanged(Connection connection, Bundle extras) {
            String id = mIdByConnection.get(connection);
            if (id != null) {
                mAdapter.setExtras(id, extras);
            }
        }
    };

    /** {@inheritDoc} */
    @Override
    public final IBinder onBind(Intent intent) {
        return mBinder;
    }

    /** {@inheritDoc} */
    @Override
    public boolean onUnbind(Intent intent) {
        endAllConnections();
        return super.onUnbind(intent);
    }

    /**
     * This can be used by telecom to either create a new outgoing call or attach to an existing
     * incoming call. In either case, telecom will cycle through a set of services and call
     * createConnection util a connection service cancels the process or completes it successfully.
     */
    private void createConnection(
            final PhoneAccountHandle callManagerAccount,
            final String callId,
            final ConnectionRequest request,
            boolean isIncoming,
            boolean isUnknown) {
        Log.d(this, "createConnection, callManagerAccount: %s, callId: %s, request: %s, " +
                "isIncoming: %b, isUnknown: %b", callManagerAccount, callId, request, isIncoming,
                isUnknown);

        Connection connection = isUnknown ? onCreateUnknownConnection(callManagerAccount, request)
                : isIncoming ? onCreateIncomingConnection(callManagerAccount, request)
                : onCreateOutgoingConnection(callManagerAccount, request);
        Log.d(this, "createConnection, connection: %s", connection);
        if (connection == null) {
            connection = Connection.createFailedConnection(
                    new DisconnectCause(DisconnectCause.ERROR));
        }

        if (connection.getState() != Connection.STATE_DISCONNECTED) {
            addConnection(callId, connection);
        }

        Uri address = connection.getAddress();
        String number = address == null ? "null" : address.getSchemeSpecificPart();
        Log.v(this, "createConnection, number: %s, state: %s, capabilities: %s",
                Connection.toLogSafePhoneNumber(number),
                Connection.stateToString(connection.getState()),
                Connection.capabilitiesToString(connection.getConnectionCapabilities()));

        Log.d(this, "createConnection, calling handleCreateConnectionSuccessful %s", callId);
        mAdapter.handleCreateConnectionComplete(
                callId,
                request,
                new ParcelableConnection(
                        request.getAccountHandle(),
                        connection.getState(),
                        connection.getConnectionCapabilities(),
                        connection.getAddress(),
                        connection.getAddressPresentation(),
                        connection.getCallerDisplayName(),
                        connection.getCallerDisplayNamePresentation(),
                        connection.getVideoProvider() == null ?
                                null : connection.getVideoProvider().getInterface(),
                        connection.getVideoState(),
                        connection.isRingbackRequested(),
                        connection.getAudioModeIsVoip(),
                        connection.getConnectTimeMillis(),
                        connection.getStatusHints(),
                        connection.getDisconnectCause(),
                        createIdList(connection.getConferenceables()),
                        connection.getExtras()));
        if (isUnknown) {
            triggerConferenceRecalculate();
        }
    }

    private void abort(String callId) {
        Log.d(this, "abort %s", callId);
        findConnectionForAction(callId, "abort").onAbort();
    }

    private void answerVideo(String callId, int videoState) {
        Log.d(this, "answerVideo %s", callId);
        findConnectionForAction(callId, "answer").onAnswer(videoState);
    }

    private void answer(String callId) {
        Log.d(this, "answer %s", callId);
        findConnectionForAction(callId, "answer").onAnswer();
    }

    private void reject(String callId) {
        Log.d(this, "reject %s", callId);
        findConnectionForAction(callId, "reject").onReject();
    }

    private void disconnect(String callId) {
        Log.d(this, "disconnect %s", callId);
        if (mConnectionById.containsKey(callId)) {
            findConnectionForAction(callId, "disconnect").onDisconnect();
        } else {
            findConferenceForAction(callId, "disconnect").onDisconnect();
        }
    }

    private void hold(String callId) {
        Log.d(this, "hold %s", callId);
        if (mConnectionById.containsKey(callId)) {
            findConnectionForAction(callId, "hold").onHold();
        } else {
            findConferenceForAction(callId, "hold").onHold();
        }
    }

    private void unhold(String callId) {
        Log.d(this, "unhold %s", callId);
        if (mConnectionById.containsKey(callId)) {
            findConnectionForAction(callId, "unhold").onUnhold();
        } else {
            findConferenceForAction(callId, "unhold").onUnhold();
        }
    }

    private void onCallAudioStateChanged(String callId, CallAudioState callAudioState) {
        Log.d(this, "onAudioStateChanged %s %s", callId, callAudioState);
        if (mConnectionById.containsKey(callId)) {
            findConnectionForAction(callId, "onCallAudioStateChanged").setCallAudioState(
                    callAudioState);
        } else {
            findConferenceForAction(callId, "onCallAudioStateChanged").setCallAudioState(
                    callAudioState);
        }
    }

    private void playDtmfTone(String callId, char digit) {
        Log.d(this, "playDtmfTone %s %c", callId, digit);
        if (mConnectionById.containsKey(callId)) {
            findConnectionForAction(callId, "playDtmfTone").onPlayDtmfTone(digit);
        } else {
            findConferenceForAction(callId, "playDtmfTone").onPlayDtmfTone(digit);
        }
    }

    private void stopDtmfTone(String callId) {
        Log.d(this, "stopDtmfTone %s", callId);
        if (mConnectionById.containsKey(callId)) {
            findConnectionForAction(callId, "stopDtmfTone").onStopDtmfTone();
        } else {
            findConferenceForAction(callId, "stopDtmfTone").onStopDtmfTone();
        }
    }

    private void conference(String callId1, String callId2) {
        Log.d(this, "conference %s, %s", callId1, callId2);

        // Attempt to get second connection or conference.
        Connection connection2 = findConnectionForAction(callId2, "conference");
        Conference conference2 = getNullConference();
        if (connection2 == getNullConnection()) {
            conference2 = findConferenceForAction(callId2, "conference");
            if (conference2 == getNullConference()) {
                Log.w(this, "Connection2 or Conference2 missing in conference request %s.",
                        callId2);
                return;
            }
        }

        // Attempt to get first connection or conference and perform merge.
        Connection connection1 = findConnectionForAction(callId1, "conference");
        if (connection1 == getNullConnection()) {
            Conference conference1 = findConferenceForAction(callId1, "addConnection");
            if (conference1 == getNullConference()) {
                Log.w(this,
                        "Connection1 or Conference1 missing in conference request %s.",
                        callId1);
            } else {
                // Call 1 is a conference.
                if (connection2 != getNullConnection()) {
                    // Call 2 is a connection so merge via call 1 (conference).
                    conference1.onMerge(connection2);
                } else {
                    // Call 2 is ALSO a conference; this should never happen.
                    Log.wtf(this, "There can only be one conference and an attempt was made to " +
                            "merge two conferences.");
                    return;
                }
            }
        } else {
            // Call 1 is a connection.
            if (conference2 != getNullConference()) {
                // Call 2 is a conference, so merge via call 2.
                conference2.onMerge(connection1);
            } else {
                // Call 2 is a connection, so merge together.
                onConference(connection1, connection2);
            }
        }
    }

    private void splitFromConference(String callId) {
        Log.d(this, "splitFromConference(%s)", callId);

        Connection connection = findConnectionForAction(callId, "splitFromConference");
        if (connection == getNullConnection()) {
            Log.w(this, "Connection missing in conference request %s.", callId);
            return;
        }

        Conference conference = connection.getConference();
        if (conference != null) {
            conference.onSeparate(connection);
        }
    }

    private void mergeConference(String callId) {
        Log.d(this, "mergeConference(%s)", callId);
        Conference conference = findConferenceForAction(callId, "mergeConference");
        if (conference != null) {
            conference.onMerge();
        }
    }

    private void swapConference(String callId) {
        Log.d(this, "swapConference(%s)", callId);
        Conference conference = findConferenceForAction(callId, "swapConference");
        if (conference != null) {
            conference.onSwap();
        }
    }

    private void onPostDialContinue(String callId, boolean proceed) {
        Log.d(this, "onPostDialContinue(%s)", callId);
        findConnectionForAction(callId, "stopDtmfTone").onPostDialContinue(proceed);
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
     * incoming request. This is used by {@code ConnectionService}s that are registered with
     * {@link PhoneAccount#CAPABILITY_CONNECTION_MANAGER} and want to be able to manage
     * SIM-based incoming calls.
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
     * outgoing request. This is used by {@code ConnectionService}s that are registered with
     * {@link PhoneAccount#CAPABILITY_CONNECTION_MANAGER} and want to be able to use the
     * SIM-based {@code ConnectionService} to place its outgoing calls.
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
     * Indicates to the relevant {@code RemoteConnectionService} that the specified
     * {@link RemoteConnection}s should be merged into a conference call.
     * <p>
     * If the conference request is successful, the method {@link #onRemoteConferenceAdded} will
     * be invoked.
     *
     * @param remoteConnection1 The first of the remote connections to conference.
     * @param remoteConnection2 The second of the remote connections to conference.
     */
    public final void conferenceRemoteConnections(
            RemoteConnection remoteConnection1,
            RemoteConnection remoteConnection2) {
        mRemoteConnectionManager.conferenceRemoteConnections(remoteConnection1, remoteConnection2);
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
        Log.d(this, "addConference: conference=%s", conference);

        String id = addConferenceInternal(conference);
        if (id != null) {
            List<String> connectionIds = new ArrayList<>(2);
            for (Connection connection : conference.getConnections()) {
                if (mIdByConnection.containsKey(connection)) {
                    connectionIds.add(mIdByConnection.get(connection));
                }
            }
            ParcelableConference parcelableConference = new ParcelableConference(
                    conference.getPhoneAccountHandle(),
                    conference.getState(),
                    conference.getConnectionCapabilities(),
                    connectionIds,
                    conference.getVideoProvider() == null ?
                            null : conference.getVideoProvider().getInterface(),
                    conference.getVideoState(),
                    conference.getConnectTimeMillis(),
                    conference.getStatusHints(),
                    conference.getExtras());

            mAdapter.addConferenceCall(id, parcelableConference);
            mAdapter.setVideoProvider(id, conference.getVideoProvider());
            mAdapter.setVideoState(id, conference.getVideoState());

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
     * Adds a connection created by the {@link ConnectionService} and informs telecom of the new
     * connection.
     *
     * @param phoneAccountHandle The phone account handle for the connection.
     * @param connection The connection to add.
     */
    public final void addExistingConnection(PhoneAccountHandle phoneAccountHandle,
            Connection connection) {

        String id = addExistingConnectionInternal(connection);
        if (id != null) {
            List<String> emptyList = new ArrayList<>(0);

            ParcelableConnection parcelableConnection = new ParcelableConnection(
                    phoneAccountHandle,
                    connection.getState(),
                    connection.getConnectionCapabilities(),
                    connection.getAddress(),
                    connection.getAddressPresentation(),
                    connection.getCallerDisplayName(),
                    connection.getCallerDisplayNamePresentation(),
                    connection.getVideoProvider() == null ?
                            null : connection.getVideoProvider().getInterface(),
                    connection.getVideoState(),
                    connection.isRingbackRequested(),
                    connection.getAudioModeIsVoip(),
                    connection.getConnectTimeMillis(),
                    connection.getStatusHints(),
                    connection.getDisconnectCause(),
                    emptyList,
                    connection.getExtras());
            mAdapter.addExistingConnection(id, parcelableConnection);
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
     * Trigger recalculate functinality for conference calls. This is used when a Telephony
     * Connection is part of a conference controller but is not yet added to Connection
     * Service and hence cannot be added to the conference call.
     *
     * @hide
     */
    public void triggerConferenceRecalculate() {
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
     *         of {@link Connection#createFailedConnection(DisconnectCause)} to not handle the call.
     */
    public Connection onCreateOutgoingConnection(
            PhoneAccountHandle connectionManagerPhoneAccount,
            ConnectionRequest request) {
        return null;
    }

    /**
     * Create a {@code Connection} for a new unknown call. An unknown call is a call originating
     * from the ConnectionService that was neither a user-initiated outgoing call, nor an incoming
     * call created using
     * {@code TelecomManager#addNewIncomingCall(PhoneAccountHandle, android.os.Bundle)}.
     *
     * @param connectionManagerPhoneAccount
     * @param request
     * @return
     *
     * @hide
     */
    public Connection onCreateUnknownConnection(PhoneAccountHandle connectionManagerPhoneAccount,
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
     * Indicates that a remote conference has been created for existing {@link RemoteConnection}s.
     * When this method is invoked, this {@link ConnectionService} should create its own
     * representation of the conference call and send it to telecom using {@link #addConference}.
     * <p>
     * This is only relevant to {@link ConnectionService}s which are registered with
     * {@link PhoneAccount#CAPABILITY_CONNECTION_MANAGER}.
     *
     * @param conference The remote conference call.
     */
    public void onRemoteConferenceAdded(RemoteConference conference) {}

    /**
     * Called when an existing connection is added remotely.
     * @param connection The existing connection which was added.
     */
    public void onRemoteExistingConnectionAdded(RemoteConnection connection) {}

    /**
     * @hide
     */
    public boolean containsConference(Conference conference) {
        return mIdByConference.containsKey(conference);
    }

    /** {@hide} */
    void addRemoteConference(RemoteConference remoteConference) {
        onRemoteConferenceAdded(remoteConference);
    }

    /** {@hide} */
    void addRemoteExistingConnection(RemoteConnection remoteConnection) {
        onRemoteExistingConnectionAdded(remoteConnection);
    }

    private void onAccountsInitialized() {
        mAreAccountsInitialized = true;
        for (Runnable r : mPreInitializationConnectionRequests) {
            r.run();
        }
        mPreInitializationConnectionRequests.clear();
    }

    /**
     * Adds an existing connection to the list of connections, identified by a new UUID.
     *
     * @param connection The connection.
     * @return The UUID of the connection (e.g. the call-id).
     */
    private String addExistingConnectionInternal(Connection connection) {
        String id = UUID.randomUUID().toString();
        addConnection(id, connection);
        return id;
    }

    private void addConnection(String callId, Connection connection) {
        mConnectionById.put(callId, connection);
        mIdByConnection.put(connection, callId);
        connection.addConnectionListener(mConnectionListener);
        connection.setConnectionService(this);
    }

    /** {@hide} */
    protected void removeConnection(Connection connection) {
        String id = mIdByConnection.get(connection);
        connection.unsetConnectionService(this);
        connection.removeConnectionListener(mConnectionListener);
        mConnectionById.remove(mIdByConnection.get(connection));
        mIdByConnection.remove(connection);
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
        return getNullConnection();
    }

    static synchronized Connection getNullConnection() {
        if (sNullConnection == null) {
            sNullConnection = new Connection() {};
        }
        return sNullConnection;
    }

    private Conference findConferenceForAction(String conferenceId, String action) {
        if (mConferenceById.containsKey(conferenceId)) {
            return mConferenceById.get(conferenceId);
        }
        Log.w(this, "%s - Cannot find conference %s", action, conferenceId);
        return getNullConference();
    }

    private List<String> createConnectionIdList(List<Connection> connections) {
        List<String> ids = new ArrayList<>();
        for (Connection c : connections) {
            if (mIdByConnection.containsKey(c)) {
                ids.add(mIdByConnection.get(c));
            }
        }
        Collections.sort(ids);
        return ids;
    }

    /**
     * Builds a list of {@link Connection} and {@link Conference} IDs based on the list of
     * {@link Conferenceable}s passed in.
     *
     * @param conferenceables The {@link Conferenceable} connections and conferences.
     * @return List of string conference and call Ids.
     */
    private List<String> createIdList(List<Conferenceable> conferenceables) {
        List<String> ids = new ArrayList<>();
        for (Conferenceable c : conferenceables) {
            // Only allow Connection and Conference conferenceables.
            if (c instanceof Connection) {
                Connection connection = (Connection) c;
                if (mIdByConnection.containsKey(connection)) {
                    ids.add(mIdByConnection.get(connection));
                }
            } else if (c instanceof Conference) {
                Conference conference = (Conference) c;
                if (mIdByConference.containsKey(conference)) {
                    ids.add(mIdByConference.get(conference));
                }
            }
        }
        Collections.sort(ids);
        return ids;
    }

    private Conference getNullConference() {
        if (sNullConference == null) {
            sNullConference = new Conference(null) {};
        }
        return sNullConference;
    }

    private void endAllConnections() {
        // Unbound from telecomm.  We should end all connections and conferences.
        for (Connection connection : mIdByConnection.keySet()) {
            // only operate on top-level calls. Conference calls will be removed on their own.
            if (connection.getConference() == null) {
                connection.onDisconnect();
            }
        }
        for (Conference conference : mIdByConference.keySet()) {
            conference.onDisconnect();
        }
    }
}
