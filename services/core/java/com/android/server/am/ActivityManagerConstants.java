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

import static android.os.PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_NOT_ALLOWED;
import static android.os.PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_NONE;

import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_POWER_QUICK;

import android.app.ActivityThread;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.os.PowerExemptionManager;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.OnPropertiesChangedListener;
import android.provider.DeviceConfig.Properties;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.KeyValueListParser;
import android.util.Slog;

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
    private static final String KEY_BACKGROUND_SETTLE_TIME = "background_settle_time";
    private static final String KEY_FGSERVICE_MIN_SHOWN_TIME
            = "fgservice_min_shown_time";
    private static final String KEY_FGSERVICE_MIN_REPORT_TIME
            = "fgservice_min_report_time";
    private static final String KEY_FGSERVICE_SCREEN_ON_BEFORE_TIME
            = "fgservice_screen_on_before_time";
    private static final String KEY_FGSERVICE_SCREEN_ON_AFTER_TIME
            = "fgservice_screen_on_after_time";
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
    static final String KEY_PENDINGINTENT_WARNING_THRESHOLD = "pendingintent_warning_threshold";
    static final String KEY_MIN_CRASH_INTERVAL = "min_crash_interval";
    static final String KEY_PROCESS_CRASH_COUNT_RESET_INTERVAL =
            "process_crash_count_reset_interval";
    static final String KEY_PROCESS_CRASH_COUNT_LIMIT = "process_crash_count_limit";
    static final String KEY_BOOT_TIME_TEMP_ALLOWLIST_DURATION = "boot_time_temp_allowlist_duration";
    static final String KEY_FG_TO_BG_FGS_GRACE_DURATION = "fg_to_bg_fgs_grace_duration";
    static final String KEY_FGS_START_FOREGROUND_TIMEOUT = "fgs_start_foreground_timeout";
    static final String KEY_FGS_ATOM_SAMPLE_RATE = "fgs_atom_sample_rate";
    static final String KEY_FGS_ALLOW_OPT_OUT = "fgs_allow_opt_out";

    private static final int DEFAULT_MAX_CACHED_PROCESSES = 32;
    private static final long DEFAULT_BACKGROUND_SETTLE_TIME = 60*1000;
    private static final long DEFAULT_FGSERVICE_MIN_SHOWN_TIME = 2*1000;
    private static final long DEFAULT_FGSERVICE_MIN_REPORT_TIME = 3*1000;
    private static final long DEFAULT_FGSERVICE_SCREEN_ON_BEFORE_TIME = 1*1000;
    private static final long DEFAULT_FGSERVICE_SCREEN_ON_AFTER_TIME = 5*1000;
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
    private static final int DEFAULT_PENDINGINTENT_WARNING_THRESHOLD = 2000;
    private static final int DEFAULT_MIN_CRASH_INTERVAL = 2 * 60 * 1000;
    private static final int DEFAULT_MAX_PHANTOM_PROCESSES = 32;
    private static final int DEFAULT_PROCESS_CRASH_COUNT_RESET_INTERVAL = 12 * 60 * 60 * 1000;
    private static final int DEFAULT_PROCESS_CRASH_COUNT_LIMIT = 12;
    private static final int DEFAULT_BOOT_TIME_TEMP_ALLOWLIST_DURATION = 20 * 1000;
    private static final long DEFAULT_FG_TO_BG_FGS_GRACE_DURATION = 5 * 1000;
    private static final int DEFAULT_FGS_START_FOREGROUND_TIMEOUT_MS = 10 * 1000;
    private static final float DEFAULT_FGS_ATOM_SAMPLE_RATE = 1; // 100 %
    /**
     * Same as {@link TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_NOT_ALLOWED}
     */
    private static final int
            DEFAULT_PUSH_MESSAGING_OVER_QUOTA_BEHAVIOR = 1;
    private static final boolean DEFAULT_FGS_ALLOW_OPT_OUT = false;

    // Flag stored in the DeviceConfig API.
    /**
     * Maximum number of cached processes.
     */
    private static final String KEY_MAX_CACHED_PROCESSES = "max_cached_processes";

    /**
     * Maximum number of cached processes.
     */
    private static final String KEY_MAX_PHANTOM_PROCESSES = "max_phantom_processes";

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
     * Time in milliseconds; once an FGS notification for a given uid has been
     * deferred, no subsequent FGS notification from that uid will be deferred
     * until this amount of time has passed.  Default is two minutes
     * (2 * 60 * 1000) unless overridden.
     */
    private static final String KEY_DEFERRED_FGS_NOTIFICATION_EXCLUSION_TIME =
            "deferred_fgs_notification_exclusion_time";

    /**
     * Default value for mPushMessagingOverQuotaBehavior if not explicitly set in
     * Settings.Global.
     */
    private static final String KEY_PUSH_MESSAGING_OVER_QUOTA_BEHAVIOR =
            "push_messaging_over_quota_behavior";

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
    public long TOP_TO_FGS_GRACE_DURATION = DEFAULT_TOP_TO_FGS_GRACE_DURATION;

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

    // Rate limit: minimum time after an app's FGS notification is deferred
    // before another FGS notification from that app can be deferred.
    volatile long mFgsNotificationDeferralExclusionTime = 2 * 60 * 1000L;

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
     * When service started from background, before the timeout it can be promoted to FGS by calling
     * Service.startForeground().
     */
    volatile long mFgsStartForegroundTimeoutMs = DEFAULT_FGS_START_FOREGROUND_TIMEOUT_MS;

    /**
     * Sample rate for the FGS westworld atom.
     *
     * If the value is 0.1, 10% of the installed packages would be sampled.
     */
    volatile float mFgsAtomSampleRate = DEFAULT_FGS_ATOM_SAMPLE_RATE;

    /**
     * Whether to allow "opt-out" from the foreground service restrictions.
     * (https://developer.android.com/about/versions/12/foreground-services)
     */
    volatile boolean mFgsAllowOptOut = DEFAULT_FGS_ALLOW_OPT_OUT;

    private final ActivityManagerService mService;
    private ContentResolver mResolver;
    private final KeyValueListParser mParser = new KeyValueListParser(',');

    private int mOverrideMaxCachedProcesses = -1;

    // The maximum number of cached processes we will keep around before killing them.
    // NOTE: this constant is *only* a control to not let us go too crazy with
    // keeping around processes on devices with large amounts of RAM.  For devices that
    // are tighter on RAM, the out of memory killer is responsible for killing background
    // processes as RAM is needed, and we should *never* be relying on this limit to
    // kill them.  Also note that this limit only applies to cached background processes;
    // we have no limit on the number of service, visible, foreground, or other such
    // processes and the number of those processes does not count against the cached
    // process limit.
    public int CUR_MAX_CACHED_PROCESSES = DEFAULT_MAX_CACHED_PROCESSES;

    // The maximum number of empty app processes we will let sit around.
    public int CUR_MAX_EMPTY_PROCESSES = computeEmptyProcessLimit(CUR_MAX_CACHED_PROCESSES);

    // The number of empty apps at which we don't consider it necessary to do
    // memory trimming.
    public int CUR_TRIM_EMPTY_PROCESSES = computeEmptyProcessLimit(MAX_CACHED_PROCESSES) / 2;

    // The number of cached at which we don't consider it necessary to do
    // memory trimming.
    public int CUR_TRIM_CACHED_PROCESSES =
            (MAX_CACHED_PROCESSES - computeEmptyProcessLimit(MAX_CACHED_PROCESSES)) / 3;

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

    /**
     * The threshold to decide if a given association should be dumped into metrics.
     */
    private static final long DEFAULT_MIN_ASSOC_LOG_DURATION = 5 * 60 * 1000; // 5 mins

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
                            case KEY_FGS_START_FOREGROUND_TIMEOUT:
                                updateFgsStartForegroundTimeout();
                                break;
                            case KEY_FGS_ATOM_SAMPLE_RATE:
                                updateFgsAtomSamplePercent();
                                break;
                            case KEY_FGS_ALLOW_OPT_OUT:
                                updateFgsAllowOptOut();
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
        updateConstants();
        if (mSystemServerAutomaticHeapDumpEnabled) {
            updateEnableAutomaticSystemServerHeapDumps();
        }
        DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                ActivityThread.currentApplication().getMainExecutor(),
                mOnDeviceConfigChangedListener);
        loadDeviceConfigConstants();
        // The following read from Settings.
        updateActivityStartsLoggingEnabled();
        updateForegroundServiceStartsLoggingEnabled();
    }

    private void loadDeviceConfigConstants() {
        mOnDeviceConfigChangedListener.onPropertiesChanged(
                DeviceConfig.getProperties(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER));
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
            TOP_TO_FGS_GRACE_DURATION = mParser.getDurationMillis(KEY_TOP_TO_FGS_GRACE_DURATION,
                    DEFAULT_TOP_TO_FGS_GRACE_DURATION);
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

    private void updateFgsNotificationDeferralExclusionTime() {
        mFgsNotificationDeferralExclusionTime = DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_DEFERRED_FGS_NOTIFICATION_EXCLUSION_TIME,
                /*default value*/ 2 * 60 * 1000L);
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

    private void updateFgsAllowOptOut() {
        mFgsAllowOptOut = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_FGS_ALLOW_OPT_OUT,
                DEFAULT_FGS_ALLOW_OPT_OUT);
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
                    ? DEFAULT_MAX_CACHED_PROCESSES : Integer.parseInt(maxCachedProcessesFlag))
                    : mOverrideMaxCachedProcesses;
        } catch (NumberFormatException e) {
            // Bad flag value from Phenotype, revert to default.
            Slog.e(TAG,
                    "Unable to parse flag for max_cached_processes: " + maxCachedProcessesFlag, e);
            CUR_MAX_CACHED_PROCESSES = DEFAULT_MAX_CACHED_PROCESSES;
        }
        CUR_MAX_EMPTY_PROCESSES = computeEmptyProcessLimit(CUR_MAX_CACHED_PROCESSES);

        // Note the trim levels do NOT depend on the override process limit, we want
        // to consider the same level the point where we do trimming regardless of any
        // additional enforced limit.
        final int rawMaxEmptyProcesses = computeEmptyProcessLimit(MAX_CACHED_PROCESSES);
        CUR_TRIM_EMPTY_PROCESSES = rawMaxEmptyProcesses/2;
        CUR_TRIM_CACHED_PROCESSES = (MAX_CACHED_PROCESSES-rawMaxEmptyProcesses)/3;
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
        pw.print("  "); pw.print(KEY_PUSH_MESSAGING_OVER_QUOTA_BEHAVIOR);
        pw.print("="); pw.println(mPushMessagingOverQuotaBehavior);
        pw.print("  "); pw.print(KEY_FGS_ALLOW_OPT_OUT);
        pw.print("="); pw.println(mFgsAllowOptOut);

        pw.println();
        if (mOverrideMaxCachedProcesses >= 0) {
            pw.print("  mOverrideMaxCachedProcesses="); pw.println(mOverrideMaxCachedProcesses);
        }
        pw.print("  CUR_MAX_CACHED_PROCESSES="); pw.println(CUR_MAX_CACHED_PROCESSES);
        pw.print("  CUR_MAX_EMPTY_PROCESSES="); pw.println(CUR_MAX_EMPTY_PROCESSES);
        pw.print("  CUR_TRIM_EMPTY_PROCESSES="); pw.println(CUR_TRIM_EMPTY_PROCESSES);
        pw.print("  CUR_TRIM_CACHED_PROCESSES="); pw.println(CUR_TRIM_CACHED_PROCESSES);
        pw.print("  OOMADJ_UPDATE_QUICK="); pw.println(OOMADJ_UPDATE_QUICK);
    }
}
