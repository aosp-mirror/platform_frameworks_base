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

import static android.app.tare.EconomyManager.parseCreditValue;

import static com.android.server.tare.Modifier.COST_MODIFIER_CHARGING;
import static com.android.server.tare.Modifier.COST_MODIFIER_DEVICE_IDLE;
import static com.android.server.tare.Modifier.COST_MODIFIER_POWER_SAVE_MODE;
import static com.android.server.tare.Modifier.COST_MODIFIER_PROCESS_STATE;
import static com.android.server.tare.Modifier.NUM_COST_MODIFIERS;
import static com.android.server.tare.TareUtils.cakeToString;

import android.annotation.CallSuper;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.util.IndentingPrintWriter;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.utils.UserSettingDeviceConfigMediator;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * An EconomicPolicy includes pricing information and daily ARC requirements and suggestions.
 * Policies are defined per participating system service. This allows each service’s EconomicPolicy
 * to be isolated while allowing the core economic system to scale across policies to achieve a
 * logical system-wide value system.
 */
public abstract class EconomicPolicy {
    private static final String TAG = "TARE-" + EconomicPolicy.class.getSimpleName();

    private static final int SHIFT_TYPE = 30;
    static final int MASK_TYPE = 0b11 << SHIFT_TYPE;
    static final int TYPE_REGULATION = 0 << SHIFT_TYPE;
    static final int TYPE_ACTION = 1 << SHIFT_TYPE;
    static final int TYPE_REWARD = 2 << SHIFT_TYPE;

    private static final int SHIFT_POLICY = 28;
    static final int MASK_POLICY = 0b11 << SHIFT_POLICY;
    static final int ALL_POLICIES = MASK_POLICY;
    // Reserve 0 for the base/common policy.
    public static final int POLICY_ALARM = 1 << SHIFT_POLICY;
    public static final int POLICY_JOB = 2 << SHIFT_POLICY;

    static final int MASK_EVENT = -1 ^ (MASK_TYPE | MASK_POLICY);

    static final int REGULATION_BASIC_INCOME = TYPE_REGULATION | 0;
    static final int REGULATION_BIRTHRIGHT = TYPE_REGULATION | 1;
    static final int REGULATION_WEALTH_RECLAMATION = TYPE_REGULATION | 2;
    static final int REGULATION_PROMOTION = TYPE_REGULATION | 3;
    static final int REGULATION_DEMOTION = TYPE_REGULATION | 4;
    /** App is fully restricted from running in the background. */
    static final int REGULATION_BG_RESTRICTED = TYPE_REGULATION | 5;
    static final int REGULATION_BG_UNRESTRICTED = TYPE_REGULATION | 6;
    static final int REGULATION_FORCE_STOP = TYPE_REGULATION | 8;

    static final int REWARD_NOTIFICATION_SEEN = TYPE_REWARD | 0;
    static final int REWARD_NOTIFICATION_INTERACTION = TYPE_REWARD | 1;
    static final int REWARD_TOP_ACTIVITY = TYPE_REWARD | 2;
    static final int REWARD_WIDGET_INTERACTION = TYPE_REWARD | 3;
    static final int REWARD_OTHER_USER_INTERACTION = TYPE_REWARD | 4;

