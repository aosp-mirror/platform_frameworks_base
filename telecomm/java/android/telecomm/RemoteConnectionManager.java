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
import android.os.RemoteException;

import com.android.internal.telecomm.IConnectionService;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @hide
 */
public class RemoteConnectionManager {
    private Map<ComponentName, RemoteConnectionService> mRemoteConnectionServices = new HashMap<>();

    void addConnectionService(ComponentName componentName, IConnectionService connectionService) {
        if (!mRemoteConnectionServices.containsKey(componentName)) {
            try {
                RemoteConnectionService remoteConnectionService =
                        new RemoteConnectionService(componentName, connectionService);
                mRemoteConnectionServices.put(componentName, remoteConnectionService);
            } catch (RemoteException ignored) {
            }
        }
    }

    List<PhoneAccount> getAccounts(Uri handle) {
        List<PhoneAccount> accounts = new LinkedList<>();
        Log.d(this, "Getting accounts: " + mRemoteConnectionServices.keySet());
        for (RemoteConnectionService remoteService : mRemoteConnectionServices.values()) {
            // TODO(santoscordon): Eventually this will be async.
            accounts.addAll(remoteService.lookupAccounts(handle));
        }
        return accounts;
    }

    public void createRemoteConnection(
            ConnectionRequest request,
            ConnectionService.CreateConnectionResponse response,
            boolean isIncoming) {
        PhoneAccount account = request.getAccount();
        if (account == null) {
            throw new IllegalArgumentException("account must be specified.");
        }

        ComponentName componentName = request.getAccount().getComponentName();
        if (!mRemoteConnectionServices.containsKey(componentName)) {
            throw new UnsupportedOperationException("account not supported: " + componentName);
        } else {
            RemoteConnectionService remoteService = mRemoteConnectionServices.get(componentName);
            remoteService.createRemoteConnection(request, response, isIncoming);
        }
    }
}
