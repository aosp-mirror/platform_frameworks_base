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

package android.os;

import static android.app.ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_BOUND_TOP;
import static android.app.ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_PERSISTENT;
import static android.app.ActivityManager.PROCESS_STATE_PERSISTENT_UI;
import static android.app.ActivityManager.PROCESS_STATE_TOP;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.UserHandleAware;
import android.content.Context;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collections;
import java.util.List;

/**
 * Interface to access and modify the permanent and temporary power save allow list. The two lists
 * are kept separately. Apps placed on the permanent allow list are only removed via an explicit
 * {@link #removeFromPermanentAllowList(String)} call. Apps allow-listed by default by the system
 * cannot be removed. Apps placed on the temporary allow list are removed from that allow list after
 * a predetermined amount of time.
 *
 * @hide
 */
@SystemApi
@SystemService(Context.POWER_EXEMPTION_SERVICE)
public class PowerExemptionManager {
    private final Context mContext;
    // Proxy to DeviceIdleController for now
    // TODO: migrate to PowerExemptionController
    private final IDeviceIdleController mService;

    /**
     * Indicates that an unforeseen event has occurred and the app should be allow-listed to handle
     * it.
     */
    public static final int EVENT_UNSPECIFIED = 0;

    /**
     * Indicates that an SMS event has occurred and the app should be allow-listed to handle it.
     */
    public static final int EVENT_SMS = 1;

