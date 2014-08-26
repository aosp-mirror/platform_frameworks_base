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

    private static final RemoteConnection NULL_CONNECTION =
            new RemoteConnection("NULL", null, null);

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
                connection.setState(parcel.getState());
                connection.setCallCapabilities(parcel.getCapabilities());
                connection.setHandle(
                        parcel.getHandle(), parcel.getHandlePresentation());
                connection.setCallerDisplayName(
                        parcel.getCallerDisplayName(),
                        parcel.getCallerDisplayNamePresentation());
                List<RemoteConnection> conferenceable = new ArrayList<>();
                for (String confId : parcel.getConferenceableConnectionIds()) {
                    if (mConnectionById.containsKey(confId)) {
                        conferenceable.add(mConnectionById.get(confId));
                    }
                }
                connection.setConferenceableConnections(conferenceable);
                // TODO: Do we need to support video providers for remote connections?
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
        public void setDisconnected(String callId, int disconnectCause,
                String disconnectMessage) {
            if (mConnectionById.containsKey(callId)) {
                findConnectionForAction(callId, "setDisconnected")
                        .setDisconnected(disconnectCause, disconnectMessage);
            } else {
                findConferenceForAction(callId, "setDisconnected")
                        .setDisconnected(disconnectCause, disconnectMessage);
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
        public void setRequestingRingback(String callId, boolean ringing) {
            findConnectionForAction(callId, "setRequestingRingback")
                    .setRequestingRingback(ringing);
        }

        @Override
        public void setCallCapabilities(String callId, int callCapabilities) {
            if (mConnectionById.containsKey(callId)) {
                findConnectionForAction(callId, "setCallCapabilities")
                        .setCallCapabilities(callCapabilities);
            } else {
                findConferenceForAction(callId, "setCallCapabilities")
                        .setCallCapabilities(callCapabilities);
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
            conference.setCallCapabilities(parcel.getCapabilities());
            mConferenceById.put(callId, conference);
            conference.addListener(new RemoteConference.Listener() {
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
            List<RemoteConnection> conferenceable = new ArrayList<>();
            for (String id : conferenceableConnectionIds) {
                if (mConnectionById.containsKey(id)) {
                    conferenceable.add(mConnectionById.get(id));
                }
            }

            findConnectionForAction(callId, "setConferenceableConnections")
                    .setConferenceableConnections(conferenceable);
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
                request.getHandle(),
                request.getHandlePresentation(),
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
                    isIncoming);
            connection.addListener(new RemoteConnection.Listener() {
                @Override
                public void onDestroyed(RemoteConnection connection) {
                    mConnectionById.remove(id);
                    maybeDisconnectAdapter();
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
