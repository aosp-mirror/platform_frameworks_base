/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.provider.DeviceConfig.NAMESPACE_ACTIVITY_MANAGER_NATIVE_BOOT;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.app.ActivityManager;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.compat.annotation.Overridable;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.SystemProperties;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.util.IndentingPrintWriter;
import android.util.KeyValueListParser;
import android.util.Slog;
import android.util.TimeUtils;

import dalvik.annotation.optimization.NeverCompile;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Tunable parameters for broadcast dispatch policy
 */
public class BroadcastConstants {
    private static final String TAG = "BroadcastConstants";

    // TODO: migrate remaining constants to be loaded from DeviceConfig
    // TODO: migrate fg/bg values into single constants instance

    // Value element names within the Settings record
    static final String KEY_TIMEOUT = "bcast_timeout";
    static final String KEY_SLOW_TIME = "bcast_slow_time";
    static final String KEY_DEFERRAL = "bcast_deferral";
    static final String KEY_DEFERRAL_DECAY_FACTOR = "bcast_deferral_decay_factor";
    static final String KEY_DEFERRAL_FLOOR = "bcast_deferral_floor";
    static final String KEY_ALLOW_BG_ACTIVITY_START_TIMEOUT =
            "bcast_allow_bg_activity_start_timeout";

    // All time intervals are in milliseconds
    private static final long DEFAULT_TIMEOUT = 10_000 * Build.HW_TIMEOUT_MULTIPLIER;
    private static final long DEFAULT_SLOW_TIME = 5_000 * Build.HW_TIMEOUT_MULTIPLIER;
    private static final long DEFAULT_DEFERRAL = 5_000 * Build.HW_TIMEOUT_MULTIPLIER;
    private static final float DEFAULT_DEFERRAL_DECAY_FACTOR = 0.75f;
    private static final long DEFAULT_DEFERRAL_FLOOR = 0;
    private static final long DEFAULT_ALLOW_BG_ACTIVITY_START_TIMEOUT =
            10_000 * Build.HW_TIMEOUT_MULTIPLIER;

    /**
     * Defer LOCKED_BOOT_COMPLETED and BOOT_COMPLETED broadcasts until the first time any process in
     * the UID is started.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = android.os.Build.VERSION_CODES.S_V2)
    @Overridable
    static final long DEFER_BOOT_COMPLETED_BROADCAST_CHANGE_ID = 203704822L;

    /**
     * Do not defer LOCKED_BOOT_COMPLETED and BOOT_COMPLETED broadcasts.
     */
    public static final int DEFER_BOOT_COMPLETED_BROADCAST_NONE = 0;
    /**
     * Defer all LOCKED_BOOT_COMPLETED and BOOT_COMPLETED broadcasts.
     */
    public static final int DEFER_BOOT_COMPLETED_BROADCAST_ALL = 1 << 0;
    /**
     * Defer LOCKED_BOOT_COMPLETED and BOOT_COMPLETED broadcasts if app is background restricted.
     */
    public static final int DEFER_BOOT_COMPLETED_BROADCAST_BACKGROUND_RESTRICTED_ONLY = 1 << 1;
    /**
     * Defer LOCKED_BOOT_COMPLETED and BOOT_COMPLETED broadcasts if app's targetSdkVersion is T
     * and above.
     */
    public static final int DEFER_BOOT_COMPLETED_BROADCAST_TARGET_T_ONLY = 1 << 2;

