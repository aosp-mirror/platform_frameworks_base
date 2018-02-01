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
import android.os.IBinder;
import android.os.IStatsManager;
import android.os.RemoteException;
import android.os.ServiceManager;


/*
 *
 *
 *
 *
 * THIS ENTIRE FILE IS ONLY TEMPORARY TO PREVENT BREAKAGES OF DEPENDENCIES ON OLD APIS.
 * The new StatsManager is to be found in android.app.StatsManager.
 * TODO: Delete this file!
 *
 *
 *
 *
 */


/**
 * API for StatsD clients to send configurations and retrieve data.
 *
 * @hide
 */
public class StatsManager {
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
     * Temporary to prevent build failures. Will be deleted.
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
                return service.addConfiguration(Long.parseLong(configKey), config, pkg, cls);
            } catch (RemoteException e) {
                Slog.d(TAG, "Failed to connect to statsd when adding configuration");
                return false;
            }
        }
    }

    /**
     * Clients can send a configuration and simultaneously registers the name of a broadcast
     * receiver that listens for when it should request data.
     *
     * @param configKey An arbitrary integer that allows clients to track the configuration.
     * @param config    Wire-encoded StatsDConfig proto that specifies metrics (and all
     *                  dependencies eg, conditions and matchers).
     * @param pkg       The package name to receive the broadcast.
     * @param cls       The name of the class that receives the broadcast.
     * @return true if successful
     */
    @RequiresPermission(Manifest.permission.DUMP)
    public boolean addConfiguration(long configKey, byte[] config, String pkg, String cls) {
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
     * Temporary to prevent build failures. Will be deleted.
     */
    @RequiresPermission(Manifest.permission.DUMP)
    public boolean removeConfiguration(String configKey) {
        // To prevent breakages of old dependencies.
        synchronized (this) {
            try {
                IStatsManager service = getIStatsManagerLocked();
                if (service == null) {
                    Slog.d(TAG, "Failed to find statsd when removing configuration");
                    return false;
                }
                return service.removeConfiguration(Long.parseLong(configKey));
            } catch (RemoteException e) {
                Slog.d(TAG, "Failed to connect to statsd when removing configuration");
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
    public boolean removeConfiguration(long configKey) {
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
     * Temporary to prevent build failures. Will be deleted.
     */
    @RequiresPermission(Manifest.permission.DUMP)
    public byte[] getData(String configKey) {
        // TODO: remove this and all other methods with String-based config keys.
        // To prevent build breakages of dependencies.
        synchronized (this) {
            try {
                IStatsManager service = getIStatsManagerLocked();
                if (service == null) {
                    Slog.d(TAG, "Failed to find statsd when getting data");
                    return null;
                }
                return service.getData(Long.parseLong(configKey));
            } catch (RemoteException e) {
                Slog.d(TAG, "Failed to connecto statsd when getting data");
                return null;
            }
        }
    }

    /**
     * Clients can request data with a binder call. This getter is destructive and also clears
     * the retrieved metrics from statsd memory.
     *
     * @param configKey Configuration key to retrieve data from.
     * @return Serialized ConfigMetricsReportList proto. Returns null on failure.
     */
    @RequiresPermission(Manifest.permission.DUMP)
    public byte[] getData(long configKey) {
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

    /**
     * Clients can request metadata for statsd. Will contain stats across all configurations but not
     * the actual metrics themselves (metrics must be collected via {@link #getData(String)}.
     * This getter is not destructive and will not reset any metrics/counters.
     *
     * @return Serialized StatsdStatsReport proto. Returns null on failure.
     */
    @RequiresPermission(Manifest.permission.DUMP)
    public byte[] getMetadata() {
        synchronized (this) {
            try {
                IStatsManager service = getIStatsManagerLocked();
                if (service == null) {
                    Slog.d(TAG, "Failed to find statsd when getting metadata");
                    return null;
                }
                return service.getMetadata();
            } catch (RemoteException e) {
                Slog.d(TAG, "Failed to connecto statsd when getting metadata");
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
