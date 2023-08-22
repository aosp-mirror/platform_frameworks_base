/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.os;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

/**
 * Interface to access and modify the permanent and temporary power save allowlist. The two lists
 * are kept separately. Apps placed on the permanent allowlist are only removed via an explicit
 * removeFromAllowlist call. Apps whitelisted by default by the system cannot be removed. Apps
 * placed on the temporary allowlist are removed from that allowlist after a predetermined amount of
 * time.
 *
 * @deprecated Use {@link PowerExemptionManager} instead
 * @hide
 */
@SystemApi
@Deprecated
@SystemService(Context.POWER_WHITELIST_MANAGER)
public class PowerWhitelistManager {
    private final Context mContext;
    // Proxy to DeviceIdleController for now
    // TODO: migrate to PowerWhitelistController
    private final IDeviceIdleController mService;

    private final PowerExemptionManager mPowerExemptionManager;

    /**
     * Indicates that an unforeseen event has occurred and the app should be allowlisted to handle
     * it.
     */
    public static final int EVENT_UNSPECIFIED = PowerExemptionManager.EVENT_UNSPECIFIED;

    /**
     * Indicates that an SMS event has occurred and the app should be allowlisted to handle it.
     */
    public static final int EVENT_SMS = PowerExemptionManager.EVENT_SMS;

