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
import android.os.IBinder;
import android.os.RemoteException;

import com.android.internal.telecomm.ICallService;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @hide
 */
public class RemoteConnectionManager {
    private Map<ComponentName, RemoteConnectionService> mRemoteConnectionServices = new HashMap<>();

    void addConnectionService(ComponentName componentName, ICallService callService) {
        if (!mRemoteConnectionServices.containsKey(componentName)) {
            try {
                RemoteConnectionService remoteConnectionService =
                        new RemoteConnectionService(componentName, callService);
                mRemoteConnectionServices.put(componentName, remoteConnectionService);
            } catch (RemoteException ignored) {
            }
        }
    }

    List<Subscription> getSubscriptions(Uri handle) {
        List<Subscription> subscriptions = new LinkedList<>();
        Log.d(this, "Getting subscriptions: " + mRemoteConnectionServices.keySet());
        for (RemoteConnectionService remoteService : mRemoteConnectionServices.values()) {
            // TODO(santoscordon): Eventually this will be async.
            subscriptions.addAll(remoteService.lookupSubscriptions(handle));
        }
        return subscriptions;
    }

    public void createOutgoingConnection(
            ConnectionRequest request,
            final SimpleResponse<ConnectionRequest, RemoteConnection> response) {
        Subscription subscription = request.getSubscription();
        if (subscription == null) {
            throw new IllegalArgumentException("subscription must be specified.");
        }

        ComponentName componentName = request.getSubscription().getComponentName();
        if (!mRemoteConnectionServices.containsKey(componentName)) {
            throw new UnsupportedOperationException("subscription not supported: " + componentName);
        } else {
            RemoteConnectionService remoteService = mRemoteConnectionServices.get(componentName);
            remoteService.createOutgoingConnection(request, response);
        }
    }
}
