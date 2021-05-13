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

package android.net;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.concurrent.Executor;

/**
 * Base class for network providers such as telephony or Wi-Fi. NetworkProviders connect the device
 * to networks and makes them available to the core network stack by creating
 * {@link NetworkAgent}s. The networks can then provide connectivity to apps and can be interacted
 * with via networking APIs such as {@link ConnectivityManager}.
 *
 * Subclasses should implement {@link #onNetworkRequested} and {@link #onNetworkRequestWithdrawn}
 * to receive {@link NetworkRequest}s sent by the system and by apps. A network that is not the
 * best (highest-scoring) network for any request is generally not used by the system, and torn
 * down.
 *
 * @hide
 */
@SystemApi
public class NetworkProvider {
    /**
     * {@code providerId} value that indicates the absence of a provider. It is the providerId of
     * any NetworkProvider that is not currently registered, and of any NetworkRequest that is not
     * currently being satisfied by a network.
     */
    public static final int ID_NONE = -1;

    /**
     * The first providerId value that will be allocated.
     * @hide only used by ConnectivityService.
     */
    public static final int FIRST_PROVIDER_ID = 1;

    /** @hide only used by ConnectivityService */
    public static final int CMD_REQUEST_NETWORK = 1;
    /** @hide only used by ConnectivityService */
    public static final int CMD_CANCEL_REQUEST = 2;

    private final Messenger mMessenger;
    private final String mName;
    private final Context mContext;

    private int mProviderId = ID_NONE;

    /**
     * Constructs a new NetworkProvider.
     *
     * @param looper the Looper on which to run {@link #onNetworkRequested} and
     *               {@link #onNetworkRequestWithdrawn}.
     * @param name the name of the listener, used only for debugging.
     *
     * @hide
     */
    @SystemApi
    public NetworkProvider(@NonNull Context context, @NonNull Looper looper, @NonNull String name) {
        // TODO (b/174636568) : this class should be able to cache an instance of
        // ConnectivityManager so it doesn't have to fetch it again every time.
        final Handler handler = new Handler(looper) {
            @Override
            public void handleMessage(Message m) {
                switch (m.what) {
                    case CMD_REQUEST_NETWORK:
                        onNetworkRequested((NetworkRequest) m.obj, m.arg1, m.arg2);
                        break;
                    case CMD_CANCEL_REQUEST:
                        onNetworkRequestWithdrawn((NetworkRequest) m.obj);
                        break;
                    default:
                        Log.e(mName, "Unhandled message: " + m.what);
                }
            }
        };
        mContext = context;
        mMessenger = new Messenger(handler);
        mName = name;
    }

    // TODO: consider adding a register() method so ConnectivityManager does not need to call this.
    /** @hide */
    public @Nullable Messenger getMessenger() {
        return mMessenger;
    }

    /** @hide */
    public @NonNull String getName() {
        return mName;
    }

    /**
     * Returns the ID of this provider. This is known only once the provider is registered via
     * {@link ConnectivityManager#registerNetworkProvider()}, otherwise the ID is {@link #ID_NONE}.
     * This ID must be used when registering any {@link NetworkAgent}s.
     */
    public int getProviderId() {
        return mProviderId;
    }

    /** @hide */
    public void setProviderId(int providerId) {
        mProviderId = providerId;
    }

    /**
     *  Called when a NetworkRequest is received. The request may be a new request or an existing
     *  request with a different score.
     *
     * @param request the NetworkRequest being received
     * @param score the score of the network currently satisfying the request, or 0 if none.
     * @param providerId the ID of the provider that created the network currently satisfying this
     *                   request, or {@link #ID_NONE} if none.
     *
     *  @hide
     */
    @SystemApi
    public void onNetworkRequested(@NonNull NetworkRequest request,
            @IntRange(from = 0, to = 99) int score, int providerId) {}

    /**
     *  Called when a NetworkRequest is withdrawn.
     *  @hide
     */
    @SystemApi
    public void onNetworkRequestWithdrawn(@NonNull NetworkRequest request) {}

    /**
     * Asserts that no provider will ever be able to satisfy the specified request. The provider
     * must only call this method if it knows that it is the only provider on the system capable of
     * satisfying this request, and that the request cannot be satisfied. The application filing the
     * request will receive an {@link NetworkCallback#onUnavailable()} callback.
     *
     * @param request the request that permanently cannot be fulfilled
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.NETWORK_FACTORY)
    public void declareNetworkRequestUnfulfillable(@NonNull NetworkRequest request) {
        ConnectivityManager.from(mContext).declareNetworkRequestUnfulfillable(request);
    }

    /** @hide */
    // TODO : make @SystemApi when the impl is complete
    public interface NetworkOfferCallback {
        /** Called by the system when this offer is needed to satisfy some networking request. */
        void onOfferNeeded(@NonNull NetworkRequest request, int providerId);
        /** Called by the system when this offer is no longer needed. */
        void onOfferUnneeded(@NonNull NetworkRequest request);
    }

    private class NetworkOfferCallbackProxy extends INetworkOfferCallback.Stub {
        @NonNull public final NetworkOfferCallback callback;
        @NonNull private final Executor mExecutor;

        NetworkOfferCallbackProxy(@NonNull final NetworkOfferCallback callback,
                @NonNull final Executor executor) {
            this.callback = callback;
            this.mExecutor = executor;
        }

        @Override
        public void onOfferNeeded(final @NonNull NetworkRequest request,
                final int providerId) {
            mExecutor.execute(() -> callback.onOfferNeeded(request, providerId));
        }

        @Override
        public void onOfferUnneeded(final @NonNull NetworkRequest request) {
            mExecutor.execute(() -> callback.onOfferUnneeded(request));
        }
    }

