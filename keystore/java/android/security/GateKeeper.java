/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.security;

import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.service.gatekeeper.IGateKeeperService;

/**
 * Convenience class for accessing the gatekeeper service.
 *
 * @hide
 */
public abstract class GateKeeper {

    private GateKeeper() {}

    public static IGateKeeperService getService() {
        IGateKeeperService service = IGateKeeperService.Stub.asInterface(
                ServiceManager.getService(Context.GATEKEEPER_SERVICE));
        if (service == null) {
            throw new IllegalStateException("Gatekeeper service not available");
        }
        return service;
    }

    public static long getSecureUserId() throws IllegalStateException {
        try {
            return getService().getSecureUserId(UserHandle.myUserId());
        } catch (RemoteException e) {
            throw new IllegalStateException(
                    "Failed to obtain secure user ID from gatekeeper", e);
        }
    }
}
