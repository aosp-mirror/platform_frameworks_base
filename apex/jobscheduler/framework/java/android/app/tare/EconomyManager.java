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

package android.app.tare;

import android.annotation.Nullable;
import android.annotation.SystemService;
import android.content.Context;
import android.util.Log;

/**
 * Provides access to the resource economy service.
 *
 * @hide
 */
@SystemService(Context.RESOURCE_ECONOMY_SERVICE)
public class EconomyManager {
    private static final String TAG = "TARE-" + EconomyManager.class.getSimpleName();

    /**
     * 1 ARC = 1 GIGA-CAKE!
     *
     * @hide
     */
    public static final long CAKE_IN_ARC = 1_000_000_000L;

    /** @hide */
    public static long arcToCake(int arcs) {
        return arcs * CAKE_IN_ARC;
    }

    /**
     * Parses a configuration string to get the value in cakes.
     *
     * @hide
     */
    public static long parseCreditValue(@Nullable final String val, final long defaultValCakes) {
        String trunc;
        if (val == null || (trunc = val.trim()).isEmpty()) {
            return defaultValCakes;
        }
        long multiplier;
        if (trunc.endsWith("c")) {
            trunc = trunc.substring(0, trunc.length() - 1);
            multiplier = 1;
        } else if (trunc.endsWith("ck")) {
            trunc = trunc.substring(0, trunc.length() - 2);
            multiplier = 1;
        } else if (trunc.endsWith("A")) {
            trunc = trunc.substring(0, trunc.length() - 1);
            multiplier = CAKE_IN_ARC;
        } else if (trunc.endsWith("ARC")) {
            trunc = trunc.substring(0, trunc.length() - 3);
            multiplier = CAKE_IN_ARC;
        } else {
            // Don't risk using the wrong units
            Log.e(TAG, "Couldn't determine units of credit value: " + val);
            return defaultValCakes;
        }

        // Allow people to shorten notation (eg. Mc for Megacake).
        if (trunc.endsWith("k")) {
            trunc = trunc.substring(0, trunc.length() - 1);
            multiplier *= 1_000;
        } else if (trunc.endsWith("M")) {
            trunc = trunc.substring(0, trunc.length() - 1);
            multiplier *= 1_000_000;
        } else if (trunc.endsWith("G")) {
            trunc = trunc.substring(0, trunc.length() - 1);
            multiplier *= 1_000_000_000;
        }

        try {
            return Long.parseLong(trunc) * multiplier;
        } catch (NumberFormatException e) {
            Log.e(TAG, "Malformed config string: " + val + " to " + trunc, e);
            return defaultValCakes;
        }
    }

