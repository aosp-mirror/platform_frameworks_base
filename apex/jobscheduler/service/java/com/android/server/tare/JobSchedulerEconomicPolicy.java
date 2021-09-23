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

import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_DEFAULT_RUNNING_BASE_PRICE;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_DEFAULT_RUNNING_CTP;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_DEFAULT_START_BASE_PRICE;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_DEFAULT_START_CTP;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_HIGH_RUNNING_BASE_PRICE;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_HIGH_RUNNING_CTP;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_HIGH_START_BASE_PRICE;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_HIGH_START_CTP;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_LOW_RUNNING_BASE_PRICE;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_LOW_RUNNING_CTP;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_LOW_START_BASE_PRICE;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_LOW_START_CTP;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_MAX_RUNNING_BASE_PRICE;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_MAX_RUNNING_CTP;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_MAX_START_BASE_PRICE;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_MAX_START_CTP;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_MIN_RUNNING_BASE_PRICE;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_MIN_RUNNING_CTP;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_MIN_START_BASE_PRICE;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_MIN_START_CTP;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_TIMEOUT_PENALTY_BASE_PRICE;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_TIMEOUT_PENALTY_CTP;
import static android.app.tare.EconomyManager.DEFAULT_JS_MAX_CIRCULATION;
import static android.app.tare.EconomyManager.DEFAULT_JS_MAX_SATIATED_BALANCE;
import static android.app.tare.EconomyManager.DEFAULT_JS_MIN_SATIATED_BALANCE_OTHER_APP;
import static android.app.tare.EconomyManager.DEFAULT_JS_REWARD_NOTIFICATION_INTERACTION_INSTANT;
import static android.app.tare.EconomyManager.DEFAULT_JS_REWARD_NOTIFICATION_INTERACTION_MAX;
import static android.app.tare.EconomyManager.DEFAULT_JS_REWARD_NOTIFICATION_INTERACTION_ONGOING;
import static android.app.tare.EconomyManager.DEFAULT_JS_REWARD_NOTIFICATION_SEEN_INSTANT;
import static android.app.tare.EconomyManager.DEFAULT_JS_REWARD_NOTIFICATION_SEEN_MAX;
import static android.app.tare.EconomyManager.DEFAULT_JS_REWARD_NOTIFICATION_SEEN_ONGOING;
import static android.app.tare.EconomyManager.DEFAULT_JS_REWARD_OTHER_USER_INTERACTION_INSTANT;
import static android.app.tare.EconomyManager.DEFAULT_JS_REWARD_OTHER_USER_INTERACTION_MAX;
import static android.app.tare.EconomyManager.DEFAULT_JS_REWARD_OTHER_USER_INTERACTION_ONGOING;
import static android.app.tare.EconomyManager.DEFAULT_JS_REWARD_TOP_ACTIVITY_INSTANT;
import static android.app.tare.EconomyManager.DEFAULT_JS_REWARD_TOP_ACTIVITY_MAX;
import static android.app.tare.EconomyManager.DEFAULT_JS_REWARD_TOP_ACTIVITY_ONGOING;
import static android.app.tare.EconomyManager.DEFAULT_JS_REWARD_WIDGET_INTERACTION_INSTANT;
import static android.app.tare.EconomyManager.DEFAULT_JS_REWARD_WIDGET_INTERACTION_MAX;
import static android.app.tare.EconomyManager.DEFAULT_JS_REWARD_WIDGET_INTERACTION_ONGOING;
import static android.app.tare.EconomyManager.KEY_JS_ACTION_JOB_DEFAULT_RUNNING_BASE_PRICE;
import static android.app.tare.EconomyManager.KEY_JS_ACTION_JOB_DEFAULT_RUNNING_CTP;
import static android.app.tare.EconomyManager.KEY_JS_ACTION_JOB_DEFAULT_START_BASE_PRICE;
import static android.app.tare.EconomyManager.KEY_JS_ACTION_JOB_DEFAULT_START_CTP;
import static android.app.tare.EconomyManager.KEY_JS_ACTION_JOB_HIGH_RUNNING_BASE_PRICE;
import static android.app.tare.EconomyManager.KEY_JS_ACTION_JOB_HIGH_RUNNING_CTP;
import static android.app.tare.EconomyManager.KEY_JS_ACTION_JOB_HIGH_START_BASE_PRICE;
import static android.app.tare.EconomyManager.KEY_JS_ACTION_JOB_HIGH_START_CTP;
import static android.app.tare.EconomyManager.KEY_JS_ACTION_JOB_LOW_RUNNING_BASE_PRICE;
import static android.app.tare.EconomyManager.KEY_JS_ACTION_JOB_LOW_RUNNING_CTP;
import static android.app.tare.EconomyManager.KEY_JS_ACTION_JOB_LOW_START_BASE_PRICE;
import static android.app.tare.EconomyManager.KEY_JS_ACTION_JOB_LOW_START_CTP;
import static android.app.tare.EconomyManager.KEY_JS_ACTION_JOB_MAX_RUNNING_BASE_PRICE;
import static android.app.tare.EconomyManager.KEY_JS_ACTION_JOB_MAX_RUNNING_CTP;
import static android.app.tare.EconomyManager.KEY_JS_ACTION_JOB_MAX_START_BASE_PRICE;
import static android.app.tare.EconomyManager.KEY_JS_ACTION_JOB_MAX_START_CTP;
import static android.app.tare.EconomyManager.KEY_JS_ACTION_JOB_MIN_RUNNING_BASE_PRICE;
import static android.app.tare.EconomyManager.KEY_JS_ACTION_JOB_MIN_RUNNING_CTP;
import static android.app.tare.EconomyManager.KEY_JS_ACTION_JOB_MIN_START_BASE_PRICE;
import static android.app.tare.EconomyManager.KEY_JS_ACTION_JOB_MIN_START_CTP;
import static android.app.tare.EconomyManager.KEY_JS_ACTION_JOB_TIMEOUT_PENALTY_BASE_PRICE;
import static android.app.tare.EconomyManager.KEY_JS_ACTION_JOB_TIMEOUT_PENALTY_CTP;
import static android.app.tare.EconomyManager.KEY_JS_MAX_CIRCULATION;
import static android.app.tare.EconomyManager.KEY_JS_MAX_SATIATED_BALANCE;
import static android.app.tare.EconomyManager.KEY_JS_MIN_SATIATED_BALANCE_OTHER_APP;
import static android.app.tare.EconomyManager.KEY_JS_REWARD_NOTIFICATION_INTERACTION_INSTANT;
import static android.app.tare.EconomyManager.KEY_JS_REWARD_NOTIFICATION_INTERACTION_MAX;
import static android.app.tare.EconomyManager.KEY_JS_REWARD_NOTIFICATION_INTERACTION_ONGOING;
import static android.app.tare.EconomyManager.KEY_JS_REWARD_NOTIFICATION_SEEN_INSTANT;
import static android.app.tare.EconomyManager.KEY_JS_REWARD_NOTIFICATION_SEEN_MAX;
import static android.app.tare.EconomyManager.KEY_JS_REWARD_NOTIFICATION_SEEN_ONGOING;
import static android.app.tare.EconomyManager.KEY_JS_REWARD_OTHER_USER_INTERACTION_INSTANT;
import static android.app.tare.EconomyManager.KEY_JS_REWARD_OTHER_USER_INTERACTION_MAX;
import static android.app.tare.EconomyManager.KEY_JS_REWARD_OTHER_USER_INTERACTION_ONGOING;
import static android.app.tare.EconomyManager.KEY_JS_REWARD_TOP_ACTIVITY_INSTANT;
import static android.app.tare.EconomyManager.KEY_JS_REWARD_TOP_ACTIVITY_MAX;
import static android.app.tare.EconomyManager.KEY_JS_REWARD_TOP_ACTIVITY_ONGOING;
import static android.app.tare.EconomyManager.KEY_JS_REWARD_WIDGET_INTERACTION_INSTANT;
import static android.app.tare.EconomyManager.KEY_JS_REWARD_WIDGET_INTERACTION_MAX;
import static android.app.tare.EconomyManager.KEY_JS_REWARD_WIDGET_INTERACTION_ONGOING;
import static android.provider.Settings.Global.TARE_JOB_SCHEDULER_CONSTANTS;

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
 * JobScheduler.
 */
