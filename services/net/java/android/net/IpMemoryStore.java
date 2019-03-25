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
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Manager class used to communicate with the ip memory store service in the network stack,
 * which is running in a separate module.
 * @hide
*/
public class IpMemoryStore extends IpMemoryStoreClient {
    private static final String TAG = IpMemoryStore.class.getSimpleName();
    @NonNull private final CompletableFuture<IIpMemoryStore> mService;
    @NonNull private final AtomicReference<CompletableFuture<IIpMemoryStore>> mTailNode;

    public IpMemoryStore(@NonNull final Context context) {
        super(context);
        mService = new CompletableFuture<>();
        mTailNode = new AtomicReference<CompletableFuture<IIpMemoryStore>>(mService);
        getNetworkStackClient().fetchIpMemoryStore(
                new IIpMemoryStoreCallbacks.Stub() {
                    @Override
                    public void onIpMemoryStoreFetched(@NonNull final IIpMemoryStore memoryStore) {
                        mService.complete(memoryStore);
                    }

                    @Override
                    public int getInterfaceVersion() {
                        return this.VERSION;
                    }
                });
    }

    /*
     *  If the IpMemoryStore is ready, this function will run the request synchronously.
     *  Otherwise, it will enqueue the requests for execution immediately after the
     *  service becomes ready. The requests are guaranteed to be executed in the order
     *  they are sumbitted.
     */
    @Override
    protected void runWhenServiceReady(Consumer<IIpMemoryStore> cb) throws ExecutionException {
        mTailNode.getAndUpdate(future -> future.handle((store, exception) -> {
            if (exception != null) {
                // this should never happens since we also catch the exception below
                Log.wtf(TAG, "Error fetching IpMemoryStore", exception);
                return store;
            }

            try {
                cb.accept(store);
            } catch (Exception e) {
                Log.wtf(TAG, "Exception occured: " + e.getMessage());
            }
            return store;
        }));
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