    // Keys for AlarmManager TARE factors
    /** @hide */
    public static final String KEY_AM_MIN_SATIATED_BALANCE_EXEMPTED =
            "am_min_satiated_balance_exempted";
    /** @hide */
    public static final String KEY_AM_MIN_SATIATED_BALANCE_HEADLESS_SYSTEM_APP =
            "am_min_satiated_balance_headless_system_app";
    /** @hide */
    public static final String KEY_AM_MIN_SATIATED_BALANCE_OTHER_APP =
            "am_min_satiated_balance_other_app";
    /** @hide */
    public static final String KEY_AM_MAX_SATIATED_BALANCE = "am_max_satiated_balance";
    /** @hide */
    public static final String KEY_AM_INITIAL_CONSUMPTION_LIMIT = "am_initial_consumption_limit";
    /** @hide */
    public static final String KEY_AM_HARD_CONSUMPTION_LIMIT = "am_hard_consumption_limit";
    // TODO: Add AlarmManager modifier keys
    /** @hide */
    public static final String KEY_AM_REWARD_TOP_ACTIVITY_INSTANT =
            "am_reward_top_activity_instant";
    /** @hide */
    public static final String KEY_AM_REWARD_TOP_ACTIVITY_ONGOING =
            "am_reward_top_activity_ongoing";
    /** @hide */
    public static final String KEY_AM_REWARD_TOP_ACTIVITY_MAX = "am_reward_top_activity_max";
    /** @hide */
    public static final String KEY_AM_REWARD_NOTIFICATION_SEEN_INSTANT =
            "am_reward_notification_seen_instant";
    /** @hide */
    public static final String KEY_AM_REWARD_NOTIFICATION_SEEN_ONGOING =
            "am_reward_notification_seen_ongoing";
    /** @hide */
    public static final String KEY_AM_REWARD_NOTIFICATION_SEEN_MAX =
            "am_reward_notification_seen_max";
    /** @hide */
    public static final String KEY_AM_REWARD_NOTIFICATION_SEEN_WITHIN_15_INSTANT =
            "am_reward_notification_seen_within_15_instant";
    /** @hide */
    public static final String KEY_AM_REWARD_NOTIFICATION_SEEN_WITHIN_15_ONGOING =
            "am_reward_notification_seen_within_15_ongoing";
    /** @hide */
    public static final String KEY_AM_REWARD_NOTIFICATION_SEEN_WITHIN_15_MAX =
            "am_reward_notification_seen_within_15_max";
    /** @hide */
    public static final String KEY_AM_REWARD_NOTIFICATION_INTERACTION_INSTANT =
            "am_reward_notification_interaction_instant";
    /** @hide */
    public static final String KEY_AM_REWARD_NOTIFICATION_INTERACTION_ONGOING =
            "am_reward_notification_interaction_ongoing";
    /** @hide */
    public static final String KEY_AM_REWARD_NOTIFICATION_INTERACTION_MAX =
            "am_reward_notification_interaction_max";
    /** @hide */
    public static final String KEY_AM_REWARD_WIDGET_INTERACTION_INSTANT =
            "am_reward_widget_interaction_instant";
    /** @hide */
    public static final String KEY_AM_REWARD_WIDGET_INTERACTION_ONGOING =
            "am_reward_widget_interaction_ongoing";
    /** @hide */
    public static final String KEY_AM_REWARD_WIDGET_INTERACTION_MAX =
            "am_reward_widget_interaction_max";
    /** @hide */
    public static final String KEY_AM_REWARD_OTHER_USER_INTERACTION_INSTANT =
            "am_reward_other_user_interaction_instant";
    /** @hide */
    public static final String KEY_AM_REWARD_OTHER_USER_INTERACTION_ONGOING =
            "am_reward_other_user_interaction_ongoing";
    /** @hide */
    public static final String KEY_AM_REWARD_OTHER_USER_INTERACTION_MAX =
            "am_reward_other_user_interaction_max";
    /** @hide */
    public static final String KEY_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_EXACT_WAKEUP_CTP =
            "am_action_alarm_allow_while_idle_exact_wakeup_ctp";
    /** @hide */
    public static final String KEY_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_INEXACT_WAKEUP_CTP =
            "am_action_alarm_allow_while_idle_inexact_wakeup_ctp";
    /** @hide */
    public static final String KEY_AM_ACTION_ALARM_EXACT_WAKEUP_CTP =
            "am_action_alarm_exact_wakeup_ctp";
    /** @hide */
    public static final String KEY_AM_ACTION_ALARM_INEXACT_WAKEUP_CTP =
            "am_action_alarm_inexact_wakeup_ctp";
    /** @hide */
    public static final String KEY_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_EXACT_NONWAKEUP_CTP =
            "am_action_alarm_allow_while_idle_exact_nonwakeup_ctp";
    /** @hide */
    public static final String KEY_AM_ACTION_ALARM_EXACT_NONWAKEUP_CTP =
            "am_action_alarm_exact_nonwakeup_ctp";
    /** @hide */
    public static final String KEY_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_INEXACT_NONWAKEUP_CTP =
            "am_action_alarm_allow_while_idle_inexact_nonwakeup_ctp";
    /** @hide */
    public static final String KEY_AM_ACTION_ALARM_INEXACT_NONWAKEUP_CTP =
            "am_action_alarm_inexact_nonwakeup_ctp";
    /** @hide */
    public static final String KEY_AM_ACTION_ALARM_ALARMCLOCK_CTP =
            "am_action_alarm_alarmclock_ctp";
    /** @hide */
    public static final String KEY_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_EXACT_WAKEUP_BASE_PRICE =
            "am_action_alarm_allow_while_idle_exact_wakeup_base_price";
    /** @hide */
    public static final String KEY_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_INEXACT_WAKEUP_BASE_PRICE =
            "am_action_alarm_allow_while_idle_inexact_wakeup_base_price";
    /** @hide */
    public static final String KEY_AM_ACTION_ALARM_EXACT_WAKEUP_BASE_PRICE =
            "am_action_alarm_exact_wakeup_base_price";
    /** @hide */
    public static final String KEY_AM_ACTION_ALARM_INEXACT_WAKEUP_BASE_PRICE =
            "am_action_alarm_inexact_wakeup_base_price";
    /** @hide */
    public static final String KEY_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_EXACT_NONWAKEUP_BASE_PRICE =
            "am_action_alarm_allow_while_idle_exact_nonwakeup_base_price";
    /** @hide */
    public static final String KEY_AM_ACTION_ALARM_EXACT_NONWAKEUP_BASE_PRICE =
            "am_action_alarm_exact_nonwakeup_base_price";
    /** @hide */
    public static final String KEY_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_INEXACT_NONWAKEUP_BASE_PRICE =
            "am_action_alarm_allow_while_idle_inexact_nonwakeup_base_price";
    /** @hide */
    public static final String KEY_AM_ACTION_ALARM_INEXACT_NONWAKEUP_BASE_PRICE =
            "am_action_alarm_inexact_nonwakeup_base_price";
    /** @hide */
    public static final String KEY_AM_ACTION_ALARM_ALARMCLOCK_BASE_PRICE =
            "am_action_alarm_alarmclock_base_price";

// Keys for JobScheduler TARE factors
    /** @hide */
    public static final String KEY_JS_MIN_SATIATED_BALANCE_EXEMPTED =
            "js_min_satiated_balance_exempted";
    /** @hide */
    public static final String KEY_JS_MIN_SATIATED_BALANCE_HEADLESS_SYSTEM_APP =
            "js_min_satiated_balance_headless_system_app";
    /** @hide */
    public static final String KEY_JS_MIN_SATIATED_BALANCE_OTHER_APP =
            "js_min_satiated_balance_other_app";
    /** @hide */
    public static final String KEY_JS_MAX_SATIATED_BALANCE =
            "js_max_satiated_balance";
    /** @hide */
    public static final String KEY_JS_INITIAL_CONSUMPTION_LIMIT = "js_initial_consumption_limit";
    /** @hide */
    public static final String KEY_JS_HARD_CONSUMPTION_LIMIT = "js_hard_consumption_limit";
    // TODO: Add JobScheduler modifier keys
    /** @hide */
    public static final String KEY_JS_REWARD_TOP_ACTIVITY_INSTANT =
            "js_reward_top_activity_instant";
    /** @hide */
    public static final String KEY_JS_REWARD_TOP_ACTIVITY_ONGOING =
            "js_reward_top_activity_ongoing";
    /** @hide */
    public static final String KEY_JS_REWARD_TOP_ACTIVITY_MAX =
            "js_reward_top_activity_max";
    /** @hide */
    public static final String KEY_JS_REWARD_NOTIFICATION_SEEN_INSTANT =
            "js_reward_notification_seen_instant";
    /** @hide */
    public static final String KEY_JS_REWARD_NOTIFICATION_SEEN_ONGOING =
            "js_reward_notification_seen_ongoing";
    /** @hide */
    public static final String KEY_JS_REWARD_NOTIFICATION_SEEN_MAX =
            "js_reward_notification_seen_max";
    /** @hide */
    public static final String KEY_JS_REWARD_NOTIFICATION_INTERACTION_INSTANT =
            "js_reward_notification_interaction_instant";
    /** @hide */
    public static final String KEY_JS_REWARD_NOTIFICATION_INTERACTION_ONGOING =
            "js_reward_notification_interaction_ongoing";
    /** @hide */
    public static final String KEY_JS_REWARD_NOTIFICATION_INTERACTION_MAX =
            "js_reward_notification_interaction_max";
    /** @hide */
    public static final String KEY_JS_REWARD_WIDGET_INTERACTION_INSTANT =
            "js_reward_widget_interaction_instant";
    /** @hide */
    public static final String KEY_JS_REWARD_WIDGET_INTERACTION_ONGOING =
            "js_reward_widget_interaction_ongoing";
    /** @hide */
    public static final String KEY_JS_REWARD_WIDGET_INTERACTION_MAX =
            "js_reward_widget_interaction_max";
    /** @hide */
    public static final String KEY_JS_REWARD_OTHER_USER_INTERACTION_INSTANT =
            "js_reward_other_user_interaction_instant";
    /** @hide */
    public static final String KEY_JS_REWARD_OTHER_USER_INTERACTION_ONGOING =
            "js_reward_other_user_interaction_ongoing";
    /** @hide */
    public static final String KEY_JS_REWARD_OTHER_USER_INTERACTION_MAX =
            "js_reward_other_user_interaction_max";
    /** @hide */
    public static final String KEY_JS_ACTION_JOB_MAX_START_CTP = "js_action_job_max_start_ctp";
    /** @hide */
    public static final String KEY_JS_ACTION_JOB_MAX_RUNNING_CTP = "js_action_job_max_running_ctp";
    /** @hide */
    public static final String KEY_JS_ACTION_JOB_HIGH_START_CTP = "js_action_job_high_start_ctp";
    /** @hide */
    public static final String KEY_JS_ACTION_JOB_HIGH_RUNNING_CTP =
            "js_action_job_high_running_ctp";
    /** @hide */
    public static final String KEY_JS_ACTION_JOB_DEFAULT_START_CTP =
            "js_action_job_default_start_ctp";
    /** @hide */
    public static final String KEY_JS_ACTION_JOB_DEFAULT_RUNNING_CTP =
            "js_action_job_default_running_ctp";
    /** @hide */
    public static final String KEY_JS_ACTION_JOB_LOW_START_CTP = "js_action_job_low_start_ctp";
    /** @hide */
    public static final String KEY_JS_ACTION_JOB_LOW_RUNNING_CTP = "js_action_job_low_running_ctp";
    /** @hide */
    public static final String KEY_JS_ACTION_JOB_MIN_START_CTP = "js_action_job_min_start_ctp";
    /** @hide */
    public static final String KEY_JS_ACTION_JOB_MIN_RUNNING_CTP = "js_action_job_min_running_ctp";
    /** @hide */
    public static final String KEY_JS_ACTION_JOB_TIMEOUT_PENALTY_CTP =
            "js_action_job_timeout_penalty_ctp";
    /** @hide */
    public static final String KEY_JS_ACTION_JOB_MAX_START_BASE_PRICE =
            "js_action_job_max_start_base_price";
    /** @hide */
    public static final String KEY_JS_ACTION_JOB_MAX_RUNNING_BASE_PRICE =
            "js_action_job_max_running_base_price";
    /** @hide */
    public static final String KEY_JS_ACTION_JOB_HIGH_START_BASE_PRICE =
            "js_action_job_high_start_base_price";
    /** @hide */
    public static final String KEY_JS_ACTION_JOB_HIGH_RUNNING_BASE_PRICE =
            "js_action_job_high_running_base_price";
    /** @hide */
    public static final String KEY_JS_ACTION_JOB_DEFAULT_START_BASE_PRICE =
            "js_action_job_default_start_base_price";
    /** @hide */
    public static final String KEY_JS_ACTION_JOB_DEFAULT_RUNNING_BASE_PRICE =
            "js_action_job_default_running_base_price";
    /** @hide */
    public static final String KEY_JS_ACTION_JOB_LOW_START_BASE_PRICE =
            "js_action_job_low_start_base_price";
    /** @hide */
    public static final String KEY_JS_ACTION_JOB_LOW_RUNNING_BASE_PRICE =
            "js_action_job_low_running_base_price";
    /** @hide */
    public static final String KEY_JS_ACTION_JOB_MIN_START_BASE_PRICE =
            "js_action_job_min_start_base_price";
    /** @hide */
    public static final String KEY_JS_ACTION_JOB_MIN_RUNNING_BASE_PRICE =
            "js_action_job_min_running_base_price";
    /** @hide */
    public static final String KEY_JS_ACTION_JOB_TIMEOUT_PENALTY_BASE_PRICE =
            "js_action_job_timeout_penalty_base_price";

