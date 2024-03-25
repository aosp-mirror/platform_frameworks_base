/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PERMISSIONS_REVIEW;
import static com.android.server.am.ActivityManagerService.checkComponentPermission;
import static com.android.server.am.BroadcastQueue.TAG;
import static com.android.server.am.Flags.usePermissionManagerForBroadcastDeliveryCheck;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.BroadcastOptions;
import android.app.PendingIntent;
import android.content.AttributionSource;
import android.content.ComponentName;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ResolveInfo;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.permission.IPermissionManager;
import android.permission.PermissionManager;
import android.util.Slog;

import com.android.internal.util.ArrayUtils;

import java.util.Objects;

/**
 * Policy logic that decides if delivery of a particular {@link BroadcastRecord}
 * should be skipped for a given {@link ResolveInfo} or {@link BroadcastFilter}.
 * <p>
 * This policy should be consulted as close as possible to the actual dispatch.
 */
public class BroadcastSkipPolicy {
    private final ActivityManagerService mService;

    @Nullable
    private PermissionManager mPermissionManager;

    public BroadcastSkipPolicy(@NonNull ActivityManagerService service) {
        mService = Objects.requireNonNull(service);
    }

    /**
     * Determine if the given {@link BroadcastRecord} is eligible to be sent to
     * the given {@link BroadcastFilter} or {@link ResolveInfo}.
     *
     * @return message indicating why the argument should be skipped, otherwise
     *         {@code null} if it can proceed.
     */
    public @Nullable String shouldSkipMessage(@NonNull BroadcastRecord r, @NonNull Object target) {
        if (target instanceof BroadcastFilter) {
            return shouldSkipMessage(r, (BroadcastFilter) target);
        } else {
            return shouldSkipMessage(r, (ResolveInfo) target);
        }
    }

