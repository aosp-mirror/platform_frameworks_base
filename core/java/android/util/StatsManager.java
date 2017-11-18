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
package android.util;

import android.Manifest;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.os.IBinder;
import android.os.IStatsManager;
import android.os.RemoteException;
import android.os.ServiceManager;

/**
 * API for StatsD clients to send configurations and retrieve data.
 *
 * @hide
 */
@SystemApi
public final class StatsManager {
    IStatsManager mService;
    private static final String TAG = "StatsManager";

    /**
     * Constructor for StatsManagerClient.
     *
     * @hide
     */
    public StatsManager() {
    }

    /**
     * Clients can send a configuration and simultaneously registers the name of a broadcast
     * receiver that listens for when it should request data.
     *
     * @param configKey An arbitrary string that allows clients to track the configuration.
     * @param config    Wire-encoded StatsDConfig proto that specifies metrics (and all
     *                  dependencies eg, conditions and matchers).
     * @param pkg       The package name to receive the broadcast.
     * @param cls       The name of the class that receives the broadcast.
     * @return true if successful
     */
    @RequiresPermission(Manifest.permission.DUMP)
    public boolean addConfiguration(String configKey, byte[] config, String pkg, String cls) {
        synchronized (this) {
            try {
                IStatsManager service = getIStatsManagerLocked();
                if (service == null) {
                    Slog.d(TAG, "Failed to find statsd when adding configuration");
                    return false;
                }
                return service.addConfiguration(configKey, config, pkg, cls);
            } catch (RemoteException e) {
                Slog.d(TAG, "Failed to connect to statsd when adding configuration");
                return false;
            }
        }
    }

    /**
     * Remove a configuration from logging.
     *
     * @param configKey Configuration key to remove.
     * @return true if successful
     */
    @RequiresPermission(Manifest.permission.DUMP)
    public boolean removeConfiguration(String configKey) {
        synchronized (this) {
            try {
                IStatsManager service = getIStatsManagerLocked();
                if (service == null) {
                    Slog.d(TAG, "Failed to find statsd when removing configuration");
                    return false;
                }
                return service.removeConfiguration(configKey);
            } catch (RemoteException e) {
                Slog.d(TAG, "Failed to connect to statsd when removing configuration");
                return false;
            }
        }
    }

    /**
     * Clients can request data with a binder call.
     *
     * @param configKey Configuration key to retrieve data from.
     * @return Serialized ConfigMetricsReport proto. Returns null on failure.
     */
    @RequiresPermission(Manifest.permission.DUMP)
    public byte[] getData(String configKey) {
        synchronized (this) {
            try {
                IStatsManager service = getIStatsManagerLocked();
                if (service == null) {
                    Slog.d(TAG, "Failed to find statsd when getting data");
                    return null;
                }
                return service.getData(configKey);
            } catch (RemoteException e) {
                Slog.d(TAG, "Failed to connecto statsd when getting data");
                return null;
            }
        }
    }

    private class StatsdDeathRecipient implements IBinder.DeathRecipient {
        @Override
        public void binderDied() {
            synchronized (this) {
                mService = null;
            }
        }
    }

    private IStatsManager getIStatsManagerLocked() throws RemoteException {
        if (mService != null) {
            return mService;
        }
        mService = IStatsManager.Stub.asInterface(ServiceManager.getService("stats"));
        if (mService != null) {
            mService.asBinder().linkToDeath(new StatsdDeathRecipient(), 0);
        }
        return mService;
    }
}
