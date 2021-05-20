/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.connectivity;

import static android.net.ConnectivityManager.NETID_UNSET;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.ConnectivityResources;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import com.android.connectivity.resources.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.MessageUtils;
import com.android.server.connectivity.NetworkNotificationManager.NotificationType;

import java.util.Arrays;
import java.util.HashMap;

/**
 * Class that monitors default network linger events and possibly notifies the user of network
 * switches.
 *
 * This class is not thread-safe and all its methods must be called on the ConnectivityService
 * handler thread.
 */
public class LingerMonitor {

    private static final boolean DBG = true;
    private static final boolean VDBG = false;
    private static final String TAG = LingerMonitor.class.getSimpleName();

    public static final int DEFAULT_NOTIFICATION_DAILY_LIMIT = 3;
    public static final long DEFAULT_NOTIFICATION_RATE_LIMIT_MILLIS = DateUtils.MINUTE_IN_MILLIS;

    private static final HashMap<String, Integer> TRANSPORT_NAMES = makeTransportToNameMap();
    @VisibleForTesting
    public static final Intent CELLULAR_SETTINGS = new Intent().setComponent(new ComponentName(
            "com.android.settings", "com.android.settings.Settings$DataUsageSummaryActivity"));

    @VisibleForTesting
    public static final int NOTIFY_TYPE_NONE         = 0;
    public static final int NOTIFY_TYPE_NOTIFICATION = 1;
    public static final int NOTIFY_TYPE_TOAST        = 2;

    private static SparseArray<String> sNotifyTypeNames = MessageUtils.findMessageNames(
            new Class[] { LingerMonitor.class }, new String[]{ "NOTIFY_TYPE_" });

    private final Context mContext;
    final Resources mResources;
    private final NetworkNotificationManager mNotifier;
    private final int mDailyLimit;
    private final long mRateLimitMillis;

    private long mFirstNotificationMillis;
    private long mLastNotificationMillis;
    private int mNotificationCounter;

    /** Current notifications. Maps the netId we switched away from to the netId we switched to. */
    private final SparseIntArray mNotifications = new SparseIntArray();

    /** Whether we ever notified that we switched away from a particular network. */
    private final SparseBooleanArray mEverNotified = new SparseBooleanArray();

    public LingerMonitor(Context context, NetworkNotificationManager notifier,
            int dailyLimit, long rateLimitMillis) {
        mContext = context;
        mResources = new ConnectivityResources(mContext).get();
        mNotifier = notifier;
        mDailyLimit = dailyLimit;
        mRateLimitMillis = rateLimitMillis;
        // Ensure that (now - mLastNotificationMillis) >= rateLimitMillis at first
        mLastNotificationMillis = -rateLimitMillis;
    }

    private static HashMap<String, Integer> makeTransportToNameMap() {
        SparseArray<String> numberToName = MessageUtils.findMessageNames(
            new Class[] { NetworkCapabilities.class }, new String[]{ "TRANSPORT_" });
        HashMap<String, Integer> nameToNumber = new HashMap<>();
        for (int i = 0; i < numberToName.size(); i++) {
            // MessageUtils will fail to initialize if there are duplicate constant values, so there
            // are no duplicates here.
            nameToNumber.put(numberToName.valueAt(i), numberToName.keyAt(i));
        }
        return nameToNumber;
    }

    private static boolean hasTransport(NetworkAgentInfo nai, int transport) {
        return nai.networkCapabilities.hasTransport(transport);
    }

    private int getNotificationSource(NetworkAgentInfo toNai) {
        for (int i = 0; i < mNotifications.size(); i++) {
            if (mNotifications.valueAt(i) == toNai.network.getNetId()) {
                return mNotifications.keyAt(i);
            }
        }
        return NETID_UNSET;
    }

    private boolean everNotified(NetworkAgentInfo nai) {
        return mEverNotified.get(nai.network.getNetId(), false);
    }

