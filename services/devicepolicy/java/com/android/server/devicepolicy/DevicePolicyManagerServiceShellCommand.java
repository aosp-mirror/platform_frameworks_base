/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.server.devicepolicy;

import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.app.admin.flags.Flags;
import android.content.ComponentName;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.UserHandle;

import java.io.PrintWriter;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

final class DevicePolicyManagerServiceShellCommand extends ShellCommand {

    private static final String CMD_IS_SAFE_OPERATION = "is-operation-safe";
    private static final String CMD_IS_SAFE_OPERATION_BY_REASON = "is-operation-safe-by-reason";
    private static final String CMD_SET_SAFE_OPERATION = "set-operation-safe";
    private static final String CMD_LIST_OWNERS = "list-owners";
    private static final String CMD_LIST_POLICY_EXEMPT_APPS = "list-policy-exempt-apps";
    private static final String CMD_SET_ACTIVE_ADMIN = "set-active-admin";
    private static final String CMD_SET_DEVICE_OWNER = "set-device-owner";
    private static final String CMD_SET_PROFILE_OWNER = "set-profile-owner";
    private static final String CMD_REMOVE_ACTIVE_ADMIN = "remove-active-admin";
    private static final String CMD_CLEAR_FREEZE_PERIOD_RECORD = "clear-freeze-period-record";
    private static final String CMD_FORCE_NETWORK_LOGS = "force-network-logs";
    private static final String CMD_FORCE_SECURITY_LOGS = "force-security-logs";
    private static final String CMD_MARK_PO_ON_ORG_OWNED_DEVICE =
            "mark-profile-owner-on-organization-owned-device";

    private static final String USER_OPTION = "--user";
    private static final String DO_ONLY_OPTION = "--device-owner-only";
    private static final String PROVISIONING_CONTEXT_OPTION = "--provisioning-context";

    private final DevicePolicyManagerService mService;
    private int mUserId = UserHandle.USER_SYSTEM;
    private ComponentName mComponent;
    private boolean mSetDoOnly;
    private String mProvisioningContext = null;

    DevicePolicyManagerServiceShellCommand(DevicePolicyManagerService service) {
        mService = Objects.requireNonNull(service);
    }

    @Override
    public void onHelp() {
        try (PrintWriter pw = getOutPrintWriter()) {
            pw.printf("DevicePolicyManager Service (device_policy) commands:\n\n");
            showHelp(pw);
        }
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        try (PrintWriter pw = getOutPrintWriter()) {
            switch (cmd) {
                case CMD_IS_SAFE_OPERATION:
                    return runIsSafeOperation(pw);
                case CMD_IS_SAFE_OPERATION_BY_REASON:
                    return runIsSafeOperationByReason(pw);
                case CMD_SET_SAFE_OPERATION:
                    return runSetSafeOperation(pw);
                case CMD_LIST_OWNERS:
                    return runListOwners(pw);
                case CMD_LIST_POLICY_EXEMPT_APPS:
                    return runListPolicyExemptApps(pw);
                case CMD_SET_ACTIVE_ADMIN:
                    return runSetActiveAdmin(pw);
                case CMD_SET_DEVICE_OWNER:
                    return runSetDeviceOwner(pw);
                case CMD_SET_PROFILE_OWNER:
                    return runSetProfileOwner(pw);
                case CMD_REMOVE_ACTIVE_ADMIN:
                    return runRemoveActiveAdmin(pw);
                case CMD_CLEAR_FREEZE_PERIOD_RECORD:
                    return runClearFreezePeriodRecord(pw);
                case CMD_FORCE_NETWORK_LOGS:
                    return runForceNetworkLogs(pw);
                case CMD_FORCE_SECURITY_LOGS:
                    return runForceSecurityLogs(pw);
                case CMD_MARK_PO_ON_ORG_OWNED_DEVICE:
                    return runMarkProfileOwnerOnOrganizationOwnedDevice(pw);
                default:
                    return onInvalidCommand(pw, cmd);
            }
        }
    }

    private int onInvalidCommand(PrintWriter pw, String cmd) {
        if (super.handleDefaultCommands(cmd) == 0) {
            return 0;
        }

        pw.printf("Usage: \n");
        showHelp(pw);
        return -1;
    }

