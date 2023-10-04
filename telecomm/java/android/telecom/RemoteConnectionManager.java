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

package android.telecom;

import android.content.ComponentName;
import android.os.RemoteException;

import com.android.internal.telecom.IConnectionService;

import java.util.HashMap;
import java.util.Map;

/**
 * @hide
 */
public class RemoteConnectionManager {
    private final Map<ComponentName, RemoteConnectionService> mRemoteConnectionServices =
            new HashMap<>();
    private final ConnectionService mOurConnectionServiceImpl;

    public RemoteConnectionManager(ConnectionService ourConnectionServiceImpl) {
        mOurConnectionServiceImpl = ourConnectionServiceImpl;
    }

    void addConnectionService(
            ComponentName componentName,
            IConnectionService outgoingConnectionServiceRpc) {
        mRemoteConnectionServices.computeIfAbsent(
                componentName,
                key -> {
                    try {
                        return new RemoteConnectionService(
                                outgoingConnectionServiceRpc, mOurConnectionServiceImpl);
                    } catch (RemoteException e) {
                        Log.w(
                                RemoteConnectionManager.this,
                                "error when addConnectionService of %s: %s",
                                componentName,
                                e.toString());
                        return null;
                    }
                });
    }

    public RemoteConnection createRemoteConnection(
            PhoneAccountHandle connectionManagerPhoneAccount,
            ConnectionRequest request,
            boolean isIncoming) {
        PhoneAccountHandle accountHandle = request.getAccountHandle();
        if (accountHandle == null) {
            throw new IllegalArgumentException("accountHandle must be specified.");
        }

        ComponentName componentName = request.getAccountHandle().getComponentName();
        RemoteConnectionService remoteService = mRemoteConnectionServices.get(componentName);
        if (remoteService == null) {
            throw new UnsupportedOperationException("accountHandle not supported: "
                    + componentName);
        }

        return remoteService.createRemoteConnection(
                connectionManagerPhoneAccount, request, isIncoming);
    }

    /**
     * Ask a {@code RemoteConnectionService} to create a {@code RemoteConference}.
     * @param connectionManagerPhoneAccount See description at
     * {@link ConnectionService#onCreateOutgoingConnection(PhoneAccountHandle, ConnectionRequest)}.
     * @param request Details about the incoming conference call.
     * @param isIncoming {@code true} if it's an incoming conference.
     * @return
     */
    public RemoteConference createRemoteConference(
            PhoneAccountHandle connectionManagerPhoneAccount,
            ConnectionRequest request,
            boolean isIncoming) {
        PhoneAccountHandle accountHandle = request.getAccountHandle();
        if (accountHandle == null) {
            throw new IllegalArgumentException("accountHandle must be specified.");
        }

        ComponentName componentName = request.getAccountHandle().getComponentName();
        RemoteConnectionService remoteService = mRemoteConnectionServices.get(componentName);
        if (remoteService == null) {
            throw new UnsupportedOperationException("accountHandle not supported: "
                    + componentName);
        }

        return remoteService.createRemoteConference(
                connectionManagerPhoneAccount, request, isIncoming);
    }

    public void conferenceRemoteConnections(RemoteConnection a, RemoteConnection b) {
        if (a.getConnectionService() == b.getConnectionService()) {
            try {
                a.getConnectionService().conference(a.getId(), b.getId(), null /*Session.Info*/);
            } catch (RemoteException e) {
            }
        } else {
            Log.w(this, "Request to conference incompatible remote connections (%s,%s) (%s,%s)",
                    a.getConnectionService(), a.getId(),
                    b.getConnectionService(), b.getId());
        }
    }
}
