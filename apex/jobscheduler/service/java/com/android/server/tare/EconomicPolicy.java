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
import static com.android.server.tare.Modifier.NUM_COST_MODIFIERS;

import android.annotation.CallSuper;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.IndentingPrintWriter;

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

    private static final int SHIFT_POLICY = 29;
    static final int MASK_POLICY = 0b1 << SHIFT_POLICY;
    static final int POLICY_AM = 0 << SHIFT_POLICY;
    static final int POLICY_JS = 1 << SHIFT_POLICY;

    static final int MASK_EVENT = ~0 - (0b111 << SHIFT_POLICY);

    static final int REGULATION_BASIC_INCOME = TYPE_REGULATION | 0;
    static final int REGULATION_BIRTHRIGHT = TYPE_REGULATION | 1;
    static final int REGULATION_WEALTH_RECLAMATION = TYPE_REGULATION | 2;

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
            REWARD_TOP_ACTIVITY,
            REWARD_NOTIFICATION_SEEN,
            REWARD_NOTIFICATION_INTERACTION,
            REWARD_WIDGET_INTERACTION,
            REWARD_OTHER_USER_INTERACTION,
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

        Action(int id, long costToProduce, long basePrice) {
            this.id = id;
            this.costToProduce = costToProduce;
            this.basePrice = basePrice;
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

    private static final Modifier[] COST_MODIFIER_BY_INDEX = new Modifier[NUM_COST_MODIFIERS];

    EconomicPolicy(@NonNull InternalResourceService irs) {
        for (int mId : getCostModifiers()) {
            initModifier(mId, irs);
        }
    }

    @CallSuper
    void onSystemServicesReady() {
        for (int i = 0; i < NUM_COST_MODIFIERS; ++i) {
            final Modifier modifier = COST_MODIFIER_BY_INDEX[i];
            if (modifier != null) {
                modifier.onSystemServicesReady();
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
    abstract long getMaxSatiatedBalance();

    /**
     * Returns the maximum number of narcs that should be in circulation at once when the device is
     * at 100% battery.
     */
    abstract long getMaxSatiatedCirculation();

    /** Return the set of modifiers that should apply to this policy's costs. */
    @NonNull
    abstract int[] getCostModifiers();

    @Nullable
    abstract Action getAction(@AppAction int actionId);

    @Nullable
    abstract Reward getReward(@UtilityReward int rewardId);

    void dump(IndentingPrintWriter pw) {
    }

    final long getCostOfAction(int actionId, int userId, @NonNull String pkgName) {
        final Action action = getAction(actionId);
        if (action == null) {
            return 0;
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
        return price;
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
            case POLICY_AM:
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
            case POLICY_JS:
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
        }
        return "UNKNOWN_REWARD:" + Integer.toHexString(eventId);
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
}
