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

import android.app.Service;
import android.content.Intent;
import android.content.ComponentName;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.telecomm.CallVideoProvider;

import com.android.internal.os.SomeArgs;
import com.android.internal.telecomm.IConnectionService;
import com.android.internal.telecomm.IConnectionServiceAdapter;
import com.android.internal.telecomm.RemoteServiceCallback;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link android.app.Service} that provides telephone connections to processes running on an
 * Android device.
 */
public abstract class ConnectionService extends Service {
    // Flag controlling whether PII is emitted into the logs
    private static final boolean PII_DEBUG = Log.isLoggable(android.util.Log.DEBUG);
    private static final Connection NULL_CONNECTION = new Connection() {};

    private static final int MSG_ADD_CALL_SERVICE_ADAPTER = 1;
    private static final int MSG_CALL = 2;
    private static final int MSG_ABORT = 3;
    private static final int MSG_CREATE_INCOMING_CALL = 4;
    private static final int MSG_ANSWER = 5;
    private static final int MSG_REJECT = 6;
    private static final int MSG_DISCONNECT = 7;
    private static final int MSG_HOLD = 8;
    private static final int MSG_UNHOLD = 9;
    private static final int MSG_ON_AUDIO_STATE_CHANGED = 10;
    private static final int MSG_PLAY_DTMF_TONE = 11;
    private static final int MSG_STOP_DTMF_TONE = 12;
    private static final int MSG_CONFERENCE = 13;
    private static final int MSG_SPLIT_FROM_CONFERENCE = 14;
    private static final int MSG_SWAP_WITH_BACKGROUND_CALL = 15;
    private static final int MSG_ON_POST_DIAL_CONTINUE = 16;
    private static final int MSG_ON_PHONE_ACCOUNT_CLICKED = 17;

    private final Map<String, Connection> mConnectionById = new HashMap<>();
    private final Map<Connection, String> mIdByConnection = new HashMap<>();
    private final RemoteConnectionManager mRemoteConnectionManager = new RemoteConnectionManager();

    private SimpleResponse<Uri, List<PhoneAccount>> mAccountLookupResponse;
    private Uri mAccountLookupHandle;
    private boolean mAreAccountsInitialized = false;
    private final ConnectionServiceAdapter mAdapter = new ConnectionServiceAdapter();

    /**
     * A callback for providing the resuilt of creating a connection.
     */
    public interface OutgoingCallResponse<CONNECTION> {
        /**
         * Tells Telecomm that an attempt to place the specified outgoing call succeeded.
         *
         * @param request The original request.
         * @param connection The connection.
         */
        void onSuccess(ConnectionRequest request, CONNECTION connection);

        /**
         * Tells Telecomm that an attempt to place the specified outgoing call failed.
         *
         * @param request The original request.
         * @param code An integer code indicating the reason for failure.
         * @param msg A message explaining the reason for failure.
         */
        void onFailure(ConnectionRequest request, int code, String msg);

        /**
         * Tells Telecomm to cancel the call.
         *
         * @param request The original request.
         */
        void onCancel(ConnectionRequest request);
    }

