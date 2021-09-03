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

import static android.app.tare.EconomyManager.DEFAULT_AM_ACTION_ALARM_ALARMCLOCK_BASE_PRICE;
import static android.app.tare.EconomyManager.DEFAULT_AM_ACTION_ALARM_ALARMCLOCK_CTP;
import static android.app.tare.EconomyManager.DEFAULT_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_EXACT_NONWAKEUP_CTP;
import static android.app.tare.EconomyManager.DEFAULT_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_EXACT_WAKEUP_BASE_PRICE;
import static android.app.tare.EconomyManager.DEFAULT_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_EXACT_WAKEUP_CTP;
import static android.app.tare.EconomyManager.DEFAULT_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_INEXACT_NONWAKEUP_BASE_PRICE;
import static android.app.tare.EconomyManager.DEFAULT_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_INEXACT_NONWAKEUP_CTP;
import static android.app.tare.EconomyManager.DEFAULT_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_INEXACT_WAKEUP_BASE_PRICE;
import static android.app.tare.EconomyManager.DEFAULT_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_INEXACT_WAKEUP_CTP;
import static android.app.tare.EconomyManager.DEFAULT_AM_ACTION_ALARM_EXACT_NONWAKEUP_BASE_PRICE;
import static android.app.tare.EconomyManager.DEFAULT_AM_ACTION_ALARM_EXACT_NONWAKEUP_CTP;
import static android.app.tare.EconomyManager.DEFAULT_AM_ACTION_ALARM_EXACT_WAKEUP_BASE_PRICE;
import static android.app.tare.EconomyManager.DEFAULT_AM_ACTION_ALARM_EXACT_WAKEUP_CTP;
import static android.app.tare.EconomyManager.DEFAULT_AM_ACTION_ALARM_INEXACT_NONWAKEUP_BASE_PRICE;
import static android.app.tare.EconomyManager.DEFAULT_AM_ACTION_ALARM_INEXACT_NONWAKEUP_CTP;
import static android.app.tare.EconomyManager.DEFAULT_AM_ACTION_ALARM_INEXACT_WAKEUP_BASE_PRICE;
import static android.app.tare.EconomyManager.DEFAULT_AM_ACTION_ALARM_INEXACT_WAKEUP_CTP;
import static android.app.tare.EconomyManager.DEFAULT_AM_MAX_CIRCULATION;
import static android.app.tare.EconomyManager.DEFAULT_AM_MAX_SATIATED_BALANCE;
import static android.app.tare.EconomyManager.DEFAULT_AM_MIN_SATIATED_BALANCE_OTHER_APP;
import static android.app.tare.EconomyManager.DEFAULT_AM_REWARD_NOTIFICATION_INTERACTION_INSTANT;
import static android.app.tare.EconomyManager.DEFAULT_AM_REWARD_NOTIFICATION_INTERACTION_MAX;
import static android.app.tare.EconomyManager.DEFAULT_AM_REWARD_NOTIFICATION_INTERACTION_ONGOING;
import static android.app.tare.EconomyManager.DEFAULT_AM_REWARD_NOTIFICATION_SEEN_INSTANT;
import static android.app.tare.EconomyManager.DEFAULT_AM_REWARD_NOTIFICATION_SEEN_MAX;
import static android.app.tare.EconomyManager.DEFAULT_AM_REWARD_NOTIFICATION_SEEN_ONGOING;
import static android.app.tare.EconomyManager.DEFAULT_AM_REWARD_OTHER_USER_INTERACTION_INSTANT;
import static android.app.tare.EconomyManager.DEFAULT_AM_REWARD_OTHER_USER_INTERACTION_MAX;
import static android.app.tare.EconomyManager.DEFAULT_AM_REWARD_OTHER_USER_INTERACTION_ONGOING;
import static android.app.tare.EconomyManager.DEFAULT_AM_REWARD_TOP_ACTIVITY_INSTANT;
import static android.app.tare.EconomyManager.DEFAULT_AM_REWARD_TOP_ACTIVITY_MAX;
import static android.app.tare.EconomyManager.DEFAULT_AM_REWARD_TOP_ACTIVITY_ONGOING;
import static android.app.tare.EconomyManager.DEFAULT_AM_REWARD_WIDGET_INTERACTION_INSTANT;
import static android.app.tare.EconomyManager.DEFAULT_AM_REWARD_WIDGET_INTERACTION_MAX;
import static android.app.tare.EconomyManager.DEFAULT_AM_REWARD_WIDGET_INTERACTION_ONGOING;
import static android.app.tare.EconomyManager.KEY_AM_ACTION_ALARM_ALARMCLOCK_BASE_PRICE;
import static android.app.tare.EconomyManager.KEY_AM_ACTION_ALARM_ALARMCLOCK_CTP;
import static android.app.tare.EconomyManager.KEY_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_EXACT_NONWAKEUP_BASE_PRICE;
import static android.app.tare.EconomyManager.KEY_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_EXACT_NONWAKEUP_CTP;
import static android.app.tare.EconomyManager.KEY_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_EXACT_WAKEUP_BASE_PRICE;
import static android.app.tare.EconomyManager.KEY_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_EXACT_WAKEUP_CTP;
import static android.app.tare.EconomyManager.KEY_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_INEXACT_NONWAKEUP_BASE_PRICE;
import static android.app.tare.EconomyManager.KEY_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_INEXACT_NONWAKEUP_CTP;
import static android.app.tare.EconomyManager.KEY_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_INEXACT_WAKEUP_BASE_PRICE;
import static android.app.tare.EconomyManager.KEY_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_INEXACT_WAKEUP_CTP;
import static android.app.tare.EconomyManager.KEY_AM_ACTION_ALARM_EXACT_NONWAKEUP_BASE_PRICE;
import static android.app.tare.EconomyManager.KEY_AM_ACTION_ALARM_EXACT_NONWAKEUP_CTP;
import static android.app.tare.EconomyManager.KEY_AM_ACTION_ALARM_EXACT_WAKEUP_BASE_PRICE;
import static android.app.tare.EconomyManager.KEY_AM_ACTION_ALARM_EXACT_WAKEUP_CTP;
import static android.app.tare.EconomyManager.KEY_AM_ACTION_ALARM_INEXACT_NONWAKEUP_BASE_PRICE;
import static android.app.tare.EconomyManager.KEY_AM_ACTION_ALARM_INEXACT_NONWAKEUP_CTP;
import static android.app.tare.EconomyManager.KEY_AM_ACTION_ALARM_INEXACT_WAKEUP_BASE_PRICE;
import static android.app.tare.EconomyManager.KEY_AM_ACTION_ALARM_INEXACT_WAKEUP_CTP;
import static android.app.tare.EconomyManager.KEY_AM_MAX_CIRCULATION;
import static android.app.tare.EconomyManager.KEY_AM_MAX_SATIATED_BALANCE;
import static android.app.tare.EconomyManager.KEY_AM_MIN_SATIATED_BALANCE_OTHER_APP;
import static android.app.tare.EconomyManager.KEY_AM_REWARD_NOTIFICATION_INTERACTION_INSTANT;
import static android.app.tare.EconomyManager.KEY_AM_REWARD_NOTIFICATION_INTERACTION_MAX;
import static android.app.tare.EconomyManager.KEY_AM_REWARD_NOTIFICATION_INTERACTION_ONGOING;
import static android.app.tare.EconomyManager.KEY_AM_REWARD_NOTIFICATION_SEEN_INSTANT;
import static android.app.tare.EconomyManager.KEY_AM_REWARD_NOTIFICATION_SEEN_MAX;
import static android.app.tare.EconomyManager.KEY_AM_REWARD_NOTIFICATION_SEEN_ONGOING;
import static android.app.tare.EconomyManager.KEY_AM_REWARD_OTHER_USER_INTERACTION_INSTANT;
import static android.app.tare.EconomyManager.KEY_AM_REWARD_OTHER_USER_INTERACTION_MAX;
import static android.app.tare.EconomyManager.KEY_AM_REWARD_OTHER_USER_INTERACTION_ONGOING;
import static android.app.tare.EconomyManager.KEY_AM_REWARD_TOP_ACTIVITY_INSTANT;
import static android.app.tare.EconomyManager.KEY_AM_REWARD_TOP_ACTIVITY_MAX;
import static android.app.tare.EconomyManager.KEY_AM_REWARD_TOP_ACTIVITY_ONGOING;
import static android.app.tare.EconomyManager.KEY_AM_REWARD_WIDGET_INTERACTION_INSTANT;
import static android.app.tare.EconomyManager.KEY_AM_REWARD_WIDGET_INTERACTION_MAX;
import static android.app.tare.EconomyManager.KEY_AM_REWARD_WIDGET_INTERACTION_ONGOING;
import static android.provider.Settings.Global.TARE_ALARM_MANAGER_CONSTANTS;