    @IntDef({
            AlarmManagerEconomicPolicy.ACTION_ALARM_WAKEUP_EXACT_ALLOW_WHILE_IDLE,
            AlarmManagerEconomicPolicy.ACTION_ALARM_WAKEUP_EXACT,
            AlarmManagerEconomicPolicy.ACTION_ALARM_WAKEUP_INEXACT_ALLOW_WHILE_IDLE,
            AlarmManagerEconomicPolicy.ACTION_ALARM_WAKEUP_INEXACT,
            AlarmManagerEconomicPolicy.ACTION_ALARM_NONWAKEUP_EXACT_ALLOW_WHILE_IDLE,
            AlarmManagerEconomicPolicy.ACTION_ALARM_NONWAKEUP_EXACT,
            AlarmManagerEconomicPolicy.ACTION_ALARM_NONWAKEUP_INEXACT_ALLOW_WHILE_IDLE,
            AlarmManagerEconomicPolicy.ACTION_ALARM_NONWAKEUP_INEXACT,
            AlarmManagerEconomicPolicy.ACTION_ALARM_CLOCK,
            JobSchedulerEconomicPolicy.ACTION_JOB_MAX_START,
            JobSchedulerEconomicPolicy.ACTION_JOB_MAX_RUNNING,
            JobSchedulerEconomicPolicy.ACTION_JOB_HIGH_START,
            JobSchedulerEconomicPolicy.ACTION_JOB_HIGH_RUNNING,
            JobSchedulerEconomicPolicy.ACTION_JOB_DEFAULT_START,
            JobSchedulerEconomicPolicy.ACTION_JOB_DEFAULT_RUNNING,
            JobSchedulerEconomicPolicy.ACTION_JOB_LOW_START,
            JobSchedulerEconomicPolicy.ACTION_JOB_LOW_RUNNING,
            JobSchedulerEconomicPolicy.ACTION_JOB_MIN_START,
            JobSchedulerEconomicPolicy.ACTION_JOB_MIN_RUNNING,
            JobSchedulerEconomicPolicy.ACTION_JOB_TIMEOUT,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AppAction {
    }

    @IntDef({
            TYPE_ACTION,
            TYPE_REGULATION,
            TYPE_REWARD,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EventType {
    }

    @IntDef({
            ALL_POLICIES,
            POLICY_ALARM,
            POLICY_JOB,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Policy {
    }

    @IntDef({
            REWARD_TOP_ACTIVITY,
            REWARD_NOTIFICATION_SEEN,
            REWARD_NOTIFICATION_INTERACTION,
            REWARD_WIDGET_INTERACTION,
            REWARD_OTHER_USER_INTERACTION,
            JobSchedulerEconomicPolicy.REWARD_APP_INSTALL,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface UtilityReward {
    }

    static class Action {
        /** Unique id (including across policies) for this action. */
        public final int id;
        /**
         * How many ARCs the system says it takes to perform this action.
         */
        public final long costToProduce;
        /**
         * The base price to perform this action. If this is
         * less than the {@link #costToProduce}, then the system should not perform
         * the action unless a modifier lowers the cost to produce.
         */
        public final long basePrice;
        /**
         * Whether the remaining stock limit affects an app's ability to perform this action.
         * If {@code false}, then the action can be performed, even if the cost is higher
         * than the remaining stock. This does not affect checking against an app's balance.
         */
        public final boolean respectsStockLimit;

        Action(int id, long costToProduce, long basePrice) {
            this(id, costToProduce, basePrice, true);
        }

        Action(int id, long costToProduce, long basePrice, boolean respectsStockLimit) {
            this.id = id;
            this.costToProduce = costToProduce;
            this.basePrice = basePrice;
            this.respectsStockLimit = respectsStockLimit;
        }
    }

    static class Reward {
        /** Unique id (including across policies) for this reward. */
        @UtilityReward
        public final int id;
        public final long instantReward;
        /** Reward credited per second of ongoing activity. */
        public final long ongoingRewardPerSecond;
        /** The maximum amount an app can earn from this reward within a 24 hour period. */
        public final long maxDailyReward;

        Reward(int id, long instantReward, long ongoingReward, long maxDailyReward) {
            this.id = id;
            this.instantReward = instantReward;
            this.ongoingRewardPerSecond = ongoingReward;
            this.maxDailyReward = maxDailyReward;
        }
    }

    static class Cost {
        public final long costToProduce;
        public final long price;

        Cost(long costToProduce, long price) {
            this.costToProduce = costToProduce;
            this.price = price;
        }
    }

    protected final InternalResourceService mIrs;
    protected final UserSettingDeviceConfigMediator mUserSettingDeviceConfigMediator;
    private static final Modifier[] COST_MODIFIER_BY_INDEX = new Modifier[NUM_COST_MODIFIERS];

    EconomicPolicy(@NonNull InternalResourceService irs) {
        mIrs = irs;
        // Don't cross the streams! Mixing Settings/local user config changes with DeviceConfig
        // config can cause issues since the scales may be different, so use one or the other.
        // If user settings exist, then just stick with the Settings constants, even if there
        // are invalid values.
        mUserSettingDeviceConfigMediator =
                new UserSettingDeviceConfigMediator.SettingsOverridesAllMediator(',');
        for (int mId : getCostModifiers()) {
            initModifier(mId, irs);
        }
    }

    @CallSuper
    void setup(@NonNull DeviceConfig.Properties properties) {
        for (int i = 0; i < NUM_COST_MODIFIERS; ++i) {
            final Modifier modifier = COST_MODIFIER_BY_INDEX[i];
            if (modifier != null) {
                modifier.setup();
            }
        }
    }

    @CallSuper
    void tearDown() {
        for (int i = 0; i < NUM_COST_MODIFIERS; ++i) {
            final Modifier modifier = COST_MODIFIER_BY_INDEX[i];
            if (modifier != null) {
                modifier.tearDown();
            }
        }
    }

    /**
     * Returns the minimum suggested balance an app should have when the device is at 100% battery.
     * This takes into account any exemptions the app may have.
     */
    abstract long getMinSatiatedBalance(int userId, @NonNull String pkgName);

    /**
     * Returns the maximum balance an app should have when the device is at 100% battery. This
     * exists to ensure that no single app accumulate all available resources and increases fairness
     * for all apps.
     */
    abstract long getMaxSatiatedBalance(int userId, @NonNull String pkgName);

    /**
     * Returns the maximum number of cakes that should be consumed during a full 100% discharge
     * cycle. This is the initial limit. The system may choose to increase the limit over time,
     * but the increased limit should never exceed the value returned from
     * {@link #getMaxSatiatedConsumptionLimit()}.
     */
    abstract long getInitialSatiatedConsumptionLimit();

    /**
     * Returns the minimum number of cakes that should be available for consumption during a full
     * 100% discharge cycle.
     */
    abstract long getMinSatiatedConsumptionLimit();

    /**
     * Returns the maximum number of cakes that should be available for consumption during a full
     * 100% discharge cycle.
     */
    abstract long getMaxSatiatedConsumptionLimit();

    /** Return the set of modifiers that should apply to this policy's costs. */
    @NonNull
    abstract int[] getCostModifiers();

    @Nullable
    abstract Action getAction(@AppAction int actionId);

    @Nullable
    abstract Reward getReward(@UtilityReward int rewardId);

    void dump(IndentingPrintWriter pw) {
    }

    @NonNull
    final Cost getCostOfAction(int actionId, int userId, @NonNull String pkgName) {
        final Action action = getAction(actionId);
        if (action == null || mIrs.isVip(userId, pkgName)) {
            return new Cost(0, 0);
        }
        long ctp = action.costToProduce;
        long price = action.basePrice;
        final int[] costModifiers = getCostModifiers();
        boolean useProcessStatePriceDeterminant = false;
        for (int costModifier : costModifiers) {
            if (costModifier == COST_MODIFIER_PROCESS_STATE) {
                useProcessStatePriceDeterminant = true;
            } else {
                final Modifier modifier = getModifier(costModifier);
                ctp = modifier.getModifiedCostToProduce(ctp);
                price = modifier.getModifiedPrice(price);
            }
        }
        // ProcessStateModifier needs to be done last.
        if (useProcessStatePriceDeterminant) {
            ProcessStateModifier processStateModifier =
                    (ProcessStateModifier) getModifier(COST_MODIFIER_PROCESS_STATE);
            price = processStateModifier.getModifiedPrice(userId, pkgName, ctp, price);
        }
        return new Cost(ctp, price);
    }

    private static void initModifier(@Modifier.CostModifier final int modifierId,
            @NonNull InternalResourceService irs) {
        if (modifierId < 0 || modifierId >= COST_MODIFIER_BY_INDEX.length) {
            throw new IllegalArgumentException("Invalid modifier id " + modifierId);
        }
        Modifier modifier = COST_MODIFIER_BY_INDEX[modifierId];
        if (modifier == null) {
            switch (modifierId) {
                case COST_MODIFIER_CHARGING:
                    modifier = new ChargingModifier(irs);
                    break;
                case COST_MODIFIER_DEVICE_IDLE:
                    modifier = new DeviceIdleModifier(irs);
                    break;
                case COST_MODIFIER_POWER_SAVE_MODE:
                    modifier = new PowerSaveModeModifier(irs);
                    break;
                case COST_MODIFIER_PROCESS_STATE:
                    modifier = new ProcessStateModifier(irs);
                    break;
                default:
                    throw new IllegalArgumentException("Invalid modifier id " + modifierId);
            }
            COST_MODIFIER_BY_INDEX[modifierId] = modifier;
        }
    }

    @NonNull
    private static Modifier getModifier(@Modifier.CostModifier final int modifierId) {
        if (modifierId < 0 || modifierId >= COST_MODIFIER_BY_INDEX.length) {
            throw new IllegalArgumentException("Invalid modifier id " + modifierId);
        }
        final Modifier modifier = COST_MODIFIER_BY_INDEX[modifierId];
        if (modifier == null) {
            throw new IllegalStateException(
                    "Modifier #" + modifierId + " was never initialized");
        }
        return modifier;
    }

    @EventType
    static int getEventType(int eventId) {
        return eventId & MASK_TYPE;
    }

    static boolean isReward(int eventId) {
        return getEventType(eventId) == TYPE_REWARD;
    }

    @NonNull
    static String eventToString(int eventId) {
        switch (eventId & MASK_TYPE) {
            case TYPE_ACTION:
                return actionToString(eventId);

            case TYPE_REGULATION:
                return regulationToString(eventId);

            case TYPE_REWARD:
                return rewardToString(eventId);

            default:
                return "UNKNOWN_EVENT:" + Integer.toHexString(eventId);
        }
    }

    @NonNull
    static String actionToString(int eventId) {
        switch (eventId & MASK_POLICY) {
            case POLICY_ALARM:
                switch (eventId) {
                    case AlarmManagerEconomicPolicy.ACTION_ALARM_WAKEUP_EXACT_ALLOW_WHILE_IDLE:
                        return "ALARM_WAKEUP_EXACT_ALLOW_WHILE_IDLE";
                    case AlarmManagerEconomicPolicy.ACTION_ALARM_WAKEUP_EXACT:
                        return "ALARM_WAKEUP_EXACT";
                    case AlarmManagerEconomicPolicy.ACTION_ALARM_WAKEUP_INEXACT_ALLOW_WHILE_IDLE:
                        return "ALARM_WAKEUP_INEXACT_ALLOW_WHILE_IDLE";
                    case AlarmManagerEconomicPolicy.ACTION_ALARM_WAKEUP_INEXACT:
                        return "ALARM_WAKEUP_INEXACT";
                    case AlarmManagerEconomicPolicy.ACTION_ALARM_NONWAKEUP_EXACT_ALLOW_WHILE_IDLE:
                        return "ALARM_NONWAKEUP_EXACT_ALLOW_WHILE_IDLE";
                    case AlarmManagerEconomicPolicy.ACTION_ALARM_NONWAKEUP_EXACT:
                        return "ALARM_NONWAKEUP_EXACT";
                    case AlarmManagerEconomicPolicy.ACTION_ALARM_NONWAKEUP_INEXACT_ALLOW_WHILE_IDLE:
                        return "ALARM_NONWAKEUP_INEXACT_ALLOW_WHILE_IDLE";
                    case AlarmManagerEconomicPolicy.ACTION_ALARM_NONWAKEUP_INEXACT:
                        return "ALARM_NONWAKEUP_INEXACT";
                    case AlarmManagerEconomicPolicy.ACTION_ALARM_CLOCK:
                        return "ALARM_CLOCK";
                }
                break;

            case POLICY_JOB:
                switch (eventId) {
                    case JobSchedulerEconomicPolicy.ACTION_JOB_MAX_START:
                        return "JOB_MAX_START";
                    case JobSchedulerEconomicPolicy.ACTION_JOB_MAX_RUNNING:
                        return "JOB_MAX_RUNNING";
                    case JobSchedulerEconomicPolicy.ACTION_JOB_HIGH_START:
                        return "JOB_HIGH_START";
                    case JobSchedulerEconomicPolicy.ACTION_JOB_HIGH_RUNNING:
                        return "JOB_HIGH_RUNNING";
                    case JobSchedulerEconomicPolicy.ACTION_JOB_DEFAULT_START:
                        return "JOB_DEFAULT_START";
                    case JobSchedulerEconomicPolicy.ACTION_JOB_DEFAULT_RUNNING:
                        return "JOB_DEFAULT_RUNNING";
                    case JobSchedulerEconomicPolicy.ACTION_JOB_LOW_START:
                        return "JOB_LOW_START";
                    case JobSchedulerEconomicPolicy.ACTION_JOB_LOW_RUNNING:
                        return "JOB_LOW_RUNNING";
                    case JobSchedulerEconomicPolicy.ACTION_JOB_MIN_START:
                        return "JOB_MIN_START";
                    case JobSchedulerEconomicPolicy.ACTION_JOB_MIN_RUNNING:
                        return "JOB_MIN_RUNNING";
                    case JobSchedulerEconomicPolicy.ACTION_JOB_TIMEOUT:
                        return "JOB_TIMEOUT";
                }
                break;
        }
        return "UNKNOWN_ACTION:" + Integer.toHexString(eventId);
    }

    @NonNull
    static String regulationToString(int eventId) {
        switch (eventId) {
            case REGULATION_BASIC_INCOME:
                return "BASIC_INCOME";
            case REGULATION_BIRTHRIGHT:
                return "BIRTHRIGHT";
            case REGULATION_WEALTH_RECLAMATION:
                return "WEALTH_RECLAMATION";
            case REGULATION_PROMOTION:
                return "PROMOTION";
            case REGULATION_DEMOTION:
                return "DEMOTION";
            case REGULATION_BG_RESTRICTED:
                return "BG_RESTRICTED";
            case REGULATION_BG_UNRESTRICTED:
                return "BG_UNRESTRICTED";
            case REGULATION_FORCE_STOP:
                return "FORCE_STOP";
        }
        return "UNKNOWN_REGULATION:" + Integer.toHexString(eventId);
    }

    @NonNull
    static String rewardToString(int eventId) {
        switch (eventId) {
            case REWARD_TOP_ACTIVITY:
                return "REWARD_TOP_ACTIVITY";
            case REWARD_NOTIFICATION_SEEN:
                return "REWARD_NOTIFICATION_SEEN";
            case REWARD_NOTIFICATION_INTERACTION:
                return "REWARD_NOTIFICATION_INTERACTION";
            case REWARD_WIDGET_INTERACTION:
                return "REWARD_WIDGET_INTERACTION";
            case REWARD_OTHER_USER_INTERACTION:
                return "REWARD_OTHER_USER_INTERACTION";
            case JobSchedulerEconomicPolicy.REWARD_APP_INSTALL:
                return "REWARD_JOB_APP_INSTALL";
        }
        return "UNKNOWN_REWARD:" + Integer.toHexString(eventId);
    }

    protected long getConstantAsCake(String key, long defaultValCake) {
        return getConstantAsCake(key, defaultValCake, 0);
    }

    protected long getConstantAsCake(String key, long defaultValCake, long minValCake) {
        return Math.max(minValCake,
                parseCreditValue(
                        mUserSettingDeviceConfigMediator.getString(key, null), defaultValCake));
    }

    @VisibleForTesting
    static class Injector {
        @Nullable
        String getSettingsGlobalString(@NonNull ContentResolver resolver, @NonNull String name) {
            return Settings.Global.getString(resolver, name);
        }
    }

    protected static void dumpActiveModifiers(IndentingPrintWriter pw) {
        for (int i = 0; i < NUM_COST_MODIFIERS; ++i) {
            pw.print("Modifier ");
            pw.println(i);
            pw.increaseIndent();

            Modifier modifier = COST_MODIFIER_BY_INDEX[i];
            if (modifier != null) {
                modifier.dump(pw);
            } else {
                pw.println("NOT ACTIVE");
            }

            pw.decreaseIndent();
        }
    }

    protected static void dumpAction(IndentingPrintWriter pw, @NonNull Action action) {
        pw.print(actionToString(action.id));
        pw.print(": ");
        pw.print("ctp=");
        pw.print(cakeToString(action.costToProduce));
        pw.print(", basePrice=");
        pw.print(cakeToString(action.basePrice));
        pw.println();
    }

    protected static void dumpReward(IndentingPrintWriter pw, @NonNull Reward reward) {
        pw.print(rewardToString(reward.id));
        pw.print(": ");
        pw.print("instant=");
        pw.print(cakeToString(reward.instantReward));
        pw.print(", ongoing/sec=");
        pw.print(cakeToString(reward.ongoingRewardPerSecond));
        pw.print(", maxDaily=");
        pw.print(cakeToString(reward.maxDailyReward));
        pw.println();
    }
}
