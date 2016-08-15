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

import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.RemoteException;

import com.android.internal.telecom.IConnectionService;
import com.android.internal.telecom.IConnectionServiceAdapter;
import com.android.internal.telecom.IVideoProvider;
import com.android.internal.telecom.RemoteServiceCallback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.UUID;

/**
 * Remote connection service which other connection services can use to place calls on their behalf.
 *
 * @hide
 */
final class RemoteConnectionService {

    // Note: Casting null to avoid ambiguous constructor reference.
    private static final RemoteConnection NULL_CONNECTION =
            new RemoteConnection("NULL", null, (ConnectionRequest) null);

    private static final RemoteConference NULL_CONFERENCE =
            new RemoteConference("NULL", null);

    private final IConnectionServiceAdapter mServantDelegate = new IConnectionServiceAdapter() {
        @Override
        public void handleCreateConnectionComplete(
                String id,
                ConnectionRequest request,
                ParcelableConnection parcel) {
            RemoteConnection connection =
                    findConnectionForAction(id, "handleCreateConnectionSuccessful");
            if (connection != NULL_CONNECTION && mPendingConnections.contains(connection)) {
                mPendingConnections.remove(connection);
                // Unconditionally initialize the connection ...
                connection.setConnectionCapabilities(parcel.getConnectionCapabilities());
                connection.setConnectionProperties(parcel.getConnectionProperties());
                if (parcel.getHandle() != null
                    || parcel.getState() != Connection.STATE_DISCONNECTED) {
                    connection.setAddress(parcel.getHandle(), parcel.getHandlePresentation());
                }
                if (parcel.getCallerDisplayName() != null
                    || parcel.getState() != Connection.STATE_DISCONNECTED) {
                    connection.setCallerDisplayName(
                            parcel.getCallerDisplayName(),
                            parcel.getCallerDisplayNamePresentation());
                }
                // Set state after handle so that the client can identify the connection.
                if (parcel.getState() == Connection.STATE_DISCONNECTED) {
                    connection.setDisconnected(parcel.getDisconnectCause());
                } else {
                    connection.setState(parcel.getState());
                }
                List<RemoteConnection> conferenceable = new ArrayList<>();
                for (String confId : parcel.getConferenceableConnectionIds()) {
                    if (mConnectionById.containsKey(confId)) {
                        conferenceable.add(mConnectionById.get(confId));
                    }
                }
                connection.setConferenceableConnections(conferenceable);
                connection.setVideoState(parcel.getVideoState());
                if (connection.getState() == Connection.STATE_DISCONNECTED) {
                    // ... then, if it was created in a disconnected state, that indicates
                    // failure on the providing end, so immediately mark it destroyed
                    connection.setDestroyed();
                }
            }
        }

        @Override
        public void setActive(String callId) {
            if (mConnectionById.containsKey(callId)) {
                findConnectionForAction(callId, "setActive")
                        .setState(Connection.STATE_ACTIVE);
            } else {
                findConferenceForAction(callId, "setActive")
                        .setState(Connection.STATE_ACTIVE);
            }
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
        public void setPulling(String callId) {
            findConnectionForAction(callId, "setPulling")
                    .setState(Connection.STATE_PULLING_CALL);
        }

        @Override
        public void setDisconnected(String callId, DisconnectCause disconnectCause) {
            if (mConnectionById.containsKey(callId)) {
                findConnectionForAction(callId, "setDisconnected")
                        .setDisconnected(disconnectCause);
            } else {
                findConferenceForAction(callId, "setDisconnected")
                        .setDisconnected(disconnectCause);
            }
        }

        @Override
        public void setOnHold(String callId) {
            if (mConnectionById.containsKey(callId)) {
                findConnectionForAction(callId, "setOnHold")
                        .setState(Connection.STATE_HOLDING);
            } else {
                findConferenceForAction(callId, "setOnHold")
                        .setState(Connection.STATE_HOLDING);
            }
        }

        @Override
        public void setRingbackRequested(String callId, boolean ringing) {
            findConnectionForAction(callId, "setRingbackRequested")
                    .setRingbackRequested(ringing);
        }

        @Override
        public void setConnectionCapabilities(String callId, int connectionCapabilities) {
            if (mConnectionById.containsKey(callId)) {
                findConnectionForAction(callId, "setConnectionCapabilities")
                        .setConnectionCapabilities(connectionCapabilities);
            } else {
                findConferenceForAction(callId, "setConnectionCapabilities")
                        .setConnectionCapabilities(connectionCapabilities);
            }
        }

        @Override
        public void setConnectionProperties(String callId, int connectionProperties) {
            if (mConnectionById.containsKey(callId)) {
                findConnectionForAction(callId, "setConnectionProperties")
                        .setConnectionProperties(connectionProperties);
            } else {
                findConferenceForAction(callId, "setConnectionProperties")
                        .setConnectionProperties(connectionProperties);
            }
        }

        @Override
        public void setIsConferenced(String callId, String conferenceCallId) {
            // Note: callId should not be null; conferenceCallId may be null
            RemoteConnection connection =
                    findConnectionForAction(callId, "setIsConferenced");
            if (connection != NULL_CONNECTION) {
                if (conferenceCallId == null) {
                    // 'connection' is being split from its conference
                    if (connection.getConference() != null) {
                        connection.getConference().removeConnection(connection);
                    }
                } else {
                    RemoteConference conference =
                            findConferenceForAction(conferenceCallId, "setIsConferenced");
                    if (conference != NULL_CONFERENCE) {
                        conference.addConnection(connection);
                    }
                }
            }
        }

        @Override
        public void setConferenceMergeFailed(String callId) {
            // Nothing to do here.
            // The event has already been handled and there is no state to update
            // in the underlying connection or conference objects
        }

        @Override
        public void addConferenceCall(
                final String callId,
                ParcelableConference parcel) {
            RemoteConference conference = new RemoteConference(callId,
                    mOutgoingConnectionServiceRpc);

            for (String id : parcel.getConnectionIds()) {
                RemoteConnection c = mConnectionById.get(id);
                if (c != null) {
                    conference.addConnection(c);
                }
            }

            if (conference.getConnections().size() == 0) {
                // A conference was created, but none of its connections are ones that have been
                // created by, and therefore being tracked by, this remote connection service. It
                // is of no interest to us.
                return;
            }

            conference.setState(parcel.getState());
            conference.setConnectionCapabilities(parcel.getConnectionCapabilities());
            conference.setConnectionProperties(parcel.getConnectionProperties());
            mConferenceById.put(callId, conference);
            conference.registerCallback(new RemoteConference.Callback() {
                @Override
                public void onDestroyed(RemoteConference c) {
                    mConferenceById.remove(callId);
                    maybeDisconnectAdapter();
                }
            });

            mOurConnectionServiceImpl.addRemoteConference(conference);
        }

        @Override
        public void removeCall(String callId) {
            if (mConnectionById.containsKey(callId)) {
                findConnectionForAction(callId, "removeCall")
                        .setDestroyed();
            } else {
                findConferenceForAction(callId, "removeCall")
                        .setDestroyed();
            }
        }

        @Override
        public void onPostDialWait(String callId, String remaining) {
            findConnectionForAction(callId, "onPostDialWait")
                    .setPostDialWait(remaining);
        }

        @Override
        public void onPostDialChar(String callId, char nextChar) {
            findConnectionForAction(callId, "onPostDialChar")
                    .onPostDialChar(nextChar);
        }

        @Override
        public void queryRemoteConnectionServices(RemoteServiceCallback callback) {
            // Not supported from remote connection service.
        }

        @Override
        public void setVideoProvider(String callId, IVideoProvider videoProvider) {
            RemoteConnection.VideoProvider remoteVideoProvider = null;
            if (videoProvider != null) {
                remoteVideoProvider = new RemoteConnection.VideoProvider(videoProvider);
            }
            findConnectionForAction(callId, "setVideoProvider")
                    .setVideoProvider(remoteVideoProvider);
        }

        @Override
        public void setVideoState(String callId, int videoState) {
            findConnectionForAction(callId, "setVideoState")
                    .setVideoState(videoState);
        }

        @Override
        public void setIsVoipAudioMode(String callId, boolean isVoip) {
            findConnectionForAction(callId, "setIsVoipAudioMode")
                    .setIsVoipAudioMode(isVoip);
        }

        @Override
        public void setStatusHints(String callId, StatusHints statusHints) {
            findConnectionForAction(callId, "setStatusHints")
                    .setStatusHints(statusHints);
        }

        @Override
        public void setAddress(String callId, Uri address, int presentation) {
            findConnectionForAction(callId, "setAddress")
                    .setAddress(address, presentation);
        }

        @Override
        public void setCallerDisplayName(String callId, String callerDisplayName,
                int presentation) {
            findConnectionForAction(callId, "setCallerDisplayName")
                    .setCallerDisplayName(callerDisplayName, presentation);
        }

        @Override
        public IBinder asBinder() {
            throw new UnsupportedOperationException();
        }

        @Override
        public final void setConferenceableConnections(
                String callId, List<String> conferenceableConnectionIds) {
            List<RemoteConnection> conferenceable = new ArrayList<>();
            for (String id : conferenceableConnectionIds) {
                if (mConnectionById.containsKey(id)) {
                    conferenceable.add(mConnectionById.get(id));
                }
            }

            if (hasConnection(callId)) {
                findConnectionForAction(callId, "setConferenceableConnections")
                        .setConferenceableConnections(conferenceable);
            } else {
                findConferenceForAction(callId, "setConferenceableConnections")
                        .setConferenceableConnections(conferenceable);
            }
        }

        @Override
        public void addExistingConnection(String callId, ParcelableConnection connection) {
            // TODO: add contents of this method
            RemoteConnection remoteConnction = new RemoteConnection(callId,
                    mOutgoingConnectionServiceRpc, connection);

            mOurConnectionServiceImpl.addRemoteExistingConnection(remoteConnction);
        }

        @Override
        public void putExtras(String callId, Bundle extras) {
            if (hasConnection(callId)) {
                findConnectionForAction(callId, "putExtras").putExtras(extras);
            } else {
                findConferenceForAction(callId, "putExtras").putExtras(extras);
            }
        }

        @Override
        public void removeExtras(String callId, List<String> keys) {
            if (hasConnection(callId)) {
                findConnectionForAction(callId, "removeExtra").removeExtras(keys);
            } else {
                findConferenceForAction(callId, "removeExtra").removeExtras(keys);
            }
        }

        @Override
        public void onConnectionEvent(String callId, String event, Bundle extras) {
            if (mConnectionById.containsKey(callId)) {
                findConnectionForAction(callId, "onConnectionEvent").onConnectionEvent(event,
                        extras);
            }
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
            for (RemoteConference c : mConferenceById.values()) {
                c.setDestroyed();
            }
            mConnectionById.clear();
            mConferenceById.clear();
            mPendingConnections.clear();
            mOutgoingConnectionServiceRpc.asBinder().unlinkToDeath(mDeathRecipient, 0);
        }
    };

    private final IConnectionService mOutgoingConnectionServiceRpc;
    private final ConnectionService mOurConnectionServiceImpl;
    private final Map<String, RemoteConnection> mConnectionById = new HashMap<>();
    private final Map<String, RemoteConference> mConferenceById = new HashMap<>();
    private final Set<RemoteConnection> mPendingConnections = new HashSet<>();

    RemoteConnectionService(
            IConnectionService outgoingConnectionServiceRpc,
            ConnectionService ourConnectionServiceImpl) throws RemoteException {
        mOutgoingConnectionServiceRpc = outgoingConnectionServiceRpc;
        mOutgoingConnectionServiceRpc.asBinder().linkToDeath(mDeathRecipient, 0);
        mOurConnectionServiceImpl = ourConnectionServiceImpl;
    }

    @Override
    public String toString() {
        return "[RemoteCS - " + mOutgoingConnectionServiceRpc.asBinder().toString() + "]";
    }

    final RemoteConnection createRemoteConnection(
            PhoneAccountHandle connectionManagerPhoneAccount,
            ConnectionRequest request,
            boolean isIncoming) {
        final String id = UUID.randomUUID().toString();
        final ConnectionRequest newRequest = new ConnectionRequest(
                request.getAccountHandle(),
                request.getAddress(),
                request.getExtras(),
                request.getVideoState());
        try {
            if (mConnectionById.isEmpty()) {
                mOutgoingConnectionServiceRpc.addConnectionServiceAdapter(mServant.getStub());
            }
            RemoteConnection connection =
                    new RemoteConnection(id, mOutgoingConnectionServiceRpc, newRequest);
            mPendingConnections.add(connection);
            mConnectionById.put(id, connection);
            mOutgoingConnectionServiceRpc.createConnection(
                    connectionManagerPhoneAccount,
                    id,
                    newRequest,
                    isIncoming,
                    false /* isUnknownCall */);
            connection.registerCallback(new RemoteConnection.Callback() {
                @Override
                public void onDestroyed(RemoteConnection connection) {
                    mConnectionById.remove(id);
                    maybeDisconnectAdapter();
                }
            });
            return connection;
        } catch (RemoteException e) {
            return RemoteConnection.failure(
                    new DisconnectCause(DisconnectCause.ERROR, e.toString()));
        }
    }

    private boolean hasConnection(String callId) {
        return mConnectionById.containsKey(callId);
    }

    private RemoteConnection findConnectionForAction(
            String callId, String action) {
        if (mConnectionById.containsKey(callId)) {
            return mConnectionById.get(callId);
        }
        Log.w(this, "%s - Cannot find Connection %s", action, callId);
        return NULL_CONNECTION;
    }

    private RemoteConference findConferenceForAction(
            String callId, String action) {
        if (mConferenceById.containsKey(callId)) {
            return mConferenceById.get(callId);
        }
        Log.w(this, "%s - Cannot find Conference %s", action, callId);
        return NULL_CONFERENCE;
    }

    private void maybeDisconnectAdapter() {
        if (mConnectionById.isEmpty() && mConferenceById.isEmpty()) {
            try {
                mOutgoingConnectionServiceRpc.removeConnectionServiceAdapter(mServant.getStub());
            } catch (RemoteException e) {
            }
        }
    }
}
