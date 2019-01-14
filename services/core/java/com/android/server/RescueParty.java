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

package com.android.server;

import static com.android.server.pm.PackageManagerServiceUtils.logCriticalInfo;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.FileUtils;
import android.os.RecoverySystem;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.util.ExceptionUtils;
import android.util.Log;
import android.util.MathUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.util.StatsLog;

import com.android.internal.util.ArrayUtils;

import java.io.File;

/**
 * Utilities to help rescue the system from crash loops. Callers are expected to
 * report boot events and persistent app crashes, and if they happen frequently
 * enough this class will slowly escalate through several rescue operations
 * before finally rebooting and prompting the user if they want to wipe data as
 * a last resort.
 *
 * @hide
 */
public class RescueParty {
    private static final String TAG = "RescueParty";

    private static final String PROP_ENABLE_RESCUE = "persist.sys.enable_rescue";
    private static final String PROP_DISABLE_RESCUE = "persist.sys.disable_rescue";
    private static final String PROP_RESCUE_LEVEL = "sys.rescue_level";
    private static final String PROP_RESCUE_BOOT_COUNT = "sys.rescue_boot_count";
    private static final String PROP_RESCUE_BOOT_START = "sys.rescue_boot_start";
    private static final String PROP_VIRTUAL_DEVICE = "ro.hardware.virtual_device";

    private static final int LEVEL_NONE = 0;
    private static final int LEVEL_RESET_SETTINGS_UNTRUSTED_DEFAULTS = 1;
    private static final int LEVEL_RESET_SETTINGS_UNTRUSTED_CHANGES = 2;
    private static final int LEVEL_RESET_SETTINGS_TRUSTED_DEFAULTS = 3;
    private static final int LEVEL_FACTORY_RESET = 4;

    /** Threshold for boot loops */
    private static final Threshold sBoot = new BootThreshold();
    /** Threshold for app crash loops */
    private static SparseArray<Threshold> sApps = new SparseArray<>();

    private static boolean isDisabled() {
        // Check if we're explicitly enabled for testing
        if (SystemProperties.getBoolean(PROP_ENABLE_RESCUE, false)) {
            return false;
        }

        // We're disabled on all engineering devices
        if (Build.IS_ENG) {
            Slog.v(TAG, "Disabled because of eng build");
            return true;
        }

        // We're disabled on userdebug devices connected over USB, since that's
        // a decent signal that someone is actively trying to debug the device,
        // or that it's in a lab environment.
        if (Build.IS_USERDEBUG && isUsbActive()) {
            Slog.v(TAG, "Disabled because of active USB connection");
            return true;
        }

        // One last-ditch check
        if (SystemProperties.getBoolean(PROP_DISABLE_RESCUE, false)) {
            Slog.v(TAG, "Disabled because of manual property");
            return true;
        }

        return false;
    }

    /**
     * Take note of a boot event. If we notice too many of these events
     * happening in rapid succession, we'll send out a rescue party.
     */
    public static void noteBoot(Context context) {
        if (isDisabled()) return;
        if (sBoot.incrementAndTest()) {
            sBoot.reset();
            incrementRescueLevel(sBoot.uid);
            executeRescueLevel(context);
        }
    }

    /**
     * Take note of a persistent app crash. If we notice too many of these
     * events happening in rapid succession, we'll send out a rescue party.
     */
    public static void notePersistentAppCrash(Context context, int uid) {
        if (isDisabled()) return;
        Threshold t = sApps.get(uid);
        if (t == null) {
            t = new AppThreshold(uid);
            sApps.put(uid, t);
        }
        if (t.incrementAndTest()) {
            t.reset();
            incrementRescueLevel(t.uid);
            executeRescueLevel(context);
        }
    }

    /**
     * Check if we're currently attempting to reboot for a factory reset.
     */
    public static boolean isAttemptingFactoryReset() {
        return SystemProperties.getInt(PROP_RESCUE_LEVEL, LEVEL_NONE) == LEVEL_FACTORY_RESET;
    }

