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

package android.net.netstats.provider;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.net.NetworkStats;
import android.os.RemoteException;

/**
 * A callback class that allows callers to report events to the system.
 * @hide
 */
@SystemApi
@SuppressLint("CallbackMethodName")
public class NetworkStatsProviderCallback {
    @NonNull private final INetworkStatsProviderCallback mBinder;

    /** @hide */
    public NetworkStatsProviderCallback(@NonNull INetworkStatsProviderCallback binder) {
        mBinder = binder;
    }

    /**
     * Notify the system of new network statistics.
     *
     * Send the network statistics recorded since the last call to {@link #onStatsUpdated}. Must be
     * called within one minute of {@link AbstractNetworkStatsProvider#requestStatsUpdate(int)}
     * being called. The provider can also call this whenever it wants to reports new stats for any
     * reason. Note that the system will not necessarily immediately propagate the statistics to
     * reflect the update.
     *
     * @param token the token under which these stats were gathered. Providers can call this method
     *              with the current token as often as they want, until the token changes.
     *              {@see AbstractNetworkStatsProvider#requestStatsUpdate()}
     * @param ifaceStats the {@link NetworkStats} per interface to be reported.
     *                   The provider should not include any traffic that is already counted by
     *                   kernel interface counters.
     * @param uidStats the same stats as above, but counts {@link NetworkStats}
     *                 per uid.
     */
    public void onStatsUpdated(int token, @NonNull NetworkStats ifaceStats,
            @NonNull NetworkStats uidStats) {
        try {
            mBinder.onStatsUpdated(token, ifaceStats, uidStats);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * Notify system that the quota set by {@code setAlert} has been reached.
     */
    public void onAlertReached() {
        try {
            mBinder.onAlertReached();
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * Notify system that the quota set by {@code setLimit} has been reached.
     */
    public void onLimitReached() {
        try {
            mBinder.onLimitReached();
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * Unregister the provider and the referencing callback.
     */
    public void unregister() {
        try {
            mBinder.unregister();
        } catch (RemoteException e) {
            // Ignore error.
        }
    }
}