import static com.android.server.tare.Modifier.COST_MODIFIER_CHARGING;
import static com.android.server.tare.Modifier.COST_MODIFIER_DEVICE_IDLE;
import static com.android.server.tare.Modifier.COST_MODIFIER_POWER_SAVE_MODE;
import static com.android.server.tare.Modifier.COST_MODIFIER_PROCESS_STATE;
import static com.android.server.tare.TareUtils.arcToNarc;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.provider.Settings;
import android.util.IndentingPrintWriter;
import android.util.KeyValueListParser;
import android.util.Slog;
import android.util.SparseArray;

/**
 * Policy defining pricing information and daily ARC requirements and suggestions for
 * AlarmManager.
 */
public class AlarmManagerEconomicPolicy extends EconomicPolicy {
    private static final String TAG = "TARE- " + AlarmManagerEconomicPolicy.class.getSimpleName();

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

    private long mMinSatiatedBalance;
    private long mMaxSatiatedBalance;
    private long mMaxSatiatedCirculation;

    private final KeyValueListParser mParser = new KeyValueListParser(',');
    private final InternalResourceService mInternalResourceService;

    private final SparseArray<Action> mActions = new SparseArray<>();
    private final SparseArray<Reward> mRewards = new SparseArray<>();

    AlarmManagerEconomicPolicy(InternalResourceService irs) {
        super(irs);
        mInternalResourceService = irs;
        loadConstants("");
    }

