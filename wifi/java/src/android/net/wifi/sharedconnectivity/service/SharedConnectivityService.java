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

package android.net.wifi.sharedconnectivity.service;

import static android.Manifest.permission.NETWORK_SETTINGS;
import static android.Manifest.permission.NETWORK_SETUP_WIZARD;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.wifi.sharedconnectivity.app.KnownNetwork;
import android.net.wifi.sharedconnectivity.app.KnownNetworkConnectionStatus;
import android.net.wifi.sharedconnectivity.app.SharedConnectivityManager;
import android.net.wifi.sharedconnectivity.app.SharedConnectivitySettingsState;
import android.net.wifi.sharedconnectivity.app.TetherNetwork;
import android.net.wifi.sharedconnectivity.app.TetherNetworkConnectionStatus;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * This class is the partly implemented service for injecting Shared Connectivity networks into the
 * Wi-Fi Pickers and other relevant UI surfaces.
 *
 * Implementing application should extend this service and override the indicated methods.
 * Callers to the service should use {@link SharedConnectivityManager} to bind to the implemented
 * service as specified in the configuration overlay.
 *
 * @hide
 */
@SystemApi
public abstract class SharedConnectivityService extends Service {
    private static final String TAG = SharedConnectivityService.class.getSimpleName();
    private static final boolean DEBUG = true;

    private  Handler mHandler;
    private final List<ISharedConnectivityCallback> mCallbacks = new ArrayList<>();
    // Used to find DeathRecipient when unregistering a callback to call unlinkToDeath.
    private final Map<ISharedConnectivityCallback, DeathRecipient> mDeathRecipientMap =
            new HashMap<>();

    private List<TetherNetwork> mTetherNetworks = Collections.emptyList();
    private List<KnownNetwork> mKnownNetworks = Collections.emptyList();
    private SharedConnectivitySettingsState mSettingsState;
    private TetherNetworkConnectionStatus mTetherNetworkConnectionStatus;
    private KnownNetworkConnectionStatus mKnownNetworkConnectionStatus;

    private final class DeathRecipient implements IBinder.DeathRecipient {
        ISharedConnectivityCallback mCallback;

        DeathRecipient(ISharedConnectivityCallback callback) {
            mCallback = callback;
        }

        @Override
        public void binderDied() {
            mCallbacks.remove(mCallback);
            mDeathRecipientMap.remove(mCallback);
        }
    }

    @Override
    @Nullable
    public final IBinder onBind(@NonNull Intent intent) {
        if (DEBUG) Log.i(TAG, "onBind intent=" + intent);
        mHandler = new Handler(getMainLooper());
        return new ISharedConnectivityService.Stub() {
            @Override
            public void registerCallback(ISharedConnectivityCallback callback) {
                checkPermissions();
                mHandler.post(() -> onRegisterCallback(callback));
            }

            @Override
            public void unregisterCallback(ISharedConnectivityCallback callback) {
                checkPermissions();
                mHandler.post(() -> onUnregisterCallback(callback));
            }

            @Override
            public void connectTetherNetwork(TetherNetwork network) {
                checkPermissions();
                mHandler.post(() -> onConnectTetherNetwork(network));
            }

            @Override
            public void disconnectTetherNetwork(TetherNetwork network) {
                checkPermissions();
                mHandler.post(() -> onDisconnectTetherNetwork(network));
            }

            @Override
            public void connectKnownNetwork(KnownNetwork network) {
                checkPermissions();
                mHandler.post(() -> onConnectKnownNetwork(network));
            }

            @Override
            public void forgetKnownNetwork(KnownNetwork network) {
                checkPermissions();
                mHandler.post(() -> onForgetKnownNetwork(network));
            }

            @RequiresPermission(anyOf = {android.Manifest.permission.NETWORK_SETTINGS,
                    android.Manifest.permission.NETWORK_SETUP_WIZARD})
            private void checkPermissions() {
                if (checkCallingPermission(NETWORK_SETTINGS) != PackageManager.PERMISSION_GRANTED
                        && checkCallingPermission(NETWORK_SETUP_WIZARD)
                                != PackageManager.PERMISSION_GRANTED) {
                    throw new SecurityException("Calling process must have NETWORK_SETTINGS or"
                            + " NETWORK_SETUP_WIZARD permission");
                }
            }
        };
    }

    private void onRegisterCallback(ISharedConnectivityCallback callback) {
        // Listener gets triggered on first register using cashed data
        if (!notifyTetherNetworkUpdate(callback) || !notifyKnownNetworkUpdate(callback)
                || !notifySettingsStateUpdate(callback)
                || !notifyTetherNetworkConnectionStatusChanged(callback)
                || !notifyKnownNetworkConnectionStatusChanged(callback)) {
            if (DEBUG) Log.w(TAG, "Failed to notify client");
            return;
        }

        DeathRecipient deathRecipient = new DeathRecipient(callback);
        try {
            callback.asBinder().linkToDeath(deathRecipient, 0);
            mCallbacks.add(callback);
            mDeathRecipientMap.put(callback, deathRecipient);
        } catch (RemoteException e) {
            if (DEBUG) Log.w(TAG, "Exception in registerCallback", e);
        }
    }

    private void onUnregisterCallback(ISharedConnectivityCallback callback) {
        DeathRecipient deathRecipient = mDeathRecipientMap.get(callback);
        if (deathRecipient != null) {
            callback.asBinder().unlinkToDeath(deathRecipient, 0);
            mDeathRecipientMap.remove(callback);
        }
        mCallbacks.remove(callback);
    }

