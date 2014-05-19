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

import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * A {@link android.app.Service} that provides telephone connections to
 * processes running on an Android device.
 */
public abstract class ConnectionService extends CallService {
    private static final String TAG = ConnectionService.class.getSimpleName();

    // STOPSHIP: Debug Logging should be conditional on a debug flag or use a set of
    // logging functions that make it automaticaly so.

    // Flag controlling whether PII is emitted into the logs
    private static final boolean PII_DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final Connection NULL_CONNECTION = new Connection() {};

    // Mappings from Connections to IDs as understood by the current CallService implementation
    private final Map<String, Connection> mConnectionById = new HashMap<>();
    private final Map<Connection, String> mIdByConnection = new HashMap<>();

    private final Connection.Listener mConnectionListener = new Connection.Listener() {
        @Override
        public void onStateChanged(Connection c, int state) {
            String id = mIdByConnection.get(c);
            Log.d(TAG, "Adapter set state " + id + " " + Connection.stateToString(state));
            switch (state) {
                case Connection.State.ACTIVE:
                    getAdapter().setActive(id);
                    break;
                case Connection.State.DIALING:
                    getAdapter().setDialing(id);
                    break;
                case Connection.State.DISCONNECTED:
                    // Handled in onDisconnected()
                    break;
                case Connection.State.HOLDING:
                    getAdapter().setOnHold(id);
                    break;
                case Connection.State.NEW:
                    // Nothing to tell Telecomm
                    break;
                case Connection.State.RINGING:
                    getAdapter().setRinging(id);
                    break;
            }
        }

        @Override
        public void onDisconnected(Connection c, int cause, String message) {
            String id = mIdByConnection.get(c);
            Log.d(TAG, "Adapter set disconnected " + cause + " " + message);
            getAdapter().setDisconnected(id, cause, message);
        }

        @Override
        public void onHandleChanged(Connection c, Uri newHandle) {
            // TODO: Unsupported yet
        }

        @Override
        public void onAudioStateChanged(Connection c, CallAudioState state) {
            // TODO: Unsupported yet
        }

        @Override
        public void onSignalChanged(Connection c, Bundle details) {
            // TODO: Unsupported yet
        }

        @Override
        public void onDestroyed(Connection c) {
            removeConnection(c);
        }
    };

    @Override
    public final void isCompatibleWith(final CallInfo callInfo) {
        Log.d(TAG, "isCompatibleWith " + callInfo);
        onFindSubscriptions(
                callInfo.getHandle(),
                new Response<Uri, Subscription>() {
                    @Override
                    public void onResult(Uri handle, Subscription... result) {
                        boolean isCompatible = result.length > 0;
                        Log.d(TAG, "adapter setIsCompatibleWith "
                                + callInfo.getId() + " " + isCompatible);
                        getAdapter().setIsCompatibleWith(callInfo.getId(), isCompatible);
                    }

                    @Override
                    public void onError(Uri handle, String reason) {
                        Log.wtf(TAG, "Error in onFindSubscriptions " + callInfo.getHandle()
                                + " error: " + reason);
                        getAdapter().setIsCompatibleWith(callInfo.getId(), false);
                    }
                }
        );
    }

    @Override
    public final void call(final CallInfo callInfo) {
        Log.d(TAG, "call " + callInfo);
        onCreateConnections(
                new ConnectionRequest(
                        callInfo.getHandle(),
                        callInfo.getExtras()),
                new Response<ConnectionRequest, Connection>() {
                    @Override
                    public void onResult(ConnectionRequest request, Connection... result) {
                        if (result.length != 1) {
                            Log.d(TAG, "adapter handleFailedOutgoingCall " + callInfo);
                            getAdapter().handleFailedOutgoingCall(
                                    callInfo.getId(),
                                    "Created " + result.length + " Connections, expected 1");
                            for (Connection c : result) {
                                c.abort();
                            }
                        } else {
                            addConnection(callInfo.getId(), result[0]);
                            Log.d(TAG, "adapter handleSuccessfulOutgoingCall "
                                    + callInfo.getId());
                            getAdapter().handleSuccessfulOutgoingCall(callInfo.getId());
                        }
                    }

                    @Override
                    public void onError(ConnectionRequest request, String reason) {
                        getAdapter().handleFailedOutgoingCall(callInfo.getId(), reason);
                    }
                }
        );
    }

    @Override
    public final void abort(String callId) {
        Log.d(TAG, "abort " + callId);
        findConnectionForAction(callId, "abort").abort();
    }