    private final IBinder mBinder = new IConnectionService.Stub() {
        @Override
        public void addConnectionServiceAdapter(IConnectionServiceAdapter adapter) {
            mHandler.obtainMessage(MSG_ADD_CALL_SERVICE_ADAPTER, adapter).sendToTarget();
        }

        @Override
        public void call(ConnectionRequest request) {
            mHandler.obtainMessage(MSG_CALL, request).sendToTarget();
        }

        @Override
        public void abort(String callId) {
            mHandler.obtainMessage(MSG_ABORT, callId).sendToTarget();
        }

        @Override
        public void createIncomingCall(ConnectionRequest request) {
            mHandler.obtainMessage(MSG_CREATE_INCOMING_CALL, request).sendToTarget();
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
                case MSG_ADD_CALL_SERVICE_ADAPTER:
                    mAdapter.addAdapter((IConnectionServiceAdapter) msg.obj);
                    onAdapterAttached();
                    break;
                case MSG_CALL:
                    call((ConnectionRequest) msg.obj);
                    break;
                case MSG_ABORT:
                    abort((String) msg.obj);
                    break;
                case MSG_CREATE_INCOMING_CALL:
                    createIncomingCall((ConnectionRequest) msg.obj);
                    break;
                case MSG_ANSWER:
                    answer((String) msg.obj);
                    break;
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
                    onPhoneAccountClicked((String) msg.obj);
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
            mAdapter.setCallCapabilities(id, callCapabilities);
        }

        /** ${inheritDoc} */
        @Override
        public void onParentConnectionChanged(Connection c, Connection parent) {
            String id = mIdByConnection.get(c);
            String parentId = parent == null ? null : mIdByConnection.get(parent);
            mAdapter.setIsConferenced(id, parentId);
        }

        @Override
        public void onSetCallVideoProvider(Connection c, CallVideoProvider callVideoProvider) {
            String id = mIdByConnection.get(c);
            mAdapter.setCallVideoProvider(id, callVideoProvider);
        }

        @Override
        public void onSetAudioModeIsVoip(Connection c, boolean isVoip) {
            String id = mIdByConnection.get(c);
            mAdapter.setAudioModeIsVoip(id, isVoip);
        }

        @Override
        public void onSetStatusHints(Connection c, StatusHints statusHints) {
            String id = mIdByConnection.get(c);
            mAdapter.setStatusHints(id, statusHints);
        }
    };

    /** {@inheritDoc} */
    @Override
    public final IBinder onBind(Intent intent) {
        return mBinder;
    }

    private void call(final ConnectionRequest originalRequest) {
        Log.d(this, "call %s", originalRequest);
        onCreateConnections(
                originalRequest,
                new OutgoingCallResponse<Connection>() {
                    @Override
                    public void onSuccess(ConnectionRequest request, Connection connection) {
                        Log.d(this, "adapter handleSuccessfulOutgoingCall %s", request.getCallId());
                        mAdapter.handleSuccessfulOutgoingCall(request);
                        addConnection(request.getCallId(), connection);
                    }

                    @Override
                    public void onFailure(ConnectionRequest request, int code, String msg) {
                        mAdapter.handleFailedOutgoingCall(request, code, msg);
                    }

                    @Override
                    public void onCancel(ConnectionRequest request) {
                        mAdapter.cancelOutgoingCall(request);
                    }
                }
        );
    }

    private void abort(String callId) {
        Log.d(this, "abort %s", callId);
        findConnectionForAction(callId, "abort").onAbort();
    }