    private boolean notifyTetherNetworkUpdate(ISharedConnectivityCallback callback) {
        try {
            callback.onTetherNetworksUpdated(mTetherNetworks);
        } catch (RemoteException e) {
            if (DEBUG) Log.w(TAG, "Exception in notifyTetherNetworkUpdate", e);
            return false;
        }
        return true;
    }

    private boolean notifyKnownNetworkUpdate(ISharedConnectivityCallback callback) {
        try {
            callback.onKnownNetworksUpdated(mKnownNetworks);
        } catch (RemoteException e) {
            if (DEBUG) Log.w(TAG, "Exception in notifyKnownNetworkUpdate", e);
            return false;
        }
        return true;
    }

    private boolean notifySettingsStateUpdate(ISharedConnectivityCallback callback) {
        try {
            callback.onSharedConnectivitySettingsChanged(mSettingsState);
        } catch (RemoteException e) {
            if (DEBUG) Log.w(TAG, "Exception in notifySettingsStateUpdate", e);
            return false;
        }
        return true;
    }

    private boolean notifyTetherNetworkConnectionStatusChanged(
            ISharedConnectivityCallback callback) {
        try {
            callback.onTetherNetworkConnectionStatusChanged(mTetherNetworkConnectionStatus);
        } catch (RemoteException e) {
            if (DEBUG) Log.w(TAG, "Exception in notifyTetherNetworkConnectionStatusChanged", e);
            return false;
        }
        return true;
    }

    private boolean notifyKnownNetworkConnectionStatusChanged(
            ISharedConnectivityCallback callback) {
        try {
            callback.onKnownNetworkConnectionStatusChanged(mKnownNetworkConnectionStatus);
        } catch (RemoteException e) {
            if (DEBUG) Log.w(TAG, "Exception in notifyKnownNetworkConnectionStatusChanged", e);
            return false;
        }
        return true;
    }
    /**
     * Implementing application should call this method to provide an up-to-date list of Tether
     * Networks to be displayed to the user.
     *
     * This method updates the cached list and notifies all registered callbacks. Any callbacks that
     * are inaccessible will be unregistered.
     *
     * @param networks The updated list of {@link TetherNetwork} objects.
     */
    public final void setTetherNetworks(@NonNull List<TetherNetwork> networks) {
        mTetherNetworks = networks;

        for (ISharedConnectivityCallback callback:mCallbacks) {
            notifyTetherNetworkUpdate(callback);
        }
    }

    /**
     * Implementing application should call this method to provide an up-to-date list of Known
     * Networks to be displayed to the user.
     *
     * This method updates the cached list and notifies all registered callbacks. Any callbacks that
     * are inaccessible will be unregistered.
     *
     * @param networks The updated list of {@link KnownNetwork} objects.
     */
    public final void setKnownNetworks(@NonNull List<KnownNetwork> networks) {
        mKnownNetworks = networks;

        for (ISharedConnectivityCallback callback:mCallbacks) {
            notifyKnownNetworkUpdate(callback);
        }
    }

    /**
     * Implementing application should call this method to provide an up-to-date state of Shared
     * connectivity settings state.
     *
     * This method updates the cached state and notifies all registered callbacks. Any callbacks
     * that are inaccessible will be unregistered.
     *
     * @param settingsState The updated state {@link SharedConnectivitySettingsState}
     *                 objects.
     */
    public final void setSettingsState(@NonNull SharedConnectivitySettingsState settingsState) {
        mSettingsState = settingsState;

        for (ISharedConnectivityCallback callback:mCallbacks) {
            notifySettingsStateUpdate(callback);
        }
    }

    /**
     * Implementing application should call this method to provide an up-to-date status of enabling
     * and connecting to the tether network.
     *
     * @param status The updated status {@link TetherNetworkConnectionStatus} of the connection.
     *
     */
    public final void updateTetherNetworkConnectionStatus(
            @NonNull TetherNetworkConnectionStatus status) {
        mTetherNetworkConnectionStatus = status;
        for (ISharedConnectivityCallback callback:mCallbacks) {
            notifyTetherNetworkConnectionStatusChanged(callback);
        }
    }

    /**
     * Implementing application should call this method to provide an up-to-date status of
     * connecting to a known network.
     *
     * @param status The updated status {@link KnownNetworkConnectionStatus} of the connection.
     *
     */
    public final void updateKnownNetworkConnectionStatus(
            @NonNull KnownNetworkConnectionStatus status) {
        mKnownNetworkConnectionStatus = status;

        for (ISharedConnectivityCallback callback:mCallbacks) {
            notifyKnownNetworkConnectionStatusChanged(callback);
        }
    }

    /**
     * Implementing application should implement this method.
     *
     * Implementation should initiate a connection to the Tether Network indicated.
     *
     * @param network Object identifying the Tether Network the user has requested a connection to.
     */
    public abstract void onConnectTetherNetwork(@NonNull TetherNetwork network);

    /**
     * Implementing application should implement this method.
     *
     * Implementation should initiate a disconnection from the active Tether Network.
     *
     * @param network Object identifying the Tether Network the user has requested to disconnect.
     */
    public abstract void onDisconnectTetherNetwork(@NonNull TetherNetwork network);

    /**
     * Implementing application should implement this method.
     *
     * Implementation should initiate a connection to the Known Network indicated.
     *
     * @param network Object identifying the Known Network the user has requested a connection to.
     */
    public abstract void onConnectKnownNetwork(@NonNull KnownNetwork network);

    /**
     * Implementing application should implement this method.
     *
     * Implementation should remove the Known Network indicated from the synced list of networks.
     *
     * @param network Object identifying the Known Network the user has requested to forget.
     */
    public abstract void onForgetKnownNetwork(@NonNull KnownNetwork network);
}