    @GuardedBy("mProxies")
    @NonNull private final ArrayList<NetworkOfferCallbackProxy> mProxies = new ArrayList<>();

    // Returns the proxy associated with this callback, or null if none.
    @Nullable
    private NetworkOfferCallbackProxy findProxyForCallback(@NonNull final NetworkOfferCallback cb) {
        synchronized (mProxies) {
            for (final NetworkOfferCallbackProxy p : mProxies) {
                if (p.callback == cb) return p;
            }
        }
        return null;
    }

    /**
     * Register or update an offer for network with the passed caps and score.
     *
     * A NetworkProvider's job is to provide networks. This function is how a provider tells the
     * connectivity stack what kind of network it may provide. The score and caps arguments act
     * as filters that the connectivity stack uses to tell when the offer is necessary. When an
     * offer might be advantageous over existing networks, the provider will receive a call to
     * the associated callback's {@link NetworkOfferCallback#onOfferNeeded} method. The provider
     * should then try to bring up this network. When an offer is no longer needed, the stack
     * will inform the provider by calling {@link NetworkOfferCallback#onOfferUnneeded}. The
     * provider should stop trying to bring up such a network, or disconnect it if it already has
     * one.
     *
     * The stack determines what offers are needed according to what networks are currently
     * available to the system, and what networking requests are made by applications. If an
     * offer looks like it could be a better choice than any existing network for any particular
     * request, that's when the stack decides the offer is needed. If the current networking
     * requests are all satisfied by networks that this offer can't possibly be a better match
     * for, that's when the offer is unneeded. An offer starts off as unneeded ; the provider
     * should not try to bring up the network until {@link NetworkOfferCallback#onOfferNeeded}
     * is called.
     *
     * Note that the offers are non-binding to the providers, in particular because providers
     * often don't know if they will be able to bring up such a network at any given time. For
     * example, no wireless network may be in range when the offer is needed. This is fine and
     * expected ; the provider should simply continue to try to bring up the network and do so
     * if/when it becomes possible. In the mean time, the stack will continue to satisfy requests
     * with the best network currently available, or if none, keep the apps informed that no
     * network can currently satisfy this request. When/if the provider can bring up the network,
     * the connectivity stack will match it against requests, and inform interested apps of the
     * availability of this network. This may, in turn, render the offer of some other provider
     * unneeded if all requests it used to satisfy are now better served by this network.
     *
     * A network can become unneeded for a reason like the above : whether the provider managed
     * to bring up the offered network after it became needed or not, some other provider may
     * bring up a better network than this one, making this offer unneeded. A network may also
     * become unneeded if the application making the request withdrew it (for example, after it
     * is done transferring data, or if the user canceled an operation).
     *
     * The capabilities and score act as filters as to what requests the provider will see.
     * They are not promises, but for best performance, the providers should strive to put
     * as much known information as possible in the offer. For capabilities in particular, it
     * should put all NetworkAgent-managed capabilities a network may have, even if it doesn't
     * have them at first. This applies to INTERNET, for example ; if a provider thinks the
     * network it can bring up for this offer may offer Internet access it should include the
     * INTERNET bit. It's fine if the brought up network ends up not actually having INTERNET.
     *
     * TODO : in the future, to avoid possible infinite loops, there should be constraints on
     * what can be put in capabilities of networks brought up for an offer. If a provider might
     * bring up a network with or without INTERNET, then it should file two offers : this will
     * let it know precisely what networks are needed, so it can avoid bringing up networks that
     * won't actually satisfy requests and remove the risk for bring-up-bring-down loops.
     *
     * @hide
     */
    // TODO : make @SystemApi when the impl is complete
    @RequiresPermission(android.Manifest.permission.NETWORK_FACTORY)
    public void offerNetwork(@NonNull final NetworkScore score,
            @NonNull final NetworkCapabilities caps, @NonNull final Executor executor,
            @NonNull final NetworkOfferCallback callback) {
        NetworkOfferCallbackProxy proxy = null;
        synchronized (mProxies) {
            for (final NetworkOfferCallbackProxy existingProxy : mProxies) {
                if (existingProxy.callback == callback) {
                    proxy = existingProxy;
                    break;
                }
            }
            if (null == proxy) {
                proxy = new NetworkOfferCallbackProxy(callback, executor);
                mProxies.add(proxy);
            }
        }
        mContext.getSystemService(ConnectivityManager.class).offerNetwork(this, score, caps, proxy);
    }

    /**
     * Withdraw a network offer previously made to the networking stack.
     *
     * If a provider can no longer provide a network they offered, it should call this method.
     * An example of usage could be if the hardware necessary to bring up the network was turned
     * off in UI by the user. Note that because offers are never binding, the provider might
     * alternatively decide not to withdraw this offer and simply refuse to bring up the network
     * even when it's needed. However, withdrawing the request is slightly more resource-efficient
     * because the networking stack won't have to compare this offer to exiting networks to see
     * if it could beat any of them, and may be advantageous to the provider's implementation that
     * can rely on no longer receiving callbacks for a network that they can't bring up anyways.
     *
     * @hide
     */
    // TODO : make @SystemApi when the impl is complete
    @RequiresPermission(android.Manifest.permission.NETWORK_FACTORY)
    public void unofferNetwork(final @NonNull NetworkOfferCallback callback) {
        final NetworkOfferCallbackProxy proxy = findProxyForCallback(callback);
        if (null == proxy) return;
        mProxies.remove(proxy);
        mContext.getSystemService(ConnectivityManager.class).unofferNetwork(proxy);
    }
}