    @VisibleForTesting
    public boolean isNotificationEnabled(NetworkAgentInfo fromNai, NetworkAgentInfo toNai) {
        // TODO: Evaluate moving to CarrierConfigManager.
        String[] notifySwitches = mResources.getStringArray(R.array.config_networkNotifySwitches);

        if (VDBG) {
            Log.d(TAG, "Notify on network switches: " + Arrays.toString(notifySwitches));
        }

        for (String notifySwitch : notifySwitches) {
            if (TextUtils.isEmpty(notifySwitch)) continue;
            String[] transports = notifySwitch.split("-", 2);
            if (transports.length != 2) {
                Log.e(TAG, "Invalid network switch notification configuration: " + notifySwitch);
                continue;
            }
            int fromTransport = TRANSPORT_NAMES.get("TRANSPORT_" + transports[0]);
            int toTransport = TRANSPORT_NAMES.get("TRANSPORT_" + transports[1]);
            if (hasTransport(fromNai, fromTransport) && hasTransport(toNai, toTransport)) {
                return true;
            }
        }

        return false;
    }

    private void showNotification(NetworkAgentInfo fromNai, NetworkAgentInfo toNai) {
        mNotifier.showNotification(fromNai.network.getNetId(), NotificationType.NETWORK_SWITCH,
                fromNai, toNai, createNotificationIntent(), true);
    }

