/*
 * Copyright (C) 2019 The Android Open Source Project
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
import android.content.Context;

/**
 * service used to communicate with the ip memory store service in network stack,
 * which is running in the same module.
 * @see com.android.server.connectivity.ipmemorystore.IpMemoryStoreService
 * @hide
 */
public class NetworkStackIpMemoryStore extends IpMemoryStoreClient {
    @NonNull private final IIpMemoryStore mService;

    public NetworkStackIpMemoryStore(@NonNull final Context context,
            @NonNull final IIpMemoryStore service) {
        super(context);
        mService = service;
    }

    @Override
    @NonNull
    protected IIpMemoryStore getService() {
        return mService;
    }
}
