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
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.RemoteException;
import android.telephony.DisconnectCause;

import com.android.internal.telecomm.IConnectionService;
import com.android.internal.telecomm.IConnectionServiceAdapter;
import com.android.internal.telecomm.IVideoProvider;
import com.android.internal.telecomm.RemoteServiceCallback;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Remote connection service which other connection services can use to place calls on their behalf.
 *
 * @hide
 */
final class RemoteConnectionService {

    private static final RemoteConnection
            NULL_CONNECTION = new RemoteConnection("NULL", null, null);

    private final IConnectionServiceAdapter mServantDelegate = new IConnectionServiceAdapter() {
        @Override
        public void handleCreateConnectionSuccessful(
                String id,
                ConnectionRequest request,
                ParcelableConnection parcel) {
            RemoteConnection connection =
                    findConnectionForAction(id, "handleCreateConnectionSuccessful");
            if (connection != NULL_CONNECTION && mPendingConnections.contains(connection)) {
                mPendingConnections.remove(connection);
                connection.setState(parcel.getState());
                connection.setCallCapabilities(parcel.getCapabilities());
                connection.setHandle(
                        parcel.getHandle(), parcel.getHandlePresentation());
                connection.setCallerDisplayName(
                        parcel.getCallerDisplayName(),
                        parcel.getCallerDisplayNamePresentation());
                // TODO: Do we need to support video providers for remote connections?
            }
        }

        @Override
        public void handleCreateConnectionFailed(
                String id,
                ConnectionRequest request,
                int errorCode,
                String errorMessage) {
            // TODO: How do we propagate the failure codes?
            findConnectionForAction(id, "handleCreateConnectionFailed")
                    .setDestroyed();
        }

        @Override
        public void handleCreateConnectionCancelled(
                String id,
                ConnectionRequest request) {
            findConnectionForAction(id, "handleCreateConnectionCancelled")
                    .setDestroyed();
        }

        @Override
        public void setActive(String callId) {
            findConnectionForAction(callId, "setActive")
                    .setState(Connection.STATE_ACTIVE);
        }

        @Override
        public void setRinging(String callId) {
            findConnectionForAction(callId, "setRinging")
                    .setState(Connection.STATE_RINGING);
        }

        @Override
        public void setDialing(String callId) {
            findConnectionForAction(callId, "setDialing")
                    .setState(Connection.STATE_DIALING);
        }

        @Override
        public void setDisconnected(String callId, int disconnectCause,
                String disconnectMessage) {
            findConnectionForAction(callId, "setDisconnected")
                    .setDisconnected(disconnectCause, disconnectMessage);
        }

        @Override
        public void setOnHold(String callId) {
            findConnectionForAction(callId, "setOnHold")
                    .setState(Connection.STATE_HOLDING);
        }

        @Override
        public void setRequestingRingback(String callId, boolean ringing) {
            findConnectionForAction(callId, "setRequestingRingback")
                    .setRequestingRingback(ringing);
        }

        @Override
        public void setCallCapabilities(String callId, int callCapabilities) {
            findConnectionForAction("callId", "setCallCapabilities")
                    .setCallCapabilities(callCapabilities);
        }

        @Override
        public void setIsConferenced(String callId, String conferenceCallId) {
            // not supported for remote connections.
        }

        @Override
        public void addConferenceCall(String callId) {
            // not supported for remote connections.
        }

        @Override
        public void removeCall(String callId) {
            findConnectionForAction(callId, "removeCall")
                    .setDestroyed();
        }

        @Override
        public void onPostDialWait(String callId, String remaining) {
            findConnectionForAction(callId, "onPostDialWait")
                    .setPostDialWait(remaining);
        }

        @Override
        public void queryRemoteConnectionServices(RemoteServiceCallback callback) {
            // Not supported from remote connection service.
        }

        @Override
        public void setVideoProvider(String callId, IVideoProvider videoProvider) {
            // not supported for remote connections.
        }

        @Override
        public void setVideoState(String callId, int videoState) {
            findConnectionForAction(callId, "setVideoState")
                    .setVideoState(videoState);
        }

        @Override
        public void setAudioModeIsVoip(String callId, boolean isVoip) {
            findConnectionForAction(callId, "setAudioModeIsVoip")
                    .setAudioModeIsVoip(isVoip);
        }

        @Override
        public void setStatusHints(String callId, StatusHints statusHints) {
            findConnectionForAction(callId, "setStatusHints")
                    .setStatusHints(statusHints);
        }

        @Override
        public void setHandle(String callId, Uri handle, int presentation) {
            findConnectionForAction(callId, "setHandle")
                    .setHandle(handle, presentation);
        }

        @Override
        public void setCallerDisplayName(String callId, String callerDisplayName,
                int presentation) {
            findConnectionForAction(callId, "setCallerDisplayName")
                    .setCallerDisplayName(callerDisplayName, presentation);
        }

        @Override
        public void startActivityFromInCall(String callId, PendingIntent intent) {
            findConnectionForAction(callId, "startActivityFromInCall")
                    .startActivityFromInCall(intent);
        }

        @Override
        public IBinder asBinder() {
            throw new UnsupportedOperationException();
        }

        @Override
        public final void setConferenceableConnections(
                String callId, List<String> conferenceableConnectionIds) {

            // TODO: When we support more than 1 remote connection, this should
            // loop through the incoming list of connection IDs and acquire the list
            // of remote connections which correspond to the IDs. That list should
            // be set onto the remote connections.
            findConnectionForAction(callId, "setConferenceableConnections")
                    .setConferenceableConnections(Collections.<RemoteConnection>emptyList());
        }
    };

