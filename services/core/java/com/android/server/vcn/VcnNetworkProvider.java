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

import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN;
import static android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;

import static com.android.server.VcnManagementService.VDBG;

import android.annotation.NonNull;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkProvider;
import android.net.NetworkRequest;
import android.net.NetworkScore;
import android.net.vcn.VcnGatewayConnectionConfig;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.annotations.VisibleForTesting.Visibility;
import com.android.internal.util.IndentingPrintWriter;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

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

    private final Context mContext;
    private final Handler mHandler;
    private final Dependencies mDeps;

    /**
     * Cache of NetworkRequest(s).
     *
     * <p>NetworkRequests are immutable once created, and therefore can be used as stable keys.
     */
    private final Set<NetworkRequest> mRequests = new ArraySet<>();

    public VcnNetworkProvider(@NonNull Context context, @NonNull Looper looper) {
        this(context, looper, new Dependencies());
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public VcnNetworkProvider(
            @NonNull Context context, @NonNull Looper looper, @NonNull Dependencies dependencies) {
        super(
                Objects.requireNonNull(context, "Missing context"),
                Objects.requireNonNull(looper, "Missing looper"),
                TAG);

        mContext = context;
        mHandler = new Handler(looper);
        mDeps = Objects.requireNonNull(dependencies, "Missing dependencies");
    }

    /** Registers this VcnNetworkProvider and a generic network offer with ConnectivityService. */
    public void register() {
        mContext.getSystemService(ConnectivityManager.class).registerNetworkProvider(this);
        mDeps.registerNetworkOffer(
                this,
                Vcn.getNetworkScore(), // score filter
                buildCapabilityFilter(),
                new HandlerExecutor(mHandler),
                new NetworkOfferCallback() {
                    @Override
                    public void onNetworkNeeded(@NonNull NetworkRequest request) {
                        handleNetworkRequested(request);
                    }

                    @Override
                    public void onNetworkUnneeded(@NonNull NetworkRequest request) {
                        handleNetworkRequestWithdrawn(request);
                    }
                });
    }

    /** Builds the filter for NetworkRequests that can be served by the VcnNetworkProvider. */
    private NetworkCapabilities buildCapabilityFilter() {
        final NetworkCapabilities.Builder builder =
                new NetworkCapabilities.Builder()
                        .addTransportType(TRANSPORT_CELLULAR)
                        .addCapability(NET_CAPABILITY_TRUSTED)
                        .addCapability(NET_CAPABILITY_NOT_RESTRICTED)
                        .addCapability(NET_CAPABILITY_NOT_VPN)
                        .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED);

        for (int cap : VcnGatewayConnectionConfig.ALLOWED_CAPABILITIES) {
            builder.addCapability(cap);
        }

        return builder.build();
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
        for (NetworkRequest request : mRequests) {
            notifyListenerForEvent(listener, request);
        }
    }

    private void notifyListenerForEvent(
            @NonNull NetworkRequestListener listener, @NonNull NetworkRequest request) {
        listener.onNetworkRequested(request);
    }

    private void handleNetworkRequested(@NonNull NetworkRequest request) {
        if (VDBG) {
            Slog.v(TAG, "Network requested: Request = " + request);
        }

        mRequests.add(request);

        // TODO(b/176939047): Intelligently route requests to prioritized VcnInstances (based on
        // Default Data Sub, or similar)
        for (NetworkRequestListener listener : mListeners) {
            notifyListenerForEvent(listener, request);
        }
    }

    private void handleNetworkRequestWithdrawn(@NonNull NetworkRequest request) {
        if (VDBG) {
            Slog.v(TAG, "Network request withdrawn: Request = " + request);
        }

        mRequests.remove(request);
    }

    // package-private
    interface NetworkRequestListener {
        void onNetworkRequested(@NonNull NetworkRequest request);
    }

    /**
     * Dumps the state of this VcnNetworkProvider for logging and debugging purposes.
     *
     * <p>PII and credentials MUST NEVER be dumped here.
     */
    public void dump(IndentingPrintWriter pw) {
        pw.println("VcnNetworkProvider:");
        pw.increaseIndent();

        pw.println("mListeners:");
        pw.increaseIndent();
        for (NetworkRequestListener listener : mListeners) {
            pw.println(listener);
        }
        pw.decreaseIndent();
        pw.println();

        pw.println("mRequests:");
        pw.increaseIndent();
        for (NetworkRequest request : mRequests) {
            pw.println(request);
        }
        pw.decreaseIndent();
        pw.println();

        pw.decreaseIndent();
    }

    /** Proxy class for dependencies used for testing. */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public static class Dependencies {
        /** Registers a given network offer for the given provider. */
        public void registerNetworkOffer(
                @NonNull VcnNetworkProvider provider,
                @NonNull NetworkScore score,
                @NonNull NetworkCapabilities capabilitiesFilter,
                @NonNull Executor executor,
                @NonNull NetworkOfferCallback callback) {
            provider.registerNetworkOffer(score, capabilitiesFilter, executor, callback);
        }
    }
}
