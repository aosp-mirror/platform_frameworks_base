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

import static com.android.server.tare.Modifier.COST_MODIFIER_CHARGING;
import static com.android.server.tare.Modifier.COST_MODIFIER_POWER_SAVE_MODE;
import static com.android.server.tare.Modifier.COST_MODIFIER_PROCESS_STATE;
import static com.android.server.tare.TareUtils.arcToNarc;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.ArrayMap;

/**
 * Policy defining pricing information and daily ARC requirements and suggestions for
 * JobScheduler.
 */
public class JobSchedulerEconomicPolicy extends EconomicPolicy {
    public static final String ACTION_JOB_MAX_START = "JOB_MAX_START";
    public static final String ACTION_JOB_MAX_RUNNING = "JOB_MAX_RUNNING";
    public static final String ACTION_JOB_HIGH_START = "JOB_HIGH_START";
    public static final String ACTION_JOB_HIGH_RUNNING = "JOB_HIGH_RUNNING";
    public static final String ACTION_JOB_DEFAULT_START = "JOB_DEFAULT_START";
    public static final String ACTION_JOB_DEFAULT_RUNNING = "JOB_DEFAULT_RUNNING";
    public static final String ACTION_JOB_LOW_START = "JOB_LOW_START";
    public static final String ACTION_JOB_LOW_RUNNING = "JOB_LOW_RUNNING";
    public static final String ACTION_JOB_MIN_START = "JOB_MIN_START";
    public static final String ACTION_JOB_MIN_RUNNING = "JOB_MIN_RUNNING";
    public static final String ACTION_JOB_TIMEOUT = "JOB_TIMEOUT";

    private static final int[] COST_MODIFIERS = new int[]{
            COST_MODIFIER_CHARGING,
            COST_MODIFIER_POWER_SAVE_MODE,
            COST_MODIFIER_PROCESS_STATE
    };

    private final ArrayMap<String, Action> mActions = new ArrayMap<>();
    private final ArrayMap<String, Reward> mRewards = new ArrayMap<>();

    JobSchedulerEconomicPolicy(InternalResourceService irs) {
        super(irs);
        loadActions();
        loadRewards();
    }

    @Override
    long getMinSatiatedBalance(final int userId, @NonNull final String pkgName) {
        // TODO: incorporate time since usage
        return arcToNarc(2000);
    }

    @Override
    long getMaxSatiatedBalance() {
        return arcToNarc(60000);
    }

    @Override
    long getMaxSatiatedCirculation() {
        return arcToNarc(691200);
    }

    @NonNull
    @Override
    int[] getCostModifiers() {
        return COST_MODIFIERS;
    }

    @Nullable
    @Override
    Action getAction(@NonNull String actionName) {
        return mActions.get(actionName);
    }

    @Nullable
    @Override
    Reward getReward(@NonNull String rewardName) {
        return mRewards.get(rewardName);
    }

    private void loadActions() {
        mActions.put(ACTION_JOB_MAX_START,
                new Action(ACTION_JOB_MAX_START, arcToNarc(3), arcToNarc(10)));
        mActions.put(ACTION_JOB_MAX_RUNNING,
                new Action(ACTION_JOB_MAX_RUNNING, arcToNarc(2), arcToNarc(5)));
        mActions.put(ACTION_JOB_HIGH_START,
                new Action(ACTION_JOB_HIGH_START, arcToNarc(3), arcToNarc(8)));
        mActions.put(ACTION_JOB_HIGH_RUNNING,
                new Action(ACTION_JOB_HIGH_RUNNING, arcToNarc(2), arcToNarc(4)));
        mActions.put(ACTION_JOB_DEFAULT_START,
                new Action(ACTION_JOB_DEFAULT_START, arcToNarc(3), arcToNarc(6)));
        mActions.put(ACTION_JOB_DEFAULT_RUNNING,
                new Action(ACTION_JOB_DEFAULT_RUNNING, arcToNarc(2), arcToNarc(3)));
        mActions.put(ACTION_JOB_LOW_START,
                new Action(ACTION_JOB_LOW_START, arcToNarc(3), arcToNarc(4)));
        mActions.put(ACTION_JOB_LOW_RUNNING,
                new Action(ACTION_JOB_LOW_RUNNING, arcToNarc(2), arcToNarc(2)));
        mActions.put(ACTION_JOB_MIN_START,
                new Action(ACTION_JOB_MIN_START, arcToNarc(3), arcToNarc(2)));
        mActions.put(ACTION_JOB_MIN_RUNNING,
                new Action(ACTION_JOB_MIN_RUNNING, arcToNarc(2), arcToNarc(1)));
        mActions.put(ACTION_JOB_TIMEOUT,
                new Action(ACTION_JOB_TIMEOUT, arcToNarc(30), arcToNarc(60)));
    }

    private void loadRewards() {
        mRewards.put(REWARD_TOP_ACTIVITY,
                new Reward(REWARD_TOP_ACTIVITY,
                        arcToNarc(0), /* .5 arcs */ arcToNarc(5) / 10, arcToNarc(15000)));
        mRewards.put(REWARD_NOTIFICATION_SEEN,
                new Reward(REWARD_NOTIFICATION_SEEN, arcToNarc(1), arcToNarc(0), arcToNarc(10)));
        mRewards.put(REWARD_NOTIFICATION_INTERACTION,
                new Reward(REWARD_NOTIFICATION_INTERACTION,
                        arcToNarc(5), arcToNarc(0), arcToNarc(5000)));
        mRewards.put(REWARD_WIDGET_INTERACTION,
                new Reward(REWARD_WIDGET_INTERACTION,
                        arcToNarc(10), arcToNarc(0), arcToNarc(5000)));
        mRewards.put(REWARD_OTHER_USER_INTERACTION,
                new Reward(REWARD_OTHER_USER_INTERACTION,
                        arcToNarc(10), arcToNarc(0), arcToNarc(5000)));
    }
}
