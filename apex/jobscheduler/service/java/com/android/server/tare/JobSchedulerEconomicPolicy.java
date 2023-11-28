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

import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_DEFAULT_RUNNING_BASE_PRICE_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_DEFAULT_RUNNING_CTP_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_DEFAULT_START_BASE_PRICE_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_DEFAULT_START_CTP_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_HIGH_RUNNING_BASE_PRICE_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_HIGH_RUNNING_CTP_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_HIGH_START_BASE_PRICE_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_HIGH_START_CTP_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_LOW_RUNNING_BASE_PRICE_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_LOW_RUNNING_CTP_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_LOW_START_BASE_PRICE_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_LOW_START_CTP_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_MAX_RUNNING_BASE_PRICE_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_MAX_RUNNING_CTP_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_MAX_START_BASE_PRICE_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_MAX_START_CTP_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_MIN_RUNNING_BASE_PRICE_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_MIN_RUNNING_CTP_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_MIN_START_BASE_PRICE_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_MIN_START_CTP_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_TIMEOUT_PENALTY_BASE_PRICE_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_ACTION_JOB_TIMEOUT_PENALTY_CTP_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_INITIAL_CONSUMPTION_LIMIT_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_MAX_CONSUMPTION_LIMIT_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_MAX_SATIATED_BALANCE_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_MIN_CONSUMPTION_LIMIT_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_MIN_SATIATED_BALANCE_EXEMPTED_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_MIN_SATIATED_BALANCE_HEADLESS_SYSTEM_APP_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_MIN_SATIATED_BALANCE_INCREMENT_APP_UPDATER_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_MIN_SATIATED_BALANCE_OTHER_APP_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_REWARD_APP_INSTALL_INSTANT_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_REWARD_APP_INSTALL_MAX_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_REWARD_APP_INSTALL_ONGOING_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_REWARD_NOTIFICATION_INTERACTION_INSTANT_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_REWARD_NOTIFICATION_INTERACTION_MAX_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_REWARD_NOTIFICATION_INTERACTION_ONGOING_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_REWARD_NOTIFICATION_SEEN_INSTANT_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_REWARD_NOTIFICATION_SEEN_MAX_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_REWARD_NOTIFICATION_SEEN_ONGOING_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_REWARD_OTHER_USER_INTERACTION_INSTANT_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_REWARD_OTHER_USER_INTERACTION_MAX_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_REWARD_OTHER_USER_INTERACTION_ONGOING_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_REWARD_TOP_ACTIVITY_INSTANT_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_REWARD_TOP_ACTIVITY_MAX_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_REWARD_TOP_ACTIVITY_ONGOING_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_REWARD_WIDGET_INTERACTION_INSTANT_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_REWARD_WIDGET_INTERACTION_MAX_CAKES;
import static android.app.tare.EconomyManager.DEFAULT_JS_REWARD_WIDGET_INTERACTION_ONGOING_CAKES;
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
import static android.app.tare.EconomyManager.KEY_JS_INITIAL_CONSUMPTION_LIMIT;
import static android.app.tare.EconomyManager.KEY_JS_MAX_CONSUMPTION_LIMIT;
import static android.app.tare.EconomyManager.KEY_JS_MAX_SATIATED_BALANCE;
import static android.app.tare.EconomyManager.KEY_JS_MIN_CONSUMPTION_LIMIT;
import static android.app.tare.EconomyManager.KEY_JS_MIN_SATIATED_BALANCE_EXEMPTED;
import static android.app.tare.EconomyManager.KEY_JS_MIN_SATIATED_BALANCE_HEADLESS_SYSTEM_APP;
import static android.app.tare.EconomyManager.KEY_JS_MIN_SATIATED_BALANCE_INCREMENT_APP_UPDATER;
import static android.app.tare.EconomyManager.KEY_JS_MIN_SATIATED_BALANCE_OTHER_APP;
import static android.app.tare.EconomyManager.KEY_JS_REWARD_APP_INSTALL_INSTANT;
import static android.app.tare.EconomyManager.KEY_JS_REWARD_APP_INSTALL_MAX;
import static android.app.tare.EconomyManager.KEY_JS_REWARD_APP_INSTALL_ONGOING;
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
import static android.app.tare.EconomyManager.arcToCake;
import static android.provider.Settings.Global.TARE_JOB_SCHEDULER_CONSTANTS;