    private void showHelp(PrintWriter pw) {
        pw.printf("  help\n");
        pw.printf("    Prints this help text.\n\n");
        pw.printf("  %s <OPERATION_ID>\n", CMD_IS_SAFE_OPERATION);
        pw.printf("    Checks if the give operation is safe \n\n");
        pw.printf("  %s <REASON_ID>\n", CMD_IS_SAFE_OPERATION_BY_REASON);
        pw.printf("    Checks if the operations are safe for the given reason\n\n");
        pw.printf("  %s <OPERATION_ID> <REASON_ID>\n", CMD_SET_SAFE_OPERATION);
        pw.printf("    Emulates the result of the next call to check if the given operation is safe"
                + " \n\n");
        pw.printf("  %s\n", CMD_LIST_OWNERS);
        pw.printf("    Lists the device / profile owners per user \n\n");
        pw.printf("  %s\n", CMD_LIST_POLICY_EXEMPT_APPS);
        pw.printf("    Lists the apps that are exempt from policies\n\n");
        if (Flags.provisioningContextParameter()) {
            pw.printf("  %s [ %s <USER_ID> | current ] [ %s <PROVISIONING_CONTEXT>] <COMPONENT>\n",
                    CMD_SET_ACTIVE_ADMIN, USER_OPTION, PROVISIONING_CONTEXT_OPTION);
            pw.printf("    Sets the given component as active admin for an existing user.\n\n");
            pw.printf("  %s [ %s <USER_ID> | current *EXPERIMENTAL* ] [ %s ]"
                            + " [ %s <PROVISIONING_CONTEXT>] <COMPONENT>\n",
                    CMD_SET_DEVICE_OWNER, USER_OPTION, DO_ONLY_OPTION, PROVISIONING_CONTEXT_OPTION);
            pw.printf("    Sets the given component as active admin, and its package as device"
                    + " owner.\n\n");
            pw.printf("  %s [ %s <USER_ID> | current ] [ %s <PROVISIONING_CONTEXT>] <COMPONENT>\n",
                    CMD_SET_PROFILE_OWNER, USER_OPTION, PROVISIONING_CONTEXT_OPTION);
        } else {
            pw.printf("  %s [ %s <USER_ID> | current ] <COMPONENT>\n",
                    CMD_SET_ACTIVE_ADMIN, USER_OPTION);
            pw.printf("    Sets the given component as active admin for an existing user.\n\n");
            pw.printf("  %s [ %s <USER_ID> | current *EXPERIMENTAL* ] [ %s ]"
                    + "<COMPONENT>\n", CMD_SET_DEVICE_OWNER, USER_OPTION, DO_ONLY_OPTION);
            pw.printf("    Sets the given component as active admin, and its package as device"
                    + " owner.\n\n");
            pw.printf("  %s [ %s <USER_ID> | current ] <COMPONENT>\n",
                    CMD_SET_PROFILE_OWNER, USER_OPTION);
        }
        pw.printf("    Sets the given component as active admin and profile owner for an existing "
                + "user.\n\n");
        pw.printf("  %s [ %s <USER_ID> | current ] <COMPONENT>\n",
                CMD_REMOVE_ACTIVE_ADMIN, USER_OPTION);
        pw.printf("    Disables an active admin, the admin must have declared android:testOnly in "
                + "the application in its manifest. This will also remove device and profile "
                + "owners.\n\n");
        pw.printf("  %s\n", CMD_CLEAR_FREEZE_PERIOD_RECORD);
        pw.printf("    Clears framework-maintained record of past freeze periods that the device "
                + "went through. For use during feature development to prevent triggering "
                + "restriction on setting freeze periods.\n\n");
        pw.printf("  %s\n", CMD_FORCE_NETWORK_LOGS);
        pw.printf("    Makes all network logs available to the DPC and triggers "
                + "DeviceAdminReceiver.onNetworkLogsAvailable() if needed.\n\n");
        pw.printf("  %s\n", CMD_FORCE_SECURITY_LOGS);
        pw.printf("    Makes all security logs available to the DPC and triggers "
                + "DeviceAdminReceiver.onSecurityLogsAvailable() if needed.\n\n");
        pw.printf("  %s [ %s <USER_ID> | current ] <COMPONENT>\n",
                CMD_MARK_PO_ON_ORG_OWNED_DEVICE, USER_OPTION);
        pw.printf("    Marks the profile owner of the given user as managing an organization-owned"
                + "device. That will give it access to device identifiers (such as serial number, "
                + "IMEI and MEID), as well as other privileges.\n\n");
    }