public class JobSchedulerEconomicPolicy extends EconomicPolicy {
    private static final String TAG = "TARE- " + JobSchedulerEconomicPolicy.class.getSimpleName();

    public static final int ACTION_JOB_MAX_START = TYPE_ACTION | POLICY_JS | 0;
    public static final int ACTION_JOB_MAX_RUNNING = TYPE_ACTION | POLICY_JS | 1;
    public static final int ACTION_JOB_HIGH_START = TYPE_ACTION | POLICY_JS | 2;
    public static final int ACTION_JOB_HIGH_RUNNING = TYPE_ACTION | POLICY_JS | 3;
    public static final int ACTION_JOB_DEFAULT_START = TYPE_ACTION | POLICY_JS | 4;
    public static final int ACTION_JOB_DEFAULT_RUNNING = TYPE_ACTION | POLICY_JS | 5;
    public static final int ACTION_JOB_LOW_START = TYPE_ACTION | POLICY_JS | 6;
    public static final int ACTION_JOB_LOW_RUNNING = TYPE_ACTION | POLICY_JS | 7;
    public static final int ACTION_JOB_MIN_START = TYPE_ACTION | POLICY_JS | 8;
    public static final int ACTION_JOB_MIN_RUNNING = TYPE_ACTION | POLICY_JS | 9;
    public static final int ACTION_JOB_TIMEOUT = TYPE_ACTION | POLICY_JS | 10;

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