    /**
     * The list of DEFER_BOOT_COMPLETED_BROADCAST types.
     * If multiple flags are selected, all conditions must be met to defer the broadcast.
     * @hide
     */
    @IntDef(flag = true, prefix = { "DEFER_BOOT_COMPLETED_BROADCAST_" }, value = {
            DEFER_BOOT_COMPLETED_BROADCAST_NONE,
            DEFER_BOOT_COMPLETED_BROADCAST_ALL,
            DEFER_BOOT_COMPLETED_BROADCAST_BACKGROUND_RESTRICTED_ONLY,
            DEFER_BOOT_COMPLETED_BROADCAST_TARGET_T_ONLY,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DeferBootCompletedBroadcastType {}

    // All time constants are in milliseconds

    // Timeout period for this broadcast queue
    public long TIMEOUT = DEFAULT_TIMEOUT;
    // Handling time above which we declare that a broadcast recipient was "slow".  Any
    // value <= zero is interpreted as disabling broadcast deferral policy entirely.
    public long SLOW_TIME = DEFAULT_SLOW_TIME;
    // How long to initially defer broadcasts, if an app is slow to handle one
    public long DEFERRAL = DEFAULT_DEFERRAL;
    // Decay factor for successive broadcasts' deferral time
    public float DEFERRAL_DECAY_FACTOR = DEFAULT_DEFERRAL_DECAY_FACTOR;
    // Minimum that the deferral time can decay to until the backlog fully clears
    public long DEFERRAL_FLOOR = DEFAULT_DEFERRAL_FLOOR;
    // For a receiver that has been allowed to start background activities, how long after it
    // started its process can start a background activity.
    public long ALLOW_BG_ACTIVITY_START_TIMEOUT = DEFAULT_ALLOW_BG_ACTIVITY_START_TIMEOUT;

    /**
     * Flag indicating if we should use {@link BroadcastQueueModernImpl} instead
     * of the default {@link BroadcastQueueImpl}.
     */
    public boolean MODERN_QUEUE_ENABLED = DEFAULT_MODERN_QUEUE_ENABLED;
    private static final String KEY_MODERN_QUEUE_ENABLED = "modern_queue_enabled";
    private static final boolean DEFAULT_MODERN_QUEUE_ENABLED = true;

    /**
     * For {@link BroadcastQueueModernImpl}: Maximum dispatch parallelism
     * that we'll tolerate for ordinary broadcast dispatch.
     */
    public int MAX_RUNNING_PROCESS_QUEUES = DEFAULT_MAX_RUNNING_PROCESS_QUEUES;
    private static final String KEY_MAX_RUNNING_PROCESS_QUEUES = "bcast_max_running_process_queues";
    private static final int DEFAULT_MAX_RUNNING_PROCESS_QUEUES =
            ActivityManager.isLowRamDeviceStatic() ? 2 : 4;

    /**
     * For {@link BroadcastQueueModernImpl}: Additional running process queue parallelism beyond
     * {@link #MAX_RUNNING_PROCESS_QUEUES} for dispatch of "urgent" broadcasts.
     */
    public int EXTRA_RUNNING_URGENT_PROCESS_QUEUES = DEFAULT_EXTRA_RUNNING_URGENT_PROCESS_QUEUES;
    private static final String KEY_EXTRA_RUNNING_URGENT_PROCESS_QUEUES =
            "bcast_extra_running_urgent_process_queues";
    private static final int DEFAULT_EXTRA_RUNNING_URGENT_PROCESS_QUEUES = 1;

    /**
     * For {@link BroadcastQueueModernImpl}: Maximum number of consecutive urgent
     * broadcast dispatches allowed before letting broadcasts in lower priority queue
     * to be scheduled in order to avoid starvation.
     */
    public int MAX_CONSECUTIVE_URGENT_DISPATCHES = DEFAULT_MAX_CONSECUTIVE_URGENT_DISPATCHES;
    private static final String KEY_MAX_CONSECUTIVE_URGENT_DISPATCHES =
            "bcast_max_consecutive_urgent_dispatches";
    private static final int DEFAULT_MAX_CONSECUTIVE_URGENT_DISPATCHES = 3;

    /**
     * For {@link BroadcastQueueModernImpl}: Maximum number of consecutive normal
     * broadcast dispatches allowed before letting broadcasts in lower priority queue
     * to be scheduled in order to avoid starvation.
     */
    public int MAX_CONSECUTIVE_NORMAL_DISPATCHES = DEFAULT_MAX_CONSECUTIVE_NORMAL_DISPATCHES;
    private static final String KEY_MAX_CONSECUTIVE_NORMAL_DISPATCHES =
            "bcast_max_consecutive_normal_dispatches";
    private static final int DEFAULT_MAX_CONSECUTIVE_NORMAL_DISPATCHES = 10;

    /**
     * For {@link BroadcastQueueModernImpl}: Maximum number of active broadcasts
     * to dispatch to a "running" process queue before we retire them back to
     * being "runnable" to give other processes a chance to run.
     */
    public int MAX_RUNNING_ACTIVE_BROADCASTS = DEFAULT_MAX_RUNNING_ACTIVE_BROADCASTS;
    private static final String KEY_MAX_RUNNING_ACTIVE_BROADCASTS =
            "bcast_max_running_active_broadcasts";
    private static final int DEFAULT_MAX_RUNNING_ACTIVE_BROADCASTS =
            ActivityManager.isLowRamDeviceStatic() ? 8 : 16;

    /**
     * For {@link BroadcastQueueModernImpl}: Maximum number of active "blocking" broadcasts
     * to dispatch to a "running" System process queue before we retire them back to
     * being "runnable" to give other processes a chance to run. Here "blocking" refers to
     * whether or not we are going to block on the finishReceiver() to be called before moving
     * to the next broadcast.
     */
    public int MAX_CORE_RUNNING_BLOCKING_BROADCASTS = DEFAULT_MAX_CORE_RUNNING_BLOCKING_BROADCASTS;
    private static final String KEY_CORE_MAX_RUNNING_BLOCKING_BROADCASTS =
            "bcast_max_core_running_blocking_broadcasts";
    private static final int DEFAULT_MAX_CORE_RUNNING_BLOCKING_BROADCASTS =
            ActivityManager.isLowRamDeviceStatic() ? 8 : 16;

    /**
     * For {@link BroadcastQueueModernImpl}: Maximum number of active non-"blocking" broadcasts
     * to dispatch to a "running" System process queue before we retire them back to
     * being "runnable" to give other processes a chance to run. Here "blocking" refers to
     * whether or not we are going to block on the finishReceiver() to be called before moving
     * to the next broadcast.
     */
    public int MAX_CORE_RUNNING_NON_BLOCKING_BROADCASTS =
            DEFAULT_MAX_CORE_RUNNING_NON_BLOCKING_BROADCASTS;
    private static final String KEY_CORE_MAX_RUNNING_NON_BLOCKING_BROADCASTS =
            "bcast_max_core_running_non_blocking_broadcasts";
    private static final int DEFAULT_MAX_CORE_RUNNING_NON_BLOCKING_BROADCASTS =
            ActivityManager.isLowRamDeviceStatic() ? 32 : 64;

    /**
     * For {@link BroadcastQueueModernImpl}: Maximum number of pending
     * broadcasts to hold for a process before we ignore any delays that policy
     * might have applied to that process.
     */
    public int MAX_PENDING_BROADCASTS = DEFAULT_MAX_PENDING_BROADCASTS;
    private static final String KEY_MAX_PENDING_BROADCASTS = "bcast_max_pending_broadcasts";
    private static final int DEFAULT_MAX_PENDING_BROADCASTS =
            ActivityManager.isLowRamDeviceStatic() ? 128 : 256;

    /**
     * For {@link BroadcastQueueModernImpl}: Delay to apply to normal
     * broadcasts, giving a chance for debouncing of rapidly changing events.
     */
    public long DELAY_NORMAL_MILLIS = DEFAULT_DELAY_NORMAL_MILLIS;
    private static final String KEY_DELAY_NORMAL_MILLIS = "bcast_delay_normal_millis";
    private static final long DEFAULT_DELAY_NORMAL_MILLIS = +500;

    /**
     * For {@link BroadcastQueueModernImpl}: Delay to apply to broadcasts
     * targeting cached applications.
     */
    public long DELAY_CACHED_MILLIS = DEFAULT_DELAY_CACHED_MILLIS;
    private static final String KEY_DELAY_CACHED_MILLIS = "bcast_delay_cached_millis";
    private static final long DEFAULT_DELAY_CACHED_MILLIS = +120_000;

    /**
     * For {@link BroadcastQueueModernImpl}: Delay to apply to urgent
     * broadcasts, typically a negative value to indicate they should be
     * executed before most other pending broadcasts.
     */
    public long DELAY_URGENT_MILLIS = DEFAULT_DELAY_URGENT_MILLIS;
    private static final String KEY_DELAY_URGENT_MILLIS = "bcast_delay_urgent_millis";
    private static final long DEFAULT_DELAY_URGENT_MILLIS = -120_000;

    /**
     * For {@link BroadcastQueueModernImpl}: Maximum number of complete
     * historical broadcasts to retain for debugging purposes.
     */
    public int MAX_HISTORY_COMPLETE_SIZE = DEFAULT_MAX_HISTORY_COMPLETE_SIZE;
    private static final String KEY_MAX_HISTORY_COMPLETE_SIZE = "bcast_max_history_complete_size";
    private static final int DEFAULT_MAX_HISTORY_COMPLETE_SIZE =
            ActivityManager.isLowRamDeviceStatic() ? 64 : 256;

    /**
     * For {@link BroadcastQueueModernImpl}: Maximum number of summarized
     * historical broadcasts to retain for debugging purposes.
     */
    public int MAX_HISTORY_SUMMARY_SIZE = DEFAULT_MAX_HISTORY_SUMMARY_SIZE;
    private static final String KEY_MAX_HISTORY_SUMMARY_SIZE = "bcast_max_history_summary_size";
    private static final int DEFAULT_MAX_HISTORY_SUMMARY_SIZE =
            ActivityManager.isLowRamDeviceStatic() ? 256 : 1024;

    /**
     * For {@link BroadcastRecord}: Default to treating all broadcasts sent by
     * the system as be {@link BroadcastOptions#DEFERRAL_POLICY_UNTIL_ACTIVE}.
     */
    public boolean CORE_DEFER_UNTIL_ACTIVE = DEFAULT_CORE_DEFER_UNTIL_ACTIVE;
    private static final String KEY_CORE_DEFER_UNTIL_ACTIVE = "bcast_core_defer_until_active";
    private static final boolean DEFAULT_CORE_DEFER_UNTIL_ACTIVE = true;

    // Settings override tracking for this instance
    private String mSettingsKey;
    private SettingsObserver mSettingsObserver;
    private ContentResolver mResolver;
    private final KeyValueListParser mParser = new KeyValueListParser(',');

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettingsConstants();
        }
    }

