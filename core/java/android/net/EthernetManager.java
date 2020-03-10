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
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * A class representing the IP configuration of the Ethernet network.
 *
 * @hide
 */
@SystemApi
@TestApi
@SystemService(Context.ETHERNET_SERVICE)
public class EthernetManager {
    private static final String TAG = "EthernetManager";
    private static final int MSG_AVAILABILITY_CHANGED = 1000;

    private final Context mContext;
    private final IEthernetManager mService;
    private final Handler mHandler = new Handler(ConnectivityThread.getInstanceLooper()) {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_AVAILABILITY_CHANGED) {
                boolean isAvailable = (msg.arg1 == 1);
                for (Listener listener : mListeners) {
                    listener.onAvailabilityChanged((String) msg.obj, isAvailable);
                }
            }
        }
    };
    private final ArrayList<Listener> mListeners = new ArrayList<>();
    private final IEthernetServiceListener.Stub mServiceListener =
            new IEthernetServiceListener.Stub() {
                @Override
                public void onAvailabilityChanged(String iface, boolean isAvailable) {
                    mHandler.obtainMessage(
                            MSG_AVAILABILITY_CHANGED, isAvailable ? 1 : 0, 0, iface).sendToTarget();
                }
            };

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
        @UnsupportedAppUsage
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
        mContext = context;
        mService = service;
    }

    /**
     * Get Ethernet configuration.
     * @return the Ethernet Configuration, contained in {@link IpConfiguration}.
     * @hide
     */
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
    public boolean isAvailable() {
        return getAvailableInterfaces().length > 0;
    }

    /**
     * Indicates whether the system has given interface.
     *
     * @param iface Ethernet interface name
     * @hide
     */
    @UnsupportedAppUsage
    public boolean isAvailable(String iface) {
        try {
            return mService.isAvailable(iface);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Adds a listener.
     * @param listener A {@link Listener} to add.
     * @throws IllegalArgumentException If the listener is null.
     * @hide
     */
    @UnsupportedAppUsage
    public void addListener(Listener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        mListeners.add(listener);
        if (mListeners.size() == 1) {
            try {
                mService.addListener(mServiceListener);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns an array of available Ethernet interface names.
     * @hide
     */
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
    public void removeListener(Listener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        mListeners.remove(listener);
        if (mListeners.isEmpty()) {
            try {
                mService.removeListener(mServiceListener);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
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
