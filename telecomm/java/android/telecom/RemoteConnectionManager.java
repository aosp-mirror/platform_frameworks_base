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
        if (!mRemoteConnectionServices.containsKey(componentName)) {
            try {
                RemoteConnectionService remoteConnectionService = new RemoteConnectionService(
                        outgoingConnectionServiceRpc,
                        mOurConnectionServiceImpl);
                mRemoteConnectionServices.put(componentName, remoteConnectionService);
            } catch (RemoteException ignored) {
            }
        }
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
        if (!mRemoteConnectionServices.containsKey(componentName)) {
            throw new UnsupportedOperationException("accountHandle not supported: "
                    + componentName);
        }

        RemoteConnectionService remoteService = mRemoteConnectionServices.get(componentName);
        if (remoteService != null) {
            return remoteService.createRemoteConnection(
                    connectionManagerPhoneAccount, request, isIncoming);
        }
        return null;
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
