/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.vcn;

import static com.android.server.VcnManagementService.VDBG;

import android.annotation.NonNull;
import android.content.Context;
import android.net.NetworkProvider;
import android.net.NetworkRequest;
import android.os.Looper;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.annotations.VisibleForTesting.Visibility;

import java.util.Objects;
import java.util.Set;

/**
 * VCN Network Provider routes NetworkRequests to listeners to bring up tunnels as needed.
 *
 * <p>The VcnNetworkProvider provides a caching layer to ensure that all listeners receive all
 * active NetworkRequest(s), including ones that were filed prior to listener registration.
 *
 * @hide
 */
public class VcnNetworkProvider extends NetworkProvider {
    private static final String TAG = VcnNetworkProvider.class.getSimpleName();

    private final Set<NetworkRequestListener> mListeners = new ArraySet<>();

    /**
     * Cache of NetworkRequest(s), scores and network providers, keyed by NetworkRequest
     *
     * <p>NetworkRequests are immutable once created, and therefore can be used as stable keys.
     */
    private final ArrayMap<NetworkRequest, NetworkRequestEntry> mRequests = new ArrayMap<>();

    public VcnNetworkProvider(Context context, Looper looper) {
        super(context, looper, VcnNetworkProvider.class.getSimpleName());
    }

    /**
     * Registers a NetworkRequestListener with this NetworkProvider.
     *
     * <p>Upon registering, the provided listener will receive all cached requests.
     */
    @VisibleForTesting(visibility = Visibility.PACKAGE)
    public void registerListener(@NonNull NetworkRequestListener listener) {
        mListeners.add(listener);

        // Send listener all cached requests
        resendAllRequests(listener);
    }

    /** Unregisters the specified listener from receiving future NetworkRequests. */
    @VisibleForTesting(visibility = Visibility.PACKAGE)
    public void unregisterListener(@NonNull NetworkRequestListener listener) {
        mListeners.remove(listener);
    }

    /** Sends all cached NetworkRequest(s) to the specified listener. */
    @VisibleForTesting(visibility = Visibility.PACKAGE)
    public void resendAllRequests(@NonNull NetworkRequestListener listener) {
        for (NetworkRequestEntry entry : mRequests.values()) {
            notifyListenerForEvent(listener, entry);
        }
    }

    private void notifyListenerForEvent(
            @NonNull NetworkRequestListener listener, @NonNull NetworkRequestEntry entry) {
        listener.onNetworkRequested(entry.mRequest, entry.mScore, entry.mProviderId);
    }

    @Override
    public void onNetworkRequested(@NonNull NetworkRequest request, int score, int providerId) {
        if (VDBG) {
            Slog.v(
                    TAG,
                    "Network requested: Request = "
                            + request
                            + ", score = "
                            + score
                            + ", providerId = "
                            + providerId);
        }

        final NetworkRequestEntry entry = new NetworkRequestEntry(request, score, providerId);

        // NetworkRequests are immutable once created, and therefore can be used as stable keys.
        mRequests.put(request, entry);

        // TODO(b/176939047): Intelligently route requests to prioritized VcnInstances (based on
        // Default Data Sub, or similar)
        for (NetworkRequestListener listener : mListeners) {
            notifyListenerForEvent(listener, entry);
        }
    }

    @Override
    public void onNetworkRequestWithdrawn(@NonNull NetworkRequest request) {
        mRequests.remove(request);
    }

    private static class NetworkRequestEntry {
        public final NetworkRequest mRequest;
        public final int mScore;
        public final int mProviderId;

        private NetworkRequestEntry(@NonNull NetworkRequest request, int score, int providerId) {
            mRequest = Objects.requireNonNull(request, "Missing request");
            mScore = score;
            mProviderId = providerId;
        }
    }

    // package-private
    interface NetworkRequestListener {
        void onNetworkRequested(@NonNull NetworkRequest request, int score, int providerId);
    }
}
