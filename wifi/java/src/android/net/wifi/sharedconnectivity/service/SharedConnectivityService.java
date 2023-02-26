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
import android.annotation.TestApi;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.wifi.sharedconnectivity.app.KnownNetwork;
import android.net.wifi.sharedconnectivity.app.KnownNetworkConnectionStatus;
import android.net.wifi.sharedconnectivity.app.SharedConnectivityManager;
import android.net.wifi.sharedconnectivity.app.SharedConnectivitySettingsState;
import android.net.wifi.sharedconnectivity.app.TetherNetwork;
import android.net.wifi.sharedconnectivity.app.TetherNetworkConnectionStatus;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import java.util.Collections;
import java.util.List;


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

    private Handler mHandler;
    private final RemoteCallbackList<ISharedConnectivityCallback> mRemoteCallbackList =
            new RemoteCallbackList<>();
    private List<TetherNetwork> mTetherNetworks = Collections.emptyList();
    private List<KnownNetwork> mKnownNetworks = Collections.emptyList();
    private SharedConnectivitySettingsState mSettingsState =
            new SharedConnectivitySettingsState.Builder().setInstantTetherEnabled(false)
                    .setExtras(Bundle.EMPTY).build();
    private TetherNetworkConnectionStatus mTetherNetworkConnectionStatus =
            new TetherNetworkConnectionStatus.Builder()
                    .setStatus(TetherNetworkConnectionStatus.CONNECTION_STATUS_UNKNOWN)
                    .setExtras(Bundle.EMPTY).build();
    private KnownNetworkConnectionStatus mKnownNetworkConnectionStatus =
            new KnownNetworkConnectionStatus.Builder()
                    .setStatus(KnownNetworkConnectionStatus.CONNECTION_STATUS_UNKNOWN)
                    .setExtras(Bundle.EMPTY).build();

    @Override
    @Nullable
    public final IBinder onBind(@NonNull Intent intent) {
        if (DEBUG) Log.i(TAG, "onBind intent=" + intent);
        mHandler = new Handler(getMainLooper());
        IBinder serviceStub = new ISharedConnectivityService.Stub() {
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

            @Override
            public List<TetherNetwork> getTetherNetworks() {
                checkPermissions();
                return mTetherNetworks;
            }

            @Override
            public List<KnownNetwork> getKnownNetworks() {
                checkPermissions();
                return mKnownNetworks;
            }

            @Override
            public SharedConnectivitySettingsState getSettingsState() {
                checkPermissions();
                return mSettingsState;
            }

            @Override
            public TetherNetworkConnectionStatus getTetherNetworkConnectionStatus() {
                checkPermissions();
                return mTetherNetworkConnectionStatus;
            }

            @Override
            public KnownNetworkConnectionStatus getKnownNetworkConnectionStatus() {
                checkPermissions();
                return mKnownNetworkConnectionStatus;
            }

            @RequiresPermission(anyOf = {android.Manifest.permission.NETWORK_SETTINGS,
                    android.Manifest.permission.NETWORK_SETUP_WIZARD})
            /**
             * checkPermissions is using checkCallingOrSelfPermission to support CTS testing of this
             * service. This does allow a process to bind to itself if it holds the proper
             * permission. We do not consider this to be an issue given that the process can already
             * access the service data since they are in the same process.
             */
            private void checkPermissions() {
                if (checkCallingOrSelfPermission(NETWORK_SETTINGS)
                        != PackageManager.PERMISSION_GRANTED
                        && checkCallingOrSelfPermission(NETWORK_SETUP_WIZARD)
                        != PackageManager.PERMISSION_GRANTED) {
                    throw new SecurityException("Calling process must have NETWORK_SETTINGS or"
                            + " NETWORK_SETUP_WIZARD permission");
                }
            }
        };
        onBind(); // For CTS testing
        return serviceStub;
    }

    /** @hide */
    @TestApi
    public void onBind() {}

    private void onRegisterCallback(ISharedConnectivityCallback callback) {
        mRemoteCallbackList.register(callback);
    }

    private void onUnregisterCallback(ISharedConnectivityCallback callback) {
        mRemoteCallbackList.unregister(callback);
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

        int count = mRemoteCallbackList.beginBroadcast();
        for (int i = 0; i < count; i++) {
            try {
                mRemoteCallbackList.getBroadcastItem(i).onTetherNetworksUpdated(mTetherNetworks);
            } catch (RemoteException e) {
                if (DEBUG) Log.w(TAG, "Exception in setTetherNetworks", e);
            }
        }
        mRemoteCallbackList.finishBroadcast();
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

        int count = mRemoteCallbackList.beginBroadcast();
        for (int i = 0; i < count; i++) {
            try {
                mRemoteCallbackList.getBroadcastItem(i).onKnownNetworksUpdated(mKnownNetworks);
            } catch (RemoteException e) {
                if (DEBUG) Log.w(TAG, "Exception in setKnownNetworks", e);
            }
        }
        mRemoteCallbackList.finishBroadcast();
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

        int count = mRemoteCallbackList.beginBroadcast();
        for (int i = 0; i < count; i++) {
            try {
                mRemoteCallbackList.getBroadcastItem(i).onSharedConnectivitySettingsChanged(
                        mSettingsState);
            } catch (RemoteException e) {
                if (DEBUG) Log.w(TAG, "Exception in setSettingsState", e);
            }
        }
        mRemoteCallbackList.finishBroadcast();
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

        int count = mRemoteCallbackList.beginBroadcast();
        for (int i = 0; i < count; i++) {
            try {
                mRemoteCallbackList
                        .getBroadcastItem(i).onTetherNetworkConnectionStatusChanged(
                                mTetherNetworkConnectionStatus);
            } catch (RemoteException e) {
                if (DEBUG) Log.w(TAG, "Exception in updateTetherNetworkConnectionStatus", e);
            }
        }
        mRemoteCallbackList.finishBroadcast();
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

        int count = mRemoteCallbackList.beginBroadcast();
        for (int i = 0; i < count; i++) {
            try {
                mRemoteCallbackList
                        .getBroadcastItem(i).onKnownNetworkConnectionStatusChanged(
                                mKnownNetworkConnectionStatus);
            } catch (RemoteException e) {
                if (DEBUG) Log.w(TAG, "Exception in updateKnownNetworkConnectionStatus", e);
            }
        }
        mRemoteCallbackList.finishBroadcast();
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
