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

import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.Nullable;
import android.annotation.SystemService;
import android.app.usage.NetworkStats.Bucket;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.DataUsageRequest;
import android.net.INetworkStatsService;
import android.net.NetworkIdentity;
import android.net.NetworkTemplate;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

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

    private final Context mContext;
    private final INetworkStatsService mService;

    /** @hide */
    public static final int FLAG_POLL_ON_OPEN = 1 << 0;
    /** @hide */
    public static final int FLAG_AUGMENT_WITH_SUBSCRIPTION_PLAN = 1 << 1;

    private int mFlags;

    /**
     * {@hide}
     */
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
     *
     * @param networkType As defined in {@link ConnectivityManager}, e.g.
     *            {@link ConnectivityManager#TYPE_MOBILE}, {@link ConnectivityManager#TYPE_WIFI}
     *            etc.
     * @param subscriberId If applicable, the subscriber id of the network interface.
     * @param startTime Start of period. Defined in terms of "Unix time", see
     *            {@link java.lang.System#currentTimeMillis}.
     * @param endTime End of period. Defined in terms of "Unix time", see
     *            {@link java.lang.System#currentTimeMillis}.
     * @return Bucket object or null if permissions are insufficient or error happened during
     *         statistics collection.
     */
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
     *
     * @param networkType As defined in {@link ConnectivityManager}, e.g.
     *            {@link ConnectivityManager#TYPE_MOBILE}, {@link ConnectivityManager#TYPE_WIFI}
     *            etc.
     * @param subscriberId If applicable, the subscriber id of the network interface.
     * @param startTime Start of period. Defined in terms of "Unix time", see
     *            {@link java.lang.System#currentTimeMillis}.
     * @param endTime End of period. Defined in terms of "Unix time", see
     *            {@link java.lang.System#currentTimeMillis}.
     * @return Bucket object or null if permissions are insufficient or error happened during
     *         statistics collection.
     */
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
     *
     * @param networkType As defined in {@link ConnectivityManager}, e.g.
     *            {@link ConnectivityManager#TYPE_MOBILE}, {@link ConnectivityManager#TYPE_WIFI}
     *            etc.
     * @param subscriberId If applicable, the subscriber id of the network interface.
     * @param startTime Start of period. Defined in terms of "Unix time", see
     *            {@link java.lang.System#currentTimeMillis}.
     * @param endTime End of period. Defined in terms of "Unix time", see
     *            {@link java.lang.System#currentTimeMillis}.
     * @return Statistics object or null if permissions are insufficient or error happened during
     *         statistics collection.
     */
    public NetworkStats querySummary(int networkType, String subscriberId, long startTime,
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
        result.startSummaryEnumeration();

        return result;
    }

    /**
     * Query network usage statistics details for a given uid.
     *
     * #see queryDetailsForUidTagState(int, String, long, long, int, int, int)
     */
    public NetworkStats queryDetailsForUid(int networkType, String subscriberId,
            long startTime, long endTime, int uid) throws SecurityException {
        return queryDetailsForUidTagState(networkType, subscriberId, startTime, endTime, uid,
            NetworkStats.Bucket.TAG_NONE, NetworkStats.Bucket.STATE_ALL);
    }

    /**
     * Query network usage statistics details for a given uid and tag.
     *
     * #see queryDetailsForUidTagState(int, String, long, long, int, int, int)
     */
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
     *
     * @param networkType As defined in {@link ConnectivityManager}, e.g.
     *            {@link ConnectivityManager#TYPE_MOBILE}, {@link ConnectivityManager#TYPE_WIFI}
     *            etc.
     * @param subscriberId If applicable, the subscriber id of the network interface.
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
    public NetworkStats queryDetailsForUidTagState(int networkType, String subscriberId,
            long startTime, long endTime, int uid, int tag, int state) throws SecurityException {
        NetworkTemplate template;
        template = createTemplate(networkType, subscriberId);

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
     *
     * @param networkType As defined in {@link ConnectivityManager}, e.g.
     *            {@link ConnectivityManager#TYPE_MOBILE}, {@link ConnectivityManager#TYPE_WIFI}
     *            etc.
     * @param subscriberId If applicable, the subscriber id of the network interface.
     * @param startTime Start of period. Defined in terms of "Unix time", see
     *            {@link java.lang.System#currentTimeMillis}.
     * @param endTime End of period. Defined in terms of "Unix time", see
     *            {@link java.lang.System#currentTimeMillis}.
     * @return Statistics object or null if permissions are insufficient or error happened during
     *         statistics collection.
     */
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
        checkNotNull(callback, "UsageCallback cannot be null");

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
     * #see registerUsageCallback(int, String[], long, UsageCallback, Handler)
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

    private static NetworkTemplate createTemplate(int networkType, String subscriberId) {
        final NetworkTemplate template;
        switch (networkType) {
            case ConnectivityManager.TYPE_MOBILE:
                template = subscriberId == null
                        ? NetworkTemplate.buildTemplateMobileWildcard()
                        : NetworkTemplate.buildTemplateMobileAll(subscriberId);
                break;
            case ConnectivityManager.TYPE_WIFI:
                template = NetworkTemplate.buildTemplateWifiWildcard();
                break;
            default:
                throw new IllegalArgumentException("Cannot create template for network type "
                        + networkType + ", subscriberId '"
                        + NetworkIdentity.scrubSubscriberId(subscriberId) + "'.");
        }
        return template;
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
