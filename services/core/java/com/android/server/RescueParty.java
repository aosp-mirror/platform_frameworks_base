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

import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.os.Build;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Process;
import android.os.RecoverySystem;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ExceptionUtils;
import android.util.Log;
import android.util.MathUtils;
import android.util.Slog;
import android.util.StatsLog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.server.PackageWatchdog.FailureReasons;
import com.android.server.PackageWatchdog.PackageHealthObserver;
import com.android.server.PackageWatchdog.PackageHealthObserverImpact;
import com.android.server.am.SettingsToPropertiesMapper;
import com.android.server.utils.FlagNamespaceUtils;

import java.io.File;
import java.util.Arrays;

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
    @VisibleForTesting
    static final String PROP_ENABLE_RESCUE = "persist.sys.enable_rescue";
    @VisibleForTesting
    static final int TRIGGER_COUNT = 5;
    @VisibleForTesting
    static final String PROP_RESCUE_LEVEL = "sys.rescue_level";
    @VisibleForTesting
    static final int LEVEL_NONE = 0;
    @VisibleForTesting
    static final int LEVEL_RESET_SETTINGS_UNTRUSTED_DEFAULTS = 1;
    @VisibleForTesting
    static final int LEVEL_RESET_SETTINGS_UNTRUSTED_CHANGES = 2;
    @VisibleForTesting
    static final int LEVEL_RESET_SETTINGS_TRUSTED_DEFAULTS = 3;
    @VisibleForTesting
    static final int LEVEL_FACTORY_RESET = 4;
    @VisibleForTesting
    static final String PROP_RESCUE_BOOT_COUNT = "sys.rescue_boot_count";
    @VisibleForTesting
    static final String TAG = "RescueParty";

    private static final String NAME = "rescue-party-observer";


    private static final String PROP_DISABLE_RESCUE = "persist.sys.disable_rescue";
    private static final String PROP_VIRTUAL_DEVICE = "ro.hardware.virtual_device";

    private static final int PERSISTENT_MASK = ApplicationInfo.FLAG_PERSISTENT
            | ApplicationInfo.FLAG_SYSTEM;

    /** Register the Rescue Party observer as a Package Watchdog health observer */
    public static void registerHealthObserver(Context context) {
        PackageWatchdog.getInstance(context).registerHealthObserver(
                RescuePartyObserver.getInstance(context));
    }

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
     * Check if we're currently attempting to reboot for a factory reset.
     */
    public static boolean isAttemptingFactoryReset() {
        return SystemProperties.getInt(PROP_RESCUE_LEVEL, LEVEL_NONE) == LEVEL_FACTORY_RESET;
    }

    /**
     * Called when {@code SettingsProvider} has been published, which is a good
     * opportunity to reset any settings depending on our rescue level.
     */
    public static void onSettingsProviderPublished(Context context) {
        handleNativeRescuePartyResets();
        executeRescueLevel(context);
    }

    @VisibleForTesting
    static long getElapsedRealtime() {
        return SystemClock.elapsedRealtime();
    }

    private static void handleNativeRescuePartyResets() {
        if (SettingsToPropertiesMapper.isNativeFlagsResetPerformed()) {
            FlagNamespaceUtils.resetDeviceConfig(Settings.RESET_MODE_TRUSTED_DEFAULTS,
                    Arrays.asList(SettingsToPropertiesMapper.getResetNativeCategories()));
        }
    }

    /**
     * Get the current rescue level.
     */
    private static int getRescueLevel() {
        return MathUtils.constrain(SystemProperties.getInt(PROP_RESCUE_LEVEL, LEVEL_NONE),
                LEVEL_NONE, LEVEL_FACTORY_RESET);
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
        FlagNamespaceUtils.addToKnownResetNamespaces(
                FlagNamespaceUtils.NAMESPACE_NO_PACKAGE);
    }

    private static int mapRescueLevelToUserImpact(int rescueLevel) {
        switch(rescueLevel) {
            case LEVEL_RESET_SETTINGS_UNTRUSTED_DEFAULTS:
            case LEVEL_RESET_SETTINGS_UNTRUSTED_CHANGES:
                return PackageHealthObserverImpact.USER_IMPACT_LOW;
            case LEVEL_RESET_SETTINGS_TRUSTED_DEFAULTS:
            case LEVEL_FACTORY_RESET:
                return PackageHealthObserverImpact.USER_IMPACT_HIGH;
            default:
                return PackageHealthObserverImpact.USER_IMPACT_NONE;
        }
    }

    private static int getPackageUid(Context context, String packageName) {
        try {
            return context.getPackageManager().getPackageUid(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            // Since UIDs are always >= 0, this value means the UID could not be determined.
            return -1;
        }
    }

    private static void resetAllSettings(Context context, int mode) throws Exception {
        // Try our best to reset all settings possible, and once finished
        // rethrow any exception that we encountered
        Exception res = null;
        final ContentResolver resolver = context.getContentResolver();
        try {
            FlagNamespaceUtils.resetDeviceConfig(mode);
        } catch (Exception e) {
            res = new RuntimeException("Failed to reset config settings", e);
        }
        try {
            Settings.Global.resetToDefaultsAsUser(resolver, null, mode, UserHandle.USER_SYSTEM);
        } catch (Exception e) {
            res = new RuntimeException("Failed to reset global settings", e);
        }
        for (int userId : getAllUserIds()) {
            try {
                Settings.Secure.resetToDefaultsAsUser(resolver, null, mode, userId);
            } catch (Exception e) {
                res = new RuntimeException("Failed to reset secure settings for " + userId, e);
            }
        }
        if (res != null) {
            throw res;
        }
    }

    /**
     * Handle mitigation action for package failures. This observer will be register to Package
     * Watchdog and will receive calls about package failures. This observer is persistent so it
     * may choose to mitigate failures for packages it has not explicitly asked to observe.
     */
    public static class RescuePartyObserver implements PackageHealthObserver {

        private Context mContext;

        @GuardedBy("RescuePartyObserver.class")
        static RescuePartyObserver sRescuePartyObserver;

        private RescuePartyObserver(Context context) {
            mContext = context;
        }

        /** Creates or gets singleton instance of RescueParty. */
        public static RescuePartyObserver getInstance(Context context) {
            synchronized (RescuePartyObserver.class) {
                if (sRescuePartyObserver == null) {
                    sRescuePartyObserver = new RescuePartyObserver(context);
                }
                return sRescuePartyObserver;
            }
        }

        @Override
        public int onHealthCheckFailed(@Nullable VersionedPackage failedPackage,
                @FailureReasons int failureReason) {
            if (failureReason == PackageWatchdog.FAILURE_REASON_APP_CRASH
                    || failureReason == PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING) {
                int rescueLevel = MathUtils.constrain(
                        SystemProperties.getInt(PROP_RESCUE_LEVEL, LEVEL_NONE) + 1,
                        LEVEL_NONE, LEVEL_FACTORY_RESET);
                return mapRescueLevelToUserImpact(rescueLevel);
            } else {
                return PackageHealthObserverImpact.USER_IMPACT_NONE;
            }
        }

        @Override
        public boolean execute(@Nullable VersionedPackage failedPackage,
                @FailureReasons int failureReason) {
            if (isDisabled()) {
                return false;
            }
            if (failureReason == PackageWatchdog.FAILURE_REASON_APP_CRASH
                    || failureReason == PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING) {
                int triggerUid = getPackageUid(mContext, failedPackage.getPackageName());
                incrementRescueLevel(triggerUid);
                executeRescueLevel(mContext);
                return true;
            } else {
                return false;
            }
        }

        @Override
        public boolean isPersistent() {
            return true;
        }

        @Override
        public boolean mayObservePackage(String packageName) {
            PackageManager pm = mContext.getPackageManager();
            try {
                // A package is a Mainline module if this is non-null
                if (pm.getModuleInfo(packageName, 0) != null) {
                    return true;
                }
                ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
                return (info.flags & PERSISTENT_MASK) == PERSISTENT_MASK;
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        }

        @Override
        public int onBootLoop() {
            if (isDisabled()) {
                return PackageHealthObserverImpact.USER_IMPACT_NONE;
            }
            return mapRescueLevelToUserImpact(getRescueLevel());
        }

        @Override
        public boolean executeBootLoopMitigation() {
            if (isDisabled()) {
                return false;
            }
            incrementRescueLevel(Process.ROOT_UID);
            executeRescueLevel(mContext);
            return true;
        }

        @Override
        public String getName() {
            return NAME;
        }
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
