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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
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
import android.net.netstats.provider.INetworkStatsProviderCallback;
import android.net.netstats.provider.NetworkStatsProvider;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.telephony.TelephonyManager;
import android.util.DataUnit;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.NetworkIdentityUtils;

import java.util.List;
import java.util.Objects;

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
    public static final long MIN_THRESHOLD_BYTES = DataUnit.MEBIBYTES.toBytes(2);

    private final Context mContext;
    private final INetworkStatsService mService;

    /** @hide */
    public static final int FLAG_POLL_ON_OPEN = 1 << 0;
    /** @hide */
    public static final int FLAG_POLL_FORCE = 1 << 1;
    /** @hide */
    public static final int FLAG_AUGMENT_WITH_SUBSCRIPTION_PLAN = 1 << 2;

    private int mFlags;

    /**
     * {@hide}
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public NetworkStatsManager(Context context) throws ServiceNotFoundException {
        this(context, INetworkStatsService.Stub.asInterface(
                ServiceManager.getServiceOrThrow(Context.NETWORK_STATS_SERVICE)));
    }

    /** @hide */
    @VisibleForTesting
    public NetworkStatsManager(Context context, INetworkStatsService service) {
        mContext = context;
        mService = service;
        setPollOnOpen(true);
    }

    /** @hide */
    public void setPollOnOpen(boolean pollOnOpen) {
        if (pollOnOpen) {
            mFlags |= FLAG_POLL_ON_OPEN;
        } else {
            mFlags &= ~FLAG_POLL_ON_OPEN;
        }
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @TestApi
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

    /** @hide */
    public Bucket querySummaryForDevice(NetworkTemplate template,
            long startTime, long endTime) throws SecurityException, RemoteException {
        Bucket bucket = null;
        NetworkStats stats = new NetworkStats(mContext, template, mFlags, startTime, endTime,
                mService);
        bucket = stats.getDeviceSummaryForNetwork();

        stats.close();
        return bucket;
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

    /** @hide */
    public NetworkStats querySummary(NetworkTemplate template, long startTime,
            long endTime) throws SecurityException, RemoteException {
        NetworkStats result;
        result = new NetworkStats(mContext, template, mFlags, startTime, endTime, mService);
        result.startSummaryEnumeration();

        return result;
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
     * @param startTime Start of period. Defined in terms of "Unix time", see
     *            {@link java.lang.System#currentTimeMillis}.
     * @param endTime End of period. Defined in terms of "Unix time", see
     *            {@link java.lang.System#currentTimeMillis}.
     * @param uid UID of app
     * @param tag TAG of interest. Use {@link NetworkStats.Bucket#TAG_NONE} for no tags.
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

    /** @hide */
    public NetworkStats queryDetailsForUidTagState(NetworkTemplate template,
            long startTime, long endTime, int uid, int tag, int state) throws SecurityException {

        NetworkStats result;
        try {
            result = new NetworkStats(mContext, template, mFlags, startTime, endTime, mService);
            result.startHistoryEnumeration(uid, tag, state);
        } catch (RemoteException e) {
            Log.e(TAG, "Error while querying stats for uid=" + uid + " tag=" + tag
                    + " state=" + state, e);
            return null;
        }

        return result;
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

    /** @hide */
    public void registerUsageCallback(NetworkTemplate template, int networkType,
            long thresholdBytes, UsageCallback callback, @Nullable Handler handler) {
        Objects.requireNonNull(callback, "UsageCallback cannot be null");

        final Looper looper;
        if (handler == null) {
            looper = Looper.myLooper();
        } else {
            looper = handler.getLooper();
        }

        DataUsageRequest request = new DataUsageRequest(DataUsageRequest.REQUEST_ID_UNSET,
                template, thresholdBytes);
        try {
            CallbackHandler callbackHandler = new CallbackHandler(looper, networkType,
                    template.getSubscriberId(), callback);
            callback.request = mService.registerUsageCallback(
                    mContext.getOpPackageName(), request, new Messenger(callbackHandler),
                    new Binder());
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
        registerUsageCallback(template, networkType, thresholdBytes, callback, handler);
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
         */
        public abstract void onThresholdReached(int networkType, String subscriberId);

        /**
         * @hide used for internal bookkeeping
         */
        private DataUsageRequest request;
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
                template = subscriberId == null
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
     *  Notify {@code NetworkStatsService} about network status changed.
     *
     *  Notifies NetworkStatsService of network state changes for data usage accounting purposes.
     *
     *  To avoid races that attribute data usage to wrong network, such as new network with
     *  the same interface after SIM hot-swap, this function will not return until
     *  {@code NetworkStatsService} finishes its work of retrieving traffic statistics from
     *  all data sources.
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
            // TODO: Change internal namings after the name is decided.
            mService.forceUpdateIfaces(defaultNetworks.toArray(new Network[0]),
                    networkStateSnapshots.toArray(new NetworkStateSnapshot[0]), activeIface,
                    underlyingNetworkInfos.toArray(new UnderlyingNetworkInfo[0]));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private static class CallbackHandler extends Handler {
        private final int mNetworkType;
        private final String mSubscriberId;
        private UsageCallback mCallback;

        CallbackHandler(Looper looper, int networkType, String subscriberId,
                UsageCallback callback) {
            super(looper);
            mNetworkType = networkType;
            mSubscriberId = subscriberId;
            mCallback = callback;
        }

        @Override
        public void handleMessage(Message message) {
            DataUsageRequest request =
                    (DataUsageRequest) getObject(message, DataUsageRequest.PARCELABLE_KEY);

            switch (message.what) {
                case CALLBACK_LIMIT_REACHED: {
                    if (mCallback != null) {
                        mCallback.onThresholdReached(mNetworkType, mSubscriberId);
                    } else {
                        Log.e(TAG, "limit reached with released callback for " + request);
                    }
                    break;
                }
                case CALLBACK_RELEASED: {
                    if (DBG) Log.d(TAG, "callback released for " + request);
                    mCallback = null;
                    break;
                }
            }
        }

        private static Object getObject(Message msg, String key) {
            return msg.getData().getParcelable(key);
        }
    }
}
