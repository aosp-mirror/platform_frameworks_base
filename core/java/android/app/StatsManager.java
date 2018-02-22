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
package android.app;

import android.Manifest;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.os.IBinder;
import android.os.IStatsManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;

/**
 * API for statsd clients to send configurations and retrieve data.
 *
 * @hide
 */
@SystemApi
public final class StatsManager {
    IStatsManager mService;
    private static final String TAG = "StatsManager";
    private static final boolean DEBUG = false;

    /**
     * Long extra of uid that added the relevant stats config.
     */
    public static final String EXTRA_STATS_CONFIG_UID = "android.app.extra.STATS_CONFIG_UID";
    /**
     * Long extra of the relevant stats config's configKey.
     */
    public static final String EXTRA_STATS_CONFIG_KEY = "android.app.extra.STATS_CONFIG_KEY";
    /**
     * Long extra of the relevant statsd_config.proto's Subscription.id.
     */
    public static final String EXTRA_STATS_SUBSCRIPTION_ID =
            "android.app.extra.STATS_SUBSCRIPTION_ID";
    /**
     * Long extra of the relevant statsd_config.proto's Subscription.rule_id.
     */
    public static final String EXTRA_STATS_SUBSCRIPTION_RULE_ID =
            "android.app.extra.STATS_SUBSCRIPTION_RULE_ID";
    /**
     * Extra of a {@link android.os.StatsDimensionsValue} representing sliced dimension value
     * information.
     */
    public static final String EXTRA_STATS_DIMENSIONS_VALUE =
            "android.app.extra.STATS_DIMENSIONS_VALUE";

