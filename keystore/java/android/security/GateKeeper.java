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

import android.compat.annotation.UnsupportedAppUsage;
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

    public static final long INVALID_SECURE_USER_ID = 0;

    private GateKeeper() {}

    public static IGateKeeperService getService() {
        IGateKeeperService service = IGateKeeperService.Stub.asInterface(
                ServiceManager.getService(Context.GATEKEEPER_SERVICE));
        if (service == null) {
            throw new IllegalStateException("Gatekeeper service not available");
        }
        return service;
    }

    @UnsupportedAppUsage
    public static long getSecureUserId() throws IllegalStateException {
        return getSecureUserId(UserHandle.myUserId());
    }

    /**
     * Return the secure user id for a given user id
     * @param userId the user id, e.g. 0
     * @return the secure user id or {@link GateKeeper#INVALID_SECURE_USER_ID} if no such mapping
     * for the given user id is found.
     * @throws IllegalStateException if there is an error retrieving the secure user id
     */
    public static long getSecureUserId(int userId) throws IllegalStateException {
        try {
            return getService().getSecureUserId(userId);
        } catch (RemoteException e) {
            throw new IllegalStateException(
                    "Failed to obtain secure user ID from gatekeeper", e);
        }
    }
}