    @VisibleForTesting
    protected PendingIntent createNotificationIntent() {
        return PendingIntent.getActivity(
                mContext.createContextAsUser(UserHandle.CURRENT, 0 /* flags */),
                0 /* requestCode */,
                CELLULAR_SETTINGS,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // Removes any notification that was put up as a result of switching to nai.
    private void maybeStopNotifying(NetworkAgentInfo nai) {
        int fromNetId = getNotificationSource(nai);
        if (fromNetId != NETID_UNSET) {
            mNotifications.delete(fromNetId);
            mNotifier.clearNotification(fromNetId);
            // Toasts can't be deleted.
        }
    }

    // Notify the user of a network switch using a notification or a toast.
    private void notify(NetworkAgentInfo fromNai, NetworkAgentInfo toNai, boolean forceToast) {
        int notifyType = mResources.getInteger(R.integer.config_networkNotifySwitchType);
        if (notifyType == NOTIFY_TYPE_NOTIFICATION && forceToast) {
            notifyType = NOTIFY_TYPE_TOAST;
        }

        if (VDBG) {
            Log.d(TAG, "Notify type: " + sNotifyTypeNames.get(notifyType, "" + notifyType));
        }

        switch (notifyType) {
            case NOTIFY_TYPE_NONE:
                return;
            case NOTIFY_TYPE_NOTIFICATION:
                showNotification(fromNai, toNai);
                break;
            case NOTIFY_TYPE_TOAST:
                mNotifier.showToast(fromNai, toNai);
                break;
            default:
                Log.e(TAG, "Unknown notify type " + notifyType);
                return;
        }

        if (DBG) {
            Log.d(TAG, "Notifying switch from=" + fromNai.toShortString()
                    + " to=" + toNai.toShortString()
                    + " type=" + sNotifyTypeNames.get(notifyType, "unknown(" + notifyType + ")"));
        }

        mNotifications.put(fromNai.network.getNetId(), toNai.network.getNetId());
        mEverNotified.put(fromNai.network.getNetId(), true);
    }

    /**
     * Put up or dismiss a notification or toast for of a change in the default network if needed.
     *
     * Putting up a notification when switching from no network to some network is not supported
     * and as such this method can't be called with a null |fromNai|. It can be called with a
     * null |toNai| if there isn't a default network any more.
     *
     * @param fromNai switching from this NAI
     * @param toNai switching to this NAI
     */
    // The default network changed from fromNai to toNai due to a change in score.
    public void noteLingerDefaultNetwork(@NonNull final NetworkAgentInfo fromNai,
            @Nullable final NetworkAgentInfo toNai) {
        if (VDBG) {
            Log.d(TAG, "noteLingerDefaultNetwork from=" + fromNai.toShortString()
                    + " everValidated=" + fromNai.everValidated
                    + " lastValidated=" + fromNai.lastValidated
                    + " to=" + toNai.toShortString());
        }

        // If we are currently notifying the user because the device switched to fromNai, now that
        // we are switching away from it we should remove the notification. This includes the case
        // where we switch back to toNai because its score improved again (e.g., because it regained
        // Internet access).
        maybeStopNotifying(fromNai);

        // If the network was simply lost (either because it disconnected or because it stopped
        // being the default with no replacement), then don't show a notification.
        if (null == toNai) return;

        // If this network never validated, don't notify. Otherwise, we could do things like:
        //
        // 1. Unvalidated wifi connects.
        // 2. Unvalidated mobile data connects.
        // 3. Cell validates, and we show a notification.
        // or:
        // 1. User connects to wireless printer.
        // 2. User turns on cellular data.
        // 3. We show a notification.
        if (!fromNai.everValidated) return;

        // If this network is a captive portal, don't notify. This cannot happen on initial connect
        // to a captive portal, because the everValidated check above will fail. However, it can
        // happen if the captive portal reasserts itself (e.g., because its timeout fires). In that
        // case, as soon as the captive portal reasserts itself, we'll show a sign-in notification.
        // We don't want to overwrite that notification with this one; the user has already been
        // notified, and of the two, the captive portal notification is the more useful one because
        // it allows the user to sign in to the captive portal. In this case, display a toast
        // in addition to the captive portal notification.
        //
        // Note that if the network we switch to is already up when the captive portal reappears,
        // this won't work because NetworkMonitor tells ConnectivityService that the network is
        // unvalidated (causing a switch) before asking it to show the sign in notification. In this
        // case, the toast won't show and we'll only display the sign in notification. This is the
        // best we can do at this time.
        boolean forceToast = fromNai.networkCapabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);

        // Only show the notification once, in order to avoid irritating the user every time.
        // TODO: should we do this?
        if (everNotified(fromNai)) {
            if (VDBG) {
                Log.d(TAG, "Not notifying handover from " + fromNai.toShortString()
                        + ", already notified");
            }
            return;
        }

        // Only show the notification if we switched away because a network became unvalidated, not
        // because its score changed.
        // TODO: instead of just skipping notification, keep a note of it, and show it if it becomes
        // unvalidated.
        if (fromNai.lastValidated) return;

        if (!isNotificationEnabled(fromNai, toNai)) return;

        final long now = SystemClock.elapsedRealtime();
        if (isRateLimited(now) || isAboveDailyLimit(now)) return;

        notify(fromNai, toNai, forceToast);
    }

    public void noteDisconnect(NetworkAgentInfo nai) {
        mNotifications.delete(nai.network.getNetId());
        mEverNotified.delete(nai.network.getNetId());
        maybeStopNotifying(nai);
        // No need to cancel notifications on nai: NetworkMonitor does that on disconnect.
    }

    private boolean isRateLimited(long now) {
        final long millisSinceLast = now - mLastNotificationMillis;
        if (millisSinceLast < mRateLimitMillis) {
            return true;
        }
        mLastNotificationMillis = now;
        return false;
    }

    private boolean isAboveDailyLimit(long now) {
        if (mFirstNotificationMillis == 0) {
            mFirstNotificationMillis = now;
        }
        final long millisSinceFirst = now - mFirstNotificationMillis;
        if (millisSinceFirst > DateUtils.DAY_IN_MILLIS) {
            mNotificationCounter = 0;
            mFirstNotificationMillis = 0;
        }
        if (mNotificationCounter >= mDailyLimit) {
            return true;
        }
        mNotificationCounter++;
        return false;
    }
}
