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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;
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

    // fixme ensure actions and rewards never use the same strings
    static final String REWARD_TOP_ACTIVITY = "REWARD_TOP_ACTIVITY";
    static final String REWARD_NOTIFICATION_SEEN = "REWARD_NOTIFICATION_SEEN";
    static final String REWARD_NOTIFICATION_INTERACTION = "REWARD_NOTIFICATION_INTERACTION";
    static final String REWARD_WIDGET_INTERACTION = "REWARD_WIDGET_INTERACTION";
    static final String REWARD_OTHER_USER_INTERACTION = "REWARD_OTHER_USER_INTERACTION";

    @StringDef({
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
        @NonNull
        public final String name;
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

        Action(@NonNull String name, long costToProduce, long basePrice) {
            this.name = name;
            this.costToProduce = costToProduce;
            this.basePrice = basePrice;
        }
    }

    static class Reward {
        @NonNull
        @UtilityReward
        public final String name;
        public final long instantReward;
        /** Reward credited per second of ongoing activity. */
        public final long ongoingRewardPerSecond;
        /** The maximum amount an app can earn from this reward within a 24 hour period. */
        public final long maxDailyReward;

        Reward(@NonNull String name, long instantReward, long ongoingReward, long maxDailyReward) {
            this.name = name;
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
    abstract Action getAction(@NonNull String actionName);

    @Nullable
    abstract Reward getReward(@NonNull String rewardName);

    void dump(IndentingPrintWriter pw) {
    }

    final long getCostOfAction(@NonNull String actionName, int userId, @NonNull String pkgName) {
        final Action action = getAction(actionName);
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
