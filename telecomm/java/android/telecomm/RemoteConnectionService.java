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
 R* limitations under the License.
 */

package android.telecomm;

import android.content.ComponentName;
import android.net.Uri;
import android.os.IBinder.DeathRecipient;
import android.os.RemoteException;
import android.telephony.DisconnectCause;

import android.text.TextUtils;

import com.android.internal.telecomm.IConnectionService;
import com.android.internal.telecomm.IConnectionServiceAdapter;
import com.android.internal.telecomm.ICallVideoProvider;
import com.android.internal.telecomm.RemoteServiceCallback;

import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

/**
 * Remote connection service which other connection services can use to place calls on their behalf.
 *
 * @hide
 */
final class RemoteConnectionService implements DeathRecipient {
    private final IConnectionService mConnectionService;
    private final ComponentName mComponentName;

    private String mConnectionId;
    private ConnectionRequest mPendingRequest;
    private ConnectionService.CreateConnectionResponse<RemoteConnection> mPendingResponse;
    // Remote connection services only support a single connection.
    private RemoteConnection mConnection;

    private final IConnectionServiceAdapter mAdapter = new IConnectionServiceAdapter.Stub() {
        @Override
        public void handleCreateConnectionSuccessful(ConnectionRequest request) {
            if (isPendingConnection(request.getCallId())) {
                mConnection = new RemoteConnection(mConnectionService, request.getCallId());
                mPendingResponse.onSuccess(request, mConnection);
                clearPendingInformation();
            }
        }

        @Override
        public void handleCreateConnectionFailed(
                ConnectionRequest request, int errorCode, String errorMessage) {
            if (isPendingConnection(request.getCallId())) {
                mPendingResponse.onFailure(request, errorCode, errorMessage);
                mConnectionId = null;
                clearPendingInformation();
            }
        }

        @Override
        public void handleCreateConnectionCancelled(ConnectionRequest request) {
            if (isPendingConnection(request.getCallId())) {
                mPendingResponse.onCancel(request);
                mConnectionId = null;
                clearPendingInformation();
            }
        }

        @Override
        public void setActive(String connectionId) {
            if (isCurrentConnection(connectionId)) {
                mConnection.setState(Connection.State.ACTIVE);
            }
        }

        @Override
        public void setRinging(String connectionId) {
            if (isCurrentConnection(connectionId)) {
                mConnection.setState(Connection.State.RINGING);
            }
        }

        @Override
        public void setCallVideoProvider(
                String connectionId, ICallVideoProvider callVideoProvider) {
            // not supported for remote connections.
        }

        @Override
        public void setDialing(String connectionId) {
            if (isCurrentConnection(connectionId)) {
                mConnection.setState(Connection.State.DIALING);
            }
        }

        @Override
        public void setDisconnected(
                String connectionId, int disconnectCause, String disconnectMessage) {
            if (isCurrentConnection(connectionId)) {
                mConnection.setDisconnected(disconnectCause, disconnectMessage);
            }
        }

        @Override
        public void setOnHold(String connectionId) {
            if (isCurrentConnection(connectionId)) {
                mConnection.setState(Connection.State.HOLDING);
            }
        }

        @Override
        public void setRequestingRingback(String connectionId, boolean isRequestingRingback) {
            if (isCurrentConnection(connectionId)) {
                mConnection.setRequestingRingback(isRequestingRingback);
            }
        }

        @Override
        public void setCallCapabilities(String connectionId, int callCapabilities) {
            if (isCurrentConnection(connectionId)) {
                mConnection.setCallCapabilities(callCapabilities);
            }
        }

        @Override
        public void setIsConferenced(String connectionId, String conferenceConnectionId) {
            // not supported for remote connections.
        }

        @Override
        public void addConferenceCall(String connectionId) {
            // not supported for remote connections.
        }

        @Override
        public void removeCall(String connectionId) {
            if (isCurrentConnection(connectionId)) {
                destroyConnection();
            }
        }

        @Override
        public void onPostDialWait(String connectionId, String remainingDigits) {
            if (isCurrentConnection(connectionId)) {
                mConnection.setPostDialWait(remainingDigits);
            }
        }

        @Override
        public void queryRemoteConnectionServices(RemoteServiceCallback callback) {
            try {
                // Not supported from remote connection service.
                callback.onError();
            } catch (RemoteException e) {
            }
        }

        @Override
        public final void setAudioModeIsVoip(String connectionId, boolean isVoip) {
            if (isCurrentConnection(connectionId)) {
                mConnection.setAudioModeIsVoip(isVoip);
            }
        }

        @Override
        public final void setStatusHints(String connectionId, StatusHints statusHints) {
            if (isCurrentConnection(connectionId)) {
                mConnection.setStatusHints(statusHints);
            }
        }

        @Override
        public final void setHandle(String connectionId, Uri handle, int presentation) {
            if (isCurrentConnection(connectionId)) {
                mConnection.setHandle(handle, presentation);
            }
        }

        @Override
        public final void setCallerDisplayName(
                String connectionId, String callerDisplayName, int presentation) {
            if (isCurrentConnection(connectionId)) {
                mConnection.setCallerDisplayName(callerDisplayName, presentation);
            }
        }
    };

