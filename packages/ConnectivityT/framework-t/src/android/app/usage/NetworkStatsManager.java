/**
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package android.app.usage;

import static android.annotation.SystemApi.Client.MODULE_LIBRARIES;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.WorkerThread;
import android.app.usage.NetworkStats.Bucket;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.DataUsageRequest;
import android.net.INetworkStatsService;
import android.net.Network;
import android.net.NetworkStack;
import android.net.NetworkStateSnapshot;
import android.net.NetworkTemplate;
import android.net.UnderlyingNetworkInfo;
import android.net.netstats.IUsageCallback;
import android.net.netstats.NetworkStatsDataMigrationUtils;
import android.net.netstats.provider.INetworkStatsProviderCallback;
import android.net.netstats.provider.NetworkStatsProvider;
import android.os.Build;
import android.os.Handler;
import android.os.RemoteException;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.NetworkIdentityUtils;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Provides access to network usage history and statistics. Usage data is collected in
 * discrete bins of time called 'Buckets'. See {@link NetworkStats.Bucket} for details.
 * <p />
 * Queries can define a time interval in the form of start and end timestamps (Long.MIN_VALUE and
 * Long.MAX_VALUE can be used to simulate open ended intervals). By default, apps can only obtain
 * data about themselves. See the below note for special cases in which apps can obtain data about
 * other applications.
 * <h3>
 * Summary queries
 * </h3>
 * {@link #querySummaryForDevice} <p />
 * {@link #querySummaryForUser} <p />
 * {@link #querySummary} <p />
 * These queries aggregate network usage across the whole interval. Therefore there will be only one
 * bucket for a particular key, state, metered and roaming combination. In case of the user-wide
 * and device-wide summaries a single bucket containing the totalised network usage is returned.
 * <h3>
 * History queries
 * </h3>
 * {@link #queryDetailsForUid} <p />
 * {@link #queryDetails} <p />
 * These queries do not aggregate over time but do aggregate over state, metered and roaming.
 * Therefore there can be multiple buckets for a particular key. However, all Buckets will have
 * {@code state} {@link NetworkStats.Bucket#STATE_ALL},
 * {@code defaultNetwork} {@link NetworkStats.Bucket#DEFAULT_NETWORK_ALL},
 * {@code metered } {@link NetworkStats.Bucket#METERED_ALL},
 * {@code roaming} {@link NetworkStats.Bucket#ROAMING_ALL}.
 * <p />
 * <b>NOTE:</b> Calling {@link #querySummaryForDevice} or accessing stats for apps other than the
 * calling app requires the permission {@link android.Manifest.permission#PACKAGE_USAGE_STATS},
 * which is a system-level permission and will not be granted to third-party apps. However,
 * declaring the permission implies intention to use the API and the user of the device can grant
 * permission through the Settings application.
 * <p />
 * Profile owner apps are automatically granted permission to query data on the profile they manage
 * (that is, for any query except {@link #querySummaryForDevice}). Device owner apps and carrier-
 * privileged apps likewise get access to usage data for all users on the device.
 * <p />
 * In addition to tethering usage, usage by removed users and apps, and usage by the system
 * is also included in the results for callers with one of these higher levels of access.
 * <p />
 * <b>NOTE:</b> Prior to API level {@value android.os.Build.VERSION_CODES#N}, all calls to these APIs required
 * the above permission, even to access an app's own data usage, and carrier-privileged apps were
 * not included.
 */
@SystemService(Context.NETWORK_STATS_SERVICE)
public class NetworkStatsManager {
    private static final String TAG = "NetworkStatsManager";
    private static final boolean DBG = false;

    /** @hide */
    public static final int CALLBACK_LIMIT_REACHED = 0;
    /** @hide */
    public static final int CALLBACK_RELEASED = 1;

    /**
     * Minimum data usage threshold for registering usage callbacks.
     *
     * Requests registered with a threshold lower than this will only be triggered once this minimum
     * is reached.
     * @hide
     */
    public static final long MIN_THRESHOLD_BYTES = 2 * 1_048_576L; // 2MiB

    private final Context mContext;
    private final INetworkStatsService mService;

    /**
     * @deprecated Use {@link NetworkStatsDataMigrationUtils#PREFIX_XT}
     * instead.
     * @hide
     */
    @Deprecated
    public static final String PREFIX_DEV = "dev";

    /** @hide */
    public static final int FLAG_POLL_ON_OPEN = 1 << 0;
    /** @hide */
    public static final int FLAG_POLL_FORCE = 1 << 1;
    /** @hide */
    public static final int FLAG_AUGMENT_WITH_SUBSCRIPTION_PLAN = 1 << 2;

    /**
     * Virtual RAT type to represent 5G NSA (Non Stand Alone) mode, where the primary cell is
     * still LTE and network allocates a secondary 5G cell so telephony reports RAT = LTE along
     * with NR state as connected. This is a concept added by NetworkStats on top of the telephony
     * constants for backward compatibility of metrics so this should not be overlapped with any of
     * the {@code TelephonyManager.NETWORK_TYPE_*} constants.
     *
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int NETWORK_TYPE_5G_NSA = -2;

    private int mFlags;

    /** @hide */
    @VisibleForTesting
    public NetworkStatsManager(Context context, INetworkStatsService service) {
        mContext = context;
        mService = service;
        setPollOnOpen(true);
        setAugmentWithSubscriptionPlan(true);
    }

    /** @hide */
    public INetworkStatsService getBinder() {
        return mService;
    }

    /**
     * Set poll on open flag to indicate the poll is needed before service gets statistics
     * result. This is default enabled. However, for any non-privileged caller, the poll might
     * be omitted in case of rate limiting.
     *
     * @param pollOnOpen true if poll is needed.
     * @hide
     */
    // The system will ignore any non-default values for non-privileged
    // processes, so processes that don't hold the appropriate permissions
    // can make no use of this API.
    @SystemApi(client = MODULE_LIBRARIES)
    @RequiresPermission(anyOf = {
            NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK,
            android.Manifest.permission.NETWORK_STACK})
    public void setPollOnOpen(boolean pollOnOpen) {
        if (pollOnOpen) {
            mFlags |= FLAG_POLL_ON_OPEN;
        } else {
            mFlags &= ~FLAG_POLL_ON_OPEN;
        }
    }