import static com.android.server.tare.Modifier.COST_MODIFIER_CHARGING;
import static com.android.server.tare.Modifier.COST_MODIFIER_DEVICE_IDLE;
import static com.android.server.tare.Modifier.COST_MODIFIER_POWER_SAVE_MODE;
import static com.android.server.tare.Modifier.COST_MODIFIER_PROCESS_STATE;
import static com.android.server.tare.TareUtils.appToString;
import static com.android.server.tare.TareUtils.cakeToString;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.provider.DeviceConfig;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.SparseArray;

/**
 * Policy defining pricing information and daily ARC requirements and suggestions for
 * JobScheduler.
 */
public class JobSchedulerEconomicPolicy extends EconomicPolicy {
    private static final String TAG = "TARE- " + JobSchedulerEconomicPolicy.class.getSimpleName();

    public static final int ACTION_JOB_MAX_START = TYPE_ACTION | POLICY_JOB | 0;
    public static final int ACTION_JOB_MAX_RUNNING = TYPE_ACTION | POLICY_JOB | 1;
    public static final int ACTION_JOB_HIGH_START = TYPE_ACTION | POLICY_JOB | 2;
    public static final int ACTION_JOB_HIGH_RUNNING = TYPE_ACTION | POLICY_JOB | 3;
    public static final int ACTION_JOB_DEFAULT_START = TYPE_ACTION | POLICY_JOB | 4;
    public static final int ACTION_JOB_DEFAULT_RUNNING = TYPE_ACTION | POLICY_JOB | 5;
    public static final int ACTION_JOB_LOW_START = TYPE_ACTION | POLICY_JOB | 6;
    public static final int ACTION_JOB_LOW_RUNNING = TYPE_ACTION | POLICY_JOB | 7;
    public static final int ACTION_JOB_MIN_START = TYPE_ACTION | POLICY_JOB | 8;
    public static final int ACTION_JOB_MIN_RUNNING = TYPE_ACTION | POLICY_JOB | 9;
    public static final int ACTION_JOB_TIMEOUT = TYPE_ACTION | POLICY_JOB | 10;

    public static final int REWARD_APP_INSTALL = TYPE_REWARD | POLICY_JOB | 0;

    private static final int[] COST_MODIFIERS = new int[]{
            COST_MODIFIER_CHARGING,
            COST_MODIFIER_DEVICE_IDLE,
            COST_MODIFIER_POWER_SAVE_MODE,
            COST_MODIFIER_PROCESS_STATE
    };

    private long mMinSatiatedBalanceExempted;
    private long mMinSatiatedBalanceHeadlessSystemApp;
    private long mMinSatiatedBalanceOther;
    private long mMinSatiatedBalanceIncrementalAppUpdater;
    private long mMaxSatiatedBalance;
    private long mInitialSatiatedConsumptionLimit;
    private long mMinSatiatedConsumptionLimit;
    private long mMaxSatiatedConsumptionLimit;

    private final Injector mInjector;

    private final SparseArray<Action> mActions = new SparseArray<>();
    private final SparseArray<Reward> mRewards = new SparseArray<>();

    JobSchedulerEconomicPolicy(InternalResourceService irs, Injector injector) {
        super(irs);
        mInjector = injector;
        loadConstants("", null);
    }

    @Override
    void setup(@NonNull DeviceConfig.Properties properties) {
        super.setup(properties);
        final ContentResolver resolver = mIrs.getContext().getContentResolver();
        loadConstants(mInjector.getSettingsGlobalString(resolver, TARE_JOB_SCHEDULER_CONSTANTS),
                properties);
    }

