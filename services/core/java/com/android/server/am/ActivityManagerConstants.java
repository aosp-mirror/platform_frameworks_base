/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.am;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED;
import static android.os.PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_NOT_ALLOWED;
import static android.os.PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_NONE;

import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_POWER_QUICK;
import static com.android.server.am.BroadcastConstants.DEFER_BOOT_COMPLETED_BROADCAST_BACKGROUND_RESTRICTED_ONLY;
import static com.android.server.am.BroadcastConstants.DEFER_BOOT_COMPLETED_BROADCAST_TARGET_T_ONLY;
import static com.android.server.am.BroadcastConstants.getDeviceConfigBoolean;

import android.annotation.NonNull;
import android.app.ActivityThread;
import android.app.ForegroundServiceTypePolicy;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.os.PowerExemptionManager;
import android.os.SystemClock;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.OnPropertiesChangedListener;
import android.provider.DeviceConfig.Properties;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.KeyValueListParser;
import android.util.Slog;
import android.util.SparseBooleanArray;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;

import dalvik.annotation.optimization.NeverCompile;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Settings constants that can modify the activity manager's behavior.
 */
final class ActivityManagerConstants extends ContentObserver {
    private static final String TAG = "ActivityManagerConstants";

    // Key names stored in the settings value.
    static final String KEY_BACKGROUND_SETTLE_TIME = "background_settle_time";

    private static final String KEY_FGSERVICE_MIN_SHOWN_TIME
            = "fgservice_min_shown_time";
    private static final String KEY_FGSERVICE_MIN_REPORT_TIME
            = "fgservice_min_report_time";
    private static final String KEY_FGSERVICE_SCREEN_ON_BEFORE_TIME
            = "fgservice_screen_on_before_time";
    private static final String KEY_FGSERVICE_SCREEN_ON_AFTER_TIME
            = "fgservice_screen_on_after_time";

    private static final String KEY_FGS_BOOT_COMPLETED_ALLOWLIST = "fgs_boot_completed_allowlist";

    private static final String KEY_CONTENT_PROVIDER_RETAIN_TIME = "content_provider_retain_time";
    private static final String KEY_GC_TIMEOUT = "gc_timeout";
    private static final String KEY_GC_MIN_INTERVAL = "gc_min_interval";
    private static final String KEY_FORCE_BACKGROUND_CHECK_ON_RESTRICTED_APPS =
            "force_bg_check_on_restricted";
    private static final String KEY_FULL_PSS_MIN_INTERVAL = "full_pss_min_interval";
    private static final String KEY_FULL_PSS_LOWERED_INTERVAL = "full_pss_lowered_interval";
    private static final String KEY_POWER_CHECK_INTERVAL = "power_check_interval";
    private static final String KEY_POWER_CHECK_MAX_CPU_1 = "power_check_max_cpu_1";
    private static final String KEY_POWER_CHECK_MAX_CPU_2 = "power_check_max_cpu_2";
    private static final String KEY_POWER_CHECK_MAX_CPU_3 = "power_check_max_cpu_3";
    private static final String KEY_POWER_CHECK_MAX_CPU_4 = "power_check_max_cpu_4";
    /** Used for all apps on R and earlier versions. */
    private static final String KEY_SERVICE_USAGE_INTERACTION_TIME_PRE_S =
            "service_usage_interaction_time";
    private static final String KEY_SERVICE_USAGE_INTERACTION_TIME_POST_S =
            "service_usage_interaction_time_post_s";
    /** Used for all apps on R and earlier versions. */
    private static final String KEY_USAGE_STATS_INTERACTION_INTERVAL_PRE_S =
            "usage_stats_interaction_interval";
    private static final String KEY_USAGE_STATS_INTERACTION_INTERVAL_POST_S =
            "usage_stats_interaction_interval_post_s";
    private static final String KEY_IMPERCEPTIBLE_KILL_EXEMPT_PACKAGES =
            "imperceptible_kill_exempt_packages";
    private static final String KEY_IMPERCEPTIBLE_KILL_EXEMPT_PROC_STATES =
            "imperceptible_kill_exempt_proc_states";
    static final String KEY_SERVICE_RESTART_DURATION = "service_restart_duration";
    static final String KEY_SERVICE_RESET_RUN_DURATION = "service_reset_run_duration";
    static final String KEY_SERVICE_RESTART_DURATION_FACTOR = "service_restart_duration_factor";
    static final String KEY_SERVICE_MIN_RESTART_TIME_BETWEEN = "service_min_restart_time_between";
    static final String KEY_MAX_SERVICE_INACTIVITY = "service_max_inactivity";
    static final String KEY_BG_START_TIMEOUT = "service_bg_start_timeout";
    static final String KEY_SERVICE_BG_ACTIVITY_START_TIMEOUT = "service_bg_activity_start_timeout";
    static final String KEY_BOUND_SERVICE_CRASH_RESTART_DURATION = "service_crash_restart_duration";
    static final String KEY_BOUND_SERVICE_CRASH_MAX_RETRY = "service_crash_max_retry";
    static final String KEY_PROCESS_START_ASYNC = "process_start_async";
    static final String KEY_MEMORY_INFO_THROTTLE_TIME = "memory_info_throttle_time";
    static final String KEY_TOP_TO_FGS_GRACE_DURATION = "top_to_fgs_grace_duration";
    static final String KEY_TOP_TO_ALMOST_PERCEPTIBLE_GRACE_DURATION =
            "top_to_almost_perceptible_grace_duration";
    static final String KEY_PENDINGINTENT_WARNING_THRESHOLD = "pendingintent_warning_threshold";
    static final String KEY_MIN_CRASH_INTERVAL = "min_crash_interval";
    static final String KEY_PROCESS_CRASH_COUNT_RESET_INTERVAL =
            "process_crash_count_reset_interval";
    static final String KEY_PROCESS_CRASH_COUNT_LIMIT = "process_crash_count_limit";
    static final String KEY_BOOT_TIME_TEMP_ALLOWLIST_DURATION = "boot_time_temp_allowlist_duration";
    static final String KEY_FG_TO_BG_FGS_GRACE_DURATION = "fg_to_bg_fgs_grace_duration";
    static final String KEY_VISIBLE_TO_INVISIBLE_UIJ_SCHEDULE_GRACE_DURATION =
            "vis_to_invis_uij_schedule_grace_duration";
    static final String KEY_FGS_START_FOREGROUND_TIMEOUT = "fgs_start_foreground_timeout";
    static final String KEY_FGS_ATOM_SAMPLE_RATE = "fgs_atom_sample_rate";
    static final String KEY_FGS_START_ALLOWED_LOG_SAMPLE_RATE = "fgs_start_allowed_log_sample_rate";
    static final String KEY_FGS_START_DENIED_LOG_SAMPLE_RATE = "fgs_start_denied_log_sample_rate";
    static final String KEY_FGS_ALLOW_OPT_OUT = "fgs_allow_opt_out";
    static final String KEY_EXTRA_SERVICE_RESTART_DELAY_ON_MEM_PRESSURE =
            "extra_delay_svc_restart_mem_pressure";
    static final String KEY_ENABLE_EXTRA_SERVICE_RESTART_DELAY_ON_MEM_PRESSURE =
            "enable_extra_delay_svc_restart_mem_pressure";
    static final String KEY_KILL_BG_RESTRICTED_CACHED_IDLE = "kill_bg_restricted_cached_idle";
    static final String KEY_KILL_BG_RESTRICTED_CACHED_IDLE_SETTLE_TIME =
            "kill_bg_restricted_cached_idle_settle_time";
    static final String KEY_MAX_PREVIOUS_TIME = "max_previous_time";
    /**
     * Note this key is on {@link DeviceConfig#NAMESPACE_ACTIVITY_MANAGER_COMPONENT_ALIAS}.
     * @see #mEnableComponentAlias
     */
    static final String KEY_ENABLE_COMPONENT_ALIAS = "enable_experimental_component_alias";
    /**
     * Note this key is on {@link DeviceConfig#NAMESPACE_ACTIVITY_MANAGER_COMPONENT_ALIAS}.
     * @see #mComponentAliasOverrides
     */
    static final String KEY_COMPONENT_ALIAS_OVERRIDES = "component_alias_overrides";

    /**
     * Indicates the maximum time that an app is blocked for the network rules to get updated.
     */
    static final String KEY_NETWORK_ACCESS_TIMEOUT_MS = "network_access_timeout_ms";

    static final String KEY_USE_TIERED_CACHED_ADJ = "use_tiered_cached_adj";
    static final String KEY_TIERED_CACHED_ADJ_DECAY_TIME = "tiered_cached_adj_decay_time";

    /**
     * Whether or not to enable the new oom adjuster implementation.
     */
    static final String KEY_ENABLE_NEW_OOMADJ = "enable_new_oom_adj";

    private static final int DEFAULT_MAX_CACHED_PROCESSES = 1024;
    private static final boolean DEFAULT_PRIORITIZE_ALARM_BROADCASTS = true;
    private static final long DEFAULT_FGSERVICE_MIN_SHOWN_TIME = 2*1000;
    private static final long DEFAULT_FGSERVICE_MIN_REPORT_TIME = 3*1000;
    private static final long DEFAULT_FGSERVICE_SCREEN_ON_BEFORE_TIME = 1*1000;
    private static final long DEFAULT_FGSERVICE_SCREEN_ON_AFTER_TIME = 5*1000;

    private static final int DEFAULT_FGS_BOOT_COMPLETED_ALLOWLIST =
            FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                | FOREGROUND_SERVICE_TYPE_HEALTH
                | FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
                | FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
                | FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                | FOREGROUND_SERVICE_TYPE_LOCATION;

    private static final long DEFAULT_CONTENT_PROVIDER_RETAIN_TIME = 20*1000;
    private static final long DEFAULT_GC_TIMEOUT = 5*1000;
    private static final long DEFAULT_GC_MIN_INTERVAL = 60*1000;
    private static final long DEFAULT_FULL_PSS_MIN_INTERVAL = 20*60*1000;
    private static final boolean DEFAULT_FORCE_BACKGROUND_CHECK_ON_RESTRICTED_APPS = true;
    private static final long DEFAULT_FULL_PSS_LOWERED_INTERVAL = 5*60*1000;
    private static final long DEFAULT_POWER_CHECK_INTERVAL = (DEBUG_POWER_QUICK ? 1 : 5) * 60*1000;
    private static final int DEFAULT_POWER_CHECK_MAX_CPU_1 = 25;
    private static final int DEFAULT_POWER_CHECK_MAX_CPU_2 = 25;
    private static final int DEFAULT_POWER_CHECK_MAX_CPU_3 = 10;
    private static final int DEFAULT_POWER_CHECK_MAX_CPU_4 = 2;
    private static final long DEFAULT_SERVICE_USAGE_INTERACTION_TIME_PRE_S = 30 * 60 * 1000;
    private static final long DEFAULT_SERVICE_USAGE_INTERACTION_TIME_POST_S = 60 * 1000;
    private static final long DEFAULT_USAGE_STATS_INTERACTION_INTERVAL_PRE_S = 2 * 60 * 60 * 1000;
    private static final long DEFAULT_USAGE_STATS_INTERACTION_INTERVAL_POST_S = 10 * 60 * 1000;
    private static final long DEFAULT_SERVICE_RESTART_DURATION = 1*1000;
    private static final long DEFAULT_SERVICE_RESET_RUN_DURATION = 60*1000;
    private static final int DEFAULT_SERVICE_RESTART_DURATION_FACTOR = 4;
    private static final long DEFAULT_SERVICE_MIN_RESTART_TIME_BETWEEN = 10*1000;
    private static final long DEFAULT_MAX_SERVICE_INACTIVITY = 30*60*1000;
    private static final long DEFAULT_BG_START_TIMEOUT = 15*1000;
    private static final long DEFAULT_SERVICE_BG_ACTIVITY_START_TIMEOUT = 10_000;
    private static final long DEFAULT_BOUND_SERVICE_CRASH_RESTART_DURATION = 30*60_000;
    private static final int DEFAULT_BOUND_SERVICE_CRASH_MAX_RETRY = 16;
    private static final boolean DEFAULT_PROCESS_START_ASYNC = true;
    private static final long DEFAULT_MEMORY_INFO_THROTTLE_TIME = 5*60*1000;
    private static final long DEFAULT_TOP_TO_FGS_GRACE_DURATION = 15 * 1000;
    private static final long DEFAULT_TOP_TO_ALMOST_PERCEPTIBLE_GRACE_DURATION = 15 * 1000;
    private static final int DEFAULT_PENDINGINTENT_WARNING_THRESHOLD = 2000;
    private static final int DEFAULT_MIN_CRASH_INTERVAL = 2 * 60 * 1000;
    private static final int DEFAULT_MAX_PHANTOM_PROCESSES = 32;
    private static final int DEFAULT_PROCESS_CRASH_COUNT_RESET_INTERVAL = 12 * 60 * 60 * 1000;
    private static final int DEFAULT_PROCESS_CRASH_COUNT_LIMIT = 12;
    private static final int DEFAULT_BOOT_TIME_TEMP_ALLOWLIST_DURATION = 20 * 1000;
    private static final long DEFAULT_FG_TO_BG_FGS_GRACE_DURATION = 5 * 1000;
    private static final long DEFAULT_VISIBLE_TO_INVISIBLE_UIJ_SCHEDULE_GRACE_DURATION =
            DEFAULT_FG_TO_BG_FGS_GRACE_DURATION;
    private static final int DEFAULT_FGS_START_FOREGROUND_TIMEOUT_MS = 10 * 1000;
    private static final float DEFAULT_FGS_ATOM_SAMPLE_RATE = 1; // 100 %
    private static final float DEFAULT_FGS_START_ALLOWED_LOG_SAMPLE_RATE = 0.25f; // 25%
    private static final float DEFAULT_FGS_START_DENIED_LOG_SAMPLE_RATE = 1; // 100%
    private static final long DEFAULT_PROCESS_KILL_TIMEOUT_MS = 10 * 1000;
    private static final long DEFAULT_NETWORK_ACCESS_TIMEOUT_MS = 200; // 0.2 sec
    private static final long DEFAULT_MAX_PREVIOUS_TIME = 60 * 1000; // 60s

    static final long DEFAULT_BACKGROUND_SETTLE_TIME = 60 * 1000;
    static final long DEFAULT_KILL_BG_RESTRICTED_CACHED_IDLE_SETTLE_TIME_MS = 60 * 1000;
    static final boolean DEFAULT_KILL_BG_RESTRICTED_CACHED_IDLE = true;

    static final int DEFAULT_MAX_SERVICE_CONNECTIONS_PER_PROCESS = 3000;

    private static final boolean DEFAULT_USE_TIERED_CACHED_ADJ = false;
    private static final long DEFAULT_TIERED_CACHED_ADJ_DECAY_TIME = 60 * 1000;

    /**
     * The default value to {@link #KEY_ENABLE_NEW_OOMADJ}.
     */
    private static final boolean DEFAULT_ENABLE_NEW_OOM_ADJ = Flags.oomadjusterCorrectnessRewrite();

    /**
     * Same as {@link TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_NOT_ALLOWED}
     */
    private static final int
            DEFAULT_PUSH_MESSAGING_OVER_QUOTA_BEHAVIOR = 1;
    private static final boolean DEFAULT_FGS_ALLOW_OPT_OUT = false;

    private static final boolean DEFAULT_SYSTEM_EXEMPT_POWER_RESTRICTIONS_ENABLED = true;

    /**
     * The extra delays we're putting to service restarts, based on current memory pressure.
     */
    private static final long DEFAULT_EXTRA_SERVICE_RESTART_DELAY_ON_NORMAL_MEM = 0; // ms
    private static final long DEFAULT_EXTRA_SERVICE_RESTART_DELAY_ON_MODERATE_MEM = 10000; // ms
    private static final long DEFAULT_EXTRA_SERVICE_RESTART_DELAY_ON_LOW_MEM = 20000; // ms
    private static final long DEFAULT_EXTRA_SERVICE_RESTART_DELAY_ON_CRITICAL_MEM = 30000; // ms
    private static final long[] DEFAULT_EXTRA_SERVICE_RESTART_DELAY_ON_MEM_PRESSURE  = {
        DEFAULT_EXTRA_SERVICE_RESTART_DELAY_ON_NORMAL_MEM,
        DEFAULT_EXTRA_SERVICE_RESTART_DELAY_ON_MODERATE_MEM,
        DEFAULT_EXTRA_SERVICE_RESTART_DELAY_ON_LOW_MEM,
        DEFAULT_EXTRA_SERVICE_RESTART_DELAY_ON_CRITICAL_MEM,
    };

    /**
     * Whether or not to enable the extra delays to service restarts on memory pressure.
     */
    private static final boolean DEFAULT_ENABLE_EXTRA_SERVICE_RESTART_DELAY_ON_MEM_PRESSURE = true;
    private static final boolean DEFAULT_ENABLE_COMPONENT_ALIAS = false;
    private static final String DEFAULT_COMPONENT_ALIAS_OVERRIDES = "";

    private static final int DEFAULT_DEFER_BOOT_COMPLETED_BROADCAST =
             DEFER_BOOT_COMPLETED_BROADCAST_BACKGROUND_RESTRICTED_ONLY
             | DEFER_BOOT_COMPLETED_BROADCAST_TARGET_T_ONLY;

