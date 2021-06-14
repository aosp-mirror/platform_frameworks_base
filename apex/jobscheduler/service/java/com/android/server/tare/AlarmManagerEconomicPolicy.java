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
import static com.android.server.tare.Modifier.COST_MODIFIER_DEVICE_IDLE;
import static com.android.server.tare.Modifier.COST_MODIFIER_POWER_SAVE_MODE;
import static com.android.server.tare.Modifier.COST_MODIFIER_PROCESS_STATE;
import static com.android.server.tare.TareUtils.arcToNarc;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.SparseArray;

/**
 * Policy defining pricing information and daily ARC requirements and suggestions for
 * AlarmManager.
 */
public class AlarmManagerEconomicPolicy extends EconomicPolicy {
    public static final int ACTION_ALARM_WAKEUP_EXACT_ALLOW_WHILE_IDLE =
            TYPE_ACTION | POLICY_AM | 0;
    public static final int ACTION_ALARM_WAKEUP_EXACT =
            TYPE_ACTION | POLICY_AM | 1;
    public static final int ACTION_ALARM_WAKEUP_INEXACT_ALLOW_WHILE_IDLE =
            TYPE_ACTION | POLICY_AM | 2;
    public static final int ACTION_ALARM_WAKEUP_INEXACT =
            TYPE_ACTION | POLICY_AM | 3;
    public static final int ACTION_ALARM_NONWAKEUP_EXACT_ALLOW_WHILE_IDLE =
            TYPE_ACTION | POLICY_AM | 4;
    public static final int ACTION_ALARM_NONWAKEUP_EXACT =
            TYPE_ACTION | POLICY_AM | 5;
    public static final int ACTION_ALARM_NONWAKEUP_INEXACT_ALLOW_WHILE_IDLE =
            TYPE_ACTION | POLICY_AM | 6;
    public static final int ACTION_ALARM_NONWAKEUP_INEXACT =
            TYPE_ACTION | POLICY_AM | 7;
    public static final int ACTION_ALARM_CLOCK =
            TYPE_ACTION | POLICY_AM | 8;

    private static final int[] COST_MODIFIERS = new int[]{
            COST_MODIFIER_CHARGING,
            COST_MODIFIER_DEVICE_IDLE,
            COST_MODIFIER_POWER_SAVE_MODE,
            COST_MODIFIER_PROCESS_STATE
    };

    private final SparseArray<Action> mActions = new SparseArray<>();
    private final SparseArray<Reward> mRewards = new SparseArray<>();

    AlarmManagerEconomicPolicy(InternalResourceService irs) {
        super(irs);
        loadActions();
        loadRewards();
    }

    @Override
    long getMinSatiatedBalance(final int userId, @NonNull final String pkgName) {
        // TODO: take exemption into account
        return arcToNarc(160);
    }

    @Override
    long getMaxSatiatedBalance() {
        return arcToNarc(1440);
    }


    @Override
    long getMaxSatiatedCirculation() {
        return arcToNarc(52000);
    }

    @NonNull
    @Override
    int[] getCostModifiers() {
        return COST_MODIFIERS;
    }

    @Nullable
    @Override
    Action getAction(@AppAction int actionId) {
        return mActions.get(actionId);
    }

    @Nullable
    @Override
    Reward getReward(@UtilityReward int rewardId) {
        return mRewards.get(rewardId);
    }

    private void loadActions() {
        mActions.put(ACTION_ALARM_WAKEUP_EXACT_ALLOW_WHILE_IDLE,
                new Action(ACTION_ALARM_WAKEUP_EXACT_ALLOW_WHILE_IDLE, arcToNarc(3), arcToNarc(5)));
        mActions.put(ACTION_ALARM_WAKEUP_EXACT,
                new Action(ACTION_ALARM_WAKEUP_EXACT, arcToNarc(3), arcToNarc(4)));
        mActions.put(ACTION_ALARM_WAKEUP_INEXACT_ALLOW_WHILE_IDLE,
                new Action(ACTION_ALARM_WAKEUP_INEXACT_ALLOW_WHILE_IDLE,
                        arcToNarc(3), arcToNarc(4)));
        mActions.put(ACTION_ALARM_WAKEUP_INEXACT,
                new Action(ACTION_ALARM_WAKEUP_INEXACT, arcToNarc(3), arcToNarc(3)));
        mActions.put(ACTION_ALARM_NONWAKEUP_EXACT_ALLOW_WHILE_IDLE,
                new Action(ACTION_ALARM_NONWAKEUP_EXACT_ALLOW_WHILE_IDLE,
                        arcToNarc(1), arcToNarc(3)));
        mActions.put(ACTION_ALARM_NONWAKEUP_EXACT,
                new Action(ACTION_ALARM_NONWAKEUP_EXACT, arcToNarc(1), arcToNarc(2)));
        mActions.put(ACTION_ALARM_NONWAKEUP_INEXACT_ALLOW_WHILE_IDLE,
                new Action(ACTION_ALARM_NONWAKEUP_INEXACT_ALLOW_WHILE_IDLE,
                        arcToNarc(1), arcToNarc(2)));
        mActions.put(ACTION_ALARM_NONWAKEUP_INEXACT,
                new Action(ACTION_ALARM_NONWAKEUP_INEXACT, arcToNarc(1), arcToNarc(1)));
        mActions.put(ACTION_ALARM_CLOCK,
                new Action(ACTION_ALARM_CLOCK, arcToNarc(5), arcToNarc(10)));
    }

    private void loadRewards() {
        mRewards.put(REWARD_TOP_ACTIVITY,
                new Reward(REWARD_TOP_ACTIVITY,
                        arcToNarc(0), /* .01 arcs */ arcToNarc(1) / 100, arcToNarc(500)));
        mRewards.put(REWARD_NOTIFICATION_SEEN,
                new Reward(REWARD_NOTIFICATION_SEEN, arcToNarc(3), arcToNarc(0), arcToNarc(60)));
        mRewards.put(REWARD_NOTIFICATION_INTERACTION,
                new Reward(REWARD_NOTIFICATION_INTERACTION,
                        arcToNarc(5), arcToNarc(0), arcToNarc(500)));
        mRewards.put(REWARD_WIDGET_INTERACTION,
                new Reward(REWARD_WIDGET_INTERACTION, arcToNarc(10), arcToNarc(0), arcToNarc(500)));
        mRewards.put(REWARD_OTHER_USER_INTERACTION,
                new Reward(REWARD_OTHER_USER_INTERACTION,
                        arcToNarc(10), arcToNarc(0), arcToNarc(500)));
    }
}