    /**
     * Determine if the given {@link BroadcastRecord} is eligible to be sent to
     * the given {@link ResolveInfo}.
     *
     * @return message indicating why the argument should be skipped, otherwise
     *         {@code null} if it can proceed.
     */
    private @Nullable String shouldSkipMessage(@NonNull BroadcastRecord r,
            @NonNull ResolveInfo info) {
        final BroadcastOptions brOptions = r.options;
        final ComponentName component = new ComponentName(
                info.activityInfo.applicationInfo.packageName,
                info.activityInfo.name);

        if (brOptions != null &&
                (info.activityInfo.applicationInfo.targetSdkVersion
                        < brOptions.getMinManifestReceiverApiLevel() ||
                info.activityInfo.applicationInfo.targetSdkVersion
                        > brOptions.getMaxManifestReceiverApiLevel())) {
            return "Target SDK mismatch: receiver " + info.activityInfo
                    + " targets " + info.activityInfo.applicationInfo.targetSdkVersion
                    + " but delivery restricted to ["
                    + brOptions.getMinManifestReceiverApiLevel() + ", "
                    + brOptions.getMaxManifestReceiverApiLevel()
                    + "] broadcasting " + broadcastDescription(r, component);
        }
        if (brOptions != null &&
                !brOptions.testRequireCompatChange(info.activityInfo.applicationInfo.uid)) {
            return "Compat change filtered: broadcasting " + broadcastDescription(r, component)
                    + " to uid " + info.activityInfo.applicationInfo.uid + " due to compat change "
                    + r.options.getRequireCompatChangeId();
        }
        if (!mService.validateAssociationAllowedLocked(r.callerPackage, r.callingUid,
                component.getPackageName(), info.activityInfo.applicationInfo.uid)) {
            return "Association not allowed: broadcasting "
                    + broadcastDescription(r, component);
        }
        if (!mService.mIntentFirewall.checkBroadcast(r.intent, r.callingUid,
                r.callingPid, r.resolvedType, info.activityInfo.applicationInfo.uid)) {
            return "Firewall blocked: broadcasting "
                    + broadcastDescription(r, component);
        }
        int perm = checkComponentPermission(info.activityInfo.permission,
                r.callingPid, r.callingUid, info.activityInfo.applicationInfo.uid,
                info.activityInfo.exported);
        if (perm != PackageManager.PERMISSION_GRANTED) {
            if (!info.activityInfo.exported) {
                return "Permission Denial: broadcasting "
                        + broadcastDescription(r, component)
                        + " is not exported from uid " + info.activityInfo.applicationInfo.uid;
            } else {
                return "Permission Denial: broadcasting "
                        + broadcastDescription(r, component)
                        + " requires " + info.activityInfo.permission;
            }
        } else if (info.activityInfo.permission != null) {
            final int opCode = AppOpsManager.permissionToOpCode(info.activityInfo.permission);
            if (opCode != AppOpsManager.OP_NONE && mService.getAppOpsManager().noteOpNoThrow(opCode,
                    r.callingUid, r.callerPackage, r.callerFeatureId,
                    "Broadcast delivered to " + info.activityInfo.name)
                    != AppOpsManager.MODE_ALLOWED) {
                return "Appop Denial: broadcasting "
                        + broadcastDescription(r, component)
                        + " requires appop " + AppOpsManager.permissionToOp(
                                info.activityInfo.permission);
            }
        }

        if ((info.activityInfo.flags&ActivityInfo.FLAG_SINGLE_USER) != 0) {
            if (ActivityManager.checkUidPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS,
                    info.activityInfo.applicationInfo.uid)
                            != PackageManager.PERMISSION_GRANTED) {
                return "Permission Denial: Receiver " + component.flattenToShortString()
                        + " requests FLAG_SINGLE_USER, but app does not hold "
                        + android.Manifest.permission.INTERACT_ACROSS_USERS;
            }
        }
        if (info.activityInfo.applicationInfo.isInstantApp()
                && r.callingUid != info.activityInfo.applicationInfo.uid) {
            return "Instant App Denial: receiving "
                    + r.intent
                    + " to " + component.flattenToShortString()
                    + " due to sender " + r.callerPackage
                    + " (uid " + r.callingUid + ")"
                    + " Instant Apps do not support manifest receivers";
        }
        if (r.callerInstantApp
                && (info.activityInfo.flags & ActivityInfo.FLAG_VISIBLE_TO_INSTANT_APP) == 0
                && r.callingUid != info.activityInfo.applicationInfo.uid) {
            return "Instant App Denial: receiving "
                    + r.intent
                    + " to " + component.flattenToShortString()
                    + " requires receiver have visibleToInstantApps set"
                    + " due to sender " + r.callerPackage
                    + " (uid " + r.callingUid + ")";
        }
        if (r.curApp != null && r.curApp.mErrorState.isCrashing()) {
            // If the target process is crashing, just skip it.
            return "Skipping deliver ordered [" + r.queue.toString() + "] " + r
                    + " to " + r.curApp + ": process crashing";
        }

        boolean isAvailable = false;
        try {
            isAvailable = AppGlobals.getPackageManager().isPackageAvailable(
                    info.activityInfo.packageName,
                    UserHandle.getUserId(info.activityInfo.applicationInfo.uid));
        } catch (Exception e) {
            // all such failures mean we skip this receiver
            return "Exception getting recipient info for "
                    + info.activityInfo.packageName;
        }
        if (!isAvailable) {
            return "Skipping delivery to " + info.activityInfo.packageName + " / "
                    + info.activityInfo.applicationInfo.uid
                    + " : package no longer available";
        }

        // If permissions need a review before any of the app components can run, we drop
        // the broadcast and if the calling app is in the foreground and the broadcast is
        // explicit we launch the review UI passing it a pending intent to send the skipped
        // broadcast.
        if (!requestStartTargetPermissionsReviewIfNeededLocked(r,
                info.activityInfo.packageName, UserHandle.getUserId(
                        info.activityInfo.applicationInfo.uid))) {
            return "Skipping delivery: permission review required for "
                            + broadcastDescription(r, component);
        }