    /**
     * Set poll force flag to indicate that calling any subsequent query method will force a stats
     * poll.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @SystemApi(client = MODULE_LIBRARIES)
    public void setPollForce(boolean pollForce) {
        if (pollForce) {
            mFlags |= FLAG_POLL_FORCE;
        } else {
            mFlags &= ~FLAG_POLL_FORCE;
        }
    }

    /** @hide */
    public void setAugmentWithSubscriptionPlan(boolean augmentWithSubscriptionPlan) {
        if (augmentWithSubscriptionPlan) {
            mFlags |= FLAG_AUGMENT_WITH_SUBSCRIPTION_PLAN;
        } else {
            mFlags &= ~FLAG_AUGMENT_WITH_SUBSCRIPTION_PLAN;
        }
    }

    /**
     * Query network usage statistics summaries.
     *
     * Result is summarised data usage for the whole
     * device. Result is a single Bucket aggregated over time, state, uid, tag, metered, and
     * roaming. This means the bucket's start and end timestamp will be the same as the
     * 'startTime' and 'endTime' arguments. State is going to be
     * {@link NetworkStats.Bucket#STATE_ALL}, uid {@link NetworkStats.Bucket#UID_ALL},
     * tag {@link NetworkStats.Bucket#TAG_NONE},
     * default network {@link NetworkStats.Bucket#DEFAULT_NETWORK_ALL},
     * metered {@link NetworkStats.Bucket#METERED_ALL},
     * and roaming {@link NetworkStats.Bucket#ROAMING_ALL}.
     * This may take a long time, and apps should avoid calling this on their main thread.
     *
     * @param template Template used to match networks. See {@link NetworkTemplate}.
     * @param startTime Start of period, in milliseconds since the Unix epoch, see
     *            {@link java.lang.System#currentTimeMillis}.
     * @param endTime End of period, in milliseconds since the Unix epoch, see
     *            {@link java.lang.System#currentTimeMillis}.
     * @return Bucket Summarised data usage.
     *
     * @hide
     */
    @NonNull
    @WorkerThread
    @SystemApi(client = MODULE_LIBRARIES)
    public Bucket querySummaryForDevice(@NonNull NetworkTemplate template,
            long startTime, long endTime) {
        Objects.requireNonNull(template);
        try {
            NetworkStats stats =
                    new NetworkStats(mContext, template, mFlags, startTime, endTime, mService);
            Bucket bucket = stats.getDeviceSummaryForNetwork();
            stats.close();
            return bucket;
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return null; // To make the compiler happy.
    }

    /**
     * Query network usage statistics summaries. Result is summarised data usage for the whole
     * device. Result is a single Bucket aggregated over time, state, uid, tag, metered, and
     * roaming. This means the bucket's start and end timestamp are going to be the same as the
     * 'startTime' and 'endTime' parameters. State is going to be
     * {@link NetworkStats.Bucket#STATE_ALL}, uid {@link NetworkStats.Bucket#UID_ALL},
     * tag {@link NetworkStats.Bucket#TAG_NONE},
     * default network {@link NetworkStats.Bucket#DEFAULT_NETWORK_ALL},
     * metered {@link NetworkStats.Bucket#METERED_ALL},
     * and roaming {@link NetworkStats.Bucket#ROAMING_ALL}.
     * This may take a long time, and apps should avoid calling this on their main thread.
     *
     * @param networkType As defined in {@link ConnectivityManager}, e.g.
     *            {@link ConnectivityManager#TYPE_MOBILE}, {@link ConnectivityManager#TYPE_WIFI}
     *            etc.
     * @param subscriberId If applicable, the subscriber id of the network interface.
     *                     <p>Starting with API level 29, the {@code subscriberId} is guarded by
     *                     additional restrictions. Calling apps that do not meet the new
     *                     requirements to access the {@code subscriberId} can provide a {@code
     *                     null} value when querying for the mobile network type to receive usage
     *                     for all mobile networks. For additional details see {@link
     *                     TelephonyManager#getSubscriberId()}.
     *                     <p>Starting with API level 31, calling apps can provide a
     *                     {@code subscriberId} with wifi network type to receive usage for
     *                     wifi networks which is under the given subscription if applicable.
     *                     Otherwise, pass {@code null} when querying all wifi networks.
     * @param startTime Start of period. Defined in terms of "Unix time", see
     *            {@link java.lang.System#currentTimeMillis}.
     * @param endTime End of period. Defined in terms of "Unix time", see
     *            {@link java.lang.System#currentTimeMillis}.
     * @return Bucket object or null if permissions are insufficient or error happened during
     *         statistics collection.
     */
    @WorkerThread
    public Bucket querySummaryForDevice(int networkType, String subscriberId,
            long startTime, long endTime) throws SecurityException, RemoteException {
        NetworkTemplate template;
        try {
            template = createTemplate(networkType, subscriberId);
        } catch (IllegalArgumentException e) {
            if (DBG) Log.e(TAG, "Cannot create template", e);
            return null;
        }

        return querySummaryForDevice(template, startTime, endTime);
    }

    /**
     * Query network usage statistics summaries. Result is summarised data usage for all uids
     * belonging to calling user. Result is a single Bucket aggregated over time, state and uid.
     * This means the bucket's start and end timestamp are going to be the same as the 'startTime'
     * and 'endTime' parameters. State is going to be {@link NetworkStats.Bucket#STATE_ALL},
     * uid {@link NetworkStats.Bucket#UID_ALL}, tag {@link NetworkStats.Bucket#TAG_NONE},
     * metered {@link NetworkStats.Bucket#METERED_ALL}, and roaming
     * {@link NetworkStats.Bucket#ROAMING_ALL}.
     * This may take a long time, and apps should avoid calling this on their main thread.
     *
     * @param networkType As defined in {@link ConnectivityManager}, e.g.
     *            {@link ConnectivityManager#TYPE_MOBILE}, {@link ConnectivityManager#TYPE_WIFI}
     *            etc.
     * @param subscriberId If applicable, the subscriber id of the network interface.
     *                     <p>Starting with API level 29, the {@code subscriberId} is guarded by
     *                     additional restrictions. Calling apps that do not meet the new
     *                     requirements to access the {@code subscriberId} can provide a {@code
     *                     null} value when querying for the mobile network type to receive usage
     *                     for all mobile networks. For additional details see {@link
     *                     TelephonyManager#getSubscriberId()}.
     *                     <p>Starting with API level 31, calling apps can provide a
     *                     {@code subscriberId} with wifi network type to receive usage for
     *                     wifi networks which is under the given subscription if applicable.
     *                     Otherwise, pass {@code null} when querying all wifi networks.
     * @param startTime Start of period. Defined in terms of "Unix time", see
     *            {@link java.lang.System#currentTimeMillis}.
     * @param endTime End of period. Defined in terms of "Unix time", see
     *            {@link java.lang.System#currentTimeMillis}.
     * @return Bucket object or null if permissions are insufficient or error happened during
     *         statistics collection.
     */
    @WorkerThread
    public Bucket querySummaryForUser(int networkType, String subscriberId, long startTime,
            long endTime) throws SecurityException, RemoteException {
        NetworkTemplate template;
        try {
            template = createTemplate(networkType, subscriberId);
        } catch (IllegalArgumentException e) {
            if (DBG) Log.e(TAG, "Cannot create template", e);
            return null;
        }

        NetworkStats stats;
        stats = new NetworkStats(mContext, template, mFlags, startTime, endTime, mService);
        stats.startSummaryEnumeration();

        stats.close();
        return stats.getSummaryAggregate();
    }

    /**
     * Query network usage statistics summaries. Result filtered to include only uids belonging to
     * calling user. Result is aggregated over time, hence all buckets will have the same start and
     * end timestamps. Not aggregated over state, uid, default network, metered, or roaming. This
     * means buckets' start and end timestamps are going to be the same as the 'startTime' and
     * 'endTime' parameters. State, uid, metered, and roaming are going to vary, and tag is going to
     * be the same.
     * This may take a long time, and apps should avoid calling this on their main thread.
     *
     * @param networkType As defined in {@link ConnectivityManager}, e.g.
     *            {@link ConnectivityManager#TYPE_MOBILE}, {@link ConnectivityManager#TYPE_WIFI}
     *            etc.
     * @param subscriberId If applicable, the subscriber id of the network interface.
     *                     <p>Starting with API level 29, the {@code subscriberId} is guarded by
     *                     additional restrictions. Calling apps that do not meet the new
     *                     requirements to access the {@code subscriberId} can provide a {@code
     *                     null} value when querying for the mobile network type to receive usage
     *                     for all mobile networks. For additional details see {@link
     *                     TelephonyManager#getSubscriberId()}.
     *                     <p>Starting with API level 31, calling apps can provide a
     *                     {@code subscriberId} with wifi network type to receive usage for
     *                     wifi networks which is under the given subscription if applicable.
     *                     Otherwise, pass {@code null} when querying all wifi networks.
     * @param startTime Start of period. Defined in terms of "Unix time", see
     *            {@link java.lang.System#currentTimeMillis}.
     * @param endTime End of period. Defined in terms of "Unix time", see
     *            {@link java.lang.System#currentTimeMillis}.
     * @return Statistics object or null if permissions are insufficient or error happened during
     *         statistics collection.
     */
    @WorkerThread
    public NetworkStats querySummary(int networkType, String subscriberId, long startTime,
            long endTime) throws SecurityException, RemoteException {
        NetworkTemplate template;
        try {
            template = createTemplate(networkType, subscriberId);
        } catch (IllegalArgumentException e) {
            if (DBG) Log.e(TAG, "Cannot create template", e);
            return null;
        }

        return querySummary(template, startTime, endTime);
    }

    /**
     * Query network usage statistics summaries.
     *
     * The results will only include traffic made by UIDs belonging to the calling user profile.
     * The results are aggregated over time, so that all buckets will have the same start and
     * end timestamps as the passed arguments. Not aggregated over state, uid, default network,
     * metered, or roaming.
     * This may take a long time, and apps should avoid calling this on their main thread.
     *
     * @param template Template used to match networks. See {@link NetworkTemplate}.
     * @param startTime Start of period, in milliseconds since the Unix epoch, see
     *            {@link java.lang.System#currentTimeMillis}.
     * @param endTime End of period, in milliseconds since the Unix epoch, see
     *            {@link java.lang.System#currentTimeMillis}.
     * @return Statistics which is described above.
     * @hide
     */
    @NonNull
    @SystemApi(client = MODULE_LIBRARIES)
    @WorkerThread
    public NetworkStats querySummary(@NonNull NetworkTemplate template, long startTime,
            long endTime) throws SecurityException {
        Objects.requireNonNull(template);
        try {
            NetworkStats result =
                    new NetworkStats(mContext, template, mFlags, startTime, endTime, mService);
            result.startSummaryEnumeration();
            return result;
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return null; // To make the compiler happy.
    }

    /**
     * Query tagged network usage statistics summaries.
     *
     * The results will only include tagged traffic made by UIDs belonging to the calling user
     * profile. The results are aggregated over time, so that all buckets will have the same
     * start and end timestamps as the passed arguments. Not aggregated over state, uid,
     * default network, metered, or roaming.
     * This may take a long time, and apps should avoid calling this on their main thread.
     *
     * @param template Template used to match networks. See {@link NetworkTemplate}.
     * @param startTime Start of period, in milliseconds since the Unix epoch, see
     *            {@link System#currentTimeMillis}.
     * @param endTime End of period, in milliseconds since the Unix epoch, see
     *            {@link System#currentTimeMillis}.
     * @return Statistics which is described above.
     * @hide
     */
    @NonNull
    @SystemApi(client = MODULE_LIBRARIES)
    @WorkerThread
    public NetworkStats queryTaggedSummary(@NonNull NetworkTemplate template, long startTime,
            long endTime) throws SecurityException {
        Objects.requireNonNull(template);
        try {
            NetworkStats result =
                    new NetworkStats(mContext, template, mFlags, startTime, endTime, mService);
            result.startTaggedSummaryEnumeration();
            return result;
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return null; // To make the compiler happy.
    }

    /**
     * Query usage statistics details for networks matching a given {@link NetworkTemplate}.
     *
     * Result is not aggregated over time. This means buckets' start and
     * end timestamps will be between 'startTime' and 'endTime' parameters.
     * <p>Only includes buckets whose entire time period is included between
     * startTime and endTime. Doesn't interpolate or return partial buckets.
     * Since bucket length is in the order of hours, this
     * method cannot be used to measure data usage on a fine grained time scale.
     * This may take a long time, and apps should avoid calling this on their main thread.
     *
     * @param template Template used to match networks. See {@link NetworkTemplate}.
     * @param startTime Start of period, in milliseconds since the Unix epoch, see
     *                  {@link java.lang.System#currentTimeMillis}.
     * @param endTime End of period, in milliseconds since the Unix epoch, see
     *                {@link java.lang.System#currentTimeMillis}.
     * @return Statistics which is described above.
     * @hide
     */
    @NonNull
    @SystemApi(client = MODULE_LIBRARIES)
    @WorkerThread
    public NetworkStats queryDetailsForDevice(@NonNull NetworkTemplate template,
            long startTime, long endTime) {
        Objects.requireNonNull(template);
        try {
            final NetworkStats result =
                    new NetworkStats(mContext, template, mFlags, startTime, endTime, mService);
            result.startHistoryDeviceEnumeration();
            return result;
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }

        return null; // To make the compiler happy.
    }

    /**
     * Query network usage statistics details for a given uid.
     * This may take a long time, and apps should avoid calling this on their main thread.
     *
     * @see #queryDetailsForUidTagState(int, String, long, long, int, int, int)
     */
    @WorkerThread
    public NetworkStats queryDetailsForUid(int networkType, String subscriberId,
            long startTime, long endTime, int uid) throws SecurityException {
        return queryDetailsForUidTagState(networkType, subscriberId, startTime, endTime, uid,
            NetworkStats.Bucket.TAG_NONE, NetworkStats.Bucket.STATE_ALL);
    }

    /** @hide */
    public NetworkStats queryDetailsForUid(NetworkTemplate template,
            long startTime, long endTime, int uid) throws SecurityException {
        return queryDetailsForUidTagState(template, startTime, endTime, uid,
                NetworkStats.Bucket.TAG_NONE, NetworkStats.Bucket.STATE_ALL);
    }

    /**
     * Query network usage statistics details for a given uid and tag.
     * This may take a long time, and apps should avoid calling this on their main thread.
     *
     * @see #queryDetailsForUidTagState(int, String, long, long, int, int, int)
     */
    @WorkerThread
    public NetworkStats queryDetailsForUidTag(int networkType, String subscriberId,
            long startTime, long endTime, int uid, int tag) throws SecurityException {
        return queryDetailsForUidTagState(networkType, subscriberId, startTime, endTime, uid,
            tag, NetworkStats.Bucket.STATE_ALL);
    }

    /**
     * Query network usage statistics details for a given uid, tag, and state. Only usable for uids
     * belonging to calling user. Result is not aggregated over time. This means buckets' start and
     * end timestamps are going to be between 'startTime' and 'endTime' parameters. The uid is going
     * to be the same as the 'uid' parameter, the tag the same as the 'tag' parameter, and the state
     * the same as the 'state' parameter.
     * defaultNetwork is going to be {@link NetworkStats.Bucket#DEFAULT_NETWORK_ALL},
     * metered is going to be {@link NetworkStats.Bucket#METERED_ALL}, and
     * roaming is going to be {@link NetworkStats.Bucket#ROAMING_ALL}.
     * <p>Only includes buckets that atomically occur in the inclusive time range. Doesn't
     * interpolate across partial buckets. Since bucket length is in the order of hours, this
     * method cannot be used to measure data usage on a fine grained time scale.
     * This may take a long time, and apps should avoid calling this on their main thread.
     *
     * @param networkType As defined in {@link ConnectivityManager}, e.g.
     *            {@link ConnectivityManager#TYPE_MOBILE}, {@link ConnectivityManager#TYPE_WIFI}
     *            etc.
     * @param subscriberId If applicable, the subscriber id of the network interface.
     *                     <p>Starting with API level 29, the {@code subscriberId} is guarded by
     *                     additional restrictions. Calling apps that do not meet the new
     *                     requirements to access the {@code subscriberId} can provide a {@code
     *                     null} value when querying for the mobile network type to receive usage
     *                     for all mobile networks. For additional details see {@link
     *                     TelephonyManager#getSubscriberId()}.
     *                     <p>Starting with API level 31, calling apps can provide a
     *                     {@code subscriberId} with wifi network type to receive usage for
     *                     wifi networks which is under the given subscription if applicable.
     *                     Otherwise, pass {@code null} when querying all wifi networks.
     * @param startTime Start of period. Defined in terms of "Unix time", see
     *            {@link java.lang.System#currentTimeMillis}.
     * @param endTime End of period. Defined in terms of "Unix time", see
     *            {@link java.lang.System#currentTimeMillis}.
     * @param uid UID of app
     * @param tag TAG of interest. Use {@link NetworkStats.Bucket#TAG_NONE} for aggregated data
     *            across all the tags.
     * @param state state of interest. Use {@link NetworkStats.Bucket#STATE_ALL} to aggregate
     *            traffic from all states.
     * @return Statistics object or null if an error happened during statistics collection.
     * @throws SecurityException if permissions are insufficient to read network statistics.
     */
    @WorkerThread
    public NetworkStats queryDetailsForUidTagState(int networkType, String subscriberId,
            long startTime, long endTime, int uid, int tag, int state) throws SecurityException {
        NetworkTemplate template;
        template = createTemplate(networkType, subscriberId);

        return queryDetailsForUidTagState(template, startTime, endTime, uid, tag, state);
    }

    /**
     * Query network usage statistics details for a given template, uid, tag, and state.
     *
     * Only usable for uids belonging to calling user. Result is not aggregated over time.
     * This means buckets' start and end timestamps are going to be between 'startTime' and
     * 'endTime' parameters. The uid is going to be the same as the 'uid' parameter, the tag
     * the same as the 'tag' parameter, and the state the same as the 'state' parameter.
     * defaultNetwork is going to be {@link NetworkStats.Bucket#DEFAULT_NETWORK_ALL},
     * metered is going to be {@link NetworkStats.Bucket#METERED_ALL}, and
     * roaming is going to be {@link NetworkStats.Bucket#ROAMING_ALL}.
     * <p>Only includes buckets that atomically occur in the inclusive time range. Doesn't
     * interpolate across partial buckets. Since bucket length is in the order of hours, this
     * method cannot be used to measure data usage on a fine grained time scale.
     * This may take a long time, and apps should avoid calling this on their main thread.
     *
     * @param template Template used to match networks. See {@link NetworkTemplate}.
     * @param startTime Start of period, in milliseconds since the Unix epoch, see
     *                  {@link java.lang.System#currentTimeMillis}.
     * @param endTime End of period, in milliseconds since the Unix epoch, see
     *                {@link java.lang.System#currentTimeMillis}.
     * @param uid UID of app
     * @param tag TAG of interest. Use {@link NetworkStats.Bucket#TAG_NONE} for aggregated data
     *            across all the tags.
     * @param state state of interest. Use {@link NetworkStats.Bucket#STATE_ALL} to aggregate
     *            traffic from all states.
     * @return Statistics which is described above.
     * @hide
     */
    @NonNull
    @SystemApi(client = MODULE_LIBRARIES)
    @WorkerThread
    public NetworkStats queryDetailsForUidTagState(@NonNull NetworkTemplate template,
            long startTime, long endTime, int uid, int tag, int state) throws SecurityException {
        Objects.requireNonNull(template);
        try {
            final NetworkStats result = new NetworkStats(
                    mContext, template, mFlags, startTime, endTime, mService);
            result.startHistoryUidEnumeration(uid, tag, state);
            return result;
        } catch (RemoteException e) {
            Log.e(TAG, "Error while querying stats for uid=" + uid + " tag=" + tag
                    + " state=" + state, e);
            e.rethrowFromSystemServer();
        }

        return null; // To make the compiler happy.
    }

    /**
     * Query network usage statistics details. Result filtered to include only uids belonging to
     * calling user. Result is aggregated over state but not aggregated over time, uid, tag,
     * metered, nor roaming. This means buckets' start and end timestamps are going to be between
     * 'startTime' and 'endTime' parameters. State is going to be
     * {@link NetworkStats.Bucket#STATE_ALL}, uid will vary,
     * tag {@link NetworkStats.Bucket#TAG_NONE},
     * default network is going to be {@link NetworkStats.Bucket#DEFAULT_NETWORK_ALL},
     * metered is going to be {@link NetworkStats.Bucket#METERED_ALL},
     * and roaming is going to be {@link NetworkStats.Bucket#ROAMING_ALL}.
     * <p>Only includes buckets that atomically occur in the inclusive time range. Doesn't
     * interpolate across partial buckets. Since bucket length is in the order of hours, this
     * method cannot be used to measure data usage on a fine grained time scale.
     * This may take a long time, and apps should avoid calling this on their main thread.
     *
     * @param networkType As defined in {@link ConnectivityManager}, e.g.
     *            {@link ConnectivityManager#TYPE_MOBILE}, {@link ConnectivityManager#TYPE_WIFI}
     *            etc.
     * @param subscriberId If applicable, the subscriber id of the network interface.
     *                     <p>Starting with API level 29, the {@code subscriberId} is guarded by
     *                     additional restrictions. Calling apps that do not meet the new
     *                     requirements to access the {@code subscriberId} can provide a {@code
     *                     null} value when querying for the mobile network type to receive usage
     *                     for all mobile networks. For additional details see {@link
     *                     TelephonyManager#getSubscriberId()}.
     *                     <p>Starting with API level 31, calling apps can provide a
     *                     {@code subscriberId} with wifi network type to receive usage for
     *                     wifi networks which is under the given subscription if applicable.
     *                     Otherwise, pass {@code null} when querying all wifi networks.
     * @param startTime Start of period. Defined in terms of "Unix time", see
     *            {@link java.lang.System#currentTimeMillis}.
     * @param endTime End of period. Defined in terms of "Unix time", see
     *            {@link java.lang.System#currentTimeMillis}.
     * @return Statistics object or null if permissions are insufficient or error happened during
     *         statistics collection.
     */
    @WorkerThread
    public NetworkStats queryDetails(int networkType, String subscriberId, long startTime,
            long endTime) throws SecurityException, RemoteException {
        NetworkTemplate template;
        try {
            template = createTemplate(networkType, subscriberId);
        } catch (IllegalArgumentException e) {
            if (DBG) Log.e(TAG, "Cannot create template", e);
            return null;
        }

        NetworkStats result;
        result = new NetworkStats(mContext, template, mFlags, startTime, endTime, mService);
        result.startUserUidEnumeration();
        return result;
    }

    /**
     * Query realtime mobile network usage statistics.
     *
     * Return a snapshot of current UID network statistics, as it applies
     * to the mobile radios of the device. The snapshot will include any
     * tethering traffic, video calling data usage and count of
     * network operations set by {@link TrafficStats#incrementOperationCount}
     * made over a mobile radio.
     * The snapshot will not include any statistics that cannot be seen by
     * the kernel, e.g. statistics reported by {@link NetworkStatsProvider}s.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK,
            android.Manifest.permission.NETWORK_STACK})
    @NonNull public android.net.NetworkStats getMobileUidStats() {
        try {
            return mService.getUidStatsForTransport(TRANSPORT_CELLULAR);
        } catch (RemoteException e) {
            if (DBG) Log.d(TAG, "Remote exception when get Mobile uid stats");
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Query realtime Wi-Fi network usage statistics.
     *
     * Return a snapshot of current UID network statistics, as it applies
     * to the Wi-Fi radios of the device. The snapshot will include any
     * tethering traffic, video calling data usage and count of
     * network operations set by {@link TrafficStats#incrementOperationCount}
     * made over a Wi-Fi radio.
     * The snapshot will not include any statistics that cannot be seen by
     * the kernel, e.g. statistics reported by {@link NetworkStatsProvider}s.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK,
            android.Manifest.permission.NETWORK_STACK})
    @NonNull public android.net.NetworkStats getWifiUidStats() {
        try {
            return mService.getUidStatsForTransport(TRANSPORT_WIFI);
        } catch (RemoteException e) {
            if (DBG) Log.d(TAG, "Remote exception when get WiFi uid stats");
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers to receive notifications about data usage on specified networks.
     *
     * <p>The callbacks will continue to be called as long as the process is alive or
     * {@link #unregisterUsageCallback} is called.
     *
     * @param template Template used to match networks. See {@link NetworkTemplate}.
     * @param thresholdBytes Threshold in bytes to be notified on. Provided values lower than 2MiB
     *                       will be clamped for callers except callers with the NETWORK_STACK
     *                       permission.
     * @param executor The executor on which callback will be invoked. The provided {@link Executor}
     *                 must run callback sequentially, otherwise the order of callbacks cannot be
     *                 guaranteed.
     * @param callback The {@link UsageCallback} that the system will call when data usage
     *                 has exceeded the specified threshold.
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    @RequiresPermission(anyOf = {
            NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK,
            android.Manifest.permission.NETWORK_STACK}, conditional = true)
    public void registerUsageCallback(@NonNull NetworkTemplate template, long thresholdBytes,
            @NonNull @CallbackExecutor Executor executor, @NonNull UsageCallback callback) {
        Objects.requireNonNull(template, "NetworkTemplate cannot be null");
        Objects.requireNonNull(callback, "UsageCallback cannot be null");
        Objects.requireNonNull(executor, "Executor cannot be null");

        final DataUsageRequest request = new DataUsageRequest(DataUsageRequest.REQUEST_ID_UNSET,
                template, thresholdBytes);
        try {
            final UsageCallbackWrapper callbackWrapper =
                    new UsageCallbackWrapper(executor, callback);
            callback.request = mService.registerUsageCallback(
                    mContext.getOpPackageName(), request, callbackWrapper);
            if (DBG) Log.d(TAG, "registerUsageCallback returned " + callback.request);

            if (callback.request == null) {
                Log.e(TAG, "Request from callback is null; should not happen");
            }
        } catch (RemoteException e) {
            if (DBG) Log.d(TAG, "Remote exception when registering callback");
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers to receive notifications about data usage on specified networks.
     *
     * @see #registerUsageCallback(int, String, long, UsageCallback, Handler)
     */
    public void registerUsageCallback(int networkType, String subscriberId, long thresholdBytes,
            UsageCallback callback) {
        registerUsageCallback(networkType, subscriberId, thresholdBytes, callback,
                null /* handler */);
    }

    /**
     * Registers to receive notifications about data usage on specified networks.
     *
     * <p>The callbacks will continue to be called as long as the process is live or
     * {@link #unregisterUsageCallback} is called.
     *
     * @param networkType Type of network to monitor. Either
                  {@link ConnectivityManager#TYPE_MOBILE} or {@link ConnectivityManager#TYPE_WIFI}.
     * @param subscriberId If applicable, the subscriber id of the network interface.
     *                     <p>Starting with API level 29, the {@code subscriberId} is guarded by
     *                     additional restrictions. Calling apps that do not meet the new
     *                     requirements to access the {@code subscriberId} can provide a {@code
     *                     null} value when registering for the mobile network type to receive
     *                     notifications for all mobile networks. For additional details see {@link
     *                     TelephonyManager#getSubscriberId()}.
     *                     <p>Starting with API level 31, calling apps can provide a
     *                     {@code subscriberId} with wifi network type to receive usage for
     *                     wifi networks which is under the given subscription if applicable.
     *                     Otherwise, pass {@code null} when querying all wifi networks.
     * @param thresholdBytes Threshold in bytes to be notified on.
     * @param callback The {@link UsageCallback} that the system will call when data usage
     *            has exceeded the specified threshold.
     * @param handler to dispatch callback events through, otherwise if {@code null} it uses
     *            the calling thread.
     */
    public void registerUsageCallback(int networkType, String subscriberId, long thresholdBytes,
            UsageCallback callback, @Nullable Handler handler) {
        NetworkTemplate template = createTemplate(networkType, subscriberId);
        if (DBG) {
            Log.d(TAG, "registerUsageCallback called with: {"
                    + " networkType=" + networkType
                    + " subscriberId=" + subscriberId
                    + " thresholdBytes=" + thresholdBytes
                    + " }");
        }

        final Executor executor = handler == null ? r -> r.run() : r -> handler.post(r);

        registerUsageCallback(template, thresholdBytes, executor, callback);
    }

    /**
     * Unregisters callbacks on data usage.
     *
     * @param callback The {@link UsageCallback} used when registering.
     */
    public void unregisterUsageCallback(UsageCallback callback) {
        if (callback == null || callback.request == null
                || callback.request.requestId == DataUsageRequest.REQUEST_ID_UNSET) {
            throw new IllegalArgumentException("Invalid UsageCallback");
        }
        try {
            mService.unregisterUsageRequest(callback.request);
        } catch (RemoteException e) {
            if (DBG) Log.d(TAG, "Remote exception when unregistering callback");
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Base class for usage callbacks. Should be extended by applications wanting notifications.
     */
    public static abstract class UsageCallback {
        /**
         * Called when data usage has reached the given threshold.
         *
         * Called by {@code NetworkStatsService} when the registered threshold is reached.
         * If a caller implements {@link #onThresholdReached(NetworkTemplate)}, the system
         * will not call {@link #onThresholdReached(int, String)}.
         *
         * @param template The {@link NetworkTemplate} that associated with this callback.
         * @hide
         */
        @SystemApi(client = MODULE_LIBRARIES)
        public void onThresholdReached(@NonNull NetworkTemplate template) {
            // Backward compatibility for those who didn't override this function.
            final int networkType = networkTypeForTemplate(template);
            if (networkType != ConnectivityManager.TYPE_NONE) {
                final String subscriberId = template.getSubscriberIds().isEmpty() ? null
                        : template.getSubscriberIds().iterator().next();
                onThresholdReached(networkType, subscriberId);
            }
        }

        /**
         * Called when data usage has reached the given threshold.
         */
        public abstract void onThresholdReached(int networkType, String subscriberId);

        /**
         * @hide used for internal bookkeeping
         */
        private DataUsageRequest request;

        /**
         * Get network type from a template if feasible.
         *
         * @param template the target {@link NetworkTemplate}.
         * @return legacy network type, only supports for the types which is already supported in
         *         {@link #registerUsageCallback(int, String, long, UsageCallback, Handler)}.
         *         {@link ConnectivityManager#TYPE_NONE} for other types.
         */
        private static int networkTypeForTemplate(@NonNull NetworkTemplate template) {
            switch (template.getMatchRule()) {
                case NetworkTemplate.MATCH_MOBILE:
                    return ConnectivityManager.TYPE_MOBILE;
                case NetworkTemplate.MATCH_WIFI:
                    return ConnectivityManager.TYPE_WIFI;
                default:
                    return ConnectivityManager.TYPE_NONE;
            }
        }
    }

    /**
     * Registers a custom provider of {@link android.net.NetworkStats} to provide network statistics
     * to the system. To unregister, invoke {@link #unregisterNetworkStatsProvider}.
     * Note that no de-duplication of statistics between providers is performed, so each provider
     * must only report network traffic that is not being reported by any other provider. Also note
     * that the provider cannot be re-registered after unregistering.
     *
     * @param tag a human readable identifier of the custom network stats provider. This is only
     *            used for debugging.
     * @param provider the subclass of {@link NetworkStatsProvider} that needs to be
     *                 registered to the system.
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.NETWORK_STATS_PROVIDER,
            NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK})
    @NonNull public void registerNetworkStatsProvider(
            @NonNull String tag,
            @NonNull NetworkStatsProvider provider) {
        try {
            if (provider.getProviderCallbackBinder() != null) {
                throw new IllegalArgumentException("provider is already registered");
            }
            final INetworkStatsProviderCallback cbBinder =
                    mService.registerNetworkStatsProvider(tag, provider.getProviderBinder());
            provider.setProviderCallbackBinder(cbBinder);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * Unregisters an instance of {@link NetworkStatsProvider}.
     *
     * @param provider the subclass of {@link NetworkStatsProvider} that needs to be
     *                 unregistered to the system.
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.NETWORK_STATS_PROVIDER,
            NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK})
    @NonNull public void unregisterNetworkStatsProvider(@NonNull NetworkStatsProvider provider) {
        try {
            provider.getProviderCallbackBinderOrThrow().unregister();
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    private static NetworkTemplate createTemplate(int networkType, String subscriberId) {
        final NetworkTemplate template;
        switch (networkType) {
            case ConnectivityManager.TYPE_MOBILE:
                template = subscriberId == null
                        ? NetworkTemplate.buildTemplateMobileWildcard()
                        : NetworkTemplate.buildTemplateMobileAll(subscriberId);
                break;
            case ConnectivityManager.TYPE_WIFI:
                template = TextUtils.isEmpty(subscriberId)
                        ? NetworkTemplate.buildTemplateWifiWildcard()
                        : NetworkTemplate.buildTemplateWifi(NetworkTemplate.WIFI_NETWORKID_ALL,
                                subscriberId);
                break;
            default:
                throw new IllegalArgumentException("Cannot create template for network type "
                        + networkType + ", subscriberId '"
                        + NetworkIdentityUtils.scrubSubscriberId(subscriberId) + "'.");
        }
        return template;
    }

    /**
     * Notify {@code NetworkStatsService} about network status changed.
     *
     * Notifies NetworkStatsService of network state changes for data usage accounting purposes.
     *
     * To avoid races that attribute data usage to wrong network, such as new network with
     * the same interface after SIM hot-swap, this function will not return until
     * {@code NetworkStatsService} finishes its work of retrieving traffic statistics from
     * all data sources.
     *
     * @param defaultNetworks the list of all networks that could be used by network traffic that
     *                        does not explicitly select a network.
     * @param networkStateSnapshots a list of {@link NetworkStateSnapshot}s, one for
     *                              each network that is currently connected.
     * @param activeIface the active (i.e., connected) default network interface for the calling
     *                    uid. Used to determine on which network future calls to
     *                    {@link android.net.TrafficStats#incrementOperationCount} applies to.
     * @param underlyingNetworkInfos the list of underlying network information for all
     *                               currently-connected VPNs.
     *
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    @RequiresPermission(anyOf = {
            NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK,
            android.Manifest.permission.NETWORK_STACK})
    public void notifyNetworkStatus(
            @NonNull List<Network> defaultNetworks,
            @NonNull List<NetworkStateSnapshot> networkStateSnapshots,
            @Nullable String activeIface,
            @NonNull List<UnderlyingNetworkInfo> underlyingNetworkInfos) {
        try {
            Objects.requireNonNull(defaultNetworks);
            Objects.requireNonNull(networkStateSnapshots);
            Objects.requireNonNull(underlyingNetworkInfos);
            mService.notifyNetworkStatus(defaultNetworks.toArray(new Network[0]),
                    networkStateSnapshots.toArray(new NetworkStateSnapshot[0]), activeIface,
                    underlyingNetworkInfos.toArray(new UnderlyingNetworkInfo[0]));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private static class UsageCallbackWrapper extends IUsageCallback.Stub {
        // Null if unregistered.
        private volatile UsageCallback mCallback;

        private final Executor mExecutor;

        UsageCallbackWrapper(@NonNull Executor executor, @NonNull UsageCallback callback) {
            mCallback = callback;
            mExecutor = executor;
        }

        @Override
        public void onThresholdReached(DataUsageRequest request) {
            // Copy it to a local variable in case mCallback changed inside the if condition.
            final UsageCallback callback = mCallback;
            if (callback != null) {
                mExecutor.execute(() -> callback.onThresholdReached(request.template));
            } else {
                Log.e(TAG, "onThresholdReached with released callback for " + request);
            }
        }

        @Override
        public void onCallbackReleased(DataUsageRequest request) {
            if (DBG) Log.d(TAG, "callback released for " + request);
            mCallback = null;
        }
    }

    /**
     * Mark given UID as being in foreground for stats purposes.
     *
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    @RequiresPermission(anyOf = {
            NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK,
            android.Manifest.permission.NETWORK_STACK})
    public void noteUidForeground(int uid, boolean uidForeground) {
        try {
            mService.noteUidForeground(uid, uidForeground);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set default value of global alert bytes, the value will be clamped to [128kB, 2MB].
     *
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    @RequiresPermission(anyOf = {
            NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK,
            Manifest.permission.NETWORK_STACK})
    public void setDefaultGlobalAlert(long alertBytes) {
        try {
            // TODO: Sync internal naming with the API surface.
            mService.advisePersistThreshold(alertBytes);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Force update of statistics.
     *
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    @RequiresPermission(anyOf = {
            NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK,
            android.Manifest.permission.NETWORK_STACK})
    public void forceUpdate() {
        try {
            mService.forceUpdate();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set the warning and limit to all registered custom network stats providers.
     * Note that invocation of any interface will be sent to all providers.
     *
     * Asynchronicity notes : because traffic may be happening on the device at the same time, it
     * doesn't make sense to wait for the warning and limit to be set – a caller still wouldn't
     * know when exactly it was effective. All that can matter is that it's done quickly. Also,
     * this method can't fail, so there is no status to return. All providers will see the new
     * values soon.
     * As such, this method returns immediately and sends the warning and limit to all providers
     * as soon as possible through a one-way binder call.
     *
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    @RequiresPermission(anyOf = {
            NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK,
            android.Manifest.permission.NETWORK_STACK})
    public void setStatsProviderWarningAndLimitAsync(@NonNull String iface, long warning,
            long limit) {
        try {
            mService.setStatsProviderWarningAndLimitAsync(iface, warning, limit);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get a RAT type representative of a group of RAT types for network statistics.
     *
     * Collapse the given Radio Access Technology (RAT) type into a bucket that
     * is representative of the original RAT type for network statistics. The
     * mapping mostly corresponds to {@code TelephonyManager#NETWORK_CLASS_BIT_MASK_*}
     * but with adaptations specific to the virtual types introduced by
     * networks stats.
     *
     * @param ratType An integer defined in {@code TelephonyManager#NETWORK_TYPE_*}.
     *
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public static int getCollapsedRatType(int ratType) {
        switch (ratType) {
            case TelephonyManager.NETWORK_TYPE_GPRS:
            case TelephonyManager.NETWORK_TYPE_GSM:
            case TelephonyManager.NETWORK_TYPE_EDGE:
            case TelephonyManager.NETWORK_TYPE_IDEN:
            case TelephonyManager.NETWORK_TYPE_CDMA:
            case TelephonyManager.NETWORK_TYPE_1xRTT:
                return TelephonyManager.NETWORK_TYPE_GSM;
            case TelephonyManager.NETWORK_TYPE_EVDO_0:
            case TelephonyManager.NETWORK_TYPE_EVDO_A:
            case TelephonyManager.NETWORK_TYPE_EVDO_B:
            case TelephonyManager.NETWORK_TYPE_EHRPD:
            case TelephonyManager.NETWORK_TYPE_UMTS:
            case TelephonyManager.NETWORK_TYPE_HSDPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_HSPA:
            case TelephonyManager.NETWORK_TYPE_HSPAP:
            case TelephonyManager.NETWORK_TYPE_TD_SCDMA:
                return TelephonyManager.NETWORK_TYPE_UMTS;
            case TelephonyManager.NETWORK_TYPE_LTE:
            case TelephonyManager.NETWORK_TYPE_IWLAN:
                return TelephonyManager.NETWORK_TYPE_LTE;
            case TelephonyManager.NETWORK_TYPE_NR:
                return TelephonyManager.NETWORK_TYPE_NR;
            // Virtual RAT type for 5G NSA mode, see
            // {@link NetworkStatsManager#NETWORK_TYPE_5G_NSA}.
            case NetworkStatsManager.NETWORK_TYPE_5G_NSA:
                return NetworkStatsManager.NETWORK_TYPE_5G_NSA;
            default:
                return TelephonyManager.NETWORK_TYPE_UNKNOWN;
        }
    }
}