    private static final int DEFAULT_SERVICE_START_FOREGROUND_TIMEOUT_MS = 30 * 1000;

    private static final int DEFAULT_SERVICE_START_FOREGROUND_ANR_DELAY_MS = 10 * 1000;

    private static final long DEFAULT_SERVICE_BIND_ALMOST_PERCEPTIBLE_TIMEOUT_MS = 15 * 1000;

    /**
     * Default value to {@link #SERVICE_TIMEOUT}.
     */
    private static final long DEFAULT_SERVICE_TIMEOUT = 20 * 1000 * Build.HW_TIMEOUT_MULTIPLIER;

    /**
     * Default value to {@link #SERVICE_BACKGROUND_TIMEOUT}.
     */
    private static final long DEFAULT_SERVICE_BACKGROUND_TIMEOUT = DEFAULT_SERVICE_TIMEOUT * 10;

    /**
     * Maximum number of cached processes.
     */
    private static final String KEY_MAX_CACHED_PROCESSES = "max_cached_processes";

    /**
     * Maximum number of cached processes.
     */
    private static final String KEY_MAX_PHANTOM_PROCESSES = "max_phantom_processes";

    /**
     * Enables proactive killing of cached apps
     */
    private static final String KEY_PROACTIVE_KILLS_ENABLED = "proactive_kills_enabled";

    /**
      * Trim LRU cached app when swap falls below this minimum percentage.
      *
      * Depends on KEY_PROACTIVE_KILLS_ENABLED
      */
    private static final String KEY_LOW_SWAP_THRESHOLD_PERCENT = "low_swap_threshold_percent";

    /**
     * Default value for mFlagBackgroundActivityStartsEnabled if not explicitly set in
     * Settings.Global. This allows it to be set experimentally unless it has been
     * enabled/disabled in developer options. Defaults to false.
     */
    private static final String KEY_DEFAULT_BACKGROUND_ACTIVITY_STARTS_ENABLED =
            "default_background_activity_starts_enabled";

    /**
     * Default value for mFlagBackgroundFgsStartRestrictionEnabled if not explicitly set in
     * Settings.Global.
     */
    private static final String KEY_DEFAULT_BACKGROUND_FGS_STARTS_RESTRICTION_ENABLED =
            "default_background_fgs_starts_restriction_enabled";

    /**
     * Default value for mFlagFgsStartRestrictionEnabled if not explicitly set in
     * Settings.Global.
     */
    private static final String KEY_DEFAULT_FGS_STARTS_RESTRICTION_ENABLED =
            "default_fgs_starts_restriction_enabled";

    /**
     * Default value for mFgsStartRestrictionNotificationEnabled if not explicitly set in
     * Settings.Global.
     */
    private static final String KEY_DEFAULT_FGS_STARTS_RESTRICTION_NOTIFICATION_ENABLED =
            "default_fgs_starts_restriction_notification_enabled";

    /**
     * Default value for mFgsStartRestrictionCheckCallerTargetSdk if not explicitly set in
     * Settings.Global.
     */
    private static final String KEY_DEFAULT_FGS_STARTS_RESTRICTION_CHECK_CALLER_TARGET_SDK =
            "default_fgs_starts_restriction_check_caller_target_sdk";

    /**
     * Whether FGS notification display is deferred following the transition into
     * the foreground state.  Default behavior is {@code true} unless overridden.
     */
    private static final String KEY_DEFERRED_FGS_NOTIFICATIONS_ENABLED =
            "deferred_fgs_notifications_enabled";

    /** Whether FGS notification deferral applies only to those apps targeting
     * API version S or higher.  Default is {@code true} unless overidden.
     */
    private static final String KEY_DEFERRED_FGS_NOTIFICATIONS_API_GATED =
            "deferred_fgs_notifications_api_gated";

    /**
     * Time in milliseconds to defer display of FGS notifications following the
     * transition into the foreground state.  Default is 10_000 (ten seconds)
     * unless overridden.
     */
    private static final String KEY_DEFERRED_FGS_NOTIFICATION_INTERVAL =
            "deferred_fgs_notification_interval";

    /**
     * Same as {@link #KEY_DEFERRED_FGS_NOTIFICATION_INTERVAL} but for "short FGS".
     */
    private static final String KEY_DEFERRED_FGS_NOTIFICATION_INTERVAL_FOR_SHORT =
            "deferred_fgs_notification_interval_for_short";

    /**
     * Time in milliseconds; once an FGS notification for a given uid has been
     * deferred, no subsequent FGS notification from that uid will be deferred
     * until this amount of time has passed.  Default is two minutes
     * (2 * 60 * 1000) unless overridden.
     */
    private static final String KEY_DEFERRED_FGS_NOTIFICATION_EXCLUSION_TIME =
            "deferred_fgs_notification_exclusion_time";

    /**
     * Same as {@link #KEY_DEFERRED_FGS_NOTIFICATION_EXCLUSION_TIME} but for "short FGS".
     */
    private static final String KEY_DEFERRED_FGS_NOTIFICATION_EXCLUSION_TIME_FOR_SHORT =
            "deferred_fgs_notification_exclusion_time_for_short";

    /**
     * Default value for mFlagSystemExemptPowerRestrictionEnabled.
     */
    private static final String KEY_SYSTEM_EXEMPT_POWER_RESTRICTIONS_ENABLED =
            "system_exempt_power_restrictions_enabled";

    /**
     * Default value for mPushMessagingOverQuotaBehavior if not explicitly set in
     * Settings.Global.
     */
    private static final String KEY_PUSH_MESSAGING_OVER_QUOTA_BEHAVIOR =
            "push_messaging_over_quota_behavior";

    /**
     * Time in milliseconds; the allowed duration from a process is killed until it's really gone.
     */
    private static final String KEY_PROCESS_KILL_TIMEOUT = "process_kill_timeout";

    /**
     * {@code true} to send in-flight alarm broadcasts ahead of non-alarms; {@code false}
     * to queue alarm broadcasts identically to non-alarms [i.e. the pre-U behavior]; or
     * {@code null} or empty string in order to fall back to whatever the build-time default
     * was for the device.
     */
    private static final String KEY_PRIORITIZE_ALARM_BROADCASTS = "prioritize_alarm_broadcasts";

    private static final String KEY_DEFER_BOOT_COMPLETED_BROADCAST =
            "defer_boot_completed_broadcast";

    private static final String KEY_SERVICE_START_FOREGROUND_TIMEOUT_MS =
            "service_start_foreground_timeout_ms";

    private static final String KEY_SERVICE_START_FOREGROUND_ANR_DELAY_MS =
            "service_start_foreground_anr_delay_ms";

    private static final String KEY_SERVICE_BIND_ALMOST_PERCEPTIBLE_TIMEOUT_MS =
            "service_bind_almost_perceptible_timeout_ms";

    private static final String KEY_MAX_SERVICE_CONNECTIONS_PER_PROCESS =
            "max_service_connections_per_process";

    private static final String KEY_PROC_STATE_DEBUG_UIDS = "proc_state_debug_uids";

    /**
     * UIDs we want to print detailed info in OomAdjuster.
     * It's only used for debugging, and it's almost never updated, so we just create a new
     * array when it's changed to avoid synchronization.
     */
    volatile SparseBooleanArray mProcStateDebugUids = new SparseBooleanArray(0);
    volatile boolean mEnableProcStateStacktrace = false;
    volatile int mProcStateDebugSetProcStateDelay = 0;
    volatile int mProcStateDebugSetUidStateDelay = 0;

    // Maximum number of cached processes we will allow.
    public int MAX_CACHED_PROCESSES = DEFAULT_MAX_CACHED_PROCESSES;

    // This is the amount of time we allow an app to settle after it goes into the background,
    // before we start restricting what it can do.
    public long BACKGROUND_SETTLE_TIME = DEFAULT_BACKGROUND_SETTLE_TIME;

    // The minimum time we allow a foreground service to run with a notification and the
    // screen on without otherwise telling the user about it.  (If it runs for less than this,
    // it will still be reported to the user as a running app for at least this amount of time.)
    public long FGSERVICE_MIN_SHOWN_TIME = DEFAULT_FGSERVICE_MIN_SHOWN_TIME;

    // If a foreground service is shown for less than FGSERVICE_MIN_SHOWN_TIME, we will display
    // the background app running notification about it for at least this amount of time (if it
    // is larger than the remaining shown time).
    public long FGSERVICE_MIN_REPORT_TIME = DEFAULT_FGSERVICE_MIN_REPORT_TIME;

    // The minimum amount of time the foreground service needs to have remain being shown
    // before the screen goes on for us to consider it not worth showing to the user.  That is
    // if an app has a foreground service that stops itself this amount of time or more before
    // the user turns on the screen, we will just let it go without the user being told about it.
    public long FGSERVICE_SCREEN_ON_BEFORE_TIME = DEFAULT_FGSERVICE_SCREEN_ON_BEFORE_TIME;

    // The minimum amount of time a foreground service should remain reported to the user if
    // it is stopped when the screen turns on.  This is the time from when the screen turns
    // on until we will stop reporting it.
    public long FGSERVICE_SCREEN_ON_AFTER_TIME = DEFAULT_FGSERVICE_SCREEN_ON_AFTER_TIME;

    // Allow-list for FGS types that are allowed to start from BOOT_COMPLETED.
    public int FGS_BOOT_COMPLETED_ALLOWLIST = DEFAULT_FGS_BOOT_COMPLETED_ALLOWLIST;

    // How long we will retain processes hosting content providers in the "last activity"
    // state before allowing them to drop down to the regular cached LRU list.  This is
    // to avoid thrashing of provider processes under low memory situations.
    long CONTENT_PROVIDER_RETAIN_TIME = DEFAULT_CONTENT_PROVIDER_RETAIN_TIME;

    // How long to wait after going idle before forcing apps to GC.
    long GC_TIMEOUT = DEFAULT_GC_TIMEOUT;

    // The minimum amount of time between successive GC requests for a process.
    long GC_MIN_INTERVAL = DEFAULT_GC_MIN_INTERVAL;

    /**
     * Whether or not Background Check should be forced on any apps in the
     * {@link android.app.usage.UsageStatsManager#STANDBY_BUCKET_RESTRICTED} bucket,
     * regardless of target SDK version.
     */
    boolean FORCE_BACKGROUND_CHECK_ON_RESTRICTED_APPS =
            DEFAULT_FORCE_BACKGROUND_CHECK_ON_RESTRICTED_APPS;

    // The minimum amount of time between successive PSS requests for a process.
    long FULL_PSS_MIN_INTERVAL = DEFAULT_FULL_PSS_MIN_INTERVAL;

    // The minimum amount of time between successive PSS requests for a process
    // when the request is due to the memory state being lowered.
    long FULL_PSS_LOWERED_INTERVAL = DEFAULT_FULL_PSS_LOWERED_INTERVAL;

    // The minimum sample duration we will allow before deciding we have
    // enough data on CPU usage to start killing things.
    long POWER_CHECK_INTERVAL = DEFAULT_POWER_CHECK_INTERVAL;

    // The maximum CPU (as a percentage) a process is allowed to use over the first
    // power check interval that it is cached.
    int POWER_CHECK_MAX_CPU_1 = DEFAULT_POWER_CHECK_MAX_CPU_1;

    // The maximum CPU (as a percentage) a process is allowed to use over the second
    // power check interval that it is cached.  The home app will never check for less
    // CPU than this (it will not test against the 3 or 4 levels).
    int POWER_CHECK_MAX_CPU_2 = DEFAULT_POWER_CHECK_MAX_CPU_2;

    // The maximum CPU (as a percentage) a process is allowed to use over the third
    // power check interval that it is cached.
    int POWER_CHECK_MAX_CPU_3 = DEFAULT_POWER_CHECK_MAX_CPU_3;

    // The maximum CPU (as a percentage) a process is allowed to use over the fourth
    // power check interval that it is cached.
    int POWER_CHECK_MAX_CPU_4 = DEFAULT_POWER_CHECK_MAX_CPU_4;

    // This is the amount of time an app needs to be running a foreground service before
    // we will consider it to be doing interaction for usage stats.
    // Only used for apps targeting pre-S versions.
    long SERVICE_USAGE_INTERACTION_TIME_PRE_S = DEFAULT_SERVICE_USAGE_INTERACTION_TIME_PRE_S;

    // This is the amount of time an app needs to be running a foreground service before
    // we will consider it to be doing interaction for usage stats.
    // Only used for apps targeting versions S and above.
    long SERVICE_USAGE_INTERACTION_TIME_POST_S = DEFAULT_SERVICE_USAGE_INTERACTION_TIME_POST_S;

    // Maximum amount of time we will allow to elapse before re-reporting usage stats
    // interaction with foreground processes.
    // Only used for apps targeting pre-S versions.
    long USAGE_STATS_INTERACTION_INTERVAL_PRE_S = DEFAULT_USAGE_STATS_INTERACTION_INTERVAL_PRE_S;

    // Maximum amount of time we will allow to elapse before re-reporting usage stats
    // interaction with foreground processes.
    // Only used for apps targeting versions S and above.
    long USAGE_STATS_INTERACTION_INTERVAL_POST_S = DEFAULT_USAGE_STATS_INTERACTION_INTERVAL_POST_S;

    // How long a service needs to be running until restarting its process
    // is no longer considered to be a relaunch of the service.
    public long SERVICE_RESTART_DURATION = DEFAULT_SERVICE_RESTART_DURATION;

    // How long a service needs to be running until it will start back at
    // SERVICE_RESTART_DURATION after being killed.
    public long SERVICE_RESET_RUN_DURATION = DEFAULT_SERVICE_RESET_RUN_DURATION;

    // Multiplying factor to increase restart duration time by, for each time
    // a service is killed before it has run for SERVICE_RESET_RUN_DURATION.
    public int SERVICE_RESTART_DURATION_FACTOR = DEFAULT_SERVICE_RESTART_DURATION_FACTOR;

    // The minimum amount of time between restarting services that we allow.
    // That is, when multiple services are restarting, we won't allow each
    // to restart less than this amount of time from the last one.
    public long SERVICE_MIN_RESTART_TIME_BETWEEN = DEFAULT_SERVICE_MIN_RESTART_TIME_BETWEEN;

    // How long we wait for a service to finish executing.
    long SERVICE_TIMEOUT = DEFAULT_SERVICE_TIMEOUT;

    // How long we wait for a service to finish executing.
    long SERVICE_BACKGROUND_TIMEOUT = DEFAULT_SERVICE_BACKGROUND_TIMEOUT;

    // Maximum amount of time for there to be no activity on a service before
    // we consider it non-essential and allow its process to go on the
    // LRU background list.
    public long MAX_SERVICE_INACTIVITY = DEFAULT_MAX_SERVICE_INACTIVITY;

    // How long we wait for a background started service to stop itself before
    // allowing the next pending start to run.
    public long BG_START_TIMEOUT = DEFAULT_BG_START_TIMEOUT;

    // For a service that has been allowed to start background activities, how long after it started
    // its process can start a background activity.
    public long SERVICE_BG_ACTIVITY_START_TIMEOUT = DEFAULT_SERVICE_BG_ACTIVITY_START_TIMEOUT;

    // Initial backoff delay for retrying bound foreground services
    public long BOUND_SERVICE_CRASH_RESTART_DURATION = DEFAULT_BOUND_SERVICE_CRASH_RESTART_DURATION;

    // Maximum number of retries for bound foreground services that crash soon after start
    public long BOUND_SERVICE_MAX_CRASH_RETRY = DEFAULT_BOUND_SERVICE_CRASH_MAX_RETRY;

    // Indicates if the processes need to be started asynchronously.
    public boolean FLAG_PROCESS_START_ASYNC = DEFAULT_PROCESS_START_ASYNC;

    // The minimum time we allow between requests for the MemoryInfo of a process to
    // throttle requests from apps.
    public long MEMORY_INFO_THROTTLE_TIME = DEFAULT_MEMORY_INFO_THROTTLE_TIME;

    // Allow app just moving from TOP to FOREGROUND_SERVICE to stay in a higher adj value for
    // this long.
    public volatile long TOP_TO_FGS_GRACE_DURATION = DEFAULT_TOP_TO_FGS_GRACE_DURATION;

    /**
     * Allow app just leaving TOP with an already running ALMOST_PERCEPTIBLE service to stay in
     * a higher adj value for this long.
     */
    public long TOP_TO_ALMOST_PERCEPTIBLE_GRACE_DURATION =
            DEFAULT_TOP_TO_ALMOST_PERCEPTIBLE_GRACE_DURATION;

    // How long a process can remain at previous oom_adj before dropping to cached
    public static long MAX_PREVIOUS_TIME = DEFAULT_MAX_PREVIOUS_TIME;

    /**
     * The minimum time we allow between crashes, for us to consider this
     * application to be bad and stop its services and reject broadcasts.
     * A reasonable interval here would be anything between 1-3 minutes.
     */
    public static int MIN_CRASH_INTERVAL = DEFAULT_MIN_CRASH_INTERVAL;

    /**
     * We will allow for a maximum number of {@link PROCESS_CRASH_COUNT_LIMIT} crashes within this
     * time period before we consider the application to be bad and stop services and reject
     * broadcasts.
     * A reasonable reset interval here would be anything between 10-20 hours along with a crash
     * count limit of 10-20 crashes.
     */
    static long PROCESS_CRASH_COUNT_RESET_INTERVAL = DEFAULT_PROCESS_CRASH_COUNT_RESET_INTERVAL;

