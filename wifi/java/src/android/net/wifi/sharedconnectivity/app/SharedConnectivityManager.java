/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.net.wifi.sharedconnectivity.app;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.net.wifi.sharedconnectivity.service.ISharedConnectivityCallback;
import android.net.wifi.sharedconnectivity.service.ISharedConnectivityService;
import android.net.wifi.sharedconnectivity.service.SharedConnectivityService;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.R;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * This class is the library used by consumers of Shared Connectivity data to bind to the service,
 * receive callbacks from, and send user actions to the service.
 *
 * The methods {@link #connectTetherNetwork}, {@link #disconnectTetherNetwork},
 * {@link #connectKnownNetwork} and {@link #forgetKnownNetwork} are not valid and will return false
 * if not called between {@link SharedConnectivityClientCallback#onServiceConnected()}
 * and {@link SharedConnectivityClientCallback#onServiceDisconnected()} or if
 * {@link SharedConnectivityClientCallback#onRegisterCallbackFailed} was called.
 *
 * @hide
 */
@SystemApi
public class SharedConnectivityManager {
    private static final String TAG = SharedConnectivityManager.class.getSimpleName();
    private static final boolean DEBUG = true;

    private static final class SharedConnectivityCallbackProxy extends
            ISharedConnectivityCallback.Stub {
        private final Executor mExecutor;
        private final SharedConnectivityClientCallback mCallback;

        SharedConnectivityCallbackProxy(
                @NonNull @CallbackExecutor Executor executor,
                @NonNull SharedConnectivityClientCallback callback) {
            mExecutor = executor;
            mCallback = callback;
        }

        @Override
        public void onTetherNetworksUpdated(@NonNull List<TetherNetwork> networks) {
            if (mCallback != null) {
                final long token = Binder.clearCallingIdentity();
                try {
                    mExecutor.execute(() -> mCallback.onTetherNetworksUpdated(networks));
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        }

        @Override
        public void onKnownNetworksUpdated(@NonNull List<KnownNetwork> networks) {
            if (mCallback != null) {
                final long token = Binder.clearCallingIdentity();
                try {
                    mExecutor.execute(() -> mCallback.onKnownNetworksUpdated(networks));
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        }

        @Override
        public void onSharedConnectivitySettingsChanged(
                @NonNull SharedConnectivitySettingsState state) {
            if (mCallback != null) {
                final long token = Binder.clearCallingIdentity();
                try {
                    mExecutor.execute(() -> mCallback.onSharedConnectivitySettingsChanged(state));
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        }

        @Override
        public void onTetherNetworkConnectionStatusChanged(
                @NonNull TetherNetworkConnectionStatus status) {
            if (mCallback != null) {
                final long token = Binder.clearCallingIdentity();
                try {
                    mExecutor.execute(() ->
                            mCallback.onTetherNetworkConnectionStatusChanged(status));
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        }

        @Override
        public void onKnownNetworkConnectionStatusChanged(
                @NonNull KnownNetworkConnectionStatus status) {
            if (mCallback != null) {
                final long token = Binder.clearCallingIdentity();
                try {
                    mExecutor.execute(() ->
                            mCallback.onKnownNetworkConnectionStatusChanged(status));
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        }
    }

    private ISharedConnectivityService mService;
    private final Map<SharedConnectivityClientCallback, SharedConnectivityCallbackProxy>
            mProxyMap = new HashMap<>();
    private final Map<SharedConnectivityClientCallback, SharedConnectivityCallbackProxy>
            mCallbackProxyCache = new HashMap<>();
    // Used for testing
    private final ServiceConnection mServiceConnection;

    /**
     * Creates a new instance of {@link SharedConnectivityManager}.
     *
     * Automatically binds to implementation of {@link SharedConnectivityService} specified in
     * device overlay.
     *
     * @return An instance of {@link SharedConnectivityManager} or null if the shared connectivity
     * service is not found.
     * @hide
     */
    @Nullable
    public static SharedConnectivityManager create(@NonNull Context context) {
        Resources resources = context.getResources();
        try {
            String servicePackageName = resources.getString(
                    R.string.shared_connectivity_service_package);
            String serviceIntentAction = resources.getString(
                    R.string.shared_connectivity_service_intent_action);
            return new SharedConnectivityManager(context, servicePackageName, serviceIntentAction);
        } catch (Resources.NotFoundException e) {
            Log.e(TAG, "To support shared connectivity service on this device, the service's"
                    + " package name and intent action string must be defined");
        }
        return null;
    }

    /**
     * @hide
     */
    @SuppressLint("ManagerLookup")
    @TestApi
    @Nullable
    public static SharedConnectivityManager create(@NonNull Context context,
            @NonNull String servicePackageName, @NonNull String serviceIntentAction) {
        return new SharedConnectivityManager(context, servicePackageName, serviceIntentAction);
    }

    private SharedConnectivityManager(@NonNull Context context, String servicePackageName,
            String serviceIntentAction) {
        mServiceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                mService = ISharedConnectivityService.Stub.asInterface(service);
                if (!mCallbackProxyCache.isEmpty()) {
                    synchronized (mCallbackProxyCache) {
                        mCallbackProxyCache.keySet().forEach(callback -> {
                            registerCallbackInternal(callback, mCallbackProxyCache.get(callback));
                        });
                        mCallbackProxyCache.clear();
                    }
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                if (DEBUG) Log.i(TAG, "onServiceDisconnected");
                mService = null;
                if (!mCallbackProxyCache.isEmpty()) {
                    synchronized (mCallbackProxyCache) {
                        mCallbackProxyCache.keySet().forEach(
                                SharedConnectivityClientCallback::onServiceDisconnected);
                        mCallbackProxyCache.clear();
                    }
                }
                if (!mProxyMap.isEmpty()) {
                    synchronized (mProxyMap) {
                        mProxyMap.keySet().forEach(
                                SharedConnectivityClientCallback::onServiceDisconnected);
                        mProxyMap.clear();
                    }
                }
            }
        };

        context.bindService(
                new Intent().setPackage(servicePackageName).setAction(serviceIntentAction),
                mServiceConnection, Context.BIND_AUTO_CREATE);
    }

    private void registerCallbackInternal(SharedConnectivityClientCallback callback,
            SharedConnectivityCallbackProxy proxy) {
        try {
            mService.registerCallback(proxy);
            synchronized (mProxyMap) {
                mProxyMap.put(callback, proxy);
            }
            callback.onServiceConnected();
        } catch (RemoteException e) {
            Log.e(TAG, "Exception in registerCallback", e);
            callback.onRegisterCallbackFailed(e);
        }
    }

    /**
     * @hide
     */
    @TestApi
    public void setService(@Nullable IInterface service) {
        mService = (ISharedConnectivityService) service;
    }

    /**
     * @hide
     */
    @TestApi
    @Nullable
    public ServiceConnection getServiceConnection() {
        return mServiceConnection;
    }

    /**
     * Registers a callback for receiving updates to the list of Tether Networks and Known Networks.
     * The {@link SharedConnectivityClientCallback#onRegisterCallbackFailed} will be called if the
     * registration failed.
     *
     * @param executor The Executor used to invoke the callback.
     * @param callback The callback of type {@link SharedConnectivityClientCallback} that is invoked
     *                 when the service updates either the list of Tether Networks or Known
     *                 Networks.
     */
    public void registerCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull SharedConnectivityClientCallback callback) {
        Objects.requireNonNull(executor, "executor cannot be null");
        Objects.requireNonNull(callback, "callback cannot be null");

        if (mProxyMap.containsKey(callback) || mCallbackProxyCache.containsKey(callback)) {
            Log.e(TAG, "Callback already registered");
            callback.onRegisterCallbackFailed(new IllegalStateException(
                    "Callback already registered"));
            return;
        }

        SharedConnectivityCallbackProxy proxy =
                new SharedConnectivityCallbackProxy(executor, callback);
        if (mService == null) {
            synchronized (mCallbackProxyCache) {
                mCallbackProxyCache.put(callback, proxy);
            }
            return;
        }
        registerCallbackInternal(callback, proxy);
    }

    /**
     * Unregisters a callback.
     *
     * @return Returns true if the callback was successfully unregistered, false otherwise.
     */
    public boolean unregisterCallback(
            @NonNull SharedConnectivityClientCallback callback) {
        Objects.requireNonNull(callback, "callback cannot be null");

        if (!mProxyMap.containsKey(callback) && !mCallbackProxyCache.containsKey(callback)) {
            Log.e(TAG, "Callback not found, cannot unregister");
            return false;
        }

        if (mService == null) {
            synchronized (mCallbackProxyCache) {
                mCallbackProxyCache.remove(callback);
            }
            return true;
        }

        try {
            mService.unregisterCallback(mProxyMap.get(callback));
            synchronized (mProxyMap) {
                mProxyMap.remove(callback);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Exception in unregisterCallback", e);
            return false;
        }
        return true;
    }

     /**
     * Send command to the implementation of {@link SharedConnectivityService} requesting connection
     * to the specified Tether Network.
     *
     * @param network {@link TetherNetwork} object representing the network the user has requested
     *                a connection to.
     * @return Returns true if the service received the command. Does not guarantee that the
     *         connection was successful.
     */
    public boolean connectTetherNetwork(@NonNull TetherNetwork network) {
        Objects.requireNonNull(network, "Tether network cannot be null");

        if (mService == null) {
            return false;
        }

        try {
            mService.connectTetherNetwork(network);
        } catch (RemoteException e) {
            Log.e(TAG, "Exception in connectTetherNetwork", e);
            return false;
        }
        return true;
    }

    /**
     * Send command to the implementation of {@link SharedConnectivityService} requesting
     * disconnection from the active Tether Network.
     *
     * @param network {@link TetherNetwork} object representing the network the user has requested
     *                to disconnect from.
     * @return Returns true if the service received the command. Does not guarantee that the
     *         disconnection was successful.
     */
    public boolean disconnectTetherNetwork(@NonNull TetherNetwork network) {
        if (mService == null) {
            return false;
        }

        try {
            mService.disconnectTetherNetwork(network);
        } catch (RemoteException e) {
            Log.e(TAG, "Exception in disconnectTetherNetwork", e);
            return false;
        }
        return true;
    }

    /**
     * Send command to the implementation of {@link SharedConnectivityService} requesting connection
     * to the specified Known Network.
     *
     * @param network {@link KnownNetwork} object representing the network the user has requested
     *                a connection to.
     * @return Returns true if the service received the command. Does not guarantee that the
     *         connection was successful.
     */
    public boolean connectKnownNetwork(@NonNull KnownNetwork network) {
        Objects.requireNonNull(network, "Known network cannot be null");

        if (mService == null) {
            return false;
        }

        try {
            mService.connectKnownNetwork(network);
        } catch (RemoteException e) {
            Log.e(TAG, "Exception in connectKnownNetwork", e);
            return false;
        }
        return true;
    }

    /**
     * Send command to the implementation of {@link SharedConnectivityService} requesting removal of
     * the specified Known Network from the list of Known Networks.
     *
     * @return Returns true if the service received the command. Does not guarantee that the
     *         forget action was successful.
     */
    public boolean forgetKnownNetwork(@NonNull KnownNetwork network) {
        Objects.requireNonNull(network, "Known network cannot be null");

        if (mService == null) {
            return false;
        }

        try {
            mService.forgetKnownNetwork(network);
        } catch (RemoteException e) {
            Log.e(TAG, "Exception in forgetKnownNetwork", e);
            return false;
        }
        return true;
    }
}
