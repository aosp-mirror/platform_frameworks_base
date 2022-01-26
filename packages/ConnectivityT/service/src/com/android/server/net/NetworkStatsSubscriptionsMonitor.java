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

package com.android.server.net;

import static android.app.usage.NetworkStatsManager.NETWORK_TYPE_5G_NSA;
import static android.app.usage.NetworkStatsManager.getCollapsedRatType;
import static android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED;
import static android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA;
import static android.telephony.TelephonyManager.NETWORK_TYPE_LTE;

import android.annotation.NonNull;
import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * Helper class that watches for events that are triggered per subscription.
 */
@TargetApi(Build.VERSION_CODES.TIRAMISU)
public class NetworkStatsSubscriptionsMonitor extends
        SubscriptionManager.OnSubscriptionsChangedListener {

    /**
     * Interface that this monitor uses to delegate event handling to NetworkStatsService.
     */
    public interface Delegate {
        /**
         * Notify that the collapsed RAT type has been changed for any subscription. The method
         * will also be triggered for any existing sub when start and stop monitoring.
         *
         * @param subscriberId IMSI of the subscription.
         * @param collapsedRatType collapsed RAT type.
         *                     @see android.app.usage.NetworkStatsManager#getCollapsedRatType(int).
         */
        void onCollapsedRatTypeChanged(@NonNull String subscriberId, int collapsedRatType);
    }
    private final Delegate mDelegate;

    /**
     * Receivers that watches for {@link TelephonyDisplayInfo} changes for each subscription, to
     * monitor the transitioning between Radio Access Technology(RAT) types for each sub.
     */
    @NonNull
    private final CopyOnWriteArrayList<RatTypeListener> mRatListeners =
            new CopyOnWriteArrayList<>();

    @NonNull
    private final SubscriptionManager mSubscriptionManager;
    @NonNull
    private final TelephonyManager mTeleManager;

    @NonNull
    private final Executor mExecutor;

    NetworkStatsSubscriptionsMonitor(@NonNull Context context,
            @NonNull Executor executor, @NonNull Delegate delegate) {
        super();
        mSubscriptionManager = (SubscriptionManager) context.getSystemService(
                Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        mTeleManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        mExecutor = executor;
        mDelegate = delegate;
    }

    @Override
    public void onSubscriptionsChanged() {
        // Collect active subId list, hidden subId such as opportunistic subscriptions are
        // also needed to track CBRS.
        final List<Integer> newSubs = getActiveSubIdList(mSubscriptionManager);

        // IMSI is needed for every newly added sub. Listener stores subscriberId into it to
        // prevent binder call to telephony when querying RAT. Keep listener registration with empty
        // IMSI is meaningless since the RAT type changed is ambiguous for multi-SIM if reported
        // with empty IMSI. So filter the subs w/o a valid IMSI to prevent such registration.
        final List<Pair<Integer, String>> filteredNewSubs = new ArrayList<>();
        for (final int subId : newSubs) {
            final String subscriberId =
                    mTeleManager.createForSubscriptionId(subId).getSubscriberId();
            if (!TextUtils.isEmpty(subscriberId)) {
                filteredNewSubs.add(new Pair(subId, subscriberId));
            }
        }

        for (final Pair<Integer, String> sub : filteredNewSubs) {
            // Fully match listener with subId and IMSI, since in some rare cases, IMSI might be
            // suddenly change regardless of subId, such as switch IMSI feature in modem side.
            // If that happens, register new listener with new IMSI and remove old one later.
            if (CollectionUtils.any(mRatListeners, it -> it.equalsKey(sub.first, sub.second))) {
                continue;
            }

            final RatTypeListener listener = new RatTypeListener(this, sub.first, sub.second);
            mRatListeners.add(listener);

            // Register listener to the telephony manager that associated with specific sub.
            mTeleManager.createForSubscriptionId(sub.first)
                    .registerTelephonyCallback(mExecutor, listener);
            Log.d(NetworkStatsService.TAG, "RAT type listener registered for sub " + sub.first);
        }

        for (final RatTypeListener listener : new ArrayList<>(mRatListeners)) {
            // If there is no subId and IMSI matched the listener, removes it.
            if (!CollectionUtils.any(filteredNewSubs,
                    it -> listener.equalsKey(it.first, it.second))) {
                handleRemoveRatTypeListener(listener);
            }
        }
    }

    @NonNull
    private List<Integer> getActiveSubIdList(@NonNull SubscriptionManager subscriptionManager) {
        final ArrayList<Integer> ret = new ArrayList<>();
        final int[] ids = subscriptionManager.getCompleteActiveSubscriptionIdList();
        for (int id : ids) ret.add(id);
        return ret;
    }

    /**
     * Get a collapsed RatType for the given subscriberId.
     *
     * @param subscriberId the target subscriberId
     * @return collapsed RatType for the given subscriberId
     */
    public int getRatTypeForSubscriberId(@NonNull String subscriberId) {
        final int index = CollectionUtils.indexOf(mRatListeners,
                it -> TextUtils.equals(subscriberId, it.mSubscriberId));
        return index != -1 ? mRatListeners.get(index).mLastCollapsedRatType
                : TelephonyManager.NETWORK_TYPE_UNKNOWN;
    }

    /**
     * Start monitoring events that triggered per subscription.
     */
    public void start() {
        mSubscriptionManager.addOnSubscriptionsChangedListener(mExecutor, this);
    }

    /**
     * Unregister subscription changes and all listeners for each subscription.
     */
    public void stop() {
        mSubscriptionManager.removeOnSubscriptionsChangedListener(this);

        for (final RatTypeListener listener : new ArrayList<>(mRatListeners)) {
            handleRemoveRatTypeListener(listener);
        }
    }

    private void handleRemoveRatTypeListener(@NonNull RatTypeListener listener) {
        mTeleManager.createForSubscriptionId(listener.mSubId)
                .unregisterTelephonyCallback(listener);
        Log.d(NetworkStatsService.TAG, "RAT type listener unregistered for sub " + listener.mSubId);
        mRatListeners.remove(listener);

        // Removal of subscriptions doesn't generate RAT changed event, fire it for every
        // RatTypeListener.
        mDelegate.onCollapsedRatTypeChanged(
                listener.mSubscriberId, TelephonyManager.NETWORK_TYPE_UNKNOWN);
    }

    static class RatTypeListener extends TelephonyCallback
            implements TelephonyCallback.DisplayInfoListener {
        // Unique id for the subscription. See {@link SubscriptionInfo#getSubscriptionId}.
        @NonNull
        private final int mSubId;

        // IMSI to identifying the corresponding network from {@link NetworkState}.
        // See {@link TelephonyManager#getSubscriberId}.
        @NonNull
        private final String mSubscriberId;

        private volatile int mLastCollapsedRatType = TelephonyManager.NETWORK_TYPE_UNKNOWN;
        @NonNull
        private final NetworkStatsSubscriptionsMonitor mMonitor;

        RatTypeListener(@NonNull NetworkStatsSubscriptionsMonitor monitor, int subId,
                @NonNull String subscriberId) {
            mSubId = subId;
            mSubscriberId = subscriberId;
            mMonitor = monitor;
        }

        @Override
        public void onDisplayInfoChanged(TelephonyDisplayInfo displayInfo) {
            // In 5G SA (Stand Alone) mode, the primary cell itself will be 5G hence telephony
            // would report RAT = 5G_NR.
            // However, in 5G NSA (Non Stand Alone) mode, the primary cell is still LTE and
            // network allocates a secondary 5G cell so telephony reports RAT = LTE along with
            // NR state as connected. In such case, attributes the data usage to NR.
            // See b/160727498.
            final boolean is5GNsa = displayInfo.getNetworkType() == NETWORK_TYPE_LTE
                    && (displayInfo.getOverrideNetworkType() == OVERRIDE_NETWORK_TYPE_NR_NSA
                    || displayInfo.getOverrideNetworkType() == OVERRIDE_NETWORK_TYPE_NR_ADVANCED);

            final int networkType =
                    (is5GNsa ? NETWORK_TYPE_5G_NSA : displayInfo.getNetworkType());
            final int collapsedRatType = getCollapsedRatType(networkType);
            if (collapsedRatType == mLastCollapsedRatType) return;

            if (NetworkStatsService.LOGD) {
                Log.d(NetworkStatsService.TAG, "subtype changed for sub(" + mSubId + "): "
                        + mLastCollapsedRatType + " -> " + collapsedRatType);
            }
            mLastCollapsedRatType = collapsedRatType;
            mMonitor.mDelegate.onCollapsedRatTypeChanged(mSubscriberId, mLastCollapsedRatType);
        }

        @VisibleForTesting
        public int getSubId() {
            return mSubId;
        }

        boolean equalsKey(int subId, @NonNull String subscriberId) {
            return mSubId == subId && TextUtils.equals(mSubscriberId, subscriberId);
        }
    }
}