    // Default values AlarmManager factors
    /** @hide */
    public static final long DEFAULT_AM_MIN_SATIATED_BALANCE_EXEMPTED_CAKES = arcToCake(500);
    /** @hide */
    public static final long DEFAULT_AM_MIN_SATIATED_BALANCE_HEADLESS_SYSTEM_APP_CAKES =
            arcToCake(256);
    /** @hide */
    public static final long DEFAULT_AM_MIN_SATIATED_BALANCE_OTHER_APP_CAKES = arcToCake(160);
    /** @hide */
    public static final long DEFAULT_AM_MAX_SATIATED_BALANCE_CAKES = arcToCake(960);
    /** @hide */
    public static final long DEFAULT_AM_INITIAL_CONSUMPTION_LIMIT_CAKES = arcToCake(2880);
    /** @hide */
    public static final long DEFAULT_AM_HARD_CONSUMPTION_LIMIT_CAKES = arcToCake(15_000);
    // TODO: add AlarmManager modifier default values
    /** @hide */
    public static final long DEFAULT_AM_REWARD_TOP_ACTIVITY_INSTANT_CAKES = arcToCake(0);
    /** @hide */
    // 10 megacakes = .01 ARC
    public static final long DEFAULT_AM_REWARD_TOP_ACTIVITY_ONGOING_CAKES = 10_000_000;
    /** @hide */
    public static final long DEFAULT_AM_REWARD_TOP_ACTIVITY_MAX_CAKES = arcToCake(500);
    /** @hide */
    public static final long DEFAULT_AM_REWARD_NOTIFICATION_SEEN_INSTANT_CAKES = arcToCake(3);
    /** @hide */
    public static final long DEFAULT_AM_REWARD_NOTIFICATION_SEEN_ONGOING_CAKES = arcToCake(0);
    /** @hide */
    public static final long DEFAULT_AM_REWARD_NOTIFICATION_SEEN_MAX_CAKES = arcToCake(60);
    /** @hide */
    public static final long DEFAULT_AM_REWARD_NOTIFICATION_SEEN_WITHIN_15_INSTANT_CAKES =
            arcToCake(5);
    /** @hide */
    public static final long DEFAULT_AM_REWARD_NOTIFICATION_SEEN_WITHIN_15_ONGOING_CAKES =
            arcToCake(0);
    /** @hide */
    public static final long DEFAULT_AM_REWARD_NOTIFICATION_SEEN_WITHIN_15_MAX_CAKES =
            arcToCake(500);
    /** @hide */
    public static final long DEFAULT_AM_REWARD_NOTIFICATION_INTERACTION_INSTANT_CAKES =
            arcToCake(5);
    /** @hide */
    public static final long DEFAULT_AM_REWARD_NOTIFICATION_INTERACTION_ONGOING_CAKES =
            arcToCake(0);
    /** @hide */
    public static final long DEFAULT_AM_REWARD_NOTIFICATION_INTERACTION_MAX_CAKES = arcToCake(500);
    /** @hide */
    public static final long DEFAULT_AM_REWARD_WIDGET_INTERACTION_INSTANT_CAKES = arcToCake(10);
    /** @hide */
    public static final long DEFAULT_AM_REWARD_WIDGET_INTERACTION_ONGOING_CAKES = arcToCake(0);
    /** @hide */
    public static final long DEFAULT_AM_REWARD_WIDGET_INTERACTION_MAX_CAKES = arcToCake(500);
    /** @hide */
    public static final long DEFAULT_AM_REWARD_OTHER_USER_INTERACTION_INSTANT_CAKES = arcToCake(10);
    /** @hide */
    public static final long DEFAULT_AM_REWARD_OTHER_USER_INTERACTION_ONGOING_CAKES = arcToCake(0);
    /** @hide */
    public static final long DEFAULT_AM_REWARD_OTHER_USER_INTERACTION_MAX_CAKES = arcToCake(500);
    /** @hide */
    public static final long DEFAULT_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_EXACT_WAKEUP_CTP_CAKES =
            arcToCake(3);
    /** @hide */
    public static final long DEFAULT_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_INEXACT_WAKEUP_CTP_CAKES =
            arcToCake(3);
    /** @hide */
    public static final long DEFAULT_AM_ACTION_ALARM_EXACT_WAKEUP_CTP_CAKES = arcToCake(3);
    /** @hide */
    public static final long DEFAULT_AM_ACTION_ALARM_INEXACT_WAKEUP_CTP_CAKES = arcToCake(3);
    /** @hide */
    public static final long DEFAULT_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_EXACT_NONWAKEUP_CTP_CAKES =
            arcToCake(1);
    /** @hide */
    public static final long DEFAULT_AM_ACTION_ALARM_EXACT_NONWAKEUP_CTP_CAKES = arcToCake(1);
    /** @hide */
    public static final long DEFAULT_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_INEXACT_NONWAKEUP_CTP_CAKES =
            arcToCake(1);
    /** @hide */
    public static final long DEFAULT_AM_ACTION_ALARM_INEXACT_NONWAKEUP_CTP_CAKES = arcToCake(1);
    /** @hide */
    public static final long DEFAULT_AM_ACTION_ALARM_ALARMCLOCK_CTP_CAKES = arcToCake(5);
    /** @hide */
    public static final long
            DEFAULT_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_EXACT_WAKEUP_BASE_PRICE_CAKES = arcToCake(5);
    /** @hide */
    public static final long
            DEFAULT_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_INEXACT_WAKEUP_BASE_PRICE_CAKES = arcToCake(4);
    /** @hide */
    public static final long DEFAULT_AM_ACTION_ALARM_EXACT_WAKEUP_BASE_PRICE_CAKES = arcToCake(4);
    /** @hide */
    public static final long DEFAULT_AM_ACTION_ALARM_INEXACT_WAKEUP_BASE_PRICE_CAKES = arcToCake(3);
    /** @hide */
    public static final long
            DEFAULT_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_EXACT_NONWAKEUP_BASE_PRICE_CAKES =
            arcToCake(3);
    /** @hide */
    public static final long DEFAULT_AM_ACTION_ALARM_EXACT_NONWAKEUP_BASE_PRICE_CAKES =
            arcToCake(2);
    /** @hide */
    public static final long
            DEFAULT_AM_ACTION_ALARM_ALLOW_WHILE_IDLE_INEXACT_NONWAKEUP_BASE_PRICE_CAKES =
            arcToCake(2);
    /** @hide */
    public static final long DEFAULT_AM_ACTION_ALARM_INEXACT_NONWAKEUP_BASE_PRICE_CAKES =
            arcToCake(1);
    /** @hide */
    public static final long DEFAULT_AM_ACTION_ALARM_ALARMCLOCK_BASE_PRICE_CAKES = arcToCake(10);