    @Override
    long getMinSatiatedBalance(final int userId, @NonNull final String pkgName) {
        if (mIrs.isPackageRestricted(userId, pkgName)) {
            return 0;
        }

        final long baseBalance;
        if (mIrs.isPackageExempted(userId, pkgName)) {
            baseBalance = mMinSatiatedBalanceExempted;
        } else if (mIrs.isHeadlessSystemApp(userId, pkgName)) {
            baseBalance = mMinSatiatedBalanceHeadlessSystemApp;
        } else {
            baseBalance = mMinSatiatedBalanceOther;
        }

        long minBalance = baseBalance;

        final int updateResponsibilityCount = mIrs.getAppUpdateResponsibilityCount(userId, pkgName);
        minBalance += updateResponsibilityCount * mMinSatiatedBalanceIncrementalAppUpdater;

        return Math.min(minBalance, mMaxSatiatedBalance);
    }

    @Override
    long getMaxSatiatedBalance(int userId, @NonNull String pkgName) {
        if (mIrs.isPackageRestricted(userId, pkgName)) {
            return 0;
        }
        final InstalledPackageInfo ipo = mIrs.getInstalledPackageInfo(userId, pkgName);
        if (ipo == null) {
            Slog.wtfStack(TAG,
                    "Tried to get max balance of invalid app: " + appToString(userId, pkgName));
        } else {
            // A system installer's max balance is elevated for some time after first boot so
            // they can use jobs to download and install apps.
            if (ipo.isSystemInstaller) {
                final long timeSinceFirstSetupMs = mIrs.getRealtimeSinceFirstSetupMs();
                final boolean stillExempted = timeSinceFirstSetupMs
                        < InternalResourceService.INSTALLER_FIRST_SETUP_GRACE_PERIOD_MS;
                if (stillExempted) {
                    return mMaxSatiatedConsumptionLimit;
                }
            }
        }
        return mMaxSatiatedBalance;
    }

    @Override
    long getInitialSatiatedConsumptionLimit() {
        return mInitialSatiatedConsumptionLimit;
    }

    @Override
    long getMinSatiatedConsumptionLimit() {
        return mMinSatiatedConsumptionLimit;
    }