    // A given constants instance is configured to observe specific keys from which
    // that instance's values are drawn.
    public BroadcastConstants(String settingsKey) {
        mSettingsKey = settingsKey;

        // Load initial values at least once before we start observing below
        updateDeviceConfigConstants();
    }

    /**
     * Spin up the observer lazily, since it can only happen once the settings provider
     * has been brought into service
     */
    public void startObserving(Handler handler, ContentResolver resolver) {
        mResolver = resolver;

        mSettingsObserver = new SettingsObserver(handler);
        mResolver.registerContentObserver(Settings.Global.getUriFor(mSettingsKey),
                false, mSettingsObserver);
        updateSettingsConstants();

        DeviceConfig.addOnPropertiesChangedListener(NAMESPACE_ACTIVITY_MANAGER_NATIVE_BOOT,
                new HandlerExecutor(handler), this::updateDeviceConfigConstants);
        updateDeviceConfigConstants();
    }

    public int getMaxRunningQueues() {
        return MAX_RUNNING_PROCESS_QUEUES + EXTRA_RUNNING_URGENT_PROCESS_QUEUES;
    }

    private void updateSettingsConstants() {
        synchronized (this) {
            try {
                mParser.setString(Settings.Global.getString(mResolver, mSettingsKey));
            } catch (IllegalArgumentException e) {
                Slog.e(TAG, "Bad broadcast settings in key '" + mSettingsKey + "'", e);
                return;
            }

            // Unspecified fields retain their current value rather than revert to default
            TIMEOUT = mParser.getLong(KEY_TIMEOUT, TIMEOUT);
            SLOW_TIME = mParser.getLong(KEY_SLOW_TIME, SLOW_TIME);
            DEFERRAL = mParser.getLong(KEY_DEFERRAL, DEFERRAL);
            DEFERRAL_DECAY_FACTOR = mParser.getFloat(KEY_DEFERRAL_DECAY_FACTOR,
                    DEFERRAL_DECAY_FACTOR);
            DEFERRAL_FLOOR = mParser.getLong(KEY_DEFERRAL_FLOOR, DEFERRAL_FLOOR);
            ALLOW_BG_ACTIVITY_START_TIMEOUT = mParser.getLong(KEY_ALLOW_BG_ACTIVITY_START_TIMEOUT,
                    ALLOW_BG_ACTIVITY_START_TIMEOUT);
        }
    }