        final int allowed = mService.getAppStartModeLOSP(
                info.activityInfo.applicationInfo.uid, info.activityInfo.packageName,
                info.activityInfo.applicationInfo.targetSdkVersion, -1, true, false, false);
        if (allowed != ActivityManager.APP_START_MODE_NORMAL) {
            // We won't allow this receiver to be launched if the app has been
            // completely disabled from launches, or it was not explicitly sent
            // to it and the app is in a state that should not receive it
            // (depending on how getAppStartModeLOSP has determined that).
            if (allowed == ActivityManager.APP_START_MODE_DISABLED) {
                return "Background execution disabled: receiving "
                        + r.intent + " to "
                        + component.flattenToShortString();
            } else if (disallowBackgroundStart(r)) {
                mService.addBackgroundCheckViolationLocked(r.intent.getAction(),
                        component.getPackageName());
                return "Background execution not allowed: receiving "
                        + r.intent + " to "
                        + component.flattenToShortString();
            }
        }

        if (!Intent.ACTION_SHUTDOWN.equals(r.intent.getAction())
                && !mService.mUserController
                .isUserRunning(UserHandle.getUserId(info.activityInfo.applicationInfo.uid),
                        0 /* flags */)) {
            return "Skipping delivery to " + info.activityInfo.packageName + " / "
                            + info.activityInfo.applicationInfo.uid + " : user is not running";
        }

        if (r.excludedPermissions != null && r.excludedPermissions.length > 0) {
            for (int i = 0; i < r.excludedPermissions.length; i++) {
                String excludedPermission = r.excludedPermissions[i];
                try {
                    perm = AppGlobals.getPackageManager()
                        .checkPermission(excludedPermission,
                                info.activityInfo.applicationInfo.packageName,
                                UserHandle
                                .getUserId(info.activityInfo.applicationInfo.uid));
                } catch (RemoteException e) {
                    perm = PackageManager.PERMISSION_DENIED;
                }

                int appOp = AppOpsManager.permissionToOpCode(excludedPermission);
                if (appOp != AppOpsManager.OP_NONE) {
                    // When there is an app op associated with the permission,
                    // skip when both the permission and the app op are
                    // granted.
                    if ((perm == PackageManager.PERMISSION_GRANTED) && (
                                mService.getAppOpsManager().checkOpNoThrow(appOp,
                                info.activityInfo.applicationInfo.uid,
                                info.activityInfo.packageName)
                            == AppOpsManager.MODE_ALLOWED)) {
                        return "Skipping delivery to " + info.activityInfo.packageName
                                + " due to excluded permission " + excludedPermission;
                    }
                } else {
                    // When there is no app op associated with the permission,
                    // skip when permission is granted.
                    if (perm == PackageManager.PERMISSION_GRANTED) {
                        return "Skipping delivery to " + info.activityInfo.packageName
                                + " due to excluded permission " + excludedPermission;
                    }
                }
            }
        }

        // Check that the receiver does *not* belong to any of the excluded packages
        if (r.excludedPackages != null && r.excludedPackages.length > 0) {
            if (ArrayUtils.contains(r.excludedPackages, component.getPackageName())) {
                return "Skipping delivery of excluded package "
                        + r.intent + " to "
                        + component.flattenToShortString()
                        + " excludes package " + component.getPackageName()
                        + " due to sender " + r.callerPackage
                        + " (uid " + r.callingUid + ")";
            }
        }

