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
import android.os.RemoteException;

import com.android.internal.telecomm.IConnectionService;

import java.util.HashMap;
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
                        new RemoteConnectionService(connectionService);
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
}