    /**
     * Escalate to the next rescue level. After incrementing the level you'll
     * probably want to call {@link #executeRescueLevel(Context)}.
     */
    private static void incrementRescueLevel(int triggerUid) {
        final int level = MathUtils.constrain(
                SystemProperties.getInt(PROP_RESCUE_LEVEL, LEVEL_NONE) + 1,
                LEVEL_NONE, LEVEL_FACTORY_RESET);
        SystemProperties.set(PROP_RESCUE_LEVEL, Integer.toString(level));

        EventLogTags.writeRescueLevel(level, triggerUid);
        logCriticalInfo(Log.WARN, "Incremented rescue level to "
                + levelToString(level) + " triggered by UID " + triggerUid);
    }

    /**
     * Called when {@code SettingsProvider} has been published, which is a good
     * opportunity to reset any settings depending on our rescue level.
     */
    public static void onSettingsProviderPublished(Context context) {
        executeRescueLevel(context);
    }

    private static void executeRescueLevel(Context context) {
        final int level = SystemProperties.getInt(PROP_RESCUE_LEVEL, LEVEL_NONE);
        if (level == LEVEL_NONE) return;

        Slog.w(TAG, "Attempting rescue level " + levelToString(level));
        try {
            executeRescueLevelInternal(context, level);
            EventLogTags.writeRescueSuccess(level);
            logCriticalInfo(Log.DEBUG,
                    "Finished rescue level " + levelToString(level));
        } catch (Throwable t) {
            final String msg = ExceptionUtils.getCompleteMessage(t);
            EventLogTags.writeRescueFailure(level, msg);
            logCriticalInfo(Log.ERROR,
                    "Failed rescue level " + levelToString(level) + ": " + msg);
        }
    }

    private static void executeRescueLevelInternal(Context context, int level) throws Exception {
        StatsLog.write(StatsLog.RESCUE_PARTY_RESET_REPORTED, level);
        switch (level) {
            case LEVEL_RESET_SETTINGS_UNTRUSTED_DEFAULTS:
                resetAllSettings(context, Settings.RESET_MODE_UNTRUSTED_DEFAULTS);
                break;
            case LEVEL_RESET_SETTINGS_UNTRUSTED_CHANGES:
                resetAllSettings(context, Settings.RESET_MODE_UNTRUSTED_CHANGES);
                break;
            case LEVEL_RESET_SETTINGS_TRUSTED_DEFAULTS:
                resetAllSettings(context, Settings.RESET_MODE_TRUSTED_DEFAULTS);
                break;
            case LEVEL_FACTORY_RESET:
                RecoverySystem.rebootPromptAndWipeUserData(context, TAG);
                break;
        }
    }

    private static void resetAllSettings(Context context, int mode) throws Exception {
        // Try our best to reset all settings possible, and once finished
        // rethrow any exception that we encountered
        Exception res = null;
        final ContentResolver resolver = context.getContentResolver();
        try {
            Settings.Global.resetToDefaultsAsUser(resolver, null, mode, UserHandle.USER_SYSTEM);
        } catch (Throwable t) {
            res = new RuntimeException("Failed to reset global settings", t);
        }
        for (int userId : getAllUserIds()) {
            try {
                Settings.Secure.resetToDefaultsAsUser(resolver, null, mode, userId);
            } catch (Throwable t) {
                res = new RuntimeException("Failed to reset secure settings for " + userId, t);
            }
        }
        if (res != null) {
            throw res;
        }
    }

    /**
     * Threshold that can be triggered if a number of events occur within a
     * window of time.
     */
    private abstract static class Threshold {
        public abstract int getCount();
        public abstract void setCount(int count);
        public abstract long getStart();
        public abstract void setStart(long start);

        private final int uid;
        private final int triggerCount;
        private final long triggerWindow;

        public Threshold(int uid, int triggerCount, long triggerWindow) {
            this.uid = uid;
            this.triggerCount = triggerCount;
            this.triggerWindow = triggerWindow;
        }

        public void reset() {
            setCount(0);
            setStart(0);
        }