        if (info.activityInfo.applicationInfo.uid != Process.SYSTEM_UID &&
                r.requiredPermissions != null && r.requiredPermissions.length > 0) {
            final AttributionSource[] attributionSources;
            if (usePermissionManagerForBroadcastDeliveryCheck()) {
                attributionSources = createAttributionSourcesForResolveInfo(info);
            } else {
                attributionSources = null;
            }
            for (int i = 0; i < r.requiredPermissions.length; i++) {
                String requiredPermission = r.requiredPermissions[i];
                try {
                    if (usePermissionManagerForBroadcastDeliveryCheck()) {
                        perm = hasPermissionForDataDelivery(
                                requiredPermission,
                                "Broadcast delivered to " + info.activityInfo.name,
                                attributionSources)
                                        ? PackageManager.PERMISSION_GRANTED
                                        : PackageManager.PERMISSION_DENIED;
                    } else {
                        perm = AppGlobals.getPackageManager()
                                .checkPermission(
                                        requiredPermission,
                                        info.activityInfo.applicationInfo.packageName,
                                        UserHandle
                                                .getUserId(info.activityInfo.applicationInfo.uid));
                    }
                } catch (RemoteException e) {
                    perm = PackageManager.PERMISSION_DENIED;
                }
                if (perm != PackageManager.PERMISSION_GRANTED) {
                    return "Permission Denial: receiving "
                            + r.intent + " to "
                            + component.flattenToShortString()
                            + " requires " + requiredPermission
                            + " due to sender " + r.callerPackage
                            + " (uid " + r.callingUid + ")";
                }
                if (!usePermissionManagerForBroadcastDeliveryCheck()) {
                    int appOp = AppOpsManager.permissionToOpCode(requiredPermission);
                    if (appOp != AppOpsManager.OP_NONE && appOp != r.appOp) {
                        if (!noteOpForManifestReceiver(appOp, r, info, component)) {
                            return "Skipping delivery to " + info.activityInfo.packageName
                                    + " due to required appop " + appOp;
                        }
                    }
                }
            }
        }
        if (r.appOp != AppOpsManager.OP_NONE) {
            if (!noteOpForManifestReceiver(r.appOp, r, info, component)) {
                return "Skipping delivery to " + info.activityInfo.packageName
                        + " due to required appop " + r.appOp;
            }
        }

