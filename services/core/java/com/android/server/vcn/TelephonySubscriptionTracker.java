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

package com.android.server.vcn;

import static android.telephony.CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED;
import static android.telephony.CarrierConfigManager.EXTRA_SLOT_INDEX;
import static android.telephony.CarrierConfigManager.EXTRA_SUBSCRIPTION_INDEX;
import static android.telephony.SubscriptionManager.INVALID_SIM_SLOT_INDEX;
import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.ParcelUuid;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionManager.OnSubscriptionsChangedListener;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.annotations.VisibleForTesting.Visibility;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;

/**
 * TelephonySubscriptionTracker provides a caching layer for tracking active subscription groups.
 *
 * <p>This class performs two roles:
 *
 * <ol>
 *   <li>De-noises subscription changes by ensuring that only changes in active and ready
 *       subscription groups are acted upon
 *   <li>Caches mapping between subIds and subscription groups
 * </ol>
 *
 * <p>An subscription group is active and ready if any of its contained subIds has had BOTH the
 * {@link CarrierConfigManager#isConfigForIdentifiedCarrier()} return true, AND the subscription is
 * listed as active per SubscriptionManager#getAllSubscriptionInfoList().
 *
 * <p>Note that due to the asynchronous nature of callbacks and broadcasts, the output of this class
 * is (only) eventually consistent.
 *
 * @hide
 */
public class TelephonySubscriptionTracker extends BroadcastReceiver {
    @NonNull private static final String TAG = TelephonySubscriptionTracker.class.getSimpleName();
    private static final boolean LOG_DBG = false; // STOPSHIP if true

    @NonNull private final Context mContext;
    @NonNull private final Handler mHandler;
    @NonNull private final TelephonySubscriptionTrackerCallback mCallback;
    @NonNull private final Dependencies mDeps;

    @NonNull private final SubscriptionManager mSubscriptionManager;
    @NonNull private final CarrierConfigManager mCarrierConfigManager;

    // TODO (Android T+): Add ability to handle multiple subIds per slot.
    @NonNull private final Map<Integer, Integer> mReadySubIdsBySlotId = new HashMap<>();
    @NonNull private final OnSubscriptionsChangedListener mSubscriptionChangedListener;

    @NonNull private TelephonySubscriptionSnapshot mCurrentSnapshot;