    @Override
    long getMaxSatiatedConsumptionLimit() {
        return mMaxSatiatedConsumptionLimit;
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

    private void loadConstants(String policyValuesString,
            @Nullable DeviceConfig.Properties properties) {
        mActions.clear();
        mRewards.clear();

        try {
            mUserSettingDeviceConfigMediator.setSettingsString(policyValuesString);
            mUserSettingDeviceConfigMediator.setDeviceConfigProperties(properties);
        } catch (IllegalArgumentException e) {
            Slog.e(TAG, "Global setting key incorrect: ", e);
        }

        mMinSatiatedBalanceOther = getConstantAsCake(
            KEY_JS_MIN_SATIATED_BALANCE_OTHER_APP, DEFAULT_JS_MIN_SATIATED_BALANCE_OTHER_APP_CAKES);
        mMinSatiatedBalanceHeadlessSystemApp = getConstantAsCake(
                KEY_JS_MIN_SATIATED_BALANCE_HEADLESS_SYSTEM_APP,
                DEFAULT_JS_MIN_SATIATED_BALANCE_HEADLESS_SYSTEM_APP_CAKES,
                mMinSatiatedBalanceOther);
        mMinSatiatedBalanceExempted = getConstantAsCake(
                KEY_JS_MIN_SATIATED_BALANCE_EXEMPTED,
                DEFAULT_JS_MIN_SATIATED_BALANCE_EXEMPTED_CAKES,
                mMinSatiatedBalanceHeadlessSystemApp);
        mMinSatiatedBalanceIncrementalAppUpdater = getConstantAsCake(
                KEY_JS_MIN_SATIATED_BALANCE_INCREMENT_APP_UPDATER,
                DEFAULT_JS_MIN_SATIATED_BALANCE_INCREMENT_APP_UPDATER_CAKES);
        mMaxSatiatedBalance = getConstantAsCake(
            KEY_JS_MAX_SATIATED_BALANCE, DEFAULT_JS_MAX_SATIATED_BALANCE_CAKES,
            Math.max(arcToCake(1), mMinSatiatedBalanceExempted));
        mMinSatiatedConsumptionLimit = getConstantAsCake(
                KEY_JS_MIN_CONSUMPTION_LIMIT, DEFAULT_JS_MIN_CONSUMPTION_LIMIT_CAKES,
                arcToCake(1));
        mInitialSatiatedConsumptionLimit = getConstantAsCake(
                KEY_JS_INITIAL_CONSUMPTION_LIMIT, DEFAULT_JS_INITIAL_CONSUMPTION_LIMIT_CAKES,
                mMinSatiatedConsumptionLimit);
        mMaxSatiatedConsumptionLimit = getConstantAsCake(
                KEY_JS_MAX_CONSUMPTION_LIMIT, DEFAULT_JS_MAX_CONSUMPTION_LIMIT_CAKES,
                mInitialSatiatedConsumptionLimit);

        mActions.put(ACTION_JOB_MAX_START, new Action(ACTION_JOB_MAX_START,
                getConstantAsCake(
                        KEY_JS_ACTION_JOB_MAX_START_CTP,
                        DEFAULT_JS_ACTION_JOB_MAX_START_CTP_CAKES),
                getConstantAsCake(
                        KEY_JS_ACTION_JOB_MAX_START_BASE_PRICE,
                        DEFAULT_JS_ACTION_JOB_MAX_START_BASE_PRICE_CAKES)));
        mActions.put(ACTION_JOB_MAX_RUNNING, new Action(ACTION_JOB_MAX_RUNNING,
                getConstantAsCake(
                        KEY_JS_ACTION_JOB_MAX_RUNNING_CTP,
                        DEFAULT_JS_ACTION_JOB_MAX_RUNNING_CTP_CAKES),
                getConstantAsCake(
                        KEY_JS_ACTION_JOB_MAX_RUNNING_BASE_PRICE,
                        DEFAULT_JS_ACTION_JOB_MAX_RUNNING_BASE_PRICE_CAKES)));
        mActions.put(ACTION_JOB_HIGH_START, new Action(ACTION_JOB_HIGH_START,
                getConstantAsCake(
                        KEY_JS_ACTION_JOB_HIGH_START_CTP,
                        DEFAULT_JS_ACTION_JOB_HIGH_START_CTP_CAKES),
                getConstantAsCake(
                        KEY_JS_ACTION_JOB_HIGH_START_BASE_PRICE,
                        DEFAULT_JS_ACTION_JOB_HIGH_START_BASE_PRICE_CAKES)));
        mActions.put(ACTION_JOB_HIGH_RUNNING, new Action(ACTION_JOB_HIGH_RUNNING,
                getConstantAsCake(
                        KEY_JS_ACTION_JOB_HIGH_RUNNING_CTP,
                        DEFAULT_JS_ACTION_JOB_HIGH_RUNNING_CTP_CAKES),
                getConstantAsCake(
                        KEY_JS_ACTION_JOB_HIGH_RUNNING_BASE_PRICE,
                        DEFAULT_JS_ACTION_JOB_HIGH_RUNNING_BASE_PRICE_CAKES)));
        mActions.put(ACTION_JOB_DEFAULT_START, new Action(ACTION_JOB_DEFAULT_START,
                getConstantAsCake(
                        KEY_JS_ACTION_JOB_DEFAULT_START_CTP,
                        DEFAULT_JS_ACTION_JOB_DEFAULT_START_CTP_CAKES),
                getConstantAsCake(
                        KEY_JS_ACTION_JOB_DEFAULT_START_BASE_PRICE,
                        DEFAULT_JS_ACTION_JOB_DEFAULT_START_BASE_PRICE_CAKES)));
        mActions.put(ACTION_JOB_DEFAULT_RUNNING, new Action(ACTION_JOB_DEFAULT_RUNNING,
                getConstantAsCake(
                        KEY_JS_ACTION_JOB_DEFAULT_RUNNING_CTP,
                        DEFAULT_JS_ACTION_JOB_DEFAULT_RUNNING_CTP_CAKES),
                getConstantAsCake(
                        KEY_JS_ACTION_JOB_DEFAULT_RUNNING_BASE_PRICE,
                        DEFAULT_JS_ACTION_JOB_DEFAULT_RUNNING_BASE_PRICE_CAKES)));
        mActions.put(ACTION_JOB_LOW_START, new Action(ACTION_JOB_LOW_START,
                getConstantAsCake(
                        KEY_JS_ACTION_JOB_LOW_START_CTP,
                        DEFAULT_JS_ACTION_JOB_LOW_START_CTP_CAKES),
                getConstantAsCake(
                        KEY_JS_ACTION_JOB_LOW_START_BASE_PRICE,
                        DEFAULT_JS_ACTION_JOB_LOW_START_BASE_PRICE_CAKES)));
        mActions.put(ACTION_JOB_LOW_RUNNING, new Action(ACTION_JOB_LOW_RUNNING,
                getConstantAsCake(
                        KEY_JS_ACTION_JOB_LOW_RUNNING_CTP,
                        DEFAULT_JS_ACTION_JOB_LOW_RUNNING_CTP_CAKES),
                getConstantAsCake(
                        KEY_JS_ACTION_JOB_LOW_RUNNING_BASE_PRICE,
                        DEFAULT_JS_ACTION_JOB_LOW_RUNNING_BASE_PRICE_CAKES)));
        mActions.put(ACTION_JOB_MIN_START, new Action(ACTION_JOB_MIN_START,
                getConstantAsCake(
                        KEY_JS_ACTION_JOB_MIN_START_CTP,
                        DEFAULT_JS_ACTION_JOB_MIN_START_CTP_CAKES),
                getConstantAsCake(
                        KEY_JS_ACTION_JOB_MIN_START_BASE_PRICE,
                        DEFAULT_JS_ACTION_JOB_MIN_START_BASE_PRICE_CAKES)));
        mActions.put(ACTION_JOB_MIN_RUNNING, new Action(ACTION_JOB_MIN_RUNNING,
                getConstantAsCake(
                        KEY_JS_ACTION_JOB_MIN_RUNNING_CTP,
                        DEFAULT_JS_ACTION_JOB_MIN_RUNNING_CTP_CAKES),
                getConstantAsCake(
                        KEY_JS_ACTION_JOB_MIN_RUNNING_BASE_PRICE,
                        DEFAULT_JS_ACTION_JOB_MIN_RUNNING_BASE_PRICE_CAKES)));
        mActions.put(ACTION_JOB_TIMEOUT, new Action(ACTION_JOB_TIMEOUT,
                getConstantAsCake(
                        KEY_JS_ACTION_JOB_TIMEOUT_PENALTY_CTP,
                        DEFAULT_JS_ACTION_JOB_TIMEOUT_PENALTY_CTP_CAKES),
                getConstantAsCake(
                        KEY_JS_ACTION_JOB_TIMEOUT_PENALTY_BASE_PRICE,
                        DEFAULT_JS_ACTION_JOB_TIMEOUT_PENALTY_BASE_PRICE_CAKES)));

        mRewards.put(REWARD_TOP_ACTIVITY, new Reward(REWARD_TOP_ACTIVITY,
                getConstantAsCake(
                        KEY_JS_REWARD_TOP_ACTIVITY_INSTANT,
                        DEFAULT_JS_REWARD_TOP_ACTIVITY_INSTANT_CAKES),
                getConstantAsCake(
                        KEY_JS_REWARD_TOP_ACTIVITY_ONGOING,
                        DEFAULT_JS_REWARD_TOP_ACTIVITY_ONGOING_CAKES),
                getConstantAsCake(
                        KEY_JS_REWARD_TOP_ACTIVITY_MAX,
                        DEFAULT_JS_REWARD_TOP_ACTIVITY_MAX_CAKES)));
        mRewards.put(REWARD_NOTIFICATION_SEEN, new Reward(REWARD_NOTIFICATION_SEEN,
                getConstantAsCake(
                        KEY_JS_REWARD_NOTIFICATION_SEEN_INSTANT,
                        DEFAULT_JS_REWARD_NOTIFICATION_SEEN_INSTANT_CAKES),
                getConstantAsCake(
                        KEY_JS_REWARD_NOTIFICATION_SEEN_ONGOING,
                        DEFAULT_JS_REWARD_NOTIFICATION_SEEN_ONGOING_CAKES),
                getConstantAsCake(
                        KEY_JS_REWARD_NOTIFICATION_SEEN_MAX,
                        DEFAULT_JS_REWARD_NOTIFICATION_SEEN_MAX_CAKES)));
        mRewards.put(REWARD_NOTIFICATION_INTERACTION,
                new Reward(REWARD_NOTIFICATION_INTERACTION,
                        getConstantAsCake(
                                KEY_JS_REWARD_NOTIFICATION_INTERACTION_INSTANT,
                                DEFAULT_JS_REWARD_NOTIFICATION_INTERACTION_INSTANT_CAKES),
                        getConstantAsCake(
                                KEY_JS_REWARD_NOTIFICATION_INTERACTION_ONGOING,
                                DEFAULT_JS_REWARD_NOTIFICATION_INTERACTION_ONGOING_CAKES),
                        getConstantAsCake(
                                KEY_JS_REWARD_NOTIFICATION_INTERACTION_MAX,
                                DEFAULT_JS_REWARD_NOTIFICATION_INTERACTION_MAX_CAKES)));
        mRewards.put(REWARD_WIDGET_INTERACTION, new Reward(REWARD_WIDGET_INTERACTION,
                getConstantAsCake(
                        KEY_JS_REWARD_WIDGET_INTERACTION_INSTANT,
                        DEFAULT_JS_REWARD_WIDGET_INTERACTION_INSTANT_CAKES),
                getConstantAsCake(
                        KEY_JS_REWARD_WIDGET_INTERACTION_ONGOING,
                        DEFAULT_JS_REWARD_WIDGET_INTERACTION_ONGOING_CAKES),
                getConstantAsCake(
                        KEY_JS_REWARD_WIDGET_INTERACTION_MAX,
                        DEFAULT_JS_REWARD_WIDGET_INTERACTION_MAX_CAKES)));
        mRewards.put(REWARD_OTHER_USER_INTERACTION,
                new Reward(REWARD_OTHER_USER_INTERACTION,
                        getConstantAsCake(
                                KEY_JS_REWARD_OTHER_USER_INTERACTION_INSTANT,
                                DEFAULT_JS_REWARD_OTHER_USER_INTERACTION_INSTANT_CAKES),
                        getConstantAsCake(
                                KEY_JS_REWARD_OTHER_USER_INTERACTION_ONGOING,
                                DEFAULT_JS_REWARD_OTHER_USER_INTERACTION_ONGOING_CAKES),
                        getConstantAsCake(
                                KEY_JS_REWARD_OTHER_USER_INTERACTION_MAX,
                                DEFAULT_JS_REWARD_OTHER_USER_INTERACTION_MAX_CAKES)));
        mRewards.put(REWARD_APP_INSTALL,
                new Reward(REWARD_APP_INSTALL,
                        getConstantAsCake(
                                KEY_JS_REWARD_APP_INSTALL_INSTANT,
                                DEFAULT_JS_REWARD_APP_INSTALL_INSTANT_CAKES),
                        getConstantAsCake(
                                KEY_JS_REWARD_APP_INSTALL_ONGOING,
                                DEFAULT_JS_REWARD_APP_INSTALL_ONGOING_CAKES),
                        getConstantAsCake(
                                KEY_JS_REWARD_APP_INSTALL_MAX,
                                DEFAULT_JS_REWARD_APP_INSTALL_MAX_CAKES)));
    }

    @Override
    void dump(IndentingPrintWriter pw) {
        pw.println("Min satiated balance:");
        pw.increaseIndent();
        pw.print("Exempted", cakeToString(mMinSatiatedBalanceExempted)).println();
        pw.print("Other", cakeToString(mMinSatiatedBalanceOther)).println();
        pw.print("+App Updater", cakeToString(mMinSatiatedBalanceIncrementalAppUpdater)).println();
        pw.decreaseIndent();
        pw.print("Max satiated balance", cakeToString(mMaxSatiatedBalance)).println();
        pw.print("Consumption limits: [");
        pw.print(cakeToString(mMinSatiatedConsumptionLimit));
        pw.print(", ");
        pw.print(cakeToString(mInitialSatiatedConsumptionLimit));
        pw.print(", ");
        pw.print(cakeToString(mMaxSatiatedConsumptionLimit));
        pw.println("]");

        pw.println();
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
