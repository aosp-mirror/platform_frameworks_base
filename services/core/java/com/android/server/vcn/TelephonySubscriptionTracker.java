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
import static android.telephony.TelephonyManager.ACTION_MULTI_SIM_CONFIG_CHANGED;

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
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.telephony.TelephonyManager.CarrierPrivilegesCallback;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.annotations.VisibleForTesting.Visibility;
import com.android.internal.util.IndentingPrintWriter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
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

    @NonNull private final TelephonyManager mTelephonyManager;
    @NonNull private final SubscriptionManager mSubscriptionManager;
    @NonNull private final CarrierConfigManager mCarrierConfigManager;

    @NonNull private final ActiveDataSubscriptionIdListener mActiveDataSubIdListener;

    // TODO (Android T+): Add ability to handle multiple subIds per slot.
    @NonNull private final Map<Integer, Integer> mReadySubIdsBySlotId = new HashMap<>();
    @NonNull private final OnSubscriptionsChangedListener mSubscriptionChangedListener;

    @NonNull
    private final List<CarrierPrivilegesCallback> mCarrierPrivilegesCallbacks = new ArrayList<>();

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

        mTelephonyManager = mContext.getSystemService(TelephonyManager.class);
        mSubscriptionManager = mContext.getSystemService(SubscriptionManager.class);
        mCarrierConfigManager = mContext.getSystemService(CarrierConfigManager.class);
        mActiveDataSubIdListener = new ActiveDataSubscriptionIdListener();

        mSubscriptionChangedListener =
                new OnSubscriptionsChangedListener() {
                    @Override
                    public void onSubscriptionsChanged() {
                        handleSubscriptionsChanged();
                    }
                };
    }

    /**
     * Registers the receivers, and starts tracking subscriptions.
     *
     * <p>Must always be run on the VcnManagementService thread.
     */
    public void register() {
        final HandlerExecutor executor = new HandlerExecutor(mHandler);
        final IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_CARRIER_CONFIG_CHANGED);
        filter.addAction(ACTION_MULTI_SIM_CONFIG_CHANGED);

        mContext.registerReceiver(this, filter, null, mHandler, Context.RECEIVER_NOT_EXPORTED);
        mSubscriptionManager.addOnSubscriptionsChangedListener(
                executor, mSubscriptionChangedListener);
        mTelephonyManager.registerTelephonyCallback(executor, mActiveDataSubIdListener);

        registerCarrierPrivilegesCallbacks();
    }

    // TODO(b/221306368): Refactor with the new onCarrierServiceChange in the new CPCallback
    private void registerCarrierPrivilegesCallbacks() {
        final HandlerExecutor executor = new HandlerExecutor(mHandler);
        final int modemCount = mTelephonyManager.getActiveModemCount();
        try {
            for (int i = 0; i < modemCount; i++) {
                CarrierPrivilegesCallback carrierPrivilegesCallback =
                        new CarrierPrivilegesCallback() {
                            @Override
                            public void onCarrierPrivilegesChanged(
                                    @NonNull Set<String> privilegedPackageNames,
                                    @NonNull Set<Integer> privilegedUids) {
                                // Re-trigger the synchronous check (which is also very cheap due
                                // to caching in CarrierPrivilegesTracker). This allows consistency
                                // with the onSubscriptionsChangedListener and broadcasts.
                                handleSubscriptionsChanged();
                            }
                        };

                mTelephonyManager.registerCarrierPrivilegesCallback(
                        i, executor, carrierPrivilegesCallback);
                mCarrierPrivilegesCallbacks.add(carrierPrivilegesCallback);
            }
        } catch (IllegalArgumentException e) {
            Slog.wtf(TAG, "Encounted exception registering carrier privileges listeners", e);
        }
    }

    /**
     * Unregisters the receivers, and stops tracking subscriptions.
     *
     * <p>Must always be run on the VcnManagementService thread.
     */
    public void unregister() {
        mContext.unregisterReceiver(this);
        mSubscriptionManager.removeOnSubscriptionsChangedListener(mSubscriptionChangedListener);
        mTelephonyManager.unregisterTelephonyCallback(mActiveDataSubIdListener);

        unregisterCarrierPrivilegesCallbacks();
    }

    private void unregisterCarrierPrivilegesCallbacks() {
        for (CarrierPrivilegesCallback carrierPrivilegesCallback :
                mCarrierPrivilegesCallbacks) {
            mTelephonyManager.unregisterCarrierPrivilegesCallback(carrierPrivilegesCallback);
        }
        mCarrierPrivilegesCallbacks.clear();
    }

    /**
     * Handles subscription changes, correlating available subscriptions and loaded carrier configs
     *
     * <p>The subscription change listener is registered with a HandlerExecutor backed by mHandler,
     * so callbacks & broadcasts are all serialized on mHandler, avoiding the need for locking.
     */
    public void handleSubscriptionsChanged() {
        final Map<ParcelUuid, Set<String>> privilegedPackages = new HashMap<>();
        final Map<Integer, SubscriptionInfo> newSubIdToInfoMap = new HashMap<>();

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
            newSubIdToInfoMap.put(subInfo.getSubscriptionId(), subInfo);

            // Update subscription groups that are both ready, and active. For a group to be
            // considered active, both of the following must be true:
            //
            // 1. A final CARRIER_CONFIG_CHANGED (where config is for an identified carrier)
            // broadcast must have been received for the subId
            // 2. A active subscription (is loaded into a SIM slot) must be part of the subscription
            // group.
            if (subInfo.getSimSlotIndex() != INVALID_SIM_SLOT_INDEX
                    && mReadySubIdsBySlotId.values().contains(subInfo.getSubscriptionId())) {
                final TelephonyManager subIdSpecificTelephonyManager =
                        mTelephonyManager.createForSubscriptionId(subInfo.getSubscriptionId());

                final ParcelUuid subGroup = subInfo.getGroupUuid();
                final Set<String> pkgs =
                        privilegedPackages.getOrDefault(subGroup, new ArraySet<>());
                pkgs.addAll(subIdSpecificTelephonyManager.getPackagesWithCarrierPrivileges());

                privilegedPackages.put(subGroup, pkgs);
            }
        }

        final TelephonySubscriptionSnapshot newSnapshot =
                new TelephonySubscriptionSnapshot(
                        mDeps.getActiveDataSubscriptionId(), newSubIdToInfoMap, privilegedPackages);

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
        switch (intent.getAction()) {
            case ACTION_CARRIER_CONFIG_CHANGED:
                handleActionCarrierConfigChanged(context, intent);
                break;
            case ACTION_MULTI_SIM_CONFIG_CHANGED:
                handleActionMultiSimConfigChanged(context, intent);
                break;
            default:
                Slog.v(TAG, "Unknown intent received with action: " + intent.getAction());
        }
    }

    private void handleActionMultiSimConfigChanged(Context context, Intent intent) {
        unregisterCarrierPrivilegesCallbacks();

        // Clear invalid slotIds from the mReadySubIdsBySlotId map.
        final int modemCount = mTelephonyManager.getActiveModemCount();
        final Iterator<Integer> slotIdIterator = mReadySubIdsBySlotId.keySet().iterator();
        while (slotIdIterator.hasNext()) {
            final int slotId = slotIdIterator.next();

            if (slotId >= modemCount) {
                slotIdIterator.remove();
            }
        }

        registerCarrierPrivilegesCallbacks();
        handleSubscriptionsChanged();
    }

    private void handleActionCarrierConfigChanged(Context context, Intent intent) {
        // Accept sticky broadcasts; if CARRIER_CONFIG_CHANGED was previously broadcast and it
        // already was for an identified carrier, we can stop waiting for initial load to complete
        final int subId = intent.getIntExtra(EXTRA_SUBSCRIPTION_INDEX, INVALID_SUBSCRIPTION_ID);
        final int slotId = intent.getIntExtra(EXTRA_SLOT_INDEX, INVALID_SIM_SLOT_INDEX);

        if (slotId == INVALID_SIM_SLOT_INDEX) {
            return;
        }

        if (SubscriptionManager.isValidSubscriptionId(subId)) {
            final PersistableBundle carrierConfigs = mCarrierConfigManager.getConfigForSubId(subId);
            if (mDeps.isConfigForIdentifiedCarrier(carrierConfigs)) {
                mReadySubIdsBySlotId.put(slotId, subId);
                handleSubscriptionsChanged();
            }
        } else {
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
        private final int mActiveDataSubId;
        private final Map<Integer, SubscriptionInfo> mSubIdToInfoMap;
        private final Map<ParcelUuid, Set<String>> mPrivilegedPackages;

        public static final TelephonySubscriptionSnapshot EMPTY_SNAPSHOT =
                new TelephonySubscriptionSnapshot(
                        INVALID_SUBSCRIPTION_ID, Collections.emptyMap(), Collections.emptyMap());

        @VisibleForTesting(visibility = Visibility.PRIVATE)
        TelephonySubscriptionSnapshot(
                int activeDataSubId,
                @NonNull Map<Integer, SubscriptionInfo> subIdToInfoMap,
                @NonNull Map<ParcelUuid, Set<String>> privilegedPackages) {
            mActiveDataSubId = activeDataSubId;
            Objects.requireNonNull(subIdToInfoMap, "subIdToInfoMap was null");
            Objects.requireNonNull(privilegedPackages, "privilegedPackages was null");

            mSubIdToInfoMap = Collections.unmodifiableMap(subIdToInfoMap);

            final Map<ParcelUuid, Set<String>> unmodifiableInnerSets = new ArrayMap<>();
            for (Entry<ParcelUuid, Set<String>> entry : privilegedPackages.entrySet()) {
                unmodifiableInnerSets.put(
                        entry.getKey(), Collections.unmodifiableSet(entry.getValue()));
            }
            mPrivilegedPackages = Collections.unmodifiableMap(unmodifiableInnerSets);
        }

        /** Returns the active subscription ID. May be INVALID_SUBSCRIPTION_ID */
        public int getActiveDataSubscriptionId() {
            return mActiveDataSubId;
        }

        /** Returns the active subscription group */
        @Nullable
        public ParcelUuid getActiveDataSubscriptionGroup() {
            final SubscriptionInfo info = mSubIdToInfoMap.get(getActiveDataSubscriptionId());
            if (info == null) {
                return null;
            }

            return info.getGroupUuid();
        }

        /** Returns the active subscription groups */
        @NonNull
        public Set<ParcelUuid> getActiveSubscriptionGroups() {
            return mPrivilegedPackages.keySet();
        }

        /** Checks if the provided package is carrier privileged for the specified sub group. */
        public boolean packageHasPermissionsForSubscriptionGroup(
                @NonNull ParcelUuid subGrp, @NonNull String packageName) {
            final Set<String> privilegedPackages = mPrivilegedPackages.get(subGrp);

            return privilegedPackages != null && privilegedPackages.contains(packageName);
        }

        /** Returns the Subscription Group for a given subId. */
        @Nullable
        public ParcelUuid getGroupForSubId(int subId) {
            return mSubIdToInfoMap.containsKey(subId)
                    ? mSubIdToInfoMap.get(subId).getGroupUuid()
                    : null;
        }

        /**
         * Returns all the subIds in a given group, including available, but inactive subscriptions.
         */
        @NonNull
        public Set<Integer> getAllSubIdsInGroup(ParcelUuid subGrp) {
            final Set<Integer> subIds = new ArraySet<>();

            for (Entry<Integer, SubscriptionInfo> entry : mSubIdToInfoMap.entrySet()) {
                if (subGrp.equals(entry.getValue().getGroupUuid())) {
                    subIds.add(entry.getKey());
                }
            }

            return subIds;
        }

        /** Checks if the requested subscription is opportunistic */
        @NonNull
        public boolean isOpportunistic(int subId) {
            return mSubIdToInfoMap.containsKey(subId)
                    ? mSubIdToInfoMap.get(subId).isOpportunistic()
                    : false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mActiveDataSubId, mSubIdToInfoMap, mPrivilegedPackages);
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof TelephonySubscriptionSnapshot)) {
                return false;
            }

            final TelephonySubscriptionSnapshot other = (TelephonySubscriptionSnapshot) obj;

            return mActiveDataSubId == other.mActiveDataSubId
                    && mSubIdToInfoMap.equals(other.mSubIdToInfoMap)
                    && mPrivilegedPackages.equals(other.mPrivilegedPackages);
        }

        /** Dumps the state of this snapshot for logging and debugging purposes. */
        public void dump(IndentingPrintWriter pw) {
            pw.println("TelephonySubscriptionSnapshot:");
            pw.increaseIndent();

            pw.println("mActiveDataSubId: " + mActiveDataSubId);
            pw.println("mSubIdToInfoMap: " + mSubIdToInfoMap);
            pw.println("mPrivilegedPackages: " + mPrivilegedPackages);

            pw.decreaseIndent();
        }

        @Override
        public String toString() {
            return "TelephonySubscriptionSnapshot{ "
                    + "mActiveDataSubId=" + mActiveDataSubId
                    + ", mSubIdToInfoMap=" + mSubIdToInfoMap
                    + ", mPrivilegedPackages=" + mPrivilegedPackages
                    + " }";
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

    private class ActiveDataSubscriptionIdListener extends TelephonyCallback
            implements TelephonyCallback.ActiveDataSubscriptionIdListener {
        @Override
        public void onActiveDataSubscriptionIdChanged(int subId) {
            handleSubscriptionsChanged();
        }
    }

    /** External static dependencies for test injection */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public static class Dependencies {
        /** Checks if the given bundle is for an identified carrier */
        public boolean isConfigForIdentifiedCarrier(PersistableBundle bundle) {
            return CarrierConfigManager.isConfigForIdentifiedCarrier(bundle);
        }

        /** Gets the active Subscription ID */
        public int getActiveDataSubscriptionId() {
            return SubscriptionManager.getActiveDataSubscriptionId();
        }
    }
}