        /**
         * @return if this threshold has been triggered
         */
        public boolean incrementAndTest() {
            final long now = SystemClock.elapsedRealtime();
            final long window = now - getStart();
            if (window > triggerWindow) {
                setCount(1);
                setStart(now);
                return false;
            } else {
                int count = getCount() + 1;
                setCount(count);
                EventLogTags.writeRescueNote(uid, count, window);
                Slog.w(TAG, "Noticed " + count + " events for UID " + uid + " in last "
                        + (window / 1000) + " sec");
                return (count >= triggerCount);
            }
        }
    }

    /**
     * Specialization of {@link Threshold} for monitoring boot events. It stores
     * counters in system properties for robustness.
     */
    private static class BootThreshold extends Threshold {
        public BootThreshold() {
            // We're interested in 5 events in any 300 second period; this
            // window is super relaxed because booting can take a long time if
            // forced to dexopt things.
            super(android.os.Process.ROOT_UID, 5, 300 * DateUtils.SECOND_IN_MILLIS);
        }

        @Override
        public int getCount() {
            return SystemProperties.getInt(PROP_RESCUE_BOOT_COUNT, 0);
        }

        @Override
        public void setCount(int count) {
            SystemProperties.set(PROP_RESCUE_BOOT_COUNT, Integer.toString(count));
        }

        @Override
        public long getStart() {
            return SystemProperties.getLong(PROP_RESCUE_BOOT_START, 0);
        }

        @Override
        public void setStart(long start) {
            SystemProperties.set(PROP_RESCUE_BOOT_START, Long.toString(start));
        }
    }

    /**
     * Specialization of {@link Threshold} for monitoring app crashes. It stores
     * counters in memory.
     */
    private static class AppThreshold extends Threshold {
        private int count;
        private long start;

        public AppThreshold(int uid) {
            // We're interested in 5 events in any 30 second period; apps crash
            // pretty quickly so we can keep a tight leash on them.
            super(uid, 5, 30 * DateUtils.SECOND_IN_MILLIS);
        }

        @Override public int getCount() { return count; }
        @Override public void setCount(int count) { this.count = count; }
        @Override public long getStart() { return start; }
        @Override public void setStart(long start) { this.start = start; }
    }

    private static int[] getAllUserIds() {
        int[] userIds = { UserHandle.USER_SYSTEM };
        try {
            for (File file : FileUtils.listFilesOrEmpty(Environment.getDataSystemDeDirectory())) {
                try {
                    final int userId = Integer.parseInt(file.getName());
                    if (userId != UserHandle.USER_SYSTEM) {
                        userIds = ArrayUtils.appendInt(userIds, userId);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Throwable t) {
            Slog.w(TAG, "Trouble discovering users", t);
        }
        return userIds;
    }

    /**
     * Hacky test to check if the device has an active USB connection, which is
     * a good proxy for someone doing local development work.
     */
    private static boolean isUsbActive() {
        if (SystemProperties.getBoolean(PROP_VIRTUAL_DEVICE, false)) {
            Slog.v(TAG, "Assuming virtual device is connected over USB");
            return true;
        }
        try {
            final String state = FileUtils
                    .readTextFile(new File("/sys/class/android_usb/android0/state"), 128, "");
            return "CONFIGURED".equals(state.trim());
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to determine if device was on USB", t);
            return false;
        }
    }

    private static String levelToString(int level) {
        switch (level) {
            case LEVEL_NONE: return "NONE";
            case LEVEL_RESET_SETTINGS_UNTRUSTED_DEFAULTS: return "RESET_SETTINGS_UNTRUSTED_DEFAULTS";
            case LEVEL_RESET_SETTINGS_UNTRUSTED_CHANGES: return "RESET_SETTINGS_UNTRUSTED_CHANGES";
            case LEVEL_RESET_SETTINGS_TRUSTED_DEFAULTS: return "RESET_SETTINGS_TRUSTED_DEFAULTS";
            case LEVEL_FACTORY_RESET: return "FACTORY_RESET";
            default: return Integer.toString(level);
        }
    }
}