    // Default values JobScheduler factors
    // TODO: add time_since_usage variable to min satiated balance factors
    /** @hide */
    public static final long DEFAULT_JS_MIN_SATIATED_BALANCE_EXEMPTED_CAKES = arcToCake(15000);
    /** @hide */
    public static final long DEFAULT_JS_MIN_SATIATED_BALANCE_HEADLESS_SYSTEM_APP_CAKES =
            arcToCake(7500);
    /** @hide */
    public static final long DEFAULT_JS_MIN_SATIATED_BALANCE_OTHER_APP_CAKES = arcToCake(2000);
    /** @hide */
    public static final long DEFAULT_JS_MAX_SATIATED_BALANCE_CAKES = arcToCake(60000);
    /** @hide */
    public static final long DEFAULT_JS_INITIAL_CONSUMPTION_LIMIT_CAKES = arcToCake(29_000);
    /** @hide */
    // TODO: set hard limit based on device type (phone vs tablet vs etc) + battery size
    public static final long DEFAULT_JS_HARD_CONSUMPTION_LIMIT_CAKES = arcToCake(250_000);
    // TODO: add JobScheduler modifier default values
    /** @hide */
    public static final long DEFAULT_JS_REWARD_TOP_ACTIVITY_INSTANT_CAKES = arcToCake(0);
    /** @hide */
    public static final long DEFAULT_JS_REWARD_TOP_ACTIVITY_ONGOING_CAKES = CAKE_IN_ARC / 2;
    /** @hide */
    public static final long DEFAULT_JS_REWARD_TOP_ACTIVITY_MAX_CAKES = arcToCake(15000);
    /** @hide */
    public static final long DEFAULT_JS_REWARD_NOTIFICATION_SEEN_INSTANT_CAKES = arcToCake(1);
    /** @hide */
    public static final long DEFAULT_JS_REWARD_NOTIFICATION_SEEN_ONGOING_CAKES = arcToCake(0);
    /** @hide */
    public static final long DEFAULT_JS_REWARD_NOTIFICATION_SEEN_MAX_CAKES = arcToCake(10);
    /** @hide */
    public static final long DEFAULT_JS_REWARD_NOTIFICATION_INTERACTION_INSTANT_CAKES =
            arcToCake(5);
    /** @hide */
    public static final long DEFAULT_JS_REWARD_NOTIFICATION_INTERACTION_ONGOING_CAKES =
            arcToCake(0);
    /** @hide */
    public static final long DEFAULT_JS_REWARD_NOTIFICATION_INTERACTION_MAX_CAKES = arcToCake(5000);
    /** @hide */
    public static final long DEFAULT_JS_REWARD_WIDGET_INTERACTION_INSTANT_CAKES = arcToCake(10);
    /** @hide */
    public static final long DEFAULT_JS_REWARD_WIDGET_INTERACTION_ONGOING_CAKES = arcToCake(0);
    /** @hide */
    public static final long DEFAULT_JS_REWARD_WIDGET_INTERACTION_MAX_CAKES = arcToCake(5000);
    /** @hide */
    public static final long DEFAULT_JS_REWARD_OTHER_USER_INTERACTION_INSTANT_CAKES = arcToCake(10);
    /** @hide */
    public static final long DEFAULT_JS_REWARD_OTHER_USER_INTERACTION_ONGOING_CAKES = arcToCake(0);
    /** @hide */
    public static final long DEFAULT_JS_REWARD_OTHER_USER_INTERACTION_MAX_CAKES = arcToCake(5000);
    /** @hide */
    public static final long DEFAULT_JS_ACTION_JOB_MAX_START_CTP_CAKES = arcToCake(3);
    /** @hide */
    public static final long DEFAULT_JS_ACTION_JOB_MAX_RUNNING_CTP_CAKES = arcToCake(2);
    /** @hide */
    public static final long DEFAULT_JS_ACTION_JOB_HIGH_START_CTP_CAKES = arcToCake(3);
    /** @hide */
    public static final long DEFAULT_JS_ACTION_JOB_HIGH_RUNNING_CTP_CAKES = arcToCake(2);
    /** @hide */
    public static final long DEFAULT_JS_ACTION_JOB_DEFAULT_START_CTP_CAKES = arcToCake(3);
    /** @hide */
    public static final long DEFAULT_JS_ACTION_JOB_DEFAULT_RUNNING_CTP_CAKES = arcToCake(2);
    /** @hide */
    public static final long DEFAULT_JS_ACTION_JOB_LOW_START_CTP_CAKES = arcToCake(3);
    /** @hide */
    public static final long DEFAULT_JS_ACTION_JOB_LOW_RUNNING_CTP_CAKES = arcToCake(2);
    /** @hide */
    public static final long DEFAULT_JS_ACTION_JOB_MIN_START_CTP_CAKES = arcToCake(3);
    /** @hide */
    public static final long DEFAULT_JS_ACTION_JOB_MIN_RUNNING_CTP_CAKES = arcToCake(2);
    /** @hide */
    public static final long DEFAULT_JS_ACTION_JOB_TIMEOUT_PENALTY_CTP_CAKES = arcToCake(30);
    /** @hide */
    public static final long DEFAULT_JS_ACTION_JOB_MAX_START_BASE_PRICE_CAKES = arcToCake(10);
    /** @hide */
    public static final long DEFAULT_JS_ACTION_JOB_MAX_RUNNING_BASE_PRICE_CAKES = arcToCake(5);
    /** @hide */
    public static final long DEFAULT_JS_ACTION_JOB_HIGH_START_BASE_PRICE_CAKES = arcToCake(8);
    /** @hide */
    public static final long DEFAULT_JS_ACTION_JOB_HIGH_RUNNING_BASE_PRICE_CAKES = arcToCake(4);
    /** @hide */
    public static final long DEFAULT_JS_ACTION_JOB_DEFAULT_START_BASE_PRICE_CAKES = arcToCake(6);
    /** @hide */
    public static final long DEFAULT_JS_ACTION_JOB_DEFAULT_RUNNING_BASE_PRICE_CAKES = arcToCake(3);
    /** @hide */
    public static final long DEFAULT_JS_ACTION_JOB_LOW_START_BASE_PRICE_CAKES = arcToCake(4);
    /** @hide */
    public static final long DEFAULT_JS_ACTION_JOB_LOW_RUNNING_BASE_PRICE_CAKES = arcToCake(2);
    /** @hide */
    public static final long DEFAULT_JS_ACTION_JOB_MIN_START_BASE_PRICE_CAKES = arcToCake(2);
    /** @hide */
    public static final long DEFAULT_JS_ACTION_JOB_MIN_RUNNING_BASE_PRICE_CAKES = arcToCake(1);
    /** @hide */
    public static final long DEFAULT_JS_ACTION_JOB_TIMEOUT_PENALTY_BASE_PRICE_CAKES = arcToCake(60);
}