    private int runIsSafeOperation(PrintWriter pw) {
        int operation = Integer.parseInt(getNextArgRequired());
        int reason = mService.getUnsafeOperationReason(operation);
        boolean safe = reason == DevicePolicyManager.OPERATION_SAFETY_REASON_NONE;
        pw.printf("Operation %s is %s. Reason: %s\n",
                DevicePolicyManager.operationToString(operation), safeToString(safe),
                DevicePolicyManager.operationSafetyReasonToString(reason));
        return 0;
    }

    private int runIsSafeOperationByReason(PrintWriter pw) {
        int reason = Integer.parseInt(getNextArgRequired());
        boolean safe = mService.isSafeOperation(reason);
        pw.printf("Operations affected by %s are %s\n",
                DevicePolicyManager.operationSafetyReasonToString(reason), safeToString(safe));
        return 0;
    }

    private static String safeToString(boolean safe) {
        return safe ? "SAFE" : "UNSAFE";
    }

    private int runSetSafeOperation(PrintWriter pw) {
        int operation = Integer.parseInt(getNextArgRequired());
        int reason = Integer.parseInt(getNextArgRequired());
        mService.setNextOperationSafety(operation, reason);
        pw.printf("Next call to check operation %s will return %s\n",
                DevicePolicyManager.operationToString(operation),
                DevicePolicyManager.operationSafetyReasonToString(reason));
        return 0;
    }

    private int printAndGetSize(PrintWriter pw, Collection<?> collection, String nameOnSingular) {
        if (collection.isEmpty()) {
            pw.printf("no %ss\n", nameOnSingular);
            return 0;
        }
        int size = collection.size();
        pw.printf("%d %s%s:\n", size, nameOnSingular, (size == 1 ? "" : "s"));
        return size;
    }

    private int runListOwners(PrintWriter pw) {
        List<OwnerShellData> owners = mService.listAllOwners();
        int size = printAndGetSize(pw, owners, "owner");
        if (size == 0) return 0;

        for (int i = 0; i < size; i++) {
            OwnerShellData owner = owners.get(i);
            pw.printf("User %2d: admin=%s", owner.userId, owner.admin.flattenToShortString());
            if (owner.isDeviceOwner) {
                pw.print(",DeviceOwner");
            }
            if (owner.isProfileOwner) {
                pw.print(",ProfileOwner");
            }
            if (owner.isManagedProfileOwner) {
                pw.printf(",ManagedProfileOwner(parentUserId=%d)", owner.parentUserId);
            }
            if (owner.isAffiliated) {
                pw.print(",Affiliated");
            }
            pw.println();
        }

        return 0;
    }

    private int runListPolicyExemptApps(PrintWriter pw) {
        List<String> apps = mService.listPolicyExemptApps();
        int size = printAndGetSize(pw, apps, "policy exempt app");

        if (size == 0) return 0;

        for (int i = 0; i < size; i++) {
            String app = apps.get(i);
            pw.printf("  %d: %s\n", i, app);
        }
        return 0;
    }

    private int runSetActiveAdmin(PrintWriter pw) {
        parseArgs();
        mService.setActiveAdmin(mComponent, /* refreshing= */ true, mUserId, mProvisioningContext);

        pw.printf("Success: Active admin set to component %s\n", mComponent.flattenToShortString());
        return 0;
    }