    /**
     * Return the {@link SystemProperty} name for the given key in our
     * {@link DeviceConfig} namespace.
     */
    private @NonNull String propertyFor(@NonNull String key) {
        return "persist.device_config." + NAMESPACE_ACTIVITY_MANAGER_NATIVE_BOOT + "." + key;
    }

    /**
     * Return the {@link SystemProperty} name for the given key in our
     * {@link DeviceConfig} namespace, but with a different prefix that can be
     * used to locally override the {@link DeviceConfig} value.
     */
    private @NonNull String propertyOverrideFor(@NonNull String key) {
        return "persist.sys." + NAMESPACE_ACTIVITY_MANAGER_NATIVE_BOOT + "." + key;
    }

    private boolean getDeviceConfigBoolean(@NonNull String key, boolean def) {
        return SystemProperties.getBoolean(propertyOverrideFor(key),
                SystemProperties.getBoolean(propertyFor(key), def));
    }

    private int getDeviceConfigInt(@NonNull String key, int def) {
        return SystemProperties.getInt(propertyOverrideFor(key),
                SystemProperties.getInt(propertyFor(key), def));
    }

    private long getDeviceConfigLong(@NonNull String key, long def) {
        return SystemProperties.getLong(propertyOverrideFor(key),
                SystemProperties.getLong(propertyFor(key), def));
    }

