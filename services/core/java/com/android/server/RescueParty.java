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

import static android.provider.DeviceConfig.Properties;

import static com.android.server.crashrecovery.CrashRecoveryUtils.logCrashRecoveryEvent;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.crashrecovery.flags.Flags;
import android.os.Build;
import android.os.Environment;
import android.os.FileUtils;
import android.os.PowerManager;
import android.os.RecoverySystem;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.sysprop.CrashRecoveryProperties;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.server.PackageWatchdog.FailureReasons;
import com.android.server.PackageWatchdog.PackageHealthObserver;
import com.android.server.PackageWatchdog.PackageHealthObserverImpact;
import com.android.server.am.SettingsToPropertiesMapper;
import com.android.server.crashrecovery.proto.CrashRecoveryStatsLog;

import java.io.File;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    static final int LEVEL_NONE = 0;
    @VisibleForTesting
    static final int LEVEL_RESET_SETTINGS_UNTRUSTED_DEFAULTS = 1;
    @VisibleForTesting
    static final int LEVEL_RESET_SETTINGS_UNTRUSTED_CHANGES = 2;
    @VisibleForTesting
    static final int LEVEL_RESET_SETTINGS_TRUSTED_DEFAULTS = 3;
    @VisibleForTesting
    static final int LEVEL_WARM_REBOOT = 4;
    @VisibleForTesting
    static final int LEVEL_FACTORY_RESET = 5;
    @VisibleForTesting
    static final int RESCUE_LEVEL_NONE = 0;
    @VisibleForTesting
    static final int RESCUE_LEVEL_SCOPED_DEVICE_CONFIG_RESET = 1;
    @VisibleForTesting
    static final int RESCUE_LEVEL_ALL_DEVICE_CONFIG_RESET = 2;
    @VisibleForTesting
    static final int RESCUE_LEVEL_WARM_REBOOT = 3;
    @VisibleForTesting
    static final int RESCUE_LEVEL_RESET_SETTINGS_UNTRUSTED_DEFAULTS = 4;
    @VisibleForTesting
    static final int RESCUE_LEVEL_RESET_SETTINGS_UNTRUSTED_CHANGES = 5;
    @VisibleForTesting
    static final int RESCUE_LEVEL_RESET_SETTINGS_TRUSTED_DEFAULTS = 6;
    @VisibleForTesting
    static final int RESCUE_LEVEL_FACTORY_RESET = 7;

    @IntDef(prefix = { "RESCUE_LEVEL_" }, value = {
        RESCUE_LEVEL_NONE,
        RESCUE_LEVEL_SCOPED_DEVICE_CONFIG_RESET,
        RESCUE_LEVEL_ALL_DEVICE_CONFIG_RESET,
        RESCUE_LEVEL_WARM_REBOOT,
        RESCUE_LEVEL_RESET_SETTINGS_UNTRUSTED_DEFAULTS,
        RESCUE_LEVEL_RESET_SETTINGS_UNTRUSTED_CHANGES,
        RESCUE_LEVEL_RESET_SETTINGS_TRUSTED_DEFAULTS,
        RESCUE_LEVEL_FACTORY_RESET
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface RescueLevels {}

    @VisibleForTesting
    static final String RESCUE_NON_REBOOT_LEVEL_LIMIT = "persist.sys.rescue_non_reboot_level_limit";
    @VisibleForTesting
    static final int DEFAULT_RESCUE_NON_REBOOT_LEVEL_LIMIT = RESCUE_LEVEL_WARM_REBOOT - 1;
    @VisibleForTesting
    static final String TAG = "RescueParty";
    @VisibleForTesting
    static final long DEFAULT_OBSERVING_DURATION_MS = TimeUnit.DAYS.toMillis(2);
    @VisibleForTesting
    static final int DEVICE_CONFIG_RESET_MODE = Settings.RESET_MODE_TRUSTED_DEFAULTS;
    // The DeviceConfig namespace containing all RescueParty switches.
    @VisibleForTesting
    static final String NAMESPACE_CONFIGURATION = "configuration";
    @VisibleForTesting
    static final String NAMESPACE_TO_PACKAGE_MAPPING_FLAG =
            "namespace_to_package_mapping";
    @VisibleForTesting
    static final long DEFAULT_FACTORY_RESET_THROTTLE_DURATION_MIN = 1440;

    private static final String NAME = "rescue-party-observer";

    private static final String PROP_DISABLE_RESCUE = "persist.sys.disable_rescue";
    private static final String PROP_VIRTUAL_DEVICE = "ro.hardware.virtual_device";
    private static final String PROP_DEVICE_CONFIG_DISABLE_FLAG =
            "persist.device_config.configuration.disable_rescue_party";
    private static final String PROP_DISABLE_FACTORY_RESET_FLAG =
            "persist.device_config.configuration.disable_rescue_party_factory_reset";
    private static final String PROP_THROTTLE_DURATION_MIN_FLAG =
            "persist.device_config.configuration.rescue_party_throttle_duration_min";

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

        // We're disabled if the DeviceConfig disable flag is set to true.
        // This is in case that an emergency rollback of the feature is needed.
        if (SystemProperties.getBoolean(PROP_DEVICE_CONFIG_DISABLE_FLAG, false)) {
            Slog.v(TAG, "Disabled because of DeviceConfig flag");
            return true;
        }

        // We're disabled on all engineering devices
        if (Build.TYPE.equals("eng")) {
            Slog.v(TAG, "Disabled because of eng build");
            return true;
        }

        // We're disabled on userdebug devices connected over USB, since that's
        // a decent signal that someone is actively trying to debug the device,
        // or that it's in a lab environment.
        if (Build.TYPE.equals("userdebug") && isUsbActive()) {
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
     * Check if we're currently attempting to reboot for a factory reset. This method must
     * return true if RescueParty tries to reboot early during a boot loop, since the device
     * will not be fully booted at this time.
     */
    public static boolean isRecoveryTriggeredReboot() {
        return isFactoryResetPropertySet() || isRebootPropertySet();
    }

    static boolean isFactoryResetPropertySet() {
        return CrashRecoveryProperties.attemptingFactoryReset().orElse(false);
    }

    static boolean isRebootPropertySet() {
        return CrashRecoveryProperties.attemptingReboot().orElse(false);
    }

    protected static long getLastFactoryResetTimeMs() {
        return CrashRecoveryProperties.lastFactoryResetTimeMs().orElse(0L);
    }

    protected static int getMaxRescueLevelAttempted() {
        return CrashRecoveryProperties.maxRescueLevelAttempted().orElse(LEVEL_NONE);
    }

    protected static void setFactoryResetProperty(boolean value) {
        CrashRecoveryProperties.attemptingFactoryReset(value);
    }
    protected static void setRebootProperty(boolean value) {
        CrashRecoveryProperties.attemptingReboot(value);
    }

    protected static void setLastFactoryResetTimeMs(long value) {
        CrashRecoveryProperties.lastFactoryResetTimeMs(value);
    }

    protected static void setMaxRescueLevelAttempted(int level) {
        CrashRecoveryProperties.maxRescueLevelAttempted(level);
    }

    /**
     * Called when {@code SettingsProvider} has been published, which is a good
     * opportunity to reset any settings depending on our rescue level.
     */
    public static void onSettingsProviderPublished(Context context) {
        if (!Flags.deprecateFlagsAndSettingsResets()) {
            handleNativeRescuePartyResets();
            ContentResolver contentResolver = context.getContentResolver();
            DeviceConfig.setMonitorCallback(
                    contentResolver,
                    Executors.newSingleThreadExecutor(),
                    new RescuePartyMonitorCallback(context));
        }
    }


    /**
     * Called when {@code RollbackManager} performs Mainline module rollbacks,
     * to avoid rolled back modules consuming flag values only expected to work
     * on modules of newer versions.
     */
    public static void resetDeviceConfigForPackages(List<String> packageNames) {
        if (!Flags.deprecateFlagsAndSettingsResets()) {
            if (packageNames == null) {
                return;
            }
            Set<String> namespacesToReset = new ArraySet<String>();
            Iterator<String> it = packageNames.iterator();
            RescuePartyObserver rescuePartyObserver = RescuePartyObserver.getInstanceIfCreated();
            // Get runtime package to namespace mapping if created.
            if (rescuePartyObserver != null) {
                while (it.hasNext()) {
                    String packageName = it.next();
                    Set<String> runtimeAffectedNamespaces =
                            rescuePartyObserver.getAffectedNamespaceSet(packageName);
                    if (runtimeAffectedNamespaces != null) {
                        namespacesToReset.addAll(runtimeAffectedNamespaces);
                    }
                }
            }
            // Get preset package to namespace mapping if created.
            Set<String> presetAffectedNamespaces = getPresetNamespacesForPackages(
                    packageNames);
            if (presetAffectedNamespaces != null) {
                namespacesToReset.addAll(presetAffectedNamespaces);
            }

            // Clear flags under the namespaces mapped to these packages.
            // Using setProperties since DeviceConfig.resetToDefaults bans the current flag set.
            Iterator<String> namespaceIt = namespacesToReset.iterator();
            while (namespaceIt.hasNext()) {
                String namespaceToReset = namespaceIt.next();
                Properties properties = new Properties.Builder(namespaceToReset).build();
                try {
                    if (!DeviceConfig.setProperties(properties)) {
                        logCrashRecoveryEvent(Log.ERROR, "Failed to clear properties under "
                            + namespaceToReset
                            + ". Running `device_config get_sync_disabled_for_tests` will confirm"
                            + " if config-bulk-update is enabled.");
                    }
                } catch (DeviceConfig.BadConfigException exception) {
                    logCrashRecoveryEvent(Log.WARN, "namespace " + namespaceToReset
                            + " is already banned, skip reset.");
                }
            }
        }
    }

    private static Set<String> getPresetNamespacesForPackages(List<String> packageNames) {
        Set<String> resultSet = new ArraySet<String>();
        if (!Flags.deprecateFlagsAndSettingsResets()) {
            try {
                String flagVal = DeviceConfig.getString(NAMESPACE_CONFIGURATION,
                        NAMESPACE_TO_PACKAGE_MAPPING_FLAG, "");
                String[] mappingEntries = flagVal.split(",");
                for (int i = 0; i < mappingEntries.length; i++) {
                    if (TextUtils.isEmpty(mappingEntries[i])) {
                        continue;
                    }
                    String[] splitEntry = mappingEntries[i].split(":");
                    if (splitEntry.length != 2) {
                        throw new RuntimeException("Invalid mapping entry: " + mappingEntries[i]);
                    }
                    String namespace = splitEntry[0];
                    String packageName = splitEntry[1];

                    if (packageNames.contains(packageName)) {
                        resultSet.add(namespace);
                    }
                }
            } catch (Exception e) {
                resultSet.clear();
                Slog.e(TAG, "Failed to read preset package to namespaces mapping.", e);
            } finally {
                return resultSet;
            }
        } else {
            return resultSet;
        }
    }

    @VisibleForTesting
    static long getElapsedRealtime() {
        return SystemClock.elapsedRealtime();
    }

    private static class RescuePartyMonitorCallback implements DeviceConfig.MonitorCallback {
        Context mContext;

        RescuePartyMonitorCallback(Context context) {
            this.mContext = context;
        }

        public void onNamespaceUpdate(@NonNull String updatedNamespace) {
            if (!Flags.deprecateFlagsAndSettingsResets()) {
                startObservingPackages(mContext, updatedNamespace);
            }
        }

        public void onDeviceConfigAccess(@NonNull String callingPackage,
                @NonNull String namespace) {

            if (!Flags.deprecateFlagsAndSettingsResets()) {
                RescuePartyObserver.getInstance(mContext).recordDeviceConfigAccess(
                        callingPackage,
                        namespace);
            }
        }
    }

    private static void startObservingPackages(Context context, @NonNull String updatedNamespace) {
        if (!Flags.deprecateFlagsAndSettingsResets()) {
            RescuePartyObserver rescuePartyObserver = RescuePartyObserver.getInstance(context);
            Set<String> callingPackages = rescuePartyObserver.getCallingPackagesSet(
                    updatedNamespace);
            if (callingPackages == null) {
                return;
            }
            List<String> callingPackageList = new ArrayList<>();
            callingPackageList.addAll(callingPackages);
            Slog.i(TAG, "Starting to observe: " + callingPackageList + ", updated namespace: "
                    + updatedNamespace);
            PackageWatchdog.getInstance(context).startObservingHealth(
                    rescuePartyObserver,
                    callingPackageList,
                    DEFAULT_OBSERVING_DURATION_MS);
        }
    }

    private static void handleNativeRescuePartyResets() {
        if (!Flags.deprecateFlagsAndSettingsResets()) {
            if (SettingsToPropertiesMapper.isNativeFlagsResetPerformed()) {
                String[] resetNativeCategories =
                        SettingsToPropertiesMapper.getResetNativeCategories();
                for (int i = 0; i < resetNativeCategories.length; i++) {
                    // Don't let RescueParty reset the namespace for RescueParty switches.
                    if (NAMESPACE_CONFIGURATION.equals(resetNativeCategories[i])) {
                        continue;
                    }
                    DeviceConfig.resetToDefaults(DEVICE_CONFIG_RESET_MODE,
                            resetNativeCategories[i]);
                }
            }
        }
    }

    private static int getMaxRescueLevel(boolean mayPerformReboot) {
        if (Flags.recoverabilityDetection()) {
            if (!mayPerformReboot
                    || SystemProperties.getBoolean(PROP_DISABLE_FACTORY_RESET_FLAG, false)) {
                return SystemProperties.getInt(RESCUE_NON_REBOOT_LEVEL_LIMIT,
                        DEFAULT_RESCUE_NON_REBOOT_LEVEL_LIMIT);
            }
            return RESCUE_LEVEL_FACTORY_RESET;
        } else {
            if (!mayPerformReboot
                    || SystemProperties.getBoolean(PROP_DISABLE_FACTORY_RESET_FLAG, false)) {
                return LEVEL_RESET_SETTINGS_TRUSTED_DEFAULTS;
            }
            return LEVEL_FACTORY_RESET;
        }
    }

    private static int getMaxRescueLevel() {
        if (!SystemProperties.getBoolean(PROP_DISABLE_FACTORY_RESET_FLAG, false)) {
            return Level.factoryReset();
        }
        return Level.reboot();
    }

    /**
     * Get the rescue level to perform if this is the n-th attempt at mitigating failure.
     *
     * @param mitigationCount: the mitigation attempt number (1 = first attempt etc.)
     * @param mayPerformReboot: whether or not a reboot and factory reset may be performed
     *                          for the given failure.
     * @return the rescue level for the n-th mitigation attempt.
     */
    private static int getRescueLevel(int mitigationCount, boolean mayPerformReboot) {
        if (!Flags.deprecateFlagsAndSettingsResets()) {
            if (mitigationCount == 1) {
                return LEVEL_RESET_SETTINGS_UNTRUSTED_DEFAULTS;
            } else if (mitigationCount == 2) {
                return LEVEL_RESET_SETTINGS_UNTRUSTED_CHANGES;
            } else if (mitigationCount == 3) {
                return LEVEL_RESET_SETTINGS_TRUSTED_DEFAULTS;
            } else if (mitigationCount == 4) {
                return Math.min(getMaxRescueLevel(mayPerformReboot), LEVEL_WARM_REBOOT);
            } else if (mitigationCount >= 5) {
                return Math.min(getMaxRescueLevel(mayPerformReboot), LEVEL_FACTORY_RESET);
            } else {
                Slog.w(TAG, "Expected positive mitigation count, was " + mitigationCount);
                return LEVEL_NONE;
            }
        } else {
            if (mitigationCount == 1) {
                return Level.reboot();
            } else if (mitigationCount >= 2) {
                return Math.min(getMaxRescueLevel(), Level.factoryReset());
            } else {
                Slog.w(TAG, "Expected positive mitigation count, was " + mitigationCount);
                return LEVEL_NONE;
            }
        }
    }

    /**
     * Get the rescue level to perform if this is the n-th attempt at mitigating failure.
     * When failedPackage is null then 1st and 2nd mitigation counts are redundant (scoped and
     * all device config reset). Behaves as if one mitigation attempt was already done.
     *
     * @param mitigationCount the mitigation attempt number (1 = first attempt etc.).
     * @param mayPerformReboot whether or not a reboot and factory reset may be performed
     * for the given failure.
     * @param failedPackage in case of bootloop this is null.
     * @return the rescue level for the n-th mitigation attempt.
     */
    private static @RescueLevels int getRescueLevel(int mitigationCount, boolean mayPerformReboot,
            @Nullable VersionedPackage failedPackage) {
        // Skipping RESCUE_LEVEL_SCOPED_DEVICE_CONFIG_RESET since it's not defined without a failed
        // package.
        if (failedPackage == null && mitigationCount > 0) {
            mitigationCount += 1;
        }
        if (mitigationCount == 1) {
            return RESCUE_LEVEL_SCOPED_DEVICE_CONFIG_RESET;
        } else if (mitigationCount == 2) {
            return RESCUE_LEVEL_ALL_DEVICE_CONFIG_RESET;
        } else if (mitigationCount == 3) {
            return Math.min(getMaxRescueLevel(mayPerformReboot), RESCUE_LEVEL_WARM_REBOOT);
        } else if (mitigationCount == 4) {
            return Math.min(getMaxRescueLevel(mayPerformReboot),
                    RESCUE_LEVEL_RESET_SETTINGS_UNTRUSTED_DEFAULTS);
        } else if (mitigationCount == 5) {
            return Math.min(getMaxRescueLevel(mayPerformReboot),
                    RESCUE_LEVEL_RESET_SETTINGS_UNTRUSTED_CHANGES);
        } else if (mitigationCount == 6) {
            return Math.min(getMaxRescueLevel(mayPerformReboot),
                    RESCUE_LEVEL_RESET_SETTINGS_TRUSTED_DEFAULTS);
        } else if (mitigationCount >= 7) {
            return Math.min(getMaxRescueLevel(mayPerformReboot), RESCUE_LEVEL_FACTORY_RESET);
        } else {
            return RESCUE_LEVEL_NONE;
        }
    }

    /**
     * Get the rescue level to perform if this is the n-th attempt at mitigating failure.
     *
     * @param mitigationCount the mitigation attempt number (1 = first attempt etc.).
     * @return the rescue level for the n-th mitigation attempt.
     */
    private static @RescueLevels int getRescueLevel(int mitigationCount) {
        if (mitigationCount == 1) {
            return Level.reboot();
        } else if (mitigationCount >= 2) {
            return Math.min(getMaxRescueLevel(), Level.factoryReset());
        } else {
            return Level.none();
        }
    }

    private static void executeRescueLevel(Context context, @Nullable String failedPackage,
            int level) {
        Slog.w(TAG, "Attempting rescue level " + levelToString(level));
        try {
            executeRescueLevelInternal(context, level, failedPackage);
            EventLogTags.writeRescueSuccess(level);
            String successMsg = "Finished rescue level " + levelToString(level);
            if (!TextUtils.isEmpty(failedPackage)) {
                successMsg += " for package " + failedPackage;
            }
            logCrashRecoveryEvent(Log.DEBUG, successMsg);
        } catch (Throwable t) {
            logRescueException(level, failedPackage, t);
        }
    }

    private static void executeRescueLevelInternal(Context context, int level, @Nullable
            String failedPackage) throws Exception {
        if (Flags.recoverabilityDetection()) {
            executeRescueLevelInternalNew(context, level, failedPackage);
        } else {
            executeRescueLevelInternalOld(context, level, failedPackage);
        }
    }

    private static void executeRescueLevelInternalOld(Context context, int level, @Nullable
            String failedPackage) throws Exception {
        CrashRecoveryStatsLog.write(CrashRecoveryStatsLog.RESCUE_PARTY_RESET_REPORTED,
                level, levelToString(level));
        // Try our best to reset all settings possible, and once finished
        // rethrow any exception that we encountered
        Exception res = null;
        switch (level) {
            case LEVEL_RESET_SETTINGS_UNTRUSTED_DEFAULTS:
                break;
            case LEVEL_RESET_SETTINGS_UNTRUSTED_CHANGES:
                break;
            case LEVEL_RESET_SETTINGS_TRUSTED_DEFAULTS:
                break;
            case LEVEL_WARM_REBOOT:
                executeWarmReboot(context, level, failedPackage);
                break;
            case LEVEL_FACTORY_RESET:
                // Before the completion of Reboot, if any crash happens then PackageWatchdog
                // escalates to next level i.e. factory reset, as they happen in separate threads.
                // Adding a check to prevent factory reset to execute before above reboot completes.
                // Note: this reboot property is not persistent resets after reboot is completed.
                if (isRebootPropertySet()) {
                    return;
                }
                executeFactoryReset(context, level, failedPackage);
                break;
        }

        if (res != null) {
            throw res;
        }
    }

    private static void executeRescueLevelInternalNew(Context context, @RescueLevels int level,
            @Nullable String failedPackage) throws Exception {
        CrashRecoveryStatsLog.write(CrashRecoveryStatsLog.RESCUE_PARTY_RESET_REPORTED,
                level, levelToString(level));
        switch (level) {
            case RESCUE_LEVEL_SCOPED_DEVICE_CONFIG_RESET:
                break;
            case RESCUE_LEVEL_ALL_DEVICE_CONFIG_RESET:
                break;
            case RESCUE_LEVEL_WARM_REBOOT:
                executeWarmReboot(context, level, failedPackage);
                break;
            case RESCUE_LEVEL_RESET_SETTINGS_UNTRUSTED_DEFAULTS:
                if (!Flags.deprecateFlagsAndSettingsResets()) {
                    resetAllSettingsIfNecessary(context, Settings.RESET_MODE_UNTRUSTED_DEFAULTS,
                            level);
                }
                break;
            case RESCUE_LEVEL_RESET_SETTINGS_UNTRUSTED_CHANGES:
                if (!Flags.deprecateFlagsAndSettingsResets()) {
                    resetAllSettingsIfNecessary(context, Settings.RESET_MODE_UNTRUSTED_CHANGES,
                            level);
                }
                break;
            case RESCUE_LEVEL_RESET_SETTINGS_TRUSTED_DEFAULTS:
                if (!Flags.deprecateFlagsAndSettingsResets()) {
                    resetAllSettingsIfNecessary(context, Settings.RESET_MODE_TRUSTED_DEFAULTS,
                            level);
                }
                break;
            case RESCUE_LEVEL_FACTORY_RESET:
                // Before the completion of Reboot, if any crash happens then PackageWatchdog
                // escalates to next level i.e. factory reset, as they happen in separate threads.
                // Adding a check to prevent factory reset to execute before above reboot completes.
                // Note: this reboot property is not persistent resets after reboot is completed.
                if (isRebootPropertySet()) {
                    return;
                }
                executeFactoryReset(context, level, failedPackage);
                break;
        }
    }

    private static void executeWarmReboot(Context context, int level,
            @Nullable String failedPackage) {
        if (Flags.deprecateFlagsAndSettingsResets()) {
            if (shouldThrottleReboot()) {
                return;
            }
        }

        // Request the reboot from a separate thread to avoid deadlock on PackageWatchdog
        // when device shutting down.
        setRebootProperty(true);
        Runnable runnable = () -> {
            try {
                PowerManager pm = context.getSystemService(PowerManager.class);
                if (pm != null) {
                    pm.reboot(TAG);
                }
            } catch (Throwable t) {
                logRescueException(level, failedPackage, t);
            }
        };
        Thread thread = new Thread(runnable);
        thread.start();
    }

    private static void executeFactoryReset(Context context, int level,
            @Nullable String failedPackage) {
        if (Flags.deprecateFlagsAndSettingsResets()) {
            if (shouldThrottleReboot()) {
                return;
            }
        }
        setFactoryResetProperty(true);
        long now = System.currentTimeMillis();
        setLastFactoryResetTimeMs(now);
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                try {
                    RecoverySystem.rebootPromptAndWipeUserData(context, TAG);
                } catch (Throwable t) {
                    logRescueException(level, failedPackage, t);
                }
            }
        };
        Thread thread = new Thread(runnable);
        thread.start();
    }


    private static String getCompleteMessage(Throwable t) {
        final StringBuilder builder = new StringBuilder();
        builder.append(t.getMessage());
        while ((t = t.getCause()) != null) {
            builder.append(": ").append(t.getMessage());
        }
        return builder.toString();
    }

    private static void logRescueException(int level, @Nullable String failedPackageName,
            Throwable t) {
        final String msg = getCompleteMessage(t);
        EventLogTags.writeRescueFailure(level, msg);
        String failureMsg = "Failed rescue level " + levelToString(level);
        if (!TextUtils.isEmpty(failedPackageName)) {
            failureMsg += " for package " + failedPackageName;
        }
        logCrashRecoveryEvent(Log.ERROR, failureMsg + ": " + msg);
    }

    private static int mapRescueLevelToUserImpact(int rescueLevel) {
        if (Flags.recoverabilityDetection()) {
            switch (rescueLevel) {
                case RESCUE_LEVEL_SCOPED_DEVICE_CONFIG_RESET:
                    return PackageHealthObserverImpact.USER_IMPACT_LEVEL_10;
                case RESCUE_LEVEL_ALL_DEVICE_CONFIG_RESET:
                    return PackageHealthObserverImpact.USER_IMPACT_LEVEL_40;
                case RESCUE_LEVEL_WARM_REBOOT:
                    return PackageHealthObserverImpact.USER_IMPACT_LEVEL_50;
                case RESCUE_LEVEL_RESET_SETTINGS_UNTRUSTED_DEFAULTS:
                    return PackageHealthObserverImpact.USER_IMPACT_LEVEL_71;
                case RESCUE_LEVEL_RESET_SETTINGS_UNTRUSTED_CHANGES:
                    return PackageHealthObserverImpact.USER_IMPACT_LEVEL_75;
                case RESCUE_LEVEL_RESET_SETTINGS_TRUSTED_DEFAULTS:
                    return PackageHealthObserverImpact.USER_IMPACT_LEVEL_80;
                case RESCUE_LEVEL_FACTORY_RESET:
                    return PackageHealthObserverImpact.USER_IMPACT_LEVEL_100;
                default:
                    return PackageHealthObserverImpact.USER_IMPACT_LEVEL_0;
            }
        } else {
            switch (rescueLevel) {
                case LEVEL_RESET_SETTINGS_UNTRUSTED_DEFAULTS:
                case LEVEL_RESET_SETTINGS_UNTRUSTED_CHANGES:
                    return PackageHealthObserverImpact.USER_IMPACT_LEVEL_10;
                case LEVEL_RESET_SETTINGS_TRUSTED_DEFAULTS:
                case LEVEL_WARM_REBOOT:
                    return PackageHealthObserverImpact.USER_IMPACT_LEVEL_50;
                case LEVEL_FACTORY_RESET:
                    return PackageHealthObserverImpact.USER_IMPACT_LEVEL_100;
                default:
                    return PackageHealthObserverImpact.USER_IMPACT_LEVEL_0;
            }
        }
    }

    private static void resetAllSettingsIfNecessary(Context context, int mode,
            int level) throws Exception {
        if (!Flags.deprecateFlagsAndSettingsResets()) {
            // No need to reset Settings again if they are already reset in the current level once.
            if (getMaxRescueLevelAttempted() >= level) {
                return;
            }
            setMaxRescueLevelAttempted(level);
            // Try our best to reset all settings possible, and once finished
            // rethrow any exception that we encountered
            Exception res = null;
            final ContentResolver resolver = context.getContentResolver();
            try {
                Settings.Global.resetToDefaultsAsUser(resolver, null, mode,
                        UserHandle.SYSTEM.getIdentifier());
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
    }

    /**
     * Handle mitigation action for package failures. This observer will be register to Package
     * Watchdog and will receive calls about package failures. This observer is persistent so it
     * may choose to mitigate failures for packages it has not explicitly asked to observe.
     */
    public static class RescuePartyObserver implements PackageHealthObserver {

        private final Context mContext;
        private final Map<String, Set<String>> mCallingPackageNamespaceSetMap = new HashMap<>();
        private final Map<String, Set<String>> mNamespaceCallingPackageSetMap = new HashMap<>();

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

        /** Gets singleton instance. It returns null if the instance is not created yet.*/
        @Nullable
        public static RescuePartyObserver getInstanceIfCreated() {
            synchronized (RescuePartyObserver.class) {
                return sRescuePartyObserver;
            }
        }

        @VisibleForTesting
        static void reset() {
            synchronized (RescuePartyObserver.class) {
                sRescuePartyObserver = null;
            }
        }

        @Override
        public int onHealthCheckFailed(@Nullable VersionedPackage failedPackage,
                @FailureReasons int failureReason, int mitigationCount) {
            int impact = PackageHealthObserverImpact.USER_IMPACT_LEVEL_0;
            if (!isDisabled() && (failureReason == PackageWatchdog.FAILURE_REASON_APP_CRASH
                    || failureReason == PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING)) {
                if (Flags.recoverabilityDetection()) {
                    if (!Flags.deprecateFlagsAndSettingsResets()) {
                        impact =  mapRescueLevelToUserImpact(getRescueLevel(mitigationCount,
                                mayPerformReboot(failedPackage), failedPackage));
                    } else {
                        impact =  mapRescueLevelToUserImpact(getRescueLevel(mitigationCount));
                    }
                } else {
                    impact =  mapRescueLevelToUserImpact(getRescueLevel(mitigationCount,
                            mayPerformReboot(failedPackage)));
                }
            }

            Slog.i(TAG, "Checking available remediations for health check failure."
                    + " failedPackage: "
                    + (failedPackage == null ? null : failedPackage.getPackageName())
                    + " failureReason: " + failureReason
                    + " available impact: " + impact);
            return impact;
        }

        @Override
        public boolean execute(@Nullable VersionedPackage failedPackage,
                @FailureReasons int failureReason, int mitigationCount) {
            if (isDisabled()) {
                return false;
            }
            Slog.i(TAG, "Executing remediation."
                    + " failedPackage: "
                    + (failedPackage == null ? null : failedPackage.getPackageName())
                    + " failureReason: " + failureReason
                    + " mitigationCount: " + mitigationCount);
            if (failureReason == PackageWatchdog.FAILURE_REASON_APP_CRASH
                    || failureReason == PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING) {
                final int level;
                if (Flags.recoverabilityDetection()) {
                    if (!Flags.deprecateFlagsAndSettingsResets()) {
                        level = getRescueLevel(mitigationCount, mayPerformReboot(failedPackage),
                                failedPackage);
                    } else {
                        level = getRescueLevel(mitigationCount);
                    }
                } else {
                    level = getRescueLevel(mitigationCount, mayPerformReboot(failedPackage));
                }
                executeRescueLevel(mContext,
                        failedPackage == null ? null : failedPackage.getPackageName(), level);
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
                // A package is a module if this is non-null
                if (pm.getModuleInfo(packageName, 0) != null) {
                    return true;
                }
            } catch (PackageManager.NameNotFoundException | IllegalStateException ignore) {
            }

            return isPersistentSystemApp(packageName);
        }

        @Override
        public int onBootLoop(int mitigationCount) {
            if (isDisabled()) {
                return PackageHealthObserverImpact.USER_IMPACT_LEVEL_0;
            }
            if (Flags.recoverabilityDetection()) {
                if (!Flags.deprecateFlagsAndSettingsResets()) {
                    return mapRescueLevelToUserImpact(getRescueLevel(mitigationCount,
                            true, /*failedPackage=*/ null));
                } else {
                    return mapRescueLevelToUserImpact(getRescueLevel(mitigationCount));
                }
            } else {
                return mapRescueLevelToUserImpact(getRescueLevel(mitigationCount, true));
            }
        }

        @Override
        public boolean executeBootLoopMitigation(int mitigationCount) {
            if (isDisabled()) {
                return false;
            }
            boolean mayPerformReboot = !shouldThrottleReboot();
            final int level;
            if (Flags.recoverabilityDetection()) {
                if (!Flags.deprecateFlagsAndSettingsResets()) {
                    level = getRescueLevel(mitigationCount, mayPerformReboot,
                            /*failedPackage=*/ null);
                } else {
                    level = getRescueLevel(mitigationCount);
                }
            } else {
                level = getRescueLevel(mitigationCount, mayPerformReboot);
            }
            executeRescueLevel(mContext, /*failedPackage=*/ null, level);
            return true;
        }

        @Override
        public String getUniqueIdentifier() {
            return NAME;
        }

        /**
         * Returns {@code true} if the failing package is non-null and performing a reboot or
         * prompting a factory reset is an acceptable mitigation strategy for the package's
         * failure, {@code false} otherwise.
         */
        private boolean mayPerformReboot(@Nullable VersionedPackage failingPackage) {
            if (failingPackage == null) {
                return false;
            }
            if (shouldThrottleReboot())  {
                return false;
            }

            return isPersistentSystemApp(failingPackage.getPackageName());
        }

        private boolean isPersistentSystemApp(@NonNull String packageName) {
            PackageManager pm = mContext.getPackageManager();
            try {
                ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
                return (info.flags & PERSISTENT_MASK) == PERSISTENT_MASK;
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        }

        private synchronized void recordDeviceConfigAccess(@NonNull String callingPackage,
                @NonNull String namespace) {
            if (!Flags.deprecateFlagsAndSettingsResets()) {
                // Record it in calling packages to namespace map
                Set<String> namespaceSet = mCallingPackageNamespaceSetMap.get(callingPackage);
                if (namespaceSet == null) {
                    namespaceSet = new ArraySet<>();
                    mCallingPackageNamespaceSetMap.put(callingPackage, namespaceSet);
                }
                namespaceSet.add(namespace);
                // Record it in namespace to calling packages map
                Set<String> callingPackageSet = mNamespaceCallingPackageSetMap.get(namespace);
                if (callingPackageSet == null) {
                    callingPackageSet = new ArraySet<>();
                }
                callingPackageSet.add(callingPackage);
                mNamespaceCallingPackageSetMap.put(namespace, callingPackageSet);
            }
        }

        private synchronized Set<String> getAffectedNamespaceSet(String failedPackage) {
            return mCallingPackageNamespaceSetMap.get(failedPackage);
        }

        private synchronized Set<String> getAllAffectedNamespaceSet() {
            return new HashSet<String>(mNamespaceCallingPackageSetMap.keySet());
        }

        private synchronized Set<String> getCallingPackagesSet(String namespace) {
            return mNamespaceCallingPackageSetMap.get(namespace);
        }
    }

    /**
     * Returns {@code true} if Rescue Party is allowed to attempt a reboot or factory reset.
     * Will return {@code false} if a factory reset was already offered recently.
     */
    private static boolean shouldThrottleReboot() {
        Long lastResetTime = getLastFactoryResetTimeMs();
        long now = System.currentTimeMillis();
        long throttleDurationMin = SystemProperties.getLong(PROP_THROTTLE_DURATION_MIN_FLAG,
                DEFAULT_FACTORY_RESET_THROTTLE_DURATION_MIN);
        return now < lastResetTime + TimeUnit.MINUTES.toMillis(throttleDurationMin);
    }

    private static int[] getAllUserIds() {
        int systemUserId = UserHandle.SYSTEM.getIdentifier();
        int[] userIds = { systemUserId };
        try {
            for (File file : FileUtils.listFilesOrEmpty(Environment.getDataSystemDeDirectory())) {
                try {
                    final int userId = Integer.parseInt(file.getName());
                    if (userId != systemUserId) {
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

    private static class Level {
        static int none() {
            return Flags.recoverabilityDetection() ? RESCUE_LEVEL_NONE : LEVEL_NONE;
        }

        static int reboot() {
            return Flags.recoverabilityDetection() ? RESCUE_LEVEL_WARM_REBOOT : LEVEL_WARM_REBOOT;
        }

        static int factoryReset() {
            return Flags.recoverabilityDetection()
                    ? RESCUE_LEVEL_FACTORY_RESET
                    : LEVEL_FACTORY_RESET;
        }
    }

    private static String levelToString(int level) {
        if (Flags.recoverabilityDetection()) {
            switch (level) {
                case RESCUE_LEVEL_NONE:
                    return "NONE";
                case RESCUE_LEVEL_SCOPED_DEVICE_CONFIG_RESET:
                    return "SCOPED_DEVICE_CONFIG_RESET";
                case RESCUE_LEVEL_ALL_DEVICE_CONFIG_RESET:
                    return "ALL_DEVICE_CONFIG_RESET";
                case RESCUE_LEVEL_WARM_REBOOT:
                    return "WARM_REBOOT";
                case RESCUE_LEVEL_RESET_SETTINGS_UNTRUSTED_DEFAULTS:
                    return "RESET_SETTINGS_UNTRUSTED_DEFAULTS";
                case RESCUE_LEVEL_RESET_SETTINGS_UNTRUSTED_CHANGES:
                    return "RESET_SETTINGS_UNTRUSTED_CHANGES";
                case RESCUE_LEVEL_RESET_SETTINGS_TRUSTED_DEFAULTS:
                    return "RESET_SETTINGS_TRUSTED_DEFAULTS";
                case RESCUE_LEVEL_FACTORY_RESET:
                    return "FACTORY_RESET";
                default:
                    return Integer.toString(level);
            }
        } else {
            switch (level) {
                case LEVEL_NONE:
                    return "NONE";
                case LEVEL_RESET_SETTINGS_UNTRUSTED_DEFAULTS:
                    return "RESET_SETTINGS_UNTRUSTED_DEFAULTS";
                case LEVEL_RESET_SETTINGS_UNTRUSTED_CHANGES:
                    return "RESET_SETTINGS_UNTRUSTED_CHANGES";
                case LEVEL_RESET_SETTINGS_TRUSTED_DEFAULTS:
                    return "RESET_SETTINGS_TRUSTED_DEFAULTS";
                case LEVEL_WARM_REBOOT:
                    return "WARM_REBOOT";
                case LEVEL_FACTORY_RESET:
                    return "FACTORY_RESET";
                default:
                    return Integer.toString(level);
            }
        }
    }
}