    private int runSetDeviceOwner(PrintWriter pw) {
        parseArgs();
        boolean isAdminAdded = false;
        try {
            mService.setActiveAdmin(
                    mComponent,
                    /* refreshing= */ false,
                    mUserId,
                    mProvisioningContext
            );
            isAdminAdded = true;
        } catch (IllegalArgumentException e) {
            pw.printf("%s was already an admin for user %d. No need to set it again.\n",
                    mComponent.flattenToShortString(), mUserId);
        }

        try {
            if (!mService.setDeviceOwner(mComponent, mUserId,
                    /* setProfileOwnerOnCurrentUserIfNecessary= */ !mSetDoOnly)) {
                throw new RuntimeException(
                        "Can't set package " + mComponent + " as device owner.");
            }
        } catch (Exception e) {
            if (isAdminAdded) {
                // Need to remove the admin that we just added.
                mService.removeActiveAdmin(mComponent, mUserId);
            }
            throw e;
        }

        mService.setUserProvisioningState(
                DevicePolicyManager.STATE_USER_SETUP_FINALIZED, mUserId);

        pw.printf("Success: Device owner set to package %s\n", mComponent.flattenToShortString());
        pw.printf("Active admin set to component %s\n", mComponent.flattenToShortString());
        return 0;
    }

    private int runRemoveActiveAdmin(PrintWriter pw) {
        parseArgs();
        mService.forceRemoveActiveAdmin(mComponent, mUserId);
        pw.printf("Success: Admin removed %s\n", mComponent);
        return 0;
    }

    private int runSetProfileOwner(PrintWriter pw) {
        parseArgs();
        mService.setActiveAdmin(mComponent, /* refreshing= */ true, mUserId, mProvisioningContext);

        try {
            if (!mService.setProfileOwner(mComponent, mUserId)) {
                throw new RuntimeException("Can't set component "
                        + mComponent.flattenToShortString() + " as profile owner for user "
                        + mUserId);
            }
        } catch (Exception e) {
            // Need to remove the admin that we just added.
            mService.removeActiveAdmin(mComponent, mUserId);
            throw e;
        }

        mService.setUserProvisioningState(
                DevicePolicyManager.STATE_USER_SETUP_FINALIZED, mUserId);

        pw.printf("Success: Active admin and profile owner set to %s for user %d\n",
                mComponent.flattenToShortString(), mUserId);
        return 0;
    }

    private int runClearFreezePeriodRecord(PrintWriter pw) {
        mService.clearSystemUpdatePolicyFreezePeriodRecord();
        pw.printf("Success\n");
        return 0;
    }

    private int runForceNetworkLogs(PrintWriter pw) {
        while (true) {
            long toWait = mService.forceNetworkLogs();
            if (toWait == 0) {
                break;
            }
            pw.printf("We have to wait for %d milliseconds...\n", toWait);
            SystemClock.sleep(toWait);
        }
        pw.printf("Success\n");
        return 0;
    }

    private int runForceSecurityLogs(PrintWriter pw) {
        while (true) {
            long toWait = mService.forceSecurityLogs();
            if (toWait == 0) {
                break;
            }
            pw.printf("We have to wait for %d milliseconds...\n", toWait);
            SystemClock.sleep(toWait);
        }
        pw.printf("Success\n");
        return 0;
    }

    private int runMarkProfileOwnerOnOrganizationOwnedDevice(PrintWriter pw) {
        parseArgs();
        mService.setProfileOwnerOnOrganizationOwnedDevice(mComponent, mUserId, true);
        pw.printf("Success\n");
        return 0;
    }

    private void parseArgs() {
        String opt;
        while ((opt = getNextOption()) != null) {
            if (USER_OPTION.equals(opt)) {
                String arg = getNextArgRequired();
                mUserId = UserHandle.parseUserArg(arg);
                if (mUserId == UserHandle.USER_CURRENT) {
                    mUserId = ActivityManager.getCurrentUser();
                }
            } else if (DO_ONLY_OPTION.equals(opt)) {
                mSetDoOnly = true;
            } else if (PROVISIONING_CONTEXT_OPTION.equals(opt)) {
                mProvisioningContext = getNextArgRequired();
            } else {
                throw new IllegalArgumentException("Unknown option: " + opt);
            }
        }
        mComponent = parseComponentName(getNextArgRequired());
    }

    private ComponentName parseComponentName(String component) {
        ComponentName cn = ComponentName.unflattenFromString(component);
        if (cn == null) {
            throw new IllegalArgumentException("Invalid component " + component);
        }
        return cn;
    }
}
