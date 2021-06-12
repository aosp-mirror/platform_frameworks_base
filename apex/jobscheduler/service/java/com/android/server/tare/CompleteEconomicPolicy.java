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
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;

import libcore.util.EmptyArray;


/** Combines all enabled policies into one. */
public class CompleteEconomicPolicy extends EconomicPolicy {
    private final ArraySet<EconomicPolicy> mEnabledEconomicPolicies = new ArraySet<>();
    /** Lazily populated set of actions covered by this policy. */
    private final ArrayMap<String, Action> mActions = new ArrayMap<>();
    /** Lazily populated set of rewards covered by this policy. */
    private final ArrayMap<String, Reward> mRewards = new ArrayMap<>();
    private final int[] mCostModifiers;
    private final long mMaxSatiatedBalance;
    private final long mMaxSatiatedCirculation;

    CompleteEconomicPolicy(@NonNull InternalResourceService irs) {
        super(irs);
        mEnabledEconomicPolicies.add(new AlarmManagerEconomicPolicy(irs));
        mEnabledEconomicPolicies.add(new JobSchedulerEconomicPolicy(irs));

        ArraySet<Integer> costModifiers = new ArraySet<>();
        for (int i = 0; i < mEnabledEconomicPolicies.size(); ++i) {
            final int[] sm = mEnabledEconomicPolicies.valueAt(i).getCostModifiers();
            for (int s : sm) {
                costModifiers.add(s);
            }
        }
        mCostModifiers = new int[costModifiers.size()];
        for (int i = 0; i < costModifiers.size(); ++i) {
            mCostModifiers[i] = costModifiers.valueAt(i);
        }

        long max = 0;
        for (int i = 0; i < mEnabledEconomicPolicies.size(); ++i) {
            max += mEnabledEconomicPolicies.valueAt(i).getMaxSatiatedBalance();
        }
        mMaxSatiatedBalance = max;

        max = 0;
        for (int i = 0; i < mEnabledEconomicPolicies.size(); ++i) {
            max += mEnabledEconomicPolicies.valueAt(i).getMaxSatiatedCirculation();
        }
        mMaxSatiatedCirculation = max;
    }

    @Override
    public long getMinSatiatedBalance(final int userId, @NonNull final String pkgName) {
        long min = 0;
        for (int i = 0; i < mEnabledEconomicPolicies.size(); ++i) {
            min += mEnabledEconomicPolicies.valueAt(i).getMinSatiatedBalance(userId, pkgName);
        }
        return min;
    }

    @Override
    public long getMaxSatiatedBalance() {
        return mMaxSatiatedBalance;
    }

    @Override
    public long getMaxSatiatedCirculation() {
        return mMaxSatiatedCirculation;
    }

    @NonNull
    @Override
    public int[] getCostModifiers() {
        return mCostModifiers == null ? EmptyArray.INT : mCostModifiers;
    }

    @Nullable
    @Override
    public Action getAction(@NonNull String actionName) {
        if (mActions.containsKey(actionName)) {
            return mActions.get(actionName);
        }

        long ctp = 0, price = 0;
        boolean exists = false;
        for (int i = 0; i < mEnabledEconomicPolicies.size(); ++i) {
            Action a = mEnabledEconomicPolicies.valueAt(i).getAction(actionName);
            if (a != null) {
                exists = true;
                ctp += a.costToProduce;
                price += a.basePrice;
            }
        }
        final Action action = exists ? new Action(actionName, ctp, price) : null;
        mActions.put(actionName, action);
        return action;
    }

    @Nullable
    @Override
    public Reward getReward(@NonNull String rewardName) {
        if (mRewards.containsKey(rewardName)) {
            return mRewards.get(rewardName);
        }

        long instantReward = 0, ongoingReward = 0, maxReward = 0;
        boolean exists = false;
        for (int i = 0; i < mEnabledEconomicPolicies.size(); ++i) {
            Reward r = mEnabledEconomicPolicies.valueAt(i).getReward(rewardName);
            if (r != null) {
                exists = true;
                instantReward += r.instantReward;
                ongoingReward += r.ongoingRewardPerSecond;
                maxReward += r.maxDailyReward;
            }
        }
        final Reward reward = exists
                ? new Reward(rewardName, instantReward, ongoingReward, maxReward) : null;
        mRewards.put(rewardName, reward);
        return reward;
    }

    @Override
    void dump(IndentingPrintWriter pw) {
        dumpActiveModifiers(pw);
    }
}