    private final ConnectionServiceAdapterServant mServant =
            new ConnectionServiceAdapterServant(mServantDelegate);

    private final DeathRecipient mDeathRecipient = new DeathRecipient() {
        @Override
        public void binderDied() {
            for (RemoteConnection c : mConnectionById.values()) {
                c.setDestroyed();
            }
            mConnectionById.clear();
            mPendingConnections.clear();
            mConnectionService.asBinder().unlinkToDeath(mDeathRecipient, 0);
        }
    };

    private final IConnectionService mConnectionService;
    private final Map<String, RemoteConnection> mConnectionById = new HashMap<>();
    private final Set<RemoteConnection> mPendingConnections = new HashSet<>();

    RemoteConnectionService(IConnectionService connectionService) throws RemoteException {
        mConnectionService = connectionService;
        mConnectionService.asBinder().linkToDeath(mDeathRecipient, 0);
    }

    @Override
    public String toString() {
        return "[RemoteCS - " + mConnectionService.asBinder().toString() + "]";
    }

    final RemoteConnection createRemoteConnection(
            PhoneAccountHandle connectionManagerPhoneAccount,
            ConnectionRequest request,
            boolean isIncoming) {
        final String id = UUID.randomUUID().toString();
        final ConnectionRequest newRequest = new ConnectionRequest(
                request.getAccountHandle(),
                request.getHandle(),
                request.getHandlePresentation(),
                request.getExtras(),
                request.getVideoState());
        try {
            if (mConnectionById.isEmpty()) {
                mConnectionService.addConnectionServiceAdapter(mServant.getStub());
            }
            RemoteConnection connection =
                    new RemoteConnection(id, mConnectionService, newRequest);
            mPendingConnections.add(connection);
            mConnectionById.put(id, connection);
            mConnectionService.createConnection(
                    connectionManagerPhoneAccount,
                    id,
                    newRequest,
                    isIncoming);
            connection.addListener(new RemoteConnection.Listener() {
                @Override
                public void onDestroyed(RemoteConnection connection) {
                    mConnectionById.remove(id);
                    if (mConnectionById.isEmpty()) {
                        try {
                            mConnectionService.removeConnectionServiceAdapter(mServant.getStub());
                        } catch (RemoteException e) {
                        }
                    }
                }
            });
            return connection;
        } catch (RemoteException e) {
            return RemoteConnection
                    .failure(DisconnectCause.ERROR_UNSPECIFIED, e.toString());
        }
    }

    private RemoteConnection findConnectionForAction(
            String callId, String action) {
        if (mConnectionById.containsKey(callId)) {
            return mConnectionById.get(callId);
        }
        Log.w(this, "%s - Cannot find Connection %s", action, callId);
        return NULL_CONNECTION;
    }
}