    JobSchedulerEconomicPolicy(InternalResourceService irs) {
        super(irs);
        mInternalResourceService = irs;
        loadConstants("");
    }

    @Override
    void setup() {
        super.setup();
        ContentResolver resolver = mInternalResourceService.getContext().getContentResolver();
        loadConstants(Settings.Global.getString(resolver, TARE_JOB_SCHEDULER_CONSTANTS));
    }

    @Override
    long getMinSatiatedBalance(final int userId, @NonNull final String pkgName) {
        // TODO: incorporate time since usage
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

        mMinSatiatedBalance = arcToNarc(
                mParser.getInt(KEY_JS_MIN_SATIATED_BALANCE_OTHER_APP,
                        DEFAULT_JS_MIN_SATIATED_BALANCE_OTHER_APP));
        mMaxSatiatedBalance = arcToNarc(mParser.getInt(KEY_JS_MAX_SATIATED_BALANCE,
                DEFAULT_JS_MAX_SATIATED_BALANCE));
        mMaxSatiatedCirculation = arcToNarc(mParser.getInt(KEY_JS_MAX_CIRCULATION,
                DEFAULT_JS_MAX_CIRCULATION));

        mActions.put(ACTION_JOB_MAX_START, new Action(ACTION_JOB_MAX_START,
                arcToNarc(mParser.getInt(KEY_JS_ACTION_JOB_MAX_START_CTP,
                        DEFAULT_JS_ACTION_JOB_MAX_START_CTP)),
                arcToNarc(mParser.getInt(KEY_JS_ACTION_JOB_MAX_START_BASE_PRICE,
                        DEFAULT_JS_ACTION_JOB_MAX_START_BASE_PRICE))));
        mActions.put(ACTION_JOB_MAX_RUNNING, new Action(ACTION_JOB_MAX_RUNNING,
                arcToNarc(mParser.getInt(KEY_JS_ACTION_JOB_MAX_RUNNING_CTP,
                        DEFAULT_JS_ACTION_JOB_MAX_RUNNING_CTP)),
                arcToNarc(mParser.getInt(KEY_JS_ACTION_JOB_MAX_RUNNING_BASE_PRICE,
                        DEFAULT_JS_ACTION_JOB_MAX_RUNNING_BASE_PRICE))));
        mActions.put(ACTION_JOB_HIGH_START, new Action(ACTION_JOB_HIGH_START,
                arcToNarc(mParser.getInt(KEY_JS_ACTION_JOB_HIGH_START_CTP,
                        DEFAULT_JS_ACTION_JOB_HIGH_START_CTP)),
                arcToNarc(mParser.getInt(KEY_JS_ACTION_JOB_HIGH_START_BASE_PRICE,
                        DEFAULT_JS_ACTION_JOB_HIGH_START_BASE_PRICE))));
        mActions.put(ACTION_JOB_HIGH_RUNNING, new Action(ACTION_JOB_HIGH_RUNNING,
                arcToNarc(mParser.getInt(KEY_JS_ACTION_JOB_HIGH_RUNNING_CTP,
                        DEFAULT_JS_ACTION_JOB_HIGH_RUNNING_CTP)),
                arcToNarc(mParser.getInt(KEY_JS_ACTION_JOB_HIGH_RUNNING_BASE_PRICE,
                        DEFAULT_JS_ACTION_JOB_HIGH_RUNNING_BASE_PRICE))));
        mActions.put(ACTION_JOB_DEFAULT_START, new Action(ACTION_JOB_DEFAULT_START,
                arcToNarc(mParser.getInt(KEY_JS_ACTION_JOB_DEFAULT_START_CTP,
                        DEFAULT_JS_ACTION_JOB_DEFAULT_START_CTP)),
                arcToNarc(mParser.getInt(KEY_JS_ACTION_JOB_DEFAULT_START_BASE_PRICE,
                        DEFAULT_JS_ACTION_JOB_DEFAULT_START_BASE_PRICE))));
        mActions.put(ACTION_JOB_DEFAULT_RUNNING, new Action(ACTION_JOB_DEFAULT_RUNNING,
                arcToNarc(mParser.getInt(KEY_JS_ACTION_JOB_DEFAULT_RUNNING_CTP,
                        DEFAULT_JS_ACTION_JOB_DEFAULT_RUNNING_CTP)),
                arcToNarc(mParser.getInt(KEY_JS_ACTION_JOB_DEFAULT_RUNNING_BASE_PRICE,
                        DEFAULT_JS_ACTION_JOB_DEFAULT_RUNNING_BASE_PRICE))));
        mActions.put(ACTION_JOB_LOW_START, new Action(ACTION_JOB_LOW_START,
                arcToNarc(mParser.getInt(KEY_JS_ACTION_JOB_LOW_START_CTP,
                        DEFAULT_JS_ACTION_JOB_LOW_START_CTP)),
                arcToNarc(mParser.getInt(KEY_JS_ACTION_JOB_LOW_START_BASE_PRICE,
                        DEFAULT_JS_ACTION_JOB_LOW_START_BASE_PRICE))));
        mActions.put(ACTION_JOB_LOW_RUNNING, new Action(ACTION_JOB_LOW_RUNNING,
                arcToNarc(mParser.getInt(KEY_JS_ACTION_JOB_LOW_RUNNING_CTP,
                        DEFAULT_JS_ACTION_JOB_LOW_RUNNING_CTP)),
                arcToNarc(mParser.getInt(KEY_JS_ACTION_JOB_LOW_RUNNING_BASE_PRICE,
                        DEFAULT_JS_ACTION_JOB_LOW_RUNNING_BASE_PRICE))));
        mActions.put(ACTION_JOB_MIN_START, new Action(ACTION_JOB_MIN_START,
                arcToNarc(mParser.getInt(KEY_JS_ACTION_JOB_MIN_START_CTP,
                        DEFAULT_JS_ACTION_JOB_MIN_START_CTP)),
                arcToNarc(mParser.getInt(KEY_JS_ACTION_JOB_MIN_START_BASE_PRICE,
                        DEFAULT_JS_ACTION_JOB_MIN_START_BASE_PRICE))));
        mActions.put(ACTION_JOB_MIN_RUNNING, new Action(ACTION_JOB_MIN_RUNNING,
                arcToNarc(mParser.getInt(KEY_JS_ACTION_JOB_MIN_RUNNING_CTP,
                        DEFAULT_JS_ACTION_JOB_MIN_RUNNING_CTP)),
                arcToNarc(mParser.getInt(KEY_JS_ACTION_JOB_MIN_RUNNING_BASE_PRICE,
                        DEFAULT_JS_ACTION_JOB_MIN_RUNNING_BASE_PRICE))));
        mActions.put(ACTION_JOB_TIMEOUT, new Action(ACTION_JOB_TIMEOUT,
                arcToNarc(mParser.getInt(KEY_JS_ACTION_JOB_TIMEOUT_PENALTY_CTP,
                        DEFAULT_JS_ACTION_JOB_TIMEOUT_PENALTY_CTP)),
                arcToNarc(mParser.getInt(KEY_JS_ACTION_JOB_TIMEOUT_PENALTY_BASE_PRICE,
                        DEFAULT_JS_ACTION_JOB_TIMEOUT_PENALTY_BASE_PRICE))));

        mRewards.put(REWARD_TOP_ACTIVITY, new Reward(REWARD_TOP_ACTIVITY,
                arcToNarc(mParser.getInt(KEY_JS_REWARD_TOP_ACTIVITY_INSTANT,
                        DEFAULT_JS_REWARD_TOP_ACTIVITY_INSTANT)),
                (long) (arcToNarc(1) * mParser.getFloat(KEY_JS_REWARD_TOP_ACTIVITY_ONGOING,
                        DEFAULT_JS_REWARD_TOP_ACTIVITY_ONGOING)),
                arcToNarc(mParser.getInt(KEY_JS_REWARD_TOP_ACTIVITY_MAX,
                        DEFAULT_JS_REWARD_TOP_ACTIVITY_MAX))));
        mRewards.put(REWARD_NOTIFICATION_SEEN, new Reward(REWARD_NOTIFICATION_SEEN,
                arcToNarc(mParser.getInt(KEY_JS_REWARD_NOTIFICATION_SEEN_INSTANT,
                        DEFAULT_JS_REWARD_NOTIFICATION_SEEN_INSTANT)),
                arcToNarc(mParser.getInt(KEY_JS_REWARD_NOTIFICATION_SEEN_ONGOING,
                        DEFAULT_JS_REWARD_NOTIFICATION_SEEN_ONGOING)),
                arcToNarc(mParser.getInt(KEY_JS_REWARD_NOTIFICATION_SEEN_MAX,
                        DEFAULT_JS_REWARD_NOTIFICATION_SEEN_MAX))));
        mRewards.put(REWARD_NOTIFICATION_INTERACTION,
                new Reward(REWARD_NOTIFICATION_INTERACTION,
                        arcToNarc(mParser.getInt(
                                KEY_JS_REWARD_NOTIFICATION_INTERACTION_INSTANT,
                                DEFAULT_JS_REWARD_NOTIFICATION_INTERACTION_INSTANT)),
                        arcToNarc(mParser.getInt(
                                KEY_JS_REWARD_NOTIFICATION_INTERACTION_ONGOING,
                                DEFAULT_JS_REWARD_NOTIFICATION_INTERACTION_ONGOING)),
                        arcToNarc(mParser.getInt(
                                KEY_JS_REWARD_NOTIFICATION_INTERACTION_MAX,
                                DEFAULT_JS_REWARD_NOTIFICATION_INTERACTION_MAX))));
        mRewards.put(REWARD_WIDGET_INTERACTION, new Reward(REWARD_WIDGET_INTERACTION,
                arcToNarc(mParser.getInt(KEY_JS_REWARD_WIDGET_INTERACTION_INSTANT,
                        DEFAULT_JS_REWARD_WIDGET_INTERACTION_INSTANT)),
                arcToNarc(mParser.getInt(KEY_JS_REWARD_WIDGET_INTERACTION_ONGOING,
                        DEFAULT_JS_REWARD_WIDGET_INTERACTION_ONGOING)),
                arcToNarc(mParser.getInt(KEY_JS_REWARD_WIDGET_INTERACTION_MAX,
                        DEFAULT_JS_REWARD_WIDGET_INTERACTION_MAX))));
        mRewards.put(REWARD_OTHER_USER_INTERACTION,
                new Reward(REWARD_OTHER_USER_INTERACTION,
                        arcToNarc(mParser.getInt(KEY_JS_REWARD_OTHER_USER_INTERACTION_INSTANT,
                                DEFAULT_JS_REWARD_OTHER_USER_INTERACTION_INSTANT)),
                        arcToNarc(mParser.getInt(
                                KEY_JS_REWARD_OTHER_USER_INTERACTION_ONGOING,
                                DEFAULT_JS_REWARD_OTHER_USER_INTERACTION_ONGOING)),
                        arcToNarc(mParser.getInt(
                                KEY_JS_REWARD_OTHER_USER_INTERACTION_MAX,
                                DEFAULT_JS_REWARD_OTHER_USER_INTERACTION_MAX))));
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
