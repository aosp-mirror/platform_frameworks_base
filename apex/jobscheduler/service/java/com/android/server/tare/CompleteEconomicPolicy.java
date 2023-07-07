/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.tare;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.tare.EconomyManager;
import android.provider.DeviceConfig;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;

import libcore.util.EmptyArray;

/** Combines all enabled policies into one. */
public class CompleteEconomicPolicy extends EconomicPolicy {
    private static final String TAG = "TARE-" + CompleteEconomicPolicy.class.getSimpleName();

    private final CompleteInjector mInjector;
    private final ArraySet<EconomicPolicy> mEnabledEconomicPolicies = new ArraySet<>();
    /** Lazily populated set of actions covered by this policy. */
    private final SparseArray<Action> mActions = new SparseArray<>();
    /** Lazily populated set of rewards covered by this policy. */
    private final SparseArray<Reward> mRewards = new SparseArray<>();
    private int mEnabledEconomicPolicyIds = 0;
    private int[] mCostModifiers = EmptyArray.INT;
    private long mInitialConsumptionLimit;
    private long mMinConsumptionLimit;
    private long mMaxConsumptionLimit;

    CompleteEconomicPolicy(@NonNull InternalResourceService irs) {
        this(irs, new CompleteInjector());
    }

    @VisibleForTesting
    CompleteEconomicPolicy(@NonNull InternalResourceService irs,
            @NonNull CompleteInjector injector) {
        super(irs);
        mInjector = injector;

        if (mInjector.isPolicyEnabled(POLICY_ALARM, null)) {
            mEnabledEconomicPolicyIds |= POLICY_ALARM;
            mEnabledEconomicPolicies.add(new AlarmManagerEconomicPolicy(mIrs, mInjector));
        }
        if (mInjector.isPolicyEnabled(POLICY_JOB, null)) {
            mEnabledEconomicPolicyIds |= POLICY_JOB;
            mEnabledEconomicPolicies.add(new JobSchedulerEconomicPolicy(mIrs, mInjector));
        }
    }

    @Override
    void setup(@NonNull DeviceConfig.Properties properties) {
        super.setup(properties);

        mActions.clear();
        mRewards.clear();

        mEnabledEconomicPolicies.clear();
        mEnabledEconomicPolicyIds = 0;
        if (mInjector.isPolicyEnabled(POLICY_ALARM, properties)) {
            mEnabledEconomicPolicyIds |= POLICY_ALARM;
            mEnabledEconomicPolicies.add(new AlarmManagerEconomicPolicy(mIrs, mInjector));
        }
        if (mInjector.isPolicyEnabled(POLICY_JOB, properties)) {
            mEnabledEconomicPolicyIds |= POLICY_JOB;
            mEnabledEconomicPolicies.add(new JobSchedulerEconomicPolicy(mIrs, mInjector));
        }

        ArraySet<Integer> costModifiers = new ArraySet<>();
        for (int i = 0; i < mEnabledEconomicPolicies.size(); ++i) {
            final int[] sm = mEnabledEconomicPolicies.valueAt(i).getCostModifiers();
            for (int s : sm) {
                costModifiers.add(s);
            }
        }
        mCostModifiers = ArrayUtils.convertToIntArray(costModifiers);

        for (int i = 0; i < mEnabledEconomicPolicies.size(); ++i) {
            mEnabledEconomicPolicies.valueAt(i).setup(properties);
        }
        updateLimits();
    }

    private void updateLimits() {
        long initialConsumptionLimit = 0;
        long minConsumptionLimit = 0;
        long maxConsumptionLimit = 0;
        for (int i = 0; i < mEnabledEconomicPolicies.size(); ++i) {
            final EconomicPolicy economicPolicy = mEnabledEconomicPolicies.valueAt(i);
            initialConsumptionLimit += economicPolicy.getInitialSatiatedConsumptionLimit();
            minConsumptionLimit += economicPolicy.getMinSatiatedConsumptionLimit();
            maxConsumptionLimit += economicPolicy.getMaxSatiatedConsumptionLimit();
        }
        mInitialConsumptionLimit = initialConsumptionLimit;
        mMinConsumptionLimit = minConsumptionLimit;
        mMaxConsumptionLimit = maxConsumptionLimit;
    }

    @Override
    long getMinSatiatedBalance(final int userId, @NonNull final String pkgName) {
        long min = 0;
        for (int i = 0; i < mEnabledEconomicPolicies.size(); ++i) {
            min += mEnabledEconomicPolicies.valueAt(i).getMinSatiatedBalance(userId, pkgName);
        }
        return min;
    }