    @Override
    public final void setIncomingCallId(final String callId, Bundle extras) {
        Log.d(TAG, "setIncomingCallId " + callId + " " + extras);
        onCreateIncomingConnection(
                new ConnectionRequest(
                        null,  // TODO: Can we obtain this from "extras"?
                        extras),
                new Response<ConnectionRequest, Connection>() {
                    @Override
                    public void onResult(ConnectionRequest request, Connection... result) {
                        if (result.length != 1) {
                            Log.d(TAG, "adapter handleFailedOutgoingCall " + callId);
                            getAdapter().handleFailedOutgoingCall(
                                    callId,
                                    "Created " + result.length + " Connections, expected 1");
                            for (Connection c : result) {
                                c.abort();
                            }
                        } else {
                            addConnection(callId, result[0]);
                            Log.d(TAG, "adapter notifyIncomingCall " + callId);
                            // TODO: Uri.EMPTY is because CallInfo crashes when Parceled with a
                            // null URI ... need to fix that at its cause!
                            getAdapter().notifyIncomingCall(new CallInfo(
                                    callId,
                                    connectionStateToCallState(result[0].getState()),
                                    request.getHandle() /* result[0].getHandle() == null
                                            ? Uri.EMPTY : result[0].getHandle() */));
                        }
                    }

                    @Override
                    public void onError(ConnectionRequest request, String reason) {
                        Log.d(TAG, "adapter failed setIncomingCallId " + request + " " + reason);
                    }
                }
        );
    }

    @Override
    public final void answer(String callId) {
        Log.d(TAG, "answer " + callId);
        findConnectionForAction(callId, "answer").answer();
    }

    @Override
    public final void reject(String callId) {
        Log.d(TAG, "reject " + callId);
        findConnectionForAction(callId, "reject").reject();
    }

    @Override
    public final void disconnect(String callId) {
        Log.d(TAG, "disconnect " + callId);
        findConnectionForAction(callId, "disconnect").disconnect();
    }

    @Override
    public final void hold(String callId) {
        Log.d(TAG, "hold " + callId);
        findConnectionForAction(callId, "hold").hold();
    }

    @Override
    public final void unhold(String callId) {
        Log.d(TAG, "unhold " + callId);
        findConnectionForAction(callId, "unhold").unhold();
    }

    @Override
    public final void playDtmfTone(String callId, char digit) {
        Log.d(TAG, "playDtmfTone " + callId + " " + Character.toString(digit));
        findConnectionForAction(callId, "playDtmfTone").playDtmfTone(digit);
    }

    @Override
    public final void stopDtmfTone(String callId) {
        Log.d(TAG, "stopDtmfTone " + callId);
        findConnectionForAction(callId, "stopDtmfTone").stopDtmfTone();
    }

    @Override
    public final void onAudioStateChanged(String callId, CallAudioState audioState) {
        Log.d(TAG, "onAudioStateChanged " + callId + " " + audioState);
        findConnectionForAction(callId, "onAudioStateChanged").setAudioState(audioState);
    }

    /**
     * Find a set of Subscriptions matching a given handle (e.g. phone number).
     *
     * @param handle A handle (e.g. phone number) with which to connect.
     * @param callback A callback for providing the result.
     */
    public void onFindSubscriptions(
            Uri handle,
            Response<Uri, Subscription> callback) {}

    /**
     * Create a Connection given a request.
     *
     * @param request Data encapsulating details of the desired Connection.
     * @param callback A callback for providing the result.
     */
    public void onCreateConnections(
            ConnectionRequest request,
            Response<ConnectionRequest, Connection> callback) {}

    /**
     * Create a Connection to match an incoming connection notification.
     *
     * @param request Data encapsulating details of the desired Connection.
     * @param callback A callback for providing the result.
     */
    public void onCreateIncomingConnection(
            ConnectionRequest request,
            Response<ConnectionRequest, Connection> callback) {}

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

    private CallState connectionStateToCallState(int connectionState) {
        switch (connectionState) {
            case Connection.State.NEW:
                return CallState.NEW;
            case Connection.State.RINGING:
                return CallState.RINGING;
            case Connection.State.DIALING:
                return CallState.DIALING;
            case Connection.State.ACTIVE:
                return CallState.ACTIVE;
            case Connection.State.HOLDING:
                return CallState.ON_HOLD;
            case Connection.State.DISCONNECTED:
                return CallState.DISCONNECTED;
            default:
                Log.wtf(TAG, "Unknown Connection.State " + connectionState);
                return CallState.NEW;
        }
    }

    private void addConnection(String callId, Connection connection) {
        mConnectionById.put(callId, connection);
        mIdByConnection.put(connection, callId);
        connection.addConnectionListener(mConnectionListener);
    }

    private void removeConnection(Connection connection) {
        connection.removeConnectionListener(mConnectionListener);
        mConnectionById.remove(mIdByConnection.get(connection));
        mIdByConnection.remove(connection);
    }

    private Connection findConnectionForAction(String callId, String action) {
        if (mConnectionById.containsKey(callId)) {
            return mConnectionById.get(callId);
        }
        Log.wtf(TAG, action + " - Cannot find Connection \"" + callId + "\"");
        return NULL_CONNECTION;
    }
}