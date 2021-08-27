/*
 * Copyright (C) 2021 The Android Open Source Project
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
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.os.Binder;
import android.os.RemoteException;

import com.android.internal.annotations.GuardedBy;

import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
@SystemService(Context.PAC_PROXY_SERVICE)
public class PacProxyManager {
    private final Context mContext;
    private final IPacProxyManager mService;
    @GuardedBy("mListenerMap")
    private final HashMap<PacProxyInstalledListener, PacProxyInstalledListenerProxy>
            mListenerMap = new HashMap<>();

    /** @hide */
    public PacProxyManager(Context context, IPacProxyManager service) {
        Objects.requireNonNull(service, "missing IPacProxyManager");
        mContext = context;
        mService = service;
    }

    /**
     * Add a listener to start monitoring events reported by PacProxyService.
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.NETWORK_STACK,
            NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK,
            android.Manifest.permission.NETWORK_SETTINGS})
    public void addPacProxyInstalledListener(@NonNull Executor executor,
            @NonNull PacProxyInstalledListener listener) {
        try {
            synchronized (mListenerMap) {
                final PacProxyInstalledListenerProxy listenerProxy =
                        new PacProxyInstalledListenerProxy(executor, listener);

                if (null != mListenerMap.putIfAbsent(listener, listenerProxy)) {
                    throw new IllegalStateException("Listener is already added.");
                }
                mService.addListener(listenerProxy);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Remove the listener to stop monitoring the event of PacProxyInstalledListener.
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.NETWORK_STACK,
            NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK,
            android.Manifest.permission.NETWORK_SETTINGS})
    public void removePacProxyInstalledListener(@NonNull PacProxyInstalledListener listener) {
        try {
            synchronized (mListenerMap) {
                final PacProxyInstalledListenerProxy listenerProxy = mListenerMap.remove(listener);
                if (listenerProxy == null) return;
                mService.removeListener(listenerProxy);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Updates the PAC Proxy Service with current Proxy information.
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.NETWORK_STACK,
            NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK,
            android.Manifest.permission.NETWORK_SETTINGS})
    public void setCurrentProxyScriptUrl(@Nullable ProxyInfo proxy) {
        try {
            mService.setCurrentProxyScriptUrl(proxy);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * A callback interface for monitoring changes of PAC proxy information.
     */
    public interface PacProxyInstalledListener {
        /**
         * Notify that the PAC proxy has been installed. Note that this method will be called with
         * a ProxyInfo with an empty PAC URL when the PAC proxy is removed.
         *
         * This method supports different PAC proxies per-network but not all devices might support
         * per-network proxies. In that case it will be applied globally.
         *
         * @param network the network for which this proxy installed.
         * @param proxy the installed proxy.
         */
        void onPacProxyInstalled(@Nullable Network network, @NonNull ProxyInfo proxy);
    }

    /**
     * PacProxyInstalledListener proxy for PacProxyInstalledListener object.
     * @hide
     */
    public class PacProxyInstalledListenerProxy extends IPacProxyInstalledListener.Stub {
        private final Executor mExecutor;
        private final PacProxyInstalledListener mListener;

        PacProxyInstalledListenerProxy(Executor executor, PacProxyInstalledListener listener) {
            mExecutor = executor;
            mListener = listener;
        }

        @Override
        public void onPacProxyInstalled(Network network, ProxyInfo proxy) {
            Binder.withCleanCallingIdentity(() -> {
                mExecutor.execute(() -> {
                    mListener.onPacProxyInstalled(network, proxy);
                });
            });
        }
    }
}
