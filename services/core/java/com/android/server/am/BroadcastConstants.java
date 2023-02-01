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

import android.annotation.IntDef;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.compat.annotation.Overridable;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import android.util.KeyValueListParser;
import android.util.Slog;
import android.util.TimeUtils;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Tunable parameters for broadcast dispatch policy
 */
public class BroadcastConstants {
    private static final String TAG = "BroadcastConstants";

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
            updateConstants();
        }
    }

    // A given constants instance is configured to observe specific keys from which
    // that instance's values are drawn.
    public BroadcastConstants(String settingsKey) {
        mSettingsKey = settingsKey;
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

        updateConstants();
    }

    private void updateConstants() {
        synchronized (mParser) {
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
     * Standard dumpsys support; invoked from BroadcastQueue dump
     */
    public void dump(PrintWriter pw) {
        synchronized (mParser) {
            pw.println();
            pw.print("  Broadcast parameters (key=");
            pw.print(mSettingsKey);
            pw.print(", observing=");
            pw.print(mSettingsObserver != null);
            pw.println("):");

            pw.print("    "); pw.print(KEY_TIMEOUT); pw.print(" = ");
            TimeUtils.formatDuration(TIMEOUT, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_SLOW_TIME); pw.print(" = ");
            TimeUtils.formatDuration(SLOW_TIME, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_DEFERRAL); pw.print(" = ");
            TimeUtils.formatDuration(DEFERRAL, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_DEFERRAL_DECAY_FACTOR); pw.print(" = ");
            pw.println(DEFERRAL_DECAY_FACTOR);

            pw.print("    "); pw.print(KEY_DEFERRAL_FLOOR); pw.print(" = ");
            TimeUtils.formatDuration(DEFERRAL_FLOOR, pw);

            pw.print("    "); pw.print(KEY_ALLOW_BG_ACTIVITY_START_TIMEOUT); pw.print(" = ");
            TimeUtils.formatDuration(ALLOW_BG_ACTIVITY_START_TIMEOUT, pw);
            pw.println();
        }
    }
}
