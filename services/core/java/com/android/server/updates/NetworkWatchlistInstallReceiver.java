/*
 * Copyright 2017 The Android Open Source Project
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

package com.android.server.updates;

import android.content.Context;
import android.content.Intent;
import android.net.NetworkWatchlistManager;
import android.os.RemoteException;
import android.util.Slog;

public class NetworkWatchlistInstallReceiver extends ConfigUpdateInstallReceiver {

    public NetworkWatchlistInstallReceiver() {
        super("/data/misc/network_watchlist/", "network_watchlist.xml", "metadata/", "version");
    }

    @Override
    protected void postInstall(Context context, Intent intent) {
        try {
            context.getSystemService(NetworkWatchlistManager.class).reloadWatchlist();
        } catch (Exception e) {
            // Network Watchlist is not available
            Slog.wtf("NetworkWatchlistInstallReceiver", "Unable to reload watchlist");
        }
    }
}
