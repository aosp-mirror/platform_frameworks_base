/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.net;

import android.annotation.NonNull;
import android.annotation.SystemService;
import android.content.Context;
import android.os.RemoteException;

import com.android.internal.util.Preconditions;

/**
 * The interface for system components to access the IP memory store.
 * @see com.android.server.net.ipmemorystore.IpMemoryStoreService
 * @hide
 */
@SystemService(Context.IP_MEMORY_STORE_SERVICE)
public class IpMemoryStore {
    @NonNull final Context mContext;
    @NonNull final IIpMemoryStore mService;

    public IpMemoryStore(@NonNull final Context context, @NonNull final IIpMemoryStore service) {
        mContext = Preconditions.checkNotNull(context, "missing context");
        mService = Preconditions.checkNotNull(service, "missing IIpMemoryStore");
    }

    /**
     * Returns the version of the memory store
     * @hide
     **/
    // TODO : remove this
    public int version() {
        try {
            return mService.version();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