    RemoteConnectionService(ComponentName componentName, IConnectionService connectionService)
            throws RemoteException {
        mComponentName = componentName;
        mConnectionService = connectionService;

        mConnectionService.addConnectionServiceAdapter(mAdapter);
        mConnectionService.asBinder().linkToDeath(this, 0);
    }

    @Override
    public String toString() {
        return "[RemoteCS - " + mConnectionService.asBinder().toString() + "]";
    }

    /** ${inheritDoc} */
    @Override
    public final void binderDied() {
        if (mConnection != null) {
            destroyConnection();
        }

        release();
    }

    final void createRemoteConnection(
            ConnectionRequest request,
            ConnectionService.CreateConnectionResponse<RemoteConnection> response,
            boolean isIncoming) {

        if (mConnectionId == null) {
            String id = UUID.randomUUID().toString();
            ConnectionRequest newRequest = new ConnectionRequest(
                    request.getAccount(),
                    id,
                    request.getHandle(),
                    request.getHandlePresentation(),
                    request.getExtras(),
                    request.getVideoState());
            try {
                mConnectionService.createConnection(newRequest, isIncoming);
                mConnectionId = id;
                mPendingResponse = response;
                mPendingRequest = request;
            } catch (RemoteException e) {
                response.onFailure(request, DisconnectCause.ERROR_UNSPECIFIED, e.toString());
            }
        } else {
            response.onFailure(request, DisconnectCause.ERROR_UNSPECIFIED, null);
        }
    }

    final List<PhoneAccount> lookupAccounts(Uri handle) {
        // TODO(santoscordon): Update this so that is actually calls into the RemoteConnection
        // each time.
        List<PhoneAccount> accounts = new LinkedList<>();
        accounts.add(new PhoneAccount(
                mComponentName,
                null /* id */,
                null /* handle */,
                0 /* capabilities */));
        return accounts;
    }

    /**
     * Releases the resources associated with this Remote connection service. Should be called when
     * the remote service is no longer being used.
     */
    void release() {
        mConnectionService.asBinder().unlinkToDeath(this, 0);
    }

    private boolean isPendingConnection(String id) {
        return TextUtils.equals(mConnectionId, id) && mPendingResponse != null;
    }

    private boolean isCurrentConnection(String id) {
        return mConnection != null && TextUtils.equals(mConnectionId, id);
    }

    private void clearPendingInformation() {
        mPendingRequest = null;
        mPendingResponse = null;
    }

    private void destroyConnection() {
        mConnection.setDestroyed();
        mConnection = null;
        mConnectionId = null;
    }
}
