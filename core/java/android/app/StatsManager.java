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

import static android.Manifest.permission.DUMP;
import static android.Manifest.permission.PACKAGE_USAGE_STATS;

import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.content.Context;
import android.os.IBinder;
import android.os.IStatsManager;
import android.os.IStatsPullerCallback;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.AndroidException;
import android.util.Slog;

/**
 * API for statsd clients to send configurations and retrieve data.
 *
 * @hide
 */
@SystemApi
public final class StatsManager {
    private static final String TAG = "StatsManager";
    private static final boolean DEBUG = false;

    private final Context mContext;

    private IStatsManager mService;

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
     *   List<String> of the relevant statsd_config.proto's BroadcastSubscriberDetails.cookie.
     *   Obtain using {@link android.content.Intent#getStringArrayListExtra(String)}.
     */
    public static final String EXTRA_STATS_BROADCAST_SUBSCRIBER_COOKIES =
            "android.app.extra.STATS_BROADCAST_SUBSCRIBER_COOKIES";
    /**
     * Extra of a {@link android.os.StatsDimensionsValue} representing sliced dimension value
     * information.
     */
    public static final String EXTRA_STATS_DIMENSIONS_VALUE =
            "android.app.extra.STATS_DIMENSIONS_VALUE";
    /**
     * Long array extra of the active configs for the uid that added those configs.
     */
    public static final String EXTRA_STATS_ACTIVE_CONFIG_KEYS =
            "android.app.extra.STATS_ACTIVE_CONFIG_KEYS";

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
    public StatsManager(Context context) {
        mContext = context;
    }

    /**
     * Adds the given configuration and associates it with the given configKey. If a config with the
     * given configKey already exists for the caller's uid, it is replaced with the new one.
     *
     * @param configKey An arbitrary integer that allows clients to track the configuration.
     * @param config    Wire-encoded StatsdConfig proto that specifies metrics (and all
     *                  dependencies eg, conditions and matchers).
     * @throws StatsUnavailableException if unsuccessful due to failing to connect to stats service
     * @throws IllegalArgumentException if config is not a wire-encoded StatsdConfig proto
     */
    @RequiresPermission(allOf = { DUMP, PACKAGE_USAGE_STATS })
    public void addConfig(long configKey, byte[] config) throws StatsUnavailableException {
        synchronized (this) {
            try {
                IStatsManager service = getIStatsManagerLocked();
                // can throw IllegalArgumentException
                service.addConfiguration(configKey, config, mContext.getOpPackageName());
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to connect to statsd when adding configuration");
                throw new StatsUnavailableException("could not connect", e);
            } catch (SecurityException e) {
                throw new StatsUnavailableException(e.getMessage(), e);
            }
        }
    }

    // TODO: Temporary for backwards compatibility. Remove.
    /**
     * @deprecated Use {@link #addConfig(long, byte[])}
     */
    @Deprecated
    @RequiresPermission(allOf = { DUMP, PACKAGE_USAGE_STATS })
    public boolean addConfiguration(long configKey, byte[] config) {
        try {
            addConfig(configKey, config);
            return true;
        } catch (StatsUnavailableException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Remove a configuration from logging.
     *
     * @param configKey Configuration key to remove.
     * @throws StatsUnavailableException if unsuccessful due to failing to connect to stats service
     */
    @RequiresPermission(allOf = { DUMP, PACKAGE_USAGE_STATS })
    public void removeConfig(long configKey) throws StatsUnavailableException {
        synchronized (this) {
            try {
                IStatsManager service = getIStatsManagerLocked();
                service.removeConfiguration(configKey, mContext.getOpPackageName());
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to connect to statsd when removing configuration");
                throw new StatsUnavailableException("could not connect", e);
            } catch (SecurityException e) {
                throw new StatsUnavailableException(e.getMessage(), e);
            }
        }
    }

    // TODO: Temporary for backwards compatibility. Remove.
    /**
     * @deprecated Use {@link #removeConfig(long)}
     */
    @Deprecated
    @RequiresPermission(allOf = { DUMP, PACKAGE_USAGE_STATS })
    public boolean removeConfiguration(long configKey) {
        try {
            removeConfig(configKey);
            return true;
        } catch (StatsUnavailableException e) {
            return false;
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
     * {@link #EXTRA_STATS_SUBSCRIPTION_RULE_ID},
     * {@link #EXTRA_STATS_BROADCAST_SUBSCRIBER_COOKIES}, and
     * {@link #EXTRA_STATS_DIMENSIONS_VALUE}.
     * <p>
     * This function can only be called by the owner (uid) of the config. It must be called each
     * time statsd starts. The config must have been added first (via {@link #addConfig}).
     *
     * @param pendingIntent the PendingIntent to use when broadcasting info to the subscriber
     *                      associated with the given subscriberId. May be null, in which case
     *                      it undoes any previous setting of this subscriberId.
     * @param configKey     The integer naming the config to which this subscriber is attached.
     * @param subscriberId  ID of the subscriber, as used in the config.
     * @throws StatsUnavailableException if unsuccessful due to failing to connect to stats service
     */
    @RequiresPermission(allOf = { DUMP, PACKAGE_USAGE_STATS })
    public void setBroadcastSubscriber(
            PendingIntent pendingIntent, long configKey, long subscriberId)
            throws StatsUnavailableException {
        synchronized (this) {
            try {
                IStatsManager service = getIStatsManagerLocked();
                if (pendingIntent != null) {
                    // Extracts IIntentSender from the PendingIntent and turns it into an IBinder.
                    IBinder intentSender = pendingIntent.getTarget().asBinder();
                    service.setBroadcastSubscriber(configKey, subscriberId, intentSender,
                            mContext.getOpPackageName());
                } else {
                    service.unsetBroadcastSubscriber(configKey, subscriberId,
                            mContext.getOpPackageName());
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to connect to statsd when adding broadcast subscriber", e);
                throw new StatsUnavailableException("could not connect", e);
            } catch (SecurityException e) {
                throw new StatsUnavailableException(e.getMessage(), e);
            }
        }
    }

    // TODO: Temporary for backwards compatibility. Remove.
    /**
     * @deprecated Use {@link #setBroadcastSubscriber(PendingIntent, long, long)}
     */
    @Deprecated
    @RequiresPermission(allOf = { DUMP, PACKAGE_USAGE_STATS })
    public boolean setBroadcastSubscriber(
            long configKey, long subscriberId, PendingIntent pendingIntent) {
        try {
            setBroadcastSubscriber(pendingIntent, configKey, subscriberId);
            return true;
        } catch (StatsUnavailableException e) {
            return false;
        }
    }

    /**
     * Registers the operation that is called to retrieve the metrics data. This must be called
     * each time statsd starts. The config must have been added first (via {@link #addConfig},
     * although addConfig could have been called on a previous boot). This operation allows
     * statsd to send metrics data whenever statsd determines that the metrics in memory are
     * approaching the memory limits. The fetch operation should call {@link #getReports} to fetch
     * the data, which also deletes the retrieved metrics from statsd's memory.
     *
     * @param pendingIntent the PendingIntent to use when broadcasting info to the subscriber
     *                      associated with the given subscriberId. May be null, in which case
     *                      it removes any associated pending intent with this configKey.
     * @param configKey     The integer naming the config to which this operation is attached.
     * @throws StatsUnavailableException if unsuccessful due to failing to connect to stats service
     */
    @RequiresPermission(allOf = { DUMP, PACKAGE_USAGE_STATS })
    public void setFetchReportsOperation(PendingIntent pendingIntent, long configKey)
            throws StatsUnavailableException {
        synchronized (this) {
            try {
                IStatsManager service = getIStatsManagerLocked();
                if (pendingIntent == null) {
                    service.removeDataFetchOperation(configKey, mContext.getOpPackageName());
                } else {
                    // Extracts IIntentSender from the PendingIntent and turns it into an IBinder.
                    IBinder intentSender = pendingIntent.getTarget().asBinder();
                    service.setDataFetchOperation(configKey, intentSender,
                            mContext.getOpPackageName());
                }

            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to connect to statsd when registering data listener.");
                throw new StatsUnavailableException("could not connect", e);
            } catch (SecurityException e) {
                throw new StatsUnavailableException(e.getMessage(), e);
            }
        }
    }

    /**
     * Registers the operation that is called whenever there is a change in which configs are
     * active. This must be called each time statsd starts. This operation allows
     * statsd to inform clients that they should pull data of the configs that are currently
     * active. The activeConfigsChangedOperation should set periodic alarms to pull data of configs
     * that are active and stop pulling data of configs that are no longer active.
     *
     * @param pendingIntent the PendingIntent to use when broadcasting info to the subscriber
     *                      associated with the given subscriberId. May be null, in which case
     *                      it removes any associated pending intent for this client.
     * @return A list of configs that are currently active for this client. If the pendingIntent is
     *         null, this will be an empty list.
     * @throws StatsUnavailableException if unsuccessful due to failing to connect to stats service
     */
    @RequiresPermission(allOf = { DUMP, PACKAGE_USAGE_STATS })
    public long[] setActiveConfigsChangedOperation(@Nullable PendingIntent pendingIntent)
            throws StatsUnavailableException {
        synchronized (this) {
            try {
                IStatsManager service = getIStatsManagerLocked();
                if (pendingIntent == null) {
                    service.removeActiveConfigsChangedOperation(mContext.getOpPackageName());
                    return new long[0];
                } else {
                    // Extracts IIntentSender from the PendingIntent and turns it into an IBinder.
                    IBinder intentSender = pendingIntent.getTarget().asBinder();
                    return service.setActiveConfigsChangedOperation(intentSender,
                            mContext.getOpPackageName());
                }

            } catch (RemoteException e) {
                Slog.e(TAG,
                        "Failed to connect to statsd when registering active configs listener.");
                throw new StatsUnavailableException("could not connect", e);
            } catch (SecurityException e) {
                throw new StatsUnavailableException(e.getMessage(), e);
            }
        }
    }

    // TODO: Temporary for backwards compatibility. Remove.
    /**
     * @deprecated Use {@link #setFetchReportsOperation(PendingIntent, long)}
     */
    @Deprecated
    @RequiresPermission(allOf = { DUMP, PACKAGE_USAGE_STATS })
    public boolean setDataFetchOperation(long configKey, PendingIntent pendingIntent) {
        try {
            setFetchReportsOperation(pendingIntent, configKey);
            return true;
        } catch (StatsUnavailableException e) {
            return false;
        }
    }

    /**
     * Request the data collected for the given configKey.
     * This getter is destructive - it also clears the retrieved metrics from statsd's memory.
     *
     * @param configKey Configuration key to retrieve data from.
     * @return Serialized ConfigMetricsReportList proto.
     * @throws StatsUnavailableException if unsuccessful due to failing to connect to stats service
     */
    @RequiresPermission(allOf = { DUMP, PACKAGE_USAGE_STATS })
    public byte[] getReports(long configKey) throws StatsUnavailableException {
        synchronized (this) {
            try {
                IStatsManager service = getIStatsManagerLocked();
                return service.getData(configKey, mContext.getOpPackageName());
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to connect to statsd when getting data");
                throw new StatsUnavailableException("could not connect", e);
            } catch (SecurityException e) {
                throw new StatsUnavailableException(e.getMessage(), e);
            }
        }
    }

    // TODO: Temporary for backwards compatibility. Remove.
    /**
     * @deprecated Use {@link #getReports(long)}
     */
    @Deprecated
    @RequiresPermission(allOf = { DUMP, PACKAGE_USAGE_STATS })
    public @Nullable byte[] getData(long configKey) {
        try {
            return getReports(configKey);
        } catch (StatsUnavailableException e) {
            return null;
        }
    }

    /**
     * Clients can request metadata for statsd. Will contain stats across all configurations but not
     * the actual metrics themselves (metrics must be collected via {@link #getReports(long)}.
     * This getter is not destructive and will not reset any metrics/counters.
     *
     * @return Serialized StatsdStatsReport proto.
     * @throws StatsUnavailableException if unsuccessful due to failing to connect to stats service
     */
    @RequiresPermission(allOf = { DUMP, PACKAGE_USAGE_STATS })
    public byte[] getStatsMetadata() throws StatsUnavailableException {
        synchronized (this) {
            try {
                IStatsManager service = getIStatsManagerLocked();
                return service.getMetadata(mContext.getOpPackageName());
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to connect to statsd when getting metadata");
                throw new StatsUnavailableException("could not connect", e);
            } catch (SecurityException e) {
                throw new StatsUnavailableException(e.getMessage(), e);
            }
        }
    }

    // TODO: Temporary for backwards compatibility. Remove.
    /**
     * @deprecated Use {@link #getStatsMetadata()}
     */
    @Deprecated
    @RequiresPermission(allOf = { DUMP, PACKAGE_USAGE_STATS })
    public @Nullable byte[] getMetadata() {
        try {
            return getStatsMetadata();
        } catch (StatsUnavailableException e) {
            return null;
        }
    }

    /**
     * Registers a callback for an atom when that atom is to be pulled. The stats service will
     * invoke pullData in the callback when the stats service determines that this atom needs to be
     * pulled. Currently, this only works for atoms with tags above 100,000 that do not have a uid.
     *
     * @param atomTag   The tag of the atom for this puller callback. Must be at least 100000.
     * @param callback  The callback to be invoked when the stats service pulls the atom.
     * @throws StatsUnavailableException if unsuccessful due to failing to connect to stats service
     *
     * @hide
     */
    @RequiresPermission(allOf = { DUMP, PACKAGE_USAGE_STATS })
    public void setPullerCallback(int atomTag, IStatsPullerCallback callback)
            throws StatsUnavailableException {
        synchronized (this) {
            try {
                IStatsManager service = getIStatsManagerLocked();
                if (callback == null) {
                    service.unregisterPullerCallback(atomTag, mContext.getOpPackageName());
                } else {
                    service.registerPullerCallback(atomTag, callback,
                            mContext.getOpPackageName());
                }

            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to connect to statsd when registering data listener.");
                throw new StatsUnavailableException("could not connect", e);
            } catch (SecurityException e) {
                throw new StatsUnavailableException(e.getMessage(), e);
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

    private IStatsManager getIStatsManagerLocked() throws StatsUnavailableException {
        if (mService != null) {
            return mService;
        }
        mService = IStatsManager.Stub.asInterface(ServiceManager.getService("stats"));
        if (mService == null) {
            throw new StatsUnavailableException("could not be found");
        }
        try {
            mService.asBinder().linkToDeath(new StatsdDeathRecipient(), 0);
        } catch (RemoteException e) {
            throw new StatsUnavailableException("could not connect when linkToDeath", e);
        }
        return mService;
    }

    /**
     * Exception thrown when communication with the stats service fails (eg if it is not available).
     * This might be thrown early during boot before the stats service has started or if it crashed.
     */
    public static class StatsUnavailableException extends AndroidException {
        public StatsUnavailableException(String reason) {
            super("Failed to connect to statsd: " + reason);
        }

        public StatsUnavailableException(String reason, Throwable e) {
            super("Failed to connect to statsd: " + reason, e);
        }
    }
}