        return null;
    }

    /**
     * Determine if the given {@link BroadcastRecord} is eligible to launch processes.
     */
    public boolean disallowBackgroundStart(@NonNull BroadcastRecord r) {
        return ((r.intent.getFlags() & Intent.FLAG_RECEIVER_EXCLUDE_BACKGROUND) != 0)
                || (r.intent.getComponent() == null
                        && r.intent.getPackage() == null
                        && ((r.intent.getFlags()
                                        & Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND) == 0)
                        && !isSignaturePerm(r.requiredPermissions));
    }

    /**
     * Determine if the given {@link BroadcastRecord} is eligible to be sent to
     * the given {@link BroadcastFilter}.
     *
     * @return message indicating why the argument should be skipped, otherwise
     *         {@code null} if it can proceed.
     */
    private @Nullable String shouldSkipMessage(@NonNull BroadcastRecord r,
            @NonNull BroadcastFilter filter) {
        if (r.options != null && !r.options.testRequireCompatChange(filter.owningUid)) {
            return "Compat change filtered: broadcasting " + r.intent.toString()
                    + " to uid " + filter.owningUid + " due to compat change "
                    + r.options.getRequireCompatChangeId();
        }
        if (!mService.validateAssociationAllowedLocked(r.callerPackage, r.callingUid,
                filter.packageName, filter.owningUid)) {
            return "Association not allowed: broadcasting "
                    + r.intent.toString()
                    + " from " + r.callerPackage + " (pid=" + r.callingPid
                    + ", uid=" + r.callingUid + ") to " + filter.packageName + " through "
                    + filter;
        }
        if (!mService.mIntentFirewall.checkBroadcast(r.intent, r.callingUid,
                r.callingPid, r.resolvedType, filter.receiverList.uid)) {
            return "Firewall blocked: broadcasting "
                    + r.intent.toString()
                    + " from " + r.callerPackage + " (pid=" + r.callingPid
                    + ", uid=" + r.callingUid + ") to " + filter.packageName + " through "
                    + filter;
        }
        // Check that the sender has permission to send to this receiver
        if (filter.requiredPermission != null) {
            int perm = checkComponentPermission(filter.requiredPermission,
                    r.callingPid, r.callingUid, -1, true);
            if (perm != PackageManager.PERMISSION_GRANTED) {
                return "Permission Denial: broadcasting "
                        + r.intent.toString()
                        + " from " + r.callerPackage + " (pid="
                        + r.callingPid + ", uid=" + r.callingUid + ")"
                        + " requires " + filter.requiredPermission
                        + " due to registered receiver " + filter;
            } else {
                final int opCode = AppOpsManager.permissionToOpCode(filter.requiredPermission);
                if (opCode != AppOpsManager.OP_NONE
                        && mService.getAppOpsManager().noteOpNoThrow(opCode, r.callingUid,
                        r.callerPackage, r.callerFeatureId, "Broadcast sent to protected receiver")
                        != AppOpsManager.MODE_ALLOWED) {
                    return "Appop Denial: broadcasting "
                            + r.intent.toString()
                            + " from " + r.callerPackage + " (pid="
                            + r.callingPid + ", uid=" + r.callingUid + ")"
                            + " requires appop " + AppOpsManager.permissionToOp(
                                    filter.requiredPermission)
                            + " due to registered receiver " + filter;
                }
            }
        }

        if ((filter.receiverList.app == null || filter.receiverList.app.isKilled()
                || filter.receiverList.app.mErrorState.isCrashing())) {
            return "Skipping deliver [" + r.queue.toString() + "] " + r
                    + " to " + filter.receiverList + ": process gone or crashing";
        }

        // Ensure that broadcasts are only sent to other Instant Apps if they are marked as
        // visible to Instant Apps.
        final boolean visibleToInstantApps =
                (r.intent.getFlags() & Intent.FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS) != 0;

        if (!visibleToInstantApps && filter.instantApp
                && filter.receiverList.uid != r.callingUid) {
            return "Instant App Denial: receiving "
                    + r.intent.toString()
                    + " to " + filter.receiverList.app
                    + " (pid=" + filter.receiverList.pid
                    + ", uid=" + filter.receiverList.uid + ")"
                    + " due to sender " + r.callerPackage
                    + " (uid " + r.callingUid + ")"
                    + " not specifying FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS";
        }

        if (!filter.visibleToInstantApp && r.callerInstantApp
                && filter.receiverList.uid != r.callingUid) {
            return "Instant App Denial: receiving "
                    + r.intent.toString()
                    + " to " + filter.receiverList.app
                    + " (pid=" + filter.receiverList.pid
                    + ", uid=" + filter.receiverList.uid + ")"
                    + " requires receiver be visible to instant apps"
                    + " due to sender " + r.callerPackage
                    + " (uid " + r.callingUid + ")";
        }

        // Check that the receiver has the required permission(s) to receive this broadcast.
        if (r.requiredPermissions != null && r.requiredPermissions.length > 0) {
            final AttributionSource attributionSource;
            if (usePermissionManagerForBroadcastDeliveryCheck()) {
                attributionSource =
                        new AttributionSource.Builder(filter.receiverList.uid)
                                .setPid(filter.receiverList.pid)
                                .setPackageName(filter.packageName)
                                .setAttributionTag(filter.featureId)
                                .build();
            } else {
                attributionSource = null;
            }
            for (int i = 0; i < r.requiredPermissions.length; i++) {
                String requiredPermission = r.requiredPermissions[i];
                final int perm;
                if (usePermissionManagerForBroadcastDeliveryCheck()) {
                    perm = hasPermissionForDataDelivery(
                            requiredPermission,
                            "Broadcast delivered to registered receiver " + filter.receiverId,
                            attributionSource)
                                    ? PackageManager.PERMISSION_GRANTED
                                    : PackageManager.PERMISSION_DENIED;
                } else {
                    perm = checkComponentPermission(
                            requiredPermission,
                            filter.receiverList.pid,
                            filter.receiverList.uid,
                            -1 /* owningUid */,
                            true /* exported */);
                }
                if (perm != PackageManager.PERMISSION_GRANTED) {
                    return "Permission Denial: receiving "
                            + r.intent.toString()
                            + " to " + filter.receiverList.app
                            + " (pid=" + filter.receiverList.pid
                            + ", uid=" + filter.receiverList.uid + ")"
                            + " requires " + requiredPermission
                            + " due to sender " + r.callerPackage
                            + " (uid " + r.callingUid + ")";
                }
                if (!usePermissionManagerForBroadcastDeliveryCheck()) {
                    int appOp = AppOpsManager.permissionToOpCode(requiredPermission);
                    if (appOp != AppOpsManager.OP_NONE && appOp != r.appOp
                            && mService.getAppOpsManager().noteOpNoThrow(appOp,
                            filter.receiverList.uid, filter.packageName, filter.featureId,
                            "Broadcast delivered to registered receiver " + filter.receiverId)
                            != AppOpsManager.MODE_ALLOWED) {
                        return "Appop Denial: receiving "
                                + r.intent.toString()
                                + " to " + filter.receiverList.app
                                + " (pid=" + filter.receiverList.pid
                                + ", uid=" + filter.receiverList.uid + ")"
                                + " requires appop " + AppOpsManager.permissionToOp(
                                requiredPermission)
                                + " due to sender " + r.callerPackage
                                + " (uid " + r.callingUid + ")";
                    }
                }
            }
        }
        if ((r.requiredPermissions == null || r.requiredPermissions.length == 0)) {
            int perm = checkComponentPermission(null,
                    filter.receiverList.pid, filter.receiverList.uid, -1, true);
            if (perm != PackageManager.PERMISSION_GRANTED) {
                return "Permission Denial: security check failed when receiving "
                        + r.intent.toString()
                        + " to " + filter.receiverList.app
                        + " (pid=" + filter.receiverList.pid
                        + ", uid=" + filter.receiverList.uid + ")"
                        + " due to sender " + r.callerPackage
                        + " (uid " + r.callingUid + ")";
            }
        }
        // Check that the receiver does *not* have any excluded permissions
        if (r.excludedPermissions != null && r.excludedPermissions.length > 0) {
            for (int i = 0; i < r.excludedPermissions.length; i++) {
                String excludedPermission = r.excludedPermissions[i];
                final int perm = checkComponentPermission(excludedPermission,
                        filter.receiverList.pid, filter.receiverList.uid, -1, true);

                int appOp = AppOpsManager.permissionToOpCode(excludedPermission);
                if (appOp != AppOpsManager.OP_NONE) {
                    // When there is an app op associated with the permission,
                    // skip when both the permission and the app op are
                    // granted.
                    if ((perm == PackageManager.PERMISSION_GRANTED) && (
                            mService.getAppOpsManager().checkOpNoThrow(appOp,
                                    filter.receiverList.uid,
                                    filter.packageName)
                                    == AppOpsManager.MODE_ALLOWED)) {
                        return "Appop Denial: receiving "
                                + r.intent.toString()
                                + " to " + filter.receiverList.app
                                + " (pid=" + filter.receiverList.pid
                                + ", uid=" + filter.receiverList.uid + ")"
                                + " excludes appop " + AppOpsManager.permissionToOp(
                                excludedPermission)
                                + " due to sender " + r.callerPackage
                                + " (uid " + r.callingUid + ")";
                    }
                } else {
                    // When there is no app op associated with the permission,
                    // skip when permission is granted.
                    if (perm == PackageManager.PERMISSION_GRANTED) {
                        return "Permission Denial: receiving "
                                + r.intent.toString()
                                + " to " + filter.receiverList.app
                                + " (pid=" + filter.receiverList.pid
                                + ", uid=" + filter.receiverList.uid + ")"
                                + " excludes " + excludedPermission
                                + " due to sender " + r.callerPackage
                                + " (uid " + r.callingUid + ")";
                    }
                }
            }
        }

        // Check that the receiver does *not* belong to any of the excluded packages
        if (r.excludedPackages != null && r.excludedPackages.length > 0) {
            if (ArrayUtils.contains(r.excludedPackages, filter.packageName)) {
                return "Skipping delivery of excluded package "
                        + r.intent.toString()
                        + " to " + filter.receiverList.app
                        + " (pid=" + filter.receiverList.pid
                        + ", uid=" + filter.receiverList.uid + ")"
                        + " excludes package " + filter.packageName
                        + " due to sender " + r.callerPackage
                        + " (uid " + r.callingUid + ")";
            }
        }

        // If the broadcast also requires an app op check that as well.
        if (r.appOp != AppOpsManager.OP_NONE
                && mService.getAppOpsManager().noteOpNoThrow(r.appOp,
                filter.receiverList.uid, filter.packageName, filter.featureId,
                "Broadcast delivered to registered receiver " + filter.receiverId)
                != AppOpsManager.MODE_ALLOWED) {
            return "Appop Denial: receiving "
                    + r.intent.toString()
                    + " to " + filter.receiverList.app
                    + " (pid=" + filter.receiverList.pid
                    + ", uid=" + filter.receiverList.uid + ")"
                    + " requires appop " + AppOpsManager.opToName(r.appOp)
                    + " due to sender " + r.callerPackage
                    + " (uid " + r.callingUid + ")";
        }

        // Ensure that broadcasts are only sent to other apps if they are explicitly marked as
        // exported, or are System level broadcasts
        final int originalCallingUid = r.sticky ? r.originalStickyCallingUid : r.callingUid;
        if (!filter.exported && checkComponentPermission(null, r.callingPid,
                originalCallingUid, filter.receiverList.uid, filter.exported)
                != PackageManager.PERMISSION_GRANTED) {
            return "Exported Denial: sending "
                    + r.intent.toString()
                    + ", action: " + r.intent.getAction()
                    + " from " + r.callerPackage
                    + " (uid=" + originalCallingUid + ")"
                    + " due to receiver " + filter.receiverList.app
                    + " (uid " + filter.receiverList.uid + ")"
                    + " not specifying RECEIVER_EXPORTED";
        }

        // If permissions need a review before any of the app components can run, we drop
        // the broadcast and if the calling app is in the foreground and the broadcast is
        // explicit we launch the review UI passing it a pending intent to send the skipped
        // broadcast.
        if (!requestStartTargetPermissionsReviewIfNeededLocked(r, filter.packageName,
                filter.owningUserId)) {
            return "Skipping delivery to " + filter.packageName + " due to permissions review";
        }

        return null;
    }

    private static String broadcastDescription(BroadcastRecord r, ComponentName component) {
        return r.intent.toString()
                + " from " + r.callerPackage + " (pid=" + r.callingPid
                + ", uid=" + r.callingUid + ") to " + component.flattenToShortString();
    }

    private boolean noteOpForManifestReceiver(int appOp, BroadcastRecord r, ResolveInfo info,
            ComponentName component) {
        if (ArrayUtils.isEmpty(info.activityInfo.attributionTags)) {
            return noteOpForManifestReceiverInner(appOp, r, info, component, null);
        } else {
            // Attribution tags provided, noteOp each tag
            for (String tag : info.activityInfo.attributionTags) {
                if (!noteOpForManifestReceiverInner(appOp, r, info, component, tag)) {
                    return false;
                }
            }
            return true;
        }
    }

    private boolean noteOpForManifestReceiverInner(int appOp, BroadcastRecord r, ResolveInfo info,
            ComponentName component, String tag) {
        if (mService.getAppOpsManager().noteOpNoThrow(appOp,
                    info.activityInfo.applicationInfo.uid,
                    info.activityInfo.packageName,
                    tag,
                    "Broadcast delivered to " + info.activityInfo.name)
                != AppOpsManager.MODE_ALLOWED) {
            Slog.w(TAG, "Appop Denial: receiving "
                    + r.intent + " to "
                    + component.flattenToShortString()
                    + " requires appop " + AppOpsManager.opToName(appOp)
                    + " due to sender " + r.callerPackage
                    + " (uid " + r.callingUid + ")");
            return false;
        }
        return true;
    }

    /**
     * Return true if all given permissions are signature-only perms.
     */
    private static boolean isSignaturePerm(String[] perms) {
        if (perms == null) {
            return false;
        }
        IPermissionManager pm = AppGlobals.getPermissionManager();
        for (int i = perms.length-1; i >= 0; i--) {
            try {
                PermissionInfo pi = pm.getPermissionInfo(perms[i], "android", 0);
                if (pi == null) {
                    // a required permission that no package has actually
                    // defined cannot be signature-required.
                    return false;
                }
                if ((pi.protectionLevel & (PermissionInfo.PROTECTION_MASK_BASE
                        | PermissionInfo.PROTECTION_FLAG_PRIVILEGED))
                        != PermissionInfo.PROTECTION_SIGNATURE) {
                    // If this a signature permission and NOT allowed for privileged apps, it
                    // is okay...  otherwise, nope!
                    return false;
                }
            } catch (RemoteException e) {
                return false;
            }
        }
        return true;
    }

    private boolean requestStartTargetPermissionsReviewIfNeededLocked(
            BroadcastRecord receiverRecord, String receivingPackageName,
            final int receivingUserId) {
        if (!mService.getPackageManagerInternal().isPermissionsReviewRequired(
                receivingPackageName, receivingUserId)) {
            return true;
        }

        final boolean callerForeground = receiverRecord.callerApp != null
                ? receiverRecord.callerApp.mState.getSetSchedGroup()
                != ProcessList.SCHED_GROUP_BACKGROUND : true;

        // Show a permission review UI only for explicit broadcast from a foreground app
        if (callerForeground && receiverRecord.intent.getComponent() != null) {
            IIntentSender target = mService.mPendingIntentController.getIntentSender(
                    ActivityManager.INTENT_SENDER_BROADCAST, receiverRecord.callerPackage,
                    receiverRecord.callerFeatureId, receiverRecord.callingUid,
                    receiverRecord.userId, null, null, 0,
                    new Intent[]{receiverRecord.intent},
                    new String[]{receiverRecord.intent.resolveType(mService.mContext
                            .getContentResolver())},
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT
                            | PendingIntent.FLAG_IMMUTABLE, null);

            final Intent intent = new Intent(Intent.ACTION_REVIEW_PERMISSIONS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            intent.putExtra(Intent.EXTRA_PACKAGE_NAME, receivingPackageName);
            intent.putExtra(Intent.EXTRA_INTENT, new IntentSender(target));

            if (DEBUG_PERMISSIONS_REVIEW) {
                Slog.i(TAG, "u" + receivingUserId + " Launching permission review for package "
                        + receivingPackageName);
            }

            mService.mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mService.mContext.startActivityAsUser(intent, new UserHandle(receivingUserId));
                }
            });
        } else {
            Slog.w(TAG, "u" + receivingUserId + " Receiving a broadcast in package"
                    + receivingPackageName + " requires a permissions review");
        }

        return false;
    }

    @Nullable
    private PermissionManager getPermissionManager() {
        if (mPermissionManager == null) {
            mPermissionManager = mService.mContext.getSystemService(PermissionManager.class);
        }
        return mPermissionManager;
    }

    private boolean hasPermissionForDataDelivery(
            @NonNull String permission,
            @NonNull String message,
            @NonNull AttributionSource... attributionSources) {
        final PermissionManager permissionManager = getPermissionManager();
        if (permissionManager == null) {
            return false;
        }

        for (AttributionSource attributionSource : attributionSources) {
            final int permissionCheckResult =
                    permissionManager.checkPermissionForDataDelivery(
                            permission, attributionSource, message);
            if (permissionCheckResult != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }

        return true;
    }

    private AttributionSource[] createAttributionSourcesForResolveInfo(ResolveInfo info) {
        final String[] attributionTags = info.activityInfo.attributionTags;
        if (ArrayUtils.isEmpty(attributionTags)) {
            return new AttributionSource[] {
                    new AttributionSource.Builder(info.activityInfo.applicationInfo.uid)
                            .setPackageName(info.activityInfo.packageName)
                            .build()
            };
        }

        final AttributionSource[] attributionSources =
                new AttributionSource[attributionTags.length];
        for (int i = 0; i < attributionTags.length; i++) {
            attributionSources[i] =
                    new AttributionSource.Builder(info.activityInfo.applicationInfo.uid)
                            .setPackageName(info.activityInfo.packageName)
                            .setAttributionTag(attributionTags[i])
                            .build();
        }
        return attributionSources;
    }
}