    /**
     * Indicates that an MMS event has occurred and the app should be allowlisted to handle it.
     */
    public static final int EVENT_MMS = PowerExemptionManager.EVENT_MMS;

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"EVENT_"}, value = {
            EVENT_UNSPECIFIED,
            EVENT_SMS,
            EVENT_MMS,
    })
    public @interface WhitelistEvent {
    }

    /**
     * Allow the temp allowlist behavior, plus allow foreground service start from background.
     */
    public static final int TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_ALLOWED =
            PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED;
    /**
     * Only allow the temp allowlist behavior, not allow foreground service start from
     * background.
     */
    public static final int TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_NOT_ALLOWED =
            PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_NOT_ALLOWED;

    /**
     * The list of temp allowlist types.
     * @hide
     */
    @IntDef(flag = true, prefix = { "TEMPORARY_ALLOWLIST_TYPE_" }, value = {
            TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_ALLOWED,
            TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_NOT_ALLOWED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TempAllowListType {}

    /* Reason code for BG-FGS-launch. */
    /**
     * BG-FGS-launch is denied.
     * @hide
     */
    public static final int REASON_DENIED = PowerExemptionManager.REASON_DENIED;

    /* Reason code range 0-9 are reserved for default reasons */
    /**
     * The default reason code if reason is unknown.
     */
    public static final int REASON_UNKNOWN = PowerExemptionManager.REASON_UNKNOWN;
    /**
     * Use REASON_OTHER if there is no better choice.
     */
    public static final int REASON_OTHER = PowerExemptionManager.REASON_OTHER;

    /* Reason code range 10-49 are reserved for BG-FGS-launch allowed proc states */
    /** @hide */
    public static final int REASON_PROC_STATE_PERSISTENT =
            PowerExemptionManager.REASON_PROC_STATE_PERSISTENT;
    /** @hide */
    public static final int REASON_PROC_STATE_PERSISTENT_UI =
            PowerExemptionManager.REASON_PROC_STATE_PERSISTENT_UI;
    /** @hide */
    public static final int REASON_PROC_STATE_TOP = PowerExemptionManager.REASON_PROC_STATE_TOP;
    /** @hide */
    public static final int REASON_PROC_STATE_BTOP = PowerExemptionManager.REASON_PROC_STATE_BTOP;
    /** @hide */
    public static final int REASON_PROC_STATE_FGS = PowerExemptionManager.REASON_PROC_STATE_FGS;
    /** @hide */
    public static final int REASON_PROC_STATE_BFGS = PowerExemptionManager.REASON_PROC_STATE_BFGS;

    /* Reason code range 50-99 are reserved for BG-FGS-launch allowed reasons */
    /** @hide */
    public static final int REASON_UID_VISIBLE = PowerExemptionManager.REASON_UID_VISIBLE;
    /** @hide */
    public static final int REASON_SYSTEM_UID = PowerExemptionManager.REASON_SYSTEM_UID;
    /** @hide */
    public static final int REASON_ACTIVITY_STARTER = PowerExemptionManager.REASON_ACTIVITY_STARTER;
    /** @hide */
    public static final int REASON_START_ACTIVITY_FLAG =
            PowerExemptionManager.REASON_START_ACTIVITY_FLAG;
    /** @hide */
    public static final int REASON_FGS_BINDING = PowerExemptionManager.REASON_FGS_BINDING;
    /** @hide */
    public static final int REASON_DEVICE_OWNER = PowerExemptionManager.REASON_DEVICE_OWNER;
    /** @hide */
    public static final int REASON_PROFILE_OWNER = PowerExemptionManager.REASON_PROFILE_OWNER;
    /** @hide */
    public static final int REASON_COMPANION_DEVICE_MANAGER =
            PowerExemptionManager.REASON_COMPANION_DEVICE_MANAGER;
    /**
     * START_ACTIVITIES_FROM_BACKGROUND permission.
     * @hide
     */
    public static final int REASON_BACKGROUND_ACTIVITY_PERMISSION =
            PowerExemptionManager.REASON_BACKGROUND_ACTIVITY_PERMISSION;
    /**
     * START_FOREGROUND_SERVICES_FROM_BACKGROUND permission.
     * @hide
     */
    public static final int REASON_BACKGROUND_FGS_PERMISSION =
            PowerExemptionManager.REASON_BACKGROUND_FGS_PERMISSION;
    /** @hide */
    public static final int REASON_INSTR_BACKGROUND_ACTIVITY_PERMISSION =
            PowerExemptionManager.REASON_INSTR_BACKGROUND_ACTIVITY_PERMISSION;
    /** @hide */
    public static final int REASON_INSTR_BACKGROUND_FGS_PERMISSION =
            PowerExemptionManager.REASON_INSTR_BACKGROUND_FGS_PERMISSION;
    /** @hide */
    public static final int REASON_SYSTEM_ALERT_WINDOW_PERMISSION =
            PowerExemptionManager.REASON_SYSTEM_ALERT_WINDOW_PERMISSION;
    /** @hide */
    public static final int REASON_DEVICE_DEMO_MODE = PowerExemptionManager.REASON_DEVICE_DEMO_MODE;
    /** @hide */
    public static final int REASON_ALLOWLISTED_PACKAGE =
            PowerExemptionManager.REASON_ALLOWLISTED_PACKAGE;
    /** @hide */
    public static final int REASON_APPOP = PowerExemptionManager.REASON_APPOP;

    /* BG-FGS-launch is allowed by temp-allowlist or system-allowlist.
       Reason code for temp and system allowlist starts here.
       Reason code range 100-199 are reserved for public reasons. */
    /**
     * Set temp-allowlist for location geofence purpose.
     */
    public static final int REASON_GEOFENCING = PowerExemptionManager.REASON_GEOFENCING;
    /**
     * Set temp-allowlist for server push messaging.
     */
    public static final int REASON_PUSH_MESSAGING = PowerExemptionManager.REASON_PUSH_MESSAGING;
    /**
     * Set temp-allowlist for server push messaging over the quota.
     */
    public static final int REASON_PUSH_MESSAGING_OVER_QUOTA =
            PowerExemptionManager.REASON_PUSH_MESSAGING_OVER_QUOTA;
    /**
     * Set temp-allowlist for activity recognition.
     */
    public static final int REASON_ACTIVITY_RECOGNITION =
            PowerExemptionManager.REASON_ACTIVITY_RECOGNITION;

    /* Reason code range 200-299 are reserved for broadcast actions */
    /**
     * Broadcast ACTION_BOOT_COMPLETED.
     * @hide
     */
    public static final int REASON_BOOT_COMPLETED = PowerExemptionManager.REASON_BOOT_COMPLETED;
    /**
     * Broadcast ACTION_PRE_BOOT_COMPLETED.
     * @hide
     */
    public static final int REASON_PRE_BOOT_COMPLETED =
            PowerExemptionManager.REASON_PRE_BOOT_COMPLETED;
    /**
     * Broadcast ACTION_LOCKED_BOOT_COMPLETED.
     * @hide
     */
    public static final int REASON_LOCKED_BOOT_COMPLETED =
            PowerExemptionManager.REASON_LOCKED_BOOT_COMPLETED;

    /* Reason code range 300-399 are reserved for other internal reasons */
    /**
     * Device idle system allowlist, including EXCEPT-IDLE
     * @hide
     */
    public static final int REASON_SYSTEM_ALLOW_LISTED =
            PowerExemptionManager.REASON_SYSTEM_ALLOW_LISTED;
    /** @hide */
    public static final int REASON_ALARM_MANAGER_ALARM_CLOCK =
            PowerExemptionManager.REASON_ALARM_MANAGER_ALARM_CLOCK;
    /**
     * AlarmManagerService.
     * @hide
     */
    public static final int REASON_ALARM_MANAGER_WHILE_IDLE =
            PowerExemptionManager.REASON_ALARM_MANAGER_WHILE_IDLE;
    /**
     * ActiveServices.
     * @hide
     */
    public static final int REASON_SERVICE_LAUNCH = PowerExemptionManager.REASON_SERVICE_LAUNCH;
    /**
     * KeyChainSystemService.
     * @hide
     */
    public static final int REASON_KEY_CHAIN = PowerExemptionManager.REASON_KEY_CHAIN;
    /**
     * PackageManagerService.
     * @hide
     */
    public static final int REASON_PACKAGE_VERIFIER = PowerExemptionManager.REASON_PACKAGE_VERIFIER;
    /**
     * SyncManager.
     * @hide
     */
    public static final int REASON_SYNC_MANAGER = PowerExemptionManager.REASON_SYNC_MANAGER;
    /**
     * DomainVerificationProxyV1.
     * @hide
     */
    public static final int REASON_DOMAIN_VERIFICATION_V1 =
            PowerExemptionManager.REASON_DOMAIN_VERIFICATION_V1;
    /**
     * DomainVerificationProxyV2.
     * @hide
     */
    public static final int REASON_DOMAIN_VERIFICATION_V2 =
            PowerExemptionManager.REASON_DOMAIN_VERIFICATION_V2;
    /** @hide */
    public static final int REASON_VPN = 309;
    /**
     * NotificationManagerService.
     * @hide
     */
    public static final int REASON_NOTIFICATION_SERVICE =
            PowerExemptionManager.REASON_NOTIFICATION_SERVICE;
    /**
     * Broadcast ACTION_MY_PACKAGE_REPLACED.
     * @hide
     */
    public static final int REASON_PACKAGE_REPLACED = PowerExemptionManager.REASON_PACKAGE_REPLACED;
    /**
     * LocationProvider.
     * @hide
     */
    @SystemApi
    public static final int REASON_LOCATION_PROVIDER =
            PowerExemptionManager.REASON_LOCATION_PROVIDER;
    /**
     * MediaButtonReceiver.
     * @hide
     */
    public static final int REASON_MEDIA_BUTTON = PowerExemptionManager.REASON_MEDIA_BUTTON;
    /**
     * InboundSmsHandler.
     * @hide
     */
    public static final int REASON_EVENT_SMS = PowerExemptionManager.REASON_EVENT_SMS;
    /**
     * InboundSmsHandler.
     * @hide
     */
    public static final int REASON_EVENT_MMS = PowerExemptionManager.REASON_EVENT_MMS;
    /**
     * Shell app.
     * @hide
     */
    public static final int REASON_SHELL = PowerExemptionManager.REASON_SHELL;

    /**
     * The list of BG-FGS-Launch and temp-allowlist reason code.
     * @hide
     */
    @IntDef(flag = true, prefix = { "REASON_" }, value = {
            // BG-FGS-Launch reasons.
            REASON_DENIED,
            REASON_UNKNOWN,
            REASON_OTHER,
            REASON_PROC_STATE_PERSISTENT,
            REASON_PROC_STATE_PERSISTENT_UI,
            REASON_PROC_STATE_TOP,
            REASON_PROC_STATE_BTOP,
            REASON_PROC_STATE_FGS,
            REASON_PROC_STATE_BFGS,
            REASON_UID_VISIBLE,
            REASON_SYSTEM_UID,
            REASON_ACTIVITY_STARTER,
            REASON_START_ACTIVITY_FLAG,
            REASON_FGS_BINDING,
            REASON_DEVICE_OWNER,
            REASON_PROFILE_OWNER,
            REASON_COMPANION_DEVICE_MANAGER,
            REASON_BACKGROUND_ACTIVITY_PERMISSION,
            REASON_BACKGROUND_FGS_PERMISSION,
            REASON_INSTR_BACKGROUND_ACTIVITY_PERMISSION,
            REASON_INSTR_BACKGROUND_FGS_PERMISSION,
            REASON_SYSTEM_ALERT_WINDOW_PERMISSION,
            REASON_DEVICE_DEMO_MODE,
            REASON_ALLOWLISTED_PACKAGE,
            REASON_APPOP,
            // temp and system allowlist reasons.
            REASON_GEOFENCING,
            REASON_PUSH_MESSAGING,
            REASON_PUSH_MESSAGING_OVER_QUOTA,
            REASON_ACTIVITY_RECOGNITION,
            REASON_BOOT_COMPLETED,
            REASON_PRE_BOOT_COMPLETED,
            REASON_LOCKED_BOOT_COMPLETED,
            REASON_SYSTEM_ALLOW_LISTED,
            REASON_ALARM_MANAGER_ALARM_CLOCK,
            REASON_ALARM_MANAGER_WHILE_IDLE,
            REASON_SERVICE_LAUNCH,
            REASON_KEY_CHAIN,
            REASON_PACKAGE_VERIFIER,
            REASON_SYNC_MANAGER,
            REASON_DOMAIN_VERIFICATION_V1,
            REASON_DOMAIN_VERIFICATION_V2,
            REASON_VPN,
            REASON_NOTIFICATION_SERVICE,
            REASON_PACKAGE_REPLACED,
            REASON_LOCATION_PROVIDER,
            REASON_MEDIA_BUTTON,
            REASON_EVENT_SMS,
            REASON_EVENT_MMS,
            REASON_SHELL,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ReasonCode {}

    /**
     * @hide
     */
    public PowerWhitelistManager(@NonNull Context context) {
        mContext = context;
        mService = context.getSystemService(DeviceIdleManager.class).getService();
        mPowerExemptionManager = context.getSystemService(PowerExemptionManager.class);
    }

    /**
     * Add the specified package to the permanent power save allowlist.
     *
     * @deprecated Use {@link PowerExemptionManager#addToPermanentAllowList(String)} instead
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.DEVICE_POWER)
    public void addToWhitelist(@NonNull String packageName) {
        mPowerExemptionManager.addToPermanentAllowList(packageName);
    }

    /**
     * Add the specified packages to the permanent power save allowlist.
     *
     * @deprecated Use {@link PowerExemptionManager#addToPermanentAllowList(List)} instead
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.DEVICE_POWER)
    public void addToWhitelist(@NonNull List<String> packageNames) {
        mPowerExemptionManager.addToPermanentAllowList(packageNames);
    }

    /**
     * Get a list of app IDs of app that are allowlisted. This does not include temporarily
     * allowlisted apps.
     *
     * @param includingIdle Set to true if the app should be allowlisted from device idle as well
     *                      as other power save restrictions
     * @deprecated Use {@link PowerExemptionManager#getAllowListedAppIds(boolean)} instead
     * @hide
     */
    @Deprecated
    @NonNull
    public int[] getWhitelistedAppIds(boolean includingIdle) {
        return mPowerExemptionManager.getAllowListedAppIds(includingIdle);
    }

    /**
     * Returns true if the app is allowlisted from power save restrictions. This does not include
     * temporarily allowlisted apps.
     *
     * @param includingIdle Set to true if the app should be allowlisted from device
     *                      idle as well as other power save restrictions
     * @deprecated Use {@link PowerExemptionManager#isAllowListed(String, boolean)} instead
     * @hide
     */
    @Deprecated
    public boolean isWhitelisted(@NonNull String packageName, boolean includingIdle) {
        return mPowerExemptionManager.isAllowListed(packageName, includingIdle);
    }

    /**
     * Remove an app from the permanent power save allowlist. Only apps that were added via
     * {@link #addToWhitelist(String)} or {@link #addToWhitelist(List)} will be removed. Apps
     * allowlisted by default by the system cannot be removed.
     *
     * @param packageName The app to remove from the allowlist
     * @deprecated Use {@link PowerExemptionManager#removeFromPermanentAllowList(String)} instead
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.DEVICE_POWER)
    public void removeFromWhitelist(@NonNull String packageName) {
        mPowerExemptionManager.removeFromPermanentAllowList(packageName);
    }

    /**
     * Add an app to the temporary allowlist for a short amount of time.
     *
     * @param packageName The package to add to the temp allowlist
     * @param durationMs  How long to keep the app on the temp allowlist for (in milliseconds)
     * @param reasonCode one of {@link ReasonCode}, use {@link #REASON_UNKNOWN} if not sure.
     * @param reason a optional human readable reason string, could be null or empty string.
     * @deprecated Use {@link PowerExemptionManager#addToTemporaryAllowList(
     *             String, int, String, long)} instead
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.CHANGE_DEVICE_IDLE_TEMP_WHITELIST)
    public void whitelistAppTemporarily(@NonNull String packageName, long durationMs,
            @ReasonCode int reasonCode, @Nullable String reason) {
        mPowerExemptionManager.addToTemporaryAllowList(packageName, reasonCode, reason, durationMs);
    }

    /**
     * Add an app to the temporary allowlist for a short amount of time.
     *
     * @param packageName The package to add to the temp allowlist
     * @param durationMs  How long to keep the app on the temp allowlist for (in milliseconds)
     * @deprecated Use {@link PowerExemptionManager#addToTemporaryAllowList(
     *             String, int, String, long)} instead
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.CHANGE_DEVICE_IDLE_TEMP_WHITELIST)
    public void whitelistAppTemporarily(@NonNull String packageName, long durationMs) {
        mPowerExemptionManager.addToTemporaryAllowList(
                packageName, REASON_UNKNOWN, packageName, durationMs);
    }

    /**
     * Add an app to the temporary allowlist for a short amount of time for a specific reason. The
     * temporary allowlist is kept separately from the permanent allowlist and apps are
     * automatically removed from the temporary allowlist after a predetermined amount of time.
     *
     * @param packageName The package to add to the temp allowlist
     * @param event       The reason to add the app to the temp allowlist
     * @param reason      A human-readable reason explaining why the app is temp allowlisted. Only
     *                    used for logging purposes. Could be null or empty string.
     * @return The duration (in milliseconds) that the app is allowlisted for
     * @deprecated Use {@link PowerExemptionManager#addToTemporaryAllowListForEvent(
     *             String, int, String, int)} instead
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.CHANGE_DEVICE_IDLE_TEMP_WHITELIST)
    public long whitelistAppTemporarilyForEvent(@NonNull String packageName,
            @WhitelistEvent int event, @Nullable String reason) {
        return mPowerExemptionManager.addToTemporaryAllowListForEvent(
                packageName, REASON_UNKNOWN, reason, event);
    }

    /**
     * Add an app to the temporary allowlist for a short amount of time for a specific reason. The
     * temporary allowlist is kept separately from the permanent allowlist and apps are
     * automatically removed from the temporary allowlist after a predetermined amount of time.
     *
     * @param packageName The package to add to the temp allowlist
     * @param event       The reason to add the app to the temp allowlist
     * @param reasonCode  one of {@link ReasonCode}, use {@link #REASON_UNKNOWN} if not sure.
     * @param reason      A human-readable reason explaining why the app is temp allowlisted. Only
     *                    used for logging purposes. Could be null or empty string.
     * @return The duration (in milliseconds) that the app is allowlisted for
     * @deprecated Use {@link PowerExemptionManager#addToTemporaryAllowListForEvent(
     *             String, int, String, int)} instead
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.CHANGE_DEVICE_IDLE_TEMP_WHITELIST)
    public long whitelistAppTemporarilyForEvent(@NonNull String packageName,
            @WhitelistEvent int event, @ReasonCode int reasonCode, @Nullable String reason) {
        return mPowerExemptionManager.addToTemporaryAllowListForEvent(
                packageName, reasonCode, reason, event);
    }

    /**
     * @hide
     *
     * @deprecated Use {@link PowerExemptionManager#getReasonCodeFromProcState(int)} instead
     */
    @Deprecated
    public static @ReasonCode int getReasonCodeFromProcState(int procState) {
        return PowerExemptionManager.getReasonCodeFromProcState(procState);
    }

    /**
     * Return string name of the integer reason code.
     * @hide
     * @param reasonCode
     * @return string name of the reason code.
     * @deprecated Use {@link PowerExemptionManager#reasonCodeToString(int)} instead
     */
    @Deprecated
    public static String reasonCodeToString(@ReasonCode int reasonCode) {
        return PowerExemptionManager.reasonCodeToString(reasonCode);
    }
}