    @Override
    void setup() {
        super.setup();
        ContentResolver resolver = mInternalResourceService.getContext().getContentResolver();
        loadConstants(Settings.Global.getString(resolver, TARE_ALARM_MANAGER_CONSTANTS));
    }

    @Override
    long getMinSatiatedBalance(final int userId, @NonNull final String pkgName) {
        // TODO: take exemption into account
        return mMinSatiatedBalance;
    }

    @Override
    long getMaxSatiatedBalance() {
        return mMaxSatiatedBalance;
    }

    @Override
    long getMaxSatiatedCirculation() {
        return mMaxSatiatedCirculation;
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

    private void loadConstants(String policyValuesString) {
        mActions.clear();
        mRewards.clear();

        try {
            mParser.setString(policyValuesString);
        } catch (IllegalArgumentException e) {
            Slog.e(TAG, "Global setting key incorrect: ", e);
        }

        mMinSatiatedBalance = arcToNarc(mParser.getInt(KEY_AM_MIN_SATIATED_BALANCE_OTHER_APP,
                DEFAULT_AM_MIN_SATIATED_BALANCE_OTHER_APP));
        mMaxSatiatedBalance = arcToNarc(mParser.getInt(KEY_AM_MAX_SATIATED_BALANCE,
                DEFAULT_AM_MAX_SATIATED_BALANCE));
        mMaxSatiatedCirculation = arcToNarc(mParser.getInt(KEY_AM_MAX_CIRCULATION,
                DEFAULT_AM_MAX_CIRCULATION));

        final long exactAllowWhileIdleWakeupBasePrice = arcToNarc(
                mParser.getInt(KEY_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_EXACT_WAKEUP_BASE_PRICE,
                        DEFAULT_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_EXACT_WAKEUP_BASE_PRICE));

        mActions.put(ACTION_ALARM_WAKEUP_EXACT_ALLOW_WHILE_IDLE,
                new Action(ACTION_ALARM_WAKEUP_EXACT_ALLOW_WHILE_IDLE,
                        arcToNarc(mParser.getInt(
                                KEY_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_EXACT_WAKEUP_CTP,
                                DEFAULT_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_EXACT_WAKEUP_CTP)),
                        exactAllowWhileIdleWakeupBasePrice));
        mActions.put(ACTION_ALARM_WAKEUP_EXACT,
                new Action(ACTION_ALARM_WAKEUP_EXACT,
                        arcToNarc(mParser.getInt(KEY_AM_ACTION_ALARM_EXACT_WAKEUP_CTP,
                                DEFAULT_AM_ACTION_ALARM_EXACT_WAKEUP_CTP)),
                        arcToNarc(mParser.getInt(KEY_AM_ACTION_ALARM_EXACT_WAKEUP_BASE_PRICE,
                                DEFAULT_AM_ACTION_ALARM_EXACT_WAKEUP_BASE_PRICE))));

        final long inexactAllowWhileIdleWakeupBasePrice =
                arcToNarc(mParser.getInt(
                        KEY_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_INEXACT_WAKEUP_BASE_PRICE,
                        DEFAULT_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_INEXACT_WAKEUP_BASE_PRICE));

        mActions.put(ACTION_ALARM_WAKEUP_INEXACT_ALLOW_WHILE_IDLE,
                new Action(ACTION_ALARM_WAKEUP_INEXACT_ALLOW_WHILE_IDLE,
                        arcToNarc(mParser.getInt(
                                KEY_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_INEXACT_WAKEUP_CTP,
                                DEFAULT_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_INEXACT_WAKEUP_CTP)),
                        inexactAllowWhileIdleWakeupBasePrice));
        mActions.put(ACTION_ALARM_WAKEUP_INEXACT,
                new Action(ACTION_ALARM_WAKEUP_INEXACT,
                        arcToNarc(mParser.getInt(
                                KEY_AM_ACTION_ALARM_INEXACT_WAKEUP_CTP,
                                DEFAULT_AM_ACTION_ALARM_INEXACT_WAKEUP_CTP)),
                        arcToNarc(mParser.getInt(
                                KEY_AM_ACTION_ALARM_INEXACT_WAKEUP_BASE_PRICE,
                                DEFAULT_AM_ACTION_ALARM_INEXACT_WAKEUP_BASE_PRICE))));

        final long exactAllowWhileIdleNonWakeupBasePrice =
                arcToNarc(mParser.getInt(
                        KEY_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_EXACT_NONWAKEUP_BASE_PRICE,
                        DEFAULT_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_INEXACT_NONWAKEUP_BASE_PRICE));

        mActions.put(ACTION_ALARM_NONWAKEUP_EXACT_ALLOW_WHILE_IDLE,
                new Action(ACTION_ALARM_NONWAKEUP_EXACT_ALLOW_WHILE_IDLE,
                        arcToNarc(mParser.getInt(
                                KEY_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_EXACT_NONWAKEUP_CTP,
                                DEFAULT_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_EXACT_NONWAKEUP_CTP)),
                        exactAllowWhileIdleNonWakeupBasePrice));
        mActions.put(ACTION_ALARM_NONWAKEUP_EXACT,
                new Action(ACTION_ALARM_NONWAKEUP_EXACT,
                        arcToNarc(mParser.getInt(
                                KEY_AM_ACTION_ALARM_EXACT_NONWAKEUP_CTP,
                                DEFAULT_AM_ACTION_ALARM_EXACT_NONWAKEUP_CTP)),
                        arcToNarc(mParser.getInt(
                                KEY_AM_ACTION_ALARM_EXACT_NONWAKEUP_BASE_PRICE,
                                DEFAULT_AM_ACTION_ALARM_EXACT_NONWAKEUP_BASE_PRICE))));

        final long inexactAllowWhileIdleNonWakeupBasePrice =
                arcToNarc(mParser.getInt(
                        KEY_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_INEXACT_NONWAKEUP_BASE_PRICE,
                        DEFAULT_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_INEXACT_NONWAKEUP_BASE_PRICE));

        mActions.put(ACTION_ALARM_NONWAKEUP_INEXACT_ALLOW_WHILE_IDLE,
                new Action(ACTION_ALARM_NONWAKEUP_INEXACT_ALLOW_WHILE_IDLE,
                        arcToNarc(mParser.getInt(
                                KEY_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_INEXACT_NONWAKEUP_CTP,
                                DEFAULT_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_INEXACT_NONWAKEUP_CTP)),
                        inexactAllowWhileIdleNonWakeupBasePrice));
        mActions.put(ACTION_ALARM_NONWAKEUP_INEXACT,
                new Action(ACTION_ALARM_NONWAKEUP_INEXACT,
                        arcToNarc(mParser.getInt(
                                KEY_AM_ACTION_ALARM_INEXACT_NONWAKEUP_CTP,
                                DEFAULT_AM_ACTION_ALARM_INEXACT_NONWAKEUP_CTP)),
                        arcToNarc(mParser.getInt(
                                KEY_AM_ACTION_ALARM_INEXACT_NONWAKEUP_BASE_PRICE,
                                DEFAULT_AM_ACTION_ALARM_INEXACT_NONWAKEUP_BASE_PRICE))));
        mActions.put(ACTION_ALARM_CLOCK,
                new Action(ACTION_ALARM_CLOCK,
                        arcToNarc(mParser.getInt(
                                KEY_AM_ACTION_ALARM_ALARMCLOCK_CTP,
                                DEFAULT_AM_ACTION_ALARM_ALARMCLOCK_CTP)),
                        arcToNarc(mParser.getInt(
                                KEY_AM_ACTION_ALARM_ALARMCLOCK_BASE_PRICE,
                                DEFAULT_AM_ACTION_ALARM_ALARMCLOCK_BASE_PRICE))));

        mRewards.put(REWARD_TOP_ACTIVITY, new Reward(REWARD_TOP_ACTIVITY,
                arcToNarc(mParser.getInt(KEY_AM_REWARD_TOP_ACTIVITY_INSTANT,
                        DEFAULT_AM_REWARD_TOP_ACTIVITY_INSTANT)),
                (long) (arcToNarc(1) * mParser.getFloat(KEY_AM_REWARD_TOP_ACTIVITY_ONGOING,
                        DEFAULT_AM_REWARD_TOP_ACTIVITY_ONGOING)),
                arcToNarc(mParser.getInt(KEY_AM_REWARD_TOP_ACTIVITY_MAX,
                        DEFAULT_AM_REWARD_TOP_ACTIVITY_MAX))));
        mRewards.put(REWARD_NOTIFICATION_SEEN, new Reward(REWARD_NOTIFICATION_SEEN,
                arcToNarc(mParser.getInt(KEY_AM_REWARD_NOTIFICATION_SEEN_INSTANT,
                        DEFAULT_AM_REWARD_NOTIFICATION_SEEN_INSTANT)),
                arcToNarc(mParser.getInt(KEY_AM_REWARD_NOTIFICATION_SEEN_ONGOING,
                        DEFAULT_AM_REWARD_NOTIFICATION_SEEN_ONGOING)),
                arcToNarc(mParser.getInt(KEY_AM_REWARD_NOTIFICATION_SEEN_MAX,
                        DEFAULT_AM_REWARD_NOTIFICATION_SEEN_MAX))));
        mRewards.put(REWARD_NOTIFICATION_INTERACTION,
                new Reward(REWARD_NOTIFICATION_INTERACTION,
                        arcToNarc(mParser.getInt(
                                KEY_AM_REWARD_NOTIFICATION_INTERACTION_INSTANT,
                                DEFAULT_AM_REWARD_NOTIFICATION_INTERACTION_INSTANT)),
                        arcToNarc(mParser.getInt(
                                KEY_AM_REWARD_NOTIFICATION_INTERACTION_ONGOING,
                                DEFAULT_AM_REWARD_NOTIFICATION_INTERACTION_ONGOING)),
                        arcToNarc(mParser.getInt(
                                KEY_AM_REWARD_NOTIFICATION_INTERACTION_MAX,
                                DEFAULT_AM_REWARD_NOTIFICATION_INTERACTION_MAX))));
        mRewards.put(REWARD_WIDGET_INTERACTION, new Reward(REWARD_WIDGET_INTERACTION,
                arcToNarc(mParser.getInt(KEY_AM_REWARD_WIDGET_INTERACTION_INSTANT,
                        DEFAULT_AM_REWARD_WIDGET_INTERACTION_INSTANT)),
                arcToNarc(mParser.getInt(KEY_AM_REWARD_WIDGET_INTERACTION_ONGOING,
                        DEFAULT_AM_REWARD_WIDGET_INTERACTION_ONGOING)),
                arcToNarc(mParser.getInt(KEY_AM_REWARD_WIDGET_INTERACTION_MAX,
                        DEFAULT_AM_REWARD_WIDGET_INTERACTION_MAX))));
        mRewards.put(REWARD_OTHER_USER_INTERACTION,
                new Reward(REWARD_OTHER_USER_INTERACTION,
                        arcToNarc(mParser.getInt(KEY_AM_REWARD_OTHER_USER_INTERACTION_INSTANT,
                                DEFAULT_AM_REWARD_OTHER_USER_INTERACTION_INSTANT)),
                        arcToNarc(mParser.getInt(
                                KEY_AM_REWARD_OTHER_USER_INTERACTION_ONGOING,
                                DEFAULT_AM_REWARD_OTHER_USER_INTERACTION_ONGOING)),
                        arcToNarc(mParser.getInt(
                                KEY_AM_REWARD_OTHER_USER_INTERACTION_MAX,
                                DEFAULT_AM_REWARD_OTHER_USER_INTERACTION_MAX))));
    }

    @Override
    void dump(IndentingPrintWriter pw) {
        pw.println("Actions:");
        pw.increaseIndent();
        for (int i = 0; i < mActions.size(); ++i) {
            dumpAction(pw, mActions.valueAt(i));
        }
        pw.decreaseIndent();

        pw.println();
        pw.println("Rewards:");
        pw.increaseIndent();
        for (int i = 0; i < mRewards.size(); ++i) {
            dumpReward(pw, mRewards.valueAt(i));
        }
        pw.decreaseIndent();
    }
}