    /**
     * Broadcast Action: Statsd has started.
     * Configurations and PendingIntents can now be sent to it.
     */
    public static final String ACTION_STATSD_STARTED = "android.app.action.STATSD_STARTED";

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
     * @param configKey An arbitrary integer that allows clients to track the configuration.
     * @param config    Wire-encoded StatsDConfig proto that specifies metrics (and all
     *                  dependencies eg, conditions and matchers).
     * @return true if successful
     */
    @RequiresPermission(Manifest.permission.DUMP)
    public boolean addConfiguration(long configKey, byte[] config) {
        synchronized (this) {
            try {
                IStatsManager service = getIStatsManagerLocked();
                if (service == null) {
                    if (DEBUG) Slog.d(TAG, "Failed to find statsd when adding configuration");
                    return false;
                }
                return service.addConfiguration(configKey, config);
            } catch (RemoteException e) {
                if (DEBUG) Slog.d(TAG, "Failed to connect to statsd when adding configuration");
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
                    if (DEBUG) Slog.d(TAG, "Failed to find statsd when removing configuration");
                    return false;
                }
                return service.removeConfiguration(configKey);
            } catch (RemoteException e) {
                if (DEBUG) Slog.d(TAG, "Failed to connect to statsd when removing configuration");
                return false;
            }
        }
    }

    /**
     * Set the PendingIntent to be used when broadcasting subscriber information to the given
     * subscriberId within the given config.
     * <p>
     * Suppose that the calling uid has added a config with key configKey, and that in this config
     * it is specified that when a particular anomaly is detected, a broadcast should be sent to
     * a BroadcastSubscriber with id subscriberId. This function links the given pendingIntent with
     * that subscriberId (for that config), so that this pendingIntent is used to send the broadcast
     * when the anomaly is detected.
     * <p>
     * When statsd sends the broadcast, the PendingIntent will used to send an intent with
     * information of
     * {@link #EXTRA_STATS_CONFIG_UID},
     * {@link #EXTRA_STATS_CONFIG_KEY},
     * {@link #EXTRA_STATS_SUBSCRIPTION_ID},
     * {@link #EXTRA_STATS_SUBSCRIPTION_RULE_ID}, and
     * {@link #EXTRA_STATS_DIMENSIONS_VALUE}.
     * <p>
     * This function can only be called by the owner (uid) of the config. It must be called each
     * time statsd starts. The config must have been added first (via addConfiguration()).
     *
     * @param configKey     The integer naming the config to which this subscriber is attached.
     * @param subscriberId  ID of the subscriber, as used in the config.
     * @param pendingIntent the PendingIntent to use when broadcasting info to the subscriber
     *                      associated with the given subscriberId. May be null, in which case
     *                      it undoes any previous setting of this subscriberId.
     * @return true if successful
     */
    @RequiresPermission(Manifest.permission.DUMP)
    public boolean setBroadcastSubscriber(
            long configKey, long subscriberId, PendingIntent pendingIntent) {
        synchronized (this) {
            try {
                IStatsManager service = getIStatsManagerLocked();
                if (service == null) {
                    Slog.w(TAG, "Failed to find statsd when adding broadcast subscriber");
                    return false;
                }
                if (pendingIntent != null) {
                    // Extracts IIntentSender from the PendingIntent and turns it into an IBinder.
                    IBinder intentSender = pendingIntent.getTarget().asBinder();
                    return service.setBroadcastSubscriber(configKey, subscriberId, intentSender);
                } else {
                    return service.unsetBroadcastSubscriber(configKey, subscriberId);
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to connect to statsd when adding broadcast subscriber", e);
                return false;
            }
        }
    }

    /**
     * Registers the operation that is called to retrieve the metrics data. This must be called
     * each time statsd starts. The config must have been added first (via addConfiguration(),
     * although addConfiguration could have been called on a previous boot). This operation allows
     * statsd to send metrics data whenever statsd determines that the metrics in memory are
     * approaching the memory limits. The fetch operation should call {@link #getData} to fetch the
     * data, which also deletes the retrieved metrics from statsd's memory.
     *
     * @param configKey     The integer naming the config to which this operation is attached.
     * @param pendingIntent the PendingIntent to use when broadcasting info to the subscriber
     *                      associated with the given subscriberId. May be null, in which case
     *                      it removes any associated pending intent with this configKey.
     * @return true if successful
     */
    @RequiresPermission(Manifest.permission.DUMP)
    public boolean setDataFetchOperation(long configKey, PendingIntent pendingIntent) {
        synchronized (this) {
            try {
                IStatsManager service = getIStatsManagerLocked();
                if (service == null) {
                    Slog.d(TAG, "Failed to find statsd when registering data listener.");
                    return false;
                }
                if (pendingIntent == null) {
                    return service.removeDataFetchOperation(configKey);
                } else {
                    // Extracts IIntentSender from the PendingIntent and turns it into an IBinder.
                    IBinder intentSender = pendingIntent.getTarget().asBinder();
                    return service.setDataFetchOperation(configKey, intentSender);
                }

            } catch (RemoteException e) {
                Slog.d(TAG, "Failed to connect to statsd when registering data listener.");
                return false;
            }
        }
    }

    /**
     * Clients can request data with a binder call. This getter is destructive and also clears
     * the retrieved metrics from statsd memory.
     *
     * @param configKey Configuration key to retrieve data from.
     * @return Serialized ConfigMetricsReportList proto. Returns null on failure (eg, if statsd
     * crashed).
     */
    @RequiresPermission(Manifest.permission.DUMP)
    public @Nullable byte[] getData(long configKey) {
        synchronized (this) {
            try {
                IStatsManager service = getIStatsManagerLocked();
                if (service == null) {
                    if (DEBUG) Slog.d(TAG, "Failed to find statsd when getting data");
                    return null;
                }
                return service.getData(configKey);
            } catch (RemoteException e) {
                if (DEBUG) Slog.d(TAG, "Failed to connect to statsd when getting data");
                return null;
            }
        }
    }

    /**
     * Clients can request metadata for statsd. Will contain stats across all configurations but not
     * the actual metrics themselves (metrics must be collected via {@link #getData(String)}.
     * This getter is not destructive and will not reset any metrics/counters.
     *
     * @return Serialized StatsdStatsReport proto. Returns null on failure (eg, if statsd crashed).
     */
    @RequiresPermission(Manifest.permission.DUMP)
    public @Nullable byte[] getMetadata() {
        synchronized (this) {
            try {
                IStatsManager service = getIStatsManagerLocked();
                if (service == null) {
                    if (DEBUG) Slog.d(TAG, "Failed to find statsd when getting metadata");
                    return null;
                }
                return service.getMetadata();
            } catch (RemoteException e) {
                if (DEBUG) Slog.d(TAG, "Failed to connecto statsd when getting metadata");
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
