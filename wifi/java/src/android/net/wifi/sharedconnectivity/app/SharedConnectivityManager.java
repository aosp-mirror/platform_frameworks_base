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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * This class is the library used by consumers of Shared Connectivity data to bind to the service,
 * receive callbacks from, and send user actions to the service.
 *
 * @hide
 */
@SystemApi
public class SharedConnectivityManager {
    private static final String TAG = SharedConnectivityManager.class.getSimpleName();
    private static final boolean DEBUG = true;
    private static final String SERVICE_PACKAGE_NAME = "sharedconnectivity_service_package";
    private static final String SERVICE_CLASS_NAME = "sharedconnectivity_service_class";

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
        };

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
        };

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
        };
    }

    private ISharedConnectivityService mService;
    private final Map<SharedConnectivityClientCallback, SharedConnectivityCallbackProxy>
            mProxyMap = new HashMap<>();

    /**
     * Constructor for new instance of {@link SharedConnectivityManager}.
     *
     * Automatically binds to implementation of {@link SharedConnectivityService} specified in
     * device overlay.
     */
    @SuppressLint("ManagerConstructor")
    public SharedConnectivityManager(@NonNull Context context) {
        ServiceConnection serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                mService = ISharedConnectivityService.Stub.asInterface(service);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                if (DEBUG) Log.i(TAG, "onServiceDisconnected");
                mService = null;
                mProxyMap.clear();
            }
        };
        bind(context, serviceConnection);
    }

    /**
     * @hide
     */
    @TestApi
    public void setService(@Nullable IInterface service) {
        mService = (ISharedConnectivityService) service;
    }

    private void bind(Context context, ServiceConnection serviceConnection) {
        Resources resources = context.getResources();
        int packageNameId = resources.getIdentifier(SERVICE_PACKAGE_NAME, "string",
                context.getPackageName());
        int classNameId = resources.getIdentifier(SERVICE_CLASS_NAME, "string",
                context.getPackageName());
        if (packageNameId == 0 || classNameId == 0) {
            throw new Resources.NotFoundException("Package and class names for"
                    + " shared connectivity service must be defined");
        }

        Intent intent = new Intent();
        intent.setComponent(new ComponentName(resources.getString(packageNameId),
                resources.getString(classNameId)));
        context.bindService(
                intent,
                serviceConnection, Context.BIND_AUTO_CREATE);
    }

    /**
     * Registers a callback for receiving updates to the list of Tether Networks and Known Networks.
     *
     * @param executor The Executor used to invoke the callback.
     * @param callback The callback of type {@link SharedConnectivityClientCallback} that is invoked
     *                 when the service updates either the list of Tether Networks or Known
     *                 Networks.
     * @return Returns true if the registration was successful, false otherwise.
     */
    public boolean registerCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull SharedConnectivityClientCallback callback) {
        Objects.requireNonNull(executor, "executor cannot be null");
        Objects.requireNonNull(callback, "callback cannot be null");
        if (mService == null || mProxyMap.containsKey(callback)) return false;
        try {
            SharedConnectivityCallbackProxy proxy =
                    new SharedConnectivityCallbackProxy(executor, callback);
            mService.registerCallback(proxy);
            mProxyMap.put(callback, proxy);
        } catch (RemoteException e) {
            Log.e(TAG, "Exception in registerCallback", e);
            return false;
        }
        return true;
    }

    /**
     * Unregisters a callback.
     *
     * @return Returns true if the callback was successfully unregistered, false otherwise.
     */
    public boolean unregisterCallback(
            @NonNull SharedConnectivityClientCallback callback) {
        Objects.requireNonNull(callback, "callback cannot be null");
        if (mService == null || !mProxyMap.containsKey(callback)) return false;
        try {
            mService.unregisterCallback(mProxyMap.get(callback));
            mProxyMap.remove(callback);
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
        if (mService == null) return false;
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
     * @return Returns true if the service received the command. Does not guarantee that the
     *         disconnection was successful.
     */
    public boolean disconnectTetherNetwork() {
        if (mService == null) return false;
        try {
            mService.disconnectTetherNetwork();
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
        if (mService == null) return false;
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
        if (mService == null) return false;
        try {
            mService.forgetKnownNetwork(network);
        } catch (RemoteException e) {
            Log.e(TAG, "Exception in forgetKnownNetwork", e);
            return false;
        }
        return true;
    }
}
