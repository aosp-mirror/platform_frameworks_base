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
         * the action unless a multiplier lowers the cost to produce.
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

    EconomicPolicy(@NonNull InternalResourceService irs) {
    }

    @CallSuper
    void onSystemServicesReady() {
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

    @Nullable
    abstract Action getAction(@NonNull String actionName);

    @Nullable
    abstract Reward getReward(@NonNull String rewardName);

    void dump(IndentingPrintWriter pw) {
    }
}