    public TelephonySubscriptionTracker(
            @NonNull Context context,
            @NonNull Handler handler,
            @NonNull TelephonySubscriptionTrackerCallback callback) {
        this(context, handler, callback, new Dependencies());
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    TelephonySubscriptionTracker(
            @NonNull Context context,
            @NonNull Handler handler,
            @NonNull TelephonySubscriptionTrackerCallback callback,
            @NonNull Dependencies deps) {
        mContext = Objects.requireNonNull(context, "Missing context");
        mHandler = Objects.requireNonNull(handler, "Missing handler");
        mCallback = Objects.requireNonNull(callback, "Missing callback");
        mDeps = Objects.requireNonNull(deps, "Missing deps");

        mSubscriptionManager = mContext.getSystemService(SubscriptionManager.class);
        mCarrierConfigManager = mContext.getSystemService(CarrierConfigManager.class);

        mSubscriptionChangedListener =
                new OnSubscriptionsChangedListener() {
                    @Override
                    public void onSubscriptionsChanged() {
                        handleSubscriptionsChanged();
                    }
                };
    }

    /** Registers the receivers, and starts tracking subscriptions. */
    public void register() {
        mContext.registerReceiver(
                this, new IntentFilter(ACTION_CARRIER_CONFIG_CHANGED), null, mHandler);
        mSubscriptionManager.addOnSubscriptionsChangedListener(
                new HandlerExecutor(mHandler), mSubscriptionChangedListener);
    }

    /** Unregisters the receivers, and stops tracking subscriptions. */
    public void unregister() {
        mContext.unregisterReceiver(this);
        mSubscriptionManager.removeOnSubscriptionsChangedListener(mSubscriptionChangedListener);
    }

    /**
     * Handles subscription changes, correlating available subscriptions and loaded carrier configs
     *
     * <p>The subscription change listener is registered with a HandlerExecutor backed by mHandler,
     * so callbacks & broadcasts are all serialized on mHandler, avoiding the need for locking.
     */
    public void handleSubscriptionsChanged() {
        final Set<ParcelUuid> activeSubGroups = new ArraySet<>();
        final Map<Integer, ParcelUuid> newSubIdToGroupMap = new HashMap<>();

        final List<SubscriptionInfo> allSubs = mSubscriptionManager.getAllSubscriptionInfoList();
        if (allSubs == null) {
            return; // Telephony crashed; no way to verify subscriptions.
        }

        // If allSubs is empty, no subscriptions exist. Cache will be cleared by virtue of no active
        // subscriptions
        for (SubscriptionInfo subInfo : allSubs) {
            if (subInfo.getGroupUuid() == null) {
                continue;
            }

            // Build subId -> subGrp cache
            newSubIdToGroupMap.put(subInfo.getSubscriptionId(), subInfo.getGroupUuid());

            // Update subscription groups that are both ready, and active. For a group to be
            // considered active, both of the following must be true:
            //
            // 1. A final CARRIER_CONFIG_CHANGED (where config is for an identified carrier)
            // broadcast must have been received for the subId
            // 2. A active subscription (is loaded into a SIM slot) must be part of the subscription
            // group.
            if (subInfo.getSimSlotIndex() != INVALID_SIM_SLOT_INDEX
                    && mReadySubIdsBySlotId.values().contains(subInfo.getSubscriptionId())) {
                activeSubGroups.add(subInfo.getGroupUuid());
            }
        }

        final TelephonySubscriptionSnapshot newSnapshot =
                new TelephonySubscriptionSnapshot(newSubIdToGroupMap, activeSubGroups);

        // If snapshot was meaningfully updated, fire the callback
        if (!newSnapshot.equals(mCurrentSnapshot)) {
            mCurrentSnapshot = newSnapshot;
            mHandler.post(
                    () -> {
                        mCallback.onNewSnapshot(newSnapshot);
                    });
        }
    }

    /**
     * Broadcast receiver for ACTION_CARRIER_CONFIG_CHANGED
     *
     * <p>The broadcast receiver is registered with mHandler, so callbacks & broadcasts are all
     * serialized on mHandler, avoiding the need for locking.
     */
    @Override
    public void onReceive(Context context, Intent intent) {
        // Accept sticky broadcasts; if CARRIER_CONFIG_CHANGED was previously broadcast and it
        // already was for an identified carrier, we can stop waiting for initial load to complete
        if (!ACTION_CARRIER_CONFIG_CHANGED.equals(intent.getAction())) {
            return;
        }

        final int subId = intent.getIntExtra(EXTRA_SUBSCRIPTION_INDEX, INVALID_SUBSCRIPTION_ID);
        final int slotId = intent.getIntExtra(EXTRA_SLOT_INDEX, INVALID_SIM_SLOT_INDEX);

        if (slotId == INVALID_SIM_SLOT_INDEX) {
            return;
        }

        if (SubscriptionManager.isValidSubscriptionId(subId)) {
            final PersistableBundle carrierConfigs = mCarrierConfigManager.getConfigForSubId(subId);
            if (mDeps.isConfigForIdentifiedCarrier(carrierConfigs)) {
                Slog.v(TAG, String.format("SubId %s ready for SlotId %s", subId, slotId));
                mReadySubIdsBySlotId.put(slotId, subId);
                handleSubscriptionsChanged();
            }
        } else {
            Slog.v(TAG, "Slot unloaded: " + slotId);
            mReadySubIdsBySlotId.remove(slotId);
            handleSubscriptionsChanged();
        }
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    void setReadySubIdsBySlotId(Map<Integer, Integer> readySubIdsBySlotId) {
        mReadySubIdsBySlotId.putAll(readySubIdsBySlotId);
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    Map<Integer, Integer> getReadySubIdsBySlotId() {
        return Collections.unmodifiableMap(mReadySubIdsBySlotId);
    }

    /** TelephonySubscriptionSnapshot is a class containing info about active subscriptions */
    public static class TelephonySubscriptionSnapshot {
        private final Map<Integer, ParcelUuid> mSubIdToGroupMap;
        private final Set<ParcelUuid> mActiveGroups;

        @VisibleForTesting(visibility = Visibility.PRIVATE)
        TelephonySubscriptionSnapshot(
                @NonNull Map<Integer, ParcelUuid> subIdToGroupMap,
                @NonNull Set<ParcelUuid> activeGroups) {
            mSubIdToGroupMap = Collections.unmodifiableMap(
                    Objects.requireNonNull(subIdToGroupMap, "subIdToGroupMap was null"));
            mActiveGroups = Collections.unmodifiableSet(
                    Objects.requireNonNull(activeGroups, "activeGroups was null"));
        }

        /** Returns the active subscription groups */
        @NonNull
        public Set<ParcelUuid> getActiveSubscriptionGroups() {
            return mActiveGroups;
        }

        /** Returns the Subscription Group for a given subId. */
        @Nullable
        public ParcelUuid getGroupForSubId(int subId) {
            return mSubIdToGroupMap.get(subId);
        }

        /**
         * Returns all the subIds in a given group, including available, but inactive subscriptions.
         */
        @NonNull
        public Set<Integer> getAllSubIdsInGroup(ParcelUuid subGrp) {
            final Set<Integer> subIds = new ArraySet<>();

            for (Entry<Integer, ParcelUuid> entry : mSubIdToGroupMap.entrySet()) {
                if (subGrp.equals(entry.getValue())) {
                    subIds.add(entry.getKey());
                }
            }

            return subIds;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mSubIdToGroupMap, mActiveGroups);
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof TelephonySubscriptionSnapshot)) {
                return false;
            }

            final TelephonySubscriptionSnapshot other = (TelephonySubscriptionSnapshot) obj;

            return mSubIdToGroupMap.equals(other.mSubIdToGroupMap)
                    && mActiveGroups.equals(other.mActiveGroups);
        }
    }

    /**
     * Interface for listening to changes in subscriptions
     *
     * @see TelephonySubscriptionTracker
     */
    public interface TelephonySubscriptionTrackerCallback {
        /**
         * Called when subscription information changes, and a new subscription snapshot was taken
         *
         * @param snapshot the snapshot of subscription information.
         */
        void onNewSnapshot(@NonNull TelephonySubscriptionSnapshot snapshot);
    }

    /** External static dependencies for test injection */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public static class Dependencies {
        /** Checks if the given bundle is for an identified carrier */
        public boolean isConfigForIdentifiedCarrier(PersistableBundle bundle) {
            return CarrierConfigManager.isConfigForIdentifiedCarrier(bundle);
        }
    }
}