    /**
     * Indicates that an MMS event has occurred and the app should be allow-listed to handle it.
     */
    public static final int EVENT_MMS = 2;

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"EVENT_"}, value = {
            EVENT_UNSPECIFIED,
            EVENT_SMS,
            EVENT_MMS,
    })
    public @interface AllowListEvent {
    }

    /**
     * Does not place the app on any temporary allow list. Nullifies the previous call to
     * {@link android.app.BroadcastOptions#setTemporaryAppAllowlist(long, int, int, String)}.
     * Note: this will not remove the receiver app from the temp allow list.
     */
    public static final int TEMPORARY_ALLOW_LIST_TYPE_NONE = -1;
    /**
     * Allow the temp allow list behavior, plus allow foreground service start from background.
     */
    public static final int TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED = 0;
    /**
     * Only allow the temp allow list behavior, not allow foreground service start from background.
     */
    public static final int TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_NOT_ALLOWED = 1;

    /**
     * The list of temp allow list types.
     * @hide
     */
    @IntDef(flag = true, prefix = { "TEMPORARY_ALLOW_LIST_TYPE_" }, value = {
            TEMPORARY_ALLOW_LIST_TYPE_NONE,
            TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED,
            TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_NOT_ALLOWED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TempAllowListType {}

    /* Reason codes for BG-FGS-launch. */
    /**
     * BG-FGS-launch is denied.
     * @hide
     */
    public static final int REASON_DENIED = -1;

    /* Reason code range 0-9 are reserved for default reasons */
    /**
     * The default reason code if reason is unknown.
     */
    public static final int REASON_UNKNOWN = 0;
    /**
     * Use REASON_OTHER if there is no better choice.
     */
    public static final int REASON_OTHER = 1;

    /* Reason code range 10-49 are reserved for BG-FGS-launch allowed proc states */
    /** @hide */
    public static final int REASON_PROC_STATE_PERSISTENT = 10;
    /** @hide */
    public static final int REASON_PROC_STATE_PERSISTENT_UI = 11;
    /** @hide */
    public static final int REASON_PROC_STATE_TOP = 12;
    /** @hide */
    public static final int REASON_PROC_STATE_BTOP = 13;
    /** @hide */
    public static final int REASON_PROC_STATE_FGS = 14;
    /** @hide */
    public static final int REASON_PROC_STATE_BFGS = 15;

    /* Reason code range 50-99 are reserved for BG-FGS-launch allowed reasons */
    /** @hide */
    public static final int REASON_UID_VISIBLE = 50;
    /** @hide */
    public static final int REASON_SYSTEM_UID = 51;
    /** @hide */
    public static final int REASON_ACTIVITY_STARTER = 52;
    /** @hide */
    public static final int REASON_START_ACTIVITY_FLAG = 53;
    /** @hide */
    public static final int REASON_FGS_BINDING = 54;
    /** @hide */
    public static final int REASON_DEVICE_OWNER = 55;
    /** @hide */
    public static final int REASON_PROFILE_OWNER = 56;
    /** @hide */
    public static final int REASON_COMPANION_DEVICE_MANAGER = 57;
    /**
     * START_ACTIVITIES_FROM_BACKGROUND permission.
     * @hide
     */
    public static final int REASON_BACKGROUND_ACTIVITY_PERMISSION = 58;
    /**
     * START_FOREGROUND_SERVICES_FROM_BACKGROUND permission.
     * @hide
     */
    public static final int REASON_BACKGROUND_FGS_PERMISSION = 59;
    /** @hide */
    public static final int REASON_INSTR_BACKGROUND_ACTIVITY_PERMISSION = 60;
    /** @hide */
    public static final int REASON_INSTR_BACKGROUND_FGS_PERMISSION = 61;
    /** @hide */
    public static final int REASON_SYSTEM_ALERT_WINDOW_PERMISSION = 62;
    /** @hide */
    public static final int REASON_DEVICE_DEMO_MODE = 63;
    /** @hide */
    public static final int REASON_ALLOWLISTED_PACKAGE = 65;
    /** @hide */
    public static final int REASON_APPOP = 66;
    /** @hide */
    public static final int REASON_ACTIVITY_VISIBILITY_GRACE_PERIOD = 67;
    /** @hide */
    public static final int REASON_OP_ACTIVATE_VPN = 68;
    /** @hide */
    public static final int REASON_OP_ACTIVATE_PLATFORM_VPN = 69;
    /**
     * Temporarily allowed to have FGS while-in-use permissions.
     * @hide
     */
    public static final int REASON_TEMP_ALLOWED_WHILE_IN_USE = 70;
    /** @hide */
    public static final int REASON_CURRENT_INPUT_METHOD = 71;

    /* BG-FGS-launch is allowed by temp-allow-list or system-allow-list.
       Reason code for temp and system allow list starts here.
       Reason code range 100-199 are reserved for public reasons. */
    /**
     * Set temp-allow-list for location geofence purpose.
     */
    public static final int REASON_GEOFENCING = 100;
    /**
     * Set temp-allow-list for server push messaging.
     */
    public static final int REASON_PUSH_MESSAGING = 101;
    /**
     * Set temp-allow-list for server push messaging over the quota.
     */
    public static final int REASON_PUSH_MESSAGING_OVER_QUOTA = 102;
    /**
     * Set temp-allow-list for activity recognition.
     */
    public static final int REASON_ACTIVITY_RECOGNITION = 103;
    /**
     * Set temp-allow-list for transferring accounts between users.
     */
    public static final int REASON_ACCOUNT_TRANSFER = 104;

    /* Reason code range 200-299 are reserved for broadcast actions */
    /**
     * Broadcast ACTION_BOOT_COMPLETED.
     * @hide
     */
    public static final int REASON_BOOT_COMPLETED = 200;
    /**
     * Broadcast ACTION_PRE_BOOT_COMPLETED.
     * @hide
     */
    public static final int REASON_PRE_BOOT_COMPLETED = 201;
    /**
     * Broadcast ACTION_LOCKED_BOOT_COMPLETED.
     * @hide
     */
    public static final int REASON_LOCKED_BOOT_COMPLETED = 202;
    /**
     * All Bluetooth broadcasts.
     */
    public static final int REASON_BLUETOOTH_BROADCAST = 203;
    /**
     * Broadcast {@link android.content.Intent#ACTION_TIMEZONE_CHANGED}
     * @hide
     */
    public static final int REASON_TIMEZONE_CHANGED = 204;
    /**
     * Broadcast {@link android.content.Intent#ACTION_TIME_CHANGED}
     * @hide
     */
    public static final int REASON_TIME_CHANGED = 205;
    /**
     * Broadcast {@link android.content.Intent#ACTION_LOCALE_CHANGED}
     * @hide
     */
    public static final int REASON_LOCALE_CHANGED = 206;
    /**
     * Broadcast
     * {@link android.app.AlarmManager#ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED}
     * @hide
     */
    public static final int REASON_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED = 207;
    /**
     * Broadcast {@link android.safetycenter.SafetyCenterManager#ACTION_REFRESH_SAFETY_SOURCES}.
     */
    public static final int REASON_REFRESH_SAFETY_SOURCES = 208;

    /* Reason code range 300-399 are reserved for other internal reasons */
    /**
     * Device idle system allow list, including EXCEPT-IDLE
     * @hide
     */
    public static final int REASON_SYSTEM_ALLOW_LISTED = 300;
    /** @hide */
    public static final int REASON_ALARM_MANAGER_ALARM_CLOCK = 301;
    /**
     * AlarmManagerService.
     * @hide
     */
    public static final int REASON_ALARM_MANAGER_WHILE_IDLE = 302;
    /**
     * ActiveServices.
     * @hide
     */
    public static final int REASON_SERVICE_LAUNCH = 303;
    /**
     * KeyChainSystemService.
     * @hide
     */
    public static final int REASON_KEY_CHAIN = 304;
    /**
     * PackageManagerService.
     * @hide
     */
    public static final int REASON_PACKAGE_VERIFIER = 305;
    /**
     * SyncManager.
     * @hide
     */
    public static final int REASON_SYNC_MANAGER = 306;
    /**
     * DomainVerificationProxyV1.
     * @hide
     */
    public static final int REASON_DOMAIN_VERIFICATION_V1 = 307;
    /**
     * DomainVerificationProxyV2.
     * @hide
     */
    public static final int REASON_DOMAIN_VERIFICATION_V2 = 308;
    /** @hide */
    public static final int REASON_VPN = 309;
    /**
     * NotificationManagerService.
     * @hide
     */
    public static final int REASON_NOTIFICATION_SERVICE = 310;
    /**
     * Broadcast ACTION_MY_PACKAGE_REPLACED.
     * @hide
     */
    public static final int REASON_PACKAGE_REPLACED = 311;
    /**
     * LocationProvider.
     * @hide
     */
    @SystemApi
    public static final int REASON_LOCATION_PROVIDER = 312;
    /**
     * MediaButtonReceiver.
     * @hide
     */
    public static final int REASON_MEDIA_BUTTON = 313;
    /**
     * InboundSmsHandler.
     * @hide
     */
    public static final int REASON_EVENT_SMS = 314;
    /**
     * InboundSmsHandler.
     * @hide
     */
    public static final int REASON_EVENT_MMS = 315;
    /**
     * Shell app.
     * @hide
     */
    public static final int REASON_SHELL = 316;
    /**
     * Media session callbacks.
     * @hide
     */
    public static final int REASON_MEDIA_SESSION_CALLBACK = 317;
    /**
     * Dialer app.
     * @hide
     */
    public static final int REASON_ROLE_DIALER = 318;
    /**
     * Emergency app.
     * @hide
     */
    public static final int REASON_ROLE_EMERGENCY = 319;
    /**
     * System Module.
     * @hide
     */
    public static final int REASON_SYSTEM_MODULE = 320;
    /**
     * Carrier privileged app.
     * @hide
     */
    public static final int REASON_CARRIER_PRIVILEGED_APP = 321;

    /** @hide The app requests out-out. */
    public static final int REASON_OPT_OUT_REQUESTED = 1000;

    /**
     * The list of BG-FGS-Launch and temp-allow-list reason code.
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
            REASON_ACTIVITY_VISIBILITY_GRACE_PERIOD,
            REASON_OP_ACTIVATE_VPN,
            REASON_OP_ACTIVATE_PLATFORM_VPN,
            REASON_CURRENT_INPUT_METHOD,
            REASON_TEMP_ALLOWED_WHILE_IN_USE,
            // temp and system allow list reasons.
            REASON_GEOFENCING,
            REASON_PUSH_MESSAGING,
            REASON_PUSH_MESSAGING_OVER_QUOTA,
            REASON_ACTIVITY_RECOGNITION,
            REASON_ACCOUNT_TRANSFER,
            REASON_BOOT_COMPLETED,
            REASON_PRE_BOOT_COMPLETED,
            REASON_LOCKED_BOOT_COMPLETED,
            REASON_BLUETOOTH_BROADCAST,
            REASON_TIMEZONE_CHANGED,
            REASON_TIME_CHANGED,
            REASON_LOCALE_CHANGED,
            REASON_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            REASON_REFRESH_SAFETY_SOURCES,
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
            REASON_MEDIA_SESSION_CALLBACK,
            REASON_ROLE_DIALER,
            REASON_ROLE_EMERGENCY,
            REASON_SYSTEM_MODULE,
            REASON_CARRIER_PRIVILEGED_APP,
            REASON_OPT_OUT_REQUESTED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ReasonCode {}

    /**
     * @hide
     */
    public PowerExemptionManager(@NonNull Context context) {
        mContext = context;
        mService = context.getSystemService(DeviceIdleManager.class).getService();
    }

    /**
     * Add the specified package to the permanent power save allow list.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.DEVICE_POWER)
    public void addToPermanentAllowList(@NonNull String packageName) {
        addToPermanentAllowList(Collections.singletonList(packageName));
    }

    /**
     * Add the specified packages to the permanent power save allow list.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.DEVICE_POWER)
    public void addToPermanentAllowList(@NonNull List<String> packageNames) {
        try {
            mService.addPowerSaveWhitelistApps(packageNames);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get a list of app IDs of app that are allow-listed. This does not include temporarily
     * allow-listed apps.
     *
     * @param includingIdle Set to true if the app should be allow-listed from device idle as well
     *                      as other power save restrictions
     * @hide
     */
    @NonNull
    @RequiresPermission(android.Manifest.permission.DEVICE_POWER)
    public int[] getAllowListedAppIds(boolean includingIdle) {
        try {
            if (includingIdle) {
                return mService.getAppIdWhitelist();
            } else {
                return mService.getAppIdWhitelistExceptIdle();
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns true if the app is allow-listed from power save restrictions. This does not include
     * temporarily allow-listed apps.
     *
     * @param includingIdle Set to true if the app should be allow-listed from device
     *                      idle as well as other power save restrictions
     * @hide
     */
    public boolean isAllowListed(@NonNull String packageName, boolean includingIdle) {
        try {
            if (includingIdle) {
                return mService.isPowerSaveWhitelistApp(packageName);
            } else {
                return mService.isPowerSaveWhitelistExceptIdleApp(packageName);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Remove an app from the permanent power save allow list. Only apps that were added via
     * {@link #addToPermanentAllowList(String)} or {@link #addToPermanentAllowList(List)} will be
     * removed. Apps allow-listed by default by the system cannot be removed.
     *
     * @param packageName The app to remove from the allow list
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.DEVICE_POWER)
    public void removeFromPermanentAllowList(@NonNull String packageName) {
        try {
            mService.removePowerSaveWhitelistApp(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Add an app to the temporary allow list for a short amount of time.
     *
     * @param packageName The package to add to the temp allow list
     * @param durationMs How long to keep the app on the temp allow list for (in milliseconds)
     * @param reasonCode one of {@link ReasonCode}, use {@link #REASON_UNKNOWN} if not sure.
     * @param reason a optional human readable reason string, could be null or empty string.
     */
    @UserHandleAware
    @RequiresPermission(android.Manifest.permission.CHANGE_DEVICE_IDLE_TEMP_WHITELIST)
    public void addToTemporaryAllowList(@NonNull String packageName, @ReasonCode int reasonCode,
            @Nullable String reason, long durationMs) {
        try {
            mService.addPowerSaveTempWhitelistApp(packageName, durationMs, mContext.getUserId(),
                    reasonCode, reason);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Add an app to the temporary allow list for a short amount of time for a specific reason.
     * The temporary allow list is kept separately from the permanent allow list and apps are
     * automatically removed from the temporary allow list after a predetermined amount of time.
     *
     * @param packageName The package to add to the temp allow list
     * @param event       The reason to add the app to the temp allow list
     * @param reasonCode  one of {@link ReasonCode}, use {@link #REASON_UNKNOWN} if not sure.
     * @param reason      A human-readable reason explaining why the app is temp allow-listed. Only
     *                    used for logging purposes. Could be null or empty string.
     * @return The duration (in milliseconds) that the app is allow-listed for
     */
    @UserHandleAware
    @RequiresPermission(android.Manifest.permission.CHANGE_DEVICE_IDLE_TEMP_WHITELIST)
    public long addToTemporaryAllowListForEvent(@NonNull String packageName,
            @ReasonCode int reasonCode, @Nullable String reason, @AllowListEvent int event) {
        try {
            switch (event) {
                case EVENT_MMS:
                    return mService.addPowerSaveTempWhitelistAppForMms(
                            packageName, mContext.getUserId(), reasonCode, reason);
                case EVENT_SMS:
                    return mService.addPowerSaveTempWhitelistAppForSms(
                            packageName, mContext.getUserId(), reasonCode, reason);
                case EVENT_UNSPECIFIED:
                default:
                    return mService.whitelistAppTemporarily(
                            packageName, mContext.getUserId(), reasonCode, reason);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public static @ReasonCode int getReasonCodeFromProcState(int procState) {
        if (procState <= PROCESS_STATE_PERSISTENT) {
            return REASON_PROC_STATE_PERSISTENT;
        } else if (procState <= PROCESS_STATE_PERSISTENT_UI) {
            return REASON_PROC_STATE_PERSISTENT_UI;
        } else if (procState <= PROCESS_STATE_TOP) {
            return REASON_PROC_STATE_TOP;
        } else if (procState <= PROCESS_STATE_BOUND_TOP) {
            return REASON_PROC_STATE_BTOP;
        } else if (procState <= PROCESS_STATE_FOREGROUND_SERVICE) {
            return REASON_PROC_STATE_FGS;
        } else if (procState <= PROCESS_STATE_BOUND_FOREGROUND_SERVICE) {
            return REASON_PROC_STATE_BFGS;
        } else {
            return REASON_DENIED;
        }
    }

    /**
     * @hide
     * @return the reason code mapped to statsd for the AppBackgroundRestrictionsInfo atom.
     */
    public static int getExemptionReasonForStatsd(@ReasonCode int reasonCode) {
        switch (reasonCode) {
            case REASON_SYSTEM_UID:
                return AppBackgroundRestrictionsInfo.REASON_SYSTEM_UID;
            case REASON_ALLOWLISTED_PACKAGE:
                return AppBackgroundRestrictionsInfo.REASON_ALLOWLISTED_PACKAGE;
            case REASON_COMPANION_DEVICE_MANAGER:
                return AppBackgroundRestrictionsInfo.REASON_COMPANION_DEVICE_MANAGER;
            case REASON_DEVICE_DEMO_MODE:
                return AppBackgroundRestrictionsInfo.REASON_DEVICE_DEMO_MODE;
            case REASON_DEVICE_OWNER:
                return AppBackgroundRestrictionsInfo.REASON_DEVICE_OWNER;
            case REASON_PROFILE_OWNER:
                return AppBackgroundRestrictionsInfo.REASON_PROFILE_OWNER;
            case REASON_PROC_STATE_PERSISTENT:
                return AppBackgroundRestrictionsInfo.REASON_PROC_STATE_PERSISTENT;
            case REASON_PROC_STATE_PERSISTENT_UI:
                return AppBackgroundRestrictionsInfo.REASON_PROC_STATE_PERSISTENT_UI;
            case REASON_OP_ACTIVATE_VPN:
                return AppBackgroundRestrictionsInfo.REASON_OP_ACTIVATE_VPN;
            case REASON_OP_ACTIVATE_PLATFORM_VPN:
                return AppBackgroundRestrictionsInfo.REASON_OP_ACTIVATE_PLATFORM_VPN;
            case REASON_SYSTEM_MODULE:
                return AppBackgroundRestrictionsInfo.REASON_SYSTEM_MODULE;
            case REASON_CARRIER_PRIVILEGED_APP:
                return AppBackgroundRestrictionsInfo.REASON_CARRIER_PRIVILEGED_APP;
            case REASON_SYSTEM_ALLOW_LISTED:
                return AppBackgroundRestrictionsInfo.REASON_SYSTEM_ALLOW_LISTED;
            case REASON_ROLE_DIALER:
                return AppBackgroundRestrictionsInfo.REASON_ROLE_DIALER;
            case REASON_ROLE_EMERGENCY:
                return AppBackgroundRestrictionsInfo.REASON_ROLE_EMERGENCY;
            default:
                return AppBackgroundRestrictionsInfo.REASON_DENIED;
        }
    }

    /**
     * Return string name of the integer reason code.
     * @hide
     * @param reasonCode
     * @return string name of the reason code.
     */
    public static String reasonCodeToString(@ReasonCode int reasonCode) {
        switch (reasonCode) {
            case REASON_DENIED:
                return "DENIED";
            case REASON_UNKNOWN:
                return "UNKNOWN";
            case REASON_OTHER:
                return "OTHER";
            case REASON_PROC_STATE_PERSISTENT:
                return "PROC_STATE_PERSISTENT";
            case REASON_PROC_STATE_PERSISTENT_UI:
                return "PROC_STATE_PERSISTENT_UI";
            case REASON_PROC_STATE_TOP:
                return "PROC_STATE_TOP";
            case REASON_PROC_STATE_BTOP:
                return "PROC_STATE_BTOP";
            case REASON_PROC_STATE_FGS:
                return "PROC_STATE_FGS";
            case REASON_PROC_STATE_BFGS:
                return "PROC_STATE_BFGS";
            case REASON_UID_VISIBLE:
                return "UID_VISIBLE";
            case REASON_SYSTEM_UID:
                return "SYSTEM_UID";
            case REASON_ACTIVITY_STARTER:
                return "ACTIVITY_STARTER";
            case REASON_START_ACTIVITY_FLAG:
                return "START_ACTIVITY_FLAG";
            case REASON_FGS_BINDING:
                return "FGS_BINDING";
            case REASON_DEVICE_OWNER:
                return "DEVICE_OWNER";
            case REASON_PROFILE_OWNER:
                return "PROFILE_OWNER";
            case REASON_COMPANION_DEVICE_MANAGER:
                return "COMPANION_DEVICE_MANAGER";
            case REASON_BACKGROUND_ACTIVITY_PERMISSION:
                return "BACKGROUND_ACTIVITY_PERMISSION";
            case REASON_BACKGROUND_FGS_PERMISSION:
                return "BACKGROUND_FGS_PERMISSION";
            case REASON_INSTR_BACKGROUND_ACTIVITY_PERMISSION:
                return "INSTR_BACKGROUND_ACTIVITY_PERMISSION";
            case REASON_INSTR_BACKGROUND_FGS_PERMISSION:
                return "INSTR_BACKGROUND_FGS_PERMISSION";
            case REASON_SYSTEM_ALERT_WINDOW_PERMISSION:
                return "SYSTEM_ALERT_WINDOW_PERMISSION";
            case REASON_DEVICE_DEMO_MODE:
                return "DEVICE_DEMO_MODE";
            case REASON_ALLOWLISTED_PACKAGE:
                return "ALLOWLISTED_PACKAGE";
            case REASON_APPOP:
                return "APPOP";
            case REASON_ACTIVITY_VISIBILITY_GRACE_PERIOD:
                return "ACTIVITY_VISIBILITY_GRACE_PERIOD";
            case REASON_OP_ACTIVATE_VPN:
                return "OP_ACTIVATE_VPN";
            case REASON_OP_ACTIVATE_PLATFORM_VPN:
                return "OP_ACTIVATE_PLATFORM_VPN";
            case REASON_CURRENT_INPUT_METHOD:
                return "CURRENT_INPUT_METHOD";
            case REASON_TEMP_ALLOWED_WHILE_IN_USE:
                return "TEMP_ALLOWED_WHILE_IN_USE";
            case REASON_GEOFENCING:
                return "GEOFENCING";
            case REASON_PUSH_MESSAGING:
                return "PUSH_MESSAGING";
            case REASON_PUSH_MESSAGING_OVER_QUOTA:
                return "PUSH_MESSAGING_OVER_QUOTA";
            case REASON_ACTIVITY_RECOGNITION:
                return "ACTIVITY_RECOGNITION";
            case REASON_ACCOUNT_TRANSFER:
                return "REASON_ACCOUNT_TRANSFER";
            case REASON_BOOT_COMPLETED:
                return "BOOT_COMPLETED";
            case REASON_PRE_BOOT_COMPLETED:
                return "PRE_BOOT_COMPLETED";
            case REASON_LOCKED_BOOT_COMPLETED:
                return "LOCKED_BOOT_COMPLETED";
            case REASON_BLUETOOTH_BROADCAST:
                return "BLUETOOTH_BROADCAST";
            case REASON_TIMEZONE_CHANGED:
                return "TIMEZONE_CHANGED";
            case REASON_TIME_CHANGED:
                return "TIME_CHANGED";
            case REASON_LOCALE_CHANGED:
                return "LOCALE_CHANGED";
            case REASON_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED:
                return "REASON_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED";
            case REASON_REFRESH_SAFETY_SOURCES:
                return "REASON_REFRESH_SAFETY_SOURCES";
            case REASON_SYSTEM_ALLOW_LISTED:
                return "SYSTEM_ALLOW_LISTED";
            case REASON_ALARM_MANAGER_ALARM_CLOCK:
                return "ALARM_MANAGER_ALARM_CLOCK";
            case REASON_ALARM_MANAGER_WHILE_IDLE:
                return "ALARM_MANAGER_WHILE_IDLE";
            case REASON_SERVICE_LAUNCH:
                return "SERVICE_LAUNCH";
            case REASON_KEY_CHAIN:
                return "KEY_CHAIN";
            case REASON_PACKAGE_VERIFIER:
                return "PACKAGE_VERIFIER";
            case REASON_SYNC_MANAGER:
                return "SYNC_MANAGER";
            case REASON_DOMAIN_VERIFICATION_V1:
                return "DOMAIN_VERIFICATION_V1";
            case REASON_DOMAIN_VERIFICATION_V2:
                return "DOMAIN_VERIFICATION_V2";
            case REASON_VPN:
                return "VPN";
            case REASON_NOTIFICATION_SERVICE:
                return "NOTIFICATION_SERVICE";
            case REASON_PACKAGE_REPLACED:
                return "PACKAGE_REPLACED";
            case REASON_LOCATION_PROVIDER:
                return "LOCATION_PROVIDER";
            case REASON_MEDIA_BUTTON:
                return "MEDIA_BUTTON";
            case REASON_EVENT_SMS:
                return "EVENT_SMS";
            case REASON_EVENT_MMS:
                return "EVENT_MMS";
            case REASON_SHELL:
                return "SHELL";
            case REASON_MEDIA_SESSION_CALLBACK:
                return "MEDIA_SESSION_CALLBACK";
            case REASON_ROLE_DIALER:
                return "ROLE_DIALER";
            case REASON_ROLE_EMERGENCY:
                return "ROLE_EMERGENCY";
            case REASON_SYSTEM_MODULE:
                return "SYSTEM_MODULE";
            case REASON_CARRIER_PRIVILEGED_APP:
                return "CARRIER_PRIVILEGED_APP";
            case REASON_OPT_OUT_REQUESTED:
                return "REASON_OPT_OUT_REQUESTED";
            default:
                return "(unknown:" + reasonCode + ")";
        }
    }
}