    /**
     * The maximum number of crashes allowed within {@link PROCESS_CRASH_COUNT_RESET_INTERVAL_MS}
     * before we consider the application to be bad and stop services and reject broadcasts.
     * A reasonable crash count limit here would be anything between 10-20 crashes along with a
     * reset interval of 10-20 hours.
     */
    static int PROCESS_CRASH_COUNT_LIMIT = DEFAULT_PROCESS_CRASH_COUNT_LIMIT;

    // Indicates whether the activity starts logging is enabled.
    // Controlled by Settings.Global.ACTIVITY_STARTS_LOGGING_ENABLED
    volatile boolean mFlagActivityStartsLoggingEnabled;

    // Indicates whether the background activity starts is enabled.
    // Controlled by Settings.Global.BACKGROUND_ACTIVITY_STARTS_ENABLED.
    // If not set explicitly the default is controlled by DeviceConfig.
    volatile boolean mFlagBackgroundActivityStartsEnabled;

    // Indicates whether foreground service starts logging is enabled.
    // Controlled by Settings.Global.FOREGROUND_SERVICE_STARTS_LOGGING_ENABLED
    volatile boolean mFlagForegroundServiceStartsLoggingEnabled;

    // Indicates whether the foreground service background start restriction is enabled.
    // When the restriction is enabled, foreground service started from background will not have
    // while-in-use permissions like location, camera and microphone. (The foreground service can be
    // started, the restriction is on while-in-use permissions.)
    volatile boolean mFlagBackgroundFgsStartRestrictionEnabled = true;

    // Indicates whether the foreground service background start restriction is enabled for
    // apps targeting S+.
    // When the restriction is enabled, service is not allowed to startForeground from background
    // at all.
    volatile boolean mFlagFgsStartRestrictionEnabled = true;

    // Whether to display a notification when a service is restricted from startForeground due to
    // foreground service background start restriction.
    volatile boolean mFgsStartRestrictionNotificationEnabled = false;

    // Indicates whether PSS profiling in AppProfiler is force-enabled, even if RSS is used by
    // default. Controlled by Settings.Global.FORCE_ENABLE_PSS_PROFILING
    volatile boolean mForceEnablePssProfiling = false;

    /**
     * Indicates whether the foreground service background start restriction is enabled for
     * caller app that is targeting S+.
     * This is in addition to check of {@link #mFlagFgsStartRestrictionEnabled} flag.
     */
    volatile boolean mFgsStartRestrictionCheckCallerTargetSdk = true;

    // Whether we defer FGS notifications a few seconds following their transition to
    // the foreground state.  Applies only to S+ apps; enabled by default.
    volatile boolean mFlagFgsNotificationDeferralEnabled = true;

    // Restrict FGS notification deferral policy to only those apps that target
    // API version S or higher.  Disabled by default; set to "true" to force
    // legacy app FGS notifications to display immediately in all cases.
    volatile boolean mFlagFgsNotificationDeferralApiGated = false;

    // Time in milliseconds to defer FGS notifications after their transition to
    // the foreground state.
    volatile long mFgsNotificationDeferralInterval = 10_000;

    /**
     * Same as {@link #mFgsNotificationDeferralInterval} but used for "short FGS".
     */
    volatile long mFgsNotificationDeferralIntervalForShort = mFgsNotificationDeferralInterval;

    // Rate limit: minimum time after an app's FGS notification is deferred
    // before another FGS notification from that app can be deferred.
    volatile long mFgsNotificationDeferralExclusionTime = 2 * 60 * 1000L;

    /**
     * Same as {@link #mFgsNotificationDeferralExclusionTime} but used for "short FGS".
     */
    volatile long mFgsNotificationDeferralExclusionTimeForShort =
            mFgsNotificationDeferralExclusionTime;

    // Indicates whether the system-applied exemption from all power restrictions is enabled.
    // When the exemption is enabled, any app which has the OP_SYSTEM_EXEMPT_FROM_POWER_RESTRICTIONS
    // app op will be exempt from all power-related restrictions, including app standby
    // and doze. In addition, the app will be able to start foreground services from the background,
    // and the user will not be able to stop foreground services run by the app.
    volatile boolean mFlagSystemExemptPowerRestrictionsEnabled = true;

    /**
     * When server pushing message is over the quote, select one of the temp allow list type as
     * defined in {@link PowerExemptionManager.TempAllowListType}
     */
    volatile @PowerExemptionManager.TempAllowListType int mPushMessagingOverQuotaBehavior =
            DEFAULT_PUSH_MESSAGING_OVER_QUOTA_BEHAVIOR;

    /*
     * At boot time, broadcast receiver ACTION_BOOT_COMPLETED, ACTION_LOCKED_BOOT_COMPLETED and
     * ACTION_PRE_BOOT_COMPLETED are temp allowlisted to start FGS for a duration of time in
     * milliseconds.
     */
    volatile long mBootTimeTempAllowlistDuration = DEFAULT_BOOT_TIME_TEMP_ALLOWLIST_DURATION;

    /**
     * The grace period in milliseconds to allow a process to start FGS from background after
     * switching from foreground to background; currently it's only applicable to its activities.
     */
    volatile long mFgToBgFgsGraceDuration = DEFAULT_FG_TO_BG_FGS_GRACE_DURATION;

    /**
     * The grace period in milliseconds to allow a process to schedule a
     * {@link android.app.job.JobInfo.Builder#setUserInitiated(boolean) user-initiated job}
     * after switching from visible to a non-visible state.
     * Currently it's only applicable to its activities.
     */
    volatile long mVisibleToInvisibleUijScheduleGraceDurationMs =
            DEFAULT_VISIBLE_TO_INVISIBLE_UIJ_SCHEDULE_GRACE_DURATION;

    /**
     * When service started from background, before the timeout it can be promoted to FGS by calling
     * Service.startForeground().
     */
    volatile long mFgsStartForegroundTimeoutMs = DEFAULT_FGS_START_FOREGROUND_TIMEOUT_MS;

    /**
     * Sample rate for the FGS atom.
     *
     * If the value is 0.1, 10% of the installed packages would be sampled.
     */
    volatile float mFgsAtomSampleRate = DEFAULT_FGS_ATOM_SAMPLE_RATE;

    /**
     * Sample rate for the allowed FGS start WTF logs.
     *
     * If the value is 0.1, 10% of the logs would be sampled.
     */
    volatile float mFgsStartAllowedLogSampleRate = DEFAULT_FGS_START_ALLOWED_LOG_SAMPLE_RATE;

    /**
     * Sample rate for the denied FGS start WTF logs.
     *
     * If the value is 0.1, 10% of the logs would be sampled.
     */
    volatile float mFgsStartDeniedLogSampleRate = DEFAULT_FGS_START_DENIED_LOG_SAMPLE_RATE;

    /**
     * Whether or not to kill apps in background restricted mode and it's cached, its UID state is
     * idle.
     */
    volatile boolean mKillBgRestrictedAndCachedIdle = DEFAULT_KILL_BG_RESTRICTED_CACHED_IDLE;

    /**
     * The amount of time we allow an app in background restricted mode to settle after it goes
     * into the cached &amp; UID idle, before we decide to kill it.
     */
    volatile long mKillBgRestrictedAndCachedIdleSettleTimeMs =
            DEFAULT_KILL_BG_RESTRICTED_CACHED_IDLE_SETTLE_TIME_MS;

    /**
     * The allowed duration from a process is killed until it's really gone.
     */
    volatile long mProcessKillTimeoutMs = DEFAULT_PROCESS_KILL_TIMEOUT_MS;

    /**
     * Whether to allow "opt-out" from the foreground service restrictions.
     * (https://developer.android.com/about/versions/12/foreground-services)
     */
    volatile boolean mFgsAllowOptOut = DEFAULT_FGS_ALLOW_OPT_OUT;

    /*
     * The extra delays we're putting to service restarts, based on current memory pressure.
     */
    @GuardedBy("mService")
    long[] mExtraServiceRestartDelayOnMemPressure =
            DEFAULT_EXTRA_SERVICE_RESTART_DELAY_ON_MEM_PRESSURE;

    /**
     * Whether or not to enable the extra delays to service restarts on memory pressure.
     */
    @GuardedBy("mService")
    boolean mEnableExtraServiceRestartDelayOnMemPressure =
            DEFAULT_ENABLE_EXTRA_SERVICE_RESTART_DELAY_ON_MEM_PRESSURE;

    /**
     * Whether to enable "component alias" experimental feature. This can only be enabled
     * on userdebug or eng builds.
     */
    volatile boolean mEnableComponentAlias = DEFAULT_ENABLE_COMPONENT_ALIAS;

    /**
     * Where or not to defer LOCKED_BOOT_COMPLETED and BOOT_COMPLETED broadcasts until the first
     * time the process of the UID is started.
     * Defined in {@link BroadcastConstants#DeferBootCompletedBroadcastType}
     */
    @GuardedBy("mService")
    volatile @BroadcastConstants.DeferBootCompletedBroadcastType int mDeferBootCompletedBroadcast =
            DEFAULT_DEFER_BOOT_COMPLETED_BROADCAST;

    /**
     * Whether alarm broadcasts are delivered immediately, or queued along with the rest
     * of the pending ordered broadcasts.
     */
    volatile boolean mPrioritizeAlarmBroadcasts = DEFAULT_PRIORITIZE_ALARM_BROADCASTS;

    /**
     * How long the Context.startForegroundService() grace period is to get around to
     * calling Service.startForeground() before we generate ANR.
     */
    volatile int mServiceStartForegroundTimeoutMs = DEFAULT_SERVICE_START_FOREGROUND_TIMEOUT_MS;

    /**
     *  How long from Service.startForeground() timed-out to when we generate ANR of the user app.
     *  This delay is after the timeout {@link #mServiceStartForegroundTimeoutMs}.
     */
    volatile int mServiceStartForegroundAnrDelayMs =
            DEFAULT_SERVICE_START_FOREGROUND_ANR_DELAY_MS;

    /**
     * How long the grace period is from starting an almost perceptible service to a successful
     * binding before we stop considering it an almost perceptible service.
     */
    volatile long mServiceBindAlmostPerceptibleTimeoutMs =
            DEFAULT_SERVICE_BIND_ALMOST_PERCEPTIBLE_TIMEOUT_MS;

    /**
     * Defines component aliases. Format
     * ComponentName ":" ComponentName ( "," ComponentName ":" ComponentName )*
     */
    volatile String mComponentAliasOverrides = DEFAULT_COMPONENT_ALIAS_OVERRIDES;

    /**
     *  The max number of outgoing ServiceConnection a process is allowed to bind to a service
     *  (or multiple services).
     */
    volatile int mMaxServiceConnectionsPerProcess = DEFAULT_MAX_SERVICE_CONNECTIONS_PER_PROCESS;

    private final ActivityManagerService mService;
    private ContentResolver mResolver;
    private final KeyValueListParser mParser = new KeyValueListParser(',');

    private int mOverrideMaxCachedProcesses = -1;
    private final int mCustomizedMaxCachedProcesses;

    // The maximum number of cached processes we will keep around before killing them.
    // NOTE: this constant is *only* a control to not let us go too crazy with
    // keeping around processes on devices with large amounts of RAM.  For devices that
    // are tighter on RAM, the out of memory killer is responsible for killing background
    // processes as RAM is needed, and we should *never* be relying on this limit to
    // kill them.  Also note that this limit only applies to cached background processes;
    // we have no limit on the number of service, visible, foreground, or other such
    // processes and the number of those processes does not count against the cached
    // process limit. This will be initialized in the constructor.
    public int CUR_MAX_CACHED_PROCESSES;

    // The maximum number of empty app processes we will let sit around.  This will be
    // initialized in the constructor.
    public int CUR_MAX_EMPTY_PROCESSES;

    /** @see #mNoKillCachedProcessesUntilBootCompleted */
    private static final String KEY_NO_KILL_CACHED_PROCESSES_UNTIL_BOOT_COMPLETED =
            "no_kill_cached_processes_until_boot_completed";

    /** @see #mNoKillCachedProcessesPostBootCompletedDurationMillis */
    private static final String KEY_NO_KILL_CACHED_PROCESSES_POST_BOOT_COMPLETED_DURATION_MILLIS =
            "no_kill_cached_processes_post_boot_completed_duration_millis";

    /** @see #mNoKillCachedProcessesUntilBootCompleted */
    private static final boolean DEFAULT_NO_KILL_CACHED_PROCESSES_UNTIL_BOOT_COMPLETED = true;

    /** @see #mNoKillCachedProcessesPostBootCompletedDurationMillis */
    private static final long
            DEFAULT_NO_KILL_CACHED_PROCESSES_POST_BOOT_COMPLETED_DURATION_MILLIS = 600_000;

    /**
     * If true, do not kill excessive cached processes proactively, until user-0 is unlocked.
     * @see #mNoKillCachedProcessesPostBootCompletedDurationMillis
     */
    volatile boolean mNoKillCachedProcessesUntilBootCompleted =
            DEFAULT_NO_KILL_CACHED_PROCESSES_UNTIL_BOOT_COMPLETED;

    /**
     * Do not kill excessive cached processes proactively, for this duration after each user is
     * unlocked.
     * Note we don't proactively kill extra cached processes after this. The next oomadjuster pass
     * will naturally do it.
     */
    volatile long mNoKillCachedProcessesPostBootCompletedDurationMillis =
            DEFAULT_NO_KILL_CACHED_PROCESSES_POST_BOOT_COMPLETED_DURATION_MILLIS;

    // The number of empty apps at which we don't consider it necessary to do
    // memory trimming.
    public int CUR_TRIM_EMPTY_PROCESSES = computeEmptyProcessLimit(MAX_CACHED_PROCESSES) / 2;

    // The number of cached at which we don't consider it necessary to do
    // memory trimming.
    public int CUR_TRIM_CACHED_PROCESSES =
            (MAX_CACHED_PROCESSES - computeEmptyProcessLimit(MAX_CACHED_PROCESSES)) / 3;

    /** @see #mNoKillCachedProcessesUntilBootCompleted */
    private static final String KEY_MAX_EMPTY_TIME_MILLIS =
            "max_empty_time_millis";

    private static final long DEFAULT_MAX_EMPTY_TIME_MILLIS = 1000L * 60L * 60L * 1000L;

    volatile long mMaxEmptyTimeMillis = DEFAULT_MAX_EMPTY_TIME_MILLIS;

    /**
     * Packages that can't be killed even if it's requested to be killed on imperceptible.
     */
    public ArraySet<String> IMPERCEPTIBLE_KILL_EXEMPT_PACKAGES = new ArraySet<String>();

    /**
     * Proc State that can't be killed even if it's requested to be killed on imperceptible.
     */
    public ArraySet<Integer> IMPERCEPTIBLE_KILL_EXEMPT_PROC_STATES = new ArraySet<Integer>();

    /**
     * The threshold for the amount of PendingIntent for each UID, there will be
     * warning logs if the number goes beyond this threshold.
     */
    public int PENDINGINTENT_WARNING_THRESHOLD =  DEFAULT_PENDINGINTENT_WARNING_THRESHOLD;

    /**
     * Component names of the services which will keep critical code path of the host warm
     */
    public final ArraySet<ComponentName> KEEP_WARMING_SERVICES = new ArraySet<ComponentName>();

    /**
     * Maximum number of phantom processes.
     */
    public int MAX_PHANTOM_PROCESSES = DEFAULT_MAX_PHANTOM_PROCESSES;

    private List<String> mDefaultImperceptibleKillExemptPackages;
    private List<Integer> mDefaultImperceptibleKillExemptProcStates;

    /**
     * Indicates the maximum time spent waiting for the network rules to get updated.
     */
    volatile long mNetworkAccessTimeoutMs = DEFAULT_NETWORK_ACCESS_TIMEOUT_MS;

    @SuppressWarnings("unused")
    private static final int OOMADJ_UPDATE_POLICY_SLOW = 0;
    private static final int OOMADJ_UPDATE_POLICY_QUICK = 1;
    private static final int DEFAULT_OOMADJ_UPDATE_POLICY = OOMADJ_UPDATE_POLICY_QUICK;

    private static final String KEY_OOMADJ_UPDATE_POLICY = "oomadj_update_policy";

    // Indicate if the oom adjuster should take the quick path to update the oom adj scores,
    // in which no futher actions will be performed if there are no significant adj/proc state
    // changes for the specific process; otherwise, use the traditonal slow path which would
    // keep updating all processes in the LRU list.
    public boolean OOMADJ_UPDATE_QUICK = DEFAULT_OOMADJ_UPDATE_POLICY == OOMADJ_UPDATE_POLICY_QUICK;

    private static final long MIN_AUTOMATIC_HEAP_DUMP_PSS_THRESHOLD_BYTES = 100 * 1024; // 100 KB

    private final boolean mSystemServerAutomaticHeapDumpEnabled;

    /** Package to report to when the memory usage exceeds the limit. */
    private final String mSystemServerAutomaticHeapDumpPackageName;

    /** Byte limit for dump heap monitoring. */
    private long mSystemServerAutomaticHeapDumpPssThresholdBytes;

    private static final Uri ACTIVITY_MANAGER_CONSTANTS_URI = Settings.Global.getUriFor(
                Settings.Global.ACTIVITY_MANAGER_CONSTANTS);

