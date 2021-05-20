/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.android.internal.net.INetworkWatchlistManager;
import com.android.internal.util.Preconditions;

/**
 * Class that manage network watchlist in system.
 * @hide
 */
@TestApi
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
@SystemService(Context.NETWORK_WATCHLIST_SERVICE)
public class NetworkWatchlistManager {

    private static final String TAG = "NetworkWatchlistManager";
    private static final String SHARED_MEMORY_TAG = "NETWORK_WATCHLIST_SHARED_MEMORY";

    private final Context mContext;
    private final INetworkWatchlistManager mNetworkWatchlistManager;

    /**
     * @hide
     */
    public NetworkWatchlistManager(Context context, INetworkWatchlistManager manager) {
        mContext = context;
        mNetworkWatchlistManager = manager;
    }

    /**
     * @hide
     */
    public NetworkWatchlistManager(Context context) {
        mContext = Preconditions.checkNotNull(context, "missing context");
        mNetworkWatchlistManager = (INetworkWatchlistManager)
                INetworkWatchlistManager.Stub.asInterface(
                        ServiceManager.getService(Context.NETWORK_WATCHLIST_SERVICE));
    }

    /**
     * Report network watchlist records if necessary.
     *
     * Watchlist report process will summarize records into a single report, then the
     * report will be processed by differential privacy framework and stored on disk.
     *
     * @hide
     */
    public void reportWatchlistIfNecessary() {
        try {
            mNetworkWatchlistManager.reportWatchlistIfNecessary();
        } catch (RemoteException e) {
            Log.e(TAG, "Cannot report records", e);
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Reload network watchlist.
     *
     * @hide
     */
    public void reloadWatchlist() {
        try {
            mNetworkWatchlistManager.reloadWatchlist();
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to reload watchlist");
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Get Network Watchlist config file hash.
     */
    @Nullable
    public byte[] getWatchlistConfigHash() {
        try {
            return mNetworkWatchlistManager.getWatchlistConfigHash();
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to get watchlist config hash");
            throw e.rethrowFromSystemServer();
        }
    }
}