    private void createIncomingCall(ConnectionRequest originalRequest) {
        Log.d(this, "createIncomingCall %s", originalRequest);
        onCreateIncomingConnection(
                originalRequest,
                new Response<ConnectionRequest, Connection>() {
                    @Override
                    public void onResult(ConnectionRequest request, Connection... result) {
                        if (result != null && result.length != 1) {
                            for (Connection c : result) {
                                c.onAbort();
                            }
                        } else {
                            addConnection(request.getCallId(), result[0]);
                            Log.d(this, "adapter notifyIncomingCall %s", request);
                            mAdapter.notifyIncomingCall(request);
                        }
                    }

                    @Override
                    public void onError(ConnectionRequest request, int code, String msg) {
                        Log.d(this, "adapter failed createIncomingCall %s %d %s",
                                request, code, msg);
                    }
                }
        );
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
        if (connection == NULL_CONNECTION) {
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
        if (connection == NULL_CONNECTION) {
            Log.w(this, "Connection missing in conference request %s.", callId);
            return;
        }

        // TODO(santoscordon): Find existing conference call and invoke split(connection).
    }

    private void swapWithBackgroundCall(String callId) {
        Log.d(this, "swapWithBackgroundCall(%s)", callId);
        findConnectionForAction(callId, "swapWithBackgroundCall").onSwapWithBackgroundCall();
    }

    private void onPostDialContinue(String callId, boolean proceed) {
        Log.d(this, "onPostDialContinue(%s)", callId);
        findConnectionForAction(callId, "stopDtmfTone").onPostDialContinue(proceed);
    }

    private void onPhoneAccountClicked(String callId) {
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
                        mAreAccountsInitialized = true;
                        Log.d(this, "remote call services found: " + services);
                        maybeRespondToAccountLookup();
                    }
                });
            }

            @Override
            public void onError() {
                mHandler.post(new Runnable() {
                    @Override public void run() {
                        mAreAccountsInitialized = true;
                        maybeRespondToAccountLookup();
                    }
                });
            }
        });
    }

    public final void lookupRemoteAccounts(
            Uri handle, SimpleResponse<Uri, List<PhoneAccount>> response) {
        mAccountLookupResponse = response;
        mAccountLookupHandle = handle;
        maybeRespondToAccountLookup();
    }

    public final void maybeRespondToAccountLookup() {
        if (mAreAccountsInitialized && mAccountLookupResponse != null) {
            mAccountLookupResponse.onResult(
                    mAccountLookupHandle,
                    mRemoteConnectionManager.getAccounts(mAccountLookupHandle));

            mAccountLookupHandle = null;
            mAccountLookupResponse = null;
        }
    }

    public final void createRemoteOutgoingConnection(
            ConnectionRequest request,
            OutgoingCallResponse<RemoteConnection> response) {
        mRemoteConnectionManager.createOutgoingConnection(request, response);
    }

    /**
     * Returns all connections currently associated with this connection service.
     */
    public final Collection<Connection> getAllConnections() {
        return mConnectionById.values();
    }

    /**
     * Create a Connection given a request.
     *
     * @param request Data encapsulating details of the desired Connection.
     * @param callback A callback for providing the result.
     */
    protected void onCreateConnections(
            ConnectionRequest request,
            OutgoingCallResponse<Connection> callback) {}

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
    protected void onCreateConferenceConnection(
            String token,
            Connection connection,
            Response<String, Connection> callback) {}

    /**
     * Create a Connection to match an incoming connection notification.
     *
     * IMPORTANT: If the incoming connection has a phone number (or other handle) that the user
     * is not supposed to be able to see (e.g. it is PRESENTATION_RESTRICTED), then a compliant
     * ConnectionService implementation MUST NOT reveal this phone number as part of the Intent
     * it sends to notify Telecomm of an incoming connection.
     *
     * @param request Data encapsulating details of the desired Connection.
     * @param callback A callback for providing the result.
     */
    protected void onCreateIncomingConnection(
            ConnectionRequest request,
            Response<ConnectionRequest, Connection> callback) {}

    /**
     * Notifies that a connection has been added to this connection service and sent to Telecomm.
     *
     * @param connection The connection which was added.
     */
    protected void onConnectionAdded(Connection connection) {}

    /**
     * Notified that a connection has been removed from this connection service.
     *
     * @param connection The connection which was removed.
     */
    protected void onConnectionRemoved(Connection connection) {}

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

    private void addConnection(String callId, Connection connection) {
        mConnectionById.put(callId, connection);
        mIdByConnection.put(connection, callId);
        connection.addConnectionListener(mConnectionListener);
        onConnectionAdded(connection);

        // Trigger listeners for properties set before connection listener was added.
        CallVideoProvider callVideoProvider = connection.getCallVideoProvider();
        if (callVideoProvider != null) {
            connection.setCallVideoProvider(callVideoProvider);
        }
    }

    private void removeConnection(Connection connection) {
        connection.removeConnectionListener(mConnectionListener);
        mConnectionById.remove(mIdByConnection.get(connection));
        mIdByConnection.remove(connection);
        onConnectionRemoved(connection);
    }

    private Connection findConnectionForAction(String callId, String action) {
        if (mConnectionById.containsKey(callId)) {
            return mConnectionById.get(callId);
        }
        Log.w(this, "%s - Cannot find Connection %s", action, callId);
        return NULL_CONNECTION;
    }
}