    private static final Uri ACTIVITY_STARTS_LOGGING_ENABLED_URI = Settings.Global.getUriFor(
                Settings.Global.ACTIVITY_STARTS_LOGGING_ENABLED);

    private static final Uri FOREGROUND_SERVICE_STARTS_LOGGING_ENABLED_URI =
                Settings.Global.getUriFor(
                        Settings.Global.FOREGROUND_SERVICE_STARTS_LOGGING_ENABLED);

    private static final Uri ENABLE_AUTOMATIC_SYSTEM_SERVER_HEAP_DUMPS_URI =
            Settings.Global.getUriFor(Settings.Global.ENABLE_AUTOMATIC_SYSTEM_SERVER_HEAP_DUMPS);

    private static final Uri FORCE_ENABLE_PSS_PROFILING_URI =
            Settings.Global.getUriFor(Settings.Global.FORCE_ENABLE_PSS_PROFILING);

    /**
     * The threshold to decide if a given association should be dumped into metrics.
     */
    private static final long DEFAULT_MIN_ASSOC_LOG_DURATION = 5 * 60 * 1000; // 5 mins

    private static final boolean DEFAULT_PROACTIVE_KILLS_ENABLED = false;

    private static final float DEFAULT_LOW_SWAP_THRESHOLD_PERCENT = 0.10f;

    private static final String KEY_MIN_ASSOC_LOG_DURATION = "min_assoc_log_duration";

    public static long MIN_ASSOC_LOG_DURATION = DEFAULT_MIN_ASSOC_LOG_DURATION;

    private static final String KEY_BINDER_HEAVY_HITTER_WATCHER_ENABLED =
            "binder_heavy_hitter_watcher_enabled";
    private static final String KEY_BINDER_HEAVY_HITTER_WATCHER_BATCHSIZE =
            "binder_heavy_hitter_watcher_batchsize";
    private static final String KEY_BINDER_HEAVY_HITTER_WATCHER_THRESHOLD =
            "binder_heavy_hitter_watcher_threshold";
    private static final String KEY_BINDER_HEAVY_HITTER_AUTO_SAMPLER_ENABLED =
            "binder_heavy_hitter_auto_sampler_enabled";
    private static final String KEY_BINDER_HEAVY_HITTER_AUTO_SAMPLER_BATCHSIZE =
            "binder_heavy_hitter_auto_sampler_batchsize";
    private static final String KEY_BINDER_HEAVY_HITTER_AUTO_SAMPLER_THRESHOLD =
            "binder_heavy_hitter_auto_sampler_threshold";

    private final boolean mDefaultBinderHeavyHitterWatcherEnabled;
    private final int mDefaultBinderHeavyHitterWatcherBatchSize;
    private final float mDefaultBinderHeavyHitterWatcherThreshold;
    private final boolean mDefaultBinderHeavyHitterAutoSamplerEnabled;
    private final int mDefaultBinderHeavyHitterAutoSamplerBatchSize;
    private final float mDefaultBinderHeavyHitterAutoSamplerThreshold;

    public static boolean BINDER_HEAVY_HITTER_WATCHER_ENABLED;
    public static int BINDER_HEAVY_HITTER_WATCHER_BATCHSIZE;
    public static float BINDER_HEAVY_HITTER_WATCHER_THRESHOLD;
    public static boolean BINDER_HEAVY_HITTER_AUTO_SAMPLER_ENABLED;
    public static int BINDER_HEAVY_HITTER_AUTO_SAMPLER_BATCHSIZE;
    public static float BINDER_HEAVY_HITTER_AUTO_SAMPLER_THRESHOLD;
    public static boolean PROACTIVE_KILLS_ENABLED = DEFAULT_PROACTIVE_KILLS_ENABLED;
    public static float LOW_SWAP_THRESHOLD_PERCENT = DEFAULT_LOW_SWAP_THRESHOLD_PERCENT;

    /** Timeout for a "short service" FGS, in milliseconds. */
    private static final String KEY_SHORT_FGS_TIMEOUT_DURATION =
            "short_fgs_timeout_duration";

    /** @see #KEY_SHORT_FGS_TIMEOUT_DURATION */
    static final long DEFAULT_SHORT_FGS_TIMEOUT_DURATION = 3 * 60_000;

    /** @see #KEY_SHORT_FGS_TIMEOUT_DURATION */
    public volatile long mShortFgsTimeoutDuration = DEFAULT_SHORT_FGS_TIMEOUT_DURATION;

    /**
     * If a "short service" doesn't finish within this after the timeout (
     * {@link #KEY_SHORT_FGS_TIMEOUT_DURATION}), then we'll lower the procstate.
     */
    private static final String KEY_SHORT_FGS_PROC_STATE_EXTRA_WAIT_DURATION =
            "short_fgs_proc_state_extra_wait_duration";

    /** @see #KEY_SHORT_FGS_PROC_STATE_EXTRA_WAIT_DURATION */
    static final long DEFAULT_SHORT_FGS_PROC_STATE_EXTRA_WAIT_DURATION = 5_000;

    /** @see #KEY_SHORT_FGS_PROC_STATE_EXTRA_WAIT_DURATION */
    public volatile long mShortFgsProcStateExtraWaitDuration =
            DEFAULT_SHORT_FGS_PROC_STATE_EXTRA_WAIT_DURATION;

    /** Timeout for a mediaProcessing FGS, in milliseconds. */
    private static final String KEY_MEDIA_PROCESSING_FGS_TIMEOUT_DURATION =
            "media_processing_fgs_timeout_duration";

    /** @see #KEY_MEDIA_PROCESSING_FGS_TIMEOUT_DURATION */
    static final long DEFAULT_MEDIA_PROCESSING_FGS_TIMEOUT_DURATION = 6 * 60 * 60_000; // 6 hours

    /** @see #KEY_MEDIA_PROCESSING_FGS_TIMEOUT_DURATION */
    public volatile long mMediaProcessingFgsTimeoutDuration =
            DEFAULT_MEDIA_PROCESSING_FGS_TIMEOUT_DURATION;

    /** Timeout for a dataSync FGS, in milliseconds. */
    private static final String KEY_DATA_SYNC_FGS_TIMEOUT_DURATION =
            "data_sync_fgs_timeout_duration";

    /** @see #KEY_DATA_SYNC_FGS_TIMEOUT_DURATION */
    static final long DEFAULT_DATA_SYNC_FGS_TIMEOUT_DURATION = 6 * 60 * 60_000; // 6 hours

    /** @see #KEY_DATA_SYNC_FGS_TIMEOUT_DURATION */
    public volatile long mDataSyncFgsTimeoutDuration = DEFAULT_DATA_SYNC_FGS_TIMEOUT_DURATION;

    /**
     * If enabled, when starting an application, the system will wait for a
     * {@link ActivityManagerService#finishAttachApplication} from the app before scheduling
     * Broadcasts or Services to it.
     */
    private static final String KEY_ENABLE_WAIT_FOR_FINISH_ATTACH_APPLICATION =
            "enable_wait_for_finish_attach_application";

    private static final boolean DEFAULT_ENABLE_WAIT_FOR_FINISH_ATTACH_APPLICATION = true;

    /** @see #KEY_ENABLE_WAIT_FOR_FINISH_ATTACH_APPLICATION */
    public volatile boolean mEnableWaitForFinishAttachApplication =
            DEFAULT_ENABLE_WAIT_FOR_FINISH_ATTACH_APPLICATION;

    /**
     * If a "short service" doesn't finish within this after the timeout (
     * {@link #KEY_SHORT_FGS_TIMEOUT_DURATION}), then we'll declare an ANR.
     * i.e. if the timeout is 60 seconds, and this ANR extra duration is 5 seconds, then
     * the app will be ANR'ed in 65 seconds after a short service starts and it's not stopped.
     */
    private static final String KEY_SHORT_FGS_ANR_EXTRA_WAIT_DURATION =
            "short_fgs_anr_extra_wait_duration";

    /** @see #KEY_SHORT_FGS_ANR_EXTRA_WAIT_DURATION */
    static final long DEFAULT_SHORT_FGS_ANR_EXTRA_WAIT_DURATION = 10_000;

    /** @see #KEY_SHORT_FGS_ANR_EXTRA_WAIT_DURATION */
    public volatile long mShortFgsAnrExtraWaitDuration =
            DEFAULT_SHORT_FGS_ANR_EXTRA_WAIT_DURATION;

    /**
     * If a service of a timeout-enforced type doesn't finish within this duration after its
     * timeout, then we'll crash the app.
     * i.e. if the time limit for a type is 1 hour, and this extra duration is 10 seconds, then
     * the app will crash 1 hour and 10 seconds after it started.
     */
    private static final String KEY_FGS_CRASH_EXTRA_WAIT_DURATION = "fgs_crash_extra_wait_duration";

    /** @see #KEY_FGS_CRASH_EXTRA_WAIT_DURATION */
    static final long DEFAULT_FGS_CRASH_EXTRA_WAIT_DURATION = 10_000;

    /** @see #KEY_FGS_CRASH_EXTRA_WAIT_DURATION */
    public volatile long mFgsCrashExtraWaitDuration = DEFAULT_FGS_CRASH_EXTRA_WAIT_DURATION;

    /** @see #KEY_USE_TIERED_CACHED_ADJ */
    public boolean USE_TIERED_CACHED_ADJ = DEFAULT_USE_TIERED_CACHED_ADJ;

    /** @see #KEY_TIERED_CACHED_ADJ_DECAY_TIME */
    public long TIERED_CACHED_ADJ_DECAY_TIME = DEFAULT_TIERED_CACHED_ADJ_DECAY_TIME;

    /** @see #KEY_ENABLE_NEW_OOMADJ */
    public boolean ENABLE_NEW_OOMADJ = DEFAULT_ENABLE_NEW_OOM_ADJ;

    /**
     * Indicates whether PSS profiling in AppProfiler is disabled or not.
     */
    static final String KEY_DISABLE_APP_PROFILER_PSS_PROFILING =
            "disable_app_profiler_pss_profiling";

    private final boolean mDefaultDisableAppProfilerPssProfiling;

    public boolean APP_PROFILER_PSS_PROFILING_DISABLED;

    /**
     * The modifier used to adjust PSS thresholds in OomAdjuster when RSS is collected instead.
     */
    static final String KEY_PSS_TO_RSS_THRESHOLD_MODIFIER =
            "pss_to_rss_threshold_modifier";

    private final float mDefaultPssToRssThresholdModifier;

    public float PSS_TO_RSS_THRESHOLD_MODIFIER;

    private final OnPropertiesChangedListener mOnDeviceConfigChangedListener =
            new OnPropertiesChangedListener() {
                @Override
                public void onPropertiesChanged(Properties properties) {
                    for (String name : properties.getKeyset()) {
                        if (name == null) {
                            return;
                        }
                        switch (name) {
                            case KEY_MAX_CACHED_PROCESSES:
                                updateMaxCachedProcesses();
                                break;
                            case KEY_DEFAULT_BACKGROUND_ACTIVITY_STARTS_ENABLED:
                                updateBackgroundActivityStarts();
                                break;
                            case KEY_DEFAULT_BACKGROUND_FGS_STARTS_RESTRICTION_ENABLED:
                                updateBackgroundFgsStartsRestriction();
                                break;
                            case KEY_DEFAULT_FGS_STARTS_RESTRICTION_ENABLED:
                                updateFgsStartsRestriction();
                                break;
                            case KEY_DEFAULT_FGS_STARTS_RESTRICTION_NOTIFICATION_ENABLED:
                                updateFgsStartsRestrictionNotification();
                                break;
                            case KEY_DEFAULT_FGS_STARTS_RESTRICTION_CHECK_CALLER_TARGET_SDK:
                                updateFgsStartsRestrictionCheckCallerTargetSdk();
                                break;
                            case KEY_DEFERRED_FGS_NOTIFICATIONS_ENABLED:
                                updateFgsNotificationDeferralEnable();
                                break;
                            case KEY_DEFERRED_FGS_NOTIFICATIONS_API_GATED:
                                updateFgsNotificationDeferralApiGated();
                                break;
                            case KEY_DEFERRED_FGS_NOTIFICATION_INTERVAL:
                                updateFgsNotificationDeferralInterval();
                                break;
                            case KEY_DEFERRED_FGS_NOTIFICATION_EXCLUSION_TIME:
                                updateFgsNotificationDeferralExclusionTime();
                                break;
                            case KEY_DEFERRED_FGS_NOTIFICATION_INTERVAL_FOR_SHORT:
                                updateFgsNotificationDeferralIntervalForShort();
                                break;
                            case KEY_DEFERRED_FGS_NOTIFICATION_EXCLUSION_TIME_FOR_SHORT:
                                updateFgsNotificationDeferralExclusionTimeForShort();
                                break;
                            case KEY_SYSTEM_EXEMPT_POWER_RESTRICTIONS_ENABLED:
                                updateSystemExemptPowerRestrictionsEnabled();
                                break;
                            case KEY_PUSH_MESSAGING_OVER_QUOTA_BEHAVIOR:
                                updatePushMessagingOverQuotaBehavior();
                                break;
                            case KEY_OOMADJ_UPDATE_POLICY:
                                updateOomAdjUpdatePolicy();
                                break;
                            case KEY_IMPERCEPTIBLE_KILL_EXEMPT_PACKAGES:
                            case KEY_IMPERCEPTIBLE_KILL_EXEMPT_PROC_STATES:
                                updateImperceptibleKillExemptions();
                                break;
                            case KEY_FORCE_BACKGROUND_CHECK_ON_RESTRICTED_APPS:
                                updateForceRestrictedBackgroundCheck();
                                break;
                            case KEY_MIN_ASSOC_LOG_DURATION:
                                updateMinAssocLogDuration();
                                break;
                            case KEY_BINDER_HEAVY_HITTER_WATCHER_ENABLED:
                            case KEY_BINDER_HEAVY_HITTER_WATCHER_BATCHSIZE:
                            case KEY_BINDER_HEAVY_HITTER_WATCHER_THRESHOLD:
                            case KEY_BINDER_HEAVY_HITTER_AUTO_SAMPLER_ENABLED:
                            case KEY_BINDER_HEAVY_HITTER_AUTO_SAMPLER_BATCHSIZE:
                            case KEY_BINDER_HEAVY_HITTER_AUTO_SAMPLER_THRESHOLD:
                                updateBinderHeavyHitterWatcher();
                                break;
                            case KEY_MAX_PHANTOM_PROCESSES:
                                updateMaxPhantomProcesses();
                                break;
                            case KEY_BOOT_TIME_TEMP_ALLOWLIST_DURATION:
                                updateBootTimeTempAllowListDuration();
                                break;
                            case KEY_FG_TO_BG_FGS_GRACE_DURATION:
                                updateFgToBgFgsGraceDuration();
                                break;
                            case KEY_VISIBLE_TO_INVISIBLE_UIJ_SCHEDULE_GRACE_DURATION:
                                updateFgToBgFgsGraceDuration();
                                break;
                            case KEY_FGS_START_FOREGROUND_TIMEOUT:
                                updateFgsStartForegroundTimeout();
                                break;
                            case KEY_FGS_ATOM_SAMPLE_RATE:
                                updateFgsAtomSamplePercent();
                                break;
                            case KEY_FGS_START_ALLOWED_LOG_SAMPLE_RATE:
                                updateFgsStartAllowedLogSamplePercent();
                                break;
                            case KEY_FGS_START_DENIED_LOG_SAMPLE_RATE:
                                updateFgsStartDeniedLogSamplePercent();
                                break;
                            case KEY_KILL_BG_RESTRICTED_CACHED_IDLE:
                                updateKillBgRestrictedCachedIdle();
                                break;
                            case KEY_KILL_BG_RESTRICTED_CACHED_IDLE_SETTLE_TIME:
                                updateKillBgRestrictedCachedIdleSettleTime();
                                break;
                            case KEY_FGS_ALLOW_OPT_OUT:
                                updateFgsAllowOptOut();
                                break;
                            case KEY_EXTRA_SERVICE_RESTART_DELAY_ON_MEM_PRESSURE:
                                updateExtraServiceRestartDelayOnMemPressure();
                                break;
                            case KEY_ENABLE_EXTRA_SERVICE_RESTART_DELAY_ON_MEM_PRESSURE:
                                updateEnableExtraServiceRestartDelayOnMemPressure();
                                break;
                            case KEY_PROCESS_KILL_TIMEOUT:
                                updateProcessKillTimeout();
                                break;
                            case KEY_PRIORITIZE_ALARM_BROADCASTS:
                                updatePrioritizeAlarmBroadcasts();
                                break;
                            case KEY_DEFER_BOOT_COMPLETED_BROADCAST:
                                updateDeferBootCompletedBroadcast();
                                break;
                            case KEY_SERVICE_START_FOREGROUND_TIMEOUT_MS:
                                updateServiceStartForegroundTimeoutMs();
                                break;
                            case KEY_SERVICE_START_FOREGROUND_ANR_DELAY_MS:
                                updateServiceStartForegroundAnrDealyMs();
                                break;
                            case KEY_SERVICE_BIND_ALMOST_PERCEPTIBLE_TIMEOUT_MS:
                                updateServiceBindAlmostPerceptibleTimeoutMs();
                                break;
                            case KEY_NO_KILL_CACHED_PROCESSES_UNTIL_BOOT_COMPLETED:
                                updateNoKillCachedProcessesUntilBootCompleted();
                                break;
                            case KEY_NO_KILL_CACHED_PROCESSES_POST_BOOT_COMPLETED_DURATION_MILLIS:
                                updateNoKillCachedProcessesPostBootCompletedDurationMillis();
                                break;
                            case KEY_MAX_EMPTY_TIME_MILLIS:
                                updateMaxEmptyTimeMillis();
                                break;
                            case KEY_NETWORK_ACCESS_TIMEOUT_MS:
                                updateNetworkAccessTimeoutMs();
                                break;
                            case KEY_MAX_SERVICE_CONNECTIONS_PER_PROCESS:
                                updateMaxServiceConnectionsPerProcess();
                                break;
                            case KEY_SHORT_FGS_TIMEOUT_DURATION:
                                updateShortFgsTimeoutDuration();
                                break;
                            case KEY_SHORT_FGS_PROC_STATE_EXTRA_WAIT_DURATION:
                                updateShortFgsProcStateExtraWaitDuration();
                                break;
                            case KEY_MEDIA_PROCESSING_FGS_TIMEOUT_DURATION:
                                updateMediaProcessingFgsTimeoutDuration();
                                break;
                            case KEY_DATA_SYNC_FGS_TIMEOUT_DURATION:
                                updateDataSyncFgsTimeoutDuration();
                                break;
                            case KEY_SHORT_FGS_ANR_EXTRA_WAIT_DURATION:
                                updateShortFgsAnrExtraWaitDuration();
                                break;
                            case KEY_FGS_CRASH_EXTRA_WAIT_DURATION:
                                updateFgsCrashExtraWaitDuration();
                                break;
                            case KEY_PROACTIVE_KILLS_ENABLED:
                                updateProactiveKillsEnabled();
                                break;
                            case KEY_LOW_SWAP_THRESHOLD_PERCENT:
                                updateLowSwapThresholdPercent();
                                break;
                            case KEY_TOP_TO_FGS_GRACE_DURATION:
                                updateTopToFgsGraceDuration();
                                break;
                            case KEY_ENABLE_WAIT_FOR_FINISH_ATTACH_APPLICATION:
                                updateEnableWaitForFinishAttachApplication();
                                break;
                            case KEY_MAX_PREVIOUS_TIME:
                                updateMaxPreviousTime();
                                break;
                            case KEY_USE_TIERED_CACHED_ADJ:
                            case KEY_TIERED_CACHED_ADJ_DECAY_TIME:
                                updateUseTieredCachedAdj();
                                break;
                            case KEY_DISABLE_APP_PROFILER_PSS_PROFILING:
                                updateDisableAppProfilerPssProfiling();
                                break;
                            case KEY_PSS_TO_RSS_THRESHOLD_MODIFIER:
                                updatePssToRssThresholdModifier();
                                break;
                            case KEY_PROC_STATE_DEBUG_UIDS:
                                updateProcStateDebugUids();
                                break;
                            default:
                                updateFGSPermissionEnforcementFlagsIfNecessary(name);
                                break;
                        }
                    }
                }
            };

