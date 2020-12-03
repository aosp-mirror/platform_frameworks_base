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
 * limitations under the License.
 */

package android.net;

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.os.Build;
import android.os.RemoteException;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;

import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * A class representing the IP configuration of the Ethernet network.
 *
 * @hide
 */
@SystemApi
@SystemService(Context.ETHERNET_SERVICE)
public class EthernetManager {
    private static final String TAG = "EthernetManager";

    private final IEthernetManager mService;
    @GuardedBy("mListeners")
    private final ArrayList<ListenerInfo> mListeners = new ArrayList<>();
    private final IEthernetServiceListener.Stub mServiceListener =
            new IEthernetServiceListener.Stub() {
                @Override
                public void onAvailabilityChanged(String iface, boolean isAvailable) {
                    synchronized (mListeners) {
                        for (ListenerInfo li : mListeners) {
                            li.executor.execute(() ->
                                    li.listener.onAvailabilityChanged(iface, isAvailable));
                        }
                    }
                }
            };

    private static class ListenerInfo {
        @NonNull
        public final Executor executor;
        @NonNull
        public final Listener listener;

        private ListenerInfo(@NonNull Executor executor, @NonNull Listener listener) {
            this.executor = executor;
            this.listener = listener;
        }
    }

    /**
     * A listener interface to receive notification on changes in Ethernet.
     * @hide
     */
    public interface Listener {
        /**
         * Called when Ethernet port's availability is changed.
         * @param iface Ethernet interface name
         * @param isAvailable {@code true} if Ethernet port exists.
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        void onAvailabilityChanged(String iface, boolean isAvailable);
    }

    /**
     * Create a new EthernetManager instance.
     * Applications will almost always want to use
     * {@link android.content.Context#getSystemService Context.getSystemService()} to retrieve
     * the standard {@link android.content.Context#ETHERNET_SERVICE Context.ETHERNET_SERVICE}.
     * @hide
     */
    public EthernetManager(Context context, IEthernetManager service) {
        mService = service;
    }

    /**
     * Get Ethernet configuration.
     * @return the Ethernet Configuration, contained in {@link IpConfiguration}.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public IpConfiguration getConfiguration(String iface) {
        try {
            return mService.getConfiguration(iface);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set Ethernet configuration.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setConfiguration(String iface, IpConfiguration config) {
        try {
            mService.setConfiguration(iface, config);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Indicates whether the system currently has one or more Ethernet interfaces.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean isAvailable() {
        return getAvailableInterfaces().length > 0;
    }

    /**
     * Indicates whether the system has given interface.
     *
     * @param iface Ethernet interface name
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean isAvailable(String iface) {
        try {
            return mService.isAvailable(iface);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Adds a listener.
     *
     * Consider using {@link #addListener(Listener, Executor)} instead: this method uses a default
     * executor that may have higher latency than a provided executor.
     * @param listener A {@link Listener} to add.
     * @throws IllegalArgumentException If the listener is null.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void addListener(@NonNull Listener listener) {
        addListener(listener, BackgroundThread.getExecutor());
    }

    /**
     * Adds a listener.
     * @param listener A {@link Listener} to add.
     * @param executor Executor to run callbacks on.
     * @throws IllegalArgumentException If the listener or executor is null.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void addListener(@NonNull Listener listener, @NonNull Executor executor) {
        if (listener == null || executor == null) {
            throw new NullPointerException("listener and executor must not be null");
        }
        synchronized (mListeners) {
            mListeners.add(new ListenerInfo(executor, listener));
            if (mListeners.size() == 1) {
                try {
                    mService.addListener(mServiceListener);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }

    /**
     * Returns an array of available Ethernet interface names.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public String[] getAvailableInterfaces() {
        try {
            return mService.getAvailableInterfaces();
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Removes a listener.
     * @param listener A {@link Listener} to remove.
     * @throws IllegalArgumentException If the listener is null.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void removeListener(@NonNull Listener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        synchronized (mListeners) {
            mListeners.removeIf(l -> l.listener == listener);
            if (mListeners.isEmpty()) {
                try {
                    mService.removeListener(mServiceListener);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }

    /**
     * Whether to treat interfaces created by {@link TestNetworkManager#createTapInterface}
     * as Ethernet interfaces. The effects of this method apply to any test interfaces that are
     * already present on the system.
     * @hide
     */
    @TestApi
    public void setIncludeTestInterfaces(boolean include) {
        try {
            mService.setIncludeTestInterfaces(include);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * A request for a tethered interface.
     */
    public static class TetheredInterfaceRequest {
        private final IEthernetManager mService;
        private final ITetheredInterfaceCallback mCb;

        private TetheredInterfaceRequest(@NonNull IEthernetManager service,
                @NonNull ITetheredInterfaceCallback cb) {
            this.mService = service;
            this.mCb = cb;
        }

        /**
         * Release the request, causing the interface to revert back from tethering mode if there
         * is no other requestor.
         */
        public void release() {
            try {
                mService.releaseTetheredInterface(mCb);
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Callback for {@link #requestTetheredInterface(TetheredInterfaceCallback)}.
     */
    public interface TetheredInterfaceCallback {
        /**
         * Called when the tethered interface is available.
         * @param iface The name of the interface.
         */
        void onAvailable(@NonNull String iface);

        /**
         * Called when the tethered interface is now unavailable.
         */
        void onUnavailable();
    }

    /**
     * Request a tethered interface in tethering mode.
     *
     * <p>When this method is called and there is at least one ethernet interface available, the
     * system will designate one to act as a tethered interface. If there is already a tethered
     * interface, the existing interface will be used.
     * @param callback A callback to be called once the request has been fulfilled.
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.NETWORK_STACK,
            android.net.NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK
    })
    @NonNull
    public TetheredInterfaceRequest requestTetheredInterface(@NonNull final Executor executor,
            @NonNull final TetheredInterfaceCallback callback) {
        Objects.requireNonNull(callback, "Callback must be non-null");
        Objects.requireNonNull(executor, "Executor must be non-null");
        final ITetheredInterfaceCallback cbInternal = new ITetheredInterfaceCallback.Stub() {
            @Override
            public void onAvailable(String iface) {
                executor.execute(() -> callback.onAvailable(iface));
            }

            @Override
            public void onUnavailable() {
                executor.execute(() -> callback.onUnavailable());
            }
        };

        try {
            mService.requestTetheredInterface(cbInternal);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return new TetheredInterfaceRequest(mService, cbInternal);
    }
}