    @Override
    long getMaxSatiatedBalance(int userId, @NonNull String pkgName) {
        long max = 0;
        for (int i = 0; i < mEnabledEconomicPolicies.size(); ++i) {
            max += mEnabledEconomicPolicies.valueAt(i).getMaxSatiatedBalance(userId, pkgName);
        }
        return max;
    }

    @Override
    long getInitialSatiatedConsumptionLimit() {
        return mInitialConsumptionLimit;
    }

    @Override
    long getMinSatiatedConsumptionLimit() {
        return mMinConsumptionLimit;
    }

    @Override
    long getMaxSatiatedConsumptionLimit() {
        return mMaxConsumptionLimit;
    }

    @NonNull
    @Override
    int[] getCostModifiers() {
        return mCostModifiers == null ? EmptyArray.INT : mCostModifiers;
    }

    @Nullable
    @Override
    Action getAction(@AppAction int actionId) {
        if (mActions.contains(actionId)) {
            return mActions.get(actionId);
        }

        long ctp = 0, price = 0;
        boolean exists = false;
        for (int i = 0; i < mEnabledEconomicPolicies.size(); ++i) {
            Action a = mEnabledEconomicPolicies.valueAt(i).getAction(actionId);
            if (a != null) {
                exists = true;
                ctp += a.costToProduce;
                price += a.basePrice;
            }
        }
        final Action action = exists ? new Action(actionId, ctp, price) : null;
        mActions.put(actionId, action);
        return action;
    }

    @Nullable
    @Override
    Reward getReward(@UtilityReward int rewardId) {
        if (mRewards.contains(rewardId)) {
            return mRewards.get(rewardId);
        }

        long instantReward = 0, ongoingReward = 0, maxReward = 0;
        boolean exists = false;
        for (int i = 0; i < mEnabledEconomicPolicies.size(); ++i) {
            Reward r = mEnabledEconomicPolicies.valueAt(i).getReward(rewardId);
            if (r != null) {
                exists = true;
                instantReward += r.instantReward;
                ongoingReward += r.ongoingRewardPerSecond;
                maxReward += r.maxDailyReward;
            }
        }
        final Reward reward = exists
                ? new Reward(rewardId, instantReward, ongoingReward, maxReward) : null;
        mRewards.put(rewardId, reward);
        return reward;
    }

    boolean isPolicyEnabled(@Policy int policyId) {
        return (mEnabledEconomicPolicyIds & policyId) == policyId;
    }

    int getEnabledPolicyIds() {
        return mEnabledEconomicPolicyIds;
    }

    @VisibleForTesting
    static class CompleteInjector extends Injector {

        boolean isPolicyEnabled(int policy, @Nullable DeviceConfig.Properties properties) {
            final String key;
            final boolean defaultEnable;
            switch (policy) {
                case POLICY_ALARM:
                    key = EconomyManager.KEY_ENABLE_POLICY_ALARM;
                    defaultEnable = EconomyManager.DEFAULT_ENABLE_POLICY_ALARM;
                    break;
                case POLICY_JOB:
                    key = EconomyManager.KEY_ENABLE_POLICY_JOB_SCHEDULER;
                    defaultEnable = EconomyManager.DEFAULT_ENABLE_POLICY_JOB_SCHEDULER;
                    break;
                default:
                    Slog.wtf(TAG, "Unknown policy: " + policy);
                    return false;
            }
            if (properties == null) {
                return defaultEnable;
            }
            return properties.getBoolean(key, defaultEnable);
        }
    }

    @Override
    void dump(IndentingPrintWriter pw) {
        dumpActiveModifiers(pw);

        pw.println();
        pw.println(getClass().getSimpleName() + ":");
        pw.increaseIndent();

        pw.println("Cached actions:");
        pw.increaseIndent();
        for (int i = 0; i < mActions.size(); ++i) {
            final Action action = mActions.valueAt(i);
            if (action != null) {
                dumpAction(pw, action);
            }
        }
        pw.decreaseIndent();

        pw.println();
        pw.println("Cached rewards:");
        pw.increaseIndent();
        for (int i = 0; i < mRewards.size(); ++i) {
            final Reward reward = mRewards.valueAt(i);
            if (reward != null) {
                dumpReward(pw, reward);
            }
        }
        pw.decreaseIndent();

        for (int i = 0; i < mEnabledEconomicPolicies.size(); i++) {
            final EconomicPolicy economicPolicy = mEnabledEconomicPolicies.valueAt(i);
            pw.println();
            pw.print("(Includes) ");
            pw.println(economicPolicy.getClass().getSimpleName() + ":");
            pw.increaseIndent();
            economicPolicy.dump(pw);
            pw.decreaseIndent();
        }
        pw.decreaseIndent();
    }
}