    private final OnPropertiesChangedListener mOnDeviceConfigChangedForComponentAliasListener =
            new OnPropertiesChangedListener() {
                @Override
                public void onPropertiesChanged(Properties properties) {
                    for (String name : properties.getKeyset()) {
                        if (name == null) {
                            return;
                        }
                        switch (name) {
                            case KEY_ENABLE_COMPONENT_ALIAS:
                            case KEY_COMPONENT_ALIAS_OVERRIDES:
                                updateComponentAliases();
                                break;
                            default:
                                break;
                        }
                    }
                }
            };

    ActivityManagerConstants(Context context, ActivityManagerService service, Handler handler) {
        super(handler);
        mService = service;
        mSystemServerAutomaticHeapDumpEnabled = Build.IS_DEBUGGABLE
                && context.getResources().getBoolean(
                com.android.internal.R.bool.config_debugEnableAutomaticSystemServerHeapDumps);
        mSystemServerAutomaticHeapDumpPackageName = context.getPackageName();
        mSystemServerAutomaticHeapDumpPssThresholdBytes = Math.max(
                MIN_AUTOMATIC_HEAP_DUMP_PSS_THRESHOLD_BYTES,
                context.getResources().getInteger(
                        com.android.internal.R.integer.config_debugSystemServerPssThresholdBytes));
        mDefaultImperceptibleKillExemptPackages = Arrays.asList(
                context.getResources().getStringArray(
                com.android.internal.R.array.config_defaultImperceptibleKillingExemptionPkgs));
        mDefaultImperceptibleKillExemptProcStates = Arrays.stream(
                context.getResources().getIntArray(
                com.android.internal.R.array.config_defaultImperceptibleKillingExemptionProcStates))
                .boxed().collect(Collectors.toList());
        IMPERCEPTIBLE_KILL_EXEMPT_PACKAGES.addAll(mDefaultImperceptibleKillExemptPackages);
        IMPERCEPTIBLE_KILL_EXEMPT_PROC_STATES.addAll(mDefaultImperceptibleKillExemptProcStates);
        mDefaultBinderHeavyHitterWatcherEnabled = context.getResources().getBoolean(
                com.android.internal.R.bool.config_defaultBinderHeavyHitterWatcherEnabled);
        mDefaultBinderHeavyHitterWatcherBatchSize = context.getResources().getInteger(
                com.android.internal.R.integer.config_defaultBinderHeavyHitterWatcherBatchSize);
        mDefaultBinderHeavyHitterWatcherThreshold = context.getResources().getFloat(
                com.android.internal.R.dimen.config_defaultBinderHeavyHitterWatcherThreshold);
        mDefaultBinderHeavyHitterAutoSamplerEnabled = context.getResources().getBoolean(
                com.android.internal.R.bool.config_defaultBinderHeavyHitterAutoSamplerEnabled);
        mDefaultBinderHeavyHitterAutoSamplerBatchSize = context.getResources().getInteger(
                com.android.internal.R.integer.config_defaultBinderHeavyHitterAutoSamplerBatchSize);
        mDefaultBinderHeavyHitterAutoSamplerThreshold = context.getResources().getFloat(
                com.android.internal.R.dimen.config_defaultBinderHeavyHitterAutoSamplerThreshold);
        BINDER_HEAVY_HITTER_WATCHER_ENABLED = mDefaultBinderHeavyHitterWatcherEnabled;
        BINDER_HEAVY_HITTER_WATCHER_BATCHSIZE = mDefaultBinderHeavyHitterWatcherBatchSize;
        BINDER_HEAVY_HITTER_WATCHER_THRESHOLD = mDefaultBinderHeavyHitterWatcherThreshold;
        BINDER_HEAVY_HITTER_AUTO_SAMPLER_ENABLED = mDefaultBinderHeavyHitterAutoSamplerEnabled;
        BINDER_HEAVY_HITTER_AUTO_SAMPLER_BATCHSIZE = mDefaultBinderHeavyHitterAutoSamplerBatchSize;
        BINDER_HEAVY_HITTER_AUTO_SAMPLER_THRESHOLD = mDefaultBinderHeavyHitterAutoSamplerThreshold;
        service.scheduleUpdateBinderHeavyHitterWatcherConfig();
        KEEP_WARMING_SERVICES.addAll(Arrays.stream(
                context.getResources().getStringArray(
                        com.android.internal.R.array.config_keep_warming_services))
                .map(ComponentName::unflattenFromString).collect(Collectors.toSet()));
        mCustomizedMaxCachedProcesses = context.getResources().getInteger(
                com.android.internal.R.integer.config_customizedMaxCachedProcesses);
        CUR_MAX_CACHED_PROCESSES = mCustomizedMaxCachedProcesses;
        CUR_MAX_EMPTY_PROCESSES = computeEmptyProcessLimit(CUR_MAX_CACHED_PROCESSES);

        final int rawMaxEmptyProcesses = computeEmptyProcessLimit(
                Integer.min(CUR_MAX_CACHED_PROCESSES, MAX_CACHED_PROCESSES));
        CUR_TRIM_EMPTY_PROCESSES = rawMaxEmptyProcesses / 2;
        CUR_TRIM_CACHED_PROCESSES = (Integer.min(CUR_MAX_CACHED_PROCESSES, MAX_CACHED_PROCESSES)
                    - rawMaxEmptyProcesses) / 3;
        loadNativeBootDeviceConfigConstants();
        mDefaultDisableAppProfilerPssProfiling = context.getResources().getBoolean(
                R.bool.config_am_disablePssProfiling);
        APP_PROFILER_PSS_PROFILING_DISABLED = mDefaultDisableAppProfilerPssProfiling;

        mDefaultPssToRssThresholdModifier = context.getResources().getFloat(
                com.android.internal.R.dimen.config_am_pssToRssThresholdModifier);
        PSS_TO_RSS_THRESHOLD_MODIFIER = mDefaultPssToRssThresholdModifier;
    }