    private void updateDeviceConfigConstants(@NonNull DeviceConfig.Properties properties) {
        updateDeviceConfigConstants();
    }

    /**
     * Since our values are stored in a "native boot" namespace, we load them
     * directly from the system properties.
     */
    private void updateDeviceConfigConstants() {
        synchronized (this) {
            MODERN_QUEUE_ENABLED = getDeviceConfigBoolean(KEY_MODERN_QUEUE_ENABLED,
                    DEFAULT_MODERN_QUEUE_ENABLED);
            MAX_RUNNING_PROCESS_QUEUES = getDeviceConfigInt(KEY_MAX_RUNNING_PROCESS_QUEUES,
                    DEFAULT_MAX_RUNNING_PROCESS_QUEUES);
            EXTRA_RUNNING_URGENT_PROCESS_QUEUES = getDeviceConfigInt(
                    KEY_EXTRA_RUNNING_URGENT_PROCESS_QUEUES,
                    DEFAULT_EXTRA_RUNNING_URGENT_PROCESS_QUEUES);
            MAX_CONSECUTIVE_URGENT_DISPATCHES = getDeviceConfigInt(
                    KEY_MAX_CONSECUTIVE_URGENT_DISPATCHES,
                    DEFAULT_MAX_CONSECUTIVE_URGENT_DISPATCHES);
            MAX_CONSECUTIVE_NORMAL_DISPATCHES = getDeviceConfigInt(
                    KEY_MAX_CONSECUTIVE_NORMAL_DISPATCHES,
                    DEFAULT_MAX_CONSECUTIVE_NORMAL_DISPATCHES);
            MAX_RUNNING_ACTIVE_BROADCASTS = getDeviceConfigInt(KEY_MAX_RUNNING_ACTIVE_BROADCASTS,
                    DEFAULT_MAX_RUNNING_ACTIVE_BROADCASTS);
            MAX_CORE_RUNNING_BLOCKING_BROADCASTS = getDeviceConfigInt(
                    KEY_CORE_MAX_RUNNING_BLOCKING_BROADCASTS,
                    DEFAULT_MAX_CORE_RUNNING_BLOCKING_BROADCASTS);
            MAX_CORE_RUNNING_NON_BLOCKING_BROADCASTS = getDeviceConfigInt(
                    KEY_CORE_MAX_RUNNING_NON_BLOCKING_BROADCASTS,
                    DEFAULT_MAX_CORE_RUNNING_NON_BLOCKING_BROADCASTS);
            MAX_PENDING_BROADCASTS = getDeviceConfigInt(KEY_MAX_PENDING_BROADCASTS,
                    DEFAULT_MAX_PENDING_BROADCASTS);
            DELAY_NORMAL_MILLIS = getDeviceConfigLong(KEY_DELAY_NORMAL_MILLIS,
                    DEFAULT_DELAY_NORMAL_MILLIS);
            DELAY_CACHED_MILLIS = getDeviceConfigLong(KEY_DELAY_CACHED_MILLIS,
                    DEFAULT_DELAY_CACHED_MILLIS);
            DELAY_URGENT_MILLIS = getDeviceConfigLong(KEY_DELAY_URGENT_MILLIS,
                    DEFAULT_DELAY_URGENT_MILLIS);
            MAX_HISTORY_COMPLETE_SIZE = getDeviceConfigInt(KEY_MAX_HISTORY_COMPLETE_SIZE,
                    DEFAULT_MAX_HISTORY_COMPLETE_SIZE);
            MAX_HISTORY_SUMMARY_SIZE = getDeviceConfigInt(KEY_MAX_HISTORY_SUMMARY_SIZE,
                    DEFAULT_MAX_HISTORY_SUMMARY_SIZE);
            CORE_DEFER_UNTIL_ACTIVE = getDeviceConfigBoolean(KEY_CORE_DEFER_UNTIL_ACTIVE,
                    DEFAULT_CORE_DEFER_UNTIL_ACTIVE);
        }

        // TODO: migrate BroadcastRecord to accept a BroadcastConstants
        BroadcastRecord.CORE_DEFER_UNTIL_ACTIVE = CORE_DEFER_UNTIL_ACTIVE;
    }

