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

import com.android.internal.annotations.VisibleForTesting;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Manager class used to communicate with the ip memory store service in the network stack,
 * which is running in a separate module.
 * @hide
*/
public class IpMemoryStore extends IpMemoryStoreClient {
    private final CompletableFuture<IIpMemoryStore> mService;

    public IpMemoryStore(@NonNull final Context context) {
        super(context);
        mService = new CompletableFuture<>();
        getNetworkStackClient().fetchIpMemoryStore(
                new IIpMemoryStoreCallbacks.Stub() {
                    @Override
                    public void onIpMemoryStoreFetched(final IIpMemoryStore memoryStore) {
                        mService.complete(memoryStore);
                    }
                });
    }

    @Override
    protected IIpMemoryStore getService() throws InterruptedException, ExecutionException {
        return mService.get();
    }

    @VisibleForTesting
    protected NetworkStackClient getNetworkStackClient() {
        return NetworkStackClient.getInstance();
    }

    /** Gets an instance of the memory store */
    @NonNull
    public static IpMemoryStore getMemoryStore(final Context context) {
        return new IpMemoryStore(context);
    }
}