    public void start(ContentResolver resolver) {
        mResolver = resolver;
        mResolver.registerContentObserver(ACTIVITY_MANAGER_CONSTANTS_URI, false, this);
        mResolver.registerContentObserver(ACTIVITY_STARTS_LOGGING_ENABLED_URI, false, this);
        mResolver.registerContentObserver(FOREGROUND_SERVICE_STARTS_LOGGING_ENABLED_URI,
                false, this);
        if (mSystemServerAutomaticHeapDumpEnabled) {
            mResolver.registerContentObserver(ENABLE_AUTOMATIC_SYSTEM_SERVER_HEAP_DUMPS_URI,
                    false, this);
        }
        mResolver.registerContentObserver(FORCE_ENABLE_PSS_PROFILING_URI, false, this);
        updateConstants();
        if (mSystemServerAutomaticHeapDumpEnabled) {
            updateEnableAutomaticSystemServerHeapDumps();
        }
        DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                ActivityThread.currentApplication().getMainExecutor(),
                mOnDeviceConfigChangedListener);
        DeviceConfig.addOnPropertiesChangedListener(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER_COMPONENT_ALIAS,
                ActivityThread.currentApplication().getMainExecutor(),
                mOnDeviceConfigChangedForComponentAliasListener);
        loadDeviceConfigConstants();
        // The following read from Settings.
        updateActivityStartsLoggingEnabled();
        updateForegroundServiceStartsLoggingEnabled();
        updateForceEnablePssProfiling();
        // Read DropboxRateLimiter params from flags.
        mService.initDropboxRateLimiter();
    }

    void loadDeviceConfigConstants() {
        mOnDeviceConfigChangedListener.onPropertiesChanged(
                DeviceConfig.getProperties(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER));
        mOnDeviceConfigChangedForComponentAliasListener.onPropertiesChanged(
                DeviceConfig.getProperties(
                        DeviceConfig.NAMESPACE_ACTIVITY_MANAGER_COMPONENT_ALIAS));
    }

    private void loadNativeBootDeviceConfigConstants() {
        ENABLE_NEW_OOMADJ = getDeviceConfigBoolean(KEY_ENABLE_NEW_OOMADJ,
                DEFAULT_ENABLE_NEW_OOM_ADJ);
    }

    public void setOverrideMaxCachedProcesses(int value) {
        mOverrideMaxCachedProcesses = value;
        updateMaxCachedProcesses();
    }

    public int getOverrideMaxCachedProcesses() {
        return mOverrideMaxCachedProcesses;
    }

    public static int computeEmptyProcessLimit(int totalProcessLimit) {
        return totalProcessLimit/2;
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
        if (uri == null) return;
        if (ACTIVITY_MANAGER_CONSTANTS_URI.equals(uri)) {
            updateConstants();
        } else if (ACTIVITY_STARTS_LOGGING_ENABLED_URI.equals(uri)) {
            updateActivityStartsLoggingEnabled();
        } else if (FOREGROUND_SERVICE_STARTS_LOGGING_ENABLED_URI.equals(uri)) {
            updateForegroundServiceStartsLoggingEnabled();
        } else if (ENABLE_AUTOMATIC_SYSTEM_SERVER_HEAP_DUMPS_URI.equals(uri)) {
            updateEnableAutomaticSystemServerHeapDumps();
        } else if (FORCE_ENABLE_PSS_PROFILING_URI.equals(uri)) {
            updateForceEnablePssProfiling();
        }
    }

    private void updateConstants() {
        final String setting = Settings.Global.getString(mResolver,
                Settings.Global.ACTIVITY_MANAGER_CONSTANTS);
        synchronized (mService) {
            try {
                mParser.setString(setting);
            } catch (IllegalArgumentException e) {
                // Failed to parse the settings string, log this and move on
                // with defaults.
                Slog.e("ActivityManagerConstants", "Bad activity manager config settings", e);
            }
            final long currentPowerCheckInterval = POWER_CHECK_INTERVAL;

            BACKGROUND_SETTLE_TIME = mParser.getLong(KEY_BACKGROUND_SETTLE_TIME,
                    DEFAULT_BACKGROUND_SETTLE_TIME);
            FGSERVICE_MIN_SHOWN_TIME = mParser.getLong(KEY_FGSERVICE_MIN_SHOWN_TIME,
                    DEFAULT_FGSERVICE_MIN_SHOWN_TIME);
            FGSERVICE_MIN_REPORT_TIME = mParser.getLong(KEY_FGSERVICE_MIN_REPORT_TIME,
                    DEFAULT_FGSERVICE_MIN_REPORT_TIME);
            FGSERVICE_SCREEN_ON_BEFORE_TIME = mParser.getLong(KEY_FGSERVICE_SCREEN_ON_BEFORE_TIME,
                    DEFAULT_FGSERVICE_SCREEN_ON_BEFORE_TIME);
            FGSERVICE_SCREEN_ON_AFTER_TIME = mParser.getLong(KEY_FGSERVICE_SCREEN_ON_AFTER_TIME,
                    DEFAULT_FGSERVICE_SCREEN_ON_AFTER_TIME);
            FGS_BOOT_COMPLETED_ALLOWLIST = mParser.getInt(KEY_FGS_BOOT_COMPLETED_ALLOWLIST,
                    DEFAULT_FGS_BOOT_COMPLETED_ALLOWLIST);
            CONTENT_PROVIDER_RETAIN_TIME = mParser.getLong(KEY_CONTENT_PROVIDER_RETAIN_TIME,
                    DEFAULT_CONTENT_PROVIDER_RETAIN_TIME);
            GC_TIMEOUT = mParser.getLong(KEY_GC_TIMEOUT,
                    DEFAULT_GC_TIMEOUT);
            GC_MIN_INTERVAL = mParser.getLong(KEY_GC_MIN_INTERVAL,
                    DEFAULT_GC_MIN_INTERVAL);
            FULL_PSS_MIN_INTERVAL = mParser.getLong(KEY_FULL_PSS_MIN_INTERVAL,
                    DEFAULT_FULL_PSS_MIN_INTERVAL);
            FULL_PSS_LOWERED_INTERVAL = mParser.getLong(KEY_FULL_PSS_LOWERED_INTERVAL,
                    DEFAULT_FULL_PSS_LOWERED_INTERVAL);
            POWER_CHECK_INTERVAL = mParser.getLong(KEY_POWER_CHECK_INTERVAL,
                    DEFAULT_POWER_CHECK_INTERVAL);
            POWER_CHECK_MAX_CPU_1 = mParser.getInt(KEY_POWER_CHECK_MAX_CPU_1,
                    DEFAULT_POWER_CHECK_MAX_CPU_1);
            POWER_CHECK_MAX_CPU_2 = mParser.getInt(KEY_POWER_CHECK_MAX_CPU_2,
                    DEFAULT_POWER_CHECK_MAX_CPU_2);
            POWER_CHECK_MAX_CPU_3 = mParser.getInt(KEY_POWER_CHECK_MAX_CPU_3,
                    DEFAULT_POWER_CHECK_MAX_CPU_3);
            POWER_CHECK_MAX_CPU_4 = mParser.getInt(KEY_POWER_CHECK_MAX_CPU_4,
                    DEFAULT_POWER_CHECK_MAX_CPU_4);
            SERVICE_USAGE_INTERACTION_TIME_PRE_S = mParser.getLong(
                    KEY_SERVICE_USAGE_INTERACTION_TIME_PRE_S,
                    DEFAULT_SERVICE_USAGE_INTERACTION_TIME_PRE_S);
            SERVICE_USAGE_INTERACTION_TIME_POST_S = mParser.getLong(
                    KEY_SERVICE_USAGE_INTERACTION_TIME_POST_S,
                    DEFAULT_SERVICE_USAGE_INTERACTION_TIME_POST_S);
            USAGE_STATS_INTERACTION_INTERVAL_PRE_S = mParser.getLong(
                    KEY_USAGE_STATS_INTERACTION_INTERVAL_PRE_S,
                    DEFAULT_USAGE_STATS_INTERACTION_INTERVAL_PRE_S);
            USAGE_STATS_INTERACTION_INTERVAL_POST_S = mParser.getLong(
                    KEY_USAGE_STATS_INTERACTION_INTERVAL_POST_S,
                    DEFAULT_USAGE_STATS_INTERACTION_INTERVAL_POST_S);
            SERVICE_RESTART_DURATION = mParser.getLong(KEY_SERVICE_RESTART_DURATION,
                    DEFAULT_SERVICE_RESTART_DURATION);
            SERVICE_RESET_RUN_DURATION = mParser.getLong(KEY_SERVICE_RESET_RUN_DURATION,
                    DEFAULT_SERVICE_RESET_RUN_DURATION);
            SERVICE_RESTART_DURATION_FACTOR = mParser.getInt(KEY_SERVICE_RESTART_DURATION_FACTOR,
                    DEFAULT_SERVICE_RESTART_DURATION_FACTOR);
            SERVICE_MIN_RESTART_TIME_BETWEEN = mParser.getLong(KEY_SERVICE_MIN_RESTART_TIME_BETWEEN,
                    DEFAULT_SERVICE_MIN_RESTART_TIME_BETWEEN);
            MAX_SERVICE_INACTIVITY = mParser.getLong(KEY_MAX_SERVICE_INACTIVITY,
                    DEFAULT_MAX_SERVICE_INACTIVITY);
            BG_START_TIMEOUT = mParser.getLong(KEY_BG_START_TIMEOUT,
                    DEFAULT_BG_START_TIMEOUT);
            SERVICE_BG_ACTIVITY_START_TIMEOUT = mParser.getLong(
                    KEY_SERVICE_BG_ACTIVITY_START_TIMEOUT,
                    DEFAULT_SERVICE_BG_ACTIVITY_START_TIMEOUT);
            BOUND_SERVICE_CRASH_RESTART_DURATION = mParser.getLong(
                KEY_BOUND_SERVICE_CRASH_RESTART_DURATION,
                DEFAULT_BOUND_SERVICE_CRASH_RESTART_DURATION);
            BOUND_SERVICE_MAX_CRASH_RETRY = mParser.getInt(KEY_BOUND_SERVICE_CRASH_MAX_RETRY,
                DEFAULT_BOUND_SERVICE_CRASH_MAX_RETRY);
            FLAG_PROCESS_START_ASYNC = mParser.getBoolean(KEY_PROCESS_START_ASYNC,
                    DEFAULT_PROCESS_START_ASYNC);
            MEMORY_INFO_THROTTLE_TIME = mParser.getLong(KEY_MEMORY_INFO_THROTTLE_TIME,
                    DEFAULT_MEMORY_INFO_THROTTLE_TIME);
            TOP_TO_ALMOST_PERCEPTIBLE_GRACE_DURATION = mParser.getDurationMillis(
                    KEY_TOP_TO_ALMOST_PERCEPTIBLE_GRACE_DURATION,
                    DEFAULT_TOP_TO_ALMOST_PERCEPTIBLE_GRACE_DURATION);
            MIN_CRASH_INTERVAL = mParser.getInt(KEY_MIN_CRASH_INTERVAL,
                    DEFAULT_MIN_CRASH_INTERVAL);
            PENDINGINTENT_WARNING_THRESHOLD = mParser.getInt(KEY_PENDINGINTENT_WARNING_THRESHOLD,
                    DEFAULT_PENDINGINTENT_WARNING_THRESHOLD);
            PROCESS_CRASH_COUNT_RESET_INTERVAL = mParser.getInt(
                    KEY_PROCESS_CRASH_COUNT_RESET_INTERVAL,
                    DEFAULT_PROCESS_CRASH_COUNT_RESET_INTERVAL);
            PROCESS_CRASH_COUNT_LIMIT = mParser.getInt(KEY_PROCESS_CRASH_COUNT_LIMIT,
                    DEFAULT_PROCESS_CRASH_COUNT_LIMIT);

            if (POWER_CHECK_INTERVAL != currentPowerCheckInterval) {
                mService.mHandler.removeMessages(
                        ActivityManagerService.CHECK_EXCESSIVE_POWER_USE_MSG);
                final Message msg = mService.mHandler.obtainMessage(
                        ActivityManagerService.CHECK_EXCESSIVE_POWER_USE_MSG);
                mService.mHandler.sendMessageDelayed(msg, POWER_CHECK_INTERVAL);
            }
            // For new flags that are intended for server-side experiments, please use the new
            // DeviceConfig package.
        }
    }

    private void updateActivityStartsLoggingEnabled() {
        mFlagActivityStartsLoggingEnabled = Settings.Global.getInt(mResolver,
                Settings.Global.ACTIVITY_STARTS_LOGGING_ENABLED, 1) == 1;
    }

    private void updateForceEnablePssProfiling() {
        mForceEnablePssProfiling = Settings.Global.getInt(mResolver,
                Settings.Global.FORCE_ENABLE_PSS_PROFILING, 0) == 1;
    }

    private void updateBackgroundActivityStarts() {
        mFlagBackgroundActivityStartsEnabled = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_DEFAULT_BACKGROUND_ACTIVITY_STARTS_ENABLED,
                /*defaultValue*/ false);
    }

    private void updateForegroundServiceStartsLoggingEnabled() {
        mFlagForegroundServiceStartsLoggingEnabled = Settings.Global.getInt(mResolver,
                Settings.Global.FOREGROUND_SERVICE_STARTS_LOGGING_ENABLED, 1) == 1;
    }

    private void updateBackgroundFgsStartsRestriction() {
        mFlagBackgroundFgsStartRestrictionEnabled = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_DEFAULT_BACKGROUND_FGS_STARTS_RESTRICTION_ENABLED,
                /*defaultValue*/ true);
    }

    private void updateFgsStartsRestriction() {
        mFlagFgsStartRestrictionEnabled = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_DEFAULT_FGS_STARTS_RESTRICTION_ENABLED,
                /*defaultValue*/ true);
    }

    private void updateFgsStartsRestrictionNotification() {
        mFgsStartRestrictionNotificationEnabled = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_DEFAULT_FGS_STARTS_RESTRICTION_NOTIFICATION_ENABLED,
                /*defaultValue*/ false);
    }

    private void updateFgsStartsRestrictionCheckCallerTargetSdk() {
        mFgsStartRestrictionCheckCallerTargetSdk = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_DEFAULT_FGS_STARTS_RESTRICTION_CHECK_CALLER_TARGET_SDK,
                /*defaultValue*/ true);
    }

    private void updateFgsNotificationDeferralEnable() {
        mFlagFgsNotificationDeferralEnabled = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_DEFERRED_FGS_NOTIFICATIONS_ENABLED,
                /*default value*/ true);
    }

    private void updateFgsNotificationDeferralApiGated() {
        mFlagFgsNotificationDeferralApiGated = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_DEFERRED_FGS_NOTIFICATIONS_API_GATED,
                /*default value*/ false);
    }

    private void updateFgsNotificationDeferralInterval() {
        mFgsNotificationDeferralInterval = DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_DEFERRED_FGS_NOTIFICATION_INTERVAL,
                /*default value*/ 10_000L);
    }

    private void updateFgsNotificationDeferralIntervalForShort() {
        mFgsNotificationDeferralIntervalForShort = DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_DEFERRED_FGS_NOTIFICATION_INTERVAL_FOR_SHORT,
                /*default value*/ 10_000L);
    }

    private void updateFgsNotificationDeferralExclusionTime() {
        mFgsNotificationDeferralExclusionTime = DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_DEFERRED_FGS_NOTIFICATION_EXCLUSION_TIME,
                /*default value*/ 2 * 60 * 1000L);
    }

    private void updateFgsNotificationDeferralExclusionTimeForShort() {
        mFgsNotificationDeferralExclusionTimeForShort = DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_DEFERRED_FGS_NOTIFICATION_EXCLUSION_TIME_FOR_SHORT,
                /*default value*/ 2 * 60 * 1000L);
    }

    private void updateSystemExemptPowerRestrictionsEnabled() {
        mFlagSystemExemptPowerRestrictionsEnabled = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_SYSTEM_EXEMPT_POWER_RESTRICTIONS_ENABLED,
                DEFAULT_SYSTEM_EXEMPT_POWER_RESTRICTIONS_ENABLED);
    }

    private void updatePushMessagingOverQuotaBehavior() {
        mPushMessagingOverQuotaBehavior = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_PUSH_MESSAGING_OVER_QUOTA_BEHAVIOR,
                DEFAULT_PUSH_MESSAGING_OVER_QUOTA_BEHAVIOR);
        if (mPushMessagingOverQuotaBehavior < TEMPORARY_ALLOW_LIST_TYPE_NONE
                || mPushMessagingOverQuotaBehavior
                > TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_NOT_ALLOWED) {
            mPushMessagingOverQuotaBehavior =
                    DEFAULT_PUSH_MESSAGING_OVER_QUOTA_BEHAVIOR;
        }
    }

    private void updateOomAdjUpdatePolicy() {
        OOMADJ_UPDATE_QUICK = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_OOMADJ_UPDATE_POLICY,
                /* defaultValue */ DEFAULT_OOMADJ_UPDATE_POLICY)
                == OOMADJ_UPDATE_POLICY_QUICK;
    }

    private void updateForceRestrictedBackgroundCheck() {
        FORCE_BACKGROUND_CHECK_ON_RESTRICTED_APPS = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_FORCE_BACKGROUND_CHECK_ON_RESTRICTED_APPS,
                DEFAULT_FORCE_BACKGROUND_CHECK_ON_RESTRICTED_APPS);
    }

    private void updateBootTimeTempAllowListDuration() {
        mBootTimeTempAllowlistDuration = DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_BOOT_TIME_TEMP_ALLOWLIST_DURATION,
                DEFAULT_BOOT_TIME_TEMP_ALLOWLIST_DURATION);
    }

    private void updateFgToBgFgsGraceDuration() {
        mFgToBgFgsGraceDuration = DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_FG_TO_BG_FGS_GRACE_DURATION,
                DEFAULT_FG_TO_BG_FGS_GRACE_DURATION);
    }

    private void updateVisibleToInvisibleUijScheduleGraceDuration() {
        mVisibleToInvisibleUijScheduleGraceDurationMs = DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_VISIBLE_TO_INVISIBLE_UIJ_SCHEDULE_GRACE_DURATION,
                DEFAULT_VISIBLE_TO_INVISIBLE_UIJ_SCHEDULE_GRACE_DURATION);
    }

    private void updateFgsStartForegroundTimeout() {
        mFgsStartForegroundTimeoutMs = DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_FGS_START_FOREGROUND_TIMEOUT,
                DEFAULT_FGS_START_FOREGROUND_TIMEOUT_MS);
    }

    private void updateFgsAtomSamplePercent() {
        mFgsAtomSampleRate = DeviceConfig.getFloat(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_FGS_ATOM_SAMPLE_RATE,
                DEFAULT_FGS_ATOM_SAMPLE_RATE);
    }

    private void updateFgsStartAllowedLogSamplePercent() {
        mFgsStartAllowedLogSampleRate = DeviceConfig.getFloat(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_FGS_START_ALLOWED_LOG_SAMPLE_RATE,
                DEFAULT_FGS_START_ALLOWED_LOG_SAMPLE_RATE);
    }

    private void updateFgsStartDeniedLogSamplePercent() {
        mFgsStartDeniedLogSampleRate = DeviceConfig.getFloat(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_FGS_START_DENIED_LOG_SAMPLE_RATE,
                DEFAULT_FGS_START_DENIED_LOG_SAMPLE_RATE);
    }

    private void updateKillBgRestrictedCachedIdle() {
        mKillBgRestrictedAndCachedIdle = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_KILL_BG_RESTRICTED_CACHED_IDLE,
                DEFAULT_KILL_BG_RESTRICTED_CACHED_IDLE);
    }

    private void updateKillBgRestrictedCachedIdleSettleTime() {
        final long currentSettleTime = mKillBgRestrictedAndCachedIdleSettleTimeMs;
        mKillBgRestrictedAndCachedIdleSettleTimeMs = DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_KILL_BG_RESTRICTED_CACHED_IDLE_SETTLE_TIME,
                DEFAULT_KILL_BG_RESTRICTED_CACHED_IDLE_SETTLE_TIME_MS);
        if (mKillBgRestrictedAndCachedIdleSettleTimeMs < currentSettleTime) {
            // Don't remove existing messages in case other IDLE_UIDS_MSG initiators use lower
            // delays, but send a new message if the settle time has decreased.
            mService.mHandler.sendEmptyMessageDelayed(
                    ActivityManagerService.IDLE_UIDS_MSG,
                    mKillBgRestrictedAndCachedIdleSettleTimeMs);
        }
    }

    private void updateFgsAllowOptOut() {
        mFgsAllowOptOut = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_FGS_ALLOW_OPT_OUT,
                DEFAULT_FGS_ALLOW_OPT_OUT);
    }

    private void updateExtraServiceRestartDelayOnMemPressure() {
        synchronized (mService) {
            final int memFactor = mService.mAppProfiler.getLastMemoryLevelLocked();
            final long[] prevDelays = mExtraServiceRestartDelayOnMemPressure;
            mExtraServiceRestartDelayOnMemPressure = parseLongArray(
                    KEY_EXTRA_SERVICE_RESTART_DELAY_ON_MEM_PRESSURE,
                    DEFAULT_EXTRA_SERVICE_RESTART_DELAY_ON_MEM_PRESSURE);
            mService.mServices.performRescheduleServiceRestartOnMemoryPressureLocked(
                    mExtraServiceRestartDelayOnMemPressure[memFactor],
                    prevDelays[memFactor], "config", SystemClock.uptimeMillis());
        }
    }

    private void updateEnableExtraServiceRestartDelayOnMemPressure() {
        synchronized (mService) {
            final boolean prevEnabled = mEnableExtraServiceRestartDelayOnMemPressure;
            mEnableExtraServiceRestartDelayOnMemPressure = DeviceConfig.getBoolean(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_ENABLE_EXTRA_SERVICE_RESTART_DELAY_ON_MEM_PRESSURE,
                    DEFAULT_ENABLE_EXTRA_SERVICE_RESTART_DELAY_ON_MEM_PRESSURE);
            mService.mServices.rescheduleServiceRestartOnMemoryPressureIfNeededLocked(
                    prevEnabled, mEnableExtraServiceRestartDelayOnMemPressure,
                    SystemClock.uptimeMillis());
        }
    }

    private void updatePrioritizeAlarmBroadcasts() {
        // Flag value can be something that evaluates to `true` or `false`,
        // or empty/null.  If it's empty/null, the platform default is used.
        final String flag = DeviceConfig.getString(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_PRIORITIZE_ALARM_BROADCASTS,
                "");
        mPrioritizeAlarmBroadcasts = TextUtils.isEmpty(flag)
                ? DEFAULT_PRIORITIZE_ALARM_BROADCASTS
                : Boolean.parseBoolean(flag);
    }
    private void updateDeferBootCompletedBroadcast() {
        mDeferBootCompletedBroadcast = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_DEFER_BOOT_COMPLETED_BROADCAST,
                DEFAULT_DEFER_BOOT_COMPLETED_BROADCAST);
    }

    private void updateNoKillCachedProcessesUntilBootCompleted() {
        mNoKillCachedProcessesUntilBootCompleted = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_NO_KILL_CACHED_PROCESSES_UNTIL_BOOT_COMPLETED,
                DEFAULT_NO_KILL_CACHED_PROCESSES_UNTIL_BOOT_COMPLETED);
    }

    private void updateNoKillCachedProcessesPostBootCompletedDurationMillis() {
        mNoKillCachedProcessesPostBootCompletedDurationMillis = DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_NO_KILL_CACHED_PROCESSES_POST_BOOT_COMPLETED_DURATION_MILLIS,
                DEFAULT_NO_KILL_CACHED_PROCESSES_POST_BOOT_COMPLETED_DURATION_MILLIS);
    }

    private void updateMaxEmptyTimeMillis() {
        mMaxEmptyTimeMillis = DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_MAX_EMPTY_TIME_MILLIS,
                DEFAULT_MAX_EMPTY_TIME_MILLIS);
    }

    private void updateNetworkAccessTimeoutMs() {
        mNetworkAccessTimeoutMs = DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_NETWORK_ACCESS_TIMEOUT_MS,
                DEFAULT_NETWORK_ACCESS_TIMEOUT_MS);
    }

    private void updateServiceStartForegroundTimeoutMs() {
        mServiceStartForegroundTimeoutMs = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_SERVICE_START_FOREGROUND_TIMEOUT_MS,
                DEFAULT_SERVICE_START_FOREGROUND_TIMEOUT_MS);
    }

    private void updateServiceStartForegroundAnrDealyMs() {
        mServiceStartForegroundAnrDelayMs = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_SERVICE_START_FOREGROUND_ANR_DELAY_MS,
                DEFAULT_SERVICE_START_FOREGROUND_ANR_DELAY_MS);
    }

    private void updateServiceBindAlmostPerceptibleTimeoutMs() {
        mServiceBindAlmostPerceptibleTimeoutMs = DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_SERVICE_BIND_ALMOST_PERCEPTIBLE_TIMEOUT_MS,
                DEFAULT_SERVICE_BIND_ALMOST_PERCEPTIBLE_TIMEOUT_MS);
    }


    private long[] parseLongArray(@NonNull String key, @NonNull long[] def) {
        final String val = DeviceConfig.getString(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                key, null);
        if (!TextUtils.isEmpty(val)) {
            final String[] ss = val.split(",");
            if (ss.length == def.length) {
                final long[] tmp = new long[ss.length];
                try {
                    for (int i = 0; i < ss.length; i++) {
                        tmp[i] = Long.parseLong(ss[i]);
                    }
                    return tmp;
                } catch (NumberFormatException e) {
                }
            }
        }
        return def;
    }

    private void updateComponentAliases() {
        mEnableComponentAlias = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER_COMPONENT_ALIAS,
                KEY_ENABLE_COMPONENT_ALIAS,
                DEFAULT_ENABLE_COMPONENT_ALIAS);
        mComponentAliasOverrides = DeviceConfig.getString(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER_COMPONENT_ALIAS,
                KEY_COMPONENT_ALIAS_OVERRIDES,
                DEFAULT_COMPONENT_ALIAS_OVERRIDES);
        mService.mComponentAliasResolver.update(mEnableComponentAlias, mComponentAliasOverrides);
    }

    private void updateProcessKillTimeout() {
        mProcessKillTimeoutMs = DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_PROCESS_KILL_TIMEOUT,
                DEFAULT_PROCESS_KILL_TIMEOUT_MS);
    }

    private void updateImperceptibleKillExemptions() {
        IMPERCEPTIBLE_KILL_EXEMPT_PACKAGES.clear();
        IMPERCEPTIBLE_KILL_EXEMPT_PACKAGES.addAll(mDefaultImperceptibleKillExemptPackages);
        String val = DeviceConfig.getString(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_IMPERCEPTIBLE_KILL_EXEMPT_PACKAGES, null);
        if (!TextUtils.isEmpty(val)) {
            IMPERCEPTIBLE_KILL_EXEMPT_PACKAGES.addAll(Arrays.asList(val.split(",")));
        }

        IMPERCEPTIBLE_KILL_EXEMPT_PROC_STATES.clear();
        IMPERCEPTIBLE_KILL_EXEMPT_PROC_STATES.addAll(mDefaultImperceptibleKillExemptProcStates);
        val = DeviceConfig.getString(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_IMPERCEPTIBLE_KILL_EXEMPT_PROC_STATES, null);
        if (!TextUtils.isEmpty(val)) {
            Arrays.asList(val.split(",")).stream().forEach((v) -> {
                try {
                    IMPERCEPTIBLE_KILL_EXEMPT_PROC_STATES.add(Integer.parseInt(v));
                } catch (NumberFormatException e) {
                }
            });
        }
    }

    private void updateEnableAutomaticSystemServerHeapDumps() {
        if (!mSystemServerAutomaticHeapDumpEnabled) {
            Slog.wtf(TAG,
                    "updateEnableAutomaticSystemServerHeapDumps called when leak detection "
                            + "disabled");
            return;
        }
        // Monitoring is on by default, so if the setting hasn't been set by the user,
        // monitoring should be on.
        final boolean enabled = Settings.Global.getInt(mResolver,
                Settings.Global.ENABLE_AUTOMATIC_SYSTEM_SERVER_HEAP_DUMPS, 1) == 1;

        // Setting the threshold to 0 stops the checking.
        final long threshold = enabled ? mSystemServerAutomaticHeapDumpPssThresholdBytes : 0;
        mService.setDumpHeapDebugLimit(null, 0, threshold,
                mSystemServerAutomaticHeapDumpPackageName);
    }

    private void updateMaxCachedProcesses() {
        String maxCachedProcessesFlag = DeviceConfig.getProperty(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER, KEY_MAX_CACHED_PROCESSES);
        try {
            CUR_MAX_CACHED_PROCESSES = mOverrideMaxCachedProcesses < 0
                    ? (TextUtils.isEmpty(maxCachedProcessesFlag)
                    ? mCustomizedMaxCachedProcesses : Integer.parseInt(maxCachedProcessesFlag))
                    : mOverrideMaxCachedProcesses;
        } catch (NumberFormatException e) {
            // Bad flag value from Phenotype, revert to default.
            Slog.e(TAG,
                    "Unable to parse flag for max_cached_processes: " + maxCachedProcessesFlag, e);
            CUR_MAX_CACHED_PROCESSES = mCustomizedMaxCachedProcesses;
        }
        CUR_MAX_EMPTY_PROCESSES = computeEmptyProcessLimit(CUR_MAX_CACHED_PROCESSES);

        final int rawMaxEmptyProcesses = computeEmptyProcessLimit(
                Integer.min(CUR_MAX_CACHED_PROCESSES, MAX_CACHED_PROCESSES));
        CUR_TRIM_EMPTY_PROCESSES = rawMaxEmptyProcesses / 2;
        CUR_TRIM_CACHED_PROCESSES = (Integer.min(CUR_MAX_CACHED_PROCESSES, MAX_CACHED_PROCESSES)
                    - rawMaxEmptyProcesses) / 3;
    }

    private void updateProactiveKillsEnabled() {
        PROACTIVE_KILLS_ENABLED = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_PROACTIVE_KILLS_ENABLED,
                DEFAULT_PROACTIVE_KILLS_ENABLED);
    }

    private void updateLowSwapThresholdPercent() {
        LOW_SWAP_THRESHOLD_PERCENT = DeviceConfig.getFloat(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_LOW_SWAP_THRESHOLD_PERCENT,
                DEFAULT_LOW_SWAP_THRESHOLD_PERCENT);
    }


    private void updateTopToFgsGraceDuration() {
        TOP_TO_FGS_GRACE_DURATION = DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_TOP_TO_FGS_GRACE_DURATION,
                DEFAULT_TOP_TO_FGS_GRACE_DURATION);
    }

    private void updateMaxPreviousTime() {
        MAX_PREVIOUS_TIME = DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_MAX_PREVIOUS_TIME,
                DEFAULT_MAX_PREVIOUS_TIME);
    }

    private void updateProcStateDebugUids() {
        final String val = DeviceConfig.getString(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_PROC_STATE_DEBUG_UIDS,
                "").trim();

        // Parse KEY_PROC_STATE_DEBUG_UIDS as comma-separated values. Each values can be:
        // Number:  Enable debugging on the given UID.
        // "stack": Enable stack trace when updating proc/uid-states.s
        // "u" + delay-ms: Enable sleep when updating uid-state
        // "p" + delay-ms: Enable sleep when updating procstate
        //
        // Example:
        //   device_config put activity_manager proc_state_debug_uids '10177,10202,stack,p500,u100'
        // means:
        // - Monitor UID 10177 and 10202
        // - Also enable stack trace
        // - Sleep 500 ms when updating the procstate.
        // - Sleep 100 ms when updating the UID state.

        mEnableProcStateStacktrace = false;
        mProcStateDebugSetProcStateDelay = 0;
        mProcStateDebugSetUidStateDelay = 0;
        if (val.length() == 0) {
            mProcStateDebugUids = new SparseBooleanArray(0);
            return;
        }
        final String[] uids = val.split(",");

        final SparseBooleanArray newArray = new SparseBooleanArray(0);

        for (String token : uids) {
            if (token.length() == 0) {
                continue;
            }
            // "stack" -> enable stacktrace.
            if ("stack".equals(token)) {
                mEnableProcStateStacktrace = true;
                continue;
            }
            boolean isUid = true;
            char prefix = token.charAt(0);
            if ('a' <= prefix && prefix <= 'z') {
                // If the token starts with an alphabet, it's not a UID.
                isUid = false;
                token = token.substring(1);
            }

            int value = -1;
            try {
                value = Integer.parseInt(token.trim());
            } catch (NumberFormatException e) {
                Slog.w(TAG, "Invalid number " + token + " in " + val);
                continue;
            }
            if (isUid) {
                newArray.put(value, true);
            } else if (prefix == 'p') {
                // Enable delay in set-proc-state
                mProcStateDebugSetProcStateDelay = value;
            } else if (prefix == 'u') {
                // Enable delay in set-uid-state
                mProcStateDebugSetUidStateDelay = value;
            } else {
                Slog.w(TAG, "Invalid prefix " + prefix + " in " + val);
            }
        }
        mProcStateDebugUids = newArray;
    }

    private void updateMinAssocLogDuration() {
        MIN_ASSOC_LOG_DURATION = DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER, KEY_MIN_ASSOC_LOG_DURATION,
                /* defaultValue */ DEFAULT_MIN_ASSOC_LOG_DURATION);
    }

    private void updateBinderHeavyHitterWatcher() {
        BINDER_HEAVY_HITTER_WATCHER_ENABLED = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER, KEY_BINDER_HEAVY_HITTER_WATCHER_ENABLED,
                mDefaultBinderHeavyHitterWatcherEnabled);
        BINDER_HEAVY_HITTER_WATCHER_BATCHSIZE = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER, KEY_BINDER_HEAVY_HITTER_WATCHER_BATCHSIZE,
                mDefaultBinderHeavyHitterWatcherBatchSize);
        BINDER_HEAVY_HITTER_WATCHER_THRESHOLD = DeviceConfig.getFloat(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER, KEY_BINDER_HEAVY_HITTER_WATCHER_THRESHOLD,
                mDefaultBinderHeavyHitterWatcherThreshold);
        BINDER_HEAVY_HITTER_AUTO_SAMPLER_ENABLED = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_BINDER_HEAVY_HITTER_AUTO_SAMPLER_ENABLED,
                mDefaultBinderHeavyHitterAutoSamplerEnabled);
        BINDER_HEAVY_HITTER_AUTO_SAMPLER_BATCHSIZE = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_BINDER_HEAVY_HITTER_AUTO_SAMPLER_BATCHSIZE,
                mDefaultBinderHeavyHitterAutoSamplerBatchSize);
        BINDER_HEAVY_HITTER_WATCHER_THRESHOLD = DeviceConfig.getFloat(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_BINDER_HEAVY_HITTER_AUTO_SAMPLER_THRESHOLD,
                mDefaultBinderHeavyHitterAutoSamplerThreshold);
        mService.scheduleUpdateBinderHeavyHitterWatcherConfig();
    }

    private void updateMaxPhantomProcesses() {
        final int oldVal = MAX_PHANTOM_PROCESSES;
        MAX_PHANTOM_PROCESSES = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER, KEY_MAX_PHANTOM_PROCESSES,
                DEFAULT_MAX_PHANTOM_PROCESSES);
        if (oldVal > MAX_PHANTOM_PROCESSES) {
            mService.mHandler.post(mService.mPhantomProcessList::trimPhantomProcessesIfNecessary);
        }
    }

    private void updateMaxServiceConnectionsPerProcess() {
        mMaxServiceConnectionsPerProcess = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_MAX_SERVICE_CONNECTIONS_PER_PROCESS,
                DEFAULT_MAX_SERVICE_CONNECTIONS_PER_PROCESS);
    }

    private void updateShortFgsTimeoutDuration() {
        mShortFgsTimeoutDuration = DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_SHORT_FGS_TIMEOUT_DURATION,
                DEFAULT_SHORT_FGS_TIMEOUT_DURATION);
    }

    private void updateShortFgsProcStateExtraWaitDuration() {
        mShortFgsProcStateExtraWaitDuration = DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_SHORT_FGS_PROC_STATE_EXTRA_WAIT_DURATION,
                DEFAULT_SHORT_FGS_PROC_STATE_EXTRA_WAIT_DURATION);
    }

    private void updateShortFgsAnrExtraWaitDuration() {
        mShortFgsAnrExtraWaitDuration = DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_SHORT_FGS_ANR_EXTRA_WAIT_DURATION,
                DEFAULT_SHORT_FGS_ANR_EXTRA_WAIT_DURATION);
    }

    private void updateMediaProcessingFgsTimeoutDuration() {
        mMediaProcessingFgsTimeoutDuration = DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_MEDIA_PROCESSING_FGS_TIMEOUT_DURATION,
                DEFAULT_MEDIA_PROCESSING_FGS_TIMEOUT_DURATION);
    }

    private void updateDataSyncFgsTimeoutDuration() {
        mDataSyncFgsTimeoutDuration = DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_DATA_SYNC_FGS_TIMEOUT_DURATION,
                DEFAULT_DATA_SYNC_FGS_TIMEOUT_DURATION);
    }

    private void updateFgsCrashExtraWaitDuration() {
        mFgsCrashExtraWaitDuration = DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_FGS_CRASH_EXTRA_WAIT_DURATION,
                DEFAULT_FGS_CRASH_EXTRA_WAIT_DURATION);
    }

    private void updateEnableWaitForFinishAttachApplication() {
        mEnableWaitForFinishAttachApplication = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_ENABLE_WAIT_FOR_FINISH_ATTACH_APPLICATION,
                DEFAULT_ENABLE_WAIT_FOR_FINISH_ATTACH_APPLICATION);
    }

    private void updateUseTieredCachedAdj() {
        USE_TIERED_CACHED_ADJ = DeviceConfig.getBoolean(
            DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
            KEY_USE_TIERED_CACHED_ADJ,
            DEFAULT_USE_TIERED_CACHED_ADJ);
        TIERED_CACHED_ADJ_DECAY_TIME = DeviceConfig.getLong(
            DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
            KEY_TIERED_CACHED_ADJ_DECAY_TIME,
            DEFAULT_TIERED_CACHED_ADJ_DECAY_TIME);
    }

    private void updateEnableNewOomAdj() {
        ENABLE_NEW_OOMADJ = DeviceConfig.getBoolean(
            DeviceConfig.NAMESPACE_ACTIVITY_MANAGER_NATIVE_BOOT,
            KEY_ENABLE_NEW_OOMADJ,
            DEFAULT_ENABLE_NEW_OOM_ADJ);
    }

    private void updateFGSPermissionEnforcementFlagsIfNecessary(@NonNull String name) {
        ForegroundServiceTypePolicy.getDefaultPolicy()
            .updatePermissionEnforcementFlagIfNecessary(name);
    }

    private void updateDisableAppProfilerPssProfiling() {
        APP_PROFILER_PSS_PROFILING_DISABLED = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER, KEY_DISABLE_APP_PROFILER_PSS_PROFILING,
                mDefaultDisableAppProfilerPssProfiling);
    }

    private void updatePssToRssThresholdModifier() {
        PSS_TO_RSS_THRESHOLD_MODIFIER = DeviceConfig.getFloat(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER, KEY_PSS_TO_RSS_THRESHOLD_MODIFIER,
                mDefaultPssToRssThresholdModifier);
    }

    boolean shouldDebugUidForProcState(int uid) {
        SparseBooleanArray ar = mProcStateDebugUids;
        final var size = ar.size();
        if (size == 0) { // Most common case.
            return false;
        }
        // If the array is small (also common), avoid the binary search.
        if (size <= 8) {
            for (int i = 0; i < size; i++) {
                if (ar.keyAt(i) == uid) {
                    return ar.valueAt(i);
                }
            }
            return false;
        }
        return ar.get(uid, false);
    }

    boolean shouldEnableProcStateDebug() {
        return mProcStateDebugUids.size() > 0;
    }

    @NeverCompile // Avoid size overhead of debugging code.
    void dump(PrintWriter pw) {
        pw.println("ACTIVITY MANAGER SETTINGS (dumpsys activity settings) "
                + Settings.Global.ACTIVITY_MANAGER_CONSTANTS + ":");

        pw.print("  "); pw.print(KEY_MAX_CACHED_PROCESSES); pw.print("=");
        pw.println(MAX_CACHED_PROCESSES);
        pw.print("  "); pw.print(KEY_BACKGROUND_SETTLE_TIME); pw.print("=");
        pw.println(BACKGROUND_SETTLE_TIME);
        pw.print("  "); pw.print(KEY_FGSERVICE_MIN_SHOWN_TIME); pw.print("=");
        pw.println(FGSERVICE_MIN_SHOWN_TIME);
        pw.print("  "); pw.print(KEY_FGSERVICE_MIN_REPORT_TIME); pw.print("=");
        pw.println(FGSERVICE_MIN_REPORT_TIME);
        pw.print("  "); pw.print(KEY_FGSERVICE_SCREEN_ON_BEFORE_TIME); pw.print("=");
        pw.println(FGSERVICE_SCREEN_ON_BEFORE_TIME);
        pw.print("  "); pw.print(KEY_FGSERVICE_SCREEN_ON_AFTER_TIME); pw.print("=");
        pw.println(FGSERVICE_SCREEN_ON_AFTER_TIME);
        pw.print("  "); pw.print(KEY_FGS_BOOT_COMPLETED_ALLOWLIST); pw.print("=");
        pw.println(FGS_BOOT_COMPLETED_ALLOWLIST);
        pw.print("  "); pw.print(KEY_CONTENT_PROVIDER_RETAIN_TIME); pw.print("=");
        pw.println(CONTENT_PROVIDER_RETAIN_TIME);
        pw.print("  "); pw.print(KEY_GC_TIMEOUT); pw.print("=");
        pw.println(GC_TIMEOUT);
        pw.print("  "); pw.print(KEY_GC_MIN_INTERVAL); pw.print("=");
        pw.println(GC_MIN_INTERVAL);
        pw.print("  "); pw.print(KEY_FORCE_BACKGROUND_CHECK_ON_RESTRICTED_APPS); pw.print("=");
        pw.println(FORCE_BACKGROUND_CHECK_ON_RESTRICTED_APPS);
        pw.print("  "); pw.print(KEY_FULL_PSS_MIN_INTERVAL); pw.print("=");
        pw.println(FULL_PSS_MIN_INTERVAL);
        pw.print("  "); pw.print(KEY_FULL_PSS_LOWERED_INTERVAL); pw.print("=");
        pw.println(FULL_PSS_LOWERED_INTERVAL);
        pw.print("  "); pw.print(KEY_POWER_CHECK_INTERVAL); pw.print("=");
        pw.println(POWER_CHECK_INTERVAL);
        pw.print("  "); pw.print(KEY_POWER_CHECK_MAX_CPU_1); pw.print("=");
        pw.println(POWER_CHECK_MAX_CPU_1);
        pw.print("  "); pw.print(KEY_POWER_CHECK_MAX_CPU_2); pw.print("=");
        pw.println(POWER_CHECK_MAX_CPU_2);
        pw.print("  "); pw.print(KEY_POWER_CHECK_MAX_CPU_3); pw.print("=");
        pw.println(POWER_CHECK_MAX_CPU_3);
        pw.print("  "); pw.print(KEY_POWER_CHECK_MAX_CPU_4); pw.print("=");
        pw.println(POWER_CHECK_MAX_CPU_4);
        pw.print("  "); pw.print(KEY_SERVICE_USAGE_INTERACTION_TIME_PRE_S); pw.print("=");
        pw.println(SERVICE_USAGE_INTERACTION_TIME_PRE_S);
        pw.print("  "); pw.print(KEY_SERVICE_USAGE_INTERACTION_TIME_POST_S); pw.print("=");
        pw.println(SERVICE_USAGE_INTERACTION_TIME_POST_S);
        pw.print("  "); pw.print(KEY_USAGE_STATS_INTERACTION_INTERVAL_PRE_S); pw.print("=");
        pw.println(USAGE_STATS_INTERACTION_INTERVAL_PRE_S);
        pw.print("  "); pw.print(KEY_USAGE_STATS_INTERACTION_INTERVAL_POST_S); pw.print("=");
        pw.println(USAGE_STATS_INTERACTION_INTERVAL_POST_S);
        pw.print("  "); pw.print(KEY_SERVICE_RESTART_DURATION); pw.print("=");
        pw.println(SERVICE_RESTART_DURATION);
        pw.print("  "); pw.print(KEY_SERVICE_RESET_RUN_DURATION); pw.print("=");
        pw.println(SERVICE_RESET_RUN_DURATION);
        pw.print("  "); pw.print(KEY_SERVICE_RESTART_DURATION_FACTOR); pw.print("=");
        pw.println(SERVICE_RESTART_DURATION_FACTOR);
        pw.print("  "); pw.print(KEY_SERVICE_MIN_RESTART_TIME_BETWEEN); pw.print("=");
        pw.println(SERVICE_MIN_RESTART_TIME_BETWEEN);
        pw.print("  "); pw.print(KEY_MAX_SERVICE_INACTIVITY); pw.print("=");
        pw.println(MAX_SERVICE_INACTIVITY);
        pw.print("  "); pw.print(KEY_BG_START_TIMEOUT); pw.print("=");
        pw.println(BG_START_TIMEOUT);
        pw.print("  "); pw.print(KEY_SERVICE_BG_ACTIVITY_START_TIMEOUT); pw.print("=");
        pw.println(SERVICE_BG_ACTIVITY_START_TIMEOUT);
        pw.print("  "); pw.print(KEY_BOUND_SERVICE_CRASH_RESTART_DURATION); pw.print("=");
        pw.println(BOUND_SERVICE_CRASH_RESTART_DURATION);
        pw.print("  "); pw.print(KEY_BOUND_SERVICE_CRASH_MAX_RETRY); pw.print("=");
        pw.println(BOUND_SERVICE_MAX_CRASH_RETRY);
        pw.print("  "); pw.print(KEY_PROCESS_START_ASYNC); pw.print("=");
        pw.println(FLAG_PROCESS_START_ASYNC);
        pw.print("  "); pw.print(KEY_MEMORY_INFO_THROTTLE_TIME); pw.print("=");
        pw.println(MEMORY_INFO_THROTTLE_TIME);
        pw.print("  "); pw.print(KEY_TOP_TO_FGS_GRACE_DURATION); pw.print("=");
        pw.println(TOP_TO_FGS_GRACE_DURATION);
        pw.print("  "); pw.print(KEY_TOP_TO_ALMOST_PERCEPTIBLE_GRACE_DURATION); pw.print("=");
        pw.println(TOP_TO_ALMOST_PERCEPTIBLE_GRACE_DURATION);
        pw.print("  "); pw.print(KEY_MIN_CRASH_INTERVAL); pw.print("=");
        pw.println(MIN_CRASH_INTERVAL);
        pw.print("  "); pw.print(KEY_PROCESS_CRASH_COUNT_RESET_INTERVAL); pw.print("=");
        pw.println(PROCESS_CRASH_COUNT_RESET_INTERVAL);
        pw.print("  "); pw.print(KEY_PROCESS_CRASH_COUNT_LIMIT); pw.print("=");
        pw.println(PROCESS_CRASH_COUNT_LIMIT);
        pw.print("  "); pw.print(KEY_IMPERCEPTIBLE_KILL_EXEMPT_PROC_STATES); pw.print("=");
        pw.println(Arrays.toString(IMPERCEPTIBLE_KILL_EXEMPT_PROC_STATES.toArray()));
        pw.print("  "); pw.print(KEY_IMPERCEPTIBLE_KILL_EXEMPT_PACKAGES); pw.print("=");
        pw.println(Arrays.toString(IMPERCEPTIBLE_KILL_EXEMPT_PACKAGES.toArray()));
        pw.print("  "); pw.print(KEY_MIN_ASSOC_LOG_DURATION); pw.print("=");
        pw.println(MIN_ASSOC_LOG_DURATION);
        pw.print("  "); pw.print(KEY_BINDER_HEAVY_HITTER_WATCHER_ENABLED); pw.print("=");
        pw.println(BINDER_HEAVY_HITTER_WATCHER_ENABLED);
        pw.print("  "); pw.print(KEY_BINDER_HEAVY_HITTER_WATCHER_BATCHSIZE); pw.print("=");
        pw.println(BINDER_HEAVY_HITTER_WATCHER_BATCHSIZE);
        pw.print("  "); pw.print(KEY_BINDER_HEAVY_HITTER_WATCHER_THRESHOLD); pw.print("=");
        pw.println(BINDER_HEAVY_HITTER_WATCHER_THRESHOLD);
        pw.print("  "); pw.print(KEY_BINDER_HEAVY_HITTER_AUTO_SAMPLER_ENABLED); pw.print("=");
        pw.println(BINDER_HEAVY_HITTER_AUTO_SAMPLER_ENABLED);
        pw.print("  "); pw.print(KEY_BINDER_HEAVY_HITTER_AUTO_SAMPLER_BATCHSIZE); pw.print("=");
        pw.println(BINDER_HEAVY_HITTER_AUTO_SAMPLER_BATCHSIZE);
        pw.print("  "); pw.print(KEY_BINDER_HEAVY_HITTER_AUTO_SAMPLER_THRESHOLD); pw.print("=");
        pw.println(BINDER_HEAVY_HITTER_AUTO_SAMPLER_THRESHOLD);
        pw.print("  "); pw.print(KEY_MAX_PHANTOM_PROCESSES); pw.print("=");
        pw.println(MAX_PHANTOM_PROCESSES);
        pw.print("  "); pw.print(KEY_BOOT_TIME_TEMP_ALLOWLIST_DURATION); pw.print("=");
        pw.println(mBootTimeTempAllowlistDuration);
        pw.print("  "); pw.print(KEY_FG_TO_BG_FGS_GRACE_DURATION); pw.print("=");
        pw.println(mFgToBgFgsGraceDuration);
        pw.print("  "); pw.print(KEY_FGS_START_FOREGROUND_TIMEOUT); pw.print("=");
        pw.println(mFgsStartForegroundTimeoutMs);
        pw.print("  "); pw.print(KEY_DEFAULT_BACKGROUND_ACTIVITY_STARTS_ENABLED); pw.print("=");
        pw.println(mFlagBackgroundActivityStartsEnabled);
        pw.print("  "); pw.print(KEY_DEFAULT_BACKGROUND_FGS_STARTS_RESTRICTION_ENABLED);
        pw.print("="); pw.println(mFlagBackgroundFgsStartRestrictionEnabled);
        pw.print("  "); pw.print(KEY_DEFAULT_FGS_STARTS_RESTRICTION_ENABLED); pw.print("=");
        pw.println(mFlagFgsStartRestrictionEnabled);
        pw.print("  "); pw.print(KEY_DEFAULT_FGS_STARTS_RESTRICTION_NOTIFICATION_ENABLED);
                pw.print("=");
        pw.println(mFgsStartRestrictionNotificationEnabled);
        pw.print("  "); pw.print(KEY_DEFAULT_FGS_STARTS_RESTRICTION_CHECK_CALLER_TARGET_SDK);
        pw.print("="); pw.println(mFgsStartRestrictionCheckCallerTargetSdk);
        pw.print("  "); pw.print(KEY_FGS_ATOM_SAMPLE_RATE);
        pw.print("="); pw.println(mFgsAtomSampleRate);
        pw.print("  "); pw.print(KEY_FGS_START_ALLOWED_LOG_SAMPLE_RATE);
        pw.print("="); pw.println(mFgsStartAllowedLogSampleRate);
        pw.print("  "); pw.print(KEY_FGS_START_DENIED_LOG_SAMPLE_RATE);
        pw.print("="); pw.println(mFgsStartDeniedLogSampleRate);
        pw.print("  "); pw.print(KEY_PUSH_MESSAGING_OVER_QUOTA_BEHAVIOR);
        pw.print("="); pw.println(mPushMessagingOverQuotaBehavior);
        pw.print("  "); pw.print(KEY_FGS_ALLOW_OPT_OUT);
        pw.print("="); pw.println(mFgsAllowOptOut);
        pw.print("  "); pw.print(KEY_ENABLE_COMPONENT_ALIAS);
        pw.print("="); pw.println(mEnableComponentAlias);
        pw.print("  "); pw.print(KEY_COMPONENT_ALIAS_OVERRIDES);
        pw.print("="); pw.println(mComponentAliasOverrides);
        pw.print("  "); pw.print(KEY_DEFER_BOOT_COMPLETED_BROADCAST);
        pw.print("="); pw.println(mDeferBootCompletedBroadcast);
        pw.print("  "); pw.print(KEY_PRIORITIZE_ALARM_BROADCASTS);
        pw.print("="); pw.println(mPrioritizeAlarmBroadcasts);
        pw.print("  "); pw.print(KEY_NO_KILL_CACHED_PROCESSES_UNTIL_BOOT_COMPLETED);
        pw.print("="); pw.println(mNoKillCachedProcessesUntilBootCompleted);
        pw.print("  "); pw.print(KEY_NO_KILL_CACHED_PROCESSES_POST_BOOT_COMPLETED_DURATION_MILLIS);
        pw.print("="); pw.println(mNoKillCachedProcessesPostBootCompletedDurationMillis);
        pw.print("  "); pw.print(KEY_MAX_EMPTY_TIME_MILLIS);
        pw.print("="); pw.println(mMaxEmptyTimeMillis);
        pw.print("  "); pw.print(KEY_SERVICE_START_FOREGROUND_TIMEOUT_MS);
        pw.print("="); pw.println(mServiceStartForegroundTimeoutMs);
        pw.print("  "); pw.print(KEY_SERVICE_START_FOREGROUND_ANR_DELAY_MS);
        pw.print("="); pw.println(mServiceStartForegroundAnrDelayMs);
        pw.print("  "); pw.print(KEY_SERVICE_BIND_ALMOST_PERCEPTIBLE_TIMEOUT_MS);
        pw.print("="); pw.println(mServiceBindAlmostPerceptibleTimeoutMs);
        pw.print("  "); pw.print(KEY_NETWORK_ACCESS_TIMEOUT_MS);
        pw.print("="); pw.println(mNetworkAccessTimeoutMs);
        pw.print("  "); pw.print(KEY_MAX_SERVICE_CONNECTIONS_PER_PROCESS);
        pw.print("="); pw.println(mMaxServiceConnectionsPerProcess);
        pw.print("  "); pw.print(KEY_PROACTIVE_KILLS_ENABLED);
        pw.print("="); pw.println(PROACTIVE_KILLS_ENABLED);
        pw.print("  "); pw.print(KEY_LOW_SWAP_THRESHOLD_PERCENT);
        pw.print("="); pw.println(LOW_SWAP_THRESHOLD_PERCENT);

        pw.print("  "); pw.print(KEY_DEFERRED_FGS_NOTIFICATIONS_ENABLED);
        pw.print("="); pw.println(mFlagFgsNotificationDeferralEnabled);
        pw.print("  "); pw.print(KEY_DEFERRED_FGS_NOTIFICATIONS_API_GATED);
        pw.print("="); pw.println(mFlagFgsNotificationDeferralApiGated);

        pw.print("  "); pw.print(KEY_DEFERRED_FGS_NOTIFICATION_INTERVAL);
        pw.print("="); pw.println(mFgsNotificationDeferralInterval);
        pw.print("  "); pw.print(KEY_DEFERRED_FGS_NOTIFICATION_INTERVAL_FOR_SHORT);
        pw.print("="); pw.println(mFgsNotificationDeferralIntervalForShort);

        pw.print("  "); pw.print(KEY_DEFERRED_FGS_NOTIFICATION_EXCLUSION_TIME);
        pw.print("="); pw.println(mFgsNotificationDeferralExclusionTime);
        pw.print("  "); pw.print(KEY_DEFERRED_FGS_NOTIFICATION_EXCLUSION_TIME_FOR_SHORT);
        pw.print("="); pw.println(mFgsNotificationDeferralExclusionTimeForShort);

        pw.print("  "); pw.print(KEY_SYSTEM_EXEMPT_POWER_RESTRICTIONS_ENABLED);
        pw.print("="); pw.println(mFlagSystemExemptPowerRestrictionsEnabled);

        pw.print("  "); pw.print(KEY_SHORT_FGS_TIMEOUT_DURATION);
        pw.print("="); pw.println(mShortFgsTimeoutDuration);
        pw.print("  "); pw.print(KEY_SHORT_FGS_PROC_STATE_EXTRA_WAIT_DURATION);
        pw.print("="); pw.println(mShortFgsProcStateExtraWaitDuration);
        pw.print("  "); pw.print(KEY_SHORT_FGS_ANR_EXTRA_WAIT_DURATION);
        pw.print("="); pw.println(mShortFgsAnrExtraWaitDuration);

        pw.print("  "); pw.print(KEY_MEDIA_PROCESSING_FGS_TIMEOUT_DURATION);
        pw.print("="); pw.println(mMediaProcessingFgsTimeoutDuration);
        pw.print("  "); pw.print(KEY_DATA_SYNC_FGS_TIMEOUT_DURATION);
        pw.print("="); pw.println(mDataSyncFgsTimeoutDuration);
        pw.print("  "); pw.print(KEY_FGS_CRASH_EXTRA_WAIT_DURATION);
        pw.print("="); pw.println(mFgsCrashExtraWaitDuration);

        pw.print("  "); pw.print(KEY_USE_TIERED_CACHED_ADJ);
        pw.print("="); pw.println(USE_TIERED_CACHED_ADJ);
        pw.print("  "); pw.print(KEY_TIERED_CACHED_ADJ_DECAY_TIME);
        pw.print("="); pw.println(TIERED_CACHED_ADJ_DECAY_TIME);

        pw.print("  "); pw.print(KEY_ENABLE_NEW_OOMADJ);
        pw.print("="); pw.println(ENABLE_NEW_OOMADJ);

        pw.print("  "); pw.print(KEY_DISABLE_APP_PROFILER_PSS_PROFILING);
        pw.print("="); pw.println(APP_PROFILER_PSS_PROFILING_DISABLED);

        pw.print("  "); pw.print(KEY_PSS_TO_RSS_THRESHOLD_MODIFIER);
        pw.print("="); pw.println(PSS_TO_RSS_THRESHOLD_MODIFIER);

        pw.print("  "); pw.print(KEY_MAX_PREVIOUS_TIME);
        pw.print("="); pw.println(MAX_PREVIOUS_TIME);

        pw.println();
        if (mOverrideMaxCachedProcesses >= 0) {
            pw.print("  mOverrideMaxCachedProcesses="); pw.println(mOverrideMaxCachedProcesses);
        }
        pw.print("  mCustomizedMaxCachedProcesses="); pw.println(mCustomizedMaxCachedProcesses);
        pw.print("  CUR_MAX_CACHED_PROCESSES="); pw.println(CUR_MAX_CACHED_PROCESSES);
        pw.print("  CUR_MAX_EMPTY_PROCESSES="); pw.println(CUR_MAX_EMPTY_PROCESSES);
        pw.print("  CUR_TRIM_EMPTY_PROCESSES="); pw.println(CUR_TRIM_EMPTY_PROCESSES);
        pw.print("  CUR_TRIM_CACHED_PROCESSES="); pw.println(CUR_TRIM_CACHED_PROCESSES);
        pw.print("  OOMADJ_UPDATE_QUICK="); pw.println(OOMADJ_UPDATE_QUICK);
        pw.print("  ENABLE_WAIT_FOR_FINISH_ATTACH_APPLICATION=");
        pw.println(mEnableWaitForFinishAttachApplication);

        synchronized (mProcStateDebugUids) {
            pw.print("  "); pw.print(KEY_PROC_STATE_DEBUG_UIDS);
            pw.print("="); pw.println(mProcStateDebugUids);
            pw.print("    uid-state-delay="); pw.println(mProcStateDebugSetUidStateDelay);
            pw.print("    proc-state-delay="); pw.println(mProcStateDebugSetProcStateDelay);
        }
    }
}