    /**
     * Standard dumpsys support; invoked from BroadcastQueue dump
     */
    @NeverCompile
    public void dump(@NonNull IndentingPrintWriter pw) {
        synchronized (this) {
            pw.print("Broadcast parameters (key=");
            pw.print(mSettingsKey);
            pw.print(", observing=");
            pw.print(mSettingsObserver != null);
            pw.println("):");
            pw.increaseIndent();
            pw.print(KEY_TIMEOUT, TimeUtils.formatDuration(TIMEOUT)).println();
            pw.print(KEY_SLOW_TIME, TimeUtils.formatDuration(SLOW_TIME)).println();
            pw.print(KEY_DEFERRAL, TimeUtils.formatDuration(DEFERRAL)).println();
            pw.print(KEY_DEFERRAL_DECAY_FACTOR, DEFERRAL_DECAY_FACTOR).println();
            pw.print(KEY_DEFERRAL_FLOOR, DEFERRAL_FLOOR).println();
            pw.print(KEY_ALLOW_BG_ACTIVITY_START_TIMEOUT,
                    TimeUtils.formatDuration(ALLOW_BG_ACTIVITY_START_TIMEOUT)).println();
            pw.decreaseIndent();
            pw.println();

            pw.print("Broadcast parameters (namespace=");
            pw.print(NAMESPACE_ACTIVITY_MANAGER_NATIVE_BOOT);
            pw.println("):");
            pw.increaseIndent();
            pw.print(KEY_MODERN_QUEUE_ENABLED, MODERN_QUEUE_ENABLED).println();
            pw.print(KEY_MAX_RUNNING_PROCESS_QUEUES, MAX_RUNNING_PROCESS_QUEUES).println();
            pw.print(KEY_MAX_RUNNING_ACTIVE_BROADCASTS, MAX_RUNNING_ACTIVE_BROADCASTS).println();
            pw.print(KEY_CORE_MAX_RUNNING_BLOCKING_BROADCASTS,
                    MAX_CORE_RUNNING_BLOCKING_BROADCASTS).println();
            pw.print(KEY_CORE_MAX_RUNNING_NON_BLOCKING_BROADCASTS,
                    MAX_CORE_RUNNING_NON_BLOCKING_BROADCASTS).println();
            pw.print(KEY_MAX_PENDING_BROADCASTS, MAX_PENDING_BROADCASTS).println();
            pw.print(KEY_DELAY_NORMAL_MILLIS,
                    TimeUtils.formatDuration(DELAY_NORMAL_MILLIS)).println();
            pw.print(KEY_DELAY_CACHED_MILLIS,
                    TimeUtils.formatDuration(DELAY_CACHED_MILLIS)).println();
            pw.print(KEY_DELAY_URGENT_MILLIS,
                    TimeUtils.formatDuration(DELAY_URGENT_MILLIS)).println();
            pw.print(KEY_MAX_HISTORY_COMPLETE_SIZE, MAX_HISTORY_COMPLETE_SIZE).println();
            pw.print(KEY_MAX_HISTORY_SUMMARY_SIZE, MAX_HISTORY_SUMMARY_SIZE).println();
            pw.print(KEY_MAX_CONSECUTIVE_URGENT_DISPATCHES,
                    MAX_CONSECUTIVE_URGENT_DISPATCHES).println();
            pw.print(KEY_MAX_CONSECUTIVE_NORMAL_DISPATCHES,
                    MAX_CONSECUTIVE_NORMAL_DISPATCHES).println();
            pw.print(KEY_CORE_DEFER_UNTIL_ACTIVE,
                    CORE_DEFER_UNTIL_ACTIVE).println();
            pw.decreaseIndent();
            pw.println();
        }
    }
}
